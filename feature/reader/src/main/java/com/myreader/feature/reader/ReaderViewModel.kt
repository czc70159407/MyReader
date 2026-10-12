package com.myreader.feature.reader

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myreader.core.common.DispatcherProvider
import com.myreader.core.data.repo.LibraryRepository
import com.myreader.core.document.Document
import com.myreader.core.document.DocumentEngineRouter
import com.myreader.core.document.DocumentException
import com.myreader.core.document.DocumentViewerProvider
import com.myreader.core.document.OutlineEntry
import com.myreader.core.document.PasswordRequiredException
import com.myreader.core.model.LibraryItem
import com.myreader.core.storage.DocumentStorage
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 打开失败原因：文件/记录缺失、损坏（已标 pageCount=-2）、其他打开异常 */
enum class ReaderFailure { MISSING, CORRUPT, OPEN_FAILED }

/** 阅读器状态机：Loading → Ready / NeedsPassword / Failed（密码确认后经 Loading 重入） */
sealed interface ReaderUiState {

    data object Loading : ReaderUiState

    /** doc 为打开的 native 句柄，ViewModel 复用于配置变更，onCleared 时 close */
    data class Ready(
        val doc: Document,
        val viewer: DocumentViewerProvider,
        val title: String,
        val pageCount: Int,
        val initialPage: Int,
    ) : ReaderUiState

    data class NeedsPassword(val title: String, val wrongPassword: Boolean) : ReaderUiState

    data class Failed(val reason: ReaderFailure, val message: String) : ReaderUiState
}

/**
 * 阅读器 ViewModel：按 documentId 打开文档（引擎经 [DocumentEngineRouter] 路由）+ 密码交互 +
 * 进度回写（防抖 600ms + onPause/onStop/onDispose 保底 flush）+ 加密文档首开兜底
 * （补 pageCount / 补缩略图；`needsPassword` 保留不清——加密标识常驻，见 docs/ui-design.md「导入流程」第 5 条）。
 */
@HiltViewModel
class ReaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: LibraryRepository,
    private val storage: DocumentStorage,
    private val router: DocumentEngineRouter,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    companion object {
        /** 页面静止该时长后才落库；期间翻页重置计时 */
        const val PROGRESS_DEBOUNCE_MS = 600L
    }

    private val docId: Long = checkNotNull(savedStateHandle["documentId"])

    private val _state = MutableStateFlow<ReaderUiState>(ReaderUiState.Loading)
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    /** 当前页（0 基，进度回写事实源）；null = 尚未发生翻页（沿用库中进度，flush 无需写） */
    private val currentPage = MutableStateFlow<Int?>(null)
    private var progressJob: Job? = null

    init {
        openDocument()
    }

    fun onPageChanged(page: Int) {
        if (currentPage.value == page) return
        currentPage.value = page
        progressJob?.cancel()
        progressJob = viewModelScope.launch(dispatchers.io) {
            delay(PROGRESS_DEBOUNCE_MS)
            repository.savePageProgress(docId, page, scrollOffsetY = 0)
        }
    }

    /** onPause/onStop/onDispose 保底：取消防抖立即落库（幂等，重复 flush 只多写同值） */
    fun flushProgress() {
        val page = currentPage.value ?: return
        progressJob?.cancel()
        viewModelScope.launch(dispatchers.io) {
            repository.savePageProgress(docId, page, scrollOffsetY = 0)
        }
    }

    fun submitPassword(password: String) {
        if (password.isEmpty()) return
        openDocument(password)
    }

    /** 大纲懒加载（OutlineSheet 打开时调用）；无大纲/未就绪返回空表 */
    suspend fun loadOutline(): List<OutlineEntry> {
        val doc = (_state.value as? ReaderUiState.Ready)?.doc ?: return emptyList()
        return withContext(dispatchers.io) { doc.outline() }
    }

    private fun openDocument(password: String? = null) {
        viewModelScope.launch {
            _state.value = ReaderUiState.Loading
            val item = repository.getDocument(docId)
            if (item == null) {
                _state.value = ReaderUiState.Failed(ReaderFailure.MISSING, "文档记录不存在，可能已被删除")
                return@launch
            }
            val file = storage.documentFile(item.relPath)
            if (!file.exists()) {
                _state.value = ReaderUiState.Failed(ReaderFailure.MISSING, "文档文件已丢失，可删除记录后重新导入")
                return@launch
            }

            val doc = try {
                withContext(dispatchers.io) {
                    router.engineFor(item.fileType).open(file.absolutePath, item.fileType, password)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PasswordRequiredException) {
                // 加密自愈：标记常驻 true（含修复旧版本被误清的存量记录，幂等）
                try {
                    repository.setNeedsPassword(docId, true)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Exception) {
                    // 标记失败不影响密码交互
                }
                _state.value = ReaderUiState.NeedsPassword(item.title, wrongPassword = password != null)
                return@launch
            } catch (e: DocumentException) {
                // 损坏标记 -2（区别于 -1 未知），书架引导删除/重导，不自动重试（ui-design 错误边界）
                try {
                    repository.setPageCount(docId, -2)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Exception) {
                    // 标记失败可自愈：下次打开重走此路径
                }
                _state.value = ReaderUiState.Failed(ReaderFailure.CORRUPT, "无法读取此文档（可能已损坏）")
                return@launch
            } catch (e: Exception) {
                _state.value = ReaderUiState.Failed(ReaderFailure.OPEN_FAILED, "打开失败：${e.message.orEmpty().ifEmpty { "未知错误" }}")
                return@launch
            }

            try {
                backfillAfterOpen(item, doc)
            } catch (e: CancellationException) {
                doc.close()   // 取消时 Ready 未建立，onCleared 读不到 doc，必须就地释放 native 句柄
                throw e
            }
            _state.value = ReaderUiState.Ready(
                doc = doc,
                viewer = router.viewerFor(item.fileType),
                title = item.title,
                pageCount = doc.pageCount,
                initialPage = item.currentPage.coerceIn(0, (doc.pageCount - 1).coerceAtLeast(0)),
            )
        }
    }

    /** 首开兜底：页数/缩略图与库内记录不一致时补齐（典型：加密文档解密成功后）。
     *  **不清除 `needsPassword`**：文件始终加密（密码不持久化，每次打开仍需输入），该标记即
     *  「加密文档」常驻标识，清掉会让书架/最近条锁角标永久消失（M6 验收用户反馈修复）。
     *  **加密文档不保留内容预览**：清历史缩略图（含旧版本兜底生成的）且不补生成——锁角标卡片
     *  却展示第一页内容违背加密语义（M6 用户反馈修复）。
     * 业务异常吞掉不影响阅读（各项下次打开重走自愈）；CancellationException 必须传播。 */
    private suspend fun backfillAfterOpen(item: LibraryItem, doc: Document) {
        try {
            if (item.pageCount != doc.pageCount) {
                repository.setPageCount(docId, doc.pageCount)
            }
            if (item.needsPassword) {
                if (item.thumbnailRelPath != null) {
                    withContext(dispatchers.io) { storage.deleteThumbnail(item.thumbnailRelPath) }
                    repository.setThumbnail(docId, null)
                }
            } else if (item.thumbnailRelPath == null && doc.pageCount > 0) {
                val thumb = withContext(dispatchers.io) { router.viewerFor(item.fileType).thumbnail(doc) }
                val relPath = saveThumbnailPng(thumb)
                repository.setThumbnail(docId, relPath)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 兜底失败不影响阅读
        }
    }

    /** 缩略图 PNG 保存：filesDir/thumb/<docId>.png，与导入流程同格式（相对 filesDir 的 relPath） */
    private suspend fun saveThumbnailPng(image: ImageBitmap): String {
        val file = File(storage.thumbDir().apply { if (!exists()) mkdirs() }, "$docId.png")
        withContext(dispatchers.io) {
            file.outputStream().use { out ->
                image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }
        return "thumb/$docId.png"
    }

    override fun onCleared() {
        (_state.value as? ReaderUiState.Ready)?.doc?.close()
        super.onCleared()
    }
}

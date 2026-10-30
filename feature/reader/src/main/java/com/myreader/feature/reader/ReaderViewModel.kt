package com.myreader.feature.reader

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myreader.core.common.DispatcherProvider
import com.myreader.core.data.prefs.SettingsPrefs
import com.myreader.core.data.repo.LibraryRepository
import com.myreader.core.document.Document
import com.myreader.core.document.DocumentEngineRouter
import com.myreader.core.document.DocumentException
import com.myreader.core.document.DocumentViewerProvider
import com.myreader.core.document.EpubProgressCodec
import com.myreader.core.document.FileTypes
import com.myreader.core.document.OutlineEntry
import com.myreader.core.document.PasswordRequiredException
import com.myreader.core.document.ProgressPayload
import com.myreader.core.model.Bookmark
import com.myreader.core.model.BookmarkAddResult
import com.myreader.core.model.BookmarkAnchorCodec
import com.myreader.core.model.LibraryItem
import com.myreader.core.model.MAX_BOOKMARKS_PER_DOCUMENT
import com.myreader.core.model.ReaderMode
import com.myreader.core.storage.DocumentStorage
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
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
        val reflowable: Boolean,
        val initialProgress: ProgressPayload,
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
    private val settingsPrefs: SettingsPrefs,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    companion object {
        /** 页面静止该时长后才落库；期间翻页/滚动重置计时 */
        const val PROGRESS_DEBOUNCE_MS = 600L
    }

    private val docId: Long = checkNotNull(savedStateHandle["documentId"])

    private val _state = MutableStateFlow<ReaderUiState>(ReaderUiState.Loading)
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    /**
     * 会话内阅读模式（M7）：初值取自设置偏好（仅首次打开，守卫防密码重试/重入重置），
     * 阅读器内切换是临时态，不回写偏好——避免「打开 A 文档切一下垂直，所有新文档都变垂直」。
     */
    private val _mode = MutableStateFlow(ReaderMode.HORIZONTAL)
    val mode: StateFlow<ReaderMode> = _mode.asStateFlow()
    private var modeInitialized = false

    /** 切换水平/垂直（ReaderScreen 顶栏入口）；当前页经 initialProgress 传递保持 */
    fun toggleMode() {
        _mode.value = if (_mode.value == ReaderMode.HORIZONTAL) ReaderMode.VERTICAL else ReaderMode.HORIZONTAL
    }

    /** 当前进度（回写事实源）：页码式 [ProgressPayload.Page] 或流式 [ProgressPayload.Cfi]；
     *  null = 尚未发生进度回调（沿用库中进度，flush 无需写） */
    private val currentProgress = MutableStateFlow<ProgressPayload?>(null)
    private var progressJob: Job? = null

    /**
     * 实时排版页数（T3）：视图在页数变化时回调上抛（epub 水平模式 relayout 完成），
     * 顶栏分母/跳页弹窗/大纲 clamp 优先取此值；null = 无回调（沿用 Ready.pageCount 打开快照）。
     * 独立流而非更新 Ready——Ready 变化会触发 ReaderScreen 的 key(s) 全套重置（uiProgress 回退）。
     */
    private val _displayPageCount = MutableStateFlow<Int?>(null)
    val displayPageCount: StateFlow<Int?> = _displayPageCount.asStateFlow()

    /** 渲染端页数变化回调（主线程）；相等值幂等忽略 */
    fun onPageCountChanged(count: Int) {
        if (_displayPageCount.value != count) _displayPageCount.value = count
    }

    // ---- 书签（M10-C）----

    /** 书签面板快照（本地 SQLite，Ready 后持续观察；顶栏「已收藏」态与面板共用） */
    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks.asStateFlow()

    private val _bookmarksLoading = MutableStateFlow(false)
    val bookmarksLoading: StateFlow<Boolean> = _bookmarksLoading.asStateFlow()

    private val _bookmarksError = MutableStateFlow(false)
    val bookmarksError: StateFlow<Boolean> = _bookmarksError.asStateFlow()

    /** 一次性提示（添加/重复/上限/删除）：由 ReaderScreen Snackbar 消费 */
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    private var bookmarkJob: Job? = null

    /** Ready 建立后启动书签观察（幂等）；观察流异常时标记错误，面板给「重试」出口 */
    private fun startBookmarkObserver() {
        if (bookmarkJob != null) return
        _bookmarksError.value = false
        _bookmarksLoading.value = true
        bookmarkJob = viewModelScope.launch {
            repository.observeBookmarks(docId)
                .catch {
                    _bookmarksLoading.value = false
                    _bookmarksError.value = true
                    bookmarkJob = null
                }
                .collect {
                    _bookmarks.value = it
                    _bookmarksLoading.value = false
                }
        }
    }

    /** 面板「加载失败」重试：先取消旧观察再重建 */
    fun retryBookmarks() {
        bookmarkJob?.cancel()
        bookmarkJob = null
        startBookmarkObserver()
    }

    /**
     * 以当前位置添加书签：页码式记 page + 垂直偏移；流式写比例锚点 `cp:<chapter>:<章内页>:<章页数>`
     * （M11；payload 无比例分量时退回旧 bookmark 串），page 仅展示与回落。
     * 重复位置与上限由仓储返回业务结果，转 Snackbar（不抛异常）。
     */
    fun addBookmarkAtCurrent() {
        val ready = _state.value as? ReaderUiState.Ready ?: return
        val payload = currentProgress.value ?: ready.initialProgress
        val draft = when (payload) {
            is ProgressPayload.Page -> AddDraft(
                page = payload.page,
                scrollOffsetY = payload.scrollOffsetY,
                anchor = null,
                label = "第 ${payload.page + 1} 页",
            )
            is ProgressPayload.Cfi -> {
                val decoded = EpubProgressCodec.decode(payload.cfi)
                // M11：优先写比例锚点（跨会话/跨排版可还原）；无比例分量时退回旧 bookmark 串
                val anchor = decoded?.let {
                    if (it.hasRatio) BookmarkAnchorCodec.encodeRatio(it.chIdx, it.pageInChap, it.chapPages) else it.loc
                }
                if (decoded == null || anchor == null) {
                    // 无有效锚点（payload 损坏/分量缺失）：不写库——否则会降级成页码式书签污染列表
                    _messages.trySend("当前位置无法添加书签")
                    return
                }
                AddDraft(page = decoded.page, scrollOffsetY = 0, anchor = anchor, label = "第 ${decoded.page + 1} 屏")
            }
        }
        viewModelScope.launch {
            // 写入失败（DB/磁盘）不得让协程异常冒泡崩溃：转 Snackbar（与 LibraryViewModel.launchOp 同口径）
            val message = try {
                when (repository.addBookmark(
                    documentId = docId,
                    page = draft.page,
                    scrollOffsetY = draft.scrollOffsetY,
                    anchor = draft.anchor,
                    label = draft.label,
                )) {
                    BookmarkAddResult.ADDED -> "已添加书签"
                    BookmarkAddResult.DUPLICATE -> "该位置已有书签"
                    BookmarkAddResult.LIMIT_REACHED -> "书签已达上限（$MAX_BOOKMARKS_PER_DOCUMENT 个）"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                "添加书签失败，请重试"
            }
            _messages.send(message)
        }
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch {
            val message = try {
                repository.deleteBookmark(id)
                "已删除书签"
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                "删除书签失败，请重试"
            }
            _messages.send(message)
        }
    }

    private data class AddDraft(
        val page: Int,
        val scrollOffsetY: Int,
        val anchor: String?,
        val label: String,
    )

    init {
        openDocument()
    }

    /** 渲染端进度回调（页码式：页码+垂直偏移；流式 epub：CFI 锚点）。按类型防抖落库。 */
    fun onProgress(payload: ProgressPayload) {
        if (currentProgress.value == payload) return
        currentProgress.value = payload
        progressJob?.cancel()
        progressJob = viewModelScope.launch(dispatchers.io) {
            delay(PROGRESS_DEBOUNCE_MS)
            persistProgress(payload)
        }
    }

    /** onPause/onStop/onDispose 保底：取消防抖立即落库（幂等，重复 flush 只多写同值）。
     * 用 NonCancellable 包裹，防止 onCleared() 取消 viewModelScope 导致最后一段进度丢失（M9 review S1） */
    fun flushProgress() {
        val progress = currentProgress.value ?: return
        progressJob?.cancel()
        viewModelScope.launch {
            withContext(NonCancellable) { persistProgress(progress) }
        }
    }

    /** 按进度类型分发写库：页码式 savePageProgress，流式 saveCfiProgress */
    private suspend fun persistProgress(payload: ProgressPayload) {
        when (payload) {
            is ProgressPayload.Page -> repository.savePageProgress(docId, payload.page, payload.scrollOffsetY)
            is ProgressPayload.Cfi -> repository.saveCfiProgress(docId, payload.cfi)
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
            // 重入打开（如未来的恢复/重试路径）前就地释放上一轮 native 句柄：
            // onCleared 只 close 最终 Ready 态，中间态残留的 doc 无人回收会泄漏 native 内存（M10 review H5）
            (_state.value as? ReaderUiState.Ready)?.doc?.close()
            _state.value = ReaderUiState.Loading
            _displayPageCount.value = null   // 重入打开（密码重试等）= 新会话，上一轮实时页数失效
            val item = repository.getDocument(docId)
            if (item == null) {
                _state.value = ReaderUiState.Failed(ReaderFailure.MISSING, "文档记录不存在，可能已被删除")
                return@launch
            }
            // 会话内模式初值：仅首个打开流程取偏好（守卫防密码重试/重入把用户已切换的模式打回默认）；
            // 读流内已 catch IO，失败回落 HORIZONTAL。流式重排文档（epub）会话初值强制 VERTICAL
            // （重排文档水平分页体验差；顶栏仍可临时切 HORIZONTAL，见 docs/m9-plan.md 决策）。
            if (!modeInitialized) {
                val pref = settingsPrefs.defaultReaderMode.first()
                _mode.value = if (FileTypes.isReflowable(item.fileType)) ReaderMode.VERTICAL else pref
                modeInitialized = true
            }
            val file = storage.documentFile(item.relPath)
            if (!file.exists()) {
                _state.value = ReaderUiState.Failed(ReaderFailure.MISSING, "文档文件已丢失，可删除记录后重新导入")
                return@launch
            }
            // 未注册格式（如跨版本恢复出未来/未知 fileType）：友好降级，不流入 router 抛异常崩溃（M9-E）
            // T6：文案不带 fileType 原始值（copy.md err_unsupported_type「去技术值」）
            if (!FileTypes.isKnown(item.fileType)) {
                _state.value = ReaderUiState.Failed(ReaderFailure.OPEN_FAILED, "暂不支持的文件类型")
                return@launch
            }

            val doc = try {
                withContext(dispatchers.io) {
                    router.engineFor(item.fileType).open(file.absolutePath, item.fileType, password)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PasswordRequiredException) {
                // 加密自愈：标记常驻 true，兜住旧版本误清的存量记录；已为 true（错密码重试路径）不重复写
                if (!item.needsPassword) {
                    try {
                        repository.setNeedsPassword(docId, true)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Exception) {
                        // 标记失败不影响密码交互
                    }
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
            } catch (_: Exception) {
                // T6：异常消息可能含内部路径/类名等实现细节，不进用户文案（copy.md err_open_generic）
                _state.value = ReaderUiState.Failed(ReaderFailure.OPEN_FAILED, "打开失败，请返回书架后重试")
                return@launch
            }

            try {
                backfillAfterOpen(item, doc)
            } catch (e: CancellationException) {
                doc.close()   // 取消时 Ready 未建立，onCleared 读不到 doc，必须就地释放 native 句柄
                throw e
            }
            // 初始进度：流式（epub）优先用库中 CFI 锚点；页码式用 currentPage + scrollOffsetY。
            // epub 的 pageCount 不落库（恒 -1），此处 doc.pageCount 取运行时刻固定排版页数供 UI 展示。
            val initialProgress: ProgressPayload = run {
                val payload = item.progressPayload
                if (item.progressType == "cfi" && !payload.isNullOrBlank()) {
                    ProgressPayload.Cfi(payload)
                } else {
                    ProgressPayload.Page(
                        item.currentPage.coerceIn(0, (doc.pageCount - 1).coerceAtLeast(0)),
                        item.scrollOffsetY.coerceAtLeast(0),
                    )
                }
            }
            _state.value = ReaderUiState.Ready(
                doc = doc,
                viewer = router.viewerFor(item.fileType),
                title = item.title,
                pageCount = doc.pageCount,
                reflowable = doc.reflowable,
                initialProgress = initialProgress,
            )
            // M10-C：Ready 后开始观察书签（顶栏已收藏态 + 面板共用；密码重试产生新 Ready 时幂等复用）
            startBookmarkObserver()
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
            // 流式重排文档（epub）pageCount 随排版变化、不落库：跳过 setPageCount 回写（G9），
            // 避免每次打开反复写 -1/波动值。页码式文档维持原兜底。
            if (!doc.reflowable && item.pageCount != doc.pageCount) {
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

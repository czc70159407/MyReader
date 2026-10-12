package com.myreader.feature.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myreader.core.data.repo.LibraryRepository
import com.myreader.core.model.DocSort
import com.myreader.core.model.Folder
import com.myreader.core.model.LibraryItem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 书架 ViewModel：根目录文档/文件夹流 + SAF 导入编排 + 重复导入确认交互。
 * M3 展示根目录（folderId=null）；目录树导航 M5 引入。
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    repository: LibraryRepository,
    private val importer: DocumentImporter,
) : ViewModel() {

    val folders: StateFlow<List<Folder>> =
        repository.foldersIn(null).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val documents: StateFlow<List<LibraryItem>> =
        repository.docsIn(null, DocSort.TITLE).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing

    /** Snackbar 消息通道 */
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    /** 重复导入确认弹窗状态：uri + 已存在标题；确认后 force 重导 */
    private val _pendingDuplicate = MutableStateFlow<Pair<Uri, String>?>(null)
    val pendingDuplicate: StateFlow<Pair<Uri, String>?> = _pendingDuplicate

    fun importUris(uris: List<Uri>, force: Boolean = false) {
        if (uris.isEmpty() || _importing.value) return
        viewModelScope.launch {
            _importing.value = true
            try {
                var imported = 0
                var passwordMarked = 0
                var rejected = 0
                var failed = 0
                var duplicate: Pair<Uri, String>? = null
                for (uri in uris) {
                    val r = importer.import(uri, targetFolderId = null, force = force)
                    when (r.status) {
                        DocumentImporter.Status.IMPORTED -> imported++
                        DocumentImporter.Status.IMPORTED_PASSWORD_MARKED -> passwordMarked++
                        DocumentImporter.Status.DUPLICATE_SKIPPED -> {
                            duplicate = uri to (r.existingTitle ?: "同名文档")
                        }
                        DocumentImporter.Status.CORRUPT_REJECTED -> rejected++
                        DocumentImporter.Status.FAILED -> failed++
                    }
                }
                val parts = buildList {
                    if (imported > 0) add("导入 $imported 个")
                    if (passwordMarked > 0) add("$passwordMarked 个已加密（打开时需密码）")
                    if (rejected > 0) add("$rejected 个损坏被拒收")
                    if (failed > 0) add("$failed 个失败")
                }
                _messages.send(
                    if (parts.isEmpty()) "未导入任何文档"
                    else parts.joinToString("；")
                )
                // 重复确认：仅单个重复时弹窗（多重复 M5 精化为逐个确认）
                _pendingDuplicate.value = duplicate
            } finally {
                _importing.value = false
            }
        }
    }

    /** 用户确认「仍导入」：对重复 uri 走 force 路径 */
    fun confirmDuplicateImport() {
        val pending = _pendingDuplicate.value ?: return
        _pendingDuplicate.value = null
        importUris(listOf(pending.first), force = true)
    }

    fun dismissDuplicate() {
        _pendingDuplicate.value = null
    }
}

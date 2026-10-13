package com.myreader.feature.library

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myreader.core.data.prefs.LibraryPrefs
import com.myreader.core.data.repo.LibraryRepository
import com.myreader.core.model.DocSort
import com.myreader.core.model.Folder
import com.myreader.core.model.LibraryItem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 书架 ViewModel：目录浏览（M5 路由参数 folderId，null=根）+ 目录/文档管理操作 + SAF 导入编排
 * + M6 排序切换（LibraryPrefs 记忆）/ 全库搜索（防抖 300ms）/ 最近阅读。
 * 全量目录流派生当前目录名；目录树状态（FolderTreeState）供 FolderPickerDialog 选目标。
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: LibraryRepository,
    private val importer: DocumentImporter,
    private val prefs: LibraryPrefs,
) : ViewModel() {

    /** 当前浏览目录（路由参数）：-1 哨兵（NavType.LongType 不允许 nullable）与缺失均视为根 */
    private val folderId: Long? = savedStateHandle.get<Long>("folderId")?.takeIf { it != -1L }
    val isRoot: Boolean = folderId == null

    /** picker 弹窗默认选中项（= 当前浏览目录） */
    val currentFolderId: Long? get() = folderId

    val folders: StateFlow<List<Folder>> =
        repository.foldersIn(folderId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当前目录文档（M6：排序跟随 LibraryPrefs 记忆值，切换即时生效；默认 TITLE） */
    val documents: StateFlow<List<LibraryItem>> =
        prefs.docSort
            .flatMapLatest { sort -> repository.docsIn(folderId, sort) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当前排序（UI 菜单勾选态展示用） */
    val sort: StateFlow<DocSort> =
        prefs.docSort.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DocSort.TITLE)

    /** 切换排序：写偏好即生效（documents 流跟随），重启保持；磁盘故障静默降级（排序不变），不崩溃 */
    fun setSort(sort: DocSort) {
        viewModelScope.launch {
            try {
                prefs.setDocSort(sort)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // DataStore 写失败（IO/磁盘故障）：偏好维持旧值，无副作用可吞
            }
        }
    }

    // ---- 搜索（M6：全库标题搜索，不限当前文件夹）----

    /** 搜索框展开态放 VM（code-review 修正）：此前为 Compose 本地态，从搜索结果进阅读器返回后
     *  本地态复位而 VM 查询存活，出现「结果网格 + 浏览态顶栏」失配且无退出入口 */
    private val _searchOpen = MutableStateFlow(false)
    val searchOpen: StateFlow<Boolean> = _searchOpen

    private val _searchQuery = MutableStateFlow("")
    /** 输入框即时回显（未 trim，避免打字时空格被吃） */
    val searchQuery: StateFlow<String> = _searchQuery

    /**
     * 搜索结果：null = 非搜索态（关键词为空/防抖未到），UI 维持浏览内容；
     * emptyList = 有搜索但无命中（UI 显示「未找到匹配文档」）。两者语义严格区分。
     */
    val searchResults: StateFlow<List<LibraryItem>?> = _searchQuery
        .map { it.trim() }
        .debounce(SEARCH_DEBOUNCE_MS)
        .flatMapLatest { q ->
            if (q.isEmpty()) flowOf(null) else repository.search(q)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun openSearch() {
        _searchOpen.value = true
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    /** 退出搜索：收起输入框并清空关键词 → searchResults 回落 null → UI 恢复浏览态 */
    fun exitSearch() {
        _searchOpen.value = false
        _searchQuery.value = ""
    }

    // ---- 最近阅读（M6：根目录横向卡片条数据源，lastReadAt 降序）----

    /** 仅根目录收集（子目录不显示最近条，VM 层 gate 避免白收流） */
    val recent: StateFlow<List<LibraryItem>> =
        (if (isRoot) repository.recent(RECENT_LIMIT) else flowOf(emptyList()))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val allFolders: StateFlow<List<Folder>> =
        repository.observeAllFolders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 首帧数据加载中：三个数据流首次发射前为 true（书架显示加载占位，避免空态闪现） */
    val initialLoad: StateFlow<Boolean> = combine(folders, documents, allFolders) { _, _, _ -> false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** 面包屑层级（根「书架」之后的祖先目录名链，根→当前有序）；根目录时为空。
     *  当前目录被删除时随全量目录刷新自然回退（列表转空态，不崩溃） */
    val breadcrumb: StateFlow<List<String>> = allFolders.map { all ->
        buildList {
            var cur = folderId?.let { id -> all.find { it.id == id } }
            while (cur != null) {
                add(0, cur.name)
                cur = cur.parentId?.let { pid -> all.find { it.id == pid } }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 目录树状态（FolderPickerDialog 选目标用），懒创建避免无弹窗场景开销 */
    val folderTree: FolderTreeState by lazy { FolderTreeState(repository, viewModelScope) }

    /** 移动文件夹前置校验用：该目录自身 + 全部后代 id（这些目标禁选，防循环） */
    suspend fun subtreeIds(id: Long): Set<Long> = repository.subtree(id).map { it.id }.toSet()

    // ---- 目录 / 文档管理操作 ----

    /** 同父重名校验（folders 表无 UNIQUE(parentId,name)，业务层拦截静默重名） */
    private suspend fun requireUniqueFolder(parentId: Long?, name: String, excludeId: Long? = null) {
        val duplicate = repository.foldersIn(parentId).first().any { it.name == name && it.id != excludeId }
        if (duplicate) throw IllegalArgumentException("该目录下已存在同名文件夹")
    }

    /** 同父文档重名校验 */
    private suspend fun requireUniqueDocTitle(folderId: Long?, title: String, excludeId: Long) {
        val duplicate = repository.docsIn(folderId, DocSort.TITLE).first().any { it.title == title && it.id != excludeId }
        if (duplicate) throw IllegalArgumentException("该目录下已存在同名文档")
    }

    fun createSubFolder(parentId: Long?, name: String) = launchOp {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@launchOp ""
        requireUniqueFolder(parentId, trimmed)
        repository.createFolder(parentId, trimmed)
        "已创建文件夹「$trimmed」"
    }

    /** FolderPickerDialog 内新建子目录：创建成功后回调新 id（picker 自动选中）并展开父级 */
    fun createFolderAndSelect(parentId: Long?, name: String, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            try {
                val trimmed = name.trim()
                if (trimmed.isEmpty()) return@launch
                requireUniqueFolder(parentId, trimmed)
                val id = repository.createFolder(parentId, trimmed)
                if (parentId != null) folderTree.expanded[parentId] = true
                _messages.send("已创建文件夹「$trimmed」")
                onCreated(id)
            } catch (e: IllegalArgumentException) {
                _messages.send(e.message ?: "创建失败")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send("创建失败：${e.message ?: "未知错误"}")
            }
        }
    }

    fun renameFolder(id: Long, name: String) = launchOp {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@launchOp ""
        val folder = repository.getFolder(id) ?: throw IllegalArgumentException("目录不存在: $id")
        requireUniqueFolder(folder.parentId, trimmed, excludeId = id)
        repository.renameFolder(id, trimmed)
        "已重命名文件夹"
    }

    fun deleteFolder(id: Long) = launchOp {
        repository.deleteFolder(id)
        "已删除文件夹（其中文档已移至根目录）"
    }

    fun moveFolder(id: Long, newParentId: Long?) = launchOp {
        val folder = repository.getFolder(id) ?: throw IllegalArgumentException("目录不存在: $id")
        if (folder.parentId == newParentId) return@launchOp "文件夹已在该目录"
        repository.moveFolder(id, newParentId)
        "已移动文件夹"
    }

    fun renameDocument(id: Long, title: String) = launchOp {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return@launchOp ""
        val doc = repository.getDocument(id) ?: throw IllegalArgumentException("文档不存在: $id")
        requireUniqueDocTitle(doc.folderId, trimmed, excludeId = id)
        repository.renameDocument(id, trimmed)
        "已重命名文档"
    }

    fun moveDocument(id: Long, newFolderId: Long?) = launchOp {
        val doc = repository.getDocument(id) ?: throw IllegalArgumentException("文档不存在: $id")
        if (doc.folderId == newFolderId) return@launchOp "文档已在该目录"
        repository.moveDocument(id, newFolderId)
        "已移动文档"
    }

    fun deleteDocument(id: Long) = launchOp {
        repository.deleteDocument(id)
        "已删除文档"
    }

    /** 统一操作编排：成功发消息，业务校验失败（IllegalArgumentException 带中文 message）走 snackbar，不吞取消 */
    private fun launchOp(block: suspend () -> String) {
        viewModelScope.launch {
            try {
                val message = block()
                if (message.isNotEmpty()) _messages.send(message)
            } catch (e: IllegalArgumentException) {
                _messages.send(e.message ?: "操作失败")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send("操作失败：${e.message ?: "未知错误"}")
            }
        }
    }

    // ---- 导入 ----

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing

    /** SAF 选完待确认目标目录的 uris；确认后进 importUris（弹 FolderPickerDialog） */
    private val _pendingImportUris = MutableStateFlow<List<Uri>?>(null)
    val pendingImportUris: StateFlow<List<Uri>?> = _pendingImportUris

    /** SAF 回调入口：先存 uris 弹目录选择（默认当前目录），不直接导入 */
    fun onUrisPicked(uris: List<Uri>) {
        if (uris.isEmpty() || _importing.value) return
        _pendingImportUris.value = uris
    }

    fun cancelImportPick() {
        _pendingImportUris.value = null
    }

    fun confirmImportPick(targetFolderId: Long?) {
        val uris = _pendingImportUris.value ?: return
        _pendingImportUris.value = null
        importUris(uris, targetFolderId)
    }

    /** Snackbar 消息通道 */
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    /** 重复导入确认弹窗状态：uri + 已存在标题；用户决策后经 deferred 唤醒导入循环 */
    private val _pendingDuplicate = MutableStateFlow<Pair<Uri, String>?>(null)
    val pendingDuplicate: StateFlow<Pair<Uri, String>?> = _pendingDuplicate

    private var duplicateDecision: CompletableDeferred<Boolean>? = null

    fun importUris(uris: List<Uri>, targetFolderId: Long?) {
        if (uris.isEmpty() || _importing.value) return
        viewModelScope.launch {
            _importing.value = true
            try {
                var imported = 0
                var passwordMarked = 0
                var rejected = 0
                var unsupported = 0
                var failed = 0
                for (uri in uris) {
                    var r = importer.import(uri, targetFolderId = targetFolderId)
                    if (r.status == DocumentImporter.Status.DUPLICATE_SKIPPED) {
                        // 重复逐个确认（M5）：挂起等待用户决策，确认后对该 uri force 重导
                        val stillImport = awaitDuplicateDecision(uri, r.existingTitle)
                        if (!stillImport) continue
                        r = importer.import(uri, targetFolderId = targetFolderId, force = true)
                    }
                    when (r.status) {
                        DocumentImporter.Status.IMPORTED -> imported++
                        DocumentImporter.Status.IMPORTED_PASSWORD_MARKED -> passwordMarked++
                        DocumentImporter.Status.DUPLICATE_SKIPPED -> Unit
                        DocumentImporter.Status.CORRUPT_REJECTED -> rejected++
                        DocumentImporter.Status.UNSUPPORTED -> unsupported++
                        DocumentImporter.Status.FAILED -> failed++
                    }
                }
                val parts = buildList {
                    if (imported > 0) add("导入 $imported 个")
                    if (passwordMarked > 0) add("$passwordMarked 个已加密（打开时需密码）")
                    if (rejected > 0) add("$rejected 个损坏被拒收")
                    if (unsupported > 0) add("$unsupported 个暂不支持的文件类型")
                    if (failed > 0) add("$failed 个失败")
                }
                _messages.send(
                    if (parts.isEmpty()) "未导入任何文档"
                    else parts.joinToString("；")
                )
            } finally {
                _importing.value = false
            }
        }
    }

    /** 置重复弹窗状态并挂起等待决策（确认=仍导入 / 跳过=false）。decision 必须先于置位就绪，
     *  否则观察者看到弹窗状态立即 confirm 时可能读到 null 而丢失恢复信号 */
    private suspend fun awaitDuplicateDecision(uri: Uri, existingTitle: String?): Boolean {
        val decision = CompletableDeferred<Boolean>()
        duplicateDecision = decision
        _pendingDuplicate.value = uri to (existingTitle ?: "同名文档")
        return decision.await()
    }

    /** 用户确认「仍导入」：唤醒导入循环对该 uri 走 force 重导 */
    fun confirmDuplicateImport() {
        _pendingDuplicate.value = null
        duplicateDecision?.complete(true)
        duplicateDecision = null
    }

    fun dismissDuplicate() {
        _pendingDuplicate.value = null
        duplicateDecision?.complete(false)
        duplicateDecision = null
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
        const val RECENT_LIMIT = 20
    }
}

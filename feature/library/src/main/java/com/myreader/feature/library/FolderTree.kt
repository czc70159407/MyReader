package com.myreader.feature.library

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.myreader.core.data.repo.LibraryRepository
import com.myreader.core.model.Folder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * 目录树 UI 节点：展平列表单元，[depth] 控制缩进层级（docs/ui-design.md「目录树展示」）。
 */
data class FolderNode(
    val id: Long,
    val parentId: Long?,
    val name: String,
    val depth: Int,
    val hasChildren: Boolean,
)

/**
 * 全量目录 → 展平列表（纯函数，便于 JVM 单测）。
 * 根级为 parentId=null，同层按 sortOrder, name 排序（与 foldersIn 查询一致）；
 * 仅 [expandedIds] 中的节点下钻其子级，未展开节点保留（可展开条目）但子级不出现。
 */
fun buildFlatTree(folders: List<Folder>, expandedIds: Set<Long>): List<FolderNode> {
    val byParent = folders.groupBy { it.parentId }
    fun childrenOf(parentId: Long?): List<Folder> =
        byParent[parentId].orEmpty().sortedWith(compareBy({ it.sortOrder }, { it.name }))

    val result = mutableListOf<FolderNode>()
    fun visit(f: Folder, depth: Int) {
        val kids = childrenOf(f.id)
        result += FolderNode(f.id, f.parentId, f.name, depth, kids.isNotEmpty())
        if (f.id in expandedIds) kids.forEach { visit(it, depth + 1) }
    }
    childrenOf(null).forEach { visit(it, 0) }
    return result
}

/**
 * 目录树状态：展开状态 + 展平列表输出。
 * 全量目录 Flow（自动响应增删改）与展开状态 combine 后内存组树；
 * 由 ViewModel 持有（scope 用 viewModelScope），供 FolderPickerDialog 等复用。
 */
class FolderTreeState internal constructor(
    allFolders: Flow<List<Folder>>,
    scope: CoroutineScope,
) {
    /** 常规构造：仓库全表观察（测试可经 internal 构造直接注入 Flow，绕开异步数据源） */
    constructor(repo: LibraryRepository, scope: CoroutineScope) : this(repo.observeAllFolders(), scope)

    /** 展开状态：缺失或 false = 收起；snapshotFlow 响应变化触发组树 */
    val expanded: SnapshotStateMap<Long, Boolean> = mutableStateMapOf()

    /**
     * 全量目录快照（T7）：供「新建/重命名」行内重名预检同步读取。
     * 用 Eagerly 而非 WhileSubscribed——弹窗打开时可能尚无 UI 订阅，`value` 不能是空表。
     */
    val all: StateFlow<List<Folder>> = allFolders.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** 同级重名预检（区分大小写，D7）：命中即弹窗行内提示并禁用主按钮，业务层仍有兜底 */
    fun isNameTaken(parentId: Long?, name: String): Boolean =
        all.value.any { it.parentId == parentId && it.name == name }

    val flat: StateFlow<List<FolderNode>> = combine(
        all,
        snapshotFlow { expanded.toMap() },
    ) { folders, exp ->
        buildFlatTree(folders, exp.filterValues { it }.keys)
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun toggle(id: Long) {
        expanded[id] = !(expanded[id] ?: false)
    }
}

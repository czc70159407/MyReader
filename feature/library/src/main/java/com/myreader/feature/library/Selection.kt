package com.myreader.feature.library

import com.myreader.core.model.BatchDeleteResult
import com.myreader.core.model.BatchMoveResult

/**
 * 批量选择状态（多选态，M13）：文件夹与文档 id 分离保存。id 由 newEntityId 生成、跨表
 * 全局唯一，分离保存仅为动作时免去按视图拆分的歧义。
 */
data class Selection(
    val folderIds: Set<Long> = emptySet(),
    val documentIds: Set<Long> = emptySet(),
) {
    val total: Int get() = folderIds.size + documentIds.size
}

/** Set 切换成员（Kotlin 标准库无此扩展）。 */
internal fun <T> Set<T>.toggle(e: T): Set<T> = if (e in this) this - e else this + e

/** 批量移动成功文案（Snackbar）。 */
internal fun batchMoveMessage(r: BatchMoveResult): String = buildString {
    val parts = buildList {
        if (r.movedDocuments > 0) add("${r.movedDocuments} 个文档")
        if (r.movedFolders > 0) add("${r.movedFolders} 个文件夹")
    }
    if (parts.isEmpty()) {
        append("已在目标目录")
    } else {
        append("已移动 ${parts.joinToString("、")}")
        if (r.skipped > 0) append("（${r.skipped} 个已在目标目录）")
    }
}

/** 批量删除成功文案（Snackbar）；orphanFiles 附加重试说明。 */
internal fun batchDeleteMessage(r: BatchDeleteResult): String = buildString {
    val parts = buildList {
        if (r.deletedDocuments > 0) add("${r.deletedDocuments} 个文档")
        if (r.deletedFolders > 0) add("${r.deletedFolders} 个文件夹")
    }
    append(if (parts.isEmpty()) "未删除任何内容" else "已删除 ${parts.joinToString("、")}")
    if (r.orphanFiles > 0) append("；${r.orphanFiles} 个文件删除失败，请重试")
}

/** 批量删除确认对话框正文（混合选择时两类后果各一句，末句「是否继续？」）。 */
internal fun batchDeleteConfirmMessage(selection: Selection): String = buildString {
    if (selection.documentIds.isNotEmpty()) {
        append("将删除 ${selection.documentIds.size} 个文档，物理文件一并删除。")
    }
    if (selection.folderIds.isNotEmpty()) {
        append("将删除 ${selection.folderIds.size} 个文件夹，其中的文档将移至根目录。")
    }
    append("是否继续？")
}

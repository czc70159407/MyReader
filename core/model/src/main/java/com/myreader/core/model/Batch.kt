package com.myreader.core.model

/** 批量选择上限：与目录导入 maxFiles 对齐，且 < SQLite 单条 IN 绑定上限（999） */
const val MAX_BATCH_SELECTION = 500

/**
 * 批量移动结果：moved* 为实际改父项数；skipped 为「已在目标目录」项数（非失败，不写库）。
 */
data class BatchMoveResult(
    val movedFolders: Int,
    val movedDocuments: Int,
    val skipped: Int,
)

/**
 * 批量删除结果：deleted* 为已删记录数；orphanFiles 为物理文件删除失败残留数
 * （记录已删、文件遗留，属已知限制，UI 提示重试）。文件夹删除时其后代文档回根（不删），
 * 不计入 deletedDocuments。
 */
data class BatchDeleteResult(
    val deletedFolders: Int,
    val deletedDocuments: Int,
    val orphanFiles: Int,
)

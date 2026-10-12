package com.myreader.core.model

/**
 * 书架条目元数据（纯 Kotlin，映射 core:data 的 DocumentEntity，UI 层不直接碰 Entity）。
 */
data class LibraryItem(
    val id: Long,
    val folderId: Long?,
    val title: String,
    val relPath: String,
    val fileType: String,
    val mimeType: String?,
    val sourceUri: String?,
    val fileSize: Long,
    val contentHash: String?,
    val needsPassword: Boolean,
    val pageCount: Int,
    val progressType: String,
    val currentPage: Int,
    val scrollOffsetY: Int,
    val progressPayload: String?,
    val thumbnailRelPath: String?,
    val lastReadAt: Long?,
    val addedAt: Long,
)

package com.myreader.core.model

/**
 * 目录（纯 Kotlin，映射 core:data 的 FolderEntity）。parentId 为 null 表示根级。
 */
data class Folder(
    val id: Long,
    val parentId: Long?,
    val name: String,
    val sortOrder: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

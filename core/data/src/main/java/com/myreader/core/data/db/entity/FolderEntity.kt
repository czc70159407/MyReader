package com.myreader.core.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 目录实体。parentId 自引用（无外键约束——SQLite 自引用 FK 仅约束存在性、不防循环，
 * 删除/移动顺序由业务层 LibraryRepositoryImpl 编排，见「删除目录策略」）。
 */
@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val id: Long,            // ULID/雪花，避免自增冲突便于移动
    val parentId: Long?,                // null = 根
    val name: String,
    val sortOrder: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
)

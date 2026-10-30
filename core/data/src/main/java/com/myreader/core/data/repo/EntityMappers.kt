package com.myreader.core.data.repo

import com.myreader.core.data.db.entity.BookmarkEntity
import com.myreader.core.data.db.entity.DocumentEntity
import com.myreader.core.data.db.entity.FolderEntity
import com.myreader.core.model.Bookmark
import com.myreader.core.model.Folder
import com.myreader.core.model.LibraryItem

/**
 * 实体 → 模型映射（T8 集中点）：feature 层不直接碰 Room Entity，映射变化收敛本文件。
 * 新增字段纪律：Entity 与 Model 同步加字段后，在此补 named-arg，并同步补
 * `EntityMappersTest` 的逐字段断言（快照测试 = 字段穷尽性 checklist）。
 */
internal fun FolderEntity.toModel() = Folder(
    id = id,
    parentId = parentId,
    name = name,
    sortOrder = sortOrder,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun BookmarkEntity.toModel() = Bookmark(
    id = id,
    documentId = documentId,
    page = page,
    scrollOffsetY = scrollOffsetY,
    anchor = anchor,
    label = label,
    createdAt = createdAt,
)

internal fun DocumentEntity.toModel() = LibraryItem(
    id = id,
    folderId = folderId,
    title = title,
    relPath = relPath,
    fileType = fileType,
    mimeType = mimeType,
    sourceUri = sourceUri,
    fileSize = fileSize,
    contentHash = contentHash,
    needsPassword = needsPassword,
    pageCount = pageCount,
    progressType = progressType,
    currentPage = currentPage,
    scrollOffsetY = scrollOffsetY,
    progressPayload = progressPayload,
    thumbnailRelPath = thumbnailRelPath,
    lastReadAt = lastReadAt,
    addedAt = addedAt,
)

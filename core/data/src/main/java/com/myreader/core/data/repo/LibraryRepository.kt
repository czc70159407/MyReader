package com.myreader.core.data.repo

import com.myreader.core.model.DocSort
import com.myreader.core.model.Folder
import com.myreader.core.model.LibraryItem
import kotlinx.coroutines.flow.Flow

/**
 * 书架仓储接口（目录 + 文档编排一体，不单设 FolderRepository）。
 * feature 层依赖此接口而非实现；实体→模型映射在实现内完成，UI 层不碰 Entity。
 */
interface LibraryRepository {

    // ---- 目录 ----
    fun foldersIn(parentId: Long?): Flow<List<Folder>>
    suspend fun subtree(rootId: Long): List<Folder>
    suspend fun createFolder(parentId: Long?, name: String): Long
    suspend fun renameFolder(id: Long, name: String)
    suspend fun moveFolder(id: Long, newParentId: Long?)   // 校验新父目录不在当前子树内（防循环）
    suspend fun deleteFolder(id: Long)                     // 级联：后代文档回根 + 子目录先于父删

    // ---- 文档 ----
    fun docsIn(parentId: Long?, sort: DocSort): Flow<List<LibraryItem>>
    fun search(query: String): Flow<List<LibraryItem>>
    fun recent(limit: Int = 20): Flow<List<LibraryItem>>
    suspend fun getDocument(id: Long): LibraryItem?        // 阅读器按 id 加载
    suspend fun findByHash(hash: String): LibraryItem?      // 重复导入检测

    suspend fun addDocument(
        id: Long? = null,
        folderId: Long?,
        title: String,
        relPath: String,
        fileType: String,
        mimeType: String?,
        sourceUri: String?,
        fileSize: Long,
        contentHash: String?,
        pageCount: Int,
        progressType: String = "page",
        thumbnailRelPath: String? = null,
        needsPassword: Boolean = false,
    ): Long

    suspend fun renameDocument(id: Long, title: String)     // 仅改展示名
    suspend fun moveDocument(id: Long, newFolderId: Long?)
    suspend fun deleteDocument(id: Long)                    // 先事务删记录，提交成功后再删物理文件

    // ---- 进度 ----
    suspend fun savePageProgress(id: Long, page: Int, scrollOffsetY: Int)   // progressType="page"
    suspend fun saveCfiProgress(id: Long, payload: String)                   // progressType="cfi"

    // ---- 状态 ----
    suspend fun setPageCount(id: Long, pageCount: Int)
    suspend fun setNeedsPassword(id: Long, value: Boolean)
    suspend fun setThumbnail(id: Long, relPath: String?)   // 首开兜底补缩略图
}

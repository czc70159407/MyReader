package com.myreader.core.data.repo

import com.myreader.core.model.Annotation
import com.myreader.core.model.AnnotationAddResult
import com.myreader.core.model.AnnotationType
import com.myreader.core.model.BatchDeleteResult
import com.myreader.core.model.BatchMoveResult
import com.myreader.core.model.Bookmark
import com.myreader.core.model.BookmarkAddResult
import com.myreader.core.model.DocSort
import com.myreader.core.model.Folder
import com.myreader.core.model.LibraryItem
import com.myreader.core.model.MAX_BOOKMARKS_PER_DOCUMENT
import kotlinx.coroutines.flow.Flow

/**
 * 书架仓储接口（目录 + 文档编排一体，不单设 FolderRepository）。
 * feature 层依赖此接口而非实现；实体→模型映射在实现内完成，UI 层不碰 Entity。
 */
@Suppress("TooManyFunctions")
interface LibraryRepository {

    // ---- 目录 ----
    fun foldersIn(parentId: Long?): Flow<List<Folder>>
    fun observeAllFolders(): Flow<List<Folder>>   // 全表观察：目录树内存组树 + 当前目录名派生
    suspend fun getFolder(id: Long): Folder?      // 单目录查询（重命名查重需知其 parentId）
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

    @Suppress("LongParameterList")
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

    // ---- 批量操作（M13）----

    /** 批量移动：文件夹 + 文档统一移动到同一目标；已在目标项计 skipped。防环/目标不存在抛 IllegalArgumentException。 */
    suspend fun moveItems(folderIds: List<Long>, documentIds: List<Long>, targetId: Long?): BatchMoveResult

    /** 批量删除：文件夹（后代文档回根、子先父删）与文档（物理删除）混合；记录删于单事务。 */
    suspend fun deleteItems(folderIds: List<Long>, documentIds: List<Long>): BatchDeleteResult

    // ---- 书签（M10）----
    fun observeBookmarks(documentId: Long): Flow<List<Bookmark>>
    suspend fun bookmarksOf(documentId: Long): List<Bookmark>   // 备份/跳转前快照

    /**
     * 新增书签（含双轨去重与每文档上限）：重复位置返回 [BookmarkAddResult.DUPLICATE]，
     * 超出 [MAX_BOOKMARKS_PER_DOCUMENT] 返回 [BookmarkAddResult.LIMIT_REACHED]——均为业务分支，不抛异常。
     */
    suspend fun addBookmark(
        documentId: Long,
        page: Int,
        scrollOffsetY: Int,
        anchor: String?,
        label: String,
    ): BookmarkAddResult

    suspend fun deleteBookmark(id: Long)

    // ---- 高亮/笔记（M12）----
    fun observeAnnotations(documentId: Long): Flow<List<Annotation>>
    suspend fun annotationsOf(documentId: Long): List<Annotation>   // 备份/跳转前快照

    /**
     * 新增高亮/笔记（含同页同归一化 quad 去重与每文档上限）：重复位置返回
     * [AnnotationAddResult.DUPLICATE]，超出 [MAX_ANNOTATIONS_PER_DOCUMENT] 返回
     * [AnnotationAddResult.LIMIT_REACHED]——均为业务分支，不抛异常。
     */
    @Suppress("LongParameterList")
    suspend fun addAnnotation(
        documentId: Long,
        type: AnnotationType,
        page: Int,
        anchor: String,
        color: Int,
        text: String,
        note: String?,
    ): AnnotationAddResult

    suspend fun deleteAnnotation(id: Long)

    /** 更新高亮笔记正文（null/空串即清空） */
    suspend fun updateAnnotationNote(id: Long, note: String?)

    /** 更新高亮颜色（M12 改色） */
    suspend fun updateAnnotationColor(id: Long, color: Int)

    // ---- 进度 ----
    suspend fun savePageProgress(id: Long, page: Int, scrollOffsetY: Int)   // progressType="page"
    suspend fun saveCfiProgress(id: Long, payload: String)                   // progressType="cfi"

    // ---- 状态 ----
    suspend fun setPageCount(id: Long, pageCount: Int)
    suspend fun setNeedsPassword(id: Long, value: Boolean)
    suspend fun setThumbnail(id: Long, relPath: String?)   // 首开兜底补缩略图
}

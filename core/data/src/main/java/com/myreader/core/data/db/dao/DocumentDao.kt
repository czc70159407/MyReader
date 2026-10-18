package com.myreader.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.myreader.core.data.db.entity.DocumentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {

    // Room 无法参数化 ORDER BY：按 DocSort(TITLE/LAST_READ/ADDED/SIZE) 提供具名查询，Repository 按当前偏好分发
    @Query("SELECT * FROM documents WHERE folderId IS :parentId ORDER BY title COLLATE NOCASE")
    fun docsInByTitle(parentId: Long?): Flow<List<DocumentEntity>>          // null = 根

    @Query("SELECT * FROM documents WHERE folderId IS :parentId ORDER BY lastReadAt DESC, title COLLATE NOCASE")
    fun docsInByLastRead(parentId: Long?): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE folderId IS :parentId ORDER BY addedAt DESC, title COLLATE NOCASE")
    fun docsInByAdded(parentId: Long?): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE folderId IS :parentId ORDER BY fileSize DESC, title COLLATE NOCASE")
    fun docsInBySize(parentId: Long?): Flow<List<DocumentEntity>>

    // ESCAPE '\' + 调用方转义（见 LibraryRepositoryImpl.escapeLikePattern），使 %/_ 按字面匹配
    @Query("SELECT * FROM documents WHERE title LIKE '%' || :q || '%' ESCAPE '\\' ORDER BY title COLLATE NOCASE")
    fun search(q: String): Flow<List<DocumentEntity>>       // 全库标题搜索，不限当前文件夹

    @Query("SELECT * FROM documents WHERE contentHash = :hash LIMIT 1")
    suspend fun findByHash(hash: String): DocumentEntity?   // 重复导入检测

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun findById(id: Long): DocumentEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM documents WHERE id = :id)")
    suspend fun exists(id: Long): Boolean   // ID 碰撞预检（retryOnIdCollision）

    @Query("SELECT * FROM documents WHERE lastReadAt IS NOT NULL ORDER BY lastReadAt DESC LIMIT :limit")
    fun recent(limit: Int = 20): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents")
    suspend fun all(): List<DocumentEntity>

    @Insert suspend fun insert(d: DocumentEntity): Long

    @Upsert suspend fun upsert(d: DocumentEntity)

    @Upsert suspend fun upsertAll(docs: List<DocumentEntity>)

    @Query("UPDATE documents SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)             // 仅改展示名，不动 relPath/物理文件

    @Query("UPDATE documents SET needsPassword = :v WHERE id = :id")
    suspend fun setNeedsPassword(id: Long, v: Boolean)

    @Query("UPDATE documents SET currentPage = :page, scrollOffsetY = :offset, lastReadAt = :ts WHERE id = :id")
    suspend fun updateProgress(id: Long, page: Int, offset: Int, ts: Long)   // 页码式（progressType="page"）

    @Query("UPDATE documents SET progressPayload = :payload, lastReadAt = :ts WHERE id = :id")
    suspend fun updateProgressPayload(id: Long, payload: String, ts: Long)  // 流式（progressType="cfi"）

    @Query("UPDATE documents SET pageCount = :n WHERE id = :id")
    suspend fun setPageCount(id: Long, n: Int)

    @Query("UPDATE documents SET thumbnailRelPath = :relPath WHERE id = :id")
    suspend fun setThumbnailRelPath(id: Long, relPath: String?)   // 首开兜底补缩略图（如加密文档解密成功后）

    @Query("UPDATE documents SET folderId = :newFolderId WHERE id = :id")
    suspend fun move(id: Long, newFolderId: Long?)

    @Query("UPDATE documents SET folderId = NULL WHERE folderId IN (:folderIds)")
    suspend fun moveDocsInFoldersToRoot(folderIds: List<Long>)   // 删目录编排：后代文档回根

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun delete(id: Long)
}

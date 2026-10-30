package com.myreader.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.myreader.core.data.db.entity.BookmarkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {

    /** 面板数据源：按页码升序（流式按解析页码），同页多次按时间倒序 */
    @Query("SELECT * FROM bookmarks WHERE documentId = :documentId ORDER BY page, createdAt DESC")
    fun observeByDocument(documentId: Long): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE documentId = :documentId ORDER BY page, createdAt DESC")
    suspend fun byDocument(documentId: Long): List<BookmarkEntity>

    /** 备份导出全量 */
    @Query("SELECT * FROM bookmarks")
    suspend fun all(): List<BookmarkEntity>

    /** 每文档上限校验（S0：≤100） */
    @Query("SELECT COUNT(*) FROM bookmarks WHERE documentId = :documentId")
    suspend fun countByDocument(documentId: Long): Int

    /**
     * 重复位置判定（流式）：只比锚点——重排后同一内容位置的页码会漂移，
     * 用 page 参与判定会漏判（M10-A review）。
     */
    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE documentId = :documentId AND anchor = :anchor)")
    suspend fun existsAtAnchor(documentId: Long, anchor: String): Boolean

    /** 重复位置判定（页码式）：同页即同位置（垂直偏移不参与，符合「同页去重」语义） */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM bookmarks WHERE documentId = :documentId " +
            "AND page = :page AND anchor IS NULL)",
    )
    suspend fun existsAtPage(documentId: Long, page: Int): Boolean

    @Insert
    suspend fun insert(bookmark: BookmarkEntity): Long

    /** 备份恢复批量写入（幂等，按 id upsert） */
    @Upsert
    suspend fun upsertAll(bookmarks: List<BookmarkEntity>)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM bookmarks WHERE id = :id")
    suspend fun findById(id: Long): BookmarkEntity?
}

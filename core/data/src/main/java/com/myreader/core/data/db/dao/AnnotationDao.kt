package com.myreader.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.myreader.core.data.db.entity.AnnotationEntity
import kotlinx.coroutines.flow.Flow

@Dao
@Suppress("TooManyFunctions")
interface AnnotationDao {

    /** 面板数据源：按页码升序，同页按创建时间升序 */
    @Query("SELECT * FROM annotations WHERE documentId = :documentId ORDER BY page, createdAt")
    fun observeByDocument(documentId: Long): Flow<List<AnnotationEntity>>

    @Query("SELECT * FROM annotations WHERE documentId = :documentId ORDER BY page, createdAt")
    suspend fun byDocument(documentId: Long): List<AnnotationEntity>

    /** 备份导出全量 */
    @Query("SELECT * FROM annotations")
    suspend fun all(): List<AnnotationEntity>

    /** 每文档上限校验（S0：≤200） */
    @Query("SELECT COUNT(*) FROM annotations WHERE documentId = :documentId")
    suspend fun countByDocument(documentId: Long): Int

    /** 重复位置判定：同页 + 同归一化 quad 串即同位置 */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM annotations WHERE documentId = :documentId " +
            "AND page = :page AND anchor = :anchor)",
    )
    suspend fun existsAtAnchor(documentId: Long, page: Int, anchor: String): Boolean

    @Insert
    suspend fun insert(annotation: AnnotationEntity): Long

    /** 备份恢复批量写入（幂等，按 id upsert） */
    @Upsert
    suspend fun upsertAll(annotations: List<AnnotationEntity>)

    /** 更新笔记正文（M12-C1）：note 为 null/空串即清空 */
    @Query("UPDATE annotations SET note = :note WHERE id = :id")
    suspend fun updateNote(id: Long, note: String?)

    /** 更新高亮颜色（M12 改色）：色板入口 */
    @Query("UPDATE annotations SET color = :color WHERE id = :id")
    suspend fun updateColor(id: Long, color: Int)

    @Query("DELETE FROM annotations WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM annotations WHERE id = :id")
    suspend fun findById(id: Long): AnnotationEntity?
}

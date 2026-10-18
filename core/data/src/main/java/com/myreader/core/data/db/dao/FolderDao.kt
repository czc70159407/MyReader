package com.myreader.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.myreader.core.data.db.entity.FolderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {

    @Query("SELECT * FROM folders WHERE parentId IS :parentId ORDER BY sortOrder, name")  // 根级传 null，必须 IS（= NULL 恒为假）
    fun children(parentId: Long?): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders")   // 全表观察：目录树内存组树用，排序由内存组装统一处理（sortOrder, name）
    fun observeAll(): Flow<List<FolderEntity>>

    @Query("""
        WITH RECURSIVE d AS (
          SELECT * FROM folders WHERE id = :rootId
          UNION ALL SELECT f.* FROM folders f JOIN d ON f.parentId = d.id
        )
        SELECT * FROM d
    """)
    suspend fun subtree(rootId: Long): List<FolderEntity>   // 移动/删除/循环校验用

    @Query("SELECT * FROM folders")
    suspend fun all(): List<FolderEntity>

    @Insert suspend fun insert(f: FolderEntity): Long

    @Upsert suspend fun upsert(f: FolderEntity)

    @Upsert suspend fun upsertAll(folders: List<FolderEntity>)

    @Update suspend fun rename(f: FolderEntity)

    @Query("UPDATE folders SET parentId = :newParentId, updatedAt = :ts WHERE id = :id")
    suspend fun move(id: Long, newParentId: Long?, ts: Long)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun findById(id: Long): FolderEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM folders WHERE id = :id)")
    suspend fun exists(id: Long): Boolean   // ID 碰撞预检（retryOnIdCollision）
}

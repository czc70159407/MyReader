package com.myreader.core.data.repo

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.myreader.core.common.DispatcherProvider
import com.myreader.core.data.db.MyReaderDatabase
import com.myreader.core.data.db.dao.DocumentDao
import com.myreader.core.data.db.dao.FolderDao
import com.myreader.core.data.db.entity.DocumentEntity
import com.myreader.core.data.db.entity.FolderEntity
import com.myreader.core.model.DocSort
import com.myreader.core.model.Folder
import com.myreader.core.model.LibraryItem
import com.myreader.core.storage.DocumentStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

class LibraryRepositoryImpl @Inject constructor(
    private val db: MyReaderDatabase,
    private val folderDao: FolderDao,
    private val documentDao: DocumentDao,
    private val storage: DocumentStorage,
    private val dispatchers: DispatcherProvider,
) : LibraryRepository {

    // ---- 目录 ----

    override fun foldersIn(parentId: Long?): Flow<List<Folder>> =
        folderDao.children(parentId).map { list -> list.map { it.toModel() } }

    override fun observeAllFolders(): Flow<List<Folder>> =
        folderDao.observeAll().map { list -> list.map { it.toModel() } }

    override suspend fun subtree(rootId: Long): List<Folder> = withContext(dispatchers.io) {
        folderDao.subtree(rootId).map { it.toModel() }
    }

    override suspend fun createFolder(parentId: Long?, name: String): Long = withContext(dispatchers.io) {
        val ts = System.currentTimeMillis()
        retryOnIdCollision { candidate ->
            folderDao.insert(
                FolderEntity(
                    id = candidate,
                    parentId = parentId,
                    name = name,
                    createdAt = ts,
                    updatedAt = ts,
                )
            )
        }
    }

    override suspend fun renameFolder(id: Long, name: String) = withContext(dispatchers.io) {
        val f = folderDao.findById(id) ?: throw IllegalArgumentException("目录不存在: $id")
        folderDao.rename(f.copy(name = name, updatedAt = System.currentTimeMillis()))
        Unit
    }

    override suspend fun moveFolder(id: Long, newParentId: Long?) = withContext(dispatchers.io) {
        require(newParentId != id) { "不能把目录移动到自身" }
        if (newParentId != null) {
            // folders.parentId 无自引用 FK（设计如此），目标存在性必须业务层校验，否则悬空成孤儿
            require(folderDao.findById(newParentId) != null) { "目标目录不存在: $newParentId" }
            val subtreeIds = folderDao.subtree(id).map { it.id }.toSet()
            require(newParentId !in subtreeIds) { "不能把目录移动到自己的子树内" }
        }
        folderDao.move(id, newParentId, System.currentTimeMillis())
    }

    override suspend fun deleteFolder(id: Long) = withContext(dispatchers.io) {
        val subtree = folderDao.subtree(id)
        val ids = subtree.map { it.id }
        // 后序遍历（子目录优先于父目录删除），避免 parentId 悬空成孤儿
        val byParent = subtree.groupBy { it.parentId }
        val deleteOrder = mutableListOf<Long>()
        fun visit(fid: Long) {
            byParent[fid]?.forEach { visit(it.id) }
            deleteOrder += fid
        }
        visit(id)

        db.withTransaction {
            documentDao.moveDocsInFoldersToRoot(ids)   // 后代文档回根（物理文件扁平存储，无需移动）
            deleteOrder.forEach { folderDao.delete(it) }
        }
        Unit
    }

    // ---- 文档 ----

    override fun docsIn(parentId: Long?, sort: DocSort): Flow<List<LibraryItem>> =
        when (sort) {
            DocSort.TITLE -> documentDao.docsInByTitle(parentId)
            DocSort.LAST_READ -> documentDao.docsInByLastRead(parentId)
            DocSort.ADDED -> documentDao.docsInByAdded(parentId)
            DocSort.SIZE -> documentDao.docsInBySize(parentId)
        }.map { list -> list.map { it.toModel() } }

    override fun search(query: String): Flow<List<LibraryItem>> =
        documentDao.search(escapeLikePattern(query)).map { list -> list.map { it.toModel() } }

    override fun recent(limit: Int): Flow<List<LibraryItem>> =
        documentDao.recent(limit).map { list -> list.map { it.toModel() } }

    override suspend fun findByHash(hash: String): LibraryItem? = withContext(dispatchers.io) {
        documentDao.findByHash(hash)?.toModel()
    }

    override suspend fun getDocument(id: Long): LibraryItem? = withContext(dispatchers.io) {
        documentDao.findById(id)?.toModel()
    }

    override suspend fun addDocument(
        id: Long?,
        folderId: Long?,
        title: String,
        relPath: String,
        fileType: String,
        mimeType: String?,
        sourceUri: String?,
        fileSize: Long,
        contentHash: String?,
        pageCount: Int,
        progressType: String,
        thumbnailRelPath: String?,
        needsPassword: Boolean,
    ): Long = withContext(dispatchers.io) {
        val ts = System.currentTimeMillis()
        fun buildEntity(candidate: Long) = DocumentEntity(
            id = candidate,
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
            thumbnailRelPath = thumbnailRelPath,
            lastReadAt = null,
            addedAt = ts,
        )
        // 导入流程已按外部 id 复制物理文件（relPath 含该 id），插入必须沿用同一 id
        if (id != null) {
            documentDao.insert(buildEntity(id))
        } else {
            retryOnIdCollision { candidate -> documentDao.insert(buildEntity(candidate)) }
        }
    }

    override suspend fun renameDocument(id: Long, title: String) = withContext(dispatchers.io) {
        documentDao.rename(id, title)
    }

    override suspend fun moveDocument(id: Long, newFolderId: Long?) = withContext(dispatchers.io) {
        documentDao.move(id, newFolderId)
    }

    override suspend fun deleteDocument(id: Long) = withContext(dispatchers.io) {
        val entity = documentDao.findById(id) ?: return@withContext
        // 先在 DB 事务内删记录，事务提交成功后再删物理文件（DB 失败则文件不动，避免数据丢失）
        db.withTransaction {
            documentDao.delete(id)
        }
        storage.deleteDocument(entity.relPath)
        storage.deleteThumbnail(entity.thumbnailRelPath)
        Unit
    }

    // ---- 进度 ----

    override suspend fun savePageProgress(id: Long, page: Int, scrollOffsetY: Int) =
        withContext(dispatchers.io) {
            documentDao.updateProgress(id, page, scrollOffsetY, System.currentTimeMillis())
        }

    override suspend fun saveCfiProgress(id: Long, payload: String) = withContext(dispatchers.io) {
        documentDao.updateProgressPayload(id, payload, System.currentTimeMillis())
    }

    // ---- 状态 ----

    override suspend fun setPageCount(id: Long, pageCount: Int) = withContext(dispatchers.io) {
        documentDao.setPageCount(id, pageCount)
    }

    override suspend fun setNeedsPassword(id: Long, value: Boolean) = withContext(dispatchers.io) {
        documentDao.setNeedsPassword(id, value)
    }

    override suspend fun setThumbnail(id: Long, relPath: String?) = withContext(dispatchers.io) {
        documentDao.setThumbnailRelPath(id, relPath)
    }
}

// ---- 内部工具 ----

/**
 * 实体 ID：时间戳毫秒左移 20 位 + nanoTime 低 20 位，避免自增冲突便于移动。
 * public：导入流程需先按此 id 复制物理文件（relPath = "<id>.<ext>"）再入库，两者必须同 id。
 */
fun newEntityId(): Long =
    System.currentTimeMillis() shl 20 or (System.nanoTime() and 0xFFFFF)

/**
 * 带 ID 碰撞重试的插入：仅对 PRIMARY KEY 冲突重试（同毫秒+纳秒低位相同，概率 ≈2⁻²⁰），
 * 其他约束异常（如 FK 违规）原样抛出。
 */
internal inline fun retryOnIdCollision(insert: (id: Long) -> Long): Long {
    repeat(3) {
        val candidate = newEntityId()
        try {
            return insert(candidate)
        } catch (e: SQLiteConstraintException) {
            if (!e.message.orEmpty().contains("PRIMARY KEY", ignoreCase = true)) throw e
        }
    }
    throw IllegalStateException("实体 ID 生成连续碰撞")
}

/** LIKE 关键词转义：% 与 _ 按字面匹配（配 DocumentDao.search 的 ESCAPE '\'） */
internal fun escapeLikePattern(s: String): String =
    s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

// ---- 实体 → 模型映射（feature 层不直接碰 Entity）----

internal fun FolderEntity.toModel() = Folder(
    id = id,
    parentId = parentId,
    name = name,
    sortOrder = sortOrder,
    createdAt = createdAt,
    updatedAt = updatedAt,
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

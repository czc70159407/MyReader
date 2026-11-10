package com.myreader.core.data.repo

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.myreader.core.common.DispatcherProvider
import com.myreader.core.data.db.MyReaderDatabase
import com.myreader.core.data.db.dao.AnnotationDao
import com.myreader.core.data.db.dao.BookmarkDao
import com.myreader.core.data.db.dao.DocumentDao
import com.myreader.core.data.db.dao.FolderDao
import com.myreader.core.data.db.entity.AnnotationEntity
import com.myreader.core.data.db.entity.BookmarkEntity
import com.myreader.core.data.db.entity.DocumentEntity
import com.myreader.core.data.db.entity.FolderEntity
import com.myreader.core.model.Annotation
import com.myreader.core.model.AnnotationAddResult
import com.myreader.core.model.AnnotationType
import com.myreader.core.model.BatchDeleteResult
import com.myreader.core.model.BatchMoveResult
import com.myreader.core.model.Bookmark
import com.myreader.core.model.BookmarkAddResult
import com.myreader.core.model.BookmarkAnchorCodec
import com.myreader.core.model.DocSort
import com.myreader.core.model.Folder
import com.myreader.core.model.LibraryItem
import com.myreader.core.model.MAX_ANNOTATIONS_PER_DOCUMENT
import com.myreader.core.model.MAX_BOOKMARKS_PER_DOCUMENT
import com.myreader.core.storage.DocumentStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

@Suppress("TooManyFunctions", "LongParameterList")
class LibraryRepositoryImpl @Inject constructor(
    private val db: MyReaderDatabase,
    private val folderDao: FolderDao,
    private val documentDao: DocumentDao,
    private val bookmarkDao: BookmarkDao,
    private val annotationDao: AnnotationDao,
    private val storage: DocumentStorage,
    private val dispatchers: DispatcherProvider,
) : LibraryRepository {

    // ---- 目录 ----

    override fun foldersIn(parentId: Long?): Flow<List<Folder>> =
        folderDao.children(parentId).map { list -> list.map { it.toModel() } }

    override fun observeAllFolders(): Flow<List<Folder>> =
        folderDao.observeAll().map { list -> list.map { it.toModel() } }

    override suspend fun getFolder(id: Long): Folder? = withContext(dispatchers.io) {
        folderDao.findById(id)?.toModel()
    }

    override suspend fun subtree(rootId: Long): List<Folder> = withContext(dispatchers.io) {
        folderDao.subtree(rootId).map { it.toModel() }
    }

    override suspend fun createFolder(parentId: Long?, name: String): Long = withContext(dispatchers.io) {
        val ts = System.currentTimeMillis()
        retryOnIdCollision(
            exists = folderDao::exists,
            insert = { candidate ->
                folderDao.insert(
                    FolderEntity(
                        id = candidate,
                        parentId = parentId,
                        name = name,
                        createdAt = ts,
                        updatedAt = ts,
                    )
                )
            },
        )
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

        // 后代文档（含所有子目录）一并删除：先事务删记录，提交成功后再删物理文件
        val docs = if (ids.isEmpty()) emptyList() else documentDao.findByFolderIds(ids)
        db.withTransaction {
            if (docs.isNotEmpty()) documentDao.deleteMany(docs.map { it.id })
            deleteOrder.forEach { folderDao.delete(it) }
        }
        deletePhysicalFiles(docs)   // 物理文件后置删，失败留孤儿（不阻断）
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
            retryOnIdCollision(
                exists = documentDao::exists,
                insert = { candidate -> documentDao.insert(buildEntity(candidate)) },
            )
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
        deletePhysicalFiles(listOf(entity))   // 物理文件后置删，失败留孤儿（不阻断）
        Unit
    }

    // ---- 批量操作（M13）----

    override suspend fun moveItems(
        folderIds: List<Long>,
        documentIds: List<Long>,
        targetId: Long?,
    ): BatchMoveResult = withContext(dispatchers.io) {
        if (targetId != null) {
            // folders.parentId 无自引用 FK，目标存在性必须业务层校验（与 moveFolder 一致）
            require(folderDao.findById(targetId) != null) { "目标目录不存在: $targetId" }
            // 防环：目标不得落在任一选中文件夹子树内（与 moveFolder 的 subtree 校验一致）
            val subtreeUnion = folderIds.flatMap { folderDao.subtree(it).map { f -> f.id } }.toSet()
            require(targetId !in subtreeUnion) { "不能把目录移动到自身或其子目录内" }
        }
        val docs = if (documentIds.isEmpty()) emptyList() else documentDao.findByIds(documentIds.distinct())
        val folders = if (folderIds.isEmpty()) emptyList() else folderDao.findByIds(folderIds.distinct())
        val movedDocs = docs.filter { it.folderId != targetId }.map { it.id }
        val movedFolders = folders.filter { it.parentId != targetId }.map { it.id }
        val skipped = (documentIds.size - movedDocs.size) + (folderIds.size - movedFolders.size)

        db.withTransaction {
            if (movedDocs.isNotEmpty()) documentDao.moveMany(movedDocs, targetId)
            if (movedFolders.isNotEmpty()) {
                folderDao.moveMany(movedFolders, targetId, System.currentTimeMillis())
            }
        }
        BatchMoveResult(movedFolders.size, movedDocs.size, skipped)
    }

    @Suppress("CyclomaticComplexMethod")
    override suspend fun deleteItems(
        folderIds: List<Long>,
        documentIds: List<Long>,
    ): BatchDeleteResult = withContext(dispatchers.io) {
        // 文件夹：取选中根各自子树并集（防御性去重），后序删除
        val subtreeEntities = mutableMapOf<Long, FolderEntity>()
        folderIds.distinct().forEach { id ->
            folderDao.subtree(id).forEach { subtreeEntities[it.id] = it }
        }
        val byParent = subtreeEntities.values.groupBy { it.parentId }
        val deleteOrder = mutableListOf<Long>()
        val visited = mutableSetOf<Long>()
        fun visit(fid: Long) {
            if (!visited.add(fid)) return
            byParent[fid]?.forEach { visit(it.id) }
            deleteOrder += fid
        }
        folderIds.distinct().forEach { visit(it) }

        // 直接选中文档 + 选中文件夹子树内的后代文档（去重），一并删除
        val directDocs = if (documentIds.isEmpty()) emptyList() else documentDao.findByIds(documentIds.distinct())
        val folderDocs = if (subtreeEntities.isEmpty()) emptyList()
            else documentDao.findByFolderIds(subtreeEntities.keys.toList())
        val allDocs = (directDocs + folderDocs).distinctBy { it.id }

        db.withTransaction {
            if (allDocs.isNotEmpty()) documentDao.deleteMany(allDocs.map { it.id })
            deleteOrder.forEach { folderDao.delete(it) }
        }
        // 物理文件后置删除（提交成功后）；逐项容错，失败计孤儿
        val orphan = deletePhysicalFiles(allDocs)
        BatchDeleteResult(folderIds.distinct().size, allDocs.size, orphan)
    }

    /**
     * 删除文档物理文件 + 缩略图（逐项容错，失败计孤儿；文件不存在视为成功，幂等）。
     * 与 DB 记录删除解耦：记录已删于事务内，此处失败仅留孤儿，不阻断调用方。
     */
    private fun deletePhysicalFiles(docs: List<DocumentEntity>): Int {
        var orphan = 0
        docs.forEach {
            try {
                if (!storage.deleteDocument(it.relPath)) orphan++
                if (!storage.deleteThumbnail(it.thumbnailRelPath)) orphan++
            } catch (_: Exception) {
                orphan++
            }
        }
        return orphan
    }

    // ---- 书签（M10）----

    override fun observeBookmarks(documentId: Long): Flow<List<Bookmark>> =
        bookmarkDao.observeByDocument(documentId).map { list -> list.map { it.toModel() } }

    override suspend fun bookmarksOf(documentId: Long): List<Bookmark> = withContext(dispatchers.io) {
        bookmarkDao.byDocument(documentId).map { it.toModel() }
    }

    override suspend fun addBookmark(
        documentId: Long,
        page: Int,
        scrollOffsetY: Int,
        anchor: String?,
        label: String,
    ): BookmarkAddResult = withContext(dispatchers.io) {
        // 去重：流式只比锚点（重排后 page 漂移），页码式只比页。
        // M11 比例锚点需 ±1% 容差，DAO 只有精确相等 → 取列表在内存比对（上限 100，成本可忽略）；
        // 旧十进制串与非法锚点维持精确比对语义。
        val duplicate = when (val parsed = BookmarkAnchorCodec.parse(anchor)) {
            is BookmarkAnchorCodec.Anchor.Ratio -> bookmarkDao.byDocument(documentId).any { existing ->
                BookmarkAnchorCodec.isSamePosition(parsed, BookmarkAnchorCodec.parse(existing.anchor))
            }
            else -> if (anchor != null) {
                bookmarkDao.existsAtAnchor(documentId, anchor)
            } else {
                bookmarkDao.existsAtPage(documentId, page)
            }
        }
        if (duplicate) return@withContext BookmarkAddResult.DUPLICATE
        if (bookmarkDao.countByDocument(documentId) >= MAX_BOOKMARKS_PER_DOCUMENT) {
            return@withContext BookmarkAddResult.LIMIT_REACHED
        }
        bookmarkDao.insert(
            BookmarkEntity(
                documentId = documentId,
                page = page,
                scrollOffsetY = scrollOffsetY,
                anchor = anchor,
                label = label,
                createdAt = System.currentTimeMillis(),
            ),
        )
        BookmarkAddResult.ADDED
    }

    override suspend fun deleteBookmark(id: Long) = withContext(dispatchers.io) {
        bookmarkDao.delete(id)
    }

    // ---- 高亮/笔记（M12）----

    override fun observeAnnotations(documentId: Long): Flow<List<Annotation>> =
        annotationDao.observeByDocument(documentId).map { list -> list.map { it.toModel() } }

    override suspend fun annotationsOf(documentId: Long): List<Annotation> = withContext(dispatchers.io) {
        annotationDao.byDocument(documentId).map { it.toModel() }
    }

    override suspend fun addAnnotation(
        documentId: Long,
        type: AnnotationType,
        page: Int,
        anchor: String,
        color: Int,
        text: String,
        note: String?,
    ): AnnotationAddResult = withContext(dispatchers.io) {
        // 去重：同页 + 同归一化 quad 串即同位置
        if (annotationDao.existsAtAnchor(documentId, page, anchor)) {
            return@withContext AnnotationAddResult.DUPLICATE
        }
        if (annotationDao.countByDocument(documentId) >= MAX_ANNOTATIONS_PER_DOCUMENT) {
            return@withContext AnnotationAddResult.LIMIT_REACHED
        }
        annotationDao.insert(
            AnnotationEntity(
                documentId = documentId,
                type = type.name,
                page = page,
                anchor = anchor,
                color = color,
                text = text,
                note = note,
                createdAt = System.currentTimeMillis(),
            ),
        )
        AnnotationAddResult.ADDED
    }

    override suspend fun deleteAnnotation(id: Long) = withContext(dispatchers.io) {
        annotationDao.delete(id)
    }

    override suspend fun updateAnnotationNote(id: Long, note: String?) = withContext(dispatchers.io) {
        annotationDao.updateNote(id, note)
    }

    override suspend fun updateAnnotationColor(id: Long, color: Int) = withContext(dispatchers.io) {
        annotationDao.updateColor(id, color)
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

/** 实体 ID 生成参数：时间戳左移位宽 / 纳秒低位掩码 / 碰撞重试次数。 */
private const val ID_TIME_SHIFT = 20
private const val ID_LOW_BITS_MASK = 0xFFFFFL
private const val ID_COLLISION_RETRY = 3

/**
 * 实体 ID：时间戳毫秒左移 20 位 + nanoTime 低 20 位，避免自增冲突便于移动。
 * public：导入流程需先按此 id 复制物理文件（relPath = "<id>.<ext>"）再入库，两者必须同 id。
 */
fun newEntityId(): Long =
    System.currentTimeMillis() shl ID_TIME_SHIFT or (System.nanoTime() and ID_LOW_BITS_MASK)

/**
 * 带 ID 碰撞重试的插入（同毫秒+纳秒低位相同，概率 ≈2⁻²⁰）。
 *
 * 碰撞判定用**存在性预检**而非约束异常文案：SQLite 对 INTEGER PRIMARY KEY 重复的报错文案
 * 随平台/版本变化（"UNIQUE constraint failed: …" 不一定含 "PRIMARY KEY"），字符串匹配
 * 会把 PK 冲突误判为其他约束而直接抛出（M10 review M4）。仍保留异常路径兜底：
 * 插入后冲突且该 id 确实已存在（并发插入抢先）才重试，FK 等其他约束原样抛出。
 */
internal suspend inline fun retryOnIdCollision(
    exists: suspend (Long) -> Boolean,
    insert: suspend (Long) -> Long,
): Long {
    repeat(ID_COLLISION_RETRY) {
        val candidate = newEntityId()
        if (!exists(candidate)) {
            try {
                return insert(candidate)
            } catch (e: SQLiteConstraintException) {
                if (!exists(candidate)) throw e
            }
        }
    }
    error("实体 ID 生成连续碰撞")
}

/** LIKE 关键词转义：% 与 _ 按字面匹配（配 DocumentDao.search 的 ESCAPE '\'） */
internal fun escapeLikePattern(s: String): String =
    s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

// 实体 → 模型映射已收敛至 EntityMappers.kt（T8）

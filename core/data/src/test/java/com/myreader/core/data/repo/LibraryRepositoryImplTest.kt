package com.myreader.core.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import android.database.sqlite.SQLiteConstraintException
import app.cash.turbine.test
import com.myreader.core.common.DispatcherProvider
import com.myreader.core.data.db.MyReaderDatabase
import com.myreader.core.data.db.entity.DocumentEntity
import com.myreader.core.data.db.entity.FolderEntity
import com.myreader.core.model.BookmarkAddResult
import com.myreader.core.model.DocSort
import com.myreader.core.model.MAX_BOOKMARKS_PER_DOCUMENT
import com.myreader.core.storage.DocumentStorage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LibraryRepositoryImplTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: MyReaderDatabase
    private lateinit var storage: DocumentStorage
    private lateinit var repo: LibraryRepositoryImpl

    private val dispatchers = object : DispatcherProvider {
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MyReaderDatabase::class.java
        ).allowMainThreadQueries().build()
        storage = DocumentStorage(tmp.newFolder("library"))
        repo = LibraryRepositoryImpl(db, db.folderDao(), db.documentDao(), db.bookmarkDao(), storage, dispatchers)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun addDoc(title: String, folderId: Long? = null, hash: String? = null): Long =
        repo.addDocument(
            folderId = folderId, title = title, relPath = "tmp.pdf",
            fileType = "pdf", mimeType = "application/pdf", sourceUri = null,
            fileSize = 1, contentHash = hash, pageCount = 10
        )

    @Test
    fun deleteFolder_cascadesDocsToRoot_childrenBeforeParents() = runTest {
        // 结构：root(1) / a(2) / b(3)，文档在 a 与 b
        val f1 = repo.createFolder(null, "root")
        val f2 = repo.createFolder(f1, "a")
        val f3 = repo.createFolder(f2, "b")
        addDoc("in-a", folderId = f2)
        addDoc("in-b", folderId = f3)

        repo.deleteFolder(f1)

        // 三个目录全部删除，两个文档回根
        assertTrue(repo.subtree(f1).isEmpty())
        repo.docsIn(null, DocSort.TITLE).test {
            val rootDocs = awaitItem()
            assertEquals(setOf("in-a", "in-b"), rootDocs.map { it.title }.toSet())
            assertNull(rootDocs.first { it.title == "in-a" }.folderId)
            assertNull(rootDocs.first { it.title == "in-b" }.folderId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observeAllFolders_mapsEntitiesToModels() = runTest {
        val f1 = repo.createFolder(null, "root")
        val f2 = repo.createFolder(f1, "child")

        repo.observeAllFolders().test {
            val all = awaitItem()
            assertEquals(setOf(f1, f2), all.map { it.id }.toSet())
            assertEquals("root", all.first { it.id == f1 }.name)
            assertEquals(f1, all.first { it.id == f2 }.parentId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun getFolder_returnsModelOrNull() = runTest {
        val f1 = repo.createFolder(null, "root")
        val folder = repo.getFolder(f1)!!
        assertEquals("root", folder.name)
        assertNull(folder.parentId)
        assertNull(repo.getFolder(999_999L))
    }

    @Test
    fun moveFolder_rejectsCycle() = runTest {
        val f1 = repo.createFolder(null, "a")
        val f2 = repo.createFolder(f1, "b")
        val f3 = repo.createFolder(f2, "c")

        // 移到自身
        try {
            repo.moveFolder(f1, f1)
            throw AssertionError("应当拒绝移动到自身")
        } catch (expected: IllegalArgumentException) {
        }

        // 移到自己的后代
        try {
            repo.moveFolder(f1, f3)
            throw AssertionError("应当拒绝移动到自己的子树")
        } catch (expected: IllegalArgumentException) {
        }

        // 合法移动不受影响
        repo.moveFolder(f3, null)
        assertNull(db.folderDao().findById(f3)!!.parentId)
    }

    @Test
    fun moveFolder_rejectsNonexistentParent() = runTest {
        val f1 = repo.createFolder(null, "a")
        try {
            repo.moveFolder(f1, 999_999L)
            throw AssertionError("应当拒绝不存在的目标目录")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun search_escapesLikeWildcards() = runTest {
        addDoc("100%")
        addDoc("100x")
        addDoc("50_off")

        // 含 %/_ 的关键词按字面匹配，不作为通配符
        repo.search("100%").test {
            assertEquals(listOf("100%"), awaitItem().map { it.title })
            cancelAndIgnoreRemainingEvents()
        }
        repo.search("50_o").test {
            assertEquals(listOf("50_off"), awaitItem().map { it.title })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun deleteDocument_removesRecordAndFile() = runTest {
        val result = storage.copyIn(ByteArrayInputStream("data".toByteArray()), docId = 55, ext = "pdf")
        val id = repo.addDocument(
            folderId = null, title = "x", relPath = result.relPath,
            fileType = "pdf", mimeType = null, sourceUri = null,
            fileSize = result.fileSize, contentHash = result.contentHash, pageCount = 1
        )

        repo.deleteDocument(id)

        assertNull(db.documentDao().findById(id))
        assertFalse(storage.documentExists(result.relPath))
    }

    @Test
    fun saveProgress_dispatchesByType_andStampsLastReadAt() = runTest {
        val id = addDoc("p")
        repo.savePageProgress(id, page = 12, scrollOffsetY = 40)
        val after = db.documentDao().findById(id)!!
        assertEquals(12, after.currentPage)
        assertEquals(40, after.scrollOffsetY)
        assertTrue(after.lastReadAt != null && after.lastReadAt!! > 0)

        repo.saveCfiProgress(id, "cfi-value")
        assertEquals("cfi-value", db.documentDao().findById(id)!!.progressPayload)
    }

    @Test
    fun docsIn_dispatchesToSortQueries() = runTest {
        addDoc("b")
        addDoc("a")

        repo.docsIn(null, DocSort.TITLE).test {
            assertEquals(listOf("a", "b"), awaitItem().map { it.title })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun findByHash_returnsItemOrNull() = runTest {
        addDoc("dup", hash = "h1")
        assertEquals("dup", repo.findByHash("h1")!!.title)
        assertNull(repo.findByHash("h2"))
    }

    @Test
    fun addDocument_mapsEntityToModel() = runTest {
        val fid = repo.createFolder(null, "f")
        val id = addDoc("model", folderId = fid, hash = "hh")
        val item = repo.findByHash("hh")!!
        assertEquals(id, item.id)
        assertEquals(fid, item.folderId)
        assertEquals("pdf", item.fileType)
        assertEquals("page", item.progressType)
    }

    @Test
    fun getDocument_returnsItemOrNull() = runTest {
        val id = addDoc("target")
        assertEquals("target", repo.getDocument(id)!!.title)
        assertNull(repo.getDocument(999L))
    }

    @Test
    fun setThumbnail_updatesRelPath() = runTest {
        val id = addDoc("thumb-doc")
        assertNull(repo.getDocument(id)!!.thumbnailRelPath)

        repo.setThumbnail(id, "thumb/$id.png")
        assertEquals("thumb/$id.png", repo.getDocument(id)!!.thumbnailRelPath)
    }

    // ---- ID 碰撞重试（M10 review M4：存在性预检替代约束异常文案匹配）----

    @Test
    fun retryOnIdCollision_retriesWhenInsertedIdAlreadyExists() = runTest {
        var firstAttempt = true

        val newId = retryOnIdCollision(
            exists = db.folderDao()::exists,
            insert = { candidate ->
                val name = if (firstAttempt) "first" else "retried"
                db.folderDao().insert(
                    FolderEntity(id = candidate, parentId = null, name = name, createdAt = 1L, updatedAt = 1L)
                )
                if (firstAttempt) {
                    firstAttempt = false
                    // 模拟并发插入抢先：同一候选 id 二次插入 → SQLiteConstraintException。
                    // SQLite 报「UNIQUE constraint failed: folders.id」，旧实现按 message 匹配
                    // "PRIMARY KEY" 会直接误抛——本用例即回归该缺陷（M10 review M4）
                    db.folderDao().insert(
                        FolderEntity(id = candidate, parentId = null, name = "dup", createdAt = 1L, updatedAt = 1L)
                    )
                }
                candidate
            },
        )

        assertFalse(firstAttempt)
        // 首轮冲突行保留，重试轮以新候选 id 写入
        assertEquals(listOf("first", "retried"), db.folderDao().all().map { it.name }.sorted())
        assertEquals("retried", db.folderDao().findById(newId)!!.name)
    }

    // ---- M10：书签（双轨去重 / 上限 / 级联 / 观察流）----

    @Test
    fun addBookmark_deduplicatesByAnchorForCfi_andByPageForPaged() = runTest {
        val docId = addDoc("bm.pdf")

        assertEquals(
            BookmarkAddResult.ADDED,
            repo.addBookmark(docId, page = 3, scrollOffsetY = 0, anchor = null, label = "第 4 页"),
        )
        // 页码式：同页不同偏移仍判重
        assertEquals(
            BookmarkAddResult.DUPLICATE,
            repo.addBookmark(docId, page = 3, scrollOffsetY = 90, anchor = null, label = "第 4 页"),
        )
        // 流式：同锚点不同页（重排后页码漂移）仍判重
        assertEquals(
            BookmarkAddResult.ADDED,
            repo.addBookmark(docId, page = 5, scrollOffsetY = 0, anchor = "123", label = "第 6 屏"),
        )
        assertEquals(
            BookmarkAddResult.DUPLICATE,
            repo.addBookmark(docId, page = 9, scrollOffsetY = 0, anchor = "123", label = "第 10 屏"),
        )

        assertEquals(2, repo.bookmarksOf(docId).size)
    }

    @Test
    fun addBookmark_deduplicatesRatioAnchorWithinTolerance() = runTest {
        val docId = addDoc("bm.epub")

        // M11 比例锚点：同章比例相同（分母不同）即判重
        assertEquals(
            BookmarkAddResult.ADDED,
            repo.addBookmark(docId, page = 6, scrollOffsetY = 0, anchor = "cp:1:2:4", label = "第 7 屏"),
        )
        assertEquals(
            BookmarkAddResult.DUPLICATE,
            repo.addBookmark(docId, page = 9, scrollOffsetY = 0, anchor = "cp:1:12:20", label = "第 10 屏"),
        )
        // 跨章同比例 → 不同位置
        assertEquals(
            BookmarkAddResult.ADDED,
            repo.addBookmark(docId, page = 10, scrollOffsetY = 0, anchor = "cp:2:2:4", label = "第 11 屏"),
        )
        assertEquals(2, repo.bookmarksOf(docId).size)
    }

    @Test
    fun addBookmark_enforcesPerDocumentLimit() = runTest {
        val docId = addDoc("bm.pdf")

        repeat(MAX_BOOKMARKS_PER_DOCUMENT) { i ->
            assertEquals(
                BookmarkAddResult.ADDED,
                repo.addBookmark(docId, page = i, scrollOffsetY = 0, anchor = null, label = "第 ${i + 1} 页"),
            )
        }
        assertEquals(
            BookmarkAddResult.LIMIT_REACHED,
            repo.addBookmark(docId, page = 999, scrollOffsetY = 0, anchor = null, label = "第 1000 页"),
        )
        assertEquals(MAX_BOOKMARKS_PER_DOCUMENT, repo.bookmarksOf(docId).size)
    }

    @Test
    fun deleteDocument_cascadesBookmarks() = runTest {
        val docId = addDoc("bm.pdf")
        repo.addBookmark(docId, page = 1, scrollOffsetY = 0, anchor = null, label = "第 2 页")

        repo.deleteDocument(docId)

        assertTrue(repo.bookmarksOf(docId).isEmpty())
    }

    @Test
    fun observeBookmarks_emitsCurrentList() = runTest {
        val docId = addDoc("bm.pdf")
        repo.addBookmark(docId, page = 2, scrollOffsetY = 0, anchor = null, label = "第 3 页")

        repo.observeBookmarks(docId).test {
            val list = awaitItem()
            assertEquals(listOf("第 3 页"), list.map { it.label })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun retryOnIdCollision_rethrowsForeignKeyViolation() = runTest {
        // FK 违约必须原样抛出：存在性预检不命中 → 不重试，防止把约束错误吞成无效重试后
        // 以「连续碰撞」的 IllegalStateException 掩盖真实原因
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking {
                retryOnIdCollision(
                    exists = db.documentDao()::exists,
                    insert = { candidate ->
                        db.documentDao().insert(
                            DocumentEntity(
                                id = candidate,
                                folderId = 999_999L,   // 不存在的目录 → FK 违约
                                title = "fk",
                                relPath = "1.pdf",
                                fileType = "pdf",
                                mimeType = null,
                                sourceUri = null,
                                fileSize = 1,
                                thumbnailRelPath = null,
                                lastReadAt = null,
                                addedAt = 1L,
                            )
                        )
                    },
                )
            }
        }
    }
}

package com.myreader.core.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.myreader.core.common.DispatcherProvider
import com.myreader.core.data.db.MyReaderDatabase
import com.myreader.core.model.DocSort
import com.myreader.core.storage.DocumentStorage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        repo = LibraryRepositoryImpl(db, db.folderDao(), db.documentDao(), storage, dispatchers)
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
}

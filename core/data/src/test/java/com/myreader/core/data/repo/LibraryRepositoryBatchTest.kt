package com.myreader.core.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.myreader.core.common.DispatcherProvider
import com.myreader.core.data.db.MyReaderDatabase
import com.myreader.core.model.DocSort
import com.myreader.core.storage.DocumentStorage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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

/** M13 批量操作（moveItems / deleteItems）单测：事务原子、防环、级联、物理文件后置删除。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LibraryRepositoryBatchTest {

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
            MyReaderDatabase::class.java,
        ).allowMainThreadQueries().build()
        storage = DocumentStorage(tmp.newFolder("library"))
        repo = LibraryRepositoryImpl(
            db, db.folderDao(), db.documentDao(), db.bookmarkDao(), db.annotationDao(), storage, dispatchers,
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun addDoc(title: String, folderId: Long? = null, relPath: String = "tmp.pdf"): Long =
        repo.addDocument(
            folderId = folderId, title = title, relPath = relPath,
            fileType = "pdf", mimeType = "application/pdf", sourceUri = null,
            fileSize = 1, contentHash = null, pageCount = 10,
        )

    // ---- 批量移动 ----

    @Test
    fun moveItems_movesDocsAndFolders_reportsSkipped() = runTest {
        val f1 = repo.createFolder(null, "a")
        val f2 = repo.createFolder(null, "b")
        val d1 = addDoc("d1", folderId = null, relPath = "1.pdf")
        val d2 = addDoc("d2", folderId = f1, relPath = "2.pdf")

        val r = repo.moveItems(folderIds = listOf(f1), documentIds = listOf(d1, d2), targetId = f2)

        assertEquals(1, r.movedFolders)
        assertEquals(2, r.movedDocuments)
        assertEquals(0, r.skipped)
        assertEquals(f2, db.folderDao().findById(f1)!!.parentId)
        assertEquals(f2, db.documentDao().findById(d1)!!.folderId)
        assertEquals(f2, db.documentDao().findById(d2)!!.folderId)
        // relPath 不变（移动不改物理文件）
        assertEquals("1.pdf", db.documentDao().findById(d1)!!.relPath)

        // 再次移动到同一目标 → 全部 skipped
        val again = repo.moveItems(folderIds = listOf(f1), documentIds = listOf(d1), targetId = f2)
        assertEquals(0, again.movedFolders)
        assertEquals(0, again.movedDocuments)
        assertEquals(2, again.skipped)
    }

    @Test
    fun moveItems_rejectsTargetInSelectedSubtree() = runTest {
        val f1 = repo.createFolder(null, "a")
        val f2 = repo.createFolder(f1, "b")

        try {
            repo.moveItems(folderIds = listOf(f1), documentIds = emptyList(), targetId = f2)
            throw AssertionError("应当拒绝移动到所选文件夹子树内")
        } catch (expected: IllegalArgumentException) {
        }
        // 零变更
        assertNull(db.folderDao().findById(f1)!!.parentId)
    }

    @Test
    fun moveItems_rejectsNonexistentTarget() = runTest {
        val d1 = addDoc("d1")
        try {
            repo.moveItems(folderIds = emptyList(), documentIds = listOf(d1), targetId = 999_999L)
            throw AssertionError("应当拒绝不存在的目标目录")
        } catch (expected: IllegalArgumentException) {
        }
        assertNull(db.documentDao().findById(d1)!!.folderId)
    }

    @Test
    fun moveItems_emptyLists_noop() = runTest {
        val r = repo.moveItems(folderIds = emptyList(), documentIds = emptyList(), targetId = null)
        assertEquals(0, r.movedFolders)
        assertEquals(0, r.movedDocuments)
        assertEquals(0, r.skipped)
    }

    // ---- 批量删除 ----

    @Test
    fun deleteItems_deletesDocsAndPhysicalFiles() = runTest {
        val rel1 = storage.copyIn(ByteArrayInputStream("data1".toByteArray()), docId = 101, ext = "pdf").relPath
        val rel2 = storage.copyIn(ByteArrayInputStream("data2".toByteArray()), docId = 102, ext = "pdf").relPath
        val d1 = addDoc("d1", relPath = rel1)
        val d2 = addDoc("d2", relPath = rel2)

        val r = repo.deleteItems(folderIds = emptyList(), documentIds = listOf(d1, d2))

        assertEquals(2, r.deletedDocuments)
        assertEquals(0, r.orphanFiles)
        assertNull(db.documentDao().findById(d1))
        assertNull(db.documentDao().findById(d2))
        assertFalse(storage.documentExists(rel1))
        assertFalse(storage.documentExists(rel2))
    }

    @Test
    fun deleteItems_deletesFoldersAndDescendantDocs() = runTest {
        val f1 = repo.createFolder(null, "a")
        val f2 = repo.createFolder(f1, "b")
        val f3 = repo.createFolder(f2, "c")
        addDoc("in-b", folderId = f2)
        addDoc("in-c", folderId = f3)

        val r = repo.deleteItems(folderIds = listOf(f1), documentIds = emptyList())

        assertEquals(1, r.deletedFolders)
        assertEquals(2, r.deletedDocuments)   // 后代文档一并删除
        assertTrue(repo.subtree(f1).isEmpty())
        assertTrue(repo.docsIn(null, DocSort.TITLE).first().isEmpty())
    }

    @Test
    fun deleteItems_mixedFoldersAndDocs() = runTest {
        val rel = storage.copyIn(ByteArrayInputStream("data".toByteArray()), docId = 201, ext = "pdf").relPath
        val f1 = repo.createFolder(null, "a")
        addDoc("inner", folderId = f1)
        val dOut = addDoc("outer", relPath = rel)

        val r = repo.deleteItems(folderIds = listOf(f1), documentIds = listOf(dOut))

        assertEquals(1, r.deletedFolders)
        assertEquals(2, r.deletedDocuments)   // inner（文件夹内）+ outer（直接选中）一并删除
        assertTrue(repo.docsIn(null, DocSort.TITLE).first().isEmpty())
        assertNull(db.documentDao().findById(dOut))
        assertFalse(storage.documentExists(rel))
    }

    @Test
    fun deleteItems_emptyLists_noop() = runTest {
        val r = repo.deleteItems(folderIds = emptyList(), documentIds = emptyList())
        assertEquals(0, r.deletedFolders)
        assertEquals(0, r.deletedDocuments)
        assertEquals(0, r.orphanFiles)
    }
}

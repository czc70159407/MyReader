package com.myreader.core.data.db.dao

import androidx.test.core.app.ApplicationProvider
import androidx.room.Room
import app.cash.turbine.test
import com.myreader.core.data.db.MyReaderDatabase
import com.myreader.core.data.db.entity.DocumentEntity
import com.myreader.core.data.db.entity.FolderEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FolderDaoTest {

    private lateinit var db: MyReaderDatabase
    private lateinit var folderDao: FolderDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MyReaderDatabase::class.java
        ).allowMainThreadQueries().build()
        folderDao = db.folderDao()
    }

    @After
    fun tearDown() = db.close()

    private fun folder(id: Long, parentId: Long?, name: String) = FolderEntity(
        id = id, parentId = parentId, name = name,
        createdAt = 1, updatedAt = 1
    )

    @Test
    fun children_rootUsesIsNull_notEquals() = runTest {
        // 根级查询必须用 IS（= NULL 恒为假的 SQL 三值逻辑坑）
        folderDao.insert(folder(1, null, "root-a"))
        folderDao.insert(folder(2, 1, "child-of-1"))

        folderDao.children(null).test {
            val roots = awaitItem()
            assertEquals(listOf("root-a"), roots.map { it.name })
            cancelAndIgnoreRemainingEvents()
        }
        folderDao.children(1).test {
            val kids = awaitItem()
            assertEquals(listOf("child-of-1"), kids.map { it.name })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun subtree_returnsWholeTree() = runTest {
        folderDao.insert(folder(1, null, "a"))
        folderDao.insert(folder(2, 1, "b"))
        folderDao.insert(folder(3, 2, "c"))
        folderDao.insert(folder(9, null, "other"))

        val sub = folderDao.subtree(1)
        assertEquals(setOf(1L, 2L, 3L), sub.map { it.id }.toSet())
    }

    @Test
    fun move_updatesParent() = runTest {
        folderDao.insert(folder(1, null, "a"))
        folderDao.insert(folder(2, null, "b"))

        folderDao.move(2, 1, ts = 123L)
        assertEquals(1L, folderDao.findById(2)!!.parentId)
        assertEquals(123L, folderDao.findById(2)!!.updatedAt)
    }

    @Test
    fun rename_updatesName() = runTest {
        folderDao.insert(folder(1, null, "old"))
        val f = folderDao.findById(1)!!
        folderDao.rename(f.copy(name = "new"))
        assertEquals("new", folderDao.findById(1)!!.name)
    }

    @Test
    fun delete_removesOnlyTarget() = runTest {
        folderDao.insert(folder(1, null, "a"))
        folderDao.insert(folder(2, 1, "b"))
        folderDao.delete(1)

        assertNull(folderDao.findById(1))
        // 业务层负责级联顺序；DAO 层 delete 只删目标（b 仍存，由 Repository 编排删除）
        assertEquals(2L, folderDao.findById(2)!!.id)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DocumentDaoTest {

    private lateinit var db: MyReaderDatabase
    private lateinit var dao: DocumentDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MyReaderDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.documentDao()
    }

    @After
    fun tearDown() = db.close()

    private fun doc(
        id: Long,
        title: String,
        folderId: Long? = null,
        fileSize: Long = 0,
        addedAt: Long = 0,
        lastReadAt: Long? = null,
        hash: String? = null,
    ) = DocumentEntity(
        id = id, folderId = folderId, title = title, relPath = "$id.pdf",
        fileType = "pdf", mimeType = "application/pdf", sourceUri = null,
        fileSize = fileSize, contentHash = hash, addedAt = addedAt, lastReadAt = lastReadAt,
        thumbnailRelPath = null
    )

    @Test
    fun insertAndQueryByFolder() = runTest {
        db.folderDao().insert(
            FolderEntity(id = 7, parentId = null, name = "f7", createdAt = 1, updatedAt = 1)
        )
        dao.insert(doc(1, "alpha", folderId = null))
        dao.insert(doc(2, "beta", folderId = 7))

        dao.docsInByTitle(null).test {
            assertEquals(listOf("alpha"), awaitItem().map { it.title })
            cancelAndIgnoreRemainingEvents()
        }
        dao.docsInByTitle(7).test {
            assertEquals(listOf("beta"), awaitItem().map { it.title })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun sorting_variants() = runTest {
        dao.insert(doc(1, "b", fileSize = 10, addedAt = 100, lastReadAt = 10))
        dao.insert(doc(2, "a", fileSize = 30, addedAt = 300, lastReadAt = 30))
        dao.insert(doc(3, "c", fileSize = 20, addedAt = 200, lastReadAt = 20))

        dao.docsInByTitle(null).test {
            assertEquals(listOf("a", "b", "c"), awaitItem().map { it.title })
            cancelAndIgnoreRemainingEvents()
        }
        dao.docsInByLastRead(null).test {
            assertEquals(listOf("a", "c", "b"), awaitItem().map { it.title })
            cancelAndIgnoreRemainingEvents()
        }
        dao.docsInByAdded(null).test {
            assertEquals(listOf("a", "c", "b"), awaitItem().map { it.title })
            cancelAndIgnoreRemainingEvents()
        }
        dao.docsInBySize(null).test {
            assertEquals(listOf("a", "c", "b"), awaitItem().map { it.title })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun search_matchesTitleAcrossFolders() = runTest {
        val folderDao = db.folderDao()
        folderDao.insert(FolderEntity(id = 1, parentId = null, name = "f1", createdAt = 1, updatedAt = 1))
        folderDao.insert(FolderEntity(id = 2, parentId = null, name = "f2", createdAt = 1, updatedAt = 1))
        dao.insert(doc(1, "Kotlin in Action", folderId = 1))
        dao.insert(doc(2, "Effective Java", folderId = 2))
        dao.insert(doc(3, "Kotlin Coroutines", folderId = null))

        dao.search("Kotlin").test {
            assertEquals(listOf("Kotlin Coroutines", "Kotlin in Action"), awaitItem().map { it.title })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun findByHash_detectsDuplicate() = runTest {
        dao.insert(doc(1, "a", hash = "abc123"))
        assertEquals(1L, dao.findByHash("abc123")!!.id)
        assertNull(dao.findByHash("nope"))
    }

    @Test
    fun recent_ordersByLastReadAtDesc() = runTest {
        dao.insert(doc(1, "old", lastReadAt = 100))
        dao.insert(doc(2, "new", lastReadAt = 200))
        dao.insert(doc(3, "never", lastReadAt = null))

        dao.recent().test {
            assertEquals(listOf("new", "old"), awaitItem().map { it.title })  // 未读不出现
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun updateProgress_pageAndCfi() = runTest {
        dao.insert(doc(1, "p"))
        dao.updateProgress(1, page = 5, offset = 30, ts = 111L)
        val afterPage = dao.findById(1)!!
        assertEquals(5, afterPage.currentPage)
        assertEquals(30, afterPage.scrollOffsetY)
        assertEquals(111L, afterPage.lastReadAt)

        dao.insert(doc(2, "c", ))
        dao.updateProgressPayload(2, "{\"cfi\":\"x\"}", ts = 222L)
        val afterCfi = dao.findById(2)!!
        assertEquals("{\"cfi\":\"x\"}", afterCfi.progressPayload)
        assertEquals(222L, afterCfi.lastReadAt)
    }

    @Test
    fun moveDocsInFoldersToRoot_sendsDocsToRoot() = runTest {
        val folderDao = db.folderDao()
        folderDao.insert(FolderEntity(id = 5, parentId = null, name = "f5", createdAt = 1, updatedAt = 1))
        folderDao.insert(FolderEntity(id = 6, parentId = null, name = "f6", createdAt = 1, updatedAt = 1))
        dao.insert(doc(1, "a", folderId = 5))
        dao.insert(doc(2, "b", folderId = 6))
        dao.insert(doc(3, "c", folderId = null))

        dao.moveDocsInFoldersToRoot(listOf(5L, 6L))
        assertNull(dao.findById(1)!!.folderId)
        assertNull(dao.findById(2)!!.folderId)
    }

    @Test
    fun rename_delete_setPageCount() = runTest {
        dao.insert(doc(1, "old"))
        dao.rename(1, "new")
        assertEquals("new", dao.findById(1)!!.title)
        // rename 不动 relPath
        assertEquals("1.pdf", dao.findById(1)!!.relPath)

        dao.setPageCount(1, 300)
        assertEquals(300, dao.findById(1)!!.pageCount)

        dao.delete(1)
        assertNull(dao.findById(1))
    }

    @Test
    fun setNeedsPassword_flag() = runTest {
        dao.insert(doc(1, "enc"))
        dao.setNeedsPassword(1, true)
        assertTrue(dao.findById(1)!!.needsPassword)
        dao.setNeedsPassword(1, false)
        assertTrue(!dao.findById(1)!!.needsPassword)
    }
}

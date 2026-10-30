package com.myreader.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.myreader.core.data.db.MyReaderDatabase
import com.myreader.core.data.db.entity.BookmarkEntity
import com.myreader.core.data.db.entity.DocumentEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M10-A 书签 DAO（Robolectric + inMemory Room）：排序 / 上限计数 / 双轨去重 / 级联删除。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BookmarkDaoTest {

    private lateinit var db: MyReaderDatabase
    private lateinit var dao: BookmarkDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MyReaderDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.bookmarkDao()
    }

    @After
    fun tearDown() = db.close()

    private fun doc(id: Long) = DocumentEntity(
        id = id, folderId = null, title = "d$id.pdf", relPath = "$id.pdf", fileType = "pdf",
        mimeType = null, sourceUri = null, fileSize = 1, contentHash = null, needsPassword = false,
        pageCount = 3, progressType = "page", currentPage = 0, scrollOffsetY = 0,
        progressPayload = null, thumbnailRelPath = null, lastReadAt = null, addedAt = 1,
    )

    private fun bookmark(docId: Long, page: Int, createdAt: Long, anchor: String? = null) = BookmarkEntity(
        documentId = docId,
        page = page,
        scrollOffsetY = 0,
        anchor = anchor,
        label = "第 ${page + 1} 页",
        createdAt = createdAt,
    )

    @Test
    fun observe_sortsByPageAscending_thenNewestFirst() = runTest {
        db.documentDao().insert(doc(1))
        dao.insert(bookmark(1, page = 5, createdAt = 10))
        dao.insert(bookmark(1, page = 1, createdAt = 20))
        dao.insert(bookmark(1, page = 5, createdAt = 30))

        val list = dao.observeByDocument(1).first()

        assertEquals(listOf(1, 5, 5), list.map { it.page })
        assertEquals(listOf(20L, 30L, 10L), list.map { it.createdAt })   // 同页时间倒序
    }

    @Test
    fun count_andDualTrackDuplicateChecks() = runTest {
        db.documentDao().insert(doc(1))
        db.documentDao().insert(doc(2))
        dao.insert(bookmark(1, page = 3, createdAt = 1))
        dao.insert(bookmark(1, page = 7, createdAt = 2, anchor = "123"))

        assertEquals(2, dao.countByDocument(1))
        assertEquals(0, dao.countByDocument(2))
        assertTrue(dao.existsAtPage(1, 3))
        assertFalse(dao.existsAtPage(1, 7))          // 7 是流式（anchor 非空），不算页码式命中
        assertTrue(dao.existsAtAnchor(1, "123"))
        assertFalse(dao.existsAtAnchor(1, "456"))
    }

    @Test
    fun deleteDocument_cascadesBookmarks() = runTest {
        db.documentDao().insert(doc(1))
        dao.insert(bookmark(1, page = 3, createdAt = 1))

        db.documentDao().delete(1)

        assertEquals(0, dao.countByDocument(1))
        assertTrue(dao.byDocument(1).isEmpty())
    }

    @Test
    fun delete_removesSingleRow() = runTest {
        db.documentDao().insert(doc(1))
        val id = dao.insert(bookmark(1, page = 3, createdAt = 1))

        dao.delete(id)

        assertTrue(dao.byDocument(1).isEmpty())
    }
}

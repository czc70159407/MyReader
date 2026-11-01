package com.myreader.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.myreader.core.data.db.MyReaderDatabase
import com.myreader.core.data.db.entity.AnnotationEntity
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
 * M12 高亮/笔记 DAO（Robolectric + inMemory Room）：排序 / 上限计数 / 同页同 quad 去重 / 级联删除。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AnnotationDaoTest {

    private lateinit var db: MyReaderDatabase
    private lateinit var dao: AnnotationDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MyReaderDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.annotationDao()
    }

    @After
    fun tearDown() = db.close()

    private fun doc(id: Long) = DocumentEntity(
        id = id, folderId = null, title = "d$id.pdf", relPath = "$id.pdf", fileType = "pdf",
        mimeType = null, sourceUri = null, fileSize = 1, contentHash = null, needsPassword = false,
        pageCount = 3, progressType = "page", currentPage = 0, scrollOffsetY = 0,
        progressPayload = null, thumbnailRelPath = null, lastReadAt = null, addedAt = 1,
    )

    @Suppress("LongParameterList")
    private fun annotation(
        docId: Long,
        page: Int,
        createdAt: Long,
        anchor: String = "[[0,0,1,0,0,0.1,1,0.1]]",
        type: String = "HIGHLIGHT",
        note: String? = null,
    ) = AnnotationEntity(
        documentId = docId,
        type = type,
        page = page,
        anchor = anchor,
        color = -256,
        text = "选中文本",
        note = note,
        createdAt = createdAt,
    )

    @Test
    fun observe_sortsByPageAscending_thenCreatedAtAscending() = runTest {
        db.documentDao().insert(doc(1))
        dao.insert(annotation(1, page = 5, createdAt = 10))
        dao.insert(annotation(1, page = 1, createdAt = 20))
        dao.insert(annotation(1, page = 5, createdAt = 30))

        val list = dao.observeByDocument(1).first()

        assertEquals(listOf(1, 5, 5), list.map { it.page })
        assertEquals(listOf(20L, 10L, 30L), list.map { it.createdAt })   // 同页时间升序
    }

    @Test
    fun count_andDuplicateChecks() = runTest {
        db.documentDao().insert(doc(1))
        db.documentDao().insert(doc(2))
        val anchor = "[[0,0,1,0,0,0.1,1,0.1]]"
        dao.insert(annotation(1, page = 3, createdAt = 1, anchor = anchor))

        assertEquals(1, dao.countByDocument(1))
        assertEquals(0, dao.countByDocument(2))
        assertTrue(dao.existsAtAnchor(1, 3, anchor))
        assertFalse(dao.existsAtAnchor(1, 4, anchor))                            // 不同页不算重复
        assertFalse(dao.existsAtAnchor(1, 3, "[[0,0,0.5,0,0,0.1,0.5,0.1]]"))   // 不同矩形不算重复
    }

    @Test
    fun deleteDocument_cascadesAnnotations() = runTest {
        db.documentDao().insert(doc(1))
        dao.insert(annotation(1, page = 3, createdAt = 1))

        db.documentDao().delete(1)

        assertEquals(0, dao.countByDocument(1))
        assertTrue(dao.byDocument(1).isEmpty())
    }

    @Test
    fun delete_removesSingleRow() = runTest {
        db.documentDao().insert(doc(1))
        val id = dao.insert(annotation(1, page = 3, createdAt = 1))

        dao.delete(id)

        assertTrue(dao.byDocument(1).isEmpty())
    }

    @Test
    fun noteType_storesNoteText() = runTest {
        db.documentDao().insert(doc(1))
        dao.insert(annotation(1, page = 0, createdAt = 1, type = "NOTE", note = "我的笔记"))

        val saved = dao.byDocument(1).single()
        assertEquals("NOTE", saved.type)
        assertEquals("我的笔记", saved.note)
    }
}

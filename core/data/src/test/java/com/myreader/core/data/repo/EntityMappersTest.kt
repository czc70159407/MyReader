package com.myreader.core.data.repo

import com.myreader.core.data.db.entity.DocumentEntity
import com.myreader.core.data.db.entity.FolderEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 实体 → 模型映射快照测试（T8）：全字段 named-args 构造 + 逐字段断言。
 * 价值 = 字段穷尽性 checklist：Entity/Model 新增字段时按测试失败提示到 EntityMappers 补映射，
 * 防止「映射静默漏字段」（手写映射无编译期穷尽检查的兜底）。
 */
class EntityMappersTest {

    @Test
    fun folderEntity_toModel_preservesEveryField() {
        val entity = FolderEntity(
            id = 1L,
            parentId = 2L,
            name = "A",
            sortOrder = 3,
            createdAt = 4L,
            updatedAt = 5L,
        )
        val model = entity.toModel()
        assertEquals(1L, model.id)
        assertEquals(2L, model.parentId)
        assertEquals("A", model.name)
        assertEquals(3, model.sortOrder)
        assertEquals(4L, model.createdAt)
        assertEquals(5L, model.updatedAt)
    }

    @Test
    fun documentEntity_toModel_preservesEveryField() {
        val entity = DocumentEntity(
            id = 1L,
            folderId = 2L,
            title = "t",
            relPath = "1.pdf",
            fileType = "pdf",
            mimeType = "application/pdf",
            sourceUri = "content://x",
            fileSize = 8L,
            contentHash = "abc",
            needsPassword = true,
            pageCount = -2,
            progressType = "cfi",
            currentPage = 4,
            scrollOffsetY = 609,
            progressPayload = "{\"v\":1}",
            thumbnailRelPath = "thumb/1.png",
            lastReadAt = 20L,
            addedAt = 10L,
        )
        val item = entity.toModel()
        assertEquals(1L, item.id)
        assertEquals(2L, item.folderId)
        assertEquals("t", item.title)
        assertEquals("1.pdf", item.relPath)
        assertEquals("pdf", item.fileType)
        assertEquals("application/pdf", item.mimeType)
        assertEquals("content://x", item.sourceUri)
        assertEquals(8L, item.fileSize)
        assertEquals("abc", item.contentHash)
        assertEquals(true, item.needsPassword)
        assertEquals(-2, item.pageCount)
        assertEquals("cfi", item.progressType)
        assertEquals(4, item.currentPage)
        assertEquals(609, item.scrollOffsetY)
        assertEquals("{\"v\":1}", item.progressPayload)
        assertEquals("thumb/1.png", item.thumbnailRelPath)
        assertEquals(20L, item.lastReadAt)
        assertEquals(10L, item.addedAt)
    }
}

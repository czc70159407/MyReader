package com.myreader.core.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FileTypes 归一化 / mime 白名单 / magic / 进度类型 纯逻辑测试（JVM）。 */
class FileTypesTest {

    @Test
    fun `fromExtension 大小写不敏感 且 oxps 归并 xps`() {
        assertEquals(FileTypes.PDF, FileTypes.fromExtension("PDF"))
        assertEquals(FileTypes.EPUB, FileTypes.fromExtension("epub"))
        assertEquals(FileTypes.CBZ, FileTypes.fromExtension("cbz"))
        assertEquals(FileTypes.XPS, FileTypes.fromExtension("xps"))
        assertEquals(FileTypes.XPS, FileTypes.fromExtension("OXPS"))   // 归并
        assertEquals(FileTypes.TXT, FileTypes.fromExtension("txt"))
    }

    @Test
    fun `fromExtension 未知返回 null`() {
        assertNull(FileTypes.fromExtension("docx"))
        assertNull(FileTypes.fromExtension("mobi"))
        assertNull(FileTypes.fromExtension(""))
    }

    @Test
    fun `fromMime 白名单映射`() {
        assertEquals(FileTypes.PDF, FileTypes.fromMime("application/pdf"))
        assertEquals(FileTypes.EPUB, FileTypes.fromMime("application/epub+zip"))
        assertEquals(FileTypes.CBZ, FileTypes.fromMime("application/x-cbz"))
        assertEquals(FileTypes.CBZ, FileTypes.fromMime("application/vnd.comicbook+zip"))
        assertEquals(FileTypes.XPS, FileTypes.fromMime("application/oxps"))
        assertEquals(FileTypes.XPS, FileTypes.fromMime("application/vnd.ms-xpsdocument"))
        assertEquals(FileTypes.TXT, FileTypes.fromMime("text/plain"))
        assertNull(FileTypes.fromMime("application/octet-stream"))
        assertNull(FileTypes.fromMime(null))
    }

    @Test
    fun `normalize 扩展名优先 mime 兜底`() {
        // 显示名后缀优先
        assertEquals(FileTypes.EPUB, FileTypes.normalize("book.epub", "application/octet-stream"))
        // 无后缀时回退 mime
        assertEquals(FileTypes.XPS, FileTypes.normalize("doc", "application/oxps"))
        // 后缀 + mime 均未知 → null（导入期拒收）
        assertNull(FileTypes.normalize("evil.docx", "application/msword"))
        assertNull(FileTypes.normalize("evil.docx", null))
        assertNull(FileTypes.normalize(null, "application/octet-stream"))
    }

    @Test
    fun `ALL 含全部已注册格式`() {
        assertEquals(setOf("pdf", "epub", "cbz", "xps", "txt"), FileTypes.ALL)
        FileTypes.ALL.forEach { assertTrue(FileTypes.isKnown(it)) }
        assertFalse(FileTypes.isKnown("docx"))
    }

    @Test
    fun `epub 为流式 默认进度 cfi 其余 page`() {
        assertTrue(FileTypes.isReflowable(FileTypes.EPUB))
        assertFalse(FileTypes.isReflowable(FileTypes.PDF))
        assertFalse(FileTypes.isReflowable(FileTypes.CBZ))
        assertFalse(FileTypes.isReflowable(FileTypes.XPS))
        assertFalse(FileTypes.isReflowable(FileTypes.TXT))
        assertEquals("cfi", FileTypes.defaultProgressType(FileTypes.EPUB))
        assertEquals("page", FileTypes.defaultProgressType(FileTypes.PDF))
        assertEquals("page", FileTypes.defaultProgressType(FileTypes.CBZ))
        assertEquals("page", FileTypes.defaultProgressType(FileTypes.XPS))
        assertEquals("page", FileTypes.defaultProgressType(FileTypes.TXT))
    }

    @Test
    fun `mimeFilters 含全部白名单且首项为 pdf`() {
        val mimes = FileTypes.mimeFilters()
        assertEquals(7, mimes.size)
        assertEquals("application/pdf", mimes[0])
        assertTrue("application/epub+zip" in mimes)
        assertTrue("application/x-cbz" in mimes)
        assertTrue("application/vnd.comicbook+zip" in mimes)
        assertTrue("application/oxps" in mimes)
        assertTrue("application/vnd.ms-xpsdocument" in mimes)
        assertTrue("text/plain" in mimes)   // M9-F：.txt
        // 暂不放行 octet-stream（保持选择器干净）
        assertFalse("application/octet-stream" in mimes)
    }

    @Test
    fun `magicOf 各格式返回对应打开 magic`() {
        assertEquals("application/pdf", FileTypes.magicOf(FileTypes.PDF))
        assertEquals("application/epub+zip", FileTypes.magicOf(FileTypes.EPUB))
        assertEquals("application/x-cbz", FileTypes.magicOf(FileTypes.CBZ))
        assertEquals("application/oxps", FileTypes.magicOf(FileTypes.XPS))
        // 未知回落 pdf
        assertEquals("application/pdf", FileTypes.magicOf("unknown"))
    }
}

package com.myreader.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException

class DocumentStorageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun storage() = DocumentStorage(tmp.newFolder("library"))

    @Test
    fun copyIn_copiesWithSizeAndSha256() {
        val s = storage()
        val content = "hello myreader".toByteArray()
        val result = s.copyIn(ByteArrayInputStream(content), docId = 42, ext = "pdf")

        assertEquals("42.pdf", result.relPath)
        assertEquals(content.size.toLong(), result.fileSize)
        assertEquals(64, result.contentHash.length)   // SHA-256 十六进制 64 字符
        assertTrue(s.documentExists("42.pdf"))

        // 确定性：相同内容 → 相同 hash
        val again = s.copyIn(ByteArrayInputStream(content), docId = 43, ext = "pdf")
        assertEquals(result.contentHash, again.contentHash)
    }

    @Test
    fun copyIn_writesExactBytes() {
        val s = storage()
        val content = ByteArray(100_000) { (it % 256).toByte() }
        val result = s.copyIn(ByteArrayInputStream(content), docId = 7, ext = "epub")

        assertEquals(100_000L, result.fileSize)
        val written = s.documentFile(result.relPath).readBytes()
        assertTrue(content.contentEquals(written))
    }

    @Test
    fun copyIn_failureRollsBackPartialFile() {
        val s = storage()
        val failing = object : java.io.InputStream() {
            private var reads = 0
            override fun read(): Int = throw IOException("boom")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (reads++ > 2) throw IOException("boom")
                b[off] = 1
                return 1
            }
        }

        try {
            s.copyIn(failing, docId = 99, ext = "pdf")
            throw AssertionError("应当抛出 IOException")
        } catch (expected: IOException) {
            // 半成品必须已删除
            assertFalse(s.documentExists("99.pdf"))
        }
    }

    @Test
    fun deleteDocument_removesFileAndIsIdempotent() {
        val s = storage()
        val result = s.copyIn(ByteArrayInputStream("x".toByteArray()), docId = 1, ext = "pdf")

        assertTrue(s.deleteDocument(result.relPath))
        assertFalse(s.documentExists(result.relPath))
        // 幂等：文件已不存在仍返回 true
        assertTrue(s.deleteDocument(result.relPath))
    }

    @Test
    fun deleteThumbnail_handlesNullAndMissing() {
        val s = storage()
        assertTrue(s.deleteThumbnail(null))
        assertTrue(s.deleteThumbnail("thumb/no-such.png"))
    }

    @Test
    fun hasSpaceFor_smallRequestAlwaysFits() {
        val s = storage()
        assertTrue(s.hasSpaceFor(1))
        assertFalse(s.hasSpaceFor(Long.MAX_VALUE / 2))
    }

    @Test
    fun copyIn_rejectsTraversalExt() {
        val s = storage()
        // 恶意扩展名含路径分隔符/穿越序列 → 拒绝，不产生任何文件
        listOf("pdf/../../x", "../evil", "a/b", "pdf ").forEach { ext ->
            try {
                s.copyIn(ByteArrayInputStream("x".toByteArray()), docId = 1, ext = ext)
                throw AssertionError("应当拒绝非法扩展名: $ext")
            } catch (expected: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun pathOps_rejectTraversalRelPath() {
        val s = storage()
        try {
            s.deleteDocument("../outside.pdf")
            throw AssertionError("应当拒绝越界文档路径")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            s.deleteThumbnail("../../outside.png")
            throw AssertionError("应当拒绝越界缩略图路径")
        } catch (expected: IllegalArgumentException) {
        }
    }
}

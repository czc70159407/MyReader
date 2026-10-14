package com.myreader.feature.viewertxt

import com.myreader.core.document.CorruptDocumentException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.Charset

/**
 * TxtDocumentEngine 边界测试（M9 review S1/S2）：BOM 剥离、非 UTF-8 编码回退、体积闸门、空文件。
 */
class TxtDocumentEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val engine = TxtDocumentEngine()

    @Test
    fun utf8_with_bom_strips_bom() {
        val f = tmp.newFile("b.txt")
        f.writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())) // UTF-8 BOM
        f.appendText("标题abc")
        val doc = engine.open(f.absolutePath, "txt", null) as TxtDocument
        assertEquals("标题abc", doc.pageText(0))
    }

    @Test
    fun gbk_decoded_without_replacement() {
        val f = tmp.newFile("g.txt")
        f.writeBytes("中文测试".toByteArray(Charset.forName("GBK")))
        val doc = engine.open(f.absolutePath, "txt", null) as TxtDocument
        assertEquals("中文测试", doc.pageText(0))
    }

    @Test
    fun too_large_rejected_to_avoid_oom() {
        val f = tmp.newFile("big.txt")
        f.outputStream().use { out ->
            val chunk = ByteArray(1024 * 1024) { 0x41 } // 'A'
            repeat(17) { out.write(chunk) } // >16MB
        }
        assertThrows(CorruptDocumentException::class.java) {
            engine.open(f.absolutePath, "txt", null)
        }
    }

    @Test
    fun empty_file_has_at_least_one_page() {
        val f = tmp.newFile("e.txt")
        f.writeText("")
        val doc = engine.open(f.absolutePath, "txt", null) as TxtDocument
        assertTrue(doc.pageCount >= 1)
    }

    @Test
    fun crlf_text_preserves_content() {
        val f = tmp.newFile("c.txt")
        f.writeText("line1\r\nline2\r\nline3")
        val doc = engine.open(f.absolutePath, "txt", null) as TxtDocument
        assertEquals("line1\r\nline2\r\nline3", doc.pageText(0))
    }
}

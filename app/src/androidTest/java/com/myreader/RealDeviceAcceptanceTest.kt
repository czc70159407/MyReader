package com.myreader

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myreader.feature.library.DocumentImporter
import dagger.hilt.android.EntryPointAccessors
import com.myreader.feature.viewertxt.TxtDocumentEngine
import java.io.File
import java.nio.charset.Charset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M9 review 真机验收（业务层端到端，绕过 SAF UI）：
 *  - txtGbk_importDecodesWithoutReplacement：GBK 编码 txt 经完整导入链路入库且解码无乱码（无 U+FFFD）
 *  - unsupportedDocx_importRejected：未注册格式被拒收且不入库（fileType 白名单防线）
 *  - backupRestore_roundTripKeepsDocuments：导出→恢复后追踪文档仍在，且无未知 fileType 流入
 * 运行：`.\gradlew.bat :app:connectedDebugAndroidTest`
 */
@RunWith(AndroidJUnit4::class)
class RealDeviceAcceptanceTest {

    private lateinit var ctx: Context
    private lateinit var entry: AcceptanceEntryPoint

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        entry = EntryPointAccessors.fromApplication(ctx, AcceptanceEntryPoint::class.java)
    }

    private fun testUri(name: String) = Uri.parse("content://com.myreader.testfiles/$name")

    @Test
    fun txtGbk_importDecodesWithoutReplacement() = runBlocking {
        val gbk = "中文GBK测试文本\n第二行：MyReader 多格式验收用例\n第三行内容用于翻页验证"
            .toByteArray(Charset.forName("GBK"))
        File(ctx.cacheDir, "gbk_test.txt").writeBytes(gbk)

        val res = entry.documentImporter().import(testUri("gbk_test.txt"))
        assertEquals("GBK txt 应成功导入", DocumentImporter.Status.IMPORTED, res.status)
        val id = res.documentId ?: error("导入未返回 documentId")

        val entity = entry.database().documentDao().findById(id)!!
        assertEquals("入库 fileType 应为 txt", "txt", entity.fileType)

        // 真机解码：经 TxtDocumentEngine 打开物理文件，断言中文无乱码、无替换字符
        val path = entry.documentStorage().documentFile(entity.relPath).absolutePath
        val doc = TxtDocumentEngine().open(path, "txt", null)
        try {
            val pageText = doc.javaClass.getMethod("pageText", Int::class.javaPrimitiveType)
            val full = (0 until doc.pageCount).joinToString("\n") {
                pageText.invoke(doc, it) as String
            }
            assertTrue("解码应含中文原文", full.contains("中文GBK测试文本"))
            assertFalse("不应出现替换字符 U+FFFD", full.contains("\uFFFD"))
        } finally {
            doc.close()
        }
    }

    @Test
    fun unsupportedDocx_importRejected() = runBlocking {
        val before = entry.database().documentDao().all().size
        File(ctx.cacheDir, "unsupported_test.docx")
            .writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04) + "not-a-real-docx ".toByteArray())
        val res = entry.documentImporter().import(testUri("unsupported_test.docx"))
        assertEquals("未注册格式应拒收", DocumentImporter.Status.UNSUPPORTED, res.status)
        assertEquals("拒收不应入库", before, entry.database().documentDao().all().size)
    }

    @Test
    fun backupRestore_roundTripKeepsDocuments() = runBlocking {
        val imp = entry.documentImporter()
        val name = "bk_restore_${System.currentTimeMillis()}.txt"
        File(ctx.cacheDir, name).writeBytes("备份恢复验收文本".toByteArray(Charsets.UTF_8))
        val res = imp.import(testUri(name))
        assertTrue("准备文档应导入", res.status == DocumentImporter.Status.IMPORTED)
        val id = res.documentId!!

        val zip = File(ctx.cacheDir, "myreader_acceptance_backup.zip")
        zip.outputStream().use { entry.backupManager().export(it, "MyReader") }
        assertTrue("备份 zip 应非空", zip.length() > 0)

        // 合并式恢复：不应清除既有文档，也不应引入未知 fileType
        zip.inputStream().use { entry.backupManager().restore(it) }

        val docs = entry.database().documentDao().all()
        assertTrue("恢复后应含追踪文档", docs.any { it.id == id && it.fileType == "txt" })
        val allowed = setOf("pdf", "epub", "cbz", "xps", "txt")
        assertTrue("不应存在未知 fileType 文档", docs.all { it.fileType in allowed })
    }
}

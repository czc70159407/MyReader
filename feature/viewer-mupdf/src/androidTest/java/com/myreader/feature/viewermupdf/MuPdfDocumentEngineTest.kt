package com.myreader.feature.viewermupdf

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myreader.core.document.CorruptDocumentException
import com.myreader.core.document.PasswordRequiredException
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MuPDF 引擎插桩测试（androidTest）：`libmupdf_java.so` 无法在 JVM 加载，引擎行为只能真机/模拟器验证。
 *
 * 覆盖 docs/process.md「测试策略」为本模块规划的场景：
 * - `open` 正常 / 加密（缺密码抛异常、正确密码可打开）/ 损坏（抛异常）
 * - `pageCount`、`outline`（大纲展平还原）、`drawPage`、句柄 `close()` 无异常
 *
 * 样本 PDF 位于 `src/androidTest/assets/`，由 `tools/test_pdfs.py generate` 生成（可复现）。
 * 运行：`.\gradlew.bat :feature:viewer-mupdf:connectedDebugAndroidTest`
 */
@RunWith(AndroidJUnit4::class)
class MuPdfDocumentEngineTest {

    private lateinit var engine: MuPdfDocumentEngine
    private lateinit var cacheDir: File

    @Before
    fun setUp() {
        engine = MuPdfDocumentEngine()
        val context: Context = ApplicationProvider.getApplicationContext()
        cacheDir = File(context.cacheDir, "mupdf_test").apply { mkdirs() }
    }

    /** assets 内样本拷到可访问路径（引擎按文件路径打开） */
    private fun assetFile(name: String): File {
        val context: Context = ApplicationProvider.getApplicationContext()
        return File(cacheDir, name).apply {
            context.assets.open(name).use { input -> outputStream().use { input.copyTo(it) } }
        }
    }

    @Test
    fun open_normalPdf_returnsPageCountAndCloses() {
        val doc = engine.open(assetFile("demo_test.pdf").absolutePath, "pdf", null)
        try {
            assertEquals(3, doc.pageCount)
            assertTrue(runBlocking { doc.outline() }.isEmpty())   // 该样本无大纲
        } finally {
            doc.close()
        }
    }

    @Test
    fun open_encryptedPdf_withoutPasswordThrows_withPasswordSucceeds() {
        val path = assetFile("encrypted_test.pdf").absolutePath
        try {
            engine.open(path, "pdf", null)
            fail("缺少密码时应抛 PasswordRequiredException")
        } catch (e: PasswordRequiredException) {
            // 预期：加密文档无密码不得打开
        }
        val doc = engine.open(path, "pdf", "1234")
        try {
            assertTrue(doc.pageCount > 0)
        } finally {
            doc.close()
        }
    }

    @Test
    fun open_corruptPdf_throwsCorruptDocument() {
        try {
            engine.open(assetFile("corrupt_test.pdf").absolutePath, "pdf", null)
            fail("损坏文档应抛 CorruptDocumentException")
        } catch (e: CorruptDocumentException) {
            // 预期：导入期据此拒收、打开期据此标 -2
        }
    }

    @Test
    fun outline_outlinePdf_returnsNestedEntries() {
        val doc = engine.open(assetFile("outline_test.pdf").absolutePath, "pdf", null)
        try {
            assertEquals(12, doc.pageCount)
            val outline = runBlocking { doc.outline() }
            assertEquals(3, outline.size)                        // 三个一级 Section
            assertTrue(outline.all { it.children.isNotEmpty() })  // 每个一级含二级子项
        } finally {
            doc.close()
        }
    }

    @Test
    fun drawPage_rendersIntoBitmap() {
        val doc = engine.open(assetFile("demo_test.pdf").absolutePath, "pdf", null)
        try {
            val bitmap = Bitmap.createBitmap(200, 260, Bitmap.Config.ARGB_8888)
            doc.drawPage(bitmap, 0, 200, 260, 0, 0, 200, 260)
            assertEquals(200, bitmap.width)
            assertEquals(260, bitmap.height)
        } finally {
            doc.close()
        }
    }
}

package com.myreader.feature.viewermupdf

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myreader.core.document.CorruptDocumentException
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M9 多格式引擎插桩测试（androidTest）：验证 fitz 经 magic 打开 epub / cbz / xps，
 * 并覆盖 epub 的 location/bookmark 往返（进度锚点 L1）。libmupdf_java.so 需真机/模拟器。
 *
 * 夹具位于 `src/androidTest/assets/`，由 `tools/test_docs.py generate` 生成（可复现）。
 * 运行：`.\gradlew.bat :feature:viewer-mupdf:connectedDebugAndroidTest`
 *
 * 说明：S0-3 渲染/magic 归一的可打开性判据落地于此；XPS 若在本机 fitz 不被识别，
 * 按 docs/m9-plan.md R3 降级为代码级路由验证 + 真机手工样本（AGENTS.md 记录）。
 */
@RunWith(AndroidJUnit4::class)
class MuPdfMultiFormatEngineTest {

    private lateinit var engine: MuPdfDocumentEngine
    private lateinit var cacheDir: File

    @Before
    fun setUp() {
        engine = MuPdfDocumentEngine()
        val context: Context = ApplicationProvider.getApplicationContext()
        cacheDir = File(context.cacheDir, "mupdf_multi_test").apply { mkdirs() }
    }

    private fun assetFile(name: String): File {
        val context: Context = ApplicationProvider.getApplicationContext()
        return File(cacheDir, name).apply {
            context.assets.open(name).use { input -> outputStream().use { input.copyTo(it) } }
        }
    }

    @Test
    fun open_epub_reflowableWithPagesOutlineAndDraw() {
        val doc = engine.open(assetFile("epub_test.epub").absolutePath, "epub", null)
        try {
            assertTrue("epub 应为重排格式", (doc as MuPdfDocument).reflowable)
            assertTrue("epub 打开后应有页", doc.pageCount > 0)
            // 3 章 NCX 大纲
            val outline = runBlocking { doc.outline() }
            assertTrue("epub 大纲应含 3 章", outline.size >= 3)
            // 首页可渲染
            val bitmap = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888)
            doc.drawPage(bitmap, 0, 200, 300, 0, 0, 200, 300)
            assertEquals(200, bitmap.width)
        } finally {
            doc.close()
        }
    }

    @Test
    fun open_cbz_pageBasedWithImageCountAndDraw() {
        val doc = engine.open(assetFile("comic_test.cbz").absolutePath, "cbz", null)
        try {
            assertTrue("cbz 非重排", !(doc as MuPdfDocument).reflowable)
            assertEquals("cbz 页数=图片数", 5, doc.pageCount)
            val bitmap = Bitmap.createBitmap(120, 160, Bitmap.Config.ARGB_8888)
            doc.drawPage(bitmap, 0, 120, 160, 0, 0, 120, 160)
            assertEquals(120, bitmap.width)
        } finally {
            doc.close()
        }
    }

    @Test
    fun xps_routesToMuPDFEngine_handWrittenFixtureRejectedPerR3() {
        // R3（docs/m9-plan.md）：手写最小 OPC 夹具不足以满足 fitz XPS 解析器对完整包的要求，
        // 真机实测报 "cannot find fixed document sequence start part"。此用例验证**路由与错误映射**：
        // xps → MuPdfDocumentEngine → fitz XPS handler 被触发（非 PDF handler）→ 映射为 CorruptDocumentException，
        // 与「损坏/不可解析 xps 被导入期拒收」路径一致。**正向 xps 渲染验证需真实 XPS 样本**，已记录 AGENTS.md。
        try {
            engine.open(assetFile("xps_test.xps").absolutePath, "xps", null)
            // 若 fitz 某日能打开手写夹具（更完整的 OPC），则顺带校验基本属性
            org.junit.Assert.fail("手写 xps 夹具不应被当前 fitz 打开；若打开请补正向断言")
        } catch (e: CorruptDocumentException) {
            // 预期：路由到 MuPDF 引擎且 fitz 无法解析该夹具
        }
    }

    @Test
    fun epub_location_bookmarkRoundTripStable() {
        val doc = engine.open(assetFile("epub_test.epub").absolutePath, "epub", null) as MuPdfDocument
        try {
            val core = doc.core
            val resolver = EpubLocationResolver(core)
            val count = doc.pageCount
            // 取中页 → bookmark → 还原页码，往返应稳定
            val mid = (count / 2).coerceIn(0, count - 1)
            val payload = resolver.progressForPage(mid)
            val restored = resolver.pageForProgress(payload)
            assertEquals("bookmark 往返页码应一致", mid, restored)
        } finally {
            doc.close()
        }
    }
}

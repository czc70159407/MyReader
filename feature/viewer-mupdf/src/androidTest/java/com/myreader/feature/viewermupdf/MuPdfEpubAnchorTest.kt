package com.myreader.feature.viewermupdf

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myreader.core.document.EpubProgressCodec
import com.myreader.core.document.ProgressPayload
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M11 比例锚点插桩测试（真机）：验证 [EpubLocationResolver] 产出的 payload
 * 在同一排版下精确还原、换排版后仍落在**同一章**（S0-2 实测的失效模式是「静默串到相邻章」）。
 *
 * 夹具：`src/androidTest/assets/epub_test.epub`（3 章 / 默认排版 12 页）。
 * 运行：`.\gradlew.bat :feature:viewer-mupdf:connectedDebugAndroidTest`
 */
@RunWith(AndroidJUnit4::class)
class MuPdfEpubAnchorTest {

    private lateinit var engine: MuPdfDocumentEngine
    private lateinit var cacheDir: File

    @Before
    fun setUp() {
        engine = MuPdfDocumentEngine()
        val context: Context = ApplicationProvider.getApplicationContext()
        cacheDir = File(context.cacheDir, "mupdf_anchor_test").apply { mkdirs() }
    }

    private fun assetFile(name: String): File {
        val context: Context = ApplicationProvider.getApplicationContext()
        return File(cacheDir, name).apply {
            context.assets.open(name).use { input -> outputStream().use { input.copyTo(it) } }
        }
    }

    private fun openEpub(): MuPdfDocument =
        engine.open(assetFile("epub_test.epub").absolutePath, "epub", null) as MuPdfDocument

    private fun cfiPayloadOf(payload: ProgressPayload): EpubProgressCodec.EpubProgress {
        assertTrue("流式文档应产出 Cfi payload", payload is ProgressPayload.Cfi)
        return EpubProgressCodec.decode((payload as ProgressPayload.Cfi).cfi)!!
    }

    @Test
    fun anchor_producesRatioFields() {
        val doc = openEpub()
        try {
            val resolver = EpubLocationResolver(doc.core)
            val mid = (doc.pageCount / 2).coerceIn(0, doc.pageCount - 1)
            val decoded = cfiPayloadOf(resolver.progressForPage(mid))
            assertTrue("payload 应含自洽的比例分量", decoded.hasRatio)
            assertEquals("page 与锚点页码一致", mid, decoded.page)
        } finally {
            doc.close()
        }
    }

    @Test
    fun anchor_restoresExactPageInSameLayout() {
        val doc = openEpub()
        try {
            val resolver = EpubLocationResolver(doc.core)
            val mid = (doc.pageCount / 2).coerceIn(0, doc.pageCount - 1)
            val payload = resolver.progressForPage(mid)
            assertEquals("同排版应精确回到原页", mid, resolver.pageForProgress(payload))
        } finally {
            doc.close()
        }
    }

    @Test
    fun anchor_landsOnSameChapterAfterRelayout() {
        val doc = openEpub()
        try {
            val core = doc.core
            val resolver = EpubLocationResolver(core)
            val mid = (doc.pageCount / 2).coerceIn(0, doc.pageCount - 1)
            val payload = resolver.progressForPage(mid)
            val decoded = cfiPayloadOf(payload)

            // 模拟横竖屏/分屏：换到与默认 312×504 不同的视口触发整书重排
            core.layout(mid, 420, 640, 10)
            val after = doc.pageCount
            assertTrue("重排后仍应有页", after > 0)

            val restored = resolver.pageForProgress(payload)
            assertTrue("还原页应在当前排版范围内: $restored / $after", restored in 0 until after)
            val loc = core.pageToLocation(restored)
            assertTrue("章定位应可得", loc != null)
            assertEquals("跨排版应落在同一章（不得静默串到相邻章）", decoded.chIdx, loc!![0])
        } finally {
            doc.close()
        }
    }

    @Test
    fun anchor_clampsLegacyPageBeyondCurrentLayout() {
        // L3 兜底：v1 payload 只有 page，且该页超出当前排版 → 必须钳到末页，不能把越界 index 交给渲染层
        val doc = openEpub()
        try {
            val resolver = EpubLocationResolver(doc.core)
            val legacy = EpubProgressCodec.encode(loc = "-1", page = doc.pageCount + 50)
            val page = resolver.pageForProgress(ProgressPayload.Cfi(legacy))
            assertEquals("越界页码应钳到末页", doc.pageCount - 1, page)
        } finally {
            doc.close()
        }
    }
}

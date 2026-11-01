package com.myreader.feature.viewermupdf

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Point
import java.io.File
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M12 S0 预研探针（androidTest，仅取证不设断言）。
 *
 * 逐步日志定位 fitz 1.28.2 真机行为：文本选择/高亮链路的每一步都打 `M12PROBE` 日志，
 * native SIGABRT 无法被 runCatching 捕获，故以「最后成功打印的 step」判定崩溃位置。
 */
@RunWith(AndroidJUnit4::class)
class MuPdfAnnotationProbeTest {

    private lateinit var cacheDir: File

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        cacheDir = File(context.cacheDir, "m12_probe").apply { mkdirs() }
    }

    private fun assetFile(name: String): File {
        val context: Context = ApplicationProvider.getApplicationContext()
        return File(cacheDir, name).apply {
            context.assets.open(name).use { input -> outputStream().use { input.copyTo(it) } }
        }
    }

    @Test
    fun probe_pdfStepwise() {
        val path = assetFile("outline_test.pdf").absolutePath
        val doc = Document.openDocument(path)
        val page = doc.loadPage(0)
        val st = page.toStructuredText()
        Log.i(TAG, "S3 st text=${st.asText().take(60)}")

        // 绕过 search（真机 JNI 崩溃），直接用页面坐标调 highlight/copy
        val sel = st.highlight(Point(50f, 50f), Point(200f, 100f))
        Log.i(TAG, "S7 highlight quads=${sel.size}")
        val copied = st.copy(Point(50f, 50f), Point(200f, 100f))
        Log.i(TAG, "S8 copied=$copied")

        // 高亮创建 + 读回
        val pdfPage = page as? PDFPage
        val ann = pdfPage?.createAnnotation(PDFAnnotation.TYPE_HIGHLIGHT)
        if (sel.isNotEmpty()) {
            ann?.setQuadPoints(sel)
            ann?.setColor(floatArrayOf(1f, 1f, 0f))
            ann?.update()
        }
        val anns = pdfPage?.getAnnotations()
        Log.i(TAG, "S10 annotations=${anns?.size} types=${anns?.map { it.getType() }}")
        doc.destroy()
        Log.i(TAG, "S11 done")
    }

    @Test
    fun probe_epubStepwise() {
        val path = assetFile("epub_test.epub").absolutePath
        val doc = Document.openDocument(path)
        doc.layout(312f, 504f, 10f)
        val page = doc.loadPage(0)
        val st = page.toStructuredText()
        Log.i(TAG, "E1 epub st text=${st.asText().take(80)}")
        val sel = st.highlight(Point(10f, 10f), Point(300f, 100f))
        Log.i(TAG, "E2 epub highlight quads=${sel.size}")
        val copied = st.copy(Point(10f, 10f), Point(300f, 100f))
        Log.i(TAG, "E3 epub copied=$copied")
        doc.destroy()
        Log.i(TAG, "E4 done")
    }

    private companion object {
        const val TAG = "M12PROBE"
    }
}

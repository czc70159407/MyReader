package com.myreader.feature.viewermupdf

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Size
import com.myreader.core.document.Document
import com.myreader.core.document.OutlineEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * VerticalPageLoader 单测（Robolectric：LruCache/Bitmap 需 Android 运行时）。
 * Fake 只记账不触 native；dispatcher 注入 Unconfined 使挂起内联执行（无虚拟时间耦合）。
 * 位图字节数按 Robolectric 实测口径：ARGB_8888 宽×高×4（100px 宽、595:842 比例 → 141px 高 → 56400B）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VerticalPageLoaderTest {

    /** 记账 fake：记录 drawPage/pageSize 调用（页号与宽度），渲染不产生真实像素 */
    private class RecordingDocument(
        override val pageCount: Int = 3,
        private val pointSize: Size = Size(595f, 842f),
    ) : Document {
        val drawCalls = mutableListOf<Pair<Int, Int>>()   // (page, renderWidth)
        val sizeCalls = mutableListOf<Int>()

        override fun gotoPage(page: Int) {}
        override suspend fun pageSize(page: Int): Size {
            sizeCalls += page
            return pointSize
        }

        override fun drawPage(
            bitmap: Bitmap, page: Int, width: Int, height: Int,
            patchX: Int, patchY: Int, patchW: Int, patchH: Int,
        ) {
            drawCalls += page to width
        }

        override suspend fun outline(): List<OutlineEntry> = emptyList()
        override fun close() {}
    }

    private fun loader(doc: RecordingDocument, budgetBytes: Int = Int.MAX_VALUE) =
        VerticalPageLoader(doc, budgetBytes = budgetBytes, dispatcher = Dispatchers.Unconfined)

    @Test
    fun `同页同宽二次加载命中缓存仅渲染一次`() = runTest {
        val doc = RecordingDocument()
        val loader = loader(doc)
        val first = loader.loadBitmap(0, 100)
        val second = loader.loadBitmap(0, 100)
        assertEquals(1, doc.drawCalls.size)
        assertSame(first, second)
    }

    @Test
    fun `不同页各自渲染缓存独立命中`() = runTest {
        val doc = RecordingDocument()
        val loader = loader(doc)
        loader.loadBitmap(0, 100)
        loader.loadBitmap(1, 100)
        assertEquals(listOf(0 to 100, 1 to 100), doc.drawCalls)
        assertNotNull(loader.cachedBitmap(0, 100))
        assertNotNull(loader.cachedBitmap(1, 100))
    }

    @Test
    fun `宽度变化后按新宽度重新渲染`() = runTest {
        val doc = RecordingDocument()
        val loader = loader(doc)
        loader.loadBitmap(0, 100)
        loader.loadBitmap(0, 200)
        assertEquals(listOf(0 to 100, 0 to 200), doc.drawCalls)
    }

    @Test
    fun `字节预算不足时淘汰最旧页重载触发重渲`() = runTest {
        val doc = RecordingDocument()
        // 100px 宽 × 141px 高（595:842 换算）× 4B = 56400B；预算恰好 1 页
        val loader = loader(doc, budgetBytes = 56_400)
        loader.loadBitmap(0, 100)
        loader.loadBitmap(1, 100)   // 挤掉 page 0
        assertNull(loader.cachedBitmap(0, 100))
        assertNotNull(loader.cachedBitmap(1, 100))
        loader.loadBitmap(0, 100)   // 淘汰后重载 → 重渲
        assertEquals(3, doc.drawCalls.size)
    }

    @Test
    fun `页尺寸每页仅拉取一次跨宽度复用`() = runTest {
        val doc = RecordingDocument()
        val loader = loader(doc)
        loader.pageSize(0)
        loader.pageSize(0)
        loader.loadBitmap(0, 100)
        loader.loadBitmap(0, 200)   // 宽度变化不重取尺寸（点尺寸与宽度无关）
        assertEquals(1, doc.sizeCalls.size)
    }

    @Test
    fun `evictAll 清位图引用但保留页尺寸缓存`() = runTest {
        val doc = RecordingDocument()
        val loader = loader(doc)
        loader.pageSize(0)
        loader.loadBitmap(0, 100)
        loader.evictAll()
        assertNull(loader.cachedBitmap(0, 100))
        loader.loadBitmap(0, 100)   // 位图重渲
        assertEquals(2, doc.drawCalls.size)
        assertEquals(1, doc.sizeCalls.size)   // 尺寸保留未重取
    }
}

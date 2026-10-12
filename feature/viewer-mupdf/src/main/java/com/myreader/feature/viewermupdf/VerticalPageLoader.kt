package com.myreader.feature.viewermupdf

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.ui.geometry.Size
import com.myreader.core.document.Document
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 垂直模式渲染编排（M7）：页点尺寸缓存 + 位图异步渲染 + 字节预算 LruCache。
 * Compose 无关（Robolectric 可测）。
 *
 * 线程模型（缺一不可）：
 * - MuPDFCore 非线程安全 → 所有 pageSize/drawPage 收进 [mutex] 串行化；
 * - native 渲染（createBitmap + drawPage）切换到 [dispatcher]（默认 Default）执行——
 *   调用方是 Composable 协程（Main），主线程执行必卡帧。
 * [Mutex.withLock] 是可取消挂起点：快速滚动时 item 离场、等待中的加载自动终止；
 * 取锁后经 [ensureActive] 再确认，减少完成即弃的浪费渲染。
 *
 * 内存：LruCache 按字节预算（不按固定页数——2K 屏横屏单页可达 30MB）；
 * key 含目标宽度（旋转/分屏后旧位图自然失配）。**不手动 recycle**——
 * minSdk 26 位图像素数据在 native heap、由 GC 经 NativeAllocationRegistry 释放，
 * recycle 一个仍被 Image 绘制/状态持有的位图会崩溃；淘汰仅移除引用。
 */
internal class VerticalPageLoader(
    private val doc: Document,
    budgetBytes: Int = defaultBudgetBytes(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    private val mutex = Mutex()

    /** key = (页号, 目标宽 px)；sizeOf 按实际分配字节数计 */
    private val cache = object : LruCache<Pair<Int, Int>, Bitmap>(budgetBytes) {
        override fun sizeOf(key: Pair<Int, Int>, value: Bitmap): Int = value.allocationByteCount
    }

    /** 页点尺寸缓存（点尺寸与渲染宽度无关，跨宽度变化保留；防滚动中 item 高度抖动） */
    private val pageSizes = HashMap<Int, Size>()

    /** 同步读缓存（重组快路径：命中免占位闪烁）；未命中走异步 [loadBitmap] */
    fun cachedBitmap(page: Int, widthPx: Int): Bitmap? = cache.get(page to widthPx)

    /** 页点尺寸（getPageSize 兼做页面预热，随后 drawPage 更快）；成功结果缓存复用 */
    suspend fun pageSize(page: Int): Size {
        pageSizes[page]?.let { return it }
        val size = mutex.withLock { obtainSizeLocked(page) }
        return size
    }

    /**
     * 渲染页位图：宽度适配，高度按页宽高比等比换算（超长页降分辨率，防 GPU 纹理上限）。
     * 并发双检：取锁后再查一次缓存（另一协程可能已渲染同页）。
     */
    suspend fun loadBitmap(page: Int, widthPx: Int): Bitmap {
        cachedBitmap(page, widthPx)?.let { return it }
        return mutex.withLock {
            cachedBitmap(page, widthPx)?.let { return it }
            val size = obtainSizeLocked(page)
            val (renderWidth, renderHeight) = renderSize(size, widthPx)
            val bitmap = withContext(dispatcher) {
                coroutineContext.ensureActive()
                val bitmap = Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888)
                doc.drawPage(bitmap, page, renderWidth, renderHeight)
                bitmap
            }
            cache.put(page to widthPx, bitmap)
            bitmap
        }
    }

    /** 清位图缓存引用（宽度变化/离场时）；pageSizes 保留（点尺寸与宽度无关） */
    fun evictAll() {
        cache.evictAll()
    }

    /** 已持锁前提下取页尺寸（map 命中或 native 拉取并缓存；异常 0 尺寸不缓存，下次重试） */
    private suspend fun obtainSizeLocked(page: Int): Size {
        pageSizes[page]?.let { return it }
        val size = withContext(dispatcher) { doc.pageSize(page) }
        if (size.width > 0f && size.height > 0f) pageSizes[page] = size
        return size
    }

    /** 渲染尺寸：宽度定高、按点宽高比换算；异常页回落 4:3（与 renderPageBitmap 同款兜底）；
     * 高度超上限时等比降分辨率（海报等超长页防低端 GPU 纹理溢出） */
    private fun renderSize(size: Size, widthPx: Int): Pair<Int, Int> {
        val height = if (size.width > 0f && size.height > 0f) {
            (widthPx * size.height / size.width).toInt().coerceAtLeast(1)
        } else {
            widthPx * 4 / 3
        }
        if (height <= MAX_BITMAP_HEIGHT) return widthPx to height
        val scale = MAX_BITMAP_HEIGHT.toFloat() / height
        return (widthPx * scale).toInt().coerceAtLeast(1) to MAX_BITMAP_HEIGHT
    }

    companion object {
        /** LruCache 字节预算：maxMemory/8，clamp 16~48MB（典型设备等效 4~8 页） */
        fun defaultBudgetBytes(): Int =
            (Runtime.getRuntime().maxMemory() / 8L).toInt().coerceIn(MIN_BUDGET_BYTES, MAX_BUDGET_BYTES)

        const val MIN_BUDGET_BYTES = 16 shl 20
        const val MAX_BUDGET_BYTES = 48 shl 20

        /** 单张位图高度上限（超长页等比降分辨率渲染） */
        const val MAX_BITMAP_HEIGHT = 4096
    }
}

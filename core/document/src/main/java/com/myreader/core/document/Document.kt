package com.myreader.core.document

import java.io.Closeable

/**
 * 文档句柄接口（feature 层只持有此接口，不接触 MuPDF 具体类；
 * 底层 native 句柄由引擎实现包装，配置变更时 ViewModel 复用同一实例）。
 */
interface Document : Closeable {

    /** 页数；页码式文档有效，流式重排文档（如 epub）返回 -1 */
    val pageCount: Int

    /** 定位到指定页（渲染前调用；页码越界由实现钳制） */
    fun gotoPage(page: Int)

    /**
     * 指定页的点尺寸（宽高比用于布局占位与等比渲染，M7 垂直模式）。
     * 实现内部可兼做页面预热（如 MuPDF getPageSize 触发 loadPage）；
     * 异常页可返回 0 尺寸，调用方按默认比例兜底。页码越界由实现钳制。
     */
    suspend fun pageSize(page: Int): androidx.compose.ui.geometry.Size

    /**
     * 渲染指定页到 bitmap。patch 参数支持局部渲染（全页渲染传 0,0,width,height）。
     * 实现须线程安全或由调用方串行化（MuPDFCore 非线程安全）。
     */
    fun drawPage(
        bitmap: android.graphics.Bitmap,
        page: Int,
        width: Int,
        height: Int,
        patchX: Int = 0,
        patchY: Int = 0,
        patchW: Int = width,
        patchH: Int = height,
    )

    /** 文档大纲/目录；无大纲返回空表。title/pageIndex 嵌套通过 children 表达 */
    suspend fun outline(): List<OutlineEntry>

    /** 释放 native 句柄与位图资源；幂等 */
    override fun close()
}

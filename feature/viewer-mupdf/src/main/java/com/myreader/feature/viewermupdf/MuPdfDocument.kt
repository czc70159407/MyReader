package com.myreader.feature.viewermupdf

import android.graphics.Bitmap
import com.artifex.mupdf.viewer.MuPDFCore
import com.myreader.core.document.Document
import com.myreader.core.document.OutlineEntry

/**
 * [Document] 的 MuPDF 实现：薄转发包装 [MuPDFCore] native 句柄。
 * 注意 MuPDFCore 非线程安全——并发 drawPage 须由调用方串行化（M7 垂直模式时上 mutex）。
 */
class MuPdfDocument(private val core: MuPDFCore) : Document {

    override val pageCount: Int = core.countPages()

    override fun gotoPage(page: Int) {
        // MuPDFCore.gotoPage 为 private；getPageSize 内部触发同逻辑（loadPage + bounds）
        core.getPageSize(page)
    }

    override fun drawPage(
        bitmap: Bitmap,
        page: Int,
        width: Int,
        height: Int,
        patchX: Int,
        patchY: Int,
        patchW: Int,
        patchH: Int,
    ) {
        core.drawPage(bitmap, page, width, height, patchX, patchY, patchW, patchH, null)
    }

    override suspend fun outline(): List<OutlineEntry> {
        if (!core.hasOutline()) return emptyList()
        // core.getOutline() 返回带缩进平铺列表（每级 4 空格，上游 flattenOutlineNodes 生成；
        // null 标题节点不输出，但其子树仍以更深缩进出现）。
        // 还原：按缩进深度组树；空标题节点作为隐形占位维持层级（输出时上提其子树），
        // 避免子节点错挂到前一个兄弟；首条目缩进异常时钳制到现有最深层防越界。
        data class Node(val title: String, val page: Int, val placeholder: Boolean) {
            val children = mutableListOf<Node>()
        }

        val root = mutableListOf<Node>()
        val stack = ArrayDeque<MutableList<Node>>().apply { addLast(root) }
        for (item in core.getOutline()) {
            val depth = (item.title.length - item.title.trimStart(' ').length) / 4
            val title = item.title.trimStart(' ')
            while (stack.size > depth + 1) stack.removeLast()
            while (stack.size < depth + 1) {
                val last = stack.last()
                if (last.isEmpty()) break // 父层无实体节点：钳制深度，防 NoSuchElementException
                stack.addLast(last.last().children)
            }
            val node = Node(title, item.page, placeholder = title.isEmpty())
            stack.last().add(node)
            stack.addLast(node.children)
        }

        fun List<Node>.toEntries(): List<OutlineEntry> = flatMap { n ->
            val entries = n.children.toEntries()
            if (n.placeholder) entries
            else listOf(OutlineEntry(title = n.title, pageIndex = n.page, children = entries))
        }

        return root.toEntries()
    }

    /** 渲染指定页为指定宽度的位图（缩略图/预览用）；高度按页面点尺寸等比换算 */
    fun renderPageBitmap(page: Int, width: Int): Bitmap {
        val size = core.getPageSize(page)
        val height = if (size.y > 0) (width * size.y / size.x).toInt().coerceAtLeast(1) else width * 4 / 3
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        core.drawPage(bitmap, page, width, height, 0, 0, width, height, null)
        return bitmap
    }

    override fun close() {
        core.onDestroy()
    }
}

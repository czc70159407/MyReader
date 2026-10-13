package com.myreader.feature.viewertxt

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.ui.geometry.Size
import com.myreader.core.document.Document
import com.myreader.core.document.OutlineEntry

/**
 * [Document] 的纯文本实现（M9-F 扩展点验证）：整篇文本在打开时预分页，按页渲染位图。
 * 不依赖 MuPDF；页码式进度（reflowable = false），大纲恒空（txt 无目录）。
 */
class TxtDocument internal constructor(private val pages: List<String>) : Document {

    /** 布局占位/缩略图用的标称页点尺寸（A4 纵向近似） */
    private companion object {
        val NOMINAL = Size(595f, 842f)
    }

    override val pageCount: Int = pages.size

    /** 当前页文本（越界返回空串） */
    fun pageText(page: Int): String = pages.getOrElse(page) { "" }

    override fun gotoPage(page: Int) {
        // 无状态：每页独立渲染，无需预先定位
    }

    override suspend fun pageSize(page: Int): Size = NOMINAL

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
        val text = pages.getOrNull(page.coerceIn(0, pages.size - 1)) ?: return
        bitmap.eraseColor(Color.WHITE)
        if (text.isEmpty()) return
        val canvas = Canvas(bitmap)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            // 字号随位图高度粗略缩放（裁剪溢出，验证模块不做精确排版）
            textSize = (height / 40f).coerceIn(12f, 56f)
        }
        val pad = (width * 0.06f).toInt().coerceAtLeast(8)
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, (width - pad * 2).coerceAtLeast(1))
            .build()
        canvas.save()
        canvas.translate(pad.toFloat(), pad.toFloat())
        layout.draw(canvas)   // 超长文本按位图边界自然裁剪
        canvas.restore()
    }

    override suspend fun outline(): List<OutlineEntry> = emptyList()

    override fun close() {
        // 无 native 资源
    }

    /** 渲染指定页为指定位图（缩略图用）；高度按标称页比例换算 */
    fun renderPageBitmap(page: Int, width: Int): Bitmap {
        val height = (width * NOMINAL.height / NOMINAL.width).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        drawPage(bitmap, page, width, height, 0, 0, width, height)
        return bitmap
    }
}

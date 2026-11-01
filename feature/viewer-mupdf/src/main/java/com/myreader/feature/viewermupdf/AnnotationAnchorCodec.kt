package com.myreader.feature.viewermupdf

import com.artifex.mupdf.fitz.Quad
import java.util.Locale

/** quad 顶点分量数（ul/ur/ll/lr 各 2 个 float） */
private const val QUAD_FLOATS = 8

/** epub 重排锚点分隔符（`cp:<ch>:<pageInChap>:<chapPages>|[[...]]`） */
private const val EPUB_ANCHOR_SEP = "|"

/** epub 章内比例锚点前缀（与 `EpubProgressCodec` 的 `cp:` 同口径） */
private const val EPUB_ANCHOR_PREFIX = "cp:"

/**
 * M12 高亮锚点编解码：归一化 quad（相对页面宽高 0..1）+ 可选 epub 重排前缀。
 *
 * - PDF（固定排版）：纯 quad JSON，跨会话/跨窗口尺寸稳定；
 * - EPUB（reflowable）：`cp:<ch>:<pageInChap>:<chapPages>|` 前缀记章内比例，重排后据此
 *   把高亮映射到新排版页（页级精度），后接同一份归一化 quad（页内相对位置）。
 *
 * 归一化保证同一页在不同窗口尺寸下一致；4 位小数足够（页面宽约数百 pt）。
 */
internal object AnnotationAnchorCodec {

    /** 解析结果：epub 章内比例前缀（PDF 为 null）+ 归一化 quad 列表（每项 8 个 0..1 值） */
    data class Decoded(val epubCp: String?, val quads: List<FloatArray>)

    /** quads（页面 pt）→ anchor；[epubCp] 非空时前置 `cp:...|` */
    @Suppress("MagicNumber")
    fun encode(quads: Array<Quad>, pageWidth: Float, pageHeight: Float, epubCp: String? = null): String {
        val body = quads.joinToString(separator = ",", prefix = "[", postfix = "]") { q ->
            listOf(
                q.ul_x / pageWidth, q.ul_y / pageHeight,
                q.ur_x / pageWidth, q.ur_y / pageHeight,
                q.ll_x / pageWidth, q.ll_y / pageHeight,
                q.lr_x / pageWidth, q.lr_y / pageHeight,
            ).joinToString(separator = ",", prefix = "[", postfix = "]") {
                String.format(Locale.US, "%.4f", it)
            }
        }
        return if (epubCp != null) epubCp + EPUB_ANCHOR_SEP + body else body
    }

    /** 归一化 quad 列表 → anchor（垂直模式直接产出归一化值，无需 pt 往返） */
    fun encodeNormalized(quads: List<FloatArray>): String =
        quads.joinToString(separator = ",", prefix = "[", postfix = "]") { n ->
            n.joinToString(separator = ",", prefix = "[", postfix = "]") {
                String.format(Locale.US, "%.4f", it)
            }
        }

    /** anchor → 解析结果；格式非法返回 null */
    fun decode(anchor: String): Decoded? {
        val sep = anchor.indexOf(EPUB_ANCHOR_SEP)
        val hasCp = sep > 0 && anchor.startsWith(EPUB_ANCHOR_PREFIX)
        val cp = if (hasCp) anchor.substring(0, sep) else null
        val body = if (hasCp) anchor.substring(sep + 1) else anchor
        val rows = Regex("\\[([^\\[\\]]+)\\]").findAll(body).map { it.groupValues[1] }.toList()
        if (rows.isEmpty()) return null
        val quads = rows.mapNotNull { row ->
            val nums = row.split(",").mapNotNull { it.trim().toFloatOrNull() }
            if (nums.size != QUAD_FLOATS) null else nums.toFloatArray()
        }
        return if (quads.isEmpty()) null else Decoded(cp, quads)
    }

    /** 归一化 → 页面 pt quads（原生 annotation / 绘制用） */
    @Suppress("MagicNumber")
    fun toQuads(decoded: Decoded, pageWidth: Float, pageHeight: Float): Array<Quad> =
        decoded.quads.map { n ->
            Quad(
                n[0] * pageWidth, n[1] * pageHeight,
                n[2] * pageWidth, n[3] * pageHeight,
                n[4] * pageWidth, n[5] * pageHeight,
                n[6] * pageWidth, n[7] * pageHeight,
            )
        }.toTypedArray()
}

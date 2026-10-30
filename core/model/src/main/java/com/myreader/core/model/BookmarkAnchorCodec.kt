package com.myreader.core.model

/**
 * 流式书签锚点编解码（M11；纯逻辑，无 Android 依赖）。
 *
 * 两种并存格式（共用 `bookmarks.anchor` 一个 TEXT 列，无 schema 变更）：
 * - **比例锚点（M11 起写入）**：`cp:<chapter>:<pageInChapter>:<chapterPages>`，
 *   跨会话与跨排版都能还原位置（依据 M11 S0-2 真机实测：Location 分量跨会话精确，
 *   章内页码跨排版漂移 → 需以章页数作分母折算）；
 * - **旧 bookmark 串（M9/M10 数据继续可读）**：十进制 long，跨会话已证实失效，
 *   仅在能解析时用于同会话精确跳转，否则回落 `page`。
 */
object BookmarkAnchorCodec {

    private const val PREFIX = "cp:"
    private const val SEPARATOR = ":"
    private const val RATIO_PART_COUNT = 3

    /** 比例折算用的「屏中点」偏移：以章内页中心代表该屏位置，避免边界页前后跳动。 */
    private const val PAGE_MIDPOINT = 0.5

    /** 百分比换算基数。 */
    private const val PERCENT = 100.0

    /** 浮点比较容差：抵消 `1/3` 这类除法的舍入误差，使「正好等于容差」不被判超差。 */
    private const val EPSILON = 1e-9

    /** 判重容差（百分点）：同章内比例差 ≤ 1% 视为同一位置（书签粒度本就是屏）。 */
    const val DUPLICATE_RATIO_PERCENT = 1

    /** 解析结果：比例 / 旧串 / 非法。 */
    sealed interface Anchor {
        /** 比例锚点（三值自洽：chapter ≥ 0、chapterPages ≥ 1、pageInChapter ∈ [0, chapterPages)） */
        data class Ratio(val chapter: Int, val pageInChapter: Int, val chapterPages: Int) : Anchor

        /** 旧 bookmark 十进制串（原样保留，用于 `bookmarkToPage` 同会话解析） */
        data class LegacyMark(val mark: String) : Anchor

        /** 无法识别（脏数据），调用方按「不可用」处理 */
        data object Invalid : Anchor
    }

    /** 编码比例锚点；分量不自洽时返回 null（调用方退化为旧串或拒绝写入）。 */
    fun encodeRatio(chapter: Int, pageInChapter: Int, chapterPages: Int): String? =
        Anchor.Ratio(chapter, pageInChapter, chapterPages)
            .takeIf { it.isValid() }
            ?.let { "$PREFIX${it.chapter}$SEPARATOR${it.pageInChapter}$SEPARATOR${it.chapterPages}" }

    /** 解析 anchor（null/空串 → Invalid）。 */
    fun parse(anchor: String?): Anchor = when {
        anchor.isNullOrBlank() -> Anchor.Invalid
        anchor.startsWith(PREFIX) -> parseRatio(anchor.removePrefix(PREFIX))
        else -> anchor.toLongOrNull()?.let { Anchor.LegacyMark(it.toString()) } ?: Anchor.Invalid
    }

    /** 新加书签与既有书签是否同一位置（仓储层去重用）。混合格式无法比较 → 视为不同。 */
    fun isSamePosition(candidate: Anchor, existing: Anchor): Boolean = when {
        candidate is Anchor.Ratio && existing is Anchor.Ratio -> sameRatio(candidate, existing)
        candidate is Anchor.LegacyMark && existing is Anchor.LegacyMark -> candidate.mark == existing.mark
        else -> false
    }

    private fun parseRatio(body: String): Anchor {
        val parts = body.split(SEPARATOR).map { it.toIntOrNull() }
        val ratio = parts.takeIf { it.size == RATIO_PART_COUNT }
            ?.takeIf { part -> part.all { it != null } }
            ?.let { part -> Anchor.Ratio(part[0]!!, part[1]!!, part[2]!!) }
        return ratio?.takeIf { it.isValid() } ?: Anchor.Invalid
    }

    private fun Anchor.Ratio.isValid(): Boolean =
        chapter >= 0 && chapterPages >= 1 && pageInChapter in 0 until chapterPages

    private fun sameRatio(a: Anchor.Ratio, b: Anchor.Ratio): Boolean {
        if (a.chapter != b.chapter) return false
        // 分母不同（排版变化后抓取）时按百分点比较
        val ra = (a.pageInChapter + PAGE_MIDPOINT) / a.chapterPages
        val rb = (b.pageInChapter + PAGE_MIDPOINT) / b.chapterPages
        return kotlin.math.abs(ra - rb) * PERCENT <= DUPLICATE_RATIO_PERCENT + EPSILON
    }
}

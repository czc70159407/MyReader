package com.myreader.feature.viewermupdf

import com.artifex.mupdf.viewer.MuPDFCore
import com.myreader.core.document.EpubProgressCodec
import com.myreader.core.document.ProgressPayload

/**
 * EPUB 流式进度锚点解析（M9 起，M11 改比例锚点；落在 feature:viewer-mupdf，MuPDF 边界内）。
 *
 * 三级回落（docs/m11-plan.md §3、§4.1；依据 S0-2 真机实测）：
 * - L1 **精确**：`Location(chapter, pageInChapter)` 分量在同一排版下零误差
 *   （bookmark long 跨会话返回 `(-1,-1)`，已废弃为主要锚点）；
 * - L2 **比例**：`pageInChap / chapPages`，排版变化（章页数改变）时按比例折算——
 *   直接落章内页码会**静默串到相邻章**（S0-2 C 组实测）；
 * - L3 纯页码兜底，`loc`（bookmark 串）仅兼容 v1 旧数据。
 */
class EpubLocationResolver(private val core: MuPDFCore) {

    /** 章范围缓存：`chapterRange` 是整册扫描，进度每次回发都扫一遍会拖慢滑动（键随页数失效）。 */
    private val rangeCache = HashMap<Int, IntArray>()
    private var rangeCachePageCount = -1

    /** 当前页 → cfi 进度 payload：比例三分量 + 兼容用 bookmark 串。 */
    fun progressForPage(page: Int): ProgressPayload {
        val safePage = page.coerceAtLeast(0)
        val mark = core.pageToBookmark(safePage)
        // 失败码为 Long.MIN_VALUE，合法 bookmark 0（首章首页）仍视为有效锚点
        val loc = if (mark != Long.MIN_VALUE) mark.toString() else null
        val location = core.pageToLocation(safePage)
        val chapPages = location?.let { chapterRange(it[0])?.getOrNull(1) } ?: -1
        val ratio = location?.takeIf { chapPages > 0 }
            ?.let { EpubProgressCodec.RatioAnchor(it[0], it[1], chapPages) }
        return ProgressPayload.Cfi(EpubProgressCodec.encode(loc = loc, page = safePage, ratio = ratio))
    }

    /** 进度 payload → 当前排版页码（越界由调用方钳制）。 */
    fun pageForProgress(payload: ProgressPayload): Int = when (payload) {
        is ProgressPayload.Page -> payload.page.coerceAtLeast(0)
        is ProgressPayload.Cfi -> pageForCfi(payload.cfi)
    }

    private fun pageForCfi(cfi: String): Int {
        val decoded = EpubProgressCodec.decode(cfi) ?: return 0
        val resolved = resolveByLocation(decoded) ?: resolveByMark(decoded) ?: decoded.page
        // L3 的 page 属旧排版坐标（重排后可能越界）：钳到当前排版范围，
        // 避免把越界 index 交给 scrollToItem / 适配器
        val lastIndex = (core.countPages() - 1).coerceAtLeast(0)
        return resolved.coerceIn(0, lastIndex)
    }

    /** L1 精确 → L2 比例；比例分量不可用或不自洽返回 null。 */
    private fun resolveByLocation(decoded: EpubProgressCodec.EpubProgress): Int? {
        if (!decoded.hasRatio) return null
        val exact = exactPage(decoded)
        return exact ?: ratioPage(decoded)
    }

    /**
     * 同排版精确命中。两条都必须成立：
     * 1. 还原后回读 Location 与锚点一致（防 MuPDF 静默串到相邻章）；
     * 2. **该章页数与抓取时相同**——`Location.page` 是章内页码，章自身重新分页后
     *    同一 `(chapter, pageInChapter)` 指向的内容已变（真机：竖屏 12 页时章 0 有 4 页，
     *    横屏 30 页时章 0 约 10 页，仅靠条件 1 会留在章内 30% 处而非原 87% 处）。
     */
    private fun exactPage(decoded: EpubProgressCodec.EpubProgress): Int? {
        val chapterPagesNow = chapterRange(decoded.chIdx)?.getOrNull(1)
        val page = core.pageFromLocation(decoded.chIdx, decoded.pageInChap)
        val back = if (page >= 0) core.pageToLocation(page) else null
        val matched = back != null && back[0] == decoded.chIdx && back[1] == decoded.pageInChap
        val samePagination = chapterPagesNow == decoded.chapPages
        return page.takeIf { it >= 0 && matched && samePagination }
    }

    /**
     * 章页数变化（relayout）时按章内比例折算到新排版页码。
     * 公式与 `MuPDFCore.layout()` 的旧页 remap **同口径**（`(cp + 0.5) / 旧章页数`），
     * 两侧改动需同步，否则「旋转后位置」与「重进后位置」会不一致。
     */
    private fun ratioPage(decoded: EpubProgressCodec.EpubProgress): Int? {
        val range = chapterRange(decoded.chIdx)?.takeIf { it[1] > 0 } ?: return null
        val ratio = (decoded.pageInChap + PAGE_MIDPOINT) / decoded.chapPages
        val offset = (ratio * range[1]).toInt().coerceIn(0, range[1] - 1)
        return range[0] + offset
    }

    /** L3 兼容：v1 payload 的 bookmark 串（同会话可解析，跨会话会返回 -2）。 */
    private fun resolveByMark(decoded: EpubProgressCodec.EpubProgress): Int? =
        decoded.loc?.toLongOrNull()?.let { core.bookmarkToPage(it) }?.takeIf { it >= 0 }

    /** 带缓存的章范围查询：页数（即排版）变化时整表失效，避免进度回发反复整册扫描。 */
    private fun chapterRange(chapter: Int): IntArray? {
        val current = core.countPages()
        if (current != rangeCachePageCount) {
            rangeCache.clear()
            rangeCachePageCount = current
        }
        return rangeCache[chapter] ?: core.chapterRange(chapter)?.also { rangeCache[chapter] = it }
    }

    private companion object {
        /** 比例折算的屏中点：以章内页中心代表该屏位置，避免边界页前后跳动。 */
        const val PAGE_MIDPOINT = 0.5
    }
}

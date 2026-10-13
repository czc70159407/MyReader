package com.myreader.feature.viewermupdf

import com.artifex.mupdf.viewer.MuPDFCore
import com.myreader.core.document.EpubProgressCodec
import com.myreader.core.document.ProgressPayload

/**
 * EPUB 流式进度锚点解析（M9，落在 feature:viewer-mupdf，MuPDF 边界内）。
 *
 * 三级回落（docs/m9-plan.md）：
 * - L1 bookmark 串（[MuPDFCore.pageToBookmark]/[bookmarkToPage]）跨重排稳定，首选；
 * - L3 纯页码（payload 内 page）兜底。
 * 页码 → [ProgressPayload.Cfi]（含 loc + page）；Cfi → 当前排版页码。
 */
class EpubLocationResolver(private val core: MuPDFCore) {

    /** 当前页 → cfi 进度 payload。bookmark 取不到时 loc=null，回落 L3 纯页码。 */
    fun progressForPage(page: Int): ProgressPayload {
        val safePage = page.coerceAtLeast(0)
        val mark = core.pageToBookmark(safePage)
        val loc = if (mark > 0L) mark.toString() else null
        return ProgressPayload.Cfi(EpubProgressCodec.encode(loc = loc, page = safePage))
    }

    /** 进度 payload → 当前排版页码（越界由调用方钳制）。Cfi 优先 L1 loc，其次 payload 内 page。 */
    fun pageForProgress(payload: ProgressPayload): Int = when (payload) {
        is ProgressPayload.Page -> payload.page.coerceAtLeast(0)
        is ProgressPayload.Cfi -> {
            val decoded = EpubProgressCodec.decode(payload.cfi)
            val loc = decoded?.loc
            when {
                decoded == null -> 0
                loc != null -> {
                    val mark = loc.toLongOrNull()
                    val page = mark?.let { core.bookmarkToPage(it) } ?: -1
                    if (page >= 0) page else decoded.page
                }
                else -> decoded.page
            }
        }
    }
}

package com.myreader.core.document

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * EPUB 流式进度 payload 编解码（M9 起，M11 升 v2；纯逻辑、无 Android 依赖，便于 JVM 单测）。
 *
 * 锚点三级回落（docs/m11-plan.md §4.1）：
 * - L1 比例：`chIdx + pageInChap / chapPages`（M11，跨会话且跨排版）；
 * - L2 精确 Location 分量：同为 `chIdx + pageInChap`，但以 `chapPages` 命中同一排版时零误差；
 * - L3 纯页码（`page`）兜底，`loc`（bookmark 串）仅作历史兼容——M11 真机实测跨会话失效。
 *
 * 兼容门禁：`v` 超出 `1..VERSION` 或脏输入一律拒绝（返回 null），调用方据此回落安全默认。
 * v1 payload 无比例字段 → `hasRatio=false`，按 L3 语义读。
 */
object EpubProgressCodec {

    /** 当前写入版本；`decode` 向前兼容 `1..VERSION`。 */
    const val VERSION = 2

    private const val MIN_SUPPORTED_VERSION = 1

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    internal data class Payload(
        val v: Int,
        val loc: String? = null,
        val page: Int = 0,
        val chapter: String? = null,
        /** 章索引（v2；-1 表示缺省） */
        val chIdx: Int = -1,
        /** 章内页码（v2；-1 表示缺省） */
        val pageInChap: Int = -1,
        /** 抓取时刻该章页数（v2；比例分母，-1 表示缺省） */
        val chapPages: Int = -1,
    )

    /** 章内比例锚点三元组（不自洽时调用方不应构造；`encode` 亦会丢弃非法值）。 */
    data class RatioAnchor(val chapter: Int, val pageInChapter: Int, val chapterPages: Int) {
        val isValid: Boolean
            get() = chapter >= 0 && chapterPages >= 1 && pageInChapter in 0 until chapterPages
    }

    /** 解码结果（对外只读视图）。 */
    data class EpubProgress(
        val version: Int,
        val loc: String?,
        val page: Int,
        val chapter: String?,
        val chIdx: Int,
        val pageInChap: Int,
        val chapPages: Int,
    ) {
        /** 章内比例锚点是否可用：三个分量齐全且自洽（否则调用方回落 L3 页码）。 */
        val hasRatio: Boolean
            get() = chIdx >= 0 && chapPages >= 1 && pageInChap in 0 until chapPages
    }

    /** 编码为 JSON 串（写库 progressPayload）。`ratio` 缺省或非法时退化为纯 loc/page 语义。 */
    fun encode(loc: String?, page: Int, chapter: String? = null, ratio: RatioAnchor? = null): String {
        val valid = ratio?.takeIf { it.isValid }
        return json.encodeToString(
            Payload.serializer(),
            Payload(
                v = VERSION,
                loc = loc,
                page = page.coerceAtLeast(0),
                chapter = chapter,
                chIdx = valid?.chapter ?: -1,
                pageInChap = valid?.pageInChapter ?: -1,
                chapPages = valid?.chapterPages ?: -1,
            ),
        )
    }

    /**
     * 解码并校验。返回 null 表示不可用（空串 / JSON 损坏 / 版本不在 `1..VERSION` / loc 与 page 均缺失）。
     * 容忍未知字段（向前兼容）；比例分量不自洽时**只降级比例**（置 -1），不丢整条进度。
     */
    fun decode(payload: String?): EpubProgress? {
        if (payload.isNullOrBlank()) return null
        val p = try {
            json.decodeFromString(Payload.serializer(), payload)
        } catch (_: Exception) {
            return null
        }
        if (p.v !in MIN_SUPPORTED_VERSION..VERSION) return null
        // loc 优先；无 loc 时至少要有非负页码兜底，否则视为脏数据
        val hasLoc = !p.loc.isNullOrBlank()
        if (!hasLoc && p.page < 0) return null
        val ratioOk = p.chIdx >= 0 && p.chapPages >= 1 && p.pageInChap in 0 until p.chapPages
        return EpubProgress(
            version = p.v,
            loc = p.loc?.takeIf { it.isNotBlank() },
            page = p.page.coerceAtLeast(0),
            chapter = p.chapter,
            chIdx = if (ratioOk) p.chIdx else -1,
            pageInChap = if (ratioOk) p.pageInChap else -1,
            chapPages = if (ratioOk) p.chapPages else -1,
        )
    }
}

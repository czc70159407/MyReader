package com.myreader.core.document

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * EPUB 流式进度 payload 编解码（M9，纯逻辑、无 Android 依赖，便于 JVM 单测）。
 *
 * 进度锚点采用三级回落（docs/m9-plan.md「关键技术决策」）：
 * - L1 MuPDF bookmark 串（`loc`，跨重排稳定的 long bookmark 十进制串）——首选；
 * - L2 章节索引 + 章内偏移；
 * - L3 纯页码（`page`）。
 * 本 payload 以 `loc` 为权威锚点，`page` 仅作 UI 展示/无 loc 时的兜底，`chapter` 供调试与展示。
 *
 * 兼容门禁：`version` 不匹配或字段缺失/脏输入一律拒绝（返回 null），调用方据此回落安全默认。
 */
object EpubProgressCodec {

    const val VERSION = 1

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    internal data class Payload(
        val v: Int,
        val loc: String? = null,
        val page: Int = 0,
        val chapter: String? = null,
    )

    /** 解码结果（对外只读视图）。 */
    data class EpubProgress(
        val version: Int,
        val loc: String?,
        val page: Int,
        val chapter: String?,
    )

    /** 编码为 JSON 串（写库 progressPayload）。 */
    fun encode(loc: String?, page: Int, chapter: String? = null): String =
        json.encodeToString(Payload.serializer(), Payload(v = VERSION, loc = loc, page = page.coerceAtLeast(0), chapter = chapter))

    /**
     * 解码并校验。返回 null 表示不可用（空串 / JSON 损坏 / 版本不匹配 / loc 与 page 均缺失）。
     * 容忍未知字段（向前兼容），但拒绝 version > 当前（旧 App 不认识的未来版本按不可用处理）。
     */
    fun decode(payload: String?): EpubProgress? {
        if (payload.isNullOrBlank()) return null
        val p = try {
            json.decodeFromString(Payload.serializer(), payload)
        } catch (_: Exception) {
            return null
        }
        if (p.v != VERSION) return null
        // loc 优先；无 loc 时至少要有非负页码兜底，否则视为脏数据
        val hasLoc = !p.loc.isNullOrBlank()
        if (!hasLoc && p.page < 0) return null
        return EpubProgress(
            version = p.v,
            loc = p.loc?.takeIf { it.isNotBlank() },
            page = p.page.coerceAtLeast(0),
            chapter = p.chapter,
        )
    }
}

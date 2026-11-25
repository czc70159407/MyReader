package com.myreader.core.document

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 高亮锚点 v2：文本指纹锚定（M16，纯逻辑、无 Android 依赖，便于 JVM 单测）。
 *
 * 背景：M15 垂直 EPUB 排版基准从固定 312×504pt 改为「视口宽 + 字号」后，M12 的
 * 归一化页面比例 quad（v1）系统性失效（历史高亮错位）。v2 记录「章节 Location 分量 +
 * 选中文本 + 前后文」，渲染时按当前页文本重新匹配重建 quad——换字号/旋转后仍贴合原文。
 *
 * 存储：`annotations.anchor`（TEXT，无 schema 变更）；版本门禁同 [EpubProgressCodec]：
 * `v` 超出 2..VERSION 或脏输入一律拒绝（返回 null，调用方按 v1 降级不绘制）。
 * 固定版式（pdf/cbz/xps）高亮仍用 v1 归一化 quad（无重排，保持现状）。
 */
object AnnotationAnchorV2Codec {

    const val VERSION = 2

    private const val MIN_SUPPORTED_VERSION = 2

    /** 前后文指纹最大长度（字符） */
    private const val CONTEXT_MAX = 20

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    internal data class Payload(
        val v: Int,
        val ch: Int,
        val pi: Int,
        val q: String,
        val pre: String? = null,
        val post: String? = null,
        /** 位置提示：quote 首字符的归一化左上坐标（多处同文命中时消歧；0,0 = 无提示） */
        val nx: Float = 0f,
        val ny: Float = 0f,
    )

    /** 解码结果（对外只读视图） */
    data class AnchorV2(
        val chapter: Int,
        val pageInChapter: Int,
        val quote: String,
        val pre: String?,
        val post: String?,
        val hintX: Float,
        val hintY: Float,
    )

    /**
     * 编码。quote 空串 / Location 分量非法时返回 null（调用方回落 v1 或不落库）；
     * pre 截取尾部、post 截取头部各 ≤20 字符。
     */
    @Suppress("LongParameterList")   // 锚点字段语义化展开，参数对象化会降低可读性（同项目先例）
    fun encode(
        chapter: Int,
        pageInChapter: Int,
        quote: String,
        pre: String? = null,
        post: String? = null,
        hintX: Float = 0f,
        hintY: Float = 0f,
    ): String? {
        val q = quote.trim()
        if (q.isEmpty() || chapter < 0 || pageInChapter < 0) return null
        return json.encodeToString(
            Payload.serializer(),
            Payload(
                v = VERSION,
                ch = chapter,
                pi = pageInChapter,
                q = q,
                pre = pre?.trim()?.takeLast(CONTEXT_MAX)?.takeIf { it.isNotEmpty() },
                post = post?.trim()?.take(CONTEXT_MAX)?.takeIf { it.isNotEmpty() },
                nx = hintX.coerceIn(0f, 1f),
                ny = hintY.coerceIn(0f, 1f),
            ),
        )
    }

    /** 解码并校验；null = 非 v2 / 脏数据（调用方按 v1 降级处理，不绘制） */
    @Suppress("ReturnCount")   // 前置校验逐项拒绝，比嵌套 if 更直观（同项目 ReturnCount 先例）
    fun decode(anchor: String?): AnchorV2? {
        if (anchor.isNullOrBlank()) return null
        val payload = try {
            json.decodeFromString(Payload.serializer(), anchor)
        } catch (_: Exception) {
            return null
        }
        if (payload.v !in MIN_SUPPORTED_VERSION..VERSION) return null
        if (payload.ch < 0 || payload.pi < 0 || payload.q.isBlank()) return null
        return AnchorV2(
            chapter = payload.ch,
            pageInChapter = payload.pi,
            quote = payload.q,
            pre = payload.pre?.takeIf { it.isNotBlank() },
            post = payload.post?.takeIf { it.isNotBlank() },
            hintX = payload.nx.coerceIn(0f, 1f),
            hintY = payload.ny.coerceIn(0f, 1f),
        )
    }
}

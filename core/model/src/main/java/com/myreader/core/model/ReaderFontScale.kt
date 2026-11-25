package com.myreader.core.model

/**
 * 阅读字号档位（M15）：作用于流式重排格式（epub），固定版式（pdf/cbz/xps）不适用。
 *
 * [em] 为 MuPDF reflow 排版字号（pt）：水平模式 relayout 与垂直模式打开重排均以此值排版；
 * DEFAULT(10) 与 MuPDFCore 默认 layoutEM 对齐，默认档与历史行为完全等价（零回归）。
 * 持久化以 name 字符串存储（纪律同 ThemeMode，枚举项可重排/插入，不依赖 ordinal）。
 */
@Suppress("MagicNumber")
enum class ReaderFontScale(val em: Float, val label: String) {
    SMALL(8f, "小"),
    MEDIUM_SMALL(9f, "较小"),
    DEFAULT(10f, "标准"),
    MEDIUM_LARGE(12f, "较大"),
    LARGE(14f, "大"),
    ;

    companion object {
        /** 读失败 / 未知 name 的回落档位 */
        val FALLBACK = DEFAULT
    }
}

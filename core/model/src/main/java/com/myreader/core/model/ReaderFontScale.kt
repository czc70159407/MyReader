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
    // 档位间距须保证相邻档视觉可辨（真机教训：原 8/9/10 相邻仅差 12.5%，用户切「小↔较小」无感误判为功能失效）；
    // 现相邻差 33%/25%/30%/23%，标准=10 与 MuPDF 默认排版对齐（零回归）。
    SMALL(6f, "小"),
    MEDIUM_SMALL(8f, "较小"),
    DEFAULT(10f, "标准"),
    MEDIUM_LARGE(13f, "较大"),
    LARGE(16f, "大"),
    ;

    companion object {
        /** 读失败 / 未知 name 的回落档位 */
        val FALLBACK = DEFAULT
    }
}

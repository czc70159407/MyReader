package com.myreader.core.model

/**
 * 固定版式（pdf/cbz/xps）默认显示缩放档位（M15）：相对「适合宽度」的放大倍数。
 *
 * 仅水平模式生效（垂直模式 PDF 缩放为路线 A 拍板后置项）；FIT_WIDTH(1.0) 与上游
 * ReaderView mScale=1.0（fit-to-view 语义）等价，默认档零回归。字号与缩放是两个
 * 独立偏好（reflowable 用字号、固定版式用缩放），UI 共用入口按格式分派。
 * 持久化以 name 字符串存储（纪律同 ThemeMode）。
 */
@Suppress("MagicNumber")
enum class ReaderZoomScale(val factor: Float, val label: String) {
    FIT_WIDTH(1.0f, "适合宽度"),
    ZOOM_125(1.25f, "125%"),
    ZOOM_150(1.5f, "150%"),
    ZOOM_200(2.0f, "200%"),
    ZOOM_300(3.0f, "300%"),
    ;

    companion object {
        /** 读失败 / 未知 name 的回落档位 */
        val FALLBACK = FIT_WIDTH
    }
}

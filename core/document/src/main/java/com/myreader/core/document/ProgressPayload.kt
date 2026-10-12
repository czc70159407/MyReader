package com.myreader.core.document

/** 进度模型：页码式（pdf/cbz/xps）与流式（epub CFI）统一由引擎回调，ViewModel 按类型分发写库 */
sealed class ProgressPayload {

    /** 页码式：页号 + 页内垂直偏移（垂直滚动模式用） */
    data class Page(val page: Int, val scrollOffsetY: Int = 0) : ProgressPayload()

    /** 流式：CFI 等重排定位串，序列化后存 progressPayload */
    data class Cfi(val cfi: String) : ProgressPayload()
}


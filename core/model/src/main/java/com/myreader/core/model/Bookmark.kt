package com.myreader.core.model

/** 每文档书签上限（M10 S0 拍板）：防单文档滥用拖慢面板；超限拒绝并提示，不静默丢弃 */
const val MAX_BOOKMARKS_PER_DOCUMENT = 100

/**
 * 书签（M10，章节级）：记录阅读位置的稳定锚点。
 *
 * 锚点双轨：页码式（pdf/cbz/xps/txt）`anchor == null`，位置 = [page] + [scrollOffsetY]；
 * 流式（epub）[anchor] 为 bookmark 十进制串（跨重排稳定），[page] 仅用于排序与锚点失效时回落。
 */
data class Bookmark(
    val id: Long,
    val documentId: Long,
    val page: Int,
    val scrollOffsetY: Int,
    val anchor: String?,
    /** 列表展示快照（「第 N 页」/「第 N 屏」），创建时生成，重排不回溯改写 */
    val label: String,
    val createdAt: Long,
)

/** 添加结果：重复位置与上限拒绝是业务分支（不抛异常），由 UI 给不同提示 */
enum class BookmarkAddResult {
    ADDED,
    DUPLICATE,
    LIMIT_REACHED,
}

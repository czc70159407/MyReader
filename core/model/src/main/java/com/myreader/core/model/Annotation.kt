package com.myreader.core.model

/** 每文档高亮/笔记上限（M12 S0 拍板）：防单文档滥用拖慢面板；超限拒绝并提示，不静默丢弃 */
const val MAX_ANNOTATIONS_PER_DOCUMENT = 200

/** 高亮色值（ARGB）：M12 默认黄色（S0-3 决策） */
const val ANNOTATION_COLOR_YELLOW: Int = 0xFFFFEB3B.toInt()

/** 高亮可选色板（色值 ARGB + 读屏名，M12 改色扩展）：黄 / 绿 / 蓝 / 粉 / 橙 */
val ANNOTATION_COLORS: List<Pair<Int, String>> = listOf(
    ANNOTATION_COLOR_YELLOW to "黄色",
    0xFF66BB6A.toInt() to "绿色",
    0xFF42A5F5.toInt() to "蓝色",
    0xFFEC407A.toInt() to "粉色",
    0xFFFFA726.toInt() to "橙色",
)

/** 批注类型：高亮（选中文本着色）/ 笔记（高亮 + 备注正文） */
enum class AnnotationType {
    HIGHLIGHT,
    NOTE,
}

/**
 * 高亮/笔记（M12，文本级）：锚点为「页码 + 归一化 quad 列表」。
 *
 * - PDF（固定排版）：[page] + [anchor]（归一化 quad，相对页面宽高 0..1），跨会话稳定；
 * - EPUB（reflowable）：[page] 为创建时解析页码，[anchor] 为比例 quad（跨重排按比例换算）。
 *
 * [text] 为选中文本快照（列表展示/检索）；[note] 仅 [AnnotationType.NOTE] 非空。
 */
data class Annotation(
    val id: Long,
    val documentId: Long,
    val type: AnnotationType,
    val page: Int,
    val anchor: String,
    val color: Int,
    val text: String,
    val note: String?,
    val createdAt: Long,
)

/** 添加结果：重复位置与上限拒绝是业务分支（不抛异常），由 UI 给不同提示 */
enum class AnnotationAddResult {
    ADDED,
    DUPLICATE,
    LIMIT_REACHED,
}

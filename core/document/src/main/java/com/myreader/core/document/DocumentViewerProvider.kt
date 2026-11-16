package com.myreader.core.document

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import com.myreader.core.model.ANNOTATION_COLOR_YELLOW
import com.myreader.core.model.ReaderMode

/**
 * M12：高亮/笔记草稿（渲染端选中 → 状态侧落库；状态侧回放 → 渲染端绘制）。
 *
 * [anchor] 为归一化 quad JSON（`[[ulx,uly,urx,ury,llx,lly,lrx,lry], ...]`，相对页面宽高 0..1），
 * 跨会话/跨窗口尺寸稳定；[text] 为选中文本快照；[color] 为高亮 ARGB 色值（新建默认黄色）。
 */
data class AnnotationDraft(
    val page: Int,
    val anchor: String,
    val text: String,
    val color: Int = ANNOTATION_COLOR_YELLOW,
)

/**
 * M14：文本选择完成事件（渲染端 → 状态侧）。
 * [draft] 为归一化锚点草稿（高亮落库用）；[anchorX]/[anchorY] 为选中区域屏幕锚点窗口坐标（px，气泡菜单定位用）。
 * 用两个 Float 而非 Compose 几何类型，保持 core:document 接口不耦合 UI 几何对象。
 */
data class TextSelectionEvent(
    val draft: AnnotationDraft,
    val anchorX: Float,
    val anchorY: Float,
)

/**
 * 渲染 Composable 提供者：feature:reader 经 [DocumentEngineRouter.viewerFor] 获取，
 * 在 Compose 上下文调用 [Content] 渲染；缩略图生成走普通 suspend 方法（导入流程可用）。
 */
interface DocumentViewerProvider {

    /** 阅读器渲染主体；实现为 AndroidView 桥接（如 MuPDF ReaderView）。
     * 注意：接口方法参数不带默认值——Compose 接口默认参数 + 实现会生成冲突重载桥
     *
     * @param onPageCountChanged 会话内排版页数发生变化时回调（典型：epub 水平模式 relayout
     *   完成后页数与打开时快照不一致）；打开初值无需回调（调用方以 Ready.pageCount 持有快照，
     *   经实时值覆盖展示）。实现须在主线程调用。
     * @param onTapContent 内容区点按（水平模式非边缘区 / 垂直模式静止点按）→ 切换顶栏显隐（M12 沉浸模式）。
     *   水平模式边缘点按仍翻页、不回调；垂直模式滚动会取消 tap，无手势冲突。
     * @param onUserScroll 用户翻页 / 滚动 / 缩放 → 隐藏顶栏（M12 沉浸模式）。实现应在主线程调用。
     * @param onTextSelected 文本选择完成（长按/拖动松手）→ 上报选择事件（归一化锚点草稿 + 屏幕锚点，M14 气泡菜单）；
     *   上报 null 表示「清除当前选中」（气泡非聚焦后点选区外空白由渲染端触发，M14 手柄交互前提）。
     * @param annotations 已保存的高亮/笔记（渲染端据此回放原生 annotation）；reflowable 暂不消费。
     * @param activeSelection 当前选中的草稿（M14 选中视觉反馈）：非 null 时渲染端按主题色绘制选区 + 手柄，
     *   null 表示无选中（气泡关闭 / 动作执行 / 翻页后清除）。与 [annotations]（持久高亮）分通道，不落库。 */
    @Composable
    @Suppress("LongParameterList")
    fun Content(
        doc: Document,
        initialProgress: ProgressPayload,
        mode: ReaderMode,
        onProgressChanged: (ProgressPayload) -> Unit,
        onPageCountChanged: (Int) -> Unit,
        onTapContent: () -> Unit,
        onUserScroll: () -> Unit,
        onTextSelected: (TextSelectionEvent?) -> Unit,
        annotations: List<AnnotationDraft>,
        activeSelection: AnnotationDraft?,
        modifier: Modifier,
    )

    /** 渲染首页缩略图（导入验证 / 首开兜底用）；调用方负责 doc 生命周期 */
    suspend fun thumbnail(doc: Document): ImageBitmap
}

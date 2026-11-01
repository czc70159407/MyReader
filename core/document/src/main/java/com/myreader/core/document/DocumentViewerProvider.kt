package com.myreader.core.document

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import com.myreader.core.model.ReaderMode

/**
 * M12：高亮/笔记草稿（渲染端选中 → 状态侧落库；状态侧回放 → 渲染端绘制）。
 *
 * [anchor] 为归一化 quad JSON（`[[ulx,uly,urx,ury,llx,lly,lrx,lry], ...]`，相对页面宽高 0..1），
 * 跨会话/跨窗口尺寸稳定；[text] 为选中文本快照。
 */
data class AnnotationDraft(
    val page: Int,
    val anchor: String,
    val text: String,
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
     * @param onTextSelected 文本选择完成（长按/拖动松手）→ 上报归一化锚点草稿（M12 高亮/笔记）。
     * @param annotations 已保存的高亮/笔记（渲染端据此回放原生 annotation）；reflowable 暂不消费。 */
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
        onTextSelected: (AnnotationDraft) -> Unit,
        annotations: List<AnnotationDraft>,
        modifier: Modifier,
    )

    /** 渲染首页缩略图（导入验证 / 首开兜底用）；调用方负责 doc 生命周期 */
    suspend fun thumbnail(doc: Document): ImageBitmap
}

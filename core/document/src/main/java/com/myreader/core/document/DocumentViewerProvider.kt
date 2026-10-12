package com.myreader.core.document

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap

/**
 * 渲染 Composable 提供者：feature:reader 经 [DocumentEngineRouter.viewerFor] 获取，
 * 在 Compose 上下文调用 [Content] 渲染；缩略图生成走普通 suspend 方法（导入流程可用）。
 */
interface DocumentViewerProvider {

    /** 阅读器渲染主体；实现为 AndroidView 桥接（如 MuPDF ReaderView）。
     * 注意：接口方法参数不带默认值——Compose 接口默认参数 + 实现会生成冲突重载桥 */
    @Composable
    fun Content(
        doc: Document,
        initialProgress: ProgressPayload,
        mode: ReaderMode,
        onProgressChanged: (ProgressPayload) -> Unit,
        modifier: Modifier,
    )

    /** 渲染首页缩略图（导入验证 / 首开兜底用）；调用方负责 doc 生命周期 */
    suspend fun thumbnail(doc: Document): ImageBitmap
}

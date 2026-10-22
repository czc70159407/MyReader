package com.myreader.feature.viewermupdf

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.myreader.core.document.Document
import com.myreader.core.document.DocumentViewerProvider
import com.myreader.core.document.ProgressPayload
import com.myreader.core.model.ReaderMode
import javax.inject.Inject

/**
 * MuPDF 渲染提供者（@FileTypeKey("pdf") 注册）。
 * thumbnail：首页渲染（导入验证 / 首开兜底），供书架缩略图。
 * Content：阅读器渲染主体——AndroidView 桥接 ReaderView（瓦片渲染 + BitmapPool）。
 */
class MuPdfViewerProvider @Inject constructor() : DocumentViewerProvider {

    override suspend fun thumbnail(doc: Document): ImageBitmap =
        (doc as MuPdfDocument).renderPageBitmap(page = 0, width = 480).asImageBitmap()

    @Composable
    override fun Content(
        doc: Document,
        initialProgress: ProgressPayload,
        mode: ReaderMode,
        onProgressChanged: (ProgressPayload) -> Unit,
        onPageCountChanged: (Int) -> Unit,
        modifier: Modifier,
    ) {
        val muDoc = doc as MuPdfDocument
        val reflowable = muDoc.reflowable
        if (muDoc.pageCount <= 0) {
            // 空文档/页数未知：ReaderView 依赖 adapter count，兜底不进桥接
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "无可显示的页面", modifier = Modifier.padding(16.dp))
            }
            return
        }
        when (mode) {
            // M4 水平分页：AndroidView 桥接上游 ReaderView（瓦片渲染 + BitmapPool）
            ReaderMode.HORIZONTAL -> MuPdfReaderView(
                core = muDoc.core,
                reflowable = reflowable,
                initialProgress = initialProgress,
                onProgressChanged = onProgressChanged,
                onPageCountChanged = onPageCountChanged,
                modifier = modifier,
            )
            // M7 垂直连续滚动：LazyColumn + 自管理位图（只依赖 Document 接口，MuPDF 边界仍在模块内）。
            // 垂直模式不 relayout（epub 沿用打开时排版，页数恒定），无需页数回调
            ReaderMode.VERTICAL -> MuPdfVerticalView(
                doc = doc,
                reflowable = reflowable,
                initialProgress = initialProgress,
                onProgressChanged = onProgressChanged,
                modifier = modifier,
            )
        }
    }
}

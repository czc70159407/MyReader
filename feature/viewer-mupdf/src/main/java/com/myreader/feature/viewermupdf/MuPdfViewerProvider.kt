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
import com.myreader.core.document.ReaderMode
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
        modifier: Modifier,
    ) {
        val muDoc = doc as MuPdfDocument
        val initialPage = (initialProgress as? ProgressPayload.Page)?.page ?: 0
        if (muDoc.pageCount <= 0) {
            // 空文档/页数未知：ReaderView 依赖 adapter count，兜底不进桥接
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "无可显示的页面", modifier = Modifier.padding(16.dp))
            }
            return
        }
        // M4 仅水平分页（mode 由设置页 M7 引入）；MuPDF ReaderView 即水平模式实现
        MuPdfReaderView(
            core = muDoc.core,
            initialPage = initialPage,
            onProgressChanged = onProgressChanged,
            modifier = modifier,
        )
    }
}

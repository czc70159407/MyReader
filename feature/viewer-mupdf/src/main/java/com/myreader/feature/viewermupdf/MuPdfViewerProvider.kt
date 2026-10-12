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
 * Content：阅读器渲染主体——M4 里程碑落地 ReaderView 桥接，M3 占位。
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
        // M4 里程碑：AndroidView 桥接 ReaderView（瓦片渲染 + BitmapPool）+ 跳页 + 大纲导航
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = "阅读器渲染将在 M4 提供", modifier = Modifier.padding(16.dp))
        }
    }
}

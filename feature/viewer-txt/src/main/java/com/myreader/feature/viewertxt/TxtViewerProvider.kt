package com.myreader.feature.viewertxt

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.myreader.core.document.Document
import com.myreader.core.document.DocumentViewerProvider
import com.myreader.core.document.ProgressPayload
import com.myreader.core.model.ReaderMode
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject

/**
 * 纯文本渲染提供者（M9-F，`@FileTypeKey("txt")` 注册）。
 *
 * Content：LazyColumn 每页一项连续滚动（txt 不区分水平/垂直，验证模块统一连续滚动），
 * 页码式进度经 [ProgressPayload.Page] 回写——与 MuPDF 垂直视图同型（首帧 remember 取恢复值 +
 * snapshotFlow 回流 + lastEmitted 守卫防跳页回环）。
 */
class TxtViewerProvider @Inject constructor() : DocumentViewerProvider {

    override suspend fun thumbnail(doc: Document): ImageBitmap =
        (doc as? TxtDocument)?.renderPageBitmap(page = 0, width = 480)?.asImageBitmap()
            ?: ImageBitmap(1, 1)

    @Composable
    override fun Content(
        doc: Document,
        initialProgress: ProgressPayload,
        mode: ReaderMode,
        onProgressChanged: (ProgressPayload) -> Unit,
        modifier: Modifier,
    ) {
        val txtDoc = doc as? TxtDocument
        if (txtDoc == null) {
            Box(modifier = Modifier.fillMaxSize()) {
                Text(
                    "无法显示此文档",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            return
        }
        val start = (initialProgress as? ProgressPayload.Page) ?: ProgressPayload.Page(0)
        val listState = rememberLazyListState()
        val latestCallback by rememberUpdatedState(onProgressChanged)

        // 初始 = 恢复页；区分「自身滚动回流」与「外部跳页」
        var lastEmitted by remember(txtDoc) { mutableStateOf(start.page) }
        // 恢复完成前抑制回写，避免首帧 (0,0) 覆盖恢复进度（M9 review M6）
        var restored by remember(txtDoc) { mutableStateOf(false) }

        // 一次性恢复进度（组合期初值即就位，scrollToItem 锚定 item 顶部 + 像素偏移）
        LaunchedEffect(txtDoc) {
            listState.scrollToItem(start.page, start.scrollOffsetY)
            restored = true
        }

        // 外部跳页差分：initialProgress.page 变化且非自身回流 → 滚到目标页顶部
        LaunchedEffect(txtDoc, start.page) {
            if (start.page != lastEmitted) listState.scrollToItem(start.page, 0)
        }

        // 进度回写：每帧发射（distinctUntilChanged 去重），防抖落库由 VM 承担
        LaunchedEffect(txtDoc) {
            snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                .distinctUntilChanged()
                .collect { (index, offset) ->
                    if (!restored) return@collect
                    lastEmitted = index
                    latestCallback(ProgressPayload.Page(index, offset))
                }
        }

        LazyColumn(state = listState, modifier = modifier.fillMaxSize()) {
            items(txtDoc.pageCount) { page ->
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(text = txtDoc.pageText(page).ifBlank { "（空页）" })
                }
            }
        }
    }
}

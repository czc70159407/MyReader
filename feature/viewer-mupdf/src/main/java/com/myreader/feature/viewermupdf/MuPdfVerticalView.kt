package com.myreader.feature.viewermupdf

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.myreader.core.document.Document
import com.myreader.core.document.ProgressPayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged

/** 页间距（dp） */
private val PAGE_SPACING = 4.dp

/** 占位宽高比（页尺寸未就绪/异常页兜底） */
private const val PLACEHOLDER_RATIO = 4f / 3f

/**
 * 垂直连续滚动渲染（M7）：LazyColumn 每页一项，位图经 [VerticalPageLoader] 异步渲染 + 字节预算缓存。
 *
 * 进度：firstVisibleItemIndex + 项内像素偏移 → [ProgressPayload.Page] 回写（防抖落库由 VM 承担）。
 * 恢复：一次性 scrollToItem(initialPage, initialOffsetY)——LazyList 锚定「item 顶部 + 像素偏移」，
 * 后续占位高度收敛为实测高度不影响锚点；跨宽度的旧偏移存在近似误差（已知限制，见 docs/ui-design.md）。
 * 外部跳页（跳页框/大纲写 uiPage）：经 initialProgress 差分触发 scrollToItem(page, 0)——
 * [lastEmittedPage] 守卫区分「自身滚动回流」（page 相等 no-op）与「外部跳页」，防跳转回环。
 *
 * M9 重排（reflowable，如 epub）：进度回调改走 CFI 锚点（[EpubLocationResolver]）；
 * 垂直模式沿用 MuPDFCore 默认固定排版（312×504pt）不 relayout，故 [Document.pageCount] 稳定、
 * LazyColumn 项数不变——重排拟合由水平 ReaderView 承担（见 [MuPdfReaderView]）。
 */
@Composable
internal fun MuPdfVerticalView(
    doc: Document,
    reflowable: Boolean,
    initialProgress: ProgressPayload,
    onProgressChanged: (ProgressPayload) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val loader = remember(doc) { VerticalPageLoader(doc) }
    val latestCallback by rememberUpdatedState(onProgressChanged)
    val resolver = remember(doc) { if (reflowable) EpubLocationResolver((doc as MuPdfDocument).core) else null }

    // 初始恢复锚点：重排文档把 Cfi 解析为当前排版页码；页码式取 Page(page/offset)
    // （ProgressPayload 为 data class，remember 按结构相等判定，同内容 payload 不会重复解析）
    val initPage = remember(initialProgress) {
        when {
            resolver != null -> resolver.pageForProgress(initialProgress)
            initialProgress is ProgressPayload.Page -> initialProgress.page
            else -> 0
        }
    }
    val initOffsetY = (initialProgress as? ProgressPayload.Page)?.scrollOffsetY ?: 0

    // 滚动回流守卫：初始 = 恢复页；snapshotFlow 发射时同步更新（见下方 collect）
    var lastEmittedPage by remember(doc) { mutableStateOf(initPage) }
    // 恢复完成前抑制进度回写，避免首帧 (0,0) 覆盖库中进度（与 TxtViewerProvider 同款守卫，T4；
    // 此前仅靠 lastEmittedPage 初值 + VM 600ms 防抖间接兜底，同类竞态两视图封堵程度不一致）
    var restored by remember(doc) { mutableStateOf(false) }

    DisposableEffect(doc) {
        onDispose { loader.evictAll() }
    }

    // 一次性恢复进度（doc 换新会话时重新锚定；模式切换是新组合，天然重执行）
    LaunchedEffect(doc) {
        listState.scrollToItem(initPage, initOffsetY)
        restored = true   // 锚定完成后才放行 snapshotFlow 回写（T4）
    }

    // 外部跳页差分：initPage 变化且非自身滚动回流时，滚到目标页顶部
    LaunchedEffect(doc, initPage) {
        if (initPage != lastEmittedPage) {
            listState.scrollToItem(initPage, 0)
            // 跳页后即使目标页==当前页也强制回发一次，避免「跳后立刻退出」落旧进度（M9 review #9）
            lastEmittedPage = initPage
            val payload = resolver?.progressForPage(initPage) ?: ProgressPayload.Page(initPage, 0)
            latestCallback(payload)
        }
    }

    // 进度回写：每帧发射（distinctUntilChanged 去重），防抖落库由 VM 600ms 承担
    LaunchedEffect(doc) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (index, offset) ->
                if (!restored) return@collect   // 恢复期（含 scrollToItem 触发的中间帧）不回写（T4）
                lastEmittedPage = index
                // 重排文档回调 CFI 锚点；页码式回调页码 + 项内像素偏移
                val payload = resolver?.progressForPage(index) ?: ProgressPayload.Page(index, offset)
                latestCallback(payload)
            }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        val widthPx = constraints.maxWidth

        // 宽度变化（旋转/分屏）：旧宽度位图按 key 自然失配，立即清引用释放内存
        LaunchedEffect(loader, widthPx) { loader.evictAll() }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
        ) {
            items(doc.pageCount) { page ->
                VerticalPageItem(loader = loader, page = page, widthPx = widthPx)
            }
        }
    }
}

/** 单页项：占位（4:3 + 加载圈）→ 页尺寸定高 → 位图就绪替换；渲染失败显示错误文案（不放无限加载圈） */
@Composable
private fun VerticalPageItem(
    loader: VerticalPageLoader,
    page: Int,
    widthPx: Int,
) {
    // 页点尺寸：异步获取；成功结果由 loader 跨重组/跨宽度缓存（防滚动中高度抖动）
    var pageSize by remember(page) { mutableStateOf<Size?>(null) }
    LaunchedEffect(loader, page) {
        pageSize = try {
            loader.pageSize(page)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    // 位图：先同步查缓存（命中免占位闪烁），未命中异步渲染
    var bitmap by remember(page, widthPx) { mutableStateOf(loader.cachedBitmap(page, widthPx)) }
    var loadFailed by remember(page, widthPx) { mutableStateOf(false) }
    LaunchedEffect(loader, page, widthPx) {
        if (bitmap == null) {
            loadFailed = false
            bitmap = try {
                loader.loadBitmap(page, widthPx)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (bitmap == null) loadFailed = true
        }
    }

    val ratio = pageSize
        ?.takeIf { it.width > 0f && it.height > 0f }
        ?.let { it.height / it.width }
        ?: PLACEHOLDER_RATIO
    val density = LocalDensity.current
    val heightPx = widthPx * ratio

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = PAGE_SPACING / 2)
            .height(with(density) { heightPx.toDp() }),
        contentAlignment = Alignment.Center,
    ) {
        val bm = bitmap
        when {
            bm != null -> Image(
                bitmap = bm.asImageBitmap(),
                contentDescription = "第 ${page + 1} 页",
                modifier = Modifier.fillMaxSize(),
            )
            loadFailed -> Text(
                text = "第 ${page + 1} 页渲染失败",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            else -> CircularProgressIndicator()
        }
    }
}

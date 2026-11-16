package com.myreader.feature.viewermupdf

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.artifex.mupdf.viewer.MuPDFCore
import com.myreader.core.document.AnnotationDraft
import com.myreader.core.document.Document
import com.myreader.core.document.ProgressPayload
import com.myreader.core.document.TextSelectionEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlinx.coroutines.withContext

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
 * 外部跳页（跳页框/大纲/书签写 uiProgress）：经 initialProgress 差分触发 scrollToItem(page, offset)——
 * [lastEmitted]（page + 项内偏移对）守卫区分「自身滚动回流」（值与最近发射一致则 no-op）与「外部跳页」，
 * 防跳转回环；书签跳转需落到页内偏移，故不能只比 page（M10-D 抓出：仅比 page 会忽略偏移且回写覆盖为 0）。
 *
 * M9 重排（reflowable，如 epub）：进度回调改走 CFI 锚点（[EpubLocationResolver]）；
 * 垂直模式沿用 MuPDFCore 默认固定排版（312×504pt）不 relayout，故 [Document.pageCount] 稳定、
 * LazyColumn 项数不变——重排拟合由水平 ReaderView 承担（见 [MuPdfReaderView]）。
 */
@Composable
@Suppress("LongParameterList", "LongMethod")
internal fun MuPdfVerticalView(
    doc: Document,
    reflowable: Boolean,
    initialProgress: ProgressPayload,
    onProgressChanged: (ProgressPayload) -> Unit,
    onTapContent: () -> Unit,
    onUserScroll: () -> Unit,
    onTextSelected: (TextSelectionEvent?) -> Unit,
    annotations: List<AnnotationDraft>,
    activeSelection: AnnotationDraft?,
    selectionColor: Color,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // M12 B-4：epub 文本选择/高亮走自绘 overlay（reflowable 页非 PDFPage，无原生 annotation）
    val core = remember(doc) { (doc as? MuPdfDocument)?.core }
    val loader = remember(doc) { VerticalPageLoader(doc) }
    val latestCallback by rememberUpdatedState(onProgressChanged)
    // M12 沉浸模式：静止点按切换顶栏、滚动隐藏顶栏（LazyColumn 无内建手势，外层 pointerInput 可行）
    val latestTapCallback by rememberUpdatedState(onTapContent)
    val latestScrollCallback by rememberUpdatedState(onUserScroll)
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

    // 滚动回流守卫：初始 = 恢复锚点（page + 项内偏移）；snapshotFlow 发射时同步更新（见下方 collect）
    var lastEmitted by remember(doc) { mutableStateOf(initPage to initOffsetY) }
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

    // 外部跳页差分：(page, 项内偏移) 变化且非自身滚动回流时，滚到目标锚点并回发实际目标
    LaunchedEffect(doc, initPage, initOffsetY) {
        val target = initPage to initOffsetY
        if (target != lastEmitted) {
            listState.scrollToItem(initPage, initOffsetY)
            // 跳页后即使目标锚点==当前锚点也强制回发一次，避免「跳后立刻退出」落旧进度（M9 review #9）
            lastEmitted = target
            val payload = resolver?.progressForPage(initPage)
                ?: ProgressPayload.Page(initPage, initOffsetY)
            latestCallback(payload)
        }
    }

    // 进度回写：每帧发射（distinctUntilChanged 去重），防抖落库由 VM 600ms 承担
    LaunchedEffect(doc) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (index, offset) ->
                if (!restored) return@collect   // 恢复期（含 scrollToItem 触发的中间帧）不回写（T4）
                lastEmitted = index to offset
                // 重排文档回调 CFI 锚点；页码式回调页码 + 项内像素偏移
                val payload = resolver?.progressForPage(index) ?: ProgressPayload.Page(index, offset)
                latestCallback(payload)
            }
    }

    // M12 沉浸模式：滚动开始时回调隐藏顶栏（distinctUntilChanged 只发一次）
    LaunchedEffect(doc) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling -> if (scrolling) latestScrollCallback() }
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
            modifier = Modifier
                .fillMaxSize()
                // M12 沉浸模式：静止点按 → 切换顶栏（滚动会取消 tap，无手势冲突）
                .pointerInput(Unit) { detectTapGestures(onTap = { latestTapCallback() }) },
        ) {
            items(doc.pageCount) { page ->
                VerticalPageItem(
                    loader = loader,
                    page = page,
                    widthPx = widthPx,
                    core = core,
                    annotations = annotations,
                    onTextSelected = onTextSelected,
                    activeSelection = activeSelection,
                    selectionColor = selectionColor,
                )
            }
        }
    }
}

/** M14 选中视觉反馈：选区填充不透明度（作用于主题色 primary 之上，与高亮色板可辨识） */
private const val SELECTION_ALPHA = 0.3f

/** M12 已存高亮半透明度（自绘 overlay，接近原生高亮效果） */
private const val HIGHLIGHT_ALPHA = 0.35f

/** M14 选择手柄：触达盒 / 视觉直径 / 白描边（dp） */
private const val HANDLE_BOX_DP = 28f
private const val HANDLE_VISUAL_DP = 14f
private const val HANDLE_STROKE_DP = 2f

/** M14 手柄标识：无 / 起点 / 终点 */
private const val HANDLE_NONE = 0
private const val HANDLE_START = 1
private const val HANDLE_END = 2

/** 单页项：占位（4:3 + 加载圈）→ 页尺寸定高 → 位图就绪替换；渲染失败显示错误文案（不放无限加载圈）。
 *
 * M12 B-4（epub）：长按拖动选择文本 → 归一化 quad 上报落库；已存高亮与拖动预览以自绘 overlay 呈现
 * （reflowable 页非 `PDFPage`，无原生 annotation）。
 */
@Composable
@Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod", "MagicNumber")
private fun VerticalPageItem(
    loader: VerticalPageLoader,
    page: Int,
    widthPx: Int,
    core: MuPDFCore?,
    annotations: List<AnnotationDraft>,
    onTextSelected: (TextSelectionEvent?) -> Unit,
    activeSelection: AnnotationDraft?,
    selectionColor: Color,
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

    // 页面 pt 尺寸与 px/pt 换算比（选择坐标转换、高亮绘制共用）
    val ptWidth = pageSize?.width ?: 0f
    val ptHeight = pageSize?.height ?: 0f
    val sx = if (ptWidth > 0f) widthPx / ptWidth else 0f
    val sy = if (ptHeight > 0f) heightPx / ptHeight else 0f

    // 当前页已存高亮（归一化 quad + 颜色）
    val savedQuads: List<Pair<FloatArray, Int>> = remember(annotations, page) {
        annotations.asSequence()
            .filter { it.page == page }
            .flatMap { a: AnnotationDraft ->
                val decoded = AnnotationAnchorCodec.decode(a.anchor)
                if (decoded == null) {
                    emptySequence()
                } else {
                    decoded.quads.asSequence().map { q -> q to a.color }
                }
            }
            .toList()
    }

    // M14 已选中定格：当前选中草稿（本页）反解归一化 quads（S2，与 S1 同色）
    val activeQuads: List<FloatArray> = remember(activeSelection, page) {
        activeSelection
            ?.takeIf { it.page == page }
            ?.let { AnnotationAnchorCodec.decode(it.anchor)?.quads }
            ?: emptyList()
    }

    // 拖动选择状态：起止点（Box 坐标）+ 实时归一化 quads 预览
    var selStart by remember(page) { mutableStateOf<Offset?>(null) }
    var selEnd by remember(page) { mutableStateOf<Offset?>(null) }
    var selQuads by remember(page) { mutableStateOf<List<FloatArray>>(emptyList()) }
    // M14：页 Box 窗口坐标锚点（选区起点 → 屏幕坐标，气泡菜单定位）
    var boxOrigin by remember(page) { mutableStateOf(Offset.Zero) }
    // M14 手柄拖动：命中端（HANDLE_NONE/START/END）
    var draggingHandle by remember(page) { mutableStateOf(HANDLE_NONE) }
    // 手柄中心：拖某端时该端跟随 selEnd（touch），其余由 effective 反算
    val effectiveQuads = if (draggingHandle != HANDLE_NONE) selQuads else activeQuads
    val startHandleCenter: Offset? = when {
        draggingHandle == HANDLE_START -> selEnd
        else -> effectiveQuads.firstOrNull()?.let { f -> Offset(f[0] * widthPx, (f[1] + f[5]) / 2f * heightPx) }
    }
    val endHandleCenter: Offset? = when {
        draggingHandle == HANDLE_END -> selEnd
        else -> effectiveQuads.lastOrNull()?.let { l -> Offset(l[6] * widthPx, (l[3] + l[7]) / 2f * heightPx) }
    }
    val selectable = core != null && sx > 0f && sy > 0f
    val scope = rememberCoroutineScope()
    // M14 统一提交：按起止点重算选区并上报（长按选择与手柄拖动共用，消除重复）
    val submitSelection: (Offset, Offset) -> Unit = { start, end ->
        val origin = boxOrigin
        selStart = null
        selEnd = null
        selQuads = emptyList()
        draggingHandle = HANDLE_NONE
        scope.launch {
            val result = selectQuads(core, page, start, end, sx, sy, ptWidth, ptHeight)
            if (result != null && result.quads.isNotEmpty()) {
                val draft = AnnotationDraft(
                    page = page,
                    anchor = AnnotationAnchorCodec.encodeNormalized(result.quads),
                    text = result.text,
                )
                // 气泡锚点 = 选区首 quad 左上角（窗口坐标）
                val first = result.quads.first()
                val anchor = origin + Offset(first[0] * widthPx, first[1] * heightPx)
                onTextSelected(TextSelectionEvent(draft, anchor.x, anchor.y))
            }
        }
    }
    // M14 取消手柄拖动：仅清理状态，不提交
    val cancelHandleDrag: () -> Unit = {
        selStart = null
        selEnd = null
        selQuads = emptyList()
        draggingHandle = HANDLE_NONE
    }
    // M14 手柄松手：有起止点则提交，否则按取消处理
    val finishHandleDrag: () -> Unit = {
        val start = selStart
        val end = selEnd
        if (start != null && end != null) submitSelection(start, end) else cancelHandleDrag()
    }

    // 拖动中实时重算选区（松手由 onDragEnd 直接按起止点重算提交，不依赖异步 state）
    LaunchedEffect(page, selStart, selEnd, sx, sy) {
        val start = selStart
        val end = selEnd
        selQuads = if (start != null && end != null) {
            selectQuads(core, page, start, end, sx, sy, ptWidth, ptHeight)?.quads ?: emptyList()
        } else {
            emptyList()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = PAGE_SPACING / 2)
            .height(with(density) { heightPx.toDp() })
            .onGloballyPositioned { boxOrigin = it.positionInWindow() }
            .then(
                if (selectable) {
                    Modifier.pointerInput(page, sx, sy) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset ->
                                selStart = offset
                                selEnd = offset
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                selEnd = change.position
                            },
                            onDragEnd = {
                                val start = selStart
                                val end = selEnd
                                if (start != null && end != null) submitSelection(start, end)
                            },
                            onDragCancel = {
                                selStart = null
                                selEnd = null
                                selQuads = emptyList()
                            },
                        )
                    }
                } else {
                    Modifier
                },
            )
            .drawWithContent {
                drawContent()
                savedQuads.forEach { (n, color) ->
                    drawQuadPath(n, Color(color).copy(alpha = HIGHLIGHT_ALPHA))
                }
                // S1 选择中 + S2 已选中（拖手柄时 selQuads 即实时选区，避免与旧 activeQuads 叠加）
                val selectionFill = selectionColor.copy(alpha = SELECTION_ALPHA)
                selQuads.forEach { n -> drawQuadPath(n, selectionFill) }
                if (draggingHandle == HANDLE_NONE) {
                    activeQuads.forEach { n -> drawQuadPath(n, selectionFill) }
                }
            },
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

        // M14 手柄（Compose 化：可拖动 + a11y），仅已选中时显示
        startHandleCenter?.let { center ->
            SelectionHandle(
                center = center,
                color = selectionColor,
                description = "调整选区起点",
                onDrag = { touch ->
                    draggingHandle = HANDLE_START
                    selStart = touch
                    selEnd = endHandleCenter
                },
                onDragEnd = finishHandleDrag,
                onDragCancel = cancelHandleDrag,
            )
        }
        endHandleCenter?.let { center ->
            SelectionHandle(
                center = center,
                color = selectionColor,
                description = "调整选区终点",
                onDrag = { touch ->
                    draggingHandle = HANDLE_END
                    selStart = startHandleCenter
                    selEnd = touch
                },
                onDragEnd = finishHandleDrag,
                onDragCancel = cancelHandleDrag,
            )
        }
    }
}

/** 选择结果：归一化 quads（每项 8 个 0..1 值）+ 选中文本快照 */
private data class SelectionResult(val quads: List<FloatArray>, val text: String)

/** 选择：View px 起止点 → 页面 pt → 归一化 quads；无文本/未就绪返回 null */
@Suppress("LongParameterList", "ComplexCondition", "ReturnCount")
private suspend fun selectQuads(
    core: MuPDFCore?,
    page: Int,
    start: Offset,
    end: Offset,
    sx: Float,
    sy: Float,
    ptWidth: Float,
    ptHeight: Float,
): SelectionResult? {
    if (core == null || sx <= 0f || sy <= 0f || ptWidth <= 0f || ptHeight <= 0f) return null
    val sel = withContext(Dispatchers.Default) {
        core.selectText(page, start.x / sx, start.y / sy, end.x / sx, end.y / sy)
    } ?: return null
    val quads = sel.quads.map { q ->
        floatArrayOf(
            q.ul_x / ptWidth, q.ul_y / ptHeight,
            q.ur_x / ptWidth, q.ur_y / ptHeight,
            q.ll_x / ptWidth, q.ll_y / ptHeight,
            q.lr_x / ptWidth, q.lr_y / ptHeight,
        )
    }
    return SelectionResult(quads = quads, text = sel.text ?: "")
}

/** 归一化 quad（8 分量）→ 绘制路径并填充指定颜色 */
@Suppress("MagicNumber")
private fun DrawScope.drawQuadPath(n: FloatArray, color: Color) {
    val path = Path()
    path.moveTo(n[0] * size.width, n[1] * size.height)
    path.lineTo(n[2] * size.width, n[3] * size.height)
    path.lineTo(n[6] * size.width, n[7] * size.height)
    path.lineTo(n[4] * size.width, n[5] * size.height)
    path.close()
    drawPath(path, color)
}

/** M14 选择手柄：圆形（primary + 白描边），可拖动，带 a11y 语义（Box 局部坐标定位） */
@Composable
@Suppress("LongParameterList")
private fun BoxScope.SelectionHandle(
    center: Offset,
    color: Color,
    description: String,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    val density = LocalDensity.current
    val boxSize = with(density) { HANDLE_BOX_DP.dp }
    val halfPx = with(density) { HANDLE_BOX_DP.dp.toPx() / 2 }
    Box(
        modifier = Modifier
            .align(Alignment.TopStart)
            .offset { IntOffset((center.x - halfPx).roundToInt(), (center.y - halfPx).roundToInt()) }
            .size(boxSize)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, _ ->
                        change.consume()
                        onDrag(change.position)
                    },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragCancel() },
                )
            }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(HANDLE_VISUAL_DP.dp).clip(CircleShape).background(Color.White))
        Box(Modifier.size((HANDLE_VISUAL_DP - HANDLE_STROKE_DP * 2).dp).clip(CircleShape).background(color))
    }
}

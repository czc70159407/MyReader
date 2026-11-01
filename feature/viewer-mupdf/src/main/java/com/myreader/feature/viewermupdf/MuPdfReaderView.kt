package com.myreader.feature.viewermupdf

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.viewer.MuPDFCore
import com.artifex.mupdf.viewer.PageAdapter
import com.artifex.mupdf.viewer.ReaderView
import com.myreader.core.document.AnnotationDraft
import com.myreader.core.document.ProgressPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 连续尺寸变化（分屏拖动分隔条等）静止该时长后才执行一次全量重建 */
private const val SIZE_SETTLE_DELAY_MS = 150L

/** 重排文档排版字号（pt），与 MuPDFCore 默认 layoutEM 一致 */
private const val REFLOW_EM = 10

/** pt 与 px 换算：每英寸 72 点 */
private const val POINTS_PER_INCH = 72

/** 重排排版最小尺寸（pt）：防极小分屏导致页数爆炸/除零 */
private const val MIN_LAYOUT_PT = 100

/** M12 文本选择拖动预览色（半透明黄，ARGB 0xAARRGGBB） */
private const val SELECTION_PREVIEW_COLOR: Int = 0x66FFEB3B

/** M12 高亮 annotation 颜色（RGB 0..1，黄色；M12 单色起步） */
private val HIGHLIGHT_RGB = floatArrayOf(1f, 0.92f, 0.23f)

/**
 * AndroidView 桥接 MuPDF [ReaderView]（瓦片渲染 + BitmapPool，预渲染窗口 ±1 页由其内部管理）。
 *
 * 1.28.2 实测 API 备注（上游无 setViewListener，页面变化经 protected 钩子暴露）：
 * - 匿名子类覆写 [ReaderView.onMoveToChild] 捕获翻页/跳页 → 回调进度
 * - core 经 setAdapter(PageAdapter) 注入；释放经 PageAdapter.releaseBitmaps()
 *
 * 跳页机制：initialProgress 变化（重组传入新值）时在 update 块与 getDisplayedViewIndex() 差分，
 * 不同则 setDisplayedViewIndex——「跳页/大纲导航」由外部状态驱动，无需命令式引用。
 *
 * M9 重排（reflowable，如 epub）：排版随视口变化 → 页码漂移，故进度改走 CFI 锚点
 * （[EpubLocationResolver]）；尺寸变化时 relayout 到当前视口并把当前页 remap 到新页码，
 * 避免旋转/分屏后白屏或回首页（对齐上游 DocumentActivity.onSizeChanged）。
 */
@Composable
@Suppress("LongParameterList")
internal fun MuPdfReaderView(
    core: MuPDFCore,
    reflowable: Boolean,
    initialProgress: ProgressPayload,
    onProgressChanged: (ProgressPayload) -> Unit,
    onPageCountChanged: (Int) -> Unit,
    onTapContent: () -> Unit,
    onUserScroll: () -> Unit,
    onTextSelected: (AnnotationDraft) -> Unit,
    annotations: List<AnnotationDraft>,
    modifier: Modifier = Modifier,
) {
    // factory 只执行一次：经 State 转发保证匿名子类始终回调最新 lambda；adapterRef 供释放
    val latestCallback by rememberUpdatedState(onProgressChanged)
    val latestPageCountCallback by rememberUpdatedState(onPageCountChanged)
    // M12 沉浸模式：上游 ReaderView 在中间点按 / 拖动时经 protected hook 暴露（1.28.2 无 setViewListener）
    val latestTapCallback by rememberUpdatedState(onTapContent)
    val latestScrollCallback by rememberUpdatedState(onUserScroll)
    // M12 高亮/笔记：选中完成回调（归一化锚点草稿）
    val latestSelectionCallback by rememberUpdatedState(onTextSelected)
    val adapterRef = remember { mutableStateOf<PageAdapter?>(null) }
    val resolver = remember(core) { if (reflowable) EpubLocationResolver(core) else null }
    // 重排协程作用域：rememberCoroutineScope 与组合同生命周期，组合离开即取消——
    // 不能用自建裸 CoroutineScope（M10 review H1：Composition 丢弃但 View 未 detach 时会操作失效 View）
    val scope = rememberCoroutineScope()

    // 初始页：重排文档把 Cfi 锚点解析为当前排版页码；页码式取 Page.page
    // （ProgressPayload 为 data class，remember 按结构相等判定，同内容 payload 不会重复解析）
    val initialPage = remember(initialProgress) {
        when {
            resolver != null -> resolver.pageForProgress(initialProgress)
            initialProgress is ProgressPayload.Page -> initialProgress.page.coerceAtLeast(0)
            else -> 0
        }
    }

    AndroidView(
        factory = { ctx ->
            val adapter = PageAdapter(ctx, core)
            adapterRef.value = adapter
            object : ReaderView(ctx) {
                private var refreshPending: Runnable? = null
                private var layoutJob: Job? = null

                // M12 B-1/B-2：文本选择（长按 + 拖动）；选区在 onDraw 绘制，松手创建原生高亮
                private var selecting = false
                private var selAnchorX = 0f
                private var selAnchorY = 0f
                private var selQuads: Array<Quad>? = null
                private var selText: String? = null
                /** 已回放高亮的 anchor 键（page|anchor），防重组重复创建 */
                private val replayedAnchors = mutableSetOf<String>()
                private val selPaint = Paint().apply {
                    color = SELECTION_PREVIEW_COLOR
                    style = Paint.Style.FILL
                }
                private val selPath = Path()
                private val selectDetector = GestureDetector(
                    ctx,
                    object : GestureDetector.SimpleOnGestureListener() {
                        override fun onLongPress(e: MotionEvent) {
                            // epub（reflowable）暂不支持原生高亮（B-4 自绘 overlay）
                            if (reflowable) return
                            selecting = true
                            selAnchorX = e.x
                            selAnchorY = e.y
                            updateSelection(e.x, e.y)
                            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        }
                    },
                )

                init {
                    // 选区在 onDraw 叠加绘制（ReaderView 默认 ViewGroup 不绘制自身）
                    setWillNotDraw(false)
                }

                override fun onTouchEvent(event: MotionEvent): Boolean {
                    if (!selecting) selectDetector.onTouchEvent(event)
                    if (selecting) {
                        when (event.actionMasked) {
                            MotionEvent.ACTION_MOVE -> updateSelection(event.x, event.y)
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> finishSelection()
                            else -> Unit
                        }
                        return true
                    }
                    return super.onTouchEvent(event)
                }

                /** 显示页尺寸换算比（px / pt）；页面未就绪时为 1 */
                private fun scaleOf(): Pair<Float, Float> {
                    val pv = getDisplayedView() ?: return 1f to 1f
                    val size = core.getPageSize(getDisplayedViewIndex())
                    val sx = if (size.x > 0f) pv.width / size.x else 1f
                    val sy = if (size.y > 0f) pv.height / size.y else 1f
                    return sx to sy
                }

                /** 选区更新：View 坐标 → 页面坐标（pt）→ 吸附词边界取 quad */
                private fun updateSelection(curX: Float, curY: Float) {
                    val pv = getDisplayedView() ?: return
                    val (sx, sy) = scaleOf()
                    val x0 = (selAnchorX - pv.left) / sx
                    val y0 = (selAnchorY - pv.top) / sy
                    val x1 = (curX - pv.left) / sx
                    val y1 = (curY - pv.top) / sy
                    val sel = core.selectText(getDisplayedViewIndex(), x0, y0, x1, y1)
                    selQuads = sel?.quads
                    selText = sel?.text
                    invalidate()
                }

                /** 松手：创建原生高亮、重绘，并上报归一化锚点草稿（落库） */
                private fun finishSelection() {
                    val quads = selQuads
                    val text = selText
                    selecting = false
                    selQuads = null
                    selText = null
                    invalidate()
                    if (quads == null || quads.isEmpty()) return
                    val page = getDisplayedViewIndex()
                    core.addHighlight(page, quads, HIGHLIGHT_RGB)
                    refresh()
                    val size = core.getPageSize(page)
                    if (size.x > 0f && size.y > 0f) {
                        val anchor = AnnotationAnchorCodec.encode(quads, size.x, size.y)
                        replayedAnchors.add("$page|$anchor")   // 本地已绘制，回放不再重复
                        latestSelectionCallback(AnnotationDraft(page = page, anchor = anchor, text = text ?: ""))
                    }
                }

                /** 回放库中高亮（只处理当前页；翻页后重组再回放新页），按 anchor 去重 */
                fun replayAnnotations(list: List<AnnotationDraft>) {
                    if (reflowable) return
                    val current = getDisplayedViewIndex()
                    var dirty = false
                    list.asSequence()
                        .filter { it.page == current && replayedAnchors.add("${it.page}|${it.anchor}") }
                        .forEach { a ->
                            val size = core.getPageSize(a.page)
                            val quads = if (size.x > 0f && size.y > 0f) {
                                AnnotationAnchorCodec.decode(a.anchor)
                                    ?.let { AnnotationAnchorCodec.toQuads(it, size.x, size.y) }
                            } else {
                                null
                            }
                            if (quads != null && quads.isNotEmpty()) {
                                core.addHighlight(a.page, quads, HIGHLIGHT_RGB)
                                dirty = true
                            }
                        }
                    if (dirty) refresh()
                }

                override fun onDraw(canvas: Canvas) {
                    super.onDraw(canvas)
                    val quads = selQuads ?: return
                    val pv = getDisplayedView() ?: return
                    val (sx, sy) = scaleOf()
                    for (q in quads) {
                        selPath.reset()
                        selPath.moveTo(pv.left + q.ul_x * sx, pv.top + q.ul_y * sy)
                        selPath.lineTo(pv.left + q.ur_x * sx, pv.top + q.ur_y * sy)
                        selPath.lineTo(pv.left + q.lr_x * sx, pv.top + q.lr_y * sy)
                        selPath.lineTo(pv.left + q.ll_x * sx, pv.top + q.ll_y * sy)
                        selPath.close()
                        canvas.drawPath(selPath, selPaint)
                    }
                }

                override fun onMoveToChild(i: Int) {
                    super.onMoveToChild(i)
                    // 重排文档回调 CFI 锚点（跨重排稳定）；页码式回调页码
                    val payload = resolver?.progressForPage(i) ?: ProgressPayload.Page(i)
                    latestCallback(payload)
                }

                // M12 沉浸模式：中间区点按 → 切换顶栏（边缘区仍翻页，上游自行处理不回调）
                override fun onTapMainDocArea() {
                    super.onTapMainDocArea()
                    latestTapCallback()
                }

                // M12 沉浸模式：拖动 / 滚动 / 缩放 → 隐藏顶栏
                override fun onDocMotion() {
                    super.onDocMotion()
                    latestScrollCallback()
                }

                @Suppress("ReturnCount")
                override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                    super.onSizeChanged(w, h, oldw, oldh)
                    // 尺寸变化需 refresh() 重建全部子 view 与共享位图（configChanges 下 Activity
                    // 不重建，复用的 PageView 持旧尺寸位图会白屏）。分屏拖动分隔条等场景下
                    // onSizeChanged 每帧触发，全量重建成本高（约 2x10MB 位图 + native 重渲染）——
                    // 去抖：连续变化只在静止 SIZE_SETTLE_DELAY_MS 后执行一次。
                    if (w == oldw && h == oldh) return
                    // 非重排文档首次布局（oldw<=0）无需重建；重排文档需在首次真实尺寸时排版到视口
                    if (oldw <= 0 && !reflowable) return
                    if (!isAttachedToWindow) return
                    refreshPending?.let(::removeCallbacks)
                    layoutJob?.cancel()
                    refreshPending = Runnable {
                        refreshPending = null
                        if (!isAttachedToWindow) return@Runnable
                        val target = mCurrent
                        if (reflowable && w > 0 && h > 0) {
                            // relayout 到当前视口（pt = px * 72 / dpi），并把当前页 remap 到新页码
                            val dpi = resources.displayMetrics.densityDpi.coerceAtLeast(1)
                            // 钳最小排版尺寸，防极小分屏导致页数爆炸/除零（M9 review S4）
                            val lw = (w * POINTS_PER_INCH / dpi).coerceAtLeast(MIN_LAYOUT_PT)
                            val lh = (h * POINTS_PER_INCH / dpi).coerceAtLeast(MIN_LAYOUT_PT)
                            // 整书重排放后台线程执行，避免阻塞 UI（EPUB 整书秒级重排 → ANR 风险）；
                            // 完成后再回主线程 remap 当前页并 refresh。
                            // 注意：rememberCoroutineScope 默认调度器是 Compose 主线程，必须显式指定
                            // Default，否则重排会回到主线程（review 修正）
                            layoutJob = scope.launch(Dispatchers.Default) {
                                val remapped = runCatching {
                                    core.layout(target, lw, lh, REFLOW_EM)
                                }.getOrDefault(target)
                                withContext(Dispatchers.Main) {
                                    if (!isAttachedToWindow) return@withContext
                                    // 越界钳制，防止 layout 返回超界值导致整屏空白（M9 review M2）
                                    val safe = remapped.coerceIn(0, core.countPages() - 1)
                                    if (safe != mCurrent) {
                                        onMoveOffChild(mCurrent)
                                        mCurrent = safe
                                        onMoveToChild(safe)
                                    }
                                    refresh()
                                    // relayout 后页数已变（可能增减）：上抛实时页数，
                                    // 顶栏分母/跳页弹窗/大纲 clamp 随动（T3，修复 Ready 快照过时）
                                    latestPageCountCallback(core.countPages())
                                }
                            }
                        } else {
                            refresh()
                        }
                    }.also { postDelayed(it, SIZE_SETTLE_DELAY_MS) }
                }

                override fun onDetachedFromWindow() {
                    refreshPending?.let(::removeCallbacks)
                    refreshPending = null
                    layoutJob?.cancel()
                    super.onDetachedFromWindow()
                }
            }.apply {
                setAdapter(adapter)
                setDisplayedViewIndex(initialPage)
            }
        },
        update = { view ->
            // 跳页差分：翻页回调同步状态后重组到此，两值相等自然无操作
            if (view.getDisplayedViewIndex() != initialPage) {
                view.setDisplayedViewIndex(initialPage)
            }
            // M12 高亮回放：库中已保存的高亮绘制为原生 annotation（当前页，幂等）
            view.replayAnnotations(annotations)
        },
        modifier = modifier,
    )

    DisposableEffect(core) {
        onDispose { adapterRef.value?.releaseBitmaps() }
    }
}

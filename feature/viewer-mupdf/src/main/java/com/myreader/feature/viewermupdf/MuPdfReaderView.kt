package com.myreader.feature.viewermupdf

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.viewer.MuPDFCore
import com.artifex.mupdf.viewer.PageAdapter
import com.artifex.mupdf.viewer.ReaderView
import com.myreader.core.document.AnnotationDraft
import com.myreader.core.document.ProgressPayload
import com.myreader.core.document.TextSelectionEvent
import com.myreader.core.model.ReaderFontScale
import com.myreader.core.model.ReaderZoomScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** 连续尺寸变化（分屏拖动分隔条等）静止该时长后才执行一次全量重建 */
private const val SIZE_SETTLE_DELAY_MS = 150L

/** 排版字号兜底（pt，MuPDFCore.layout 的 em 参数为 Int）：appliedFontEm 未就绪时的回退值 */
private const val DEFAULT_REFLOW_EM = 10

/** pt 与 px 换算：每英寸 72 点 */
private const val POINTS_PER_INCH = 72

/** 重排排版最小尺寸（pt）：防极小分屏导致页数爆炸/除零 */
private const val MIN_LAYOUT_PT = 100

/** M14 选中视觉反馈：选区填充不透明度（作用于主题色 primary 之上，与高亮色板可辨识） */
private const val SELECTION_ALPHA = 0.3f

/** 长按取词判定阈值（页面 pt，≈15px@fit-width）：覆盖手指长按自然抖动；有意拖动选词远超该距离 */
private const val SELECTION_POINT_EPSILON = 8f

/** M14 选择手柄（泪滴形，尖朝上）：主体半径 / 尖点到圆心距离 / 白描边 / 命中半径（dp） */
private const val HANDLE_RADIUS_DP = 10f
private const val HANDLE_DROP_DP = 13f
private const val HANDLE_STROKE_DP = 1f
private const val HANDLE_HIT_R_DP = 24f

/** M14 手柄标识：无 / 起点 / 终点 */
private const val HANDLE_NONE = 0
private const val HANDLE_START = 1
private const val HANDLE_END = 2

/** M12 改色：ARGB（Int）→ fitz RGB 0..1（原生高亮渲染用） */
@Suppress("MagicNumber")
internal fun argbToRgb(argb: Int): FloatArray = floatArrayOf(
    ((argb shr 16) and 0xFF) / 255f,
    ((argb shr 8) and 0xFF) / 255f,
    (argb and 0xFF) / 255f,
)

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
 *
 * M15：[fontScale] 作 reflowable 排版字号——字号变化与尺寸变化走同一去抖 relayout 路径；
 * [zoomScale] 作固定版式默认缩放（fork ReaderView.setDefaultScale 立即生效，
 * refresh 后回落该档；pinch 缩放仍是会话内临时值）。
 */
@Composable
@Suppress("LongParameterList", "LongMethod")
internal fun MuPdfReaderView(
    core: MuPDFCore,
    reflowable: Boolean,
    initialProgress: ProgressPayload,
    fontScale: ReaderFontScale,
    zoomScale: ReaderZoomScale,
    onProgressChanged: (ProgressPayload) -> Unit,
    onPageCountChanged: (Int) -> Unit,
    onTapContent: () -> Unit,
    onUserScroll: () -> Unit,
    onTextSelected: (TextSelectionEvent?) -> Unit,
    onSelectionAdjusting: (Boolean) -> Unit,
    annotations: List<AnnotationDraft>,
    activeSelection: AnnotationDraft?,
    selectionColor: Color,
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
    // M14 S2a：拖手柄开始/结束（菜单收起/重弹）
    val latestAdjustingCallback by rememberUpdatedState(onSelectionAdjusting)
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

                // M15：当前应生效的排版字号 / 默认缩放（update 块差分维护；NaN = 尚未应用）
                var appliedFontEm: Float = Float.NaN
                var appliedZoomFactor: Float = Float.NaN

                // M12 B-1/B-2：文本选择（长按 + 拖动）；选区在 onDraw 绘制，松手创建原生高亮
                private var selecting = false
                private var selAnchorX = 0f
                private var selAnchorY = 0f
                private var selQuads: Array<Quad>? = null
                private var selText: String? = null
                // M14 手柄拖动状态：命中端标识 + 实时选区 + 固定端（S2a）
                private var draggingHandle = HANDLE_NONE
                private var adjustingQuads: Array<Quad>? = null
                private var adjustingText: String? = null
                private var fixedX = 0f
                private var fixedY = 0f
                /** 已回放高亮的 anchor 键（page|anchor），防重组重复创建 */
                private val replayedAnchors = mutableSetOf<String>()
                private var activeDraft: AnnotationDraft? = null
                private val selPaint = Paint().apply {
                    style = Paint.Style.FILL
                }
                private val selPath = Path()
                private val handleFillPaint = Paint().apply { style = Paint.Style.FILL }

                /** M14 泪滴白描边 */
                private val handleStrokePaint = Paint().apply {
                    style = Paint.Style.STROKE
                    color = android.graphics.Color.WHITE
                    strokeWidth = HANDLE_STROKE_DP * context.resources.displayMetrics.density
                }
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

                @Suppress("ReturnCount")
                override fun onTouchEvent(event: MotionEvent): Boolean {
                    // S2a 手柄拖动中：MOVE 重算选区，UP/CANCEL 上报新选区
                    if (draggingHandle != HANDLE_NONE) {
                        when (event.actionMasked) {
                            MotionEvent.ACTION_MOVE -> adjustSelection(event.x, event.y)
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> finishAdjusting()
                            else -> Unit
                        }
                        return true
                    }
                    // S2 已选中：DOWN 命中手柄 → 进入拖动（优先于翻页/缩放/长按手势）；
                    // 未命中 → 上报清除（气泡非聚焦、点选区外空白 = 取消选中，随后可长按重选）
                    if (event.actionMasked == MotionEvent.ACTION_DOWN && activeDraft != null) {
                        val handle = hitHandle(event.x, event.y)
                        if (handle != HANDLE_NONE) {
                            draggingHandle = handle
                            beginAdjusting()
                            // S2a：菜单收起
                            latestAdjustingCallback(true)
                            return true
                        }
                        // 点选区内部（非手柄）保持选中；点选区外才清除（避免误触选中文字即取消）
                        if (!isPointInSelection(event.x, event.y)) {
                            latestSelectionCallback(null)
                        }
                    }
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

                /** 选区更新：View 坐标 → 页面坐标（pt）；零位移长按取词（英文整词 / 中文单字），拖动按字符区间截取 */
                private fun updateSelection(curX: Float, curY: Float) {
                    val pv = getDisplayedView() ?: return
                    val (sx, sy) = scaleOf()
                    val x0 = (selAnchorX - pv.left) / sx
                    val y0 = (selAnchorY - pv.top) / sy
                    val x1 = (curX - pv.left) / sx
                    val y1 = (curY - pv.top) / sy
                    val page = getDisplayedViewIndex()
                    val sel = if (abs(x0 - x1) < SELECTION_POINT_EPSILON &&
                        abs(y0 - y1) < SELECTION_POINT_EPSILON
                    ) {
                        core.selectWordAt(page, x0, y0)
                    } else {
                        core.selectText(page, x0, y0, x1, y1)
                    }
                    selQuads = sel?.quads
                    selText = sel?.text
                    invalidate()
                }

                /** M14 当前生效选区（页面坐标 pt）：拖手柄中用实时 adjusting，否则用 activeDraft 反解 */
                @Suppress("ReturnCount")
                private fun currentSelectionQuads(): Array<Quad>? {
                    adjustingQuads?.let { return it }
                    val draft = activeDraft ?: return null
                    if (draft.page != getDisplayedViewIndex()) return null
                    val size = core.getPageSize(draft.page)
                    if (size.x <= 0f || size.y <= 0f) return null
                    return AnnotationAnchorCodec.decode(draft.anchor)
                        ?.let { AnnotationAnchorCodec.toQuads(it, size.x, size.y) }
                }

                /** M14：DOWN 命中检测——touch 距泪滴主体圆心 ≤ 24dp 视为命中 */
                @Suppress("ReturnCount", "MagicNumber")
                private fun hitHandle(x: Float, y: Float): Int {
                    val quads = currentSelectionQuads()?.takeIf { it.isNotEmpty() } ?: return HANDLE_NONE
                    val pv = getDisplayedView() ?: return HANDLE_NONE
                    val (sx, sy) = scaleOf()
                    val density = resources.displayMetrics.density
                    val drop = HANDLE_DROP_DP * density
                    val hitR = HANDLE_HIT_R_DP * density
                    val hitR2 = hitR * hitR
                    val first = quads.first()
                    val last = quads.last()
                    val startX = pv.left + first.ll_x * sx
                    val startCy = pv.top + first.ll_y * sy + drop
                    val endX = pv.left + last.lr_x * sx
                    val endCy = pv.top + last.lr_y * sy + drop
                    val dStart2 = (x - startX) * (x - startX) + (y - startCy) * (y - startCy)
                    val dEnd2 = (x - endX) * (x - endX) + (y - endCy) * (y - endCy)
                    return when {
                        dStart2 <= hitR2 && dStart2 <= dEnd2 -> HANDLE_START
                        dEnd2 <= hitR2 -> HANDLE_END
                        else -> HANDLE_NONE
                    }
                }

                /** M14：touch（View 坐标）是否落在选区任一 quad 内（精确四边形包含，优于包围盒近似） */
                @Suppress("ReturnCount", "MagicNumber")
                private fun isPointInSelection(x: Float, y: Float): Boolean {
                    val quads = currentSelectionQuads() ?: return false
                    val pv = getDisplayedView() ?: return false
                    val (sx, sy) = scaleOf()
                    val px = (x - pv.left) / sx
                    val py = (y - pv.top) / sy
                    return quads.any { q -> pointInQuad(px, py, q) }
                }

                /** 点是否在 quad（四边形 ul→ur→lr→ll）内：叉积同号法 */
                @Suppress("MagicNumber")
                private fun pointInQuad(px: Float, py: Float, q: Quad): Boolean {
                    val xs = floatArrayOf(q.ul_x, q.ur_x, q.lr_x, q.ll_x)
                    val ys = floatArrayOf(q.ul_y, q.ur_y, q.lr_y, q.ll_y)
                    var positive = false
                    var negative = false
                    for (i in 0 until 4) {
                        val j = (i + 1) % 4
                        val cross = (xs[j] - xs[i]) * (py - ys[i]) - (ys[j] - ys[i]) * (px - xs[i])
                        if (cross > 0f) positive = true
                        if (cross < 0f) negative = true
                        if (positive && negative) return false
                    }
                    return true
                }

                /** M14：进入手柄拖动——记录固定端（对端手柄页面坐标） */
                private fun beginAdjusting() {
                    val quads = currentSelectionQuads() ?: return
                    val first = quads.first()
                    val last = quads.last()
                    when (draggingHandle) {
                        HANDLE_START -> {
                            fixedX = last.lr_x
                            fixedY = (last.ur_y + last.lr_y) / 2f
                        }
                        HANDLE_END -> {
                            fixedX = first.ul_x
                            fixedY = (first.ul_y + first.ll_y) / 2f
                        }
                        else -> Unit
                    }
                }

                /** M14：拖手柄——本端跟随手指、对端固定，吸附词边界重算选区 */
                private fun adjustSelection(x: Float, y: Float) {
                    val pv = getDisplayedView() ?: return
                    val (sx, sy) = scaleOf()
                    val page = getDisplayedViewIndex()
                    val drop = HANDLE_DROP_DP * resources.displayMetrics.density
                    // 泪滴主体圆心 → 反解回尖点（选区边界）位置
                    val tx = (x - pv.left) / sx
                    val ty = (y - pv.top - drop) / sy
                    // M14 方案 B：字符级精确选择（walk 逐字截取），替代矩形 highlight——
                    // 消除「手指微抖跨行 → 选区跳到相邻行行末」；反向由 min/max 自动处理
                    val sel = core.selectTextByChars(page, fixedX, fixedY, tx, ty)
                    adjustingQuads = sel?.quads
                    adjustingText = sel?.text
                    invalidate()
                }

                /** M14：松手手柄——上报新选区（菜单重弹）；无有效选区时也要结束拖动态 */
                private fun finishAdjusting() {
                    draggingHandle = HANDLE_NONE
                    val quads = adjustingQuads
                    val text = adjustingText
                    adjustingQuads = null
                    adjustingText = null
                    invalidate()
                    if (!quads.isNullOrEmpty()) {
                        reportSelection(quads, text)
                    }
                    // S2a → S2：菜单在新位置重弹
                    latestAdjustingCallback(false)
                }

                /** 松手：上报归一化锚点草稿（落库后由 replayAnnotations 统一绘制，避免本地/回放双份） */
                private fun finishSelection() {
                    val quads = selQuads
                    val text = selText
                    selecting = false
                    selQuads = null
                    selText = null
                    invalidate()
                    if (quads == null || quads.isEmpty()) return
                    reportSelection(quads, text)
                }

                /** M14：quads + text → 上报 TextSelectionEvent（draft + 窗口坐标锚点，菜单定位） */
                private fun reportSelection(quads: Array<Quad>, text: String?) {
                    val page = getDisplayedViewIndex()
                    val size = core.getPageSize(page)
                    if (size.x <= 0f || size.y <= 0f) return
                    val anchor = AnnotationAnchorCodec.encode(quads, size.x, size.y)
                    val draft = AnnotationDraft(page = page, anchor = anchor, text = text ?: "")
                    // 锚点 = 选区中心 X + 选区上沿 Y（窗口坐标）：菜单居中于选区、尾巴指向选区上沿。
                    // 必须 getLocationInWindow（父容器坐标缺状态栏偏移）。
                    val pv = getDisplayedView()
                    val (anchorX, anchorY) = if (pv != null) {
                        val (sx, sy) = scaleOf()
                        val first = quads[0]
                        val last = quads[quads.size - 1]
                        val loc = IntArray(2)
                        pv.getLocationInWindow(loc)
                        val centerX = (first.ul_x + last.lr_x) / 2f * sx
                        (loc[0] + centerX) to (loc[1] + first.ul_y * sy)
                    } else {
                        0f to 0f
                    }
                    latestSelectionCallback(TextSelectionEvent(draft, anchorX, anchorY))
                }

                /** 回放库中高亮：与已绘制集合比对，一致则 no-op；新增/删除时清空该页全量重放（幂等） */
                fun replayAnnotations(list: List<AnnotationDraft>) {
                    if (reflowable) return
                    val current = getDisplayedViewIndex()
                    val desired = list.filter { it.page == current }
                        .associateBy { "${it.page}|${it.anchor}" }
                    val drawn = replayedAnchors.filter { it.startsWith("$current|") }.toSet()
                    if (desired.keys == drawn) return
                    replayedAnchors.removeAll(drawn)
                    core.clearHighlights(current)
                    var dirty = false
                    desired.values.forEach { a ->
                        val size = core.getPageSize(a.page)
                        if (size.x <= 0f || size.y <= 0f) return@forEach
                        val quads = AnnotationAnchorCodec.decode(a.anchor)
                            ?.let { AnnotationAnchorCodec.toQuads(it, size.x, size.y) }
                        if (quads != null && quads.isNotEmpty()) {
                            core.addHighlight(a.page, quads, argbToRgb(a.color))
                            replayedAnchors.add("${a.page}|${a.anchor}")
                            dirty = true
                        }
                    }
                    if (dirty || drawn.isNotEmpty()) refresh()
                }

                /** M14：同步当前选中草稿与主题色；null 即清除选区预览 */
                fun applySelection(active: AnnotationDraft?, color: Color) {
                    activeDraft = active
                    selPaint.color = color.copy(alpha = SELECTION_ALPHA).toArgb()
                    handleFillPaint.color = color.toArgb()
                    invalidate()
                }

                // M14 修复：ReaderView 是 ViewGroup，onDraw 绘制会被子 View（PageView 位图）覆盖——
                // 拖动预览自 M12 起实际不可见（历史遗留缺陷，真机复验发现）。改为 dispatchDraw 之后
                // 绘制，确保选区/手柄叠加在页面位图之上。
                override fun dispatchDraw(canvas: Canvas) {
                    super.dispatchDraw(canvas)
                    val pv = getDisplayedView() ?: return
                    val (sx, sy) = scaleOf()
                    // S1 选择中实时预览（手指拖动）
                    selQuads?.forEach { q -> drawQuad(canvas, pv, q, sx, sy) }
                    // S2 已选中定格（从 activeDraft 反解，与 S1 同色）
                    drawActiveSelection(canvas, pv, sx, sy)
                    // S2 选择手柄（两端可拖动圆点）
                    drawHandles(canvas, pv, sx, sy)
                }

                private fun drawQuad(canvas: Canvas, pv: View, q: Quad, sx: Float, sy: Float) {
                    selPath.reset()
                    selPath.moveTo(pv.left + q.ul_x * sx, pv.top + q.ul_y * sy)
                    selPath.lineTo(pv.left + q.ur_x * sx, pv.top + q.ur_y * sy)
                    selPath.lineTo(pv.left + q.lr_x * sx, pv.top + q.lr_y * sy)
                    selPath.lineTo(pv.left + q.ll_x * sx, pv.top + q.ll_y * sy)
                    selPath.close()
                    canvas.drawPath(selPath, selPaint)
                }

                private fun drawActiveSelection(canvas: Canvas, pv: View, sx: Float, sy: Float) {
                    currentSelectionQuads()?.forEach { q -> drawQuad(canvas, pv, q, sx, sy) }
                }

                /** M14 选择手柄（泪滴形，尖朝上贴选区下沿）：尖点标边界、主体沉入下方便于抓握 */
                @Suppress("ReturnCount", "MagicNumber")
                private fun drawHandles(canvas: Canvas, pv: View, sx: Float, sy: Float) {
                    val quads = currentSelectionQuads()?.takeIf { it.isNotEmpty() } ?: return
                    val density = resources.displayMetrics.density
                    val r = HANDLE_RADIUS_DP * density
                    val drop = HANDLE_DROP_DP * density
                    val first = quads.first()
                    val last = quads.last()
                    // 起点：首 quad 左缘下沿；终点：末 quad 右缘下沿
                    drawTeardrop(canvas, pv.left + first.ll_x * sx, pv.top + first.ll_y * sy, r, drop)
                    drawTeardrop(canvas, pv.left + last.lr_x * sx, pv.top + last.lr_y * sy, r, drop)
                }

                /** 泪滴路径：尖点 → 左弧到肩 → 圆弧经底 → 右肩 → 右弧回尖；填充 + 白描边 */
                @Suppress("MagicNumber")
                private fun drawTeardrop(canvas: Canvas, tipX: Float, tipY: Float, r: Float, drop: Float) {
                    val cy = tipY + drop
                    val k = r * 0.7071f
                    val path = Path().apply {
                        moveTo(tipX, tipY)
                        quadTo(tipX - r * 0.85f, tipY + drop * 0.45f, tipX - k, cy - k)
                        arcTo(
                            android.graphics.RectF(tipX - r, cy - r, tipX + r, cy + r),
                            225f, -270f, false,
                        )
                        quadTo(tipX + r * 0.85f, tipY + drop * 0.45f, tipX, tipY)
                        close()
                    }
                    canvas.drawPath(path, handleFillPaint)
                    canvas.drawPath(path, handleStrokePaint)
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
                    scheduleRelayout()
                }

                /** M15：去抖调度一次 relayout（尺寸变化与字号变化共用；重复调度自动互斥）。
                 *  非 private：update 块（Compose 作用域）在字号差分时调用 */
                fun scheduleRelayout() {
                    refreshPending?.let(::removeCallbacks)
                    refreshPending = Runnable {
                        refreshPending = null
                        if (!isAttachedToWindow) return@Runnable
                        relayoutToViewport()
                    }.also { postDelayed(it, SIZE_SETTLE_DELAY_MS) }
                }

                /** M15：按当前视口 + 当前字号 relayout（reflowable）；页码式仅 refresh 重建子视图。
                 *  整书重排放后台线程（EPUB 整书秒级 → ANR 风险），完成后回主线程 remap 当前页。 */
                private fun relayoutToViewport() {
                    if (!isAttachedToWindow) return
                    layoutJob?.cancel()
                    if (reflowable && width > 0 && height > 0) {
                        // relayout 到当前视口（pt = px * 72 / dpi），并把当前页 remap 到新页码
                        val dpi = resources.displayMetrics.densityDpi.coerceAtLeast(1)
                        // 钳最小排版尺寸，防极小分屏导致页数爆炸/除零（M9 review S4）
                        val lw = (width * POINTS_PER_INCH / dpi).coerceAtLeast(MIN_LAYOUT_PT)
                        val lh = (height * POINTS_PER_INCH / dpi).coerceAtLeast(MIN_LAYOUT_PT)
                        val target = mCurrent
                        val em = if (appliedFontEm.isNaN()) DEFAULT_REFLOW_EM else appliedFontEm.toInt()
                        // 注意：rememberCoroutineScope 默认调度器是 Compose 主线程，必须显式指定
                        // Default，否则重排会回到主线程（review 修正）
                        layoutJob = scope.launch(Dispatchers.Default) {
                            val remapped = runCatching {
                                core.layout(target, lw, lh, em)
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
            // M14 选中视觉反馈：同步选中草稿 + 主题色（选区随 primary）
            view.applySelection(activeSelection, selectionColor)
            // M15 字号：变化 → 去抖 relayout（reflowable；与尺寸变化同一 scheduleRelayout 路径）
            if (view.appliedFontEm != fontScale.em) {
                view.appliedFontEm = fontScale.em
                view.scheduleRelayout()
            }
            // M15 默认缩放：固定版式差分应用（fork setDefaultScale 立即生效 + requestLayout）
            if (!reflowable && view.appliedZoomFactor != zoomScale.factor) {
                view.appliedZoomFactor = zoomScale.factor
                view.setDefaultScale(zoomScale.factor)
            }
        },
        modifier = modifier,
    )

    DisposableEffect(core) {
        onDispose { adapterRef.value?.releaseBitmaps() }
    }
}

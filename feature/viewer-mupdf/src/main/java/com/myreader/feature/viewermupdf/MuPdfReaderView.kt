package com.myreader.feature.viewermupdf

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.artifex.mupdf.viewer.MuPDFCore
import com.artifex.mupdf.viewer.PageAdapter
import com.artifex.mupdf.viewer.ReaderView
import com.myreader.core.document.ProgressPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 连续尺寸变化（分屏拖动分隔条等）静止该时长后才执行一次全量重建 */
private const val SIZE_SETTLE_DELAY_MS = 150L

/** 重排文档排版字号（pt），与 MuPDFCore 默认 layoutEM 一致 */
private const val REFLOW_EM = 10

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
internal fun MuPdfReaderView(
    core: MuPDFCore,
    reflowable: Boolean,
    initialProgress: ProgressPayload,
    onProgressChanged: (ProgressPayload) -> Unit,
    onPageCountChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // factory 只执行一次：经 State 转发保证匿名子类始终回调最新 lambda；adapterRef 供释放
    val latestCallback by rememberUpdatedState(onProgressChanged)
    val latestPageCountCallback by rememberUpdatedState(onPageCountChanged)
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

                override fun onMoveToChild(i: Int) {
                    super.onMoveToChild(i)
                    // 重排文档回调 CFI 锚点（跨重排稳定）；页码式回调页码
                    val payload = resolver?.progressForPage(i) ?: ProgressPayload.Page(i)
                    latestCallback(payload)
                }

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
                            val lw = (w * 72 / dpi).coerceAtLeast(100)
                            val lh = (h * 72 / dpi).coerceAtLeast(100)
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
        },
        modifier = modifier,
    )

    DisposableEffect(core) {
        onDispose { adapterRef.value?.releaseBitmaps() }
    }
}

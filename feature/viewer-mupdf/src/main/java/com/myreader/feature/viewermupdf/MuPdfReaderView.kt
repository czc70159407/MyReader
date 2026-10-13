package com.myreader.feature.viewermupdf

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.artifex.mupdf.viewer.MuPDFCore
import com.artifex.mupdf.viewer.PageAdapter
import com.artifex.mupdf.viewer.ReaderView
import com.myreader.core.document.ProgressPayload

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
    modifier: Modifier = Modifier,
) {
    // factory 只执行一次：经 State 转发保证匿名子类始终回调最新 lambda；adapterRef 供释放
    val latestCallback by rememberUpdatedState(onProgressChanged)
    val adapterRef = remember { mutableStateOf<PageAdapter?>(null) }
    val resolver = remember(core) { if (reflowable) EpubLocationResolver(core) else null }

    // 初始页：重排文档把 Cfi 锚点解析为当前排版页码；页码式取 Page.page
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

                /** 尺寸变化瞬间锁存的当前页：去抖窗口内 onLayout2 可能以「旧布局值 + 新屏宽」
                 * 误判自动翻页（横屏居中页 left ≥ 竖屏半宽 → mCurrent--，ReaderView.java:631-640），
                 * settle 后据此恢复，保证旋转/拖动不丢页 */
                private var lockedCurrent = -1

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
                    if (refreshPending == null) lockedCurrent = mCurrent // pending 期间不覆盖（拒绝误判拨动值）
                    refreshPending?.let(::removeCallbacks)
                    refreshPending = Runnable {
                        refreshPending = null
                        if (isAttachedToWindow) {
                            var target = lockedCurrent
                            if (reflowable && w > 0 && h > 0) {
                                // relayout 到当前视口（pt = px * 72 / dpi），并把锁存页 remap 到新页码
                                val dpi = resources.displayMetrics.densityDpi.coerceAtLeast(1)
                                val remapped = core.layout(
                                    if (target >= 0) target else mCurrent,
                                    w * 72 / dpi, h * 72 / dpi, REFLOW_EM,
                                )
                                if (remapped >= 0) target = remapped
                            }
                            if (target >= 0 && mCurrent != target) {
                                // 恢复锁存页（同步回调链：uiPage/进度防抖随之复位为正确页）
                                onMoveOffChild(mCurrent)
                                mCurrent = target
                                onMoveToChild(target)
                            }
                            lockedCurrent = -1
                            refresh()
                        }
                    }.also { postDelayed(it, SIZE_SETTLE_DELAY_MS) }
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

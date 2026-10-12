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

/**
 * AndroidView 桥接 MuPDF [ReaderView]（瓦片渲染 + BitmapPool，预渲染窗口 ±1 页由其内部管理）。
 *
 * 1.28.2 实测 API 备注（上游无 setViewListener，页面变化经 protected 钩子暴露）：
 * - 匿名子类覆写 [ReaderView.onMoveToChild] 捕获翻页/跳页 → 回调进度
 * - core 经 setAdapter(PageAdapter) 注入；释放经 PageAdapter.releaseBitmaps()
 *
 * 跳页机制：initialPage 变化（重组传入新值）时在 update 块与 getDisplayedViewIndex() 差分，
 * 不同则 setDisplayedViewIndex——「跳页/大纲导航」由外部状态驱动，无需命令式引用。
 */
@Composable
internal fun MuPdfReaderView(
    core: MuPDFCore,
    initialPage: Int,
    onProgressChanged: (ProgressPayload) -> Unit,
    modifier: Modifier = Modifier,
) {
    // factory 只执行一次：经 State 转发保证匿名子类始终回调最新 lambda；adapterRef 供释放
    val latestCallback by rememberUpdatedState(onProgressChanged)
    val adapterRef = remember { mutableStateOf<PageAdapter?>(null) }

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
                    latestCallback(ProgressPayload.Page(i))
                }

                override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                    super.onSizeChanged(w, h, oldw, oldh)
                    // 尺寸变化需 refresh() 重建全部子 view 与共享位图（configChanges 下 Activity
                    // 不重建，复用的 PageView 持旧尺寸位图会白屏）。分屏拖动分隔条等场景下
                    // onSizeChanged 每帧触发，全量重建成本高（约 2x10MB 位图 + native 重渲染）——
                    // 去抖：连续变化只在静止 SIZE_SETTLE_DELAY_MS 后执行一次。
                    if (oldw <= 0 || (w == oldw && h == oldh)) return
                    if (refreshPending == null) lockedCurrent = mCurrent   // pending 期间不覆盖（拒绝误判拨动值）
                    refreshPending?.let(::removeCallbacks)
                    refreshPending = Runnable {
                        refreshPending = null
                        if (isAttachedToWindow) {
                            if (lockedCurrent >= 0 && mCurrent != lockedCurrent) {
                                // 恢复锁存页（同步回调链：uiPage/进度防抖随之复位为正确页）
                                onMoveOffChild(mCurrent)
                                mCurrent = lockedCurrent
                                onMoveToChild(lockedCurrent)
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

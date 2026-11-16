package com.myreader.feature.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.myreader.core.document.AnnotationDraft
import kotlin.math.roundToInt

/** 气泡与选中锚点间距（px 换算前，dp） */
private val TAIL_GAP = 6.dp

/** 锚点太靠上时的翻转阈值：低于该高度气泡改显示在锚点下方 */
private val FLIP_THRESHOLD = 160.dp

/** 气泡圆角（「动漫对白框」偏圆润） */
private val BUBBLE_RADIUS = 20.dp

/**
 * M14：选中文本操作气泡（「轻量活泼化」）——悬浮在选中锚点旁的对白框，三动作横排：
 * 朗读（RecordVoiceOver）/ 高亮（Brush）/ 复制（ContentCopy）。
 *
 * 定位：气泡默认显示在锚点上方（底部中心对准锚点）；锚点太靠上时翻转到下方，并 clamp 到窗口内。
 */
@Composable
@Suppress("LongParameterList")
internal fun SelectionMenu(
    draft: AnnotationDraft,
    anchor: Offset,
    onSpeak: (String) -> Unit,
    onHighlight: (AnnotationDraft) -> Unit,
    onCopy: (String) -> Unit,
) {
    val density = LocalDensity.current
    val gapPx = with(density) { TAIL_GAP.toPx() }.roundToInt()
    val showBelow = anchor.y < with(density) { FLIP_THRESHOLD.toPx() }

    val positionProvider = remember(anchor, showBelow, gapPx) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val x = anchor.x.roundToInt() - popupContentSize.width / 2
                val y = if (showBelow) {
                    anchor.y.roundToInt() + gapPx
                } else {
                    anchor.y.roundToInt() - gapPx - popupContentSize.height
                }
                val clampedX = x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                val clampedY = y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
                return IntOffset(clampedX, clampedY)
            }
        }
    }

    Popup(
        popupPositionProvider = positionProvider,
        // 非聚焦：气泡不得拦截外部触摸——否则手柄拖动 / 重新长按的 DOWN 被全屏 popup window
        // 吞掉（真机日志证实）。「点选区外取消」统一由渲染端上报清除（onTextSelected(null)），
        // 此处忽略 dismiss 回调——部分版本下非聚焦 Popup 仍会因外部触摸回调 onDismissRequest，
        // 导致点选区内部（应保持选中）也被误清（真机日志：inside=true 却仍消失）。
        onDismissRequest = {},
        properties = PopupProperties(focusable = false),
    ) {
        val surface = MaterialTheme.colorScheme.surface
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (showBelow) {
                Tail(color = surface, pointUp = true)
            }
            Surface(
                shape = RoundedCornerShape(BUBBLE_RADIUS),
                color = surface,
                tonalElevation = 3.dp,
            ) {
                Row(modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                    SelectionAction(
                        icon = Icons.Filled.RecordVoiceOver,
                        label = "朗读",
                        onClick = { onSpeak(draft.text) },
                        modifier = Modifier.weight(1f),
                    )
                    SelectionAction(
                        icon = Icons.Filled.Brush,
                        label = "高亮",
                        onClick = { onHighlight(draft) },
                        modifier = Modifier.weight(1f),
                    )
                    SelectionAction(
                        icon = Icons.Filled.ContentCopy,
                        label = "复制",
                        onClick = { onCopy(draft.text) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (!showBelow) {
                Tail(color = surface, pointUp = false)
            }
        }
    }
}

/** 单个动作：图标 + 文字竖排，≥48dp 触达，圆角裁剪 ripple */
@Composable
private fun SelectionAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 对白框尾巴三角；[pointUp] 为 true 时尖朝上（气泡在锚点下方） */
@Composable
private fun Tail(color: Color, pointUp: Boolean, modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .size(width = 16.dp, height = 8.dp)
            .rotate(if (pointUp) 180f else 0f),
    ) {
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width / 2f, size.height)
            close()
        }
        drawPath(path, color)
    }
}

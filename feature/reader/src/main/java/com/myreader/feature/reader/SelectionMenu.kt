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

/** 菜单与选区间距（dp） */
private val MENU_GAP = 10.dp

/** 锚点太靠上时的翻转阈值：低于该高度菜单改显示在选区下方 */
private val FLIP_THRESHOLD = 160.dp

/** 菜单圆角 */
private val MENU_RADIUS = 20.dp

/** 深色菜单条底色（微信读书式，深浅主题统一） */
private val MENU_DARK = Color(0xFF2C2C2C)

/** 菜单内容色（白色） */
private val MENU_CONTENT = Color.White

/**
 * M14：选中文本操作菜单（微信读书式深色横条 + 弧形尾巴），三动作横排：
 * 朗读（RecordVoiceOver）/ 高亮（Brush）/ 复制（ContentCopy）。
 *
 * [anchor] 为「选区中心 X + 选区上沿 Y」（窗口坐标）：菜单居中于选区，尾巴尖指向选区上沿。
 * 定位：默认在选区上方；上方空间不足翻转到下方（尾巴朝上）；水平 clamp 到窗口内。
 */
@Composable
internal fun SelectionMenu(
    draft: AnnotationDraft,
    anchor: Offset,
    onSpeak: (String) -> Unit,
    onHighlight: (AnnotationDraft) -> Unit,
    onCopy: (String) -> Unit,
) {
    val density = LocalDensity.current
    val gapPx = with(density) { MENU_GAP.toPx() }.roundToInt()
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
        // 非聚焦：气泡不得拦截外部触摸（否则手柄拖动/重新长按的 DOWN 被全屏 popup window 吞掉）；
        // 忽略 dismiss 回调——清除统一由渲染端「点选区外」上报（onTextSelected(null)），
        // 部分版本非聚焦 Popup 仍会因外部触摸回调 onDismissRequest 导致点选区内被误清。
        onDismissRequest = {},
        properties = PopupProperties(focusable = false),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (showBelow) {
                ArcTail(pointUp = true)
            }
            Surface(
                shape = RoundedCornerShape(MENU_RADIUS),
                color = MENU_DARK,
            ) {
                Row(modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
                    SelectionAction(
                        icon = Icons.Filled.RecordVoiceOver,
                        label = "朗读",
                        contentColor = MENU_CONTENT,
                        onClick = { onSpeak(draft.text) },
                    )
                    SelectionAction(
                        icon = Icons.Filled.Brush,
                        label = "高亮",
                        contentColor = MENU_CONTENT,
                        onClick = { onHighlight(draft) },
                    )
                    SelectionAction(
                        icon = Icons.Filled.ContentCopy,
                        label = "复制",
                        contentColor = MENU_CONTENT,
                        onClick = { onCopy(draft.text) },
                    )
                }
            }
            if (!showBelow) {
                ArcTail(pointUp = false)
            }
        }
    }
}

/** 单个动作：图标 + 文字竖排，≥48dp 触达，白色内容 */
@Composable
private fun SelectionAction(
    icon: ImageVector,
    label: String,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = contentColor,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
        )
    }
}

/** 弧形尾巴（内凹「鱼尾」）：两侧 quadratic 曲线收拢到尖点；[pointUp] 为 true 时尖朝上 */
@Composable
private fun ArcTail(pointUp: Boolean, modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .size(width = 22.dp, height = 10.dp)
            .rotate(if (pointUp) 180f else 0f),
    ) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(0f, 0f)
            quadraticTo(w * 0.35f, h * 0.55f, w / 2f, h)
            quadraticTo(w * 0.65f, h * 0.55f, w, 0f)
            close()
        }
        drawPath(path, MENU_DARK)
    }
}

package com.myreader.feature.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.myreader.core.model.ReaderFontScale
import com.myreader.core.model.ReaderZoomScale

/**
 * 显示设置面板（M15）：阅读器顶栏「Aa」入口，按文档类型分派档位——
 * reflowable（epub）显字号 5 档，固定版式（pdf/cbz/xps）显默认缩放 5 档。
 *
 * 选择即写全局偏好（DataStore，跨会话 / 跨文档生效），与设置页「显示」分区同源；
 * 字号档由渲染端差分触发整书 relayout，缩放档经 fork setDefaultScale 立即生效。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList")   // 与 DocumentViewerProvider.Content 同法：回调打包降可读性，显式签名更清晰
internal fun DisplaySheet(
    reflowable: Boolean,
    fontScale: ReaderFontScale,
    zoomScale: ReaderZoomScale,
    onFontScale: (ReaderFontScale) -> Unit,
    onZoomScale: (ReaderZoomScale) -> Unit,
    onDismiss: () -> Unit,
) {
    val fontOptions = remember { ReaderFontScale.entries }
    val zoomOptions = remember { ReaderZoomScale.entries }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            if (reflowable) {
                SectionLabel(text = "字号")
                ScaleChipRow(
                    labels = fontOptions.map { it.label },
                    selectedIndex = fontOptions.indexOf(fontScale),
                    onSelect = { index -> onFontScale(fontOptions[index]) },
                )
            } else {
                SectionLabel(text = "缩放")
                ScaleChipRow(
                    labels = zoomOptions.map { it.label },
                    selectedIndex = zoomOptions.indexOf(zoomScale),
                    onSelect = { index -> onZoomScale(zoomOptions[index]) },
                )
            }
            Text(
                text = if (reflowable) {
                    "当前字号：${fontScale.label} · 字号对 EPUB 生效，调整后重新排版分页"
                } else {
                    "缩放对 PDF 等固定版式生效，超出屏幕宽度可横向滑动"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            )
        }
    }
}

/** 分组标题（语义同设置页 SettingsSection：labelMedium + primary） */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

/** 单行档位 chip（LazyRow 允许横滚，窄屏「适合宽度」组不换行）；选中态随 colorScheme */
@Composable
private fun ScaleChipRow(labels: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    LazyRow(modifier = Modifier.fillMaxWidth()) {
        items(labels.size) { index ->
            FilterChip(
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                label = { Text(text = labels[index]) },
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
}

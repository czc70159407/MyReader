package com.myreader.feature.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.myreader.core.model.Annotation
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 高亮/笔记面板状态（与 [BookmarkPanelState] 同型：items 为空即空态）。
 */
internal sealed interface AnnotationPanelState {
    data object Loading : AnnotationPanelState
    data object Failed : AnnotationPanelState
    data class Ready(val items: List<Annotation>) : AnnotationPanelState
}

/** 面板回调集合（与 [BookmarkSheetActions] 同型，规避 Compose 组件参数膨胀） */
internal data class AnnotationSheetActions(
    val onJump: (Annotation) -> Unit,
    val onDelete: (Long) -> Unit,
)

/**
 * 高亮/笔记面板内容（M12-C3：从 ModalBottomSheet 外壳抽出，作为「标注」双 Tab 的一个 Section 复用）。
 * 列表点击跳转、尾部删除；空态给「长按拖动」引导。
 */
@Composable
internal fun AnnotationSection(
    state: AnnotationPanelState,
    actions: AnnotationSheetActions,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        when (state) {
            AnnotationPanelState.Failed -> Text(
                text = "高亮加载失败",
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                color = MaterialTheme.colorScheme.error,
            )
            AnnotationPanelState.Loading -> Row(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp), strokeWidth = 2.dp)
                Text(text = "正在加载高亮…")
            }
            is AnnotationPanelState.Ready -> if (state.items.isEmpty()) {
                Text(
                    text = "暂无高亮。长按正文拖动即可添加。",
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(items = state.items, key = { it.id }) { item ->
                        AnnotationRow(
                            item = item,
                            onJump = { actions.onJump(item) },
                            onDelete = { actions.onDelete(item.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AnnotationRow(item: Annotation, onJump: () -> Unit, onDelete: () -> Unit) {
    val formatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onJump)
            .padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "第 ${item.page + 1} 页", style = MaterialTheme.typography.bodyLarge)
            item.text.takeIf { it.isNotBlank() }?.let { excerpt ->
                Text(
                    text = excerpt.take(EXCERPT_MAX_CHARS),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = EXCERPT_MAX_LINES,
                )
            }
            Text(
                text = formatter.format(Date(item.createdAt)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(imageVector = Icons.Outlined.Delete, contentDescription = "删除高亮")
        }
    }
}

/** 摘录最大字符数（列表两行内展示） */
private const val EXCERPT_MAX_CHARS = 60

/** 摘录最大行数 */
private const val EXCERPT_MAX_LINES = 2

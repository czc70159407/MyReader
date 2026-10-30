package com.myreader.feature.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.myreader.core.model.Bookmark
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 书签面板状态（对应 `docs/design/flows/bookmark-m10.md` §4 状态矩阵）：
 * 加载 / 空与有数据都归 [Ready]（items 为空即空态）/ 失败（含重试）。
 */
internal sealed interface BookmarkPanelState {
    data object Loading : BookmarkPanelState
    data object Failed : BookmarkPanelState
    data class Ready(val items: List<Bookmark>) : BookmarkPanelState
}

/** 面板回调集合（与 T7 `FolderPickerOptions` 同型：避免 Compose 组件的参数膨胀被 detekt 拦截） */
internal data class BookmarkSheetActions(
    val onAdd: () -> Unit,
    val onJump: (Bookmark) -> Unit,
    val onDelete: (Long) -> Unit,
    val onRetry: () -> Unit,
    val onDismiss: () -> Unit,
)

/**
 * 书签面板（M10-C）：复用 [OutlineSheet] 的 ModalBottomSheet 模式，不新增页面路由。
 * 顶部「添加当前位置书签」+ 列表（点击跳转，尾部删除）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookmarkSheet(
    state: BookmarkPanelState,
    actions: BookmarkSheetActions,
) {
    ModalBottomSheet(onDismissRequest = actions.onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "书签",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = actions.onAdd) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(text = "添加当前位置书签")
                }
            }
            when (state) {
                BookmarkPanelState.Failed -> Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(text = "书签加载失败", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = actions.onRetry) { Text(text = "重试") }
                }
                BookmarkPanelState.Loading -> Row(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp), strokeWidth = 2.dp)
                    Text(text = "正在加载书签…")
                }
                is BookmarkPanelState.Ready -> if (state.items.isEmpty()) {
                    Text(
                        text = "暂无书签",
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(items = state.items, key = { it.id }) { item ->
                            BookmarkRow(
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
}

@Composable
private fun BookmarkRow(item: Bookmark, onJump: () -> Unit, onDelete: () -> Unit) {
    // 条目点击跳转；删除用显式图标按钮（比长按更易发现，读屏可直接到达）
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
            Text(text = item.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = formatter.format(Date(item.createdAt)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(imageVector = Icons.Outlined.Delete, contentDescription = "删除书签")
        }
    }
}

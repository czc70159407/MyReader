package com.myreader.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.ExperimentalFoundationApi
import com.myreader.core.document.FileTypes
import com.myreader.core.model.Folder
import com.myreader.core.model.LibraryItem

/**
 * 书架卡片组件（自 LibraryScreen.kt 拆出）：文件夹卡片 + 长按菜单、文档卡片
 * （缩略图/格式角标/状态角标/页码）、最近阅读横向条。
 * 共用视觉件（ThumbnailImage / StatusBadge / FormatBadge）在 LibraryThumbnails.kt。
 */

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FolderCard(
    folder: Folder,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    menuExpanded: Boolean,
    onDismissMenu: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onNewSubFolder: () -> Unit,
) {
    Box {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp),
                )
            }
            Text(
                text = folder.name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(8.dp),
            )
        }
        FolderMenu(
            expanded = menuExpanded,
            onDismiss = onDismissMenu,
            onRename = onRename,
            onMove = onMove,
            onDelete = onDelete,
            onNewSubFolder = onNewSubFolder,
        )
    }
}

@Composable
internal fun FolderMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onNewSubFolder: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text(text = "重命名") }, onClick = { onDismiss(); onRename() })
        DropdownMenuItem(text = { Text(text = "移动") }, onClick = { onDismiss(); onMove() })
        DropdownMenuItem(text = { Text(text = "删除") }, onClick = { onDismiss(); onDelete() })
        DropdownMenuItem(text = { Text(text = "新建子目录") }, onClick = { onDismiss(); onNewSubFolder() })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DocumentCard(
    item: LibraryItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    menuExpanded: Boolean,
    onDismissMenu: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    Box {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Box(modifier = Modifier.aspectRatio(1f)) {
                ThumbnailImage(relPath = item.thumbnailRelPath, fileType = item.fileType, modifier = Modifier.fillMaxSize())
                // M9：非 PDF 格式在左上角显示格式角标（EPUB / CBZ / XPS）
                if (item.fileType != FileTypes.PDF) {
                    FormatBadge(label = formatLabel(item.fileType), modifier = Modifier.align(Alignment.TopStart))
                }
                // 状态角标：加密 / 损坏
                when {
                    item.needsPassword -> StatusBadge(
                        icon = { Icon(Icons.Filled.Lock, contentDescription = "需要密码", tint = Color.White, modifier = Modifier.size(14.dp)) },
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                    item.pageCount == -2 -> StatusBadge(
                        icon = { Icon(Icons.Filled.Warning, contentDescription = "已损坏", tint = Color.White, modifier = Modifier.size(14.dp)) },
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                }
            }
            Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (item.pageCount == -2) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                )
                // 页码式文档显示「N 页」；流式/未知页数（epub 恒 -1、加密未开）显示格式标签
                if (item.pageCount > 0) {
                    Text(
                        text = "${item.pageCount} 页",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                } else {
                    Text(
                        text = formatLabel(item.fileType),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = onDismissMenu) {
            DropdownMenuItem(text = { Text(text = "重命名") }, onClick = { onDismissMenu(); onRename() })
            DropdownMenuItem(text = { Text(text = "移动") }, onClick = { onDismissMenu(); onMove() })
            DropdownMenuItem(text = { Text(text = "删除") }, onClick = { onDismissMenu(); onDelete() })
        }
    }
}

/** 最近阅读条（M6）：根书架网格首部横向滑动卡片，点击直达阅读器。internal 供 Compose UI 测试 */
@Composable
internal fun RecentSection(recent: List<LibraryItem>, onDocumentClick: (Long) -> Unit) {
    val listState = rememberLazyListState()
    // 阅读返回后最近条按 lastReadAt 重排（刚读的文档升到首位）：内容在保留的横向偏移下原地换卡，
    // 视觉上像卡片跳变（M6 验收用户反馈）——首项变化时把横向滚动复位到最新项，跳变变更有意的展示
    LaunchedEffect(recent.firstOrNull()?.id) {
        if (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0) {
            listState.scrollToItem(0)
        }
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "最近阅读",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            items(recent, key = { "r_${it.id}" }) { item ->
                RecentCard(item = item, onClick = { onDocumentClick(item.id) })
            }
        }
    }
}

@Composable
internal fun RecentCard(item: LibraryItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier.width(100.dp).clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(0.75f)) {
            ThumbnailImage(relPath = item.thumbnailRelPath, fileType = item.fileType, modifier = Modifier.fillMaxSize())
            // 加密角标常驻（needsPassword 不再清除，见 ReaderViewModel.backfillAfterOpen）
            if (item.needsPassword) {
                StatusBadge(
                    icon = { Icon(Icons.Filled.Lock, contentDescription = "需要密码", tint = Color.White, modifier = Modifier.size(14.dp)) },
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
        }
        Column(modifier = Modifier.fillMaxWidth().padding(6.dp)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 页码式显示「第 N 页」；流式（cfi，如 epub）无固定页概念，显示「继续阅读」
            when (item.progressType) {
                "page" -> Text(
                    text = "第 ${item.currentPage + 1} 页",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                "cfi" -> Text(
                    text = "继续阅读",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

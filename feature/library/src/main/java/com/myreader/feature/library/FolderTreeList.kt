package com.myreader.feature.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** 展开箭头触达区宽度（48dp 无障碍最小触达），无子级目录用它对齐 */
private val TOGGLE_SLOT = 48.dp
private val INDENT_STEP = 16.dp

/**
 * 目录树展平列表（docs/ui-design.md「目录树展示」）：LazyColumn 按 depth 缩进，禁止递归 Composable。
 * 点击条目回调 [onFolderClick]；长按回调 [onFolderLongPress]（目录管理操作由调用方弹菜单）；
 * 展开箭头切换折叠（仅 hasChildren 节点显示）。
 * [selectedId] 用于 FolderPickerDialog 高亮当前选中项（null = 无选中）。
 */
@Composable
fun FolderTreeList(
    state: FolderTreeState,
    onFolderClick: (Long?) -> Unit,
    onFolderLongPress: (Long) -> Unit,
    modifier: Modifier = Modifier,
    selectedId: Long? = null,
) {
    val flat by state.flat.collectAsStateWithLifecycle()
    LazyColumn(modifier = modifier) {
        items(flat, key = { it.id }) { node ->
            FolderTreeItem(
                node = node,
                expanded = state.expanded[node.id] == true,
                selected = node.id == selectedId,
                onToggle = { state.toggle(node.id) },
                onClick = { onFolderClick(node.id) },
                onLongClick = { onFolderLongPress(node.id) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderTreeItem(
    node: FolderNode,
    expanded: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val background = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = INDENT_STEP * node.depth)
            .background(background),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExpandToggle(
            hasChildren = node.hasChildren,
            expanded = expanded,
            onToggle = onToggle,
        )
        Icon(
            imageVector = Icons.Filled.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = node.name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            maxLines = 1,
        )
    }
}

@Composable
private fun ExpandToggle(hasChildren: Boolean, expanded: Boolean, onToggle: () -> Unit) {
    if (!hasChildren) {
        Spacer(modifier = Modifier.size(TOGGLE_SLOT))
        return
    }
    Icon(
        imageVector = if (expanded) Icons.Filled.ExpandMore else Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = if (expanded) "收起" else "展开",
        tint = MaterialTheme.colorScheme.outline,
        modifier = Modifier
            .size(TOGGLE_SLOT)
            .clickable(onClick = onToggle)
            .padding(12.dp),   // 视觉图标 24dp，触达区保持 48dp
    )
}

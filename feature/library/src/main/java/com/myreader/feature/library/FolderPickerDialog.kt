package com.myreader.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 树区域最大高度：AlertDialog 包内容布局，LazyColumn 必须限高 */
private val TREE_MAX_HEIGHT = 360.dp

/**
 * 目录选择弹窗（导入/移动文档/移动文件夹共用，docs/ui-design.md「导入流程」第 2 步）：
 * 顶部「根目录」项（目标 null）+ FolderTreeList + 「新建子目录」。
 * 点击条目 = 选中（高亮）；长按菜单禁用（目录管理在书架页完成，picker 保持单一职责）。
 * [excludedIds]：移动文件夹时传入自身+后代（vm.subtreeIds），选中即禁用确定并提示。
 */
@Composable
fun FolderPickerDialog(
    state: FolderTreeState,
    onConfirm: (Long?) -> Unit,
    onDismiss: () -> Unit,
    onCreateSubFolder: (parentId: Long?, name: String, onCreated: (Long) -> Unit) -> Unit,
    excludedIds: Set<Long> = emptySet(),
    initialSelectedId: Long? = null,
) {
    var selectedId by remember { mutableStateOf(initialSelectedId) }
    var showNewFolder by remember { mutableStateOf(false) }
    val invalidSelection = selectedId != null && selectedId in excludedIds

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "选择目录") },
        text = {
            Column {
                RootPickerRow(
                    selected = selectedId == null,
                    onClick = { selectedId = null },
                )
                FolderTreeList(
                    state = state,
                    onFolderClick = { id -> selectedId = id },
                    onFolderLongPress = {},   // picker 内禁长按管理
                    selectedId = selectedId,
                    modifier = Modifier.heightIn(max = TREE_MAX_HEIGHT),
                )
                TextButton(onClick = { showNewFolder = true }) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(text = "新建子目录")
                }
                if (invalidSelection) {
                    Text(
                        text = "不能移动到自身或其子目录",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selectedId) },
                enabled = !invalidSelection,
            ) { Text(text = "确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = "取消") }
        },
    )

    if (showNewFolder) {
        NewFolderDialog(
            onConfirm = { name ->
                showNewFolder = false
                // 以当前选中项为父（无选中=根），创建成功后自动选中新目录
                onCreateSubFolder(selectedId, name) { newId -> selectedId = newId }
            },
            onDismiss = { showNewFolder = false },
        )
    }
}

@Composable
private fun RootPickerRow(selected: Boolean, onClick: () -> Unit) {
    val background = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .background(background),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.size(24.dp))   // 与树条目的展开箭头槽位对齐
        Icon(
            imageVector = Icons.AutoMirrored.Filled.DriveFileMove,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = "根目录",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 12.dp),
        )
    }
}

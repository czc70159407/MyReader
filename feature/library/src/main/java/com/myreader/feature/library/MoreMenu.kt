package com.myreader.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.myreader.core.model.DocSort

/**
 * 书架顶栏「更多」菜单（T2/D2 窄屏收纳）：顶栏固定 4 动作位（导入 / 搜索 / 更多 / 设置），
 * 低频入口与排序收进本菜单。结构：动作段（新建文件夹 / 导入目录）— 分隔线 — 排序段（4 项，当前项 trailing 勾选）。
 * 排序文案与设置页「书架排序」同源（sortLabel）；搜索态不渲染本菜单（搜索结果固定按标题）。
 * 点击任意项先关菜单再执行动作；[importing] 为 true 时「导入目录」置灰，「新建文件夹」与排序不受影响。
 * internal：供 Compose UI 测试直接渲染（无 Room/VM 依赖）。
 */
@Composable
internal fun MoreMenu(
    importing: Boolean,
    onNewFolder: () -> Unit,
    onImportDir: () -> Unit,
    current: DocSort,
    onSelect: (DocSort) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(imageVector = Icons.Filled.MoreVert, contentDescription = "更多")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            // 动作段：低频目录管理入口（D2 收纳）；导入中「导入目录」置灰，「新建文件夹」不受影响
            DropdownMenuItem(
                text = { Text(text = "新建文件夹") },
                enabled = true,
                onClick = {
                    expanded = false
                    onNewFolder()
                },
            )
            DropdownMenuItem(
                text = { Text(text = "导入目录") },
                enabled = !importing,
                onClick = {
                    expanded = false
                    onImportDir()
                },
            )
            HorizontalDivider()
            // 排序段：与设置页同源双向同步；选中即写偏好并即时重排
            DocSort.entries.forEach { sort ->
                DropdownMenuItem(
                    text = { Text(text = sortLabel(sort)) },
                    trailingIcon = {
                        if (sort == current) {
                            Icon(imageVector = Icons.Filled.Check, contentDescription = "当前排序")
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(sort)
                    },
                )
            }
        }
    }
}

internal fun sortLabel(sort: DocSort): String = when (sort) {
    DocSort.TITLE -> "标题"
    DocSort.LAST_READ -> "最近阅读"
    DocSort.ADDED -> "添加时间"
    DocSort.SIZE -> "大小"
}

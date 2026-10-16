package com.myreader.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
 * 排序菜单（M6，自 LibraryScreen.kt 拆出）：顶栏三点溢出，4 个选项当前项勾选，
 * 选中即写偏好并即时生效。搜索结果固定按标题排序（DocumentDao.search），故搜索态不展示此菜单。
 * internal：供 Compose UI 测试直接渲染（无 Room/VM 依赖）。
 */
@Composable
internal fun SortMenu(current: DocSort, onSelect: (DocSort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(imageVector = Icons.Filled.MoreVert, contentDescription = "排序")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
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

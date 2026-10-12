package com.myreader.feature.library

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * 重命名弹窗（文件夹/文档共用）：预填旧名并全选（输入即替换，重命名惯例），仅改展示名
 * （文档不动 relPath/物理文件）。
 * [lockedExtension] 非空时锁定文件类型：输入框以独立后缀展示扩展名（不可编辑），
 * 预填剥离旧扩展名、提交自动拼接——用户无法改出其他类型。
 */
@Composable
fun RenameDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    lockedExtension: String? = null,
) {
    val baseName = if (lockedExtension != null) currentName.removeSuffix(lockedExtension) else currentName
    var name by remember {
        mutableStateOf(TextFieldValue(baseName, selection = TextRange(0, baseName.length)))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "重命名") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(text = "名称") },
                singleLine = true,
                suffix = lockedExtension?.let { ext -> { Text(text = ext) } },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.text.trim() + (lockedExtension ?: "")) },
                enabled = name.text.isNotBlank(),
            ) { Text(text = "确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = "取消") }
        },
    )
}

/**
 * 删除确认弹窗（文件夹/文档共用）：[message] 携带后果文案
 * （目录：「其中文档将移至根目录」；文档：「物理文件一并删除」）。
 */
@Composable
fun DeleteConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = { Text(text = message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = "删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = "取消") }
        },
    )
}

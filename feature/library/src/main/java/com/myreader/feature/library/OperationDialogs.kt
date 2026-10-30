package com.myreader.feature.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
    // T7：与新建弹窗同一套名称规则——控制字符输入期过滤、超长行内提示并禁用提交；
    // 长度按**完整名称**计（含锁定扩展名），与业务层 requireNameLen 口径一致；
    // 重名仍由业务层兜底（dialogs.md §2.3），避免重命名自身被误判为冲突
    val trimmed = name.text.trim()
    val error = nameInputError(trimmed + (lockedExtension ?: ""))
    // T5：弹窗打开即聚焦输入框；等一帧避开独立窗口焦点时序，
    // 并在聚焦后重新落一次全选（未聚焦时设置的 selection 可能被收敛到末尾）
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        focusRequester.requestFocus()
        name = name.copy(selection = TextRange(0, name.text.length))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "重命名") },
        text = {
            // 自动聚焦必弹键盘：容器 imePadding 防按钮被顶出可视区
            Column(modifier = Modifier.imePadding()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { value ->
                        val filtered = filterNameInput(value.text)
                        // 过滤后长度可能缩短：选区一并 clamp，避免越界
                        val max = filtered.length
                        name = if (filtered == value.text) {
                            value
                        } else {
                            value.copy(
                                text = filtered,
                                selection = TextRange(
                                    value.selection.start.coerceAtMost(max),
                                    value.selection.end.coerceAtMost(max),
                                ),
                            )
                        }
                    },
                    label = { Text(text = "名称") },
                    singleLine = true,
                    suffix = lockedExtension?.let { ext -> { Text(text = ext) } },
                    isError = error != null,
                    supportingText = if (error != null) {
                        { Text(text = error) }
                    } else {
                        null
                    },
                    modifier = Modifier.focusRequester(focusRequester).fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(trimmed + (lockedExtension ?: "")) },
                enabled = trimmed.isNotEmpty() && error == null,
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

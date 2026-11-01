package com.myreader.feature.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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

/** 笔记正文上限（与 `BackupCodec.MAX_ANNOTATION_NOTE_LEN` 对齐，防超长文案撑爆面板/备份） */
private const val NOTE_MAX_LEN = 5000

/**
 * 高亮笔记编辑对话框（M12-C1）：预填已有笔记，保存空串即清空（由 ViewModel 归一化为 null）。
 * 与 [GoToPageDialog] 同型：打开即聚焦输入框，容器 `imePadding` + 可滚动防按钮被键盘顶出。
 */
@Composable
fun AnnotationNoteDialog(
    annotationId: Long,
    initialNote: String?,
    onDismiss: () -> Unit,
    onSave: (Long, String?) -> Unit,
) {
    var text by remember(annotationId) { mutableStateOf(initialNote ?: "") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(annotationId) {
        withFrameNanos { }
        focusRequester.requestFocus()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "高亮笔记") },
        text = {
            Column(modifier = Modifier.imePadding().verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { v -> if (v.length <= NOTE_MAX_LEN) text = v },
                    label = { Text(text = "笔记") },
                    placeholder = { Text(text = "写下你的想法…") },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.focusRequester(focusRequester).fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(annotationId, text) }) { Text(text = "保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = "取消") }
        },
    )
}

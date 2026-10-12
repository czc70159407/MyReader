package com.myreader.feature.reader

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType

/** 跳页对话框：输入 1 基页码，越界禁用确认（内部换算 0 基 onJump） */
@Composable
fun GoToPageDialog(
    currentPage: Int,
    pageCount: Int,
    onDismiss: () -> Unit,
    onJump: (Int) -> Unit,
) {
    var text by remember { mutableStateOf((currentPage + 1).toString()) }
    val target = text.toIntOrNull()
    val valid = target != null && target in 1..pageCount

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "跳转到页面") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { v -> if (v.length <= 7 && v.all { it.isDigit() }) text = v },
                label = { Text(text = "页码（1 - $pageCount）") },
                singleLine = true,
                isError = !valid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onJump(target!! - 1) }) { Text(text = "跳转") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = "取消") }
        },
    )
}

package com.myreader.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.myreader.core.model.ANNOTATION_COLORS

/** 色点直径 */
private val COLOR_DOT_SIZE = 44.dp

/** 选中色描边宽 */
private val SELECTED_RING_WIDTH = 3.dp

/**
 * 高亮改色对话框（M12 改色）：色板单选，当前色描边高亮，点击即保存（`onSelect`）。
 * 与 [AnnotationNoteDialog] 同型（AlertDialog，不新增页面路由）。
 */
@Composable
fun AnnotationColorDialog(
    annotationId: Long,
    currentColor: Int,
    onDismiss: () -> Unit,
    onSelect: (Long, Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "高亮颜色") },
        text = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                ANNOTATION_COLORS.forEach { (color, name) ->
                    val selected = color == currentColor
                    Box(
                        modifier = Modifier
                            .size(COLOR_DOT_SIZE)
                            .clip(CircleShape)
                            .background(Color(color))
                            .then(
                                if (selected) {
                                    Modifier.border(
                                        width = SELECTED_RING_WIDTH,
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = CircleShape,
                                    )
                                } else {
                                    Modifier
                                },
                            )
                            .semantics { contentDescription = name }
                            .clickable { onSelect(annotationId, color) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = "取消") }
        },
    )
}

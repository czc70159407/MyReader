package com.myreader.feature.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 导入汇总（用户要求的「成功/失败数量及原因」反馈）：
 * 各项计数 + 逐条跳过/失败明细（上限 50 条，其余只计入总数）。
 * T4/D5：单文件多选复用本对话框（[title] 区分来源；目录专有字段为 0 时不显示对应行）。
 */
@Composable
internal fun ImportSummaryDialog(
    summary: DirectoryImportSummary,
    onDismiss: () -> Unit,
    title: String = "目录导入完成",
    fromDirectory: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // D3：取消后的部分汇总首行为取消语义（copy.md summary_cancelled）；total 行契约恒显示
                if (summary.cancelled) {
                    Text(
                        text = "已取消，已导入 ${summary.imported} 个",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "共扫描 ${summary.total} 个可导入文档",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                SummaryLine("导入成功", summary.imported)
                SummaryLine("已加密（打开时需密码）", summary.passwordMarked)
                SummaryLine("已跳过（库中已存在）", summary.duplicateSkipped)
                SummaryLine("损坏被拒收", summary.corruptRejected)
                SummaryLine("暂不支持的文件类型", summary.unsupported)
                SummaryLine("超过单文件上限", summary.tooLarge)
                SummaryLine("导入失败", summary.failed)
                // T4：单文件多选复用本对话框（fromDirectory=false 时 0/0 不显示；目录导入恒显示）
                if (fromDirectory || summary.foldersCreated > 0 || summary.foldersReused > 0) {
                    SummaryLine(
                        label = "新建目录 / 复用已有目录",
                        count = null,
                        value = "${summary.foldersCreated} / ${summary.foldersReused}",
                        showWhenZero = true,
                    )
                }
                SummaryLine("同名自动重命名", summary.renamedForConflict)
                SummaryLine("超出深度上限未处理", summary.deepSkipped)
                SummaryLine("超出数量上限未处理", summary.truncated)
                SummaryLine("无法访问的子目录", summary.inaccessibleDirs)
                if (summary.issues.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "明细：", style = MaterialTheme.typography.titleSmall)
                    summary.issues.forEach { issue ->
                        Text(
                            text = "· ${issue.path} —— ${issue.reason}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(text = "知道了") } },
    )
}

@Composable
private fun SummaryLine(
    label: String,
    count: Int?,
    value: String? = null,
    showWhenZero: Boolean = false,
) {
    if (value == null && (count == null || count <= 0)) return
    if (value != null && !showWhenZero && value.isBlank()) return
    Text(text = "$label：${value ?: count}", style = MaterialTheme.typography.bodySmall)
}

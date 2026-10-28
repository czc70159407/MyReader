package com.myreader.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myreader.core.model.DocSort
import com.myreader.core.model.ReaderMode
import com.myreader.core.model.ThemeMode

/**
 * 设置页（M7+M8）：三组单选偏好（默认阅读模式 / 主题 / 书架排序，DataStore 持久化）+ M8 备份与恢复入口。
 * 排序文案与书架 SortMenu（sortLabel）一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAbout: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val vm = viewModel
    val context = LocalContext.current
    val defaultReaderMode by vm.defaultReaderMode.collectAsStateWithLifecycle()
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    val docSort by vm.docSort.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()

    val snackbarHost = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        vm.messages.collect { snackbarHost.showSnackbar(it) }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        context.contentResolver.openOutputStream(uri)?.let { vm.exportBackup(it) }
    }
    // D1：选完 zip 先二次确认，确认前不打开输入流（取消 = 零副作用）。
    // rememberSaveable：旋转等重建后确认框仍可继续，不必重新选文件（Uri 为 Parcelable）。
    var pendingRestoreUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        // review H1：持久读授权——OpenDocument 的临时授权随进程结束失效，
        // 而 pendingRestoreUri 经 rememberSaveable 可在进程死亡/重建后恢复出确认框
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        pendingRestoreUri = uri
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            SettingsSection(title = "默认阅读模式") {
                RadioRow(
                    label = "水平分页",
                    selected = defaultReaderMode == ReaderMode.HORIZONTAL,
                    onClick = { vm.setDefaultReaderMode(ReaderMode.HORIZONTAL) },
                )
                RadioRow(
                    label = "垂直滚动",
                    selected = defaultReaderMode == ReaderMode.VERTICAL,
                    onClick = { vm.setDefaultReaderMode(ReaderMode.VERTICAL) },
                )
            }
            SettingsSection(title = "主题") {
                RadioRow(
                    label = "跟随系统",
                    selected = themeMode == ThemeMode.SYSTEM,
                    onClick = { vm.setThemeMode(ThemeMode.SYSTEM) },
                )
                RadioRow(
                    label = "浅色",
                    selected = themeMode == ThemeMode.LIGHT,
                    onClick = { vm.setThemeMode(ThemeMode.LIGHT) },
                )
                RadioRow(
                    label = "深色",
                    selected = themeMode == ThemeMode.DARK,
                    onClick = { vm.setThemeMode(ThemeMode.DARK) },
                )
            }
            SettingsSection(title = "书架排序") {
                RadioRow(
                    label = "标题",
                    selected = docSort == DocSort.TITLE,
                    onClick = { vm.setDocSort(DocSort.TITLE) },
                )
                RadioRow(
                    label = "最近阅读",
                    selected = docSort == DocSort.LAST_READ,
                    onClick = { vm.setDocSort(DocSort.LAST_READ) },
                )
                RadioRow(
                    label = "添加时间",
                    selected = docSort == DocSort.ADDED,
                    onClick = { vm.setDocSort(DocSort.ADDED) },
                )
                RadioRow(
                    label = "大小",
                    selected = docSort == DocSort.SIZE,
                    onClick = { vm.setDocSort(DocSort.SIZE) },
                )
            }
            // M8：备份与恢复（导出 library/ + 缩略图 + DB 快照到用户指定 zip；从备份合并式恢复）
            SettingsSection(title = "备份与恢复") {
                if (busy) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = if (status.isNotEmpty()) status else "处理中…",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    )
                } else {
                    BackupRow(label = "导出备份", onClick = { exportLauncher.launch("myreader-backup.zip") })
                    BackupRow(
                        label = "从备份恢复",
                        onClick = { restoreLauncher.launch(arrayOf("application/zip")) },
                    )
                }
            }
            // AGPL 合规入口：开源与第三方声明必须在应用内可查（工作规则 2）
            SettingsSection(title = "关于") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenAbout)
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = "开源许可与第三方声明", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }

    // D1：恢复前二次确认（合并式语义先讲清后果；取消则不打开输入流，零副作用）
    pendingRestoreUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingRestoreUri = null },
            title = { Text(text = "从备份恢复") },
            text = {
                Text(text = "将合并备份中的目录与文档记录：同名记录以备份为准，备份之外的数据不受影响。是否继续？")
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        pendingRestoreUri = null
                        // 确认后才读取流；打开失败（null / SecurityException / FileNotFound）显式上报，不静默不崩溃
                        val input = runCatching { context.contentResolver.openInputStream(uri) }
                            .onFailure { vm.reportRestoreStreamUnavailable() }
                            .getOrNull()
                        input?.let(vm::restoreBackup)
                    },
                ) { Text(text = "继续恢复") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestoreUri = null }) { Text(text = "取消") }
            },
        )
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
    )
    Column(content = content)
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun BackupRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = true, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

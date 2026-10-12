package com.myreader.feature.settings

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myreader.core.model.DocSort
import com.myreader.core.model.ReaderMode
import com.myreader.core.model.ThemeMode

/**
 * 设置页（M7）：三组单选偏好（默认阅读模式 / 主题 / 书架排序），DataStore 持久化、重启保持。
 * 排序文案与书架 SortMenu（sortLabel）一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val defaultReaderMode by viewModel.defaultReaderMode.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val docSort by viewModel.docSort.collectAsStateWithLifecycle()

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
                    onClick = { viewModel.setDefaultReaderMode(ReaderMode.HORIZONTAL) },
                )
                RadioRow(
                    label = "垂直滚动",
                    selected = defaultReaderMode == ReaderMode.VERTICAL,
                    onClick = { viewModel.setDefaultReaderMode(ReaderMode.VERTICAL) },
                )
            }
            SettingsSection(title = "主题") {
                RadioRow(
                    label = "跟随系统",
                    selected = themeMode == ThemeMode.SYSTEM,
                    onClick = { viewModel.setThemeMode(ThemeMode.SYSTEM) },
                )
                RadioRow(
                    label = "浅色",
                    selected = themeMode == ThemeMode.LIGHT,
                    onClick = { viewModel.setThemeMode(ThemeMode.LIGHT) },
                )
                RadioRow(
                    label = "深色",
                    selected = themeMode == ThemeMode.DARK,
                    onClick = { viewModel.setThemeMode(ThemeMode.DARK) },
                )
            }
            SettingsSection(title = "书架排序") {
                RadioRow(
                    label = "标题",
                    selected = docSort == DocSort.TITLE,
                    onClick = { viewModel.setDocSort(DocSort.TITLE) },
                )
                RadioRow(
                    label = "最近阅读",
                    selected = docSort == DocSort.LAST_READ,
                    onClick = { viewModel.setDocSort(DocSort.LAST_READ) },
                )
                RadioRow(
                    label = "添加时间",
                    selected = docSort == DocSort.ADDED,
                    onClick = { viewModel.setDocSort(DocSort.ADDED) },
                )
                RadioRow(
                    label = "大小",
                    selected = docSort == DocSort.SIZE,
                    onClick = { viewModel.setDocSort(DocSort.SIZE) },
                )
            }
        }
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

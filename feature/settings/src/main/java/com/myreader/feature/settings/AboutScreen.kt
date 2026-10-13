package com.myreader.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 关于页：**AGPL 合规要求的应用内声明**（工作规则 2）。
 *
 * 此前仅 README 有声明、应用内缺失，而 README 却写着「应用内 About 页同步展示」——本页补齐该缺口，
 * 使「MuPDF 来源 + AGPL-3.0 全文链接 + 本项目整体开源」在应用内可见。
 * 文案与 README「许可证与开源声明（AGPL 合规）」章节保持一致，改动需两处同步。
 */
private const val AGPL_URL = "https://www.gnu.org/licenses/agpl-3.0.html"
private const val MUPDF_HOME_URL = "https://mupdf.com"
private const val MUPDF_REPO_URL = "https://gitlab.com/artifexsoftware/mupdf-android-viewer"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "关于") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
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
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            Text(text = "MyReader", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "安卓本地文档阅读器",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            AboutSection(title = "开源许可") {
                Text(
                    text = "本项目整体以 AGPL-3.0 开源（含服务端条款的网络传播义务）。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "AGPL-3.0 全文：$AGPL_URL",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            AboutSection(title = "PDF 渲染引擎") {
                Text(
                    text = "PDF 渲染引擎为 Artifex Software 的 MuPDF，同样遵循 AGPL-3.0，以 git submodule 形式集成自其官方仓库。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "MuPDF 主页：$MUPDF_HOME_URL",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = "源码仓库：$MUPDF_REPO_URL",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun AboutSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
    )
    Column(content = content)
}

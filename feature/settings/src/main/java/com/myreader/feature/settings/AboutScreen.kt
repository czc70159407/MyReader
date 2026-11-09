package com.myreader.feature.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * 关于页：**AGPL 合规要求的应用内声明**（工作规则 2）。
 *
 * 文案与 README「许可证与开源声明（AGPL 合规）」章节保持一致，改动需两处同步。
 * 文案键见 docs/design/copy.md §10，页面规格见 docs/design/screens/about.md。
 */
// 链接事实源：docs/design/screens/about.md §5；变更需同步 README.md 合规章节
private const val AGPL_URL = "https://www.gnu.org/licenses/agpl-3.0.html"
private const val PROJECT_REPO_URL = "https://github.com/czc70159407/MyReader"
private const val MUPDF_HOME_URL = "https://mupdf.com"
private const val MUPDF_UPSTREAM_URL = "https://github.com/ArtifexSoftware/mupdf-android-viewer"
private const val VIEWER_FORK_URL = "https://github.com/czc70159407/mupdf-android-viewer/tree/myreader-patches"

private const val MSG_LINK_COPIED = "链接已复制"
private const val MSG_NO_BROWSER = "未找到可打开链接的应用，请长按复制链接"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val versionName = remember(context) { readVersionName(context) }

    fun showMessage(message: String) {
        scope.launch { snackbarHost.showSnackbar(message) }
    }

    val actions = AboutLinkActions(
        onOpen = { url ->
            // 不用 LocalUriHandler：Compose 1.7.4 的 AndroidUriHandler 会把 ActivityNotFoundException
            // 包装成 IllegalArgumentException 重新抛出，捕获不到会导致无浏览器设备崩溃。
            // 自行 startActivity 精确捕获，兜底文案给出下一步（长按复制）。
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
            } catch (ignored: ActivityNotFoundException) {
                showMessage(MSG_NO_BROWSER)
            }
        },
        onCopy = { url ->
            clipboard.setText(AnnotatedString(url))
            showMessage(MSG_LINK_COPIED)
        },
    )

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
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        AboutContent(
            versionName = versionName,
            actions = actions,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
        )
    }
}

/** 页面正文：应用标识 + 合规两段 + 五个链接。抽子组件以控住 AboutScreen 方法长度（detekt LongMethod）。 */
@Composable
private fun AboutContent(
    versionName: String,
    actions: AboutLinkActions,
    modifier: Modifier,
) {
    Column(modifier = modifier) {
        AboutAppHeader(versionName = versionName)

        AboutSection(title = "开源许可") {
            Text(
                text = "本项目整体以 AGPL-3.0 开源（含服务端条款的网络传播义务）。",
                style = MaterialTheme.typography.bodyMedium,
            )
            AboutLinkRow(label = "AGPL-3.0 全文", url = AGPL_URL, actions = actions)
            AboutLinkRow(label = "本项目源码", url = PROJECT_REPO_URL, actions = actions)
        }

        AboutSection(title = "PDF 渲染引擎") {
            Text(
                text = "PDF 渲染引擎为 Artifex Software 的 MuPDF，同样遵循 AGPL-3.0，以 git submodule 形式集成自其官方仓库的自有分支。",
                style = MaterialTheme.typography.bodyMedium,
            )
            AboutLinkRow(label = "MuPDF 主页", url = MUPDF_HOME_URL, actions = actions)
            AboutLinkRow(label = "MuPDF 上游", url = MUPDF_UPSTREAM_URL, actions = actions)
            AboutLinkRow(label = "viewer 分支", url = VIEWER_FORK_URL, actions = actions)
        }
    }
}

/** 应用标识 + 名称 + 描述 + 版本号。标识为装饰，不朗读；色值全部取自 colorScheme（D11）。 */
@Composable
private fun AboutAppHeader(versionName: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(32.dp),
            )
        }
        Text(
            text = "MyReader",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(text = "安卓本地文档阅读器", style = MaterialTheme.typography.bodyMedium)
        if (versionName.isNotEmpty()) {
            Text(
                text = "版本 $versionName",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 链接行：点击打开、长按复制，手写链接语义与 48dp 触达（docs/design/screens/about.md §4/§7）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AboutLinkRow(
    label: String,
    url: String,
    actions: AboutLinkActions,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .combinedClickable(
                onClick = { actions.onOpen(url) },
                onLongClick = { actions.onCopy(url) },
            )
            .clearAndSetSemantics {
                contentDescription = "$label，链接。双击打开，长按复制"
                role = Role.Button
                onClick(label = "打开链接") {
                    actions.onOpen(url)
                    true
                }
                onLongClick(label = "复制链接") {
                    actions.onCopy(url)
                    true
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$label：$url",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
        )
    }
}

/** 打包链接回调，规避 detekt LongParameterList（与项目内 ReaderTopBarActions 同型）。 */
private data class AboutLinkActions(
    val onOpen: (String) -> Unit,
    val onCopy: (String) -> Unit,
)

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

/** 版本号运行时读取，避免硬编码与 BuildConfig 依赖（feature:settings 未启用 buildConfig）。 */
private fun readVersionName(context: Context): String {
    val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(0L),
        )
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    return packageInfo.versionName.orEmpty()
}

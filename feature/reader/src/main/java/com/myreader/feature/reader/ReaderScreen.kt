package com.myreader.feature.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myreader.core.document.ProgressPayload
import com.myreader.core.document.ReaderMode

/**
 * 阅读器页（M4 水平模式）：渲染经 DocumentViewerProvider 接口路由到 MuPDF 桥接。
 * 进度回写：翻页回调 → ViewModel 防抖落库；ON_PAUSE/ON_STOP/离开页面时 flush 保底。
 * 跳页/大纲：写入 uiPage 状态 → 经 initialProgress 传入渲染端做差分跳转。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    onBack: () -> Unit,
    viewModel: ReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 当前 UI 页码（0 基）：渲染回调写入 + 跳页/大纲写入（驱动渲染端 update 差分）
    var uiPage by remember { mutableStateOf(0) }
    var showOutline by remember { mutableStateOf(false) }
    var showGoTo by remember { mutableStateOf(false) }

    // 进度保底：ON_PAUSE / ON_STOP / 离开组合时即时落库
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                viewModel.flushProgress()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.flushProgress() }
    }

    when (val s = state) {
        is ReaderUiState.Loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        is ReaderUiState.NeedsPassword -> {
            // 取消即返回书架；先让弹窗离开组合（其独立窗口立即销毁）再触发导航——
            // 否则弹窗作为残影悬在书架转场之上、随目的地离场突然消失，与页面退场速度不同步（M6 用户反馈）
            var dialogDismissed by remember { mutableStateOf(false) }
            if (!dialogDismissed) {
                PasswordDialog(
                    wrongPassword = s.wrongPassword,
                    onDismiss = {
                        dialogDismissed = true
                        onBack()
                    },
                    onConfirm = viewModel::submitPassword,
                )
            }
        }

        is ReaderUiState.Failed -> Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = s.message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = onBack, modifier = Modifier.padding(top = 24.dp)) {
                    Text(text = "返回书架")
                }
            }
        }

        is ReaderUiState.Ready -> {
            // 就绪后同步初始页（仅一次；此后 uiPage 完全由渲染回调/跳页动作驱动）
            LaunchedEffect(s) { uiPage = s.initialPage }

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text(text = s.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    text = "${(uiPage + 1).coerceAtMost(s.pageCount)} / ${s.pageCount}",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(imageVector = Icons.Filled.ArrowBack, contentDescription = "返回")
                            }
                        },
                        actions = {
                            IconButton(onClick = { showOutline = true }) {
                                Icon(imageVector = Icons.AutoMirrored.Filled.List, contentDescription = "目录")
                            }
                            IconButton(onClick = { showGoTo = true }) {
                                Icon(imageVector = Icons.Filled.FindInPage, contentDescription = "跳页")
                            }
                        },
                    )
                },
            ) { padding ->
                Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                    s.viewer.Content(
                        doc = s.doc,
                        initialProgress = ProgressPayload.Page(uiPage),
                        mode = ReaderMode.HORIZONTAL,
                        onProgressChanged = { payload ->
                            if (payload is ProgressPayload.Page) {
                                uiPage = payload.page
                                viewModel.onPageChanged(payload.page)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            if (showOutline) {
                OutlineSheet(
                    load = { viewModel.loadOutline() },
                    onDismiss = { showOutline = false },
                    // 跳页同步通知 VM：不依赖下一帧渲染回调，防同帧退出时 flush 落旧页；
                    // clamp 防损坏大纲越界页码落库（渲染端 setDisplayedViewIndex 对越界 no-op），
                    // coerceAtLeast 与 ReaderViewModel.initialPage 处同款防御（pageCount=0 时上界不越 -1）
                    onJump = { page ->
                        val target = page.coerceIn(0, (s.pageCount - 1).coerceAtLeast(0))
                        uiPage = target
                        viewModel.onPageChanged(target)
                    },
                )
            }
            if (showGoTo) {
                GoToPageDialog(
                    currentPage = uiPage,
                    pageCount = s.pageCount,
                    onDismiss = { showGoTo = false },
                    onJump = { page ->
                        uiPage = page
                        viewModel.onPageChanged(page)
                        showGoTo = false
                    },
                )
            }
        }
    }
}

/** 加密文档密码对话框：密码不持久化（v1 仅会话内），错误时提示重试 */
@Composable
private fun PasswordDialog(
    wrongPassword: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "文档已加密") },
        text = {
            Column {
                if (wrongPassword) {
                    Text(
                        text = "密码错误，请重试",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(text = "密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = password.isNotEmpty(), onClick = { onConfirm(password) }) {
                Text(text = "打开")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = "取消") }
        },
    )
}

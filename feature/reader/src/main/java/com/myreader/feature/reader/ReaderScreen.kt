package com.myreader.feature.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import com.myreader.core.document.EpubProgressCodec
import com.myreader.core.document.ProgressPayload
import com.myreader.core.model.Bookmark
import com.myreader.core.model.ReaderMode

/**
 * 阅读器页（M4 水平模式 / M7 增垂直滚动 + 顶栏实时切换）：渲染经 DocumentViewerProvider 接口路由。
 * 进度回写：翻页/滚动回调 → ViewModel 防抖落库；ON_PAUSE/ON_STOP/离开页面时 flush 保底。
 * 跳页/大纲：写入 uiPage/uiOffsetY → 经 initialProgress 传入渲染端做差分跳转（offset 置 0 顶部对齐）。
 * 模式切换：VM 会话态（不回写设置偏好），当前页经 initialProgress 传递保持。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    onBack: () -> Unit,
    viewModel: ReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    // 实时排版页数（T3）：epub 水平 relayout 后由视图回调上抛；null 时以 Ready 快照兜底
    val livePageCount by viewModel.displayPageCount.collectAsStateWithLifecycle()
    // M10-C：书签（Ready 后持续观察；顶栏已收藏态与面板共用）
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val bookmarksLoading by viewModel.bookmarksLoading.collectAsStateWithLifecycle()
    val bookmarksError by viewModel.bookmarksError.collectAsStateWithLifecycle()

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
            // T5：加载态可被读屏感知
            CircularProgressIndicator(
                modifier = Modifier.semantics { contentDescription = "正在加载" },
            )
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

        is ReaderUiState.Ready -> key(s) {
            // 初始进度在组合期即就位（key(s) 内 remember 直接取 s 初值）——消除「首帧 uiPage=0
            // 先组合 Content、LaunchedEffect 帧后才补正值」的恢复竞态窗口：该窗口内垂直视图会
            // 恢复到第 1 页并回流 (0,0) 覆盖库中进度（M7 真机验收发现）。密码重试产生新 Ready
            // 实例 → key 变化 → 全套状态按新会话重置（弹窗态一并清零）
            // M9：进度统一为 ProgressPayload（页码式 Page / 流式 Cfi）；顶栏页码由 payload 派生。
            var uiProgress by remember { mutableStateOf(s.initialProgress) }
            var showOutline by remember { mutableStateOf(false) }
            var showGoTo by remember { mutableStateOf(false) }
            var showBookmarks by remember { mutableStateOf(false) }
            val snackbarHostState = remember { SnackbarHostState() }
            LaunchedEffect(Unit) {
                viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
            }

            // 展示页数：打开快照兜底，relayout 后以视图回调的实时值为准（T3）。
            // 不 copy Ready 回写 pageCount——Ready 变化会重置 key(s) 内全套会话状态（uiProgress 回退）
            val pageCount = livePageCount ?: s.pageCount

            // 顶栏/跳页用的当前页码：页码式取 page；流式 CFI 解出内嵌 page（展示近似值）
            val uiPage: Int = when (val p = uiProgress) {
                is ProgressPayload.Page -> p.page
                is ProgressPayload.Cfi -> EpubProgressCodec.decode(p.cfi)?.page ?: 0
            }

            // 顶栏书签图标态：当前页/锚点是否已收藏（与面板列表同一判据）
            val currentBookmarkKey: Pair<Int, String?>? = when (val p = uiProgress) {
                is ProgressPayload.Page -> p.page to null
                is ProgressPayload.Cfi -> EpubProgressCodec.decode(p.cfi)?.let { it.page to it.loc }
            }
            val currentBookmarked = currentBookmarkKey != null &&
                bookmarks.any { it.page == currentBookmarkKey.first && it.anchor == currentBookmarkKey.second }

            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text(text = s.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    text = "${(uiPage + 1).coerceAtMost(pageCount)} / $pageCount",
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
                            // M10-C：书签面板入口；图标实心/描边反映「当前页已收藏」
                            IconButton(onClick = { showBookmarks = true }) {
                                Icon(
                                    imageVector = if (currentBookmarked) {
                                        Icons.Filled.Bookmark
                                    } else {
                                        Icons.Filled.BookmarkBorder
                                    },
                                    contentDescription = "书签",
                                )
                            }
                            IconButton(onClick = { showOutline = true }) {
                                Icon(imageVector = Icons.AutoMirrored.Filled.List, contentDescription = "目录")
                            }
                            IconButton(onClick = { showGoTo = true }) {
                                Icon(imageVector = Icons.Filled.FindInPage, contentDescription = "跳页")
                            }
                            // 阅读模式实时切换（M7）：会话内生效不回写偏好；当前进度经 initialProgress 保持
                            IconButton(onClick = viewModel::toggleMode) {
                                Icon(
                                    imageVector = Icons.Filled.SwapHoriz,
                                    contentDescription = "切换阅读模式",
                                )
                            }
                        },
                    )
                },
            ) { padding ->
                Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                    s.viewer.Content(
                        doc = s.doc,
                        initialProgress = uiProgress,
                        mode = mode,
                        onProgressChanged = { payload ->
                            uiProgress = payload
                            viewModel.onProgress(payload)
                        },
                        onPageCountChanged = viewModel::onPageCountChanged,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            if (showBookmarks) {
                // 首次加载且无数据 → 加载态；已有数据时后台刷新不闪加载态；异常优先展示重试
                val panelState: BookmarkPanelState = when {
                    bookmarksError -> BookmarkPanelState.Failed
                    bookmarksLoading && bookmarks.isEmpty() -> BookmarkPanelState.Loading
                    else -> BookmarkPanelState.Ready(bookmarks)
                }
                BookmarkSheet(
                    state = panelState,
                    actions = BookmarkSheetActions(
                        onAdd = viewModel::addBookmarkAtCurrent,
                        onJump = { bookmark: Bookmark ->
                            val progress = if (bookmark.anchor != null) {
                                // 流式：以 bookmark 串重建 payload，渲染端解析锚点定位（跨重排稳定）
                                ProgressPayload.Cfi(
                                    EpubProgressCodec.encode(loc = bookmark.anchor, page = bookmark.page),
                                )
                            } else {
                                // 页码式：clamp 防历史/备份越界；垂直偏移原样恢复
                                ProgressPayload.Page(
                                    page = bookmark.page.coerceIn(0, (pageCount - 1).coerceAtLeast(0)),
                                    scrollOffsetY = bookmark.scrollOffsetY.coerceAtLeast(0),
                                )
                            }
                            uiProgress = progress
                            // 与大纲跳页同策略：页码式立即落库；流式由渲染端 reposition 后回发 CFI
                            if (!s.reflowable) viewModel.onProgress(progress)
                            showBookmarks = false
                        },
                        onDelete = viewModel::deleteBookmark,
                        onRetry = viewModel::retryBookmarks,
                        onDismiss = { showBookmarks = false },
                    ),
                )
            }
            if (showOutline) {
                OutlineSheet(
                    load = { viewModel.loadOutline() },
                    onDismiss = { showOutline = false },
                    // 跳页同步通知 VM：不依赖下一帧渲染回调，防同帧退出时 flush 落旧页；
                    // clamp 防损坏大纲越界页码落库（渲染端 setDisplayedViewIndex 对越界 no-op），
                    // coerceAtLeast 与 ReaderViewModel.initialProgress 处同款防御（pageCount=0 时上界不越 -1）
                    onJump = { page ->
                        val target = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
                        uiProgress = ProgressPayload.Page(target, 0)
                        // 流式文档由渲染端 reposition 后回发 CFI 落库；页码式立即落库
                        if (!s.reflowable) viewModel.onProgress(ProgressPayload.Page(target, 0))
                    },
                )
            }
            if (showGoTo) {
                GoToPageDialog(
                    currentPage = uiPage,
                    pageCount = pageCount,
                    onDismiss = { showGoTo = false },
                    onJump = { page ->
                        uiProgress = ProgressPayload.Page(page, 0)
                        if (!s.reflowable) viewModel.onProgress(ProgressPayload.Page(page, 0))
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
    // T5：弹窗打开即聚焦密码框（等一帧避开独立窗口焦点时序）
    val passwordFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        passwordFocusRequester.requestFocus()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "文档已加密") },
        text = {
            // 自动聚焦必弹键盘：容器 imePadding 防按钮被顶出可视区
            Column(modifier = Modifier.imePadding()) {
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
                    modifier = Modifier
                        .focusRequester(passwordFocusRequester)
                        .fillMaxWidth(),
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

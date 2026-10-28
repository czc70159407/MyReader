package com.myreader.feature.library

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myreader.core.document.FileTypes
import com.myreader.core.model.Folder
import com.myreader.core.model.LibraryItem
import kotlinx.coroutines.CancellationException

/**
 * 书架页（M5）：单层目录浏览（子文件夹卡片 + 文档网格）+ SAF 导入（先选目标目录）
 * + 文件夹/文档长按菜单（重命名/移动/删除/新建子目录）+ 目录树选择弹窗。
 * 点击文件夹 → onFolderClick 进子目录（library?folderId=xx）；点击文档 → onDocumentClick 进阅读器。
 *
 * 本文件只保留屏幕级编排（工程优化 2026-10-16，原 37KB 单文件拆分为）：
 * - 卡片与最近阅读条 → [LibraryCards.kt]（FolderCard / DocumentCard / RecentSection）
 * - 缩略图与角标 → [LibraryThumbnails.kt]（ThumbnailImage / StatusBadge / FormatBadge）
 * - 更多菜单（T2/D2 收纳：新建文件夹 / 导入目录 / 排序）→ [MoreMenu.kt]
 */
// ExperimentalFoundationApi 原为 combinedClickable 等卡片件所需，已随拆分移至 LibraryCards.kt
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onDocumentClick: (Long) -> Unit = {},
    onFolderClick: (Long?) -> Unit = {},
    onNavigateBack: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val documents by viewModel.documents.collectAsStateWithLifecycle()
    val breadcrumb by viewModel.breadcrumb.collectAsStateWithLifecycle()
    val initialLoad by viewModel.initialLoad.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val pendingDuplicate by viewModel.pendingDuplicate.collectAsStateWithLifecycle()
    val pendingImportUris by viewModel.pendingImportUris.collectAsStateWithLifecycle()
    val currentSort by viewModel.sort.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val searchActive by viewModel.searchOpen.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val importProgress by viewModel.importProgress.collectAsStateWithLifecycle()
    val pendingDirectoryUri by viewModel.pendingDirectoryUri.collectAsStateWithLifecycle()
    val importSummary by viewModel.importSummary.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    // T5 review L19：搜索态自动聚焦搜索框（等一帧避开布局期焦点请求）
    val searchFocusRequester = remember { FocusRequester() }
    LaunchedEffect(searchActive) {
        if (searchActive) {
            withFrameNanos { }
            searchFocusRequester.requestFocus()
        }
    }

    // 系统返回手势：搜索态先收起搜索，不 pop 导航栈（searchActive 来自 VM，跨导航一致）
    BackHandler(enabled = searchActive) { viewModel.exitSearch() }

    // 长按菜单展开目标（卡片内 DropdownMenu 以 menuX?.id == item.id 判断展开）
    var menuFolder by remember { mutableStateOf<Folder?>(null) }
    var menuDocument by remember { mutableStateOf<LibraryItem?>(null) }

    // 操作弹窗状态
    var showNewFolderInCurrent by remember { mutableStateOf(false) }   // 顶栏新建（当前目录）
    var renameFolderTarget by remember { mutableStateOf<Folder?>(null) }
    var renameDocTarget by remember { mutableStateOf<LibraryItem?>(null) }
    var deleteFolderTarget by remember { mutableStateOf<Folder?>(null) }
    var deleteDocTarget by remember { mutableStateOf<LibraryItem?>(null) }
    var moveDocTarget by remember { mutableStateOf<LibraryItem?>(null) }
    var moveFolderTarget by remember { mutableStateOf<Folder?>(null) }  // 移动文件夹（先取 subtree 排除集）
    var newSubFolderParent by remember { mutableStateOf<Folder?>(null) }  // 长按菜单「新建子目录」目标

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.onUrisPicked(uris)
    }

    // 目录导入：SAF 目录树选择器（无存储权限）。导入是异步耗时操作，回调里立即取持久读授权，
    // 避免授权在导入完成前被回收；取权失败仍尝试导入（Uri 通常仍有单次读权限）。
    val dirLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        viewModel.onDirectoryPicked(uri)
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message -> snackbarHostState.showSnackbar(message) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searchActive) {
                        // 搜索态：标题槽换输入框（实时搜索，结果由 300ms 防抖驱动）
                        TextField(
                            value = searchQuery,
                            onValueChange = viewModel::onSearchQueryChange,
                            placeholder = {
                                Text(
                                    text = "搜索全部文档",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            },
                            // T5 review L19：进入搜索态自动聚焦，读屏/键盘用户免一次触达
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(searchFocusRequester),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = viewModel::exitSearch) {
                                        Icon(imageVector = Icons.Filled.Close, contentDescription = "清空关键词")
                                    }
                                }
                            },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                        )
                    } else {
                        // 面包屑：书架 / A / B ——根级弱化，当前级加粗；单 Text 便于整体省略
                        Text(
                            text = buildAnnotatedString {
                                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                                    append("书架")
                                }
                                breadcrumb.forEachIndexed { i, name ->
                                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                                        append(" / ")
                                    }
                                    withStyle(
                                        SpanStyle(
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontWeight = if (i == breadcrumb.lastIndex) FontWeight.Bold else FontWeight.Normal,
                                        ),
                                    ) { append(name) }
                                }
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    when {
                        searchActive ->
                            IconButton(onClick = viewModel::exitSearch) {
                                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "退出搜索")
                            }
                        !viewModel.isRoot ->
                            IconButton(onClick = onNavigateBack) {
                                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回上级")
                            }
                    }
                },
                actions = {
                    if (!searchActive) {
                        // T2/D2 窄屏收纳：顶栏固定 4 动作位，「新建文件夹」「导入目录」与排序收进更多菜单
                        IconButton(
                            onClick = { importLauncher.launch(FileTypes.mimeFilters()) },
                            enabled = !importing,
                        ) {
                            Icon(imageVector = Icons.Filled.Add, contentDescription = "导入文档")
                        }
                        IconButton(onClick = viewModel::openSearch) {
                            Icon(imageVector = Icons.Filled.Search, contentDescription = "搜索")
                        }
                        MoreMenu(
                            importing = importing,
                            onNewFolder = { showNewFolderInCurrent = true },
                            onImportDir = { dirLauncher.launch(null) },
                            current = currentSort,
                            onSelect = viewModel::setSort,
                        )
                        IconButton(onClick = onOpenSettings) {
                            Icon(imageVector = Icons.Filled.Settings, contentDescription = "设置")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                // 首帧数据未到达前显示加载占位，避免每层导航闪现空态
                initialLoad -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    // T5：加载态可被读屏感知
                    CircularProgressIndicator(
                        modifier = Modifier.semantics { contentDescription = "正在加载" },
                    )
                }
                // 搜索态（仅以 searchResults != null 切换；空白关键词/防抖中维持浏览内容）
                searchResults != null -> {
                    val results = searchResults.orEmpty()
                    if (results.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.outline,
                            )
                            Text(
                                text = "未找到匹配文档",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(top = 16.dp),
                            )
                        }
                    } else {
                        // 全库结果（复用文档卡片与长按菜单；key 与浏览态同规范）
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 108.dp),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(results, key = { "d_${it.id}" }) { item ->
                                DocumentCard(
                                    item = item,
                                    onClick = { onDocumentClick(item.id) },
                                    onLongClick = { menuDocument = item },
                                    menuExpanded = menuDocument?.id == item.id,
                                    onDismissMenu = { menuDocument = null },
                                    onRename = { renameDocTarget = item },
                                    onMove = { moveDocTarget = item },
                                    onDelete = { deleteDocTarget = item },
                                )
                            }
                        }
                    }
                }
                folders.isEmpty() && documents.isEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Description,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                    Text(
                        text = if (viewModel.isRoot) "书架还是空的\n点右上角 + 导入文档" else "此文件夹为空",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
                else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 108.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // M6 最近阅读条：仅根目录 + 有阅读记录时显示（搜索态走上方独立分支）
                    if (viewModel.isRoot && recent.isNotEmpty()) {
                        item(key = "recent_header", span = { GridItemSpan(maxLineSpan) }) {
                            RecentSection(recent = recent, onDocumentClick = onDocumentClick)
                        }
                    }
                    items(folders, key = { "f_${it.id}" }) { folder ->
                        FolderCard(
                            folder = folder,
                            onClick = { onFolderClick(folder.id) },
                            onLongClick = { menuFolder = folder },
                            menuExpanded = menuFolder?.id == folder.id,
                            onDismissMenu = { menuFolder = null },
                            onRename = { renameFolderTarget = folder },
                            onMove = { moveFolderTarget = folder },
                            onDelete = { deleteFolderTarget = folder },
                            onNewSubFolder = { newSubFolderParent = folder },
                        )
                    }
                    items(documents, key = { "d_${it.id}" }) { item ->
                        DocumentCard(
                            item = item,
                            onClick = { onDocumentClick(item.id) },
                            onLongClick = { menuDocument = item },
                            menuExpanded = menuDocument?.id == item.id,
                            onDismissMenu = { menuDocument = null },
                            onRename = { renameDocTarget = item },
                            onMove = { moveDocTarget = item },
                            onDelete = { deleteDocTarget = item },
                        )
                    }
                }
            }   // else -> 网格分支
        }   // when（initialLoad / 空态 / 网格）

            if (importing) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(16.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(24.dp),
                        )
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        text = importProgress?.let { p ->
                            when (p.phase) {
                                DirectoryImportProgress.Phase.SCANNING -> "正在扫描目录…"
                                DirectoryImportProgress.Phase.IMPORTING -> "正在导入 ${p.current}/${p.total}…"
                            }
                        } ?: "正在导入…",
                        style = MaterialTheme.typography.bodyMedium,
                        // T5：读屏礼貌播报进度（更新频率由 VM 侧 1s 节流控制）
                        modifier = Modifier
                            .weight(1f)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    // T3/D3：目录导入可取消（单文件导入不显示，保持范围）。
                    // review M9：触达 ≥48dp + 读屏语义 CD「取消导入」
                    if (importProgress != null) {
                        TextButton(
                            onClick = viewModel::cancelImport,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .semantics { contentDescription = "取消导入" },
                        ) {
                            Text(text = "取消")
                        }
                    }
                }
            }
        }
    }

    // ---- 操作弹窗 ----

    // 新建文件夹（顶栏 = 当前目录；文件夹长按菜单 = 该文件夹）
    if (showNewFolderInCurrent || newSubFolderParent != null) {
        NewFolderDialog(
            onConfirm = { name ->
                viewModel.createSubFolder(
                    newSubFolderParent?.id ?: viewModel.currentFolderId,
                    name,
                )
                showNewFolderInCurrent = false
                newSubFolderParent = null
            },
            onDismiss = {
                showNewFolderInCurrent = false
                newSubFolderParent = null
            },
        )
    }

    renameFolderTarget?.let { folder ->
        RenameDialog(
            currentName = folder.name,
            onConfirm = {
                viewModel.renameFolder(folder.id, it)
                renameFolderTarget = null
            },
            onDismiss = { renameFolderTarget = null },
        )
    }

    renameDocTarget?.let { item ->
        // 锁定文件类型：扩展名独立展示不可编辑，防止改出错误类型（无扩展名的 title 不锁）
        val ext = if ('.' in item.title) "." + item.title.substringAfterLast('.') else null
        RenameDialog(
            currentName = item.title,
            lockedExtension = ext,
            onConfirm = {
                viewModel.renameDocument(item.id, it)
                renameDocTarget = null
            },
            onDismiss = { renameDocTarget = null },
        )
    }

    deleteFolderTarget?.let { folder ->
        DeleteConfirmDialog(
            title = "删除文件夹",
            message = "将删除文件夹「${folder.name}」及其所有子目录，其中的文档将移至根目录。是否继续？",
            onConfirm = {
                viewModel.deleteFolder(folder.id)
                deleteFolderTarget = null
            },
            onDismiss = { deleteFolderTarget = null },
        )
    }

    deleteDocTarget?.let { item ->
        DeleteConfirmDialog(
            title = "删除文档",
            message = "将删除《${item.title}》，物理文件一并删除。是否继续？",
            onConfirm = {
                viewModel.deleteDocument(item.id)
                deleteDocTarget = null
            },
            onDismiss = { deleteDocTarget = null },
        )
    }

    // 导入：选完文件弹目录选择（默认当前目录）
    pendingImportUris?.let {
        FolderPickerDialog(
            state = viewModel.folderTree,
            onConfirm = { target -> viewModel.confirmImportPick(target) },
            onDismiss = viewModel::cancelImportPick,
            onCreateSubFolder = viewModel::createFolderAndSelect,
            initialSelectedId = viewModel.currentFolderId,
        )
    }

    // 目录导入：选完目录弹目标位置选择（默认当前目录），结构镜像到该目录下
    pendingDirectoryUri?.let {
        FolderPickerDialog(
            state = viewModel.folderTree,
            onConfirm = { target -> viewModel.confirmDirectoryPick(target) },
            onDismiss = viewModel::cancelDirectoryPick,
            onCreateSubFolder = viewModel::createFolderAndSelect,
            initialSelectedId = viewModel.currentFolderId,
        )
    }

    // 导入汇总（T4/D5 起单文件多选与目录导入共用；成功/跳过/失败数量及原因）
    importSummary?.let { ui ->
        ImportSummaryDialog(
            summary = ui.summary,
            title = ui.title,
            fromDirectory = ui.fromDirectory,
            onDismiss = viewModel::dismissImportSummary,
        )
    }

    // 移动文档：任意目录（默认当前目录）
    moveDocTarget?.let { item ->
        FolderPickerDialog(
            state = viewModel.folderTree,
            onConfirm = { target ->
                viewModel.moveDocument(item.id, target)
                moveDocTarget = null
            },
            onDismiss = { moveDocTarget = null },
            onCreateSubFolder = viewModel::createFolderAndSelect,
            initialSelectedId = viewModel.currentFolderId,
        )
    }

    // 移动文件夹：先取自身+后代作禁选集（UI 前置防循环；业务层 moveFolder require 兜底）
    moveFolderTarget?.let { folder ->
        var excluded by remember(folder.id) { mutableStateOf<Set<Long>?>(null) }
        var subtreeLoadFailed by remember(folder.id) { mutableStateOf(false) }
        LaunchedEffect(folder.id) {
            excluded = try {
                viewModel.subtreeIds(folder.id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 读取失败不得静默无响应：保持 null 使 picker 不渲染，只弹失败提示
                // （若给空集，用户会看到选择器并可能选到非法目标，到业务层才报错）（M10 review H2）
                subtreeLoadFailed = true
                null
            }
        }
        if (subtreeLoadFailed) {
            AlertDialog(
                onDismissRequest = { moveFolderTarget = null },
                title = { Text(text = "无法移动") },
                text = { Text(text = "读取目录结构失败，请重试") },
                confirmButton = {
                    TextButton(onClick = { moveFolderTarget = null }) { Text(text = "知道了") }
                },
            )
        }
        excluded?.let { excludedIds ->
            FolderPickerDialog(
                state = viewModel.folderTree,
                onConfirm = { target ->
                    viewModel.moveFolder(folder.id, target)
                    moveFolderTarget = null
                },
                onDismiss = { moveFolderTarget = null },
                onCreateSubFolder = { parentId, name, onCreated ->
                    // 弹窗内新建的目录实时并入排除集（父在排除集内则新目录必为其后代），
                    // 避免「打开时快照」漏掉新建后代而绕过 UI 前置禁选
                    viewModel.createFolderAndSelect(parentId, name) { newId ->
                        if (parentId != null && parentId in excludedIds) {
                            excluded = excludedIds + newId
                        }
                        onCreated(newId)
                    }
                },
                excludedIds = excludedIds,
                initialSelectedId = null,
            )
        }
    }

    // 重复导入确认（逐个确认：导入循环挂起等待决策）
    pendingDuplicate?.let { (uri, existingTitle) ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDuplicate,
            title = { Text(text = "文档已存在") },
            text = { Text(text = "已存在《$existingTitle》，是否仍导入同一文件？") },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDuplicateImport) { Text(text = "仍导入") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDuplicate) { Text(text = "跳过") }
            },
        )
    }
}

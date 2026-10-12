package com.myreader.feature.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myreader.core.model.DocSort
import com.myreader.core.model.Folder
import com.myreader.core.model.LibraryItem
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 书架页（M5）：单层目录浏览（子文件夹卡片 + 文档网格）+ SAF 导入（先选目标目录）
 * + 文件夹/文档长按菜单（重命名/移动/删除/新建子目录）+ 目录树选择弹窗。
 * 点击文件夹 → onFolderClick 进子目录（library?folderId=xx）；点击文档 → onDocumentClick 进阅读器。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    onDocumentClick: (Long) -> Unit = {},
    onFolderClick: (Long?) -> Unit = {},
    onNavigateBack: () -> Unit = {},
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
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // 搜索框展开态（本地 UI 态；结果与否由 searchResults 驱动，空白关键词维持浏览内容）
    var searchActive by remember { mutableStateOf(false) }
    fun exitSearch() {
        searchActive = false
        viewModel.exitSearch()
    }

    // 系统返回手势：搜索态先收起搜索，不 pop 导航栈
    BackHandler(enabled = searchActive) { exitSearch() }

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
                            modifier = Modifier.fillMaxWidth(),
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
                            IconButton(onClick = { exitSearch() }) {
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
                        IconButton(onClick = { showNewFolderInCurrent = true }) {
                            Icon(imageVector = Icons.Filled.CreateNewFolder, contentDescription = "新建文件夹")
                        }
                        IconButton(
                            onClick = { importLauncher.launch(arrayOf("application/pdf")) },
                            enabled = !importing,
                        ) {
                            Icon(imageVector = Icons.Filled.Add, contentDescription = "导入 PDF")
                        }
                        IconButton(onClick = { searchActive = true }) {
                            Icon(imageVector = Icons.Filled.Search, contentDescription = "搜索")
                        }
                        SortMenu(current = currentSort, onSelect = viewModel::setSort)
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
                    CircularProgressIndicator()
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
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
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
                        text = if (viewModel.isRoot) "书架还是空的\n点右上角 + 导入 PDF" else "此文件夹为空",
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
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
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
                    Text(text = "正在导入…", style = MaterialTheme.typography.bodyMedium)
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
        LaunchedEffect(folder.id) { excluded = viewModel.subtreeIds(folder.id) }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderCard(
    folder: Folder,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    menuExpanded: Boolean,
    onDismissMenu: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onNewSubFolder: () -> Unit,
) {
    Box {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp),
                )
            }
            Text(
                text = folder.name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(8.dp),
            )
        }
        FolderMenu(
            expanded = menuExpanded,
            onDismiss = onDismissMenu,
            onRename = onRename,
            onMove = onMove,
            onDelete = onDelete,
            onNewSubFolder = onNewSubFolder,
        )
    }
}

@Composable
private fun FolderMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onNewSubFolder: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text(text = "重命名") }, onClick = { onDismiss(); onRename() })
        DropdownMenuItem(text = { Text(text = "移动") }, onClick = { onDismiss(); onMove() })
        DropdownMenuItem(text = { Text(text = "删除") }, onClick = { onDismiss(); onDelete() })
        DropdownMenuItem(text = { Text(text = "新建子目录") }, onClick = { onDismiss(); onNewSubFolder() })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocumentCard(
    item: LibraryItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    menuExpanded: Boolean,
    onDismissMenu: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    Box {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Box(modifier = Modifier.aspectRatio(1f)) {
                ThumbnailImage(relPath = item.thumbnailRelPath, modifier = Modifier.fillMaxSize())
                // 状态角标：加密 / 损坏
                when {
                    item.needsPassword -> StatusBadge(
                        icon = { Icon(Icons.Filled.Lock, contentDescription = "需要密码", tint = Color.White, modifier = Modifier.size(14.dp)) },
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                    item.pageCount == -2 -> StatusBadge(
                        icon = { Icon(Icons.Filled.Warning, contentDescription = "已损坏", tint = Color.White, modifier = Modifier.size(14.dp)) },
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                }
            }
            Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (item.pageCount == -2) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                )
                if (item.pageCount > 0) {
                    Text(
                        text = "${item.pageCount} 页",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = onDismissMenu) {
            DropdownMenuItem(text = { Text(text = "重命名") }, onClick = { onDismissMenu(); onRename() })
            DropdownMenuItem(text = { Text(text = "移动") }, onClick = { onDismissMenu(); onMove() })
            DropdownMenuItem(text = { Text(text = "删除") }, onClick = { onDismissMenu(); onDelete() })
        }
    }
}

@Composable
private fun StatusBadge(icon: @Composable () -> Unit, modifier: Modifier) {
    Box(
        modifier = modifier
            .padding(6.dp)
            .size(24.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) { icon() }
}

@Composable
private fun ThumbnailImage(relPath: String?, modifier: Modifier = Modifier) {
    if (relPath == null) {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(36.dp),
            )
        }
        return
    }
    val context = LocalContext.current
    // 异步解码：避免组合期主线程做磁盘 IO + PNG 解码（网格滚动卡顿）
    var bitmap by remember(relPath) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(relPath) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(File(context.filesDir, relPath).absolutePath) }.getOrNull()
        }
    }
    val decoded = bitmap
    if (decoded != null) {
        androidx.compose.foundation.Image(
            bitmap = decoded.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

/**
 * 排序菜单（M6）：顶栏三点溢出，4 个选项当前项勾选，选中即写偏好并即时生效。
 * 搜索结果固定按标题排序（DocumentDao.search），故搜索态不展示此菜单。
 * internal：供 Compose UI 测试直接渲染（无 Room/VM 依赖）。
 */
@Composable
internal fun SortMenu(current: DocSort, onSelect: (DocSort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(imageVector = Icons.Filled.MoreVert, contentDescription = "排序")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DocSort.entries.forEach { sort ->
                DropdownMenuItem(
                    text = { Text(text = sortLabel(sort)) },
                    trailingIcon = {
                        if (sort == current) {
                            Icon(imageVector = Icons.Filled.Check, contentDescription = "当前排序")
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(sort)
                    },
                )
            }
        }
    }
}

private fun sortLabel(sort: DocSort): String = when (sort) {
    DocSort.TITLE -> "标题"
    DocSort.LAST_READ -> "最近阅读"
    DocSort.ADDED -> "添加时间"
    DocSort.SIZE -> "大小"
}

/** 最近阅读条（M6）：根书架网格首部横向滑动卡片，点击直达阅读器。internal 供 Compose UI 测试 */
@Composable
internal fun RecentSection(recent: List<LibraryItem>, onDocumentClick: (Long) -> Unit) {
    val listState = rememberLazyListState()
    // 阅读返回后最近条按 lastReadAt 重排（刚读的文档升到首位）：内容在保留的横向偏移下原地换卡，
    // 视觉上像卡片跳变（M6 验收用户反馈）——首项变化时把横向滚动复位到最新项，跳变变更有意的展示
    LaunchedEffect(recent.firstOrNull()?.id) {
        if (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0) {
            listState.scrollToItem(0)
        }
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "最近阅读",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            rowItems(recent, key = { "r_${it.id}" }) { item ->
                RecentCard(item = item, onClick = { onDocumentClick(item.id) })
            }
        }
    }
}

@Composable
internal fun RecentCard(item: LibraryItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier.width(100.dp).clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(0.75f)) {
            ThumbnailImage(relPath = item.thumbnailRelPath, modifier = Modifier.fillMaxSize())
            // 加密角标常驻（needsPassword 不再清除，见 ReaderViewModel.backfillAfterOpen）
            if (item.needsPassword) {
                StatusBadge(
                    icon = { Icon(Icons.Filled.Lock, contentDescription = "需要密码", tint = Color.White, modifier = Modifier.size(14.dp)) },
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
        }
        Column(modifier = Modifier.fillMaxWidth().padding(6.dp)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 页码进度仅对页码式文档有意义（cfi 流式无页概念，M9 前不会有）
            if (item.progressType == "page") {
                Text(
                    text = "第 ${item.currentPage + 1} 页",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

package com.myreader.feature.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myreader.core.model.LibraryItem
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 书架页（M3）：文档网格展示 + SAF 导入 + 重复确认 + 结果提示。
 * 点击条目暂无动作（阅读器 M4 接入）；目录树 M5 引入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(viewModel: LibraryViewModel = hiltViewModel()) {
    val documents by viewModel.documents.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val pendingDuplicate by viewModel.pendingDuplicate.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.importUris(uris)
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message -> snackbarHostState.showSnackbar(message) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "MyReader 书架") },
                actions = {
                    IconButton(onClick = { importLauncher.launch(arrayOf("application/pdf")) }, enabled = !importing) {
                        Icon(imageVector = Icons.Filled.Add, contentDescription = "导入 PDF")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (documents.isEmpty()) {
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
                        text = "书架还是空的\n点右上角 + 导入 PDF",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 108.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(documents, key = { it.id }) { item ->
                        DocumentCard(item = item)
                    }
                }
            }

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

    // 重复导入确认
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

@Composable
private fun DocumentCard(item: LibraryItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
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

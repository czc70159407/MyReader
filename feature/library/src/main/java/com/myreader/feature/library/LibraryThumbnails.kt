package com.myreader.feature.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.myreader.core.document.FileTypes
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 书架卡片共用视觉件（自 LibraryScreen.kt 拆出，工程优化 2026-10-16）：
 * 缩略图异步解码 / 状态角标（加密·损坏）/ 格式角标（EPUB·CBZ·XPS·TXT）/ 占位图标。
 */

@Composable
internal fun StatusBadge(icon: @Composable () -> Unit, modifier: Modifier) {
    Box(
        modifier = modifier
            .padding(6.dp)
            .size(24.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) { icon() }
}

/** M9：格式文字角标（左上角，标识 EPUB / CBZ / XPS） */
@Composable
internal fun FormatBadge(label: String, modifier: Modifier) {
    Box(
        modifier = modifier
            .padding(6.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

/** fileType → 展示用格式标签 */
internal fun formatLabel(fileType: String): String = when (fileType) {
    FileTypes.EPUB -> "EPUB"
    FileTypes.CBZ -> "CBZ"
    FileTypes.XPS -> "XPS"
    FileTypes.TXT -> "TXT"
    else -> "PDF"
}

/** fileType → 无缩略图时的占位图标 */
internal fun formatIcon(fileType: String): ImageVector = when (fileType) {
    FileTypes.EPUB -> Icons.Filled.Book
    FileTypes.CBZ -> Icons.Filled.Image
    FileTypes.TXT -> Icons.AutoMirrored.Filled.Article
    else -> Icons.Filled.Description   // pdf / xps / 未知
}

@Composable
internal fun ThumbnailImage(relPath: String?, fileType: String, modifier: Modifier = Modifier) {
    if (relPath == null) {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = formatIcon(fileType),
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
        // 全限定：Icons.Filled.Image 与 foundation.Image 同名（原 LibraryScreen 同款处理）
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

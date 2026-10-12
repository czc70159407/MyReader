package com.myreader

import android.graphics.Bitmap
import android.graphics.PointF
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.artifex.mupdf.viewer.MuPDFCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MupdfDemoScreen()
                }
            }
        }
    }
}

/**
 * M1 临时验收脚手架：验证 MuPDF 打开 assets/test.pdf（countPages + 首页渲染）。
 * M4 阅读器落地（feature:viewer-mupdf 经 core:document 接口）后删除本 Composable，
 * 恢复标准入口。MuPDF 具体类出现在 :app 是本脚手架对「MuPDF 边界」的唯一临时豁免。
 */
@Composable
private fun MupdfDemoScreen() {
    val context = LocalContext.current
    var pageCount by remember { mutableStateOf<Int?>(null) }
    var firstPage by remember { mutableStateOf<ImageBitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = context.assets.open("test.pdf").use { it.readBytes() }
                val core = MuPDFCore(bytes, "pdf")
                val count = core.countPages()
                val size: PointF = core.getPageSize(0)
                val width = 540
                val height = (width * size.y / size.x).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                core.drawPage(bitmap, 0, width, height, 0, 0, width, height, null)
                core.onDestroy()
                count to bitmap.asImageBitmap()
            }.onSuccess { (count, image) ->
                pageCount = count
                firstPage = image
            }.onFailure { e ->
                error = e.message ?: e.javaClass.simpleName
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("MyReader M1 验收 Demo", style = MaterialTheme.typography.titleLarge)
        when {
            error != null -> Text("打开失败：$error", color = MaterialTheme.colorScheme.error)
            pageCount != null -> Text("countPages() = $pageCount（预期 3）")
            else -> Text("正在打开 assets/test.pdf ...")
        }
        firstPage?.let { image ->
            Image(
                bitmap = image,
                contentDescription = "test.pdf 首页渲染",
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

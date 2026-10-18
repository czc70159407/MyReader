package com.myreader.feature.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.myreader.core.document.OutlineEntry
import kotlinx.coroutines.CancellationException

/** 展平后的大纲行（目录树扁平渲染模式，按 depth 缩进，LazyColumn 复用） */
private data class FlatOutline(val title: String, val pageIndex: Int, val depth: Int)

private fun List<OutlineEntry>.flatten(depth: Int, out: MutableList<FlatOutline>) {
    for (entry in this) {
        out += FlatOutline(entry.title, entry.pageIndex, depth)
        entry.children.flatten(depth + 1, out)
    }
}

/**
 * 大纲导航（ModalBottomSheet）：打开时懒加载文档目录，展平缩进列表，点击跳页。
 * load 挂起函数由调用方注入（ViewModel.loadOutline，内部已切 IO）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutlineSheet(
    load: suspend () -> List<OutlineEntry>,
    onDismiss: () -> Unit,
    onJump: (Int) -> Unit,
) {
    // remember + LaunchedEffect 懒加载（不用 produceState：Compose lint 对其赋值形态有误报，见项目经验）
    var entries by remember { mutableStateOf<List<FlatOutline>?>(null) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            val flat = mutableListOf<FlatOutline>()
            load().flatten(0, flat)
            entries = flat
        } catch (e: CancellationException) {
            // 快速关闭 sheet 导致取消不是加载失败：传播取消，不显示「目录加载失败」（M10 review H4）
            throw e
        } catch (_: Exception) {
            failed = true
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        when {
            failed -> Text(
                text = "目录加载失败",
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                color = MaterialTheme.colorScheme.error,
            )
            entries == null -> Row(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp), strokeWidth = 2.dp)
                Text(text = "正在加载目录…")
            }
            entries!!.isEmpty() -> Text(
                text = "本文档没有目录",
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                color = MaterialTheme.colorScheme.outline,
            )
            else -> LazyColumn(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                // key 用列表序号：PDF 大纲存在同名同页重复条目，内容拼 key 会触发重复 key 崩溃
                itemsIndexed(entries!!, key = { index, _ -> index }) { _, node ->
                    Text(
                        text = node.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onJump(node.pageIndex)
                                onDismiss()
                            }
                            .padding(start = (16 + node.depth * 20).dp, top = 12.dp, end = 16.dp, bottom = 12.dp),
                    )
                }
            }
        }
    }
}

package com.myreader.feature.library

import android.net.Uri
import com.myreader.core.document.FileTypes

/**
 * 目录扫描规模上限（防 ANR / OOM / 磁盘耗尽）：超上限条目计入汇总而非中断整体导入。
 */
data class DirectoryScanLimits(
    val maxFiles: Int = 500,
    val maxDepth: Int = 8,
    val maxFileBytes: Long = 200L * 1024 * 1024,
)

/** 待导入文件：uri + 相对根目录的目录段（用于镜像目录结构）。 */
data class ScannedFile(
    val uri: Uri,
    val displayName: String?,
    val size: Long?,
    val folderPath: List<String>,
)

/** 扫描结果：可导入文件清单 + 各类跳过/截断计数（汇总反馈的数据源）。 */
data class DirectoryScanResult(
    val files: List<ScannedFile>,
    val unsupportedCount: Int,
    val tooLargeCount: Int,
    val deepSkippedCount: Int,
    val truncatedCount: Int,
    val inaccessibleDirCount: Int,
)

/**
 * 目录段规范化：trim、去控制字符（复用 T7 名称规则）、限长、空名回退。
 * 目录导入无人值守 → 超长**截断**；交互式弹窗则报 `inline_name_too_long` 并禁用提交（`NameRules`）。
 */
internal fun sanitizeFolderName(raw: String?): String {
    val cleaned = filterNameInput(raw.orEmpty()).trim()
    if (cleaned.isEmpty()) return "未命名目录"
    return if (cleaned.length > MAX_NAME_LEN) cleaned.take(MAX_NAME_LEN) else cleaned
}

/**
 * 目录遍历（纯逻辑，JVM 可测）：BFS 非递归防深目录栈溢出；
 * 格式过滤统一走 `FileTypes.normalize`（与单文件导入同一契约）。
 */
object DirectoryScanner {

    fun scan(
        source: DirectoryTreeSource,
        limits: DirectoryScanLimits = DirectoryScanLimits(),
    ): DirectoryScanResult {
        val files = mutableListOf<ScannedFile>()
        var unsupported = 0
        var tooLarge = 0
        var deepSkipped = 0
        var truncated = 0
        var inaccessible = 0

        // 队列元素 = (docId, 相对根目录的目录段)
        val queue = ArrayDeque<Pair<String, List<String>>>()
        queue += source.rootDocId to emptyList()
        while (queue.isNotEmpty()) {
            val (docId, path) = queue.removeFirst()
            val children = source.children(docId)
            if (children == null) {
                if (path.isNotEmpty()) inaccessible++
                continue
            }
            for (child in children) {
                if (child.isDirectory) {
                    if (path.size >= limits.maxDepth) { deepSkipped++; continue }
                    queue += child.docId to (path + sanitizeFolderName(child.displayName))
                    continue
                }
                // 格式未在 FileTypes 登记 → 跳过（不复制、不入库）
                if (FileTypes.normalize(child.displayName, child.mimeType) == null) { unsupported++; continue }
                if (files.size >= limits.maxFiles) { truncated++; continue }
                if (child.size != null && child.size > limits.maxFileBytes) { tooLarge++; continue }
                files += ScannedFile(source.documentUri(child.docId), child.displayName, child.size, path)
            }
        }
        return DirectoryScanResult(files, unsupported, tooLarge, deepSkipped, truncated, inaccessible)
    }
}

package com.myreader.feature.library

import android.content.Context
import android.net.Uri
import com.myreader.core.common.DispatcherProvider
import com.myreader.core.data.repo.LibraryRepository
import com.myreader.core.model.DocSort
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** 目录导入进度（UI 展示「正在扫描目录…」/「正在导入 3/42…」）。 */
data class DirectoryImportProgress(val current: Int, val total: Int, val phase: Phase) {
    enum class Phase { SCANNING, IMPORTING }
}

/** 目录导入汇总（汇总对话框展示成功/跳过/失败数量与逐条原因）。 */
data class DirectoryImportSummary(
    val total: Int,
    val imported: Int = 0,
    val passwordMarked: Int = 0,
    val duplicateSkipped: Int = 0,
    val corruptRejected: Int = 0,
    val unsupported: Int = 0,
    val tooLarge: Int = 0,
    val failed: Int = 0,
    val foldersCreated: Int = 0,
    val foldersReused: Int = 0,
    val renamedForConflict: Int = 0,
    val deepSkipped: Int = 0,
    val truncated: Int = 0,
    val inaccessibleDirs: Int = 0,
    val issues: List<Issue> = emptyList(),
) {
    data class Issue(val path: String, val reason: String)

    companion object {
        /** 明细展示上限：防海量列表拖慢 UI（超出只计总数） */
        const val MAX_ISSUES = 50
    }
}

/**
 * 目录导入编排：扫描 → 镜像目录（同名复用）→ 逐文件走既有 [DocumentImporter] → 汇总。
 *
 * 与单文件导入共用验证/查重/缩略图/入库链路，行为一致；批量场景的差异只在：
 * - 内容重复（同 contentHash）默认跳过并计入汇总，**不弹逐个确认窗**（批量不可交互）；
 * - 同目录同名文档（不同内容）自动 `name (k)` 递增重命名，不丢文件。
 */
@Singleton
class DirectoryImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: LibraryRepository,
    private val importer: DocumentImporter,
    private val dispatchers: DispatcherProvider,
) {

    /** 入口：SAF treeUri → 构造 SAF 遍历源 → 导入。权限异常由 [SafDirectoryTreeSource] 抛出。 */
    suspend fun importDirectory(
        treeUri: Uri,
        targetFolderId: Long?,
        limits: DirectoryScanLimits = DirectoryScanLimits(),
        onProgress: (DirectoryImportProgress) -> Unit = {},
    ): DirectoryImportSummary = importTree(
        source = SafDirectoryTreeSource(context.contentResolver, treeUri),
        targetFolderId = targetFolderId,
        limits = limits,
        onProgress = onProgress,
    )

    /**
     * 导入编排（遍历源可注入：生产走 SAF，单测定注入内存源——Robolectric 无法伪造
     * DocumentsContract 的 child documents 查询）。
     */
    internal suspend fun importTree(
        source: DirectoryTreeSource,
        targetFolderId: Long?,
        limits: DirectoryScanLimits = DirectoryScanLimits(),
        onProgress: (DirectoryImportProgress) -> Unit = {},
    ): DirectoryImportSummary = withContext(dispatchers.io) {
        onProgress(DirectoryImportProgress(0, 0, DirectoryImportProgress.Phase.SCANNING))
        val scan = DirectoryScanner.scan(source, limits)

        val folderCache = FolderCache(repository)
        val titleCache = TitleCache(repository)
        val issues = mutableListOf<DirectoryImportSummary.Issue>()
        var imported = 0
        var passwordMarked = 0
        var duplicateSkipped = 0
        var corruptRejected = 0
        var unsupported = 0
        var failed = 0

        scan.files.forEachIndexed { index, file ->
            // 目录在导入前即创建（import 需要 folderId）；若该目录下文件全部失败，
            // 会在结束时由 dropEmptyCreated 清理，保证「不留下空目录」的设计承诺
            onProgress(
                DirectoryImportProgress(index + 1, scan.files.size, DirectoryImportProgress.Phase.IMPORTING),
            )
            val folderId = folderCache.resolve(file.folderPath, targetFolderId)
            val result = importer.import(file.uri, targetFolderId = folderId)
            val name = (file.folderPath + (file.displayName ?: "未命名")).joinToString("/")
            when (result.status) {
                DocumentImporter.Status.IMPORTED,
                DocumentImporter.Status.IMPORTED_PASSWORD_MARKED -> {
                    val id = result.documentId
                    if (id != null) {
                        titleCache.ensureUnique(
                            folderId,
                            id,
                            result.title ?: file.displayName ?: "未命名文档",
                        )
                    }
                    if (result.status == DocumentImporter.Status.IMPORTED) imported++ else passwordMarked++
                }
                DocumentImporter.Status.DUPLICATE_SKIPPED -> {
                    duplicateSkipped++
                    addIssue(issues, name, "库中已存在相同内容，已跳过")
                }
                DocumentImporter.Status.CORRUPT_REJECTED -> {
                    corruptRejected++
                    addIssue(issues, name, result.reason ?: "文件损坏")
                }
                DocumentImporter.Status.UNSUPPORTED -> {
                    unsupported++
                    addIssue(issues, name, result.reason ?: "暂不支持的文件类型")
                }
                DocumentImporter.Status.FAILED -> {
                    failed++
                    addIssue(issues, name, result.reason ?: "导入失败")
                }
            }
        }

        if (scan.files.isEmpty() && scan.unsupportedCount > 0) {
            addIssue(issues, "目录中没有可导入的文档", "${scan.unsupportedCount} 个文件类型暂不支持")
        }

        // 目录内文件全部失败 → 不留空目录（与设计「只镜像含可导入文件的路径」一致）
        folderCache.dropEmptyCreated()

        DirectoryImportSummary(
            total = scan.files.size,
            imported = imported,
            passwordMarked = passwordMarked,
            duplicateSkipped = duplicateSkipped,
            corruptRejected = corruptRejected,
            unsupported = unsupported + scan.unsupportedCount,
            tooLarge = scan.tooLargeCount,
            failed = failed,
            foldersCreated = folderCache.created - folderCache.dropped,
            foldersReused = folderCache.reused,
            renamedForConflict = titleCache.renamed,
            deepSkipped = scan.deepSkippedCount,
            truncated = scan.truncatedCount,
            inaccessibleDirs = scan.inaccessibleDirCount,
            issues = issues,
        )
    }

    private fun addIssue(
        issues: MutableList<DirectoryImportSummary.Issue>,
        path: String,
        reason: String,
    ) {
        if (issues.size < DirectoryImportSummary.MAX_ISSUES) {
            issues += DirectoryImportSummary.Issue(path, reason)
        }
    }

    /** 目录镜像缓存：同名目录复用（合并），避免重复导入产生「副本」目录 */
    private class FolderCache(private val repository: LibraryRepository) {
        var created = 0
            private set
        var reused = 0
            private set
        /** 因目录内文件全部失败而清理掉的新建目录数（汇总里从 created 扣减） */
        var dropped = 0
            private set
        private val cache = HashMap<Long?, MutableMap<String, Long>>()
        private val createdIds = mutableListOf<Long>()

        suspend fun resolve(path: List<String>, rootParentId: Long?): Long? {
            var parent = rootParentId
            for (segment in path) {
                val map = cache.getOrPut(parent) { load(parent) }
                val existing = map[segment]
                if (existing != null) {
                    reused++
                    parent = existing
                    continue
                }
                val id = repository.createFolder(parent, segment)
                map[segment] = id
                cache[id] = mutableMapOf()
                createdIds += id
                created++
                parent = id
            }
            return parent
        }

        /**
         * 清理本次新建但**未导入进任何文档**的空目录（其内文件全部损坏/失败时才会出现）。
         * 倒序（后创建的深层先处理）避免父先删导致子悬空。
         */
        suspend fun dropEmptyCreated() {
            for (id in createdIds.asReversed()) {
                val hasDocs = repository.docsIn(id, DocSort.TITLE).first().isNotEmpty()
                val hasSubs = repository.foldersIn(id).first().isNotEmpty()
                if (!hasDocs && !hasSubs) {
                    repository.deleteFolder(id)
                    dropped++
                }
            }
        }

        private suspend fun load(parentId: Long?): MutableMap<String, Long> =
            repository.foldersIn(parentId).first().associateTo(mutableMapOf()) { it.name to it.id }
    }

    /**
     * 同目录文档重名 → 自动 `name (k)` 递增重命名（不丢文件）。
     * 注意两点（均由单测抓出后修正）：
     * 1. ensureUnique 在文档**入库后**调用，DB 已含其自身标题——须排除自身 id 再判冲突；
     * 2. 同目录已存在同名文档时 title→单一 id 的映射会键碰撞（把他人覆盖成自己），
     *    故缓存按 title→id 集合记录，任一**他人** id 占用即冲突。
     */
    private class TitleCache(private val repository: LibraryRepository) {
        var renamed = 0
            private set
        private val cache = HashMap<Long?, MutableMap<String, MutableSet<Long>>>()

        suspend fun ensureUnique(folderId: Long?, docId: Long, title: String) {
            val map = cache.getOrPut(folderId) { load(folderId) }
            val ids = map.getOrPut(title) { mutableSetOf() }
            if (ids.none { it != docId }) {
                ids += docId
                return
            }
            val dot = title.lastIndexOf('.')
            val base = if (dot > 0) title.substring(0, dot) else title
            val ext = if (dot > 0) title.substring(dot) else ""
            var k = 1
            var candidate: String
            do {
                candidate = "$base ($k)$ext"
                k++
            } while (map.containsKey(candidate) && k < 1000)
            repository.renameDocument(docId, candidate)
            map.getOrPut(candidate) { mutableSetOf() } += docId
            renamed++
        }

        private suspend fun load(folderId: Long?): MutableMap<String, MutableSet<Long>> {
            val map = HashMap<String, MutableSet<Long>>()
            repository.docsIn(folderId, DocSort.TITLE).first().forEach { item ->
                map.getOrPut(item.title) { mutableSetOf() } += item.id
            }
            return map
        }
    }
}

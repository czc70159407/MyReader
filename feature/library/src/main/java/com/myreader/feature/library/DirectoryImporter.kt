package com.myreader.feature.library

import android.content.Context
import android.net.Uri
import com.myreader.core.common.DispatcherProvider
import com.myreader.core.data.repo.LibraryRepository
import com.myreader.core.model.DocSort
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
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
    /** T3/D3：取消产生的部分汇总（对话框首行展示「已取消，已导入 N 个」） */
    val cancelled: Boolean = false,
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

    /** 入口：SAF treeUri → 构造 SAF 遍历源 → 导入。权限异常由 [SafDirectoryTreeSource] 抛出。
     *  取消（T3/D3）：[onPartialSummary] 在协程被取消时收到部分汇总，随后 CancellationException 继续传播。 */
    suspend fun importDirectory(
        treeUri: Uri,
        targetFolderId: Long?,
        limits: DirectoryScanLimits = DirectoryScanLimits(),
        onProgress: (DirectoryImportProgress) -> Unit = {},
        onPartialSummary: (DirectoryImportSummary) -> Unit = {},
    ): DirectoryImportSummary = importTree(
        source = SafDirectoryTreeSource(context.contentResolver, treeUri),
        targetFolderId = targetFolderId,
        limits = limits,
        onProgress = onProgress,
        onPartialSummary = onPartialSummary,
    )

    /**
     * 导入编排（遍历源可注入：生产走 SAF，单测定注入内存源——Robolectric 无法伪造
     * DocumentsContract 的 child documents 查询）。
     *
     * 取消（T3/D3）：job.cancel() 后由本函数在扫描完成后与每个文件前 `ensureActive` 检查——
     * - 扫描阶段取消（扫描为同步 SAF 查询，最长约 1~2s 后生效）：不创建目录、不入库；
     * - 导入阶段取消：保留已导入文档与已创建目录（不做空目录清理），部分汇总经
     *   [onPartialSummary] 上交后 `CancellationException` 继续传播——不得吞掉转为错误文案。
     */
    internal suspend fun importTree(
        source: DirectoryTreeSource,
        targetFolderId: Long?,
        limits: DirectoryScanLimits = DirectoryScanLimits(),
        onProgress: (DirectoryImportProgress) -> Unit = {},
        onPartialSummary: (DirectoryImportSummary) -> Unit = {},
    ): DirectoryImportSummary = withContext(dispatchers.io) {
        onProgress(DirectoryImportProgress(0, 0, DirectoryImportProgress.Phase.SCANNING))
        val session = ImportSession(repository, importer, DirectoryScanner.scan(source, limits), targetFolderId)
        try {
            // 扫描期取消在此抛出（session 尚未执行任何导入 → 部分汇总 imported = 0）
            coroutineContext.ensureActive()
            session.runAll(onProgress, ensureActive = { coroutineContext.ensureActive() })
            session.dropEmptyCreated()
            session.summary(cancelled = false, includeDropped = true)
        } catch (e: CancellationException) {
            onPartialSummary(session.summary(cancelled = true, includeDropped = false))
            throw e
        }
    }

    /**
     * 单次目录导入的会话状态（目录镜像缓存 / 标题查重缓存 / 计数与明细）。
     * 独立成类以隔离编排复杂度：[DirectoryImporter.importTree] 只负责进度、取消检查与汇总收尾。
     */
    private class ImportSession(
        repository: LibraryRepository,
        private val importer: DocumentImporter,
        val scan: DirectoryScanResult,
        private val targetFolderId: Long?,
    ) {
        private val folderCache = FolderCache(repository)
        private val titleCache = TitleCache(repository)
        private val issues = mutableListOf<DirectoryImportSummary.Issue>()
        private var imported = 0
        private var passwordMarked = 0
        private var duplicateSkipped = 0
        private var corruptRejected = 0
        private var unsupported = 0
        private var failed = 0

        suspend fun runAll(
            onProgress: (DirectoryImportProgress) -> Unit,
            ensureActive: () -> Unit,
        ) {
            scan.files.forEachIndexed { index, file ->
                // T3/D3：每个文件前显式检查取消（import 内部挂起点也会抛，双保险及时性）
                ensureActive()
                // 目录在导入前即创建（import 需要 folderId）；若该目录下文件全部失败，
                // 会在结束时由 dropEmptyCreated 清理，保证「不留下空目录」的设计承诺
                onProgress(
                    DirectoryImportProgress(index + 1, scan.files.size, DirectoryImportProgress.Phase.IMPORTING),
                )
                val folderId = folderCache.resolve(file.folderPath, targetFolderId)
                apply(importer.import(file.uri, targetFolderId = folderId), file, folderId)
            }
            if (scan.files.isEmpty() && scan.unsupportedCount > 0) {
                issues += DirectoryImportSummary.Issue(
                    "目录中没有可导入的文档",
                    "${scan.unsupportedCount} 个文件类型暂不支持",
                )
            }
        }

        private suspend fun apply(result: DocumentImporter.Result, file: ScannedFile, folderId: Long?) {
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
                    addIssue(name, "库中已存在相同内容，已跳过")
                }
                DocumentImporter.Status.CORRUPT_REJECTED -> {
                    corruptRejected++
                    addIssue(name, result.reason ?: "文件损坏")
                }
                DocumentImporter.Status.UNSUPPORTED -> {
                    unsupported++
                    addIssue(name, result.reason ?: "暂不支持的文件类型")
                }
                DocumentImporter.Status.FAILED -> {
                    failed++
                    addIssue(name, result.reason ?: "导入失败")
                }
            }
        }

        private fun addIssue(path: String, reason: String) {
            if (issues.size < DirectoryImportSummary.MAX_ISSUES) {
                issues += DirectoryImportSummary.Issue(path, reason)
            }
        }

        suspend fun dropEmptyCreated() = folderCache.dropEmptyCreated()

        /** [includeDropped] 决定是否扣减空目录清理（取消路径不清理，保留现场） */
        fun summary(cancelled: Boolean, includeDropped: Boolean): DirectoryImportSummary =
            DirectoryImportSummary(
                total = scan.files.size,
                imported = imported,
                passwordMarked = passwordMarked,
                duplicateSkipped = duplicateSkipped,
                corruptRejected = corruptRejected,
                unsupported = unsupported + scan.unsupportedCount,
                tooLarge = scan.tooLargeCount,
                failed = failed,
                foldersCreated = folderCache.created - if (includeDropped) folderCache.dropped else 0,
                foldersReused = folderCache.reused,
                renamedForConflict = titleCache.renamed,
                deepSkipped = scan.deepSkippedCount,
                truncated = scan.truncatedCount,
                inaccessibleDirs = scan.inaccessibleDirCount,
                issues = issues,
                cancelled = cancelled,
            )
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

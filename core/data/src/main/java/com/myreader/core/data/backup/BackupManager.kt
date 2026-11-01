package com.myreader.core.data.backup

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.myreader.core.common.DispatcherProvider
import com.myreader.core.data.db.MyReaderDatabase
import com.myreader.core.data.db.dao.AnnotationDao
import com.myreader.core.data.db.dao.BookmarkDao
import com.myreader.core.data.db.dao.DocumentDao
import com.myreader.core.data.db.dao.FolderDao
import com.myreader.core.storage.DocumentStorage
import javax.inject.Inject
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.max

/** 导出清单写入阶段的进度占比。 */
private const val MANIFEST_PROGRESS = 0.95f

/** RestoreLimits 默认值（具名化，避免裸魔数）。 */
private const val MAX_ENTRIES = 512
private const val MAX_ENTRY_BYTES = 512L * 1024 * 1024
private const val MAX_TOTAL_BYTES = 1L shl 30
private const val MAX_JSON_CHARS = 4 * 1024 * 1024
private const val MAX_RATIO = 100L
private const val RATIO_FLOOR_BYTES = 8L * 1024 * 1024

/**
 * 备份/恢复编排（M8）。纯逻辑，注入 storage + dao + dispatchers，UI 仅负责提供 SAF 流，
 * 因此完全可在 JVM（Robolectric + inMemory Room + TemporaryFolder）下单测。
 *
 * 导出：读全量 folders/documents → 流式写 zip（files/ 物理文档 + thumbs/ 缩略图 + library.json）。
 * 恢复：先整体解压到临时目录（entry name 严格白名单，防 zip slip）→ 校验 library.json（版本/路径）
 *       → 空间预检 → 事务内按拓扑序 upsert 目录、再 upsert 文档 → 提交后把临时文件 move 进 library/thumb；
 *       失败（预检/解压/校验/事务异常）由调用方清理临时目录，DB 因事务原子性保持未变。
 */
@Suppress("LongParameterList")
class BackupManager @Inject constructor(
    private val db: MyReaderDatabase,
    private val folderDao: FolderDao,
    private val documentDao: DocumentDao,
    private val bookmarkDao: BookmarkDao,
    private val annotationDao: AnnotationDao,
    private val storage: DocumentStorage,
    private val dispatchers: DispatcherProvider,
) {

    /** 恢复期资源上限（防 zip bomb / 磁盘耗尽）；测试按需收窄以覆盖各闸门。 */
    internal var limits: RestoreLimits = RestoreLimits()

    data class ExportResult(
        val folders: Int,
        val documents: Int,
        val filesWritten: Int,
        val thumbnailsWritten: Int,
        val missingFiles: Int,
        /** M10：随包导出的书签数 */
        val bookmarks: Int,
        /** M12：随包导出的高亮/笔记数 */
        val annotations: Int,
    )

    data class RestoreResult(
        val folders: Int,
        val documents: Int,
        val filesWritten: Int,
        val thumbnailsWritten: Int,
        val missingFiles: Int,
        val bytesWritten: Long,
        /** M10：随包恢复的书签数（v1 旧包为 0） */
        val bookmarks: Int,
        /** M12：随包恢复的高亮/笔记数（v1~v3 旧包为 0） */
        val annotations: Int,
    )

    /** 整段 relPath 白名单（与 BackupCodec / DocumentStorage 同源）。 */
    private val relPathPattern = Regex("^[0-9]+\\.[a-zA-Z0-9]{1,10}$")

    suspend fun export(
        output: OutputStream,
        appVersion: String,
        onProgress: Progress = { _, _ -> },
    ): ExportResult = withContext(dispatchers.io) {
        val folders = folderDao.all()
        val documents = documentDao.all()
        val bookmarks = bookmarkDao.all()
        val annotations = annotationDao.all()
        val manifest = BackupCodec.BackupManifest(
            schemaVersion = BackupCodec.SCHEMA_VERSION,
            exportedAt = System.currentTimeMillis(),
            appVersion = appVersion,
            folders = folders.map { it.toDto() },
            documents = documents.map { it.toDto() },
            bookmarks = bookmarks.map { it.toDto() },
            annotations = annotations.map { it.toDto() },
        )

        var filesWritten = 0
        var thumbnailsWritten = 0
        var missingFiles = 0

        ZipOutputStream(output).use { zip ->
            val total = documents.size
            documents.forEachIndexed { i, doc ->
                onProgress(i.toFloat() / max(1, total), "导出文档 ${i + 1}/$total")
                storage.openRead(doc.relPath)?.use { inp ->
                    zip.putNextEntry(ZipEntry("files/${doc.relPath}"))
                    inp.copyTo(zip)
                    zip.closeEntry()
                    filesWritten++
                } ?: run { missingFiles++ }

                doc.thumbnailRelPath?.let { trp ->
                    storage.openThumbnailRead(trp)?.use { inp ->
                        val id = trp.substringAfterLast('/').substringBefore('.')
                        zip.putNextEntry(ZipEntry("thumbs/$id.png"))
                        inp.copyTo(zip)
                        zip.closeEntry()
                        thumbnailsWritten++
                    }
                }
            }
            onProgress(MANIFEST_PROGRESS, "写入备份清单")
            zip.putNextEntry(ZipEntry("library.json"))
            zip.write(BackupCodec.encode(manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        onProgress(1f, "备份完成")
        ExportResult(
            folders = folders.size,
            documents = documents.size,
            filesWritten = filesWritten,
            thumbnailsWritten = thumbnailsWritten,
            missingFiles = missingFiles,
            bookmarks = bookmarks.size,
            annotations = annotations.size,
        )
    }

    suspend fun restore(
        input: InputStream,
        onProgress: Progress = { _, _ -> },
    ): RestoreResult = withContext(dispatchers.io) {
        val tmp = storage.newBackupTempDir()
        try {
            // 1) 解压到临时目录（entry name 严格白名单；zip bomb / 磁盘耗尽上限见 extractToTemp）
            val extracted = extractToTemp(input, tmp)
            require(extracted.json != null) { "备份缺少 library.json" }
            val manifest = BackupCodec.decode(extracted.json)
            // 2) 空间预检（临时目录与 library/ 同分区，恢复后净占用≈解压出的 files 总大小）
            val needed = tmp.walkTopDown()
                .filter { it.isFile && it.parentFile?.name == "files" }
                .sumOf { it.length() }
            check(storage.hasSpaceFor(needed)) {
                "存储空间不足，无法恢复备份（需约 $needed 字节）"
            }
            // 3) 事务写 DB：目录按拓扑序（父先于子）upsert，文档后插（满足 folderId 外键）
            writeManifest(manifest)
            // 4) 提交成功后再把临时物理文件 move 进 library/thumb（同分区瞬时）
            val moved = moveIntoLibrary(
                tmp = tmp,
                fileEntries = extracted.files,
                thumbEntries = extracted.thumbs,
                manifestRelPaths = manifest.documents.map { it.relPath }.toSet(),
                onProgress = onProgress,
            )
            RestoreResult(
                folders = manifest.folders.size,
                documents = manifest.documents.size,
                filesWritten = moved.files,
                thumbnailsWritten = moved.thumbs,
                missingFiles = (extracted.files.size - moved.files) +
                    (extracted.thumbs.size - moved.thumbs) + moved.orphans,
                bytesWritten = moved.bytes,
                bookmarks = manifest.bookmarks.size,
                annotations = manifest.annotations.size,
            )
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** 解压结果：临时目录已就绪，files/thumbs 为条目列表，json 为清单原文。 */
    private class Extracted(
        val files: List<String>,
        val thumbs: List<Long>,
        val json: String?,
    )

    /** 单条目解压结果：写入字节数 + 清单原文（仅 library.json 非空）。 */
    private class EntryResult(val bytes: Long, val json: String?)

    /** 物理文件搬移结果。 */
    private class Moved(val files: Int, val thumbs: Int, val bytes: Long, val orphans: Int)

    /** 解压到临时目录：严格白名单 + 条目数/单条目大小/累计量/压缩比四重上限（防 zip bomb）。 */
    private fun extractToTemp(input: InputStream, tmp: File): Extracted {
        val files = mutableListOf<String>()
        val thumbs = mutableListOf<Long>()
        var jsonText: String? = null
        var entryCount = 0
        var totalUncompressed = 0L
        val counting = CountingInputStream(BufferedInputStream(input))
        ZipInputStream(counting).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                // 目录条目也计数：恶意 zip 可用海量空目录条目拖慢恢复
                entryCount++
                require(entryCount <= limits.maxEntries) { "备份条目数超出上限（>${limits.maxEntries}），疑似恶意备份" }
                if (!entry.isDirectory) {
                    val compressedBefore = counting.count
                    val result = extractEntry(zin, tmp, entry.name, files, thumbs)
                    jsonText = result.json ?: jsonText
                    totalUncompressed += result.bytes
                    checkLimits(totalUncompressed, counting.count - compressedBefore, result.bytes)
                }
            }
        }
        return Extracted(files, thumbs, jsonText)
    }

    /** 单条目解压：按前缀分流（清单 / 文档 / 缩略图），未知条目拒绝。返回写入字节数与清单原文。 */
    private fun extractEntry(
        zin: ZipInputStream,
        tmp: File,
        name: String,
        files: MutableList<String>,
        thumbs: MutableList<Long>,
    ): EntryResult = when {
        name == "library.json" -> {
            val text = readBoundedText(zin, limits.maxJsonChars)
            EntryResult(text.length.toLong(), text)   // 计入解压量，使其同样纳入压缩比检查
        }
        name.startsWith("files/") -> {
            val rel = name.removePrefix("files/")
            require(!rel.contains("..") && relPathPattern.matches(rel)) { "非法备份路径: $name" }
            val out = File(tmp, "files/$rel")
            out.parentFile?.mkdirs()
            val written = out.outputStream().use { copyBounded(zin, it, limits.maxEntryBytes) }
            files += rel
            EntryResult(written, null)
        }
        name.startsWith("thumbs/") -> {
            val fn = name.removePrefix("thumbs/")
            val id = fn.substringBefore('.').toLongOrNull()
            require(id != null && fn == "$id.png") { "非法备份缩略图: $name" }
            val out = File(tmp, "thumbs/$fn")
            out.parentFile?.mkdirs()
            val written = out.outputStream().use { copyBounded(zin, it, limits.maxEntryBytes) }
            thumbs += id
            EntryResult(written, null)
        }
        else -> throw IllegalArgumentException("备份包含未知条目: $name")
    }

    /** 累计解压量与压缩比双闸（防 zip bomb）。 */
    private fun checkLimits(totalUncompressed: Long, compressedDelta: Long, written: Long) {
        check(totalUncompressed <= limits.maxTotalBytes) {
            "备份累计解压量超出上限（>${limits.maxTotalBytes} 字节），疑似 zip bomb"
        }
        check(
            compressedDelta <= 0 ||
                totalUncompressed <= max(limits.ratioFloorBytes, compressedDelta * limits.maxRatio),
        ) {
            "备份压缩比异常（解压 $written / 压缩 $compressedDelta），疑似 zip bomb"
        }
    }

    /** 事务写 DB：目录拓扑序 → 文档 → 书签 → 高亮（满足 folderId / documentId 外键）。 */
    private suspend fun writeManifest(manifest: BackupCodec.BackupManifest) {
        val orderedFolders = topoSortFolders(manifest.folders)
        db.withTransaction {
            folderDao.upsertAll(orderedFolders.map { it.toEntity() })
            documentDao.upsertAll(manifest.documents.map { it.toEntity() })
            bookmarkDao.upsertAll(manifest.bookmarks.map { it.toEntity() })
            annotationDao.upsertAll(manifest.annotations.map { it.toEntity() })
        }
    }

    /** 把临时文件 move 进 library/thumb；清单外条目视为孤儿跳过。返回搬移统计。 */
    private fun moveIntoLibrary(
        tmp: File,
        fileEntries: List<String>,
        thumbEntries: List<Long>,
        manifestRelPaths: Set<String>,
        onProgress: Progress,
    ): Moved {
        var filesWritten = 0
        var bytesWritten = 0L
        var orphanCount = 0
        fileEntries.forEachIndexed { i, rel ->
            onProgress(i.toFloat() / max(1, fileEntries.size), "恢复文档 $i/${fileEntries.size}")
            // 仅搬清单中有记录的条目；清单外的 zip 条目视为孤儿跳过，避免库内遗留无引用文件（M9 review S2）
            if (rel !in manifestRelPaths) {
                orphanCount++
                return@forEachIndexed
            }
            val src = File(tmp, "files/$rel")
            if (src.exists()) {
                bytesWritten += storage.moveIntoLibrary(src, rel)
                filesWritten++
            }
        }
        var thumbnailsWritten = 0
        thumbEntries.forEach { id ->
            val src = File(tmp, "thumbs/$id.png")
            if (src.exists()) {
                storage.moveIntoThumb(src, id)
                thumbnailsWritten++
            }
        }
        return Moved(filesWritten, thumbnailsWritten, bytesWritten, orphanCount)
    }

    /** 有界复制：单条目解压量超限抛异常（防 zip bomb）。返回写入字节数。 */
    private fun copyBounded(input: InputStream, out: OutputStream, maxBytes: Long): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            require(total <= maxBytes) { "备份条目解压量超出上限（>$maxBytes 字节），疑似 zip bomb" }
            out.write(buffer, 0, n)
        }
        return total
    }

    /** 有界读取文本：library.json 超大直接拒绝（防内存耗尽）。 */
    private fun readBoundedText(input: InputStream, maxChars: Int): String {
        val sb = StringBuilder()
        val reader = input.bufferedReader(Charsets.UTF_8)
        val cb = CharArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val n = reader.read(cb)
            if (n < 0) break
            sb.append(cb, 0, n)
            require(sb.length <= maxChars) { "备份清单过大（>$maxChars 字符），疑似恶意备份" }
        }
        return sb.toString()
    }

    /**
     * 目录拓扑排序：父目录先于子目录（O(n²)，目录量小足够；检测到循环引用抛异常，不写库）。
     */
    private fun topoSortFolders(folders: List<BackupCodec.FolderDto>): List<BackupCodec.FolderDto> {
        val placed = mutableSetOf<Long>()
        val remaining = folders.toMutableList()
        val result = mutableListOf<BackupCodec.FolderDto>()
        while (remaining.isNotEmpty()) {
            val idx = remaining.indexOfFirst { it.parentId == null || it.parentId in placed }
            require(idx >= 0) { "目录存在循环引用，无法恢复" }
            val f = remaining.removeAt(idx)
            placed.add(f.id)
            result.add(f)
        }
        return result
    }
}

/** 进度回调：fraction ∈ [0,1] + 状态文案。导出/恢复期间在 IO 线程调用。 */
typealias Progress = (fraction: Float, status: String) -> Unit

/** 恢复期资源上限（防 zip bomb / 磁盘耗尽）。备份包属不可信输入，超限即中止恢复。 */
data class RestoreLimits(
    /** zip 条目数上限（library.json + files 与 thumbs 下的文档/缩略图条目） */
    val maxEntries: Int = MAX_ENTRIES,
    /** 单条目解压字节数上限 */
    val maxEntryBytes: Long = MAX_ENTRY_BYTES,
    /** 全部条目累计解压字节数上限 */
    val maxTotalBytes: Long = MAX_TOTAL_BYTES,
    /** library.json 字符数上限 */
    val maxJsonChars: Int = MAX_JSON_CHARS,
    /** 压缩比上限（单条目 解压/压缩 倍数）；小包按 ratioFloorBytes 豁免，避免小文件误杀 */
    val maxRatio: Long = MAX_RATIO,
    val ratioFloorBytes: Long = RATIO_FLOOR_BYTES,
)

/** 统计已读（压缩侧）字节，供压缩比检查；ZipInputStream 的原始输入经由此流。 */
private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    var count: Long = 0L
        private set

    override fun read(): Int {
        val r = super.read()
        if (r >= 0) count++
        return r
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val r = super.read(b, off, len)
        if (r > 0) count += r
        return r
    }
}

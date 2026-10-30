package com.myreader.core.data.backup

import com.myreader.core.data.db.entity.BookmarkEntity
import com.myreader.core.data.db.entity.DocumentEntity
import com.myreader.core.document.FileTypes
import com.myreader.core.data.db.entity.FolderEntity
import com.myreader.core.model.MAX_BOOKMARKS_PER_DOCUMENT
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

/**
 * 备份快照编解码（M8）。纯逻辑、无 Android 依赖，便于 JVM 单测。
 *
 * 设计要点：
 * - 备份包内 `library.json` 顶层为 [BackupManifest]（schemaVersion 兼容门禁 + 全量 folders/documents）。
 * - 备份文件属不可信输入，[decode] 在边界处对 relPath / thumbnailRelPath 做白名单校验，拒绝路径穿越与任意文件名。
 * - schemaVersion 门禁：导出恒为当前版本；恢复**向后兼容**（接受 v1 旧包，书签为空）并拒绝超版本。
 */
object BackupCodec {

    /** 当前备份格式版本（M10 = 2：新增 bookmarks 数组；v1 旧包仍可导入）。 */
    const val SCHEMA_VERSION = 2

    /** 书签 label 上限（不可信输入防御：防超长文案撑爆面板） */
    private const val MAX_BOOKMARK_LABEL_LEN = 200

    /** 流式书签锚点格式：bookmark 十进制串（`MuPDFCore.pageToBookmark`） */
    private val BOOKMARK_ANCHOR_PATTERN = Regex("^\\d{1,20}$")

    /** JSON 配置：容错未知字段（向前兼容新增字段）、pretty 便于调试与人工核查。 */
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Serializable
    data class FolderDto(
        val id: Long,
        val parentId: Long?,
        val name: String,
        val sortOrder: Int = 0,
        val createdAt: Long,
        val updatedAt: Long,
    )

    @Serializable
    data class DocumentDto(
        val id: Long,
        val folderId: Long?,
        val title: String,
        val relPath: String,
        val fileType: String,
        val mimeType: String?,
        val sourceUri: String?,
        val fileSize: Long,
        val contentHash: String?,
        val needsPassword: Boolean,
        val pageCount: Int,
        val progressType: String,
        val currentPage: Int,
        val scrollOffsetY: Int,
        val progressPayload: String?,
        val thumbnailRelPath: String?,
        val lastReadAt: Long?,
        val addedAt: Long,
    )

    /** 书签（M10）：anchor 为 null 表示页码式（page + scrollOffsetY），非 null 为流式 bookmark 串 */
    @Serializable
    data class BookmarkDto(
        val id: Long,
        val documentId: Long,
        val page: Int,
        val scrollOffsetY: Int = 0,
        val anchor: String? = null,
        val label: String,
        val createdAt: Long,
    )

    @Serializable
    data class BackupManifest(
        val schemaVersion: Int,
        val exportedAt: Long,
        val appVersion: String,
        val folders: List<FolderDto>,
        val documents: List<DocumentDto>,
        /** v1 旧包无此字段 → 默认空（向后兼容，不报错） */
        val bookmarks: List<BookmarkDto> = emptyList(),
    )

    /** 整段 relPath 白名单：纯数字 id + 字母数字扩展名（与 DocumentStorage.REL_PATH_PATTERN 同源但本类自包含） */
    private val REL_PATH_PATTERN = Regex("^[0-9]+\\.[a-zA-Z0-9]{1,10}$")

    /** 缩略图 relPath 白名单：thumb/<纯数字id>.png */
    private val THUMB_REL_PATTERN = Regex("^thumb/[0-9]+\\.png$")

    fun encode(manifest: BackupManifest): String = json.encodeToString(manifest)

    /**
     * 解码并校验备份清单。校验失败抛 [IllegalArgumentException]/[kotlinx.serialization.SerializationException]。
     */
    fun decode(text: String): BackupManifest {
        val m = json.decodeFromString<BackupManifest>(text)
        // 向后兼容：v1 旧包（无 bookmarks 字段）可恢复，书签为空；超版本一律拒绝
        require(m.schemaVersion in 1..SCHEMA_VERSION) {
            "不支持的备份版本: ${m.schemaVersion}（当前支持 1~$SCHEMA_VERSION）"
        }
        require(m.folders.isNotEmpty() || m.documents.isNotEmpty()) {
            "备份内容为空"
        }
        m.documents.forEach { d ->
            require(REL_PATH_PATTERN.matches(d.relPath)) { "非法备份文档路径: ${d.relPath}" }
            // 拒绝未知格式入库：导入期已拒收未登记格式，备份恢复不得绕过该约束（M9 review M3）
            require(FileTypes.isKnown(d.fileType)) { "不支持的备份格式: ${d.fileType}" }
            if (d.thumbnailRelPath != null) {
                require(THUMB_REL_PATTERN.matches(d.thumbnailRelPath)) { "非法备份缩略图路径: ${d.thumbnailRelPath}" }
            }
        }
        val documentIds = m.documents.map { it.id }.toSet()
        m.bookmarks.forEach { b ->
            // 书签外键：必须指向本包内文档，否则恢复事务会失败——边界处提前给可读错误
            require(b.documentId in documentIds) { "书签指向不存在的文档: ${b.documentId}" }
            require(b.page >= 0) { "非法书签页码: ${b.page}" }
            require(b.scrollOffsetY >= 0) { "非法书签偏移: ${b.scrollOffsetY}" }
            require(b.label.isNotBlank() && b.label.length <= MAX_BOOKMARK_LABEL_LEN) { "非法书签名称" }
            if (b.anchor != null) {
                require(BOOKMARK_ANCHOR_PATTERN.matches(b.anchor)) { "非法书签锚点" }
            }
        }
        // 一致性 + 防御：总数不得超过「文档数 × 每文档上限」（恶意备份不得灌爆恢复写入）
        require(m.bookmarks.size <= m.documents.size * MAX_BOOKMARKS_PER_DOCUMENT) {
            "备份书签数量超出上限: ${m.bookmarks.size}"
        }
        return m
    }
}

// ---- 顶层映射扩展（全局可见，供 BackupManager 直接调用，避免 object 成员扩展不可见）----

fun FolderEntity.toDto(): BackupCodec.FolderDto = BackupCodec.FolderDto(
    id = id, parentId = parentId, name = name,
    sortOrder = sortOrder, createdAt = createdAt, updatedAt = updatedAt,
)

fun BackupCodec.FolderDto.toEntity(): FolderEntity = FolderEntity(
    id = id, parentId = parentId, name = name,
    sortOrder = sortOrder, createdAt = createdAt, updatedAt = updatedAt,
)

fun DocumentEntity.toDto(): BackupCodec.DocumentDto = BackupCodec.DocumentDto(
    id = id, folderId = folderId, title = title, relPath = relPath, fileType = fileType,
    mimeType = mimeType, sourceUri = sourceUri, fileSize = fileSize, contentHash = contentHash,
    needsPassword = needsPassword, pageCount = pageCount, progressType = progressType,
    currentPage = currentPage, scrollOffsetY = scrollOffsetY, progressPayload = progressPayload,
    thumbnailRelPath = thumbnailRelPath, lastReadAt = lastReadAt, addedAt = addedAt,
)

fun BackupCodec.DocumentDto.toEntity(): DocumentEntity = DocumentEntity(
    id = id, folderId = folderId, title = title, relPath = relPath, fileType = fileType,
    mimeType = mimeType, sourceUri = sourceUri, fileSize = fileSize, contentHash = contentHash,
    needsPassword = needsPassword, pageCount = pageCount, progressType = progressType,
    currentPage = currentPage, scrollOffsetY = scrollOffsetY, progressPayload = progressPayload,
    thumbnailRelPath = thumbnailRelPath, lastReadAt = lastReadAt, addedAt = addedAt,
)

fun BookmarkEntity.toDto(): BackupCodec.BookmarkDto = BackupCodec.BookmarkDto(
    id = id, documentId = documentId, page = page, scrollOffsetY = scrollOffsetY,
    anchor = anchor, label = label, createdAt = createdAt,
)

fun BackupCodec.BookmarkDto.toEntity(): BookmarkEntity = BookmarkEntity(
    id = id, documentId = documentId, page = page, scrollOffsetY = scrollOffsetY,
    anchor = anchor, label = label, createdAt = createdAt,
)

package com.myreader.core.data.backup

import com.myreader.core.data.db.entity.DocumentEntity
import com.myreader.core.document.FileTypes
import com.myreader.core.data.db.entity.FolderEntity
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
 * - schemaVersion 不匹配直接拒绝（v1 仅支持同版本往返；未来升级须写迁移）。
 */
object BackupCodec {

    /** 当前备份格式版本（与导出/恢复一致）。 */
    const val SCHEMA_VERSION = 1

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

    @Serializable
    data class BackupManifest(
        val schemaVersion: Int,
        val exportedAt: Long,
        val appVersion: String,
        val folders: List<FolderDto>,
        val documents: List<DocumentDto>,
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
        require(m.schemaVersion == SCHEMA_VERSION) {
            "不支持的备份版本: ${m.schemaVersion}（当前仅支持 $SCHEMA_VERSION）"
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

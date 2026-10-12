package com.myreader.core.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 文档实体。folderId 外键 onDelete=SET_NULL 是兜底安全网（业务漏处理直接删 folder 时文档回根不致崩溃）；
 * 正常路径走业务层事务内显式编排。
 */
@Entity(
    tableName = "documents",
    foreignKeys = [ForeignKey(
        entity = FolderEntity::class,
        parentColumns = ["id"],
        childColumns = ["folderId"],
        onDelete = ForeignKey.SET_NULL,    // 删目录后文档回到根
    )],
    indices = [Index("folderId"), Index("lastReadAt"), Index("fileType"), Index("contentHash")]
)
data class DocumentEntity(
    @PrimaryKey val id: Long,
    val folderId: Long?,
    val title: String,
    val relPath: String,                 // 相对 filesDir/library/，扁平 "<id>.<ext>"；移动文档不改此字段
    val fileType: String,               // "pdf"/"epub"/"cbz"...，路由引擎 + 书架图标 + 导入过滤
    val mimeType: String?,              // 原始 mime，备查
    val sourceUri: String?,             // 原始 SAF Uri，仅记录来源不依赖
    val fileSize: Long,
    val contentHash: String? = null,     // SHA-256，导入复制时流式计算，重复导入检测用
    val needsPassword: Boolean = false,  // 导入验证为加密文档 → true；解密成功后清除
    val pageCount: Int = -1,             // 导入验证成功时填充；-1 未知（含待解密）；-2 打开损坏标记
    val progressType: String = "page",  // "page"(页码式) / "cfi"(流式)，决定读哪个进度字段
    val currentPage: Int = 0,           // 进度（progressType="page" 时有效）
    val scrollOffsetY: Int = 0,         // 页码式垂直模式额外偏移
    val progressPayload: String? = null, // 流式进度 JSON（progressType="cfi" 时存 CFI 串等）
    val thumbnailRelPath: String?,
    val lastReadAt: Long?,
    val addedAt: Long,
)

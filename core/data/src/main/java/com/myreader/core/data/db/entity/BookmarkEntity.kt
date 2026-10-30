package com.myreader.core.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 书签实体（M10，schema v2 新增表；既有表零变更）。
 *
 * 锚点双轨（与进度锚点同一套防御，见 docs/m10-plan.md S0）：
 * - 页码式（pdf/cbz/xps/txt）：`page` + `scrollOffsetY`，`anchor` 为 null；
 * - 流式（epub）：`anchor` 存 bookmark 十进制串（跨重排稳定），`page` 存创建时解析页码，
 *   仅用于列表排序与锚点失效时回落。
 *
 * `documentId` 外键 CASCADE：删文档即删书签（目录删除只把文档移回根，书签保留）。
 */
@Entity(
    tableName = "bookmarks",
    foreignKeys = [ForeignKey(
        entity = DocumentEntity::class,
        parentColumns = ["id"],
        childColumns = ["documentId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("documentId")],
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    /** 页码式 = 收藏页（0 基）；流式 = 创建时解析页码（排序/回落用） */
    val page: Int,
    /** 垂直模式收藏点像素偏移（页码式有效） */
    val scrollOffsetY: Int = 0,
    /** 流式 bookmark 串（`MuPDFCore.pageToBookmark` 十进制）；页码式为 null */
    val anchor: String? = null,
    /** 列表展示文案快照（「第 N 页」/「第 N 屏」，创建时生成，重排不回溯改） */
    val label: String,
    val createdAt: Long,
)

package com.myreader.core.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 高亮/笔记实体（M12，schema v3 新增表；既有表零变更）。
 *
 * `anchor` 存归一化 quad JSON（`[[ulx,uly,urx,ury,llx,lly,lrx,lry], ...]`，相对页面宽高 0..1），
 * 与 Bookmark 的页码/锚点双轨并列：书签是「页级」，高亮是「文本级矩形」。
 *
 * `documentId` 外键 CASCADE：删文档即删高亮/笔记（目录删除一并删文档，批注随之级联删）。
 */
@Entity(
    tableName = "annotations",
    foreignKeys = [ForeignKey(
        entity = DocumentEntity::class,
        parentColumns = ["id"],
        childColumns = ["documentId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("documentId")],
)
data class AnnotationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    /** HIGHLIGHT / NOTE（存枚举名，避免依赖 core:model 枚举序数） */
    val type: String,
    /** 页码（PDF 固定排版；epub 为创建时解析页码，仅用于排序与锚点失效回落） */
    val page: Int,
    /** 归一化 quad JSON，相对页面宽高 0..1 */
    val anchor: String,
    /** ARGB 颜色 */
    val color: Int,
    /** 选中文本快照（列表展示/检索） */
    val text: String,
    /** 笔记正文（type=NOTE 非空） */
    val note: String? = null,
    val createdAt: Long,
)

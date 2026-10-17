package com.myreader.feature.library

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract

/**
 * 目录遍历源抽象（目录导入）：SAF tree 为默认实现，单测注入内存实现
 * （Robolectric 无法伪造 DocumentsContract 的 child documents 查询，故以接口隔离）。
 */
interface DirectoryTreeSource {

    /** 目录树中的一个条目（文件或目录）。 */
    data class Entry(
        val docId: String,
        val displayName: String?,
        val mimeType: String?,
        val size: Long?,
        val isDirectory: Boolean,
    )

    /** 根 documentId（扫描起点）。 */
    val rootDocId: String

    /** 列出该目录的直接子项；null = 不可访问（权限被撤销/提供者异常）。 */
    fun children(docId: String): List<Entry>?

    /** 该条目的可打开 Uri（供复制与 DISPLAY_NAME/SIZE 元数据查询）。 */
    fun documentUri(docId: String): Uri
}

/** 目录访问失败（无权限 / Uri 非法）：调用方据此给用户友好提示而不崩。 */
class DirectoryAccessException(message: String) : Exception(message)

/**
 * 基于 SAF `ACTION_OPEN_DOCUMENT_TREE` 的遍历实现：只读 ContentResolver 查询，
 * 不声明任何存储权限（与既有单文件导入一致，README「隐私与安全」承诺不变）。
 */
class SafDirectoryTreeSource(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
) : DirectoryTreeSource {

    override val rootDocId: String = DocumentsContract.getTreeDocumentId(treeUri)
        ?: throw DirectoryAccessException("无法读取所选目录")

    override fun children(docId: String): List<DirectoryTreeSource.Entry>? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        return try {
            resolver.query(childrenUri, projection, null, null, null)?.use { c ->
                val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                buildList {
                    while (c.moveToNext()) {
                        val id = if (idIdx >= 0) c.getString(idIdx) else continue
                        val mime = if (mimeIdx >= 0) c.getString(mimeIdx) else null
                        val size = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else null
                        add(
                            DirectoryTreeSource.Entry(
                                docId = id,
                                displayName = if (nameIdx >= 0) c.getString(nameIdx) else null,
                                mimeType = mime,
                                size = size,
                                isDirectory = DocumentsContract.Document.MIME_TYPE_DIR == mime,
                            ),
                        )
                    }
                }
            }
        } catch (_: SecurityException) {
            null   // 权限被撤销：按不可访问处理（计入汇总，不崩）
        } catch (_: Exception) {
            null
        }
    }

    override fun documentUri(docId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
}

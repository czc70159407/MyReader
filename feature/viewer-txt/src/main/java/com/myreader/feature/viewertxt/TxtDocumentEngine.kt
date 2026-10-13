package com.myreader.feature.viewertxt

import com.myreader.core.document.CorruptDocumentException
import com.myreader.core.document.Document
import com.myreader.core.document.DocumentEngine
import java.io.File
import javax.inject.Inject

/**
 * 纯文本引擎（M9-F，`@FileTypeKey("txt")` 注册于 app 的 DocumentEngineModule）。
 * 读取 UTF-8 文本 → [TxtPaginator] 分页 → [TxtDocument]。txt 不加密，忽略 password。
 * 读取 IO 失败映射为 [CorruptDocumentException]（对齐 MuPDF 引擎的异常契约，供导入期拒收/打开期标 -2）。
 */
class TxtDocumentEngine @Inject constructor() : DocumentEngine {

    override fun open(path: String, fileType: String, password: String?): Document {
        val file = File(path)
        val text = try {
            file.readText()   // 默认 UTF-8
        } catch (e: Exception) {
            throw CorruptDocumentException("无法读取文本: ${file.name}", e)
        }
        return TxtDocument(TxtPaginator.paginate(text))
    }
}

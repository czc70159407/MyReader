package com.myreader.feature.viewermupdf

import com.artifex.mupdf.fitz.SeekableInputStream
import com.artifex.mupdf.viewer.MuPDFCore
import com.myreader.core.document.CorruptDocumentException
import com.myreader.core.document.Document
import com.myreader.core.document.DocumentEngine
import com.myreader.core.document.FileTypes
import com.myreader.core.document.PasswordRequiredException
import java.io.File
import javax.inject.Inject

/**
 * MuPDF 引擎实现（M9 起以 @FileTypeKey 注册 "pdf"/"epub"/"cbz"/"xps" 四格式）。
 * 异常映射（对齐上游 DocumentActivity 模式）：
 * - 构造抛异常 → CorruptDocumentException（损坏/非支持格式）
 * - 构造成功且 needsPassword() → 密码缺失或认证失败抛 PasswordRequiredException
 * 打开策略（对齐上游 openCore）：≤8MB 整文件进内存，>8MB 走 SeekableFileInputStream 流式。
 *
 * M9 magic：fitz 打开 magic 取 [FileTypes.magicOf]（各格式的规范 MIME，如 application/epub+zip），
 * 而非裸 fileType——规范 MIME 一定在 fitz 各 handler 的 magic 列表内，规避 S0-4 的识别不确定性
 * （application/pdf 亦是 PDF 的规范 magic，无回归风险）。
 */
class MuPdfDocumentEngine @Inject constructor() : DocumentEngine {

    companion object {
        /** 上游 DocumentActivity 同款阈值：8MB 内读 buffer，超出走流 */
        private const val BUFFER_LIMIT = 8L * 1024 * 1024
    }

    override fun open(path: String, fileType: String, password: String?): Document {
        val file = File(path)
        val magic = FileTypes.magicOf(fileType)
        val core: MuPDFCore = try {
            if (file.length() <= BUFFER_LIMIT) {
                MuPDFCore(file.readBytes(), magic)
            } else {
                val stm: SeekableInputStream = SeekableFileInputStream(file)
                MuPDFCore(stm, magic)
            }
        } catch (e: Exception) {
            throw CorruptDocumentException("无法打开文档: ${file.name}", e)
        }

        if (core.needsPassword()) {
            val authed = password != null && core.authenticatePassword(password)
            if (!authed) {
                core.onDestroy()
                throw PasswordRequiredException("文档已加密: ${file.name}")
            }
        }
        return MuPdfDocument(core)
    }
}

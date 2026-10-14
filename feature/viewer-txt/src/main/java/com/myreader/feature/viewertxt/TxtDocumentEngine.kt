package com.myreader.feature.viewertxt

import com.myreader.core.document.CorruptDocumentException
import com.myreader.core.document.Document
import com.myreader.core.document.DocumentEngine
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import javax.inject.Inject

/**
 * 纯文本引擎（M9-F，`@FileTypeKey("txt")` 注册于 app 的 DocumentEngineModule）。
 * 读取文本 → [TxtPaginator] 分页 → [TxtDocument]。txt 不加密，忽略 password。
 *
 * 健壮性（M9 review S1/S2）：
 * - 体积闸门：超 16MB 直接拒收，避免整文件解码 + 全量分页导致 OOM；
 * - BOM 探测 + 编码回退：UTF-8(含 BOM) → 剥离 BOM；无 BOM 严格 UTF-8 失败再回落 GBK→Big5→ISO-8859-1，
 *   解决中文 txt（GBK/BIG5）被静默替换为 U+FFFD 与 BOM 残留问题。
 */
class TxtDocumentEngine @Inject constructor() : DocumentEngine {

    companion object {
        /** 单文件体积上限（字节），超过拒收以防 OOM（全文解码约 3~4 倍 + 分页再复制） */
        private const val MAX_BYTES = 16L * 1024 * 1024
    }

    override fun open(path: String, fileType: String, password: String?): Document {
        val file = File(path)
        if (file.length() > MAX_BYTES) {
            throw CorruptDocumentException("文本过大（>16MB），请使用分卷或压缩版: ${file.name}")
        }
        val bytes = try {
            file.readBytes()
        } catch (e: Exception) {
            throw CorruptDocumentException("无法读取文本: ${file.name}", e)
        }
        val text = decodeText(bytes)
        return TxtDocument(TxtPaginator.paginate(text))
    }

    /** BOM 探测后按 UTF-8（含 BOM 剥离）→ GBK → Big5 → ISO-8859-1 顺序解码（M9 review S2）。 */
    private fun decodeText(bytes: ByteArray): String {
        val bom = detectBom(bytes)
        return when (bom) {
            "UTF-8" -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            "UTF-16LE" -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            "UTF-16BE" -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            else -> decodeWithoutBom(bytes)
        }
    }

    private fun decodeWithoutBom(bytes: ByteArray): String {
        // 严格 UTF-8：任何非法字节即报错，不静默产生 U+FFFD
        try {
            return Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: Exception) {
            // 回落常见中文编码
            return runCatching { String(bytes, charset("GBK")) }
                .getOrElse { runCatching { String(bytes, charset("Big5")) }.getOrDefault(String(bytes, Charsets.ISO_8859_1)) }
        }
    }

    private fun detectBom(bytes: ByteArray): String? {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) return "UTF-8"
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return "UTF-16LE"
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return "UTF-16BE"
        return null
    }
}

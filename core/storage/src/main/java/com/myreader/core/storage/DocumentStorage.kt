package com.myreader.core.storage

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * 文档物理文件 IO（纯 JVM，java.io）。物理文件按文档 ID 平铺存储（relPath = "<id>.<ext>"），
 * 文件夹层级仅由 DB 的 folderId 表达，移动文档不动文件。
 *
 * 导入复制时流式计算 SHA-256（contentHash，重复导入检测用）；复制失败删除半成品回滚。
 */
class DocumentStorage(private val libraryDir: File) {

    companion object {
        /** 空间检查余量：不足该余量视为存储空间不足 */
        const val SPACE_MARGIN_BYTES: Long = 16L * 1024 * 1024

        const val THUMB_DIR_NAME = "thumb"

        /** 扩展名白名单：字母数字，1-10 位（防路径穿越，M3 从用户文件名推导 ext 前的硬闸） */
        private val EXT_PATTERN = Regex("^[a-zA-Z0-9]{1,10}$")

        /**
         * 备份恢复 relPath 白名单：纯数字 id + 字母数字扩展名（<id>.<ext>）。
         * 备份 JSON 属不可信输入，恢复写入前必须整体匹配，杜绝 "../" 或任意文件名。
         */
        private val REL_PATH_PATTERN = Regex("^[0-9]+\\.[a-zA-Z0-9]{1,10}$")

        /** 备份恢复缩略图名：thumb/<纯数字id>.png 形态不做，仅接受 docId 数字，落 thumb/<id>.png */
        private val THUMB_ID_PATTERN = Regex("^[0-9]+$")

        /** 缩略图 relPath 白名单：thumb/<纯数字id>.png（与 BackupCodec.THUMB_REL_PATTERN 同源，安全审计 L3） */
        private val THUMB_REL_PATTERN = Regex("^${THUMB_DIR_NAME}/[0-9]+\\.png$")
    }

    /** 复制结果：relPath 相对 libraryDir，fileSize 字节数，contentHash SHA-256（十六进制小写） */
    data class CopyResult(
        val relPath: String,
        val fileSize: Long,
        val contentHash: String,
    )

    /** 存储空间是否足够容纳 requiredBytes（含余量）。
     *  注意：全新安装时 libraryDir 尚不存在，File.usableSpace 对不存在路径返回 0L，
     *  会误判空间不足，故不存在时回退到其父目录（filesDir，必存在）评估。 */
    fun hasSpaceFor(requiredBytes: Long): Boolean {
        val dir = if (libraryDir.exists()) libraryDir else (libraryDir.parentFile ?: libraryDir)
        // 写成「可用 - 需求 >= 余量」而非「可用 >= 需求 + 余量」，避免 requiredBytes 极大时的 Long 溢出
        return dir.usableSpace - requiredBytes >= SPACE_MARGIN_BYTES
    }

    /**
     * 复制源流到 libraryDir/<docId>.<ext>，流式计算 SHA-256。
     * 失败（IO 异常/磁盘满）时删除半成品文件并抛出原异常（回滚）。
     * ext 仅允许字母数字（防路径穿越）。
     */
    fun copyIn(source: InputStream, docId: Long, ext: String): CopyResult {
        require(EXT_PATTERN.matches(ext)) { "非法文件扩展名: $ext" }
        val relPath = "$docId.$ext"
        val target = resolve(relPath)
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        try {
            if (!libraryDir.exists()) libraryDir.mkdirs()
            source.use { input ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        size += read
                    }
                }
            }
        } catch (t: Throwable) {
            target.delete()
            throw t
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        return CopyResult(relPath = relPath, fileSize = size, contentHash = hash)
    }

    /** 删除文档物理文件。文件不存在视为成功（幂等）。 */
    fun deleteDocument(relPath: String): Boolean {
        val f = resolve(relPath)
        return !f.exists() || f.delete()
    }

    /** 删除缩略图文件（相对 filesDir，如 "thumb/<id>.png"；null 直接成功）。
     *  先过整段白名单（安全审计 L3：canonical 包含校验只保证落在 filesDir 内，
     *  不能阻止 thumb/../library_x 等目录内旁路），再做 canonical 包含校验双保险。 */
    fun deleteThumbnail(thumbnailRelPath: String?): Boolean {
        if (thumbnailRelPath == null) return true
        require(THUMB_REL_PATTERN.matches(thumbnailRelPath)) {
            "非法缩略图路径: $thumbnailRelPath"
        }
        val base = libraryDir.parentFile ?: libraryDir
        val f = File(base, thumbnailRelPath)
        require(f.canonicalPath.startsWith(base.canonicalPath + File.separator)) {
            "非法缩略图路径: $thumbnailRelPath"
        }
        return !f.exists() || f.delete()
    }

    /** 文档物理文件是否存在。 */
    fun documentExists(relPath: String): Boolean = resolve(relPath).exists()

    /** 文档物理文件（供渲染/打开用）。 */
    fun documentFile(relPath: String): File = resolve(relPath)

    /** 解析相对路径并校验不越出 libraryDir（canonical 包含检查，防 "../" 穿越）。 */
    private fun resolve(relPath: String): File {
        val f = File(libraryDir, relPath)
        require(f.canonicalPath.startsWith(libraryDir.canonicalPath + File.separator)) {
            "非法文档路径: $relPath"
        }
        return f
    }

    /** 缩略图目录（导入时/首开兜底生成首页缩略图存放处）。 */
    fun thumbDir(): File = File(libraryDir.parentFile ?: libraryDir, THUMB_DIR_NAME)

    /** 流式读取文档物理文件（导出用）；不存在返回 null（缺失文件由调用方计入 missing）。 */
    fun openRead(relPath: String): InputStream? = resolve(relPath).takeIf { it.exists() }?.inputStream()

    /** 缩略图流式读取（导出用）；relPath 为 null、不匹配白名单（安全审计 L3）或文件不存在返回 null。 */
    fun openThumbnailRead(thumbnailRelPath: String?): InputStream? {
        if (thumbnailRelPath == null) return null
        if (!THUMB_REL_PATTERN.matches(thumbnailRelPath)) return null
        val base = libraryDir.parentFile ?: libraryDir
        val f = File(base, thumbnailRelPath)
        if (!f.canonicalPath.startsWith(base.canonicalPath + File.separator)) return null
        return f.takeIf { it.exists() }?.inputStream()
    }

    /** 计算给定 relPath 列表的总字节数（仅统计存在的文件），用于恢复前空间预检。 */
    fun totalBytesOf(relPaths: List<String>): Long =
        relPaths.fold(0L) { acc, rp -> acc + (resolve(rp).takeIf { it.exists() }?.length() ?: 0L) }

    /**
     * 恢复写回文档物理文件（沿用备份原 relPath，不新建 id）。整段 relPath 须先匹配白名单，
     * 再经 resolve 的 canonical 包含校验，双重防穿越；返回写入字节数。失败删半成品回滚。
     */
    fun restoreFile(source: InputStream, relPath: String): Long {
        require(REL_PATH_PATTERN.matches(relPath)) { "非法备份文件路径: $relPath" }
        val target = resolve(relPath)
        var size = 0L
        try {
            if (!libraryDir.exists()) libraryDir.mkdirs()
            source.use { input ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        size += read
                    }
                }
            }
        } catch (t: Throwable) {
            target.delete()
            throw t
        }
        return size
    }

    /**
     * 恢复写回缩略图（落 thumb/<docId>.png，返回相对 filesDir 的 relPath）。docId 须为纯数字（防穿越）；
     * 删除历史遗留同 id 缩略图以幂等。
     */
    fun restoreThumbnail(source: InputStream, docId: Long): String {
        require(THUMB_ID_PATTERN.matches(docId.toString())) { "非法缩略图 docId: $docId" }
        val dir = thumbDir().apply { if (!exists()) mkdirs() }
        val file = File(dir, "$docId.png")
        file.delete()
        source.use { input -> file.outputStream().use { out -> input.copyTo(out) } }
        return "${THUMB_DIR_NAME}/$docId.png"
    }

    /**
     * 创建备份恢复用临时目录（位于 filesDir 下，与 libraryDir 同分区以确保 renameTo 瞬时）。
     * 恢复全程在临时目录解压+校验，成功后再 move 进 library/，失败由调用方 deleteRecursively 清理。
     */
    fun newBackupTempDir(): File {
        val base = libraryDir.parentFile ?: libraryDir
        val dir = File(base, "myreader_backup_tmp_${System.nanoTime()}")
        require(dir.mkdirs() || dir.isDirectory) { "无法创建备份临时目录" }
        return dir
    }

    /**
     * 把临时文件移入 library/ 并沿用原 relPath（同分区 renameTo 瞬时；跨设备回退流式复制）。
     * 整段 relPath 须先匹配白名单，再经 resolve 的 canonical 包含校验，双重防穿越；返回写入字节数。
     */
    fun moveIntoLibrary(src: File, relPath: String): Long {
        require(REL_PATH_PATTERN.matches(relPath)) { "非法备份文件路径: $relPath" }
        val target = resolve(relPath)
        if (!libraryDir.exists()) libraryDir.mkdirs()
        if (!src.renameTo(target)) {
            src.inputStream().use { restoreFile(it, relPath) }
        }
        return target.length()
    }

    /**
     * 把临时缩略图移入 thumb/（落 thumb/<docId>.png），同分区 renameTo 瞬时；跨设备回退流式复制。
     * 返回相对 filesDir 的 relPath。
     */
    fun moveIntoThumb(src: File, docId: Long): String {
        require(THUMB_ID_PATTERN.matches(docId.toString())) { "非法缩略图 docId: $docId" }
        val dir = thumbDir().apply { if (!exists()) mkdirs() }
        val file = File(dir, "$docId.png")
        file.delete()
        if (!src.renameTo(file)) {
            src.inputStream().use { restoreThumbnail(it, docId) }
        }
        return "${THUMB_DIR_NAME}/$docId.png"
    }
}

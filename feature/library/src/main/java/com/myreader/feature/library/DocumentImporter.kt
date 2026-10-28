package com.myreader.feature.library

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.ImageBitmap
import com.myreader.core.common.DispatcherProvider
import com.myreader.core.data.repo.LibraryRepository
import com.myreader.core.data.repo.newEntityId
import com.myreader.core.document.CorruptDocumentException
import com.myreader.core.document.Document
import com.myreader.core.document.DocumentEngineRouter
import com.myreader.core.document.FileTypes
import com.myreader.core.document.PasswordRequiredException
import com.myreader.core.storage.DocumentStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 导入编排：复制（流式 SHA-256）→ hash 查重 → 打开验证分流（损坏拒收/加密标记）→
 * 缩略图生成 → 入库；任一步失败删除半成品回滚。
 * 设计见 docs/ui-design.md「导入流程」。
 */
@Singleton
class DocumentImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val router: DocumentEngineRouter,
    private val storage: DocumentStorage,
    private val repository: LibraryRepository,
    private val dispatchers: DispatcherProvider,
) {
    companion object {
        private const val TAG = "DocumentImporter"
    }

    enum class Status {
        /** 导入成功（含完整元数据与缩略图） */
        IMPORTED,

        /** 导入成功但已加密：入库标记 needsPassword，无 pageCount/缩略图（首开兜底） */
        IMPORTED_PASSWORD_MARKED,

        /** 内容 hash 命中已有文档，默认跳过（用户可确认后 force 重导） */
        DUPLICATE_SKIPPED,

        /** 损坏/无法读取：半成品已删，不入库 */
        CORRUPT_REJECTED,

        /** 扩展名/mime 均未命中已注册格式：不复制、不入库（M9 集中归一化，防未注册格式流入 router 崩溃） */
        UNSUPPORTED,

        /** 其他失败（空间不足/IO/插库异常），半成品已删 */
        FAILED,
    }

    data class Result(
        val uri: Uri,
        val status: Status,
        val documentId: Long? = null,
        /** 已有文档标题（DUPLICATE_SKIPPED 时用于弹窗文案） */
        val existingTitle: String? = null,
        val title: String? = null,
        val reason: String? = null,
    )

    /**
     * 导入单个文档。
     * @param force true 时跳过 hash 查重（用户确认「仍导入」后的重导路径）
     */
    suspend fun import(uri: Uri, targetFolderId: Long? = null, force: Boolean = false): Result =
        withContext(dispatchers.io) {
            val displayName = queryDisplayName(uri)
            val mimeType = context.contentResolver.getType(uri)
            val size = querySize(uri)
            // 安全审计 L1：uri/展示名含用户隐私（文件名/来源路径），仅 debug 打印
            if (BuildConfig.DEBUG) {
                android.util.Log.i(TAG, "import start: uri=$uri name=$displayName mime=$mimeType size=$size force=$force")
            }
            var copy: DocumentStorage.CopyResult? = null
            var thumbnailRelPath: String? = null
            try {
                // 1. fileType 归一化（M9）：扩展名优先、mime 兜底；未命中已注册格式直接拒收，
                //    不复制、不入库——防未注册格式流入 router 抛 IllegalArgumentException（G3）。
                val fileType = FileTypes.normalize(displayName, mimeType)
                    ?: return@withContext Result(uri, Status.UNSUPPORTED, title = displayName, reason = "暂不支持的文件类型")
                // 物理扩展名统一取 fileType（pdf/epub/cbz/xps 均过 DocumentStorage 白名单；
                // 打开经 router 按 fileType 路由并传 magic，与文件扩展名解耦，故 .oxps 等变体安全）
                val ext = fileType

                // 2. 空间检查（尺寸已知时）；复制前不写半文件
                if (size != null && size > 0 && !storage.hasSpaceFor(size)) {
                    return@withContext Result(uri, Status.FAILED, title = displayName, reason = "存储空间不足")
                }

                // 3. docId 先行（文件名含 id，入库必须同 id），流式复制 + SHA-256
                val docId = newEntityId()
                copy = context.contentResolver.openInputStream(uri)?.use {
                    storage.copyIn(it, docId, ext)
                } ?: return@withContext Result(uri, Status.FAILED, title = displayName, reason = "无法读取所选文件")

                // 4. hash 查重（force 跳过）
                if (!force) {
                    val existing = repository.findByHash(copy.contentHash)
                    if (existing != null) {
                        storage.deleteDocument(copy.relPath)
                        copy = null
                        return@withContext Result(
                            uri, Status.DUPLICATE_SKIPPED,
                            documentId = existing.id, existingTitle = existing.title,
                            // T4 review H3：displayName 供明细行展示文件名（原为 null 导致回退泄漏 uri 段）
                            title = displayName,
                        )
                    }
                }

                // 5. 打开验证分流
                val filePath = storage.documentFile(copy.relPath).absolutePath
                val doc: Document? = try {
                    router.engineFor(fileType).open(filePath, fileType, password = null)
                } catch (e: PasswordRequiredException) {
                    null // 加密文档照常入库，标记 needsPassword（首开兜底补元数据）
                } catch (e: CorruptDocumentException) {
                    storage.deleteDocument(copy.relPath)
                    copy = null
                    return@withContext Result(uri, Status.CORRUPT_REJECTED, title = displayName, reason = e.message)
                }

                // 6. pageCount + 首页缩略图（失败不阻断导入，留首开兜底）
                var pageCount = -1
                var needsPassword = false
                if (doc != null) {
                    // 流式重排（epub）pageCount 恒 -1 不落库（随排版变化，见 docs/m9-plan.md §2 / data-layer.md）；
                    // 阅读器页数取运行时 doc.pageCount。页码式（pdf/cbz/xps/txt）取实际页数。
                    pageCount = if (doc.reflowable) -1 else doc.pageCount
                    try {
                        val thumb = router.viewerFor(fileType).thumbnail(doc)
                        thumbnailRelPath = saveThumbnail(docId, thumb)
                    } catch (_: Exception) {
                        // 缩略图失败可容忍
                    }
                    doc.close()
                } else {
                    needsPassword = true
                }

                // 7. 入库（relPath 沿用复制时的 docId）
                android.util.Log.i(TAG, "验证通过: pageCount=$pageCount needsPassword=$needsPassword thumb=$thumbnailRelPath")
                val title = displayName ?: "未命名文档"
                repository.addDocument(
                    id = docId,
                    folderId = targetFolderId,
                    title = title,
                    relPath = copy.relPath,
                    fileType = fileType,
                    mimeType = mimeType,
                    sourceUri = uri.toString(),
                    fileSize = copy.fileSize,
                    contentHash = copy.contentHash,
                    pageCount = pageCount,
                    progressType = FileTypes.defaultProgressType(fileType),
                    thumbnailRelPath = thumbnailRelPath,
                    needsPassword = needsPassword,
                )
                Result(uri, if (needsPassword) Status.IMPORTED_PASSWORD_MARKED else Status.IMPORTED, docId, title = title)
            } catch (e: CancellationException) {
                // 协程取消（如导入中离开页面）：清理半成品后原样重抛，保持结构化并发
                copy?.let { storage.deleteDocument(it.relPath) }
                thumbnailRelPath?.let { storage.deleteThumbnail(it) }
                throw e
            } catch (t: Throwable) {
                // 任一步异常：删半成品回滚（文件已复制才有 relPath，缩略图已保存则一并删）
                // 安全审计 L1：release 不打印 uri（隐私），仅保留异常类型便于排查
                if (BuildConfig.DEBUG) {
                    android.util.Log.e(TAG, "导入失败 uri=$uri", t)
                } else {
                    android.util.Log.e(TAG, "导入失败: ${t.javaClass.simpleName}")
                }
                copy?.let { storage.deleteDocument(it.relPath) }
                thumbnailRelPath?.let { storage.deleteThumbnail(it) }
                Result(uri, Status.FAILED, title = displayName, reason = t.message ?: t.javaClass.simpleName)
            }
        }

    private fun queryDisplayName(uri: Uri): String? =
        queryMeta(uri).first

    private fun querySize(uri: Uri): Long? = queryMeta(uri).second

    /** SAF 元数据：DISPLAY_NAME + SIZE（单次 query） */
    private fun queryMeta(uri: Uri): Pair<String?, Long?> {
        var name: String? = null
        var size: Long? = null
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        return name to size
    }

    /** 缩略图 PNG 保存：filesDir/thumb/<docId>.png；返回相对 filesDir 的 relPath */
    private fun saveThumbnail(docId: Long, image: ImageBitmap): String {
        val dir = storage.thumbDir().apply { if (!exists()) mkdirs() }
        val file = File(dir, "$docId.png")
        FileOutputStream(file).use { out ->
            image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 90, out)
        }
        return "${DocumentStorage.THUMB_DIR_NAME}/$docId.png"
    }
}

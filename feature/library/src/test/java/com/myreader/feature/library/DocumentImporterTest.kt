package com.myreader.feature.library

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.Room
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.Composable
import androidx.test.core.app.ApplicationProvider
import com.myreader.core.common.DispatcherProvider
import com.myreader.core.data.db.MyReaderDatabase
import com.myreader.core.data.repo.LibraryRepositoryImpl
import com.myreader.core.document.CorruptDocumentException
import com.myreader.core.document.Document
import com.myreader.core.document.DocumentEngine
import com.myreader.core.document.DocumentEngineRouter
import com.myreader.core.document.DocumentViewerProvider
import com.myreader.core.document.OutlineEntry
import com.myreader.core.document.PasswordRequiredException
import com.myreader.core.document.ProgressPayload
import com.myreader.core.document.SimpleDocumentEngineRouter
import com.myreader.core.model.ReaderMode
import com.myreader.core.storage.DocumentStorage
import androidx.compose.ui.graphics.ImageBitmap
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

/**
 * DocumentImporter 编排单测：真 storage + 真 inMemory Repository + Fake 引擎/渲染器
 * （接口隔离价值：libmupdf_java.so 无法在 JVM 加载，Fake 完全覆盖编排分支）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DocumentImporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: MyReaderDatabase
    private lateinit var storage: DocumentStorage
    private lateinit var repository: LibraryRepositoryImpl
    private lateinit var importer: DocumentImporter

    /** 可编程 fake：模拟成功/损坏/加密/未预见异常；记录最近打开的 path 供断言清理效果 */
    private class FakeEngine(var behavior: Behavior) : DocumentEngine {
        enum class Behavior { OK, CORRUPT, TOO_LARGE, PASSWORD, BOOM }

        var lastOpenedPath: String? = null

        override fun open(path: String, fileType: String, password: String?): Document {
            lastOpenedPath = path
            when (behavior) {
                Behavior.CORRUPT -> throw CorruptDocumentException("bad")
                // 与 TxtDocumentEngine 超限文案逐字一致（白名单唯一放行项）
                Behavior.TOO_LARGE -> throw CorruptDocumentException("文本过大（>16MB），请使用分卷或压缩版")
                Behavior.PASSWORD -> throw PasswordRequiredException("encrypted")
                // T6：模拟含内部物理路径的异常消息（真实场景为 native/IO 异常）
                Behavior.BOOM -> error("$path 崩溃")
                Behavior.OK -> {}
            }
            // epub 视为流式重排（pageCount 不落库 → 恒 -1），其余页码式
            return FakeDocument(reflowable = fileType == "epub")
        }
    }

    private class FakeDocument(override val reflowable: Boolean = false) : Document {
        override val pageCount: Int = 3
        override fun gotoPage(page: Int) {}
        override suspend fun pageSize(page: Int): androidx.compose.ui.geometry.Size =
            androidx.compose.ui.geometry.Size(595f, 842f)
        override fun drawPage(bitmap: android.graphics.Bitmap, page: Int, width: Int, height: Int, patchX: Int, patchY: Int, patchW: Int, patchH: Int) {}
        override suspend fun outline(): List<OutlineEntry> = emptyList()
        override fun close() {}
    }

    private class FakeViewer : DocumentViewerProvider {
        @Composable
        override fun Content(doc: Document, initialProgress: ProgressPayload, mode: ReaderMode, onProgressChanged: (ProgressPayload) -> Unit, onPageCountChanged: (Int) -> Unit, modifier: androidx.compose.ui.Modifier) = Unit

        override suspend fun thumbnail(doc: Document): ImageBitmap =
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    private val dispatchers = object : DispatcherProvider {
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private val pdfBytes = "%PDF-1.4 fake".toByteArray()
    private lateinit var engine: FakeEngine
    private lateinit var libraryDir: File

    @Before
    fun setUp() {
        context = getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MyReaderDatabase::class.java).allowMainThreadQueries().build()
        libraryDir = tmp.newFolder("library")
        storage = DocumentStorage(libraryDir)
        repository = LibraryRepositoryImpl(db, db.folderDao(), db.documentDao(), storage, dispatchers)
        engine = FakeEngine(FakeEngine.Behavior.OK)
        val router = SimpleDocumentEngineRouter(
            engines = mapOf(
                "pdf" to engine,
                "epub" to engine,
                "cbz" to engine,
                "xps" to engine,
                "txt" to engine,   // T6：超限文案白名单用例
            ),
            viewers = mapOf(
                "pdf" to FakeViewer(),
                "epub" to FakeViewer(),
                "cbz" to FakeViewer(),
                "xps" to FakeViewer(),
                "txt" to FakeViewer(),
            ),
        )
        importer = DocumentImporter(context, router, storage, repository, dispatchers)
    }

    @After
    fun tearDown() = db.close()

    private fun registerPdf(name: String = "book.pdf", bytes: ByteArray = pdfBytes): Uri {
        val uri = Uri.parse("content://test/$name")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
        // 真实 SAF 提供 DISPLAY_NAME/SIZE 两列；Robolectric 的 registerInputStream 不填元数据，
        // 故用 setCursor 显式喂一行，使 FileTypes.normalize 走「显示名后缀优先」的真实路径。
        val cursor = org.robolectric.fakes.RoboCursor().apply {
            setColumnNames(listOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
            setResults(arrayOf(arrayOf<Any?>(name, bytes.size.toLong())))
        }
        shadowOf(context.contentResolver).setCursor(uri, cursor)
        return uri
    }

    @Test
    fun import_success_createsRecordFileAndThumbnail() = runTest {
        val uri = registerPdf()
        val result = importer.import(uri)

        assertEquals(DocumentImporter.Status.IMPORTED, result.status)
        val id = result.documentId!!
        val record = db.documentDao().findById(id)!!
        assertEquals("pdf", record.fileType)
        assertEquals(3, record.pageCount)
        assertEquals("page", record.progressType)
        assertFalse(record.needsPassword)
        // relPath 与记录 id 一致，物理文件存在
        assertEquals("$id.pdf", record.relPath)
        assertTrue(storage.documentExists(record.relPath))
        // 缩略图文件存在（测试 storage 的 thumbDir 在临时目录树内）
        assertNotNull(record.thumbnailRelPath)
        assertTrue(File(storage.thumbDir(), "$id.png").exists())
    }

    @Test
    fun import_duplicate_skippedAndCleanup_forceReimports() = runTest {
        val first = importer.import(registerPdf("a.pdf"))
        assertEquals(DocumentImporter.Status.IMPORTED, first.status)

        // 不同 uri、同内容 → 同 hash → 重复跳过，指向已有文档；半成品已删（库目录仍只有 1 个文件）
        val second = importer.import(registerPdf("b.pdf"))
        assertEquals(DocumentImporter.Status.DUPLICATE_SKIPPED, second.status)
        assertEquals(first.documentId, second.documentId)
        assertEquals(1, libraryDir.listFiles()!!.size)

        // force 重导成功（新 id 独立副本）
        val forced = importer.import(registerPdf("c.pdf"), force = true)
        assertEquals(DocumentImporter.Status.IMPORTED, forced.status)
        assertTrue(forced.documentId != first.documentId)
    }

    @Test
    fun import_corrupt_rejectedAndNoRecordAndFileDeleted() = runTest {
        engine.behavior = FakeEngine.Behavior.CORRUPT

        val result = importer.import(registerPdf())

        assertEquals(DocumentImporter.Status.CORRUPT_REJECTED, result.status)
        // T6/M11：明细 reason 走白名单，引擎消息（"bad"）不得直出
        assertEquals("文件损坏", result.reason)
        // 引擎收到过打开请求，且对应半成品文件已被清理
        val opened = File(engine.lastOpenedPath!!)
        assertFalse(opened.exists())
        // 无库记录
        assertNull(repository.findByHash(sha256Of(pdfBytes)))
    }

    @Test
    fun import_tooLargeText_keepsActionableReasonFromWhitelist() = runTest {
        engine.behavior = FakeEngine.Behavior.TOO_LARGE

        val result = importer.import(registerPdf("big.txt", "hello".toByteArray()))

        assertEquals(DocumentImporter.Status.CORRUPT_REJECTED, result.status)
        // 白名单放行唯一带行动指引的文案（分卷/压缩版），其余损坏原因统一「文件损坏」
        assertEquals("文本过大（>16MB），请使用分卷或压缩版", result.reason)
    }

    @Test
    fun import_unexpectedFailure_reasonIsFixedAndHidesPath() = runTest {
        engine.behavior = FakeEngine.Behavior.BOOM

        val result = importer.import(registerPdf())

        assertEquals(DocumentImporter.Status.FAILED, result.status)
        // T6/M11：异常消息含内部物理路径，用户明细只给固定文案（细节仅 debug 日志）
        assertEquals("导入失败", result.reason)
    }

    @Test
    fun import_passwordMarked_recordWithoutThumbnail() = runTest {
        engine.behavior = FakeEngine.Behavior.PASSWORD

        val result = importer.import(registerPdf())

        assertEquals(DocumentImporter.Status.IMPORTED_PASSWORD_MARKED, result.status)
        val record = db.documentDao().findById(result.documentId!!)!!
        assertTrue(record.needsPassword)
        assertEquals(-1, record.pageCount)
        assertNull(record.thumbnailRelPath)
        assertTrue(storage.documentExists(record.relPath))   // 加密文档文件保留，首开兜底
    }

    // ---- M9：多格式分流 ----

    @Test
    fun import_epub_routesCfiProgress() = runTest {
        val result = importer.import(registerPdf("book.epub", "PK\u0003\u0004 fake-epub".toByteArray()))

        assertEquals(DocumentImporter.Status.IMPORTED, result.status)
        val record = db.documentDao().findById(result.documentId!!)!!
        assertEquals("epub", record.fileType)
        assertEquals("cfi", record.progressType)          // 流式默认 cfi
        assertEquals("${record.id}.epub", record.relPath)  // 物理扩展名随 fileType
        assertEquals(-1, record.pageCount)                // 流式重排 pageCount 恒 -1 不落库
    }

    @Test
    fun import_cbz_routesPageProgress() = runTest {
        val result = importer.import(registerPdf("comic.cbz", "PK\u0003\u0004 fake-cbz".toByteArray()))

        assertEquals(DocumentImporter.Status.IMPORTED, result.status)
        val record = db.documentDao().findById(result.documentId!!)!!
        assertEquals("cbz", record.fileType)
        assertEquals("page", record.progressType)          // 页码式
    }

    @Test
    fun import_xps_viaOxpsExtension_routesXps() = runTest {
        // .oxps 扩展名应归一为 fileType=xps
        val result = importer.import(registerPdf("doc.oxps", "PK\u0003\u0004 fake-oxps".toByteArray()))

        assertEquals(DocumentImporter.Status.IMPORTED, result.status)
        val record = db.documentDao().findById(result.documentId!!)!!
        assertEquals("xps", record.fileType)
        assertEquals("page", record.progressType)
    }

    @Test
    fun import_unsupported_rejectedNoCopyNoRecord() = runTest {
        // 未注册格式：导入前归一化即拒收，不复制、不入库、不调引擎
        val result = importer.import(registerPdf("evil.docx", "PK\u0003\u0004 fake-docx".toByteArray()))

        assertEquals(DocumentImporter.Status.UNSUPPORTED, result.status)
        assertNull(result.documentId)
        assertNull(engine.lastOpenedPath)                       // 未触达引擎
        assertTrue(libraryDir.listFiles()!!.isEmpty())          // 无物理副本
        assertNull(repository.findByHash(sha256Of("PK\u0003\u0004 fake-docx".toByteArray())))
    }

    private fun sha256Of(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}

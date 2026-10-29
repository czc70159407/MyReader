package com.myreader.feature.library

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.room.Room
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
import com.myreader.core.document.ProgressPayload
import com.myreader.core.document.SimpleDocumentEngineRouter
import com.myreader.core.model.ReaderMode
import com.myreader.core.storage.DocumentStorage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
 * DirectoryImporter 编排单测：真 storage + 真 inMemory Repository + Fake 引擎（接口隔离，
 * libmupdf_java.so 无法在 JVM 加载）+ 内存目录树源（SAF 无法在 Robolectric 伪造）。
 * 覆盖：目录镜像 / 重复跳过 / 同名重命名 / 损坏与不支持计数 / 空目录。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DirectoryImporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: MyReaderDatabase
    private lateinit var repository: LibraryRepositoryImpl
    private lateinit var importer: DocumentImporter
    private lateinit var directoryImporter: DirectoryImporter

    /** 可切换行为的 Fake 引擎：行为由测试置位（存储路径是 <id>.pdf，无法按原文件名判损坏） */
    private class FakeEngine(var behavior: Behavior = Behavior.OK) : DocumentEngine {
        enum class Behavior { OK, CORRUPT }

        override fun open(path: String, fileType: String, password: String?): Document {
            if (behavior == Behavior.CORRUPT) throw CorruptDocumentException("bad")
            return FakeDocument(reflowable = fileType == "epub")
        }
    }

    private class FakeDocument(override val reflowable: Boolean = false) : Document {
        override val pageCount: Int = 3
        override fun gotoPage(page: Int) {}
        override suspend fun pageSize(page: Int) = androidx.compose.ui.geometry.Size(595f, 842f)
        override fun drawPage(bitmap: Bitmap, page: Int, width: Int, height: Int, patchX: Int, patchY: Int, patchW: Int, patchH: Int) {}
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

    private lateinit var engine: FakeEngine

    private val pdfBytes = "%PDF-1.4 fake".toByteArray()

    /** 内存目录树：目录名即 docId；documentUri 走 content://test/<name>，与注册的流/cursor 对齐 */
    private class FakeTreeSource(
        private val tree: Map<String, List<DirectoryTreeSource.Entry>>,
    ) : DirectoryTreeSource {
        override val rootDocId = "root"
        override fun children(docId: String) = tree[docId] ?: emptyList()
        override fun documentUri(docId: String): Uri = Uri.parse("content://test/$docId")
    }

    private fun file(name: String, size: Long? = 16L, mime: String? = null) =
        DirectoryTreeSource.Entry(name, name, mime, size, isDirectory = false)

    private fun dir(name: String) =
        DirectoryTreeSource.Entry(name, name, "vnd.android.document/directory", null, isDirectory = true)

    /** 注册 SAF 可读内容（流 + DISPLAY_NAME/SIZE 元数据，同 DocumentImporterTest 手法） */
    private fun registerDoc(name: String, bytes: ByteArray = pdfBytes): Uri {
        val uri = Uri.parse("content://test/$name")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
        val cursor = org.robolectric.fakes.RoboCursor().apply {
            setColumnNames(listOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
            setResults(arrayOf(arrayOf<Any?>(name, bytes.size.toLong())))
        }
        shadowOf(context.contentResolver).setCursor(uri, cursor)
        return uri
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MyReaderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val storage = DocumentStorage(tmp.newFolder("library"))
        repository = LibraryRepositoryImpl(db, db.folderDao(), db.documentDao(), storage, dispatchers)
        engine = FakeEngine()
        val router: DocumentEngineRouter = SimpleDocumentEngineRouter(
            engines = mapOf(
                "pdf" to engine,
                "epub" to engine,
                "txt" to engine,
            ),
            viewers = mapOf(
                "pdf" to FakeViewer(),
                "epub" to FakeViewer(),
                "txt" to FakeViewer(),
            ),
        )
        importer = DocumentImporter(context, router, storage, repository, dispatchers)
        directoryImporter = DirectoryImporter(context, repository, importer, dispatchers)
    }

    @After
    fun tearDown() = db.close()

    /** 测试统一走内部接缝 importTree（内存树源），绕过 SAF Uri 解析 */
    @Test
    fun importDirectory_mirrorsFolderStructure() = runTest {
        // 注册树中所有文件内容（内容互异：同内容会命中 hash 查重被跳过）
        registerDoc("a.pdf"); registerDoc("b.pdf", "%PDF-1.4 b".toByteArray()); registerDoc("c.epub", "%EPUB fake".toByteArray())
        val source = FakeTreeSource(
            mapOf(
                "root" to listOf(file("a.pdf"), dir("docs")),
                "docs" to listOf(file("b.pdf"), dir("sub")),
                "sub" to listOf(file("c.epub", mime = "application/epub+zip")),
            ),
        )

        val summary = directoryImporter.importTree(source, targetFolderId = null)

        assertEquals(3, summary.total)
        assertEquals(3, summary.imported)
        assertEquals(2, summary.foldersCreated)
        assertEquals(0, summary.duplicateSkipped)
        assertEquals(0, summary.failed)
        // 结构镜像：docs 与 docs/sub 已建库；文档落在正确目录
        val folders = repository.observeAllFolders().first()
        val docsFolder = folders.single { it.name == "docs" }
        val subFolder = folders.single { it.name == "sub" }
        assertEquals(docsFolder.id, subFolder.parentId)
        assertEquals(null, docsFolder.parentId)
        val rootDocs = repository.docsIn(null, com.myreader.core.model.DocSort.TITLE).first()
        assertEquals(listOf("a.pdf"), rootDocs.map { it.title })
        assertEquals(1, repository.docsIn(docsFolder.id, com.myreader.core.model.DocSort.TITLE).first().size)
        assertEquals(1, repository.docsIn(subFolder.id, com.myreader.core.model.DocSort.TITLE).first().size)
        // epub 落 cfi 进度类型
        assertEquals("cfi", repository.docsIn(subFolder.id, com.myreader.core.model.DocSort.TITLE).first().single().progressType)
    }

    @Test
    fun importDirectory_secondRunSkipsDuplicatesAndReusesFolders() = runTest {
        registerDoc("a.pdf")
        val source = FakeTreeSource(mapOf("root" to listOf(file("a.pdf"))))
        val first = directoryImporter.importTree(source, targetFolderId = null)
        assertEquals(1, first.imported)
        assertEquals(0, first.foldersCreated)

        // 同内容二次导入（新树源实例）：全部命中 hash 跳过；目录已存在 → 复用。
        // 注意：Robolectric 的 registerInputStream 流为一次性实例，二次导入前需重新注册
        registerDoc("a.pdf")
        val second = directoryImporter.importTree(source, targetFolderId = null)
        assertEquals(1, second.duplicateSkipped)
        assertEquals(0, second.imported)
        assertEquals(1, second.issues.size)
        assertTrue(second.issues.single().reason.contains("已存在"))
        // 库中仍只有一条记录
        assertEquals(1, repository.docsIn(null, com.myreader.core.model.DocSort.TITLE).first().size)
    }

    @Test
    fun importDirectory_renamesWhenTitleCollidesWithExistingDoc() = runTest {
        // 库中先有同名但不同内容的 book.pdf（预置）
        importer.import(registerDoc("book.pdf", "%PDF-1.4 OTHER".toByteArray()))
        assertEquals(listOf("book.pdf"), repository.docsIn(null, com.myreader.core.model.DocSort.TITLE).first().map { it.title })

        // 目录导入另一个 book.pdf（不同内容 → 不触发 hash 跳过）
        registerDoc("book.pdf")
        val source = FakeTreeSource(mapOf("root" to listOf(file("book.pdf"))))
        val summary = directoryImporter.importTree(source, targetFolderId = null)

        assertEquals(1, summary.imported)
        assertEquals(1, summary.renamedForConflict)
        val titles = repository.docsIn(null, com.myreader.core.model.DocSort.TITLE).first().map { it.title }.sorted()
        assertEquals(listOf("book (1).pdf", "book.pdf"), titles)
    }

    @Test
    fun importDirectory_countsCorruptAndUnsupported() = runTest {
        // 全损坏引擎：good/corrupt 均被拒收；notes.log/pic.jpg 扫描期即拒（不进导入）
        engine.behavior = FakeEngine.Behavior.CORRUPT
        registerDoc("good.pdf")
        registerDoc("corrupt.pdf", "%PDF-1.4 corrupt".toByteArray())   // 与 good 内容互异，避免 hash 查重先行
        val source = FakeTreeSource(
            mapOf(
                "root" to listOf(
                    file("good.pdf"),
                    file("corrupt.pdf"),
                    file("notes.log"),
                    file("pic.jpg", mime = "image/jpeg"),
                ),
            ),
        )
        val summary = directoryImporter.importTree(source, targetFolderId = null)

        assertEquals(2, summary.total)                 // good + corrupt 进入导入；log/jpg 扫描期即拒
        assertEquals(0, summary.imported)
        assertEquals(2, summary.corruptRejected)
        assertEquals(2, summary.unsupported)           // 扫描期不支持 + 导入期统计合并
        // 每个被拒文件一条 issue（T6：reason 走白名单固定文案；log/jpg 扫描期拒收不计 issue）
        assertEquals(2, summary.issues.size)
        assertTrue(summary.issues.all { it.path.endsWith(".pdf") })
        assertTrue(summary.issues.all { it.reason == "文件损坏" })
        // 损坏文件不留半成品：库内 0 条
        assertEquals(0, repository.docsIn(null, com.myreader.core.model.DocSort.TITLE).first().size)
    }

    @Test
    fun importDirectory_dropsFolderWhenAllItsFilesFail() = runTest {
        // 目录镜像在导入前创建；其内部文件全部失败时不应留下空目录
        engine.behavior = FakeEngine.Behavior.CORRUPT
        registerDoc("bad.pdf", "%PDF-1.4 bad".toByteArray())
        val source = FakeTreeSource(
            mapOf(
                "root" to listOf(dir("broken")),
                "broken" to listOf(file("bad.pdf")),
            ),
        )
        val summary = directoryImporter.importTree(source, targetFolderId = null)

        assertEquals(1, summary.total)
        assertEquals(1, summary.corruptRejected)
        assertEquals(0, summary.imported)
        assertEquals(0, summary.foldersCreated)   // 创建后被清理扣减
        assertTrue(repository.observeAllFolders().first().isEmpty())
    }

    @Test
    fun importDirectory_emptyDirectoryReportsIssue() = runTest {
        val source = FakeTreeSource(mapOf("root" to listOf(file("readme.md"), file("photo.png", mime = "image/png"))))
        val summary = directoryImporter.importTree(source, targetFolderId = null)

        assertEquals(0, summary.total)
        assertEquals(0, summary.imported)
        assertEquals(2, summary.unsupported)
        // T6：total=0 时该目录走 Snackbar（err_pick_dir_empty），明细不再放语义错位的伪路径条目
        assertTrue(summary.issues.isEmpty())
    }

    // ---- 取消（T3/D3）----

    /** 导入阶段取消：已导入项保留、未处理项不入库，取消前交出部分汇总且 CancellationException 继续传播 */
    @Test
    fun importTree_cancelDuringImport_keepsImportedDocsAndReportsPartialSummary() = runTest {
        registerDoc("a.pdf")
        registerDoc("b.pdf", "%PDF-1.4 b".toByteArray())
        val source = FakeTreeSource(mapOf("root" to listOf(file("a.pdf"), file("b.pdf"))))
        val partials = mutableListOf<DirectoryImportSummary>()
        lateinit var job: Job
        job = launch {
            directoryImporter.importTree(
                source,
                targetFolderId = null,
                // 第二个文件的进度事件即触发取消：此刻 a.pdf 已导入、b.pdf 尚未开始
                onProgress = { p ->
                    if (p.phase == DirectoryImportProgress.Phase.IMPORTING && p.current == 2) job.cancel()
                },
                onPartialSummary = { partials += it },
            )
        }
        job.join()

        assertEquals(1, partials.size)
        assertTrue(partials.single().cancelled)
        assertEquals(1, partials.single().imported)
        assertEquals(0, partials.single().failed)
        // 已导入项保留；未处理项不入库
        val titles = repository.docsIn(null, com.myreader.core.model.DocSort.TITLE).first().map { it.title }
        assertEquals(listOf("a.pdf"), titles)
    }

    /** 扫描阶段取消：不创建目录、不入库，部分汇总 imported = 0 */
    @Test
    fun importTree_cancelDuringScan_importsNothing() = runTest {
        registerDoc("a.pdf")
        val source = FakeTreeSource(mapOf("root" to listOf(file("a.pdf"))))
        val partials = mutableListOf<DirectoryImportSummary>()
        lateinit var job: Job
        job = launch {
            directoryImporter.importTree(
                source,
                targetFolderId = null,
                onProgress = { p ->
                    if (p.phase == DirectoryImportProgress.Phase.SCANNING) job.cancel()
                },
                onPartialSummary = { partials += it },
            )
        }
        job.join()

        assertEquals(1, partials.size)
        assertTrue(partials.single().cancelled)
        assertEquals(0, partials.single().imported)
        assertTrue(repository.docsIn(null, com.myreader.core.model.DocSort.TITLE).first().isEmpty())
        assertTrue(repository.observeAllFolders().first().isEmpty())
    }
}

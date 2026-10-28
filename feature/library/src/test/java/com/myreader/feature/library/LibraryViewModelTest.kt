package com.myreader.feature.library

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.Composable
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.myreader.core.common.DispatcherProvider
import com.myreader.core.data.db.MyReaderDatabase
import com.myreader.core.data.prefs.LibraryPrefs
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
import com.myreader.core.model.DocSort
import com.myreader.core.model.LibraryItem
import com.myreader.core.model.ReaderMode
import com.myreader.core.storage.DocumentStorage
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.rules.TestWatcher
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** viewModelScope 绑定 Dispatchers.Main，测试替换为 test dispatcher（Unconfined：协程同步推进到挂起点） */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val testDispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {
    /** 测试用 runTest(testScheduler) 共享调度器，runTest 挂起时自动推进 VM 协程 */
    val testScheduler get() = testDispatcher.scheduler

    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

/**
 * LibraryViewModel 单测：目录导航状态 + 目录/文档操作包装 + 导入目标目录 + 重复逐个确认。
 * 真 inMemory Room Repository + 真 storage + Fake 引擎（libmupdf_java.so 无法 JVM 加载）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LibraryViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var context: Context
    private lateinit var db: MyReaderDatabase
    private lateinit var storage: DocumentStorage
    private lateinit var repository: LibraryRepositoryImpl
    private lateinit var importer: DocumentImporter
    private lateinit var directoryImporter: DirectoryImporter
    private lateinit var prefs: LibraryPrefs
    private lateinit var viewModel: LibraryViewModel

    private class FakeEngine(var behavior: Behavior) : DocumentEngine {
        enum class Behavior { OK, CORRUPT, PASSWORD }

        override fun open(path: String, fileType: String, password: String?): Document {
            when (behavior) {
                Behavior.CORRUPT -> throw CorruptDocumentException("bad")
                Behavior.PASSWORD -> throw PasswordRequiredException("encrypted")
                Behavior.OK -> {}
            }
            return FakeDocument()
        }
    }

    private class FakeDocument : Document {
        override val pageCount: Int = 3
        override fun gotoPage(page: Int) {}
        override suspend fun pageSize(page: Int): androidx.compose.ui.geometry.Size =
            androidx.compose.ui.geometry.Size(595f, 842f)
        override fun drawPage(bitmap: Bitmap, page: Int, width: Int, height: Int, patchX: Int, patchY: Int, patchW: Int, patchH: Int) {}
        override suspend fun outline(): List<OutlineEntry> = emptyList()
        override fun close() {}
    }

    private class FakeViewer : DocumentViewerProvider {
        @Composable
        override fun Content(doc: Document, initialProgress: ProgressPayload, mode: ReaderMode, onProgressChanged: (ProgressPayload) -> Unit, onPageCountChanged: (Int) -> Unit, modifier: Modifier) = Unit

        override suspend fun thumbnail(doc: Document): ImageBitmap =
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    private val dispatchers = object : DispatcherProvider {
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private val pdfBytes = "%PDF-1.4 fake".toByteArray()

    private fun registerPdf(name: String, bytes: ByteArray = pdfBytes): Uri {
        val uri = Uri.parse("content://test/$name")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
        // 真实 SAF 提供 DISPLAY_NAME/SIZE；Robolectric registerInputStream 不填元数据，
        // 显式 setCursor 喂一行，使 DocumentImporter 的 FileTypes.normalize 走「显示名后缀优先」真实路径
        val cursor = org.robolectric.fakes.RoboCursor().apply {
            setColumnNames(listOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE))
            setResults(arrayOf(arrayOf<Any?>(name, bytes.size.toLong())))
        }
        shadowOf(context.contentResolver).setCursor(uri, cursor)
        return uri
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MyReaderDatabase::class.java).allowMainThreadQueries().build()
        storage = DocumentStorage(tmp.newFolder("library"))
        repository = LibraryRepositoryImpl(db, db.folderDao(), db.documentDao(), storage, dispatchers)
        val router = SimpleDocumentEngineRouter(
            engines = mapOf("pdf" to FakeEngine(FakeEngine.Behavior.OK)),
            viewers = mapOf("pdf" to FakeViewer()),
        )
        importer = DocumentImporter(context, router, storage, repository, dispatchers)
        directoryImporter = DirectoryImporter(context, repository, importer, dispatchers)
        // DataStore 测试实例：scope 共享 testScheduler（无参 UnconfinedTestDispatcher 会新建独立调度器，
        // 虚拟时间推不动其内部协程）；每测试独立文件，避开同名多实例崩溃
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(UnconfinedTestDispatcher(mainDispatcherRule.testScheduler) + Job()),
        ) { File(tmp.newFolder("prefs"), "library.preferences_pb") }
        prefs = LibraryPrefs(dataStore)
        viewModel = LibraryViewModel(SavedStateHandle(emptyMap()), repository, importer, directoryImporter, prefs)   // 根目录
    }

    @After
    fun tearDown() = db.close()

    /** VM 协程经 Main（共享 scheduler）排队，操作后显式推进到静止 */
    private fun idle() = mainDispatcherRule.testScheduler.advanceUntilIdle()

    /** 真实时钟轮询指定目录文档数（Room suspend 写库走真实线程，turbine 虚拟超时会抢跑） */
    private suspend fun awaitDocCount(folderId: Long?, expected: Int, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (db.documentDao().docsInByTitle(folderId).first().size == expected) return
            delay(10)
        }
        throw AssertionError("等待文档数 $expected 超时")
    }

    /** 真实时钟轮询等待重复确认状态置位（不用于 withTimeout/first——runTest 虚拟时间会抢跑） */
    private suspend fun awaitPendingDuplicate() {
        val deadline = System.currentTimeMillis() + 5_000
        while (viewModel.pendingDuplicate.value == null) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("重复确认状态未置位")
            delay(10)
        }
    }

    /** 校验汇总消息文案（调用前先以 awaitDocCount/awaitPendingDuplicate 确保消息已缓冲） */
    private suspend fun awaitImportMessage(expected: String) {
        viewModel.messages.test {
            assertEquals(expected, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---- 真实时钟轮询断言（消息由 Room 真实线程恢复链发出，时序不定，用收集列表而非 turbine 队头）----

    private val receivedMessages = mutableListOf<String>()

    private fun kotlinx.coroutines.test.TestScope.startCollectingMessages() {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.messages.collect { receivedMessages.add(it) }
        }
    }

    private suspend fun awaitMessage(expected: String, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (receivedMessages.contains(expected)) return
            delay(10)
        }
        throw AssertionError("未收到消息「$expected」，实际收到: $receivedMessages")
    }

    private suspend fun awaitFolderChildren(expected: Int, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (db.folderDao().children(null).first().size == expected) return
            delay(10)
        }
        throw AssertionError("等待根级目录数 $expected 超时")
    }

    // ---- M6 排序 / 搜索 / 最近阅读 ----

    /** 让 stateIn 上游保持活跃（WhileSubscribed 无收集者不启动上游，仅读 .value 永远是初值） */
    private fun TestScope.keepAlive(vararg flows: Flow<*>) {
        flows.forEach { flow ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect {} }
        }
    }

    /** 轮询等待文档顺序（debounce/Room 真实线程发射，advanceUntilIdle 只推虚拟任务，M5 教训）。
     *  lambda 置最后参数以支持尾随 lambda 调用 */
    private suspend fun awaitDocOrder(
        vm: LibraryViewModel = viewModel,
        timeoutMs: Long = 5_000,
        expected: (List<String>) -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            mainDispatcherRule.testScheduler.advanceUntilIdle()
            if (expected(vm.documents.value.map { it.title })) return
            delay(10)
        }
        throw AssertionError("等待文档顺序超时，当前: ${vm.documents.value.map { it.title }}")
    }

    /** 轮询等待偏好写入（DataStore edit 走共享 scheduler，写文件为真实 IO） */
    private suspend fun awaitPrefsSort(expected: DocSort, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            mainDispatcherRule.testScheduler.advanceUntilIdle()
            if (prefs.docSort.first() == expected) return
            delay(10)
        }
        throw AssertionError("排序偏好未写入，当前: ${prefs.docSort.first()}")
    }

    /** 搜索结果轮询：先推进虚拟时间跨过 300ms 防抖窗口，再等 Room 真实线程发射（lambda 置最后参数） */
    private suspend fun awaitSearchResult(timeoutMs: Long = 5_000, predicate: (List<LibraryItem>?) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            mainDispatcherRule.testScheduler.advanceTimeBy(1_000)
            mainDispatcherRule.testScheduler.runCurrent()
            if (predicate(viewModel.searchResults.value)) return
            delay(10)
        }
        throw AssertionError("等待搜索结果超时，当前值: ${viewModel.searchResults.value}")
    }

    /** 轮询等待最近阅读列表（lambda 置最后参数） */
    private suspend fun awaitRecent(timeoutMs: Long = 5_000, expected: (List<LibraryItem>) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            mainDispatcherRule.testScheduler.advanceUntilIdle()
            if (expected(viewModel.recent.value)) return
            delay(10)
        }
        throw AssertionError("等待最近阅读超时，当前: ${viewModel.recent.value.map { it.title }}")
    }

    private suspend fun addDoc(title: String, hash: String, fileSize: Long): Long = repository.addDocument(
        folderId = null, title = title, relPath = "$hash.pdf",
        fileType = "pdf", mimeType = null, sourceUri = null,
        fileSize = fileSize, contentHash = hash, pageCount = 1,
    )

    @Test
    fun importPick_confirm_importsIntoSelectedFolder() = runTest(mainDispatcherRule.testScheduler) {
        val targetId = repository.createFolder(null, "目标")
        viewModel.onUrisPicked(listOf(registerPdf("a.pdf")))
        assertNotNull(viewModel.pendingImportUris.value)   // 先弹目录选择，不直接导入

        viewModel.confirmImportPick(targetId)
        awaitDocCount(targetId, 1)
        awaitImportMessage("导入 1 个")   // 消息已缓冲，立即校验文案

        assertTrue(viewModel.pendingImportUris.value == null)
        db.documentDao().docsInByTitle(targetId).test {
            val docs = awaitItem()
            assertEquals(1, docs.size)
            assertEquals(targetId, docs.first().folderId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun duplicate_confirm_forcesReimport() = runTest(mainDispatcherRule.testScheduler) {
        viewModel.onUrisPicked(listOf(registerPdf("a.pdf")))
        viewModel.confirmImportPick(null)
        awaitDocCount(null, 1)   // 第一次导入完成
        awaitImportMessage("导入 1 个")

        viewModel.onUrisPicked(listOf(registerPdf("b.pdf")))   // 同内容 → 重复
        viewModel.confirmImportPick(null)
        awaitPendingDuplicate()   // 循环挂起等用户决策

        // Robolectric 注册流为单例（copyIn 已消费），force 重导前重新注册
        val pendingUri = viewModel.pendingDuplicate.value!!.first
        shadowOf(context.contentResolver).registerInputStream(pendingUri, ByteArrayInputStream(pdfBytes))
        viewModel.confirmDuplicateImport()   // 确认 → force 重导
        awaitDocCount(null, 2)   // 原记录 + force 新记录（真实等待）
        awaitImportMessage("导入 1 个")

        assertNull(viewModel.pendingDuplicate.value)
    }

    @Test
    fun duplicate_dismiss_skips() = runTest(mainDispatcherRule.testScheduler) {
        viewModel.onUrisPicked(listOf(registerPdf("a.pdf")))
        viewModel.confirmImportPick(null)
        awaitDocCount(null, 1)
        awaitImportMessage("导入 1 个")

        viewModel.onUrisPicked(listOf(registerPdf("b.pdf")))
        viewModel.confirmImportPick(null)
        awaitPendingDuplicate()

        viewModel.dismissDuplicate()   // 跳过（无重导，循环同步结束发消息）
        awaitImportMessage("未导入任何文档")

        db.documentDao().docsInByTitle(null).test {
            assertEquals(1, awaitItem().size)   // 仍只有原记录
            cancelAndIgnoreRemainingEvents()
        }
        assertNull(viewModel.pendingDuplicate.value)
    }

    /** T4/D5：多选且存在跳过/不支持 → 弹汇总对话框（与目录导入同构），不另发 Snackbar */
    @Test
    fun importUris_multiSelectWithSkips_showsSummaryDialogInsteadOfSnackbar() =
        runTest(mainDispatcherRule.testScheduler) {
            startCollectingMessages()
            // 预置 b.pdf 入库 → 第二次导入同内容命中重复（Robolectric 流一次性，重复前重新注册）。
            // 各文件内容必须互异，否则 a 也命中 hash 查重再次挂起（内容 hash 查重按内容不按文件名）
            importer.import(registerPdf("b.pdf"))
            val uriA = registerPdf("a.pdf", "%PDF-1.4 a".toByteArray())
            val uriB = registerPdf("b.pdf")
            val uriC = registerPdf("c.log", "%PDF-1.4 c".toByteArray())   // 未登记格式 → UNSUPPORTED

            viewModel.importUris(listOf(uriA, uriB, uriC), targetFolderId = null)
            awaitPendingDuplicate()   // b 命中重复，循环挂起等决策
            viewModel.dismissDuplicate()

            // 完成后弹汇总对话框
            val deadline = System.currentTimeMillis() + 5_000
            while (viewModel.importSummary.value == null) {
                idle()
                if (System.currentTimeMillis() > deadline) throw AssertionError("汇总对话框未弹出")
                delay(10)
            }
            val ui = viewModel.importSummary.value!!
            assertEquals("导入完成", ui.title)
            assertEquals(3, ui.summary.total)
            assertEquals(1, ui.summary.imported)
            assertEquals(1, ui.summary.duplicateSkipped)
            assertEquals(1, ui.summary.unsupported)
            assertEquals(2, ui.summary.issues.size)
            assertTrue(ui.summary.issues.any { it.reason.contains("已存在") })
            assertTrue(ui.summary.issues.any { it.reason.contains("暂不支持") })
            // 对话框路径不另发 Snackbar
            assertTrue(receivedMessages.isEmpty())
        }

    /** review H2/H3：单文件导入 FAILED 走 Snackbar（不崩、不弹对话框），文案与 summary_failed 一致 */
    @Test
    fun importUris_singleFileFailed_snackbar() = runTest(mainDispatcherRule.testScheduler) {
        startCollectingMessages()
        // 注册元数据（格式命中 pdf）但不注册输入流 → openInputStream 为 null → FAILED
        val ghost = Uri.parse("content://test/ghost.pdf")
        val cursor = org.robolectric.fakes.RoboCursor().apply {
            setColumnNames(listOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE))
            setResults(arrayOf(arrayOf<Any?>("ghost.pdf", 1024L)))
        }
        shadowOf(context.contentResolver).setCursor(ghost, cursor)

        viewModel.importUris(listOf(ghost), targetFolderId = null)
        awaitMessage("1 个导入失败")
        assertNull(viewModel.importSummary.value)
    }

    /** T4/D5 边界：多选全部成功仍走 Snackbar（不弹对话框） */
    @Test
    fun importUris_multiSelectAllSuccess_snackbar() = runTest(mainDispatcherRule.testScheduler) {
        startCollectingMessages()
        val uriA = registerPdf("a.pdf", "%PDF-1.4 a".toByteArray())
        val uriB = registerPdf("b.pdf", "%PDF-1.4 b".toByteArray())
        viewModel.importUris(listOf(uriA, uriB), targetFolderId = null)
        awaitMessage("导入 2 个")
        assertNull(viewModel.importSummary.value)
    }

    @Test
    fun createSubFolder_persistsAndNotifies() = runTest(mainDispatcherRule.testScheduler) {
        viewModel.createSubFolder(null, "新文件夹")
        idle()

        viewModel.messages.test {
            assertEquals("已创建文件夹「新文件夹」", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        db.folderDao().children(null).test {
            assertEquals(listOf("新文件夹"), awaitItem().map { it.name })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun moveFolder_rejectsCycle_postsError() = runTest(mainDispatcherRule.testScheduler) {
        val a = repository.createFolder(null, "a")
        val b = repository.createFolder(a, "b")

        viewModel.moveFolder(a, b)   // 移到自己的后代

        viewModel.messages.test {
            assertEquals("不能把目录移动到自己的子树内", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        // 结构未变
        assertEquals(a, db.folderDao().findById(b)!!.parentId)
    }

    @Test
    fun createSubFolder_rejectsDuplicateName_sameParent() = runTest(mainDispatcherRule.testScheduler) {
        startCollectingMessages()

        viewModel.createSubFolder(null, "文档")
        awaitFolderChildren(1)   // 真实等待首次落库，避免第二次查重在 insert 前执行

        viewModel.createSubFolder(null, "文档")   // 同父重名
        idle()

        awaitMessage("该目录下已存在同名文件夹")
        // 仅 1 条落库
        assertEquals(1, db.folderDao().children(null).first().size)
    }

    @Test
    fun renameDocument_rejectsDuplicateTitle_sameFolder() = runTest(mainDispatcherRule.testScheduler) {
        startCollectingMessages()
        val id1 = repository.addDocument(
            folderId = null, title = "same.pdf", relPath = "1.pdf",
            fileType = "pdf", mimeType = null, sourceUri = null,
            fileSize = 1, contentHash = "h1", pageCount = 1,
        )
        repository.addDocument(
            folderId = null, title = "other.pdf", relPath = "2.pdf",
            fileType = "pdf", mimeType = null, sourceUri = null,
            fileSize = 1, contentHash = "h2", pageCount = 1,
        )

        viewModel.renameDocument(id1, "other.pdf")   // 同父重名
        idle()

        awaitMessage("该目录下已存在同名文档")
        // title 未变
        assertEquals("same.pdf", db.documentDao().findById(id1)!!.title)
    }

    @Test
    fun moveDocument_sameFolder_shortCircuitsWithNotice() = runTest(mainDispatcherRule.testScheduler) {
        startCollectingMessages()
        val targetId = repository.createFolder(null, "T")
        viewModel.onUrisPicked(listOf(registerPdf("a.pdf")))
        viewModel.confirmImportPick(targetId)
        awaitDocCount(targetId, 1)
        awaitMessage("导入 1 个")

        val docId = db.documentDao().docsInByTitle(targetId).first().first().id
        viewModel.moveDocument(docId, targetId)   // 移回当前所在目录：短路提示

        awaitMessage("文档已在该目录")
        // 不应出现「已移动文档」假反馈
        assertFalse(receivedMessages.contains("已移动文档"))
    }

    @Test
    fun deleteFolder_cascadesDocsToRoot_andNotifies() = runTest(mainDispatcherRule.testScheduler) {
        val fid = repository.createFolder(null, "f")
        viewModel.onUrisPicked(listOf(registerPdf("x.pdf")))
        viewModel.confirmImportPick(fid)   // 文档导入到 f
        awaitDocCount(fid, 1)
        awaitImportMessage("导入 1 个")     // 消息已缓冲，校验文案

        viewModel.deleteFolder(fid)
        idle()

        viewModel.messages.test {
            assertEquals("已删除文件夹（其中文档已移至根目录）", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        db.documentDao().docsInByTitle(null).test {
            val docs = awaitItem()
            assertEquals(1, docs.size)
            assertNull(docs.first().folderId)   // 文档回根
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun breadcrumb_showsAncestorChain_andFallsBackWhenFolderDeleted() = runTest(mainDispatcherRule.testScheduler) {
        val a = repository.createFolder(null, "A")
        val b = repository.createFolder(a, "B")
        val vm = LibraryViewModel(SavedStateHandle(mapOf("folderId" to b)), repository, importer, directoryImporter, prefs)

        vm.breadcrumb.test {
            assertEquals(emptyList<String>(), awaitItem())          // stateIn 初始值
            assertEquals(listOf("A", "B"), awaitItem())             // 祖先链 根→当前
            repository.deleteFolder(a)                              // 删祖先：B 级联删除 → 链回退空
            assertEquals(emptyList<String>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        // 根目录无层级
        viewModel.breadcrumb.test {
            assertEquals(emptyList<String>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---- M6 排序 / 搜索 / 最近阅读 ----

    @Test
    fun setSort_reordersImmediately_andPersistsAcrossVmRecreation() = runTest(mainDispatcherRule.testScheduler) {
        addDoc("a.pdf", "h1", fileSize = 100)
        addDoc("b.pdf", "h2", fileSize = 200)
        keepAlive(viewModel.documents, viewModel.sort)

        awaitDocOrder { it == listOf("a.pdf", "b.pdf") }   // 默认 TITLE

        viewModel.setSort(DocSort.SIZE)
        awaitDocOrder { it == listOf("b.pdf", "a.pdf") }   // 切换即时生效
        awaitPrefsSort(DocSort.SIZE)

        // 重建 VM（同偏好实例）：重启语义读回 SIZE
        val vm2 = LibraryViewModel(SavedStateHandle(emptyMap()), repository, importer, directoryImporter, prefs)
        keepAlive(vm2.documents)
        awaitDocOrder(vm2) { it == listOf("b.pdf", "a.pdf") }
    }

    @Test
    fun search_debounce_thenMatchesAcrossFolders() = runTest(mainDispatcherRule.testScheduler) {
        val fid = repository.createFolder(null, "F")
        viewModel.onUrisPicked(listOf(registerPdf("alpha.pdf")))
        viewModel.confirmImportPick(null)
        awaitDocCount(null, 1)
        awaitImportMessage("导入 1 个")
        // 内容须与 alpha 不同：相同字节 → contentHash 撞车 → 导入挂起等重复确认
        viewModel.onUrisPicked(listOf(registerPdf("beta.pdf", "%PDF-1.4 beta".toByteArray())))
        viewModel.confirmImportPick(fid)
        awaitDocCount(fid, 1)

        // Robolectric 无 SAF provider → 导入标题为「未命名文档」；重命名为确定性标题供搜索匹配
        db.documentDao().rename(db.documentDao().docsInByTitle(null).first().single().id, "alpha.pdf")
        db.documentDao().rename(db.documentDao().docsInByTitle(fid).first().single().id, "beta.pdf")
        keepAlive(viewModel.searchResults)

        assertNull(viewModel.searchResults.value)
        viewModel.onSearchQueryChange("alp")

        // 防抖窗口内不发射（仅推进 299ms）
        mainDispatcherRule.testScheduler.advanceTimeBy(299)
        mainDispatcherRule.testScheduler.runCurrent()
        assertNull(viewModel.searchResults.value)

        // 窗口过后 Room 真实线程发射 → 轮询等待；全库命中（beta 在子目录 F，未被命中但证明范围不限当前层）
        awaitSearchResult { it != null && it.size == 1 }
        assertEquals("alpha.pdf", viewModel.searchResults.value!!.single().title)
    }

    @Test
    fun search_blankQueryAndExit_fallBackToBrowse() = runTest(mainDispatcherRule.testScheduler) {
        viewModel.onUrisPicked(listOf(registerPdf("alpha.pdf")))
        viewModel.confirmImportPick(null)
        awaitDocCount(null, 1)
        keepAlive(viewModel.searchResults)

        // 搜索框展开态在 VM（跨导航一致）：openSearch/exitSearch 成对管理
        assertFalse(viewModel.searchOpen.value)
        viewModel.openSearch()
        assertTrue(viewModel.searchOpen.value)

        viewModel.onSearchQueryChange("   ")   // 纯空白：trim 后为空 → 非搜索态
        awaitSearchResult { it == null }

        viewModel.onSearchQueryChange("alp")
        awaitSearchResult { it != null }

        viewModel.exitSearch()
        awaitSearchResult { it == null }   // 退出搜索 → 回浏览态
        assertFalse(viewModel.searchOpen.value)
        assertEquals("", viewModel.searchQuery.value)
    }

    @Test
    fun recent_ordersByLastReadDesc_andEmptyForSubFolder() = runTest(mainDispatcherRule.testScheduler) {
        val idA = addDoc("a.pdf", "h1", fileSize = 1)
        val idB = addDoc("b.pdf", "h2", fileSize = 1)
        // 显式时间戳避免同毫秒排序歧义
        db.documentDao().updateProgress(idB, 1, 0, 1_000L)
        db.documentDao().updateProgress(idA, 0, 0, 2_000L)
        keepAlive(viewModel.recent)

        awaitRecent { it.map { item -> item.id } == listOf(idA, idB) }   // lastReadAt DESC

        // 非根目录 VM：recent 恒空（VM 层 gate，stateIn 初值即空）
        val fid = repository.createFolder(null, "F")
        val vm2 = LibraryViewModel(SavedStateHandle(mapOf("folderId" to fid)), repository, importer, directoryImporter, prefs)
        assertTrue(vm2.recent.value.isEmpty())
    }
}

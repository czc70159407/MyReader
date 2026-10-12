package com.myreader.feature.library

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
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
import com.myreader.core.data.repo.LibraryRepositoryImpl
import com.myreader.core.document.CorruptDocumentException
import com.myreader.core.document.Document
import com.myreader.core.document.DocumentEngine
import com.myreader.core.document.DocumentEngineRouter
import com.myreader.core.document.DocumentViewerProvider
import com.myreader.core.document.OutlineEntry
import com.myreader.core.document.PasswordRequiredException
import com.myreader.core.document.ProgressPayload
import com.myreader.core.document.ReaderMode
import com.myreader.core.document.SimpleDocumentEngineRouter
import com.myreader.core.storage.DocumentStorage
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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
        override fun drawPage(bitmap: Bitmap, page: Int, width: Int, height: Int, patchX: Int, patchY: Int, patchW: Int, patchH: Int) {}
        override suspend fun outline(): List<OutlineEntry> = emptyList()
        override fun close() {}
    }

    private class FakeViewer : DocumentViewerProvider {
        @Composable
        override fun Content(doc: Document, initialProgress: ProgressPayload, mode: ReaderMode, onProgressChanged: (ProgressPayload) -> Unit, modifier: Modifier) = Unit

        override suspend fun thumbnail(doc: Document): ImageBitmap =
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    private val dispatchers = object : DispatcherProvider {
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private val pdfBytes = "%PDF-1.4 fake".toByteArray()

    private fun registerPdf(name: String): Uri {
        val uri = Uri.parse("content://test/$name")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(pdfBytes))
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
        viewModel = LibraryViewModel(SavedStateHandle(emptyMap()), repository, importer)   // 根目录
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
        idle()

        viewModel.messages.test {
            assertEquals("不能把目录移动到自己的子树内", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        // 结构未变
        assertEquals(a, db.folderDao().findById(b)!!.parentId)
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
        val vm = LibraryViewModel(SavedStateHandle(mapOf("folderId" to b)), repository, importer)

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
}

package com.myreader.feature.reader

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.SavedStateHandle
import com.myreader.core.common.DispatcherProvider
import com.myreader.core.data.repo.LibraryRepository
import com.myreader.core.document.CorruptDocumentException
import com.myreader.core.document.Document
import com.myreader.core.document.DocumentEngine
import com.myreader.core.document.DocumentEngineRouter
import com.myreader.core.document.DocumentViewerProvider
import com.myreader.core.document.OutlineEntry
import com.myreader.core.document.PasswordRequiredException
import com.myreader.core.document.ProgressPayload
import com.myreader.core.document.ReaderMode
import com.myreader.core.model.LibraryItem
import com.myreader.core.storage.DocumentStorage
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()

    private lateinit var repo: FakeRepository
    private lateinit var storage: DocumentStorage
    private lateinit var viewModel: ReaderViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo = FakeRepository()
        storage = DocumentStorage(tmp.newFolder("library"))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(engine: DocumentEngine, item: LibraryItem): ReaderViewModel {
        repo.docItem = item
        val router = FakeRouter(engine, ThrowingViewer())
        return ReaderViewModel(
            savedStateHandle = SavedStateHandle(mapOf("documentId" to item.id)),
            repository = repo,
            storage = storage,
            router = router,
            dispatchers = TestDispatcherProvider(dispatcher),
        )
    }

    private fun item(
        id: Long = 1L,
        pageCount: Int = 5,
        currentPage: Int = 0,
        needsPassword: Boolean = false,
        thumbnailRelPath: String? = "thumb/$id.png",
    ) = LibraryItem(
        id = id,
        folderId = null,
        title = "测试文档",
        relPath = "$id.pdf",
        fileType = "pdf",
        mimeType = "application/pdf",
        sourceUri = null,
        fileSize = 1024,
        contentHash = null,
        needsPassword = needsPassword,
        pageCount = pageCount,
        progressType = "page",
        currentPage = currentPage,
        scrollOffsetY = 0,
        progressPayload = null,
        thumbnailRelPath = thumbnailRelPath,
        lastReadAt = null,
        addedAt = 0L,
    )

    private fun writeFile(relPath: String) {
        storage.documentFile(relPath).writeBytes(byteArrayOf(1, 2, 3))
    }

    @Test
    fun `打开成功进入 Ready 并恢复库中页码`() = runTest(dispatcher) {
        writeFile("1.pdf")
        val vm = newViewModel(ScriptedEngine { FakeDocument(pageCount = 5) }, item(currentPage = 2))
        advanceUntilIdle()

        val state = vm.state.value as ReaderUiState.Ready
        assertEquals(5, state.pageCount)
        assertEquals(2, state.initialPage)
        assertEquals("测试文档", state.title)
    }

    @Test
    fun `记录不存在返回 MISSING`() = runTest(dispatcher) {
        val vm = newViewModel(ScriptedEngine { FakeDocument() }, item(id = 1L))
        repo.docItem = null
        advanceUntilIdle()

        assertEquals(ReaderFailure.MISSING, (vm.state.value as ReaderUiState.Failed).reason)
    }

    @Test
    fun `物理文件丢失返回 MISSING`() = runTest(dispatcher) {
        val vm = newViewModel(ScriptedEngine { FakeDocument() }, item())   // 不写文件
        advanceUntilIdle()

        assertEquals(ReaderFailure.MISSING, (vm.state.value as ReaderUiState.Failed).reason)
    }

    @Test
    fun `加密文档先弹密码 密码正确后 Ready 并回写页数与清除标记`() = runTest(dispatcher) {
        writeFile("1.pdf")
        var calls = 0
        val engine = ScriptedEngine { password ->
            calls++
            if (password == null) throw PasswordRequiredException()
            FakeDocument(pageCount = 9)
        }
        val vm = newViewModel(engine, item(pageCount = -1, needsPassword = true))
        advanceUntilIdle()

        assertTrue(vm.state.value is ReaderUiState.NeedsPassword)
        assertTrue(!(vm.state.value as ReaderUiState.NeedsPassword).wrongPassword)

        vm.submitPassword("1234")
        advanceUntilIdle()

        val ready = vm.state.value as ReaderUiState.Ready
        assertEquals(9, ready.pageCount)
        assertEquals(1L to 9, repo.pageCountWrites.single())
        assertEquals(1L to false, repo.passwordWrites.single())
    }

    @Test
    fun `密码错误提示重试`() = runTest(dispatcher) {
        writeFile("1.pdf")
        val engine = ScriptedEngine { throw PasswordRequiredException() }
        val vm = newViewModel(engine, item(needsPassword = true))
        advanceUntilIdle()

        vm.submitPassword("wrong")
        advanceUntilIdle()

        val state = vm.state.value as ReaderUiState.NeedsPassword
        assertTrue(state.wrongPassword)
    }

    @Test
    fun `损坏文档返回 CORRUPT 且标记 pageCount 为 -2`() = runTest(dispatcher) {
        writeFile("1.pdf")
        val vm = newViewModel(ScriptedEngine { throw CorruptDocumentException() }, item())
        advanceUntilIdle()

        assertEquals(ReaderFailure.CORRUPT, (vm.state.value as ReaderUiState.Failed).reason)
        assertEquals(-2, repo.pageCountWrites.single().second)
    }

    @Test
    fun `翻页防抖 静止期后仅落库最新页`() = runTest(dispatcher) {
        writeFile("1.pdf")
        val vm = newViewModel(ScriptedEngine { FakeDocument() }, item())
        advanceUntilIdle()

        vm.onPageChanged(3)
        vm.onPageChanged(4)
        advanceTimeBy(ReaderViewModel.PROGRESS_DEBOUNCE_MS - 1)
        runCurrent()   // 只跑当前时刻任务，不推进虚拟时间（advanceUntilIdle 会把防抖期跑完）
        assertTrue(repo.savedProgress.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, repo.savedProgress.size)
        assertEquals(4, repo.savedProgress.single().second)
    }

    @Test
    fun `flush 立即落库且不等待防抖期`() = runTest(dispatcher) {
        writeFile("1.pdf")
        val vm = newViewModel(ScriptedEngine { FakeDocument() }, item())
        advanceUntilIdle()

        vm.onPageChanged(7)
        vm.flushProgress()
        advanceUntilIdle()

        assertEquals(7, repo.savedProgress.single().second)
    }

    @Test
    fun `未发生翻页时 flush 不写库`() = runTest(dispatcher) {
        writeFile("1.pdf")
        val vm = newViewModel(ScriptedEngine { FakeDocument() }, item())
        advanceUntilIdle()

        vm.flushProgress()
        advanceUntilIdle()
        assertTrue(repo.savedProgress.isEmpty())
    }

    // ---- fakes ----

    private class TestDispatcherProvider(d: CoroutineDispatcher) : DispatcherProvider {
        override val io: CoroutineDispatcher = d
        override val default: CoroutineDispatcher = d
        override val main: CoroutineDispatcher = d
    }

    private class FakeRouter(
        private val engine: DocumentEngine,
        private val viewer: DocumentViewerProvider,
    ) : DocumentEngineRouter {
        override fun engineFor(fileType: String) = engine
        override fun viewerFor(fileType: String) = viewer
    }

    private class ScriptedEngine(private val onOpen: (String?) -> Document) : DocumentEngine {
        override fun open(path: String, fileType: String, password: String?): Document = onOpen(password)
    }

    private class FakeDocument(override val pageCount: Int = 5) : Document {
        override fun gotoPage(page: Int) {}
        @Suppress("UNUSED_PARAMETER")
        override fun drawPage(bitmap: android.graphics.Bitmap, page: Int, width: Int, height: Int, patchX: Int, patchY: Int, patchW: Int, patchH: Int) {}
        override suspend fun outline(): List<OutlineEntry> = emptyList()
        override fun close() {}
    }

    /** 缩略图兜底测试不覆盖成功路径（asAndroidBitmap 需 Android 运行时），仅验证失败不阻断阅读 */
    private class ThrowingViewer : DocumentViewerProvider {
        @androidx.compose.runtime.Composable
        override fun Content(doc: Document, initialProgress: ProgressPayload, mode: ReaderMode, onProgressChanged: (ProgressPayload) -> Unit, modifier: androidx.compose.ui.Modifier) = Unit
        override suspend fun thumbnail(doc: Document): ImageBitmap = throw UnsupportedOperationException()
    }

    private class FakeRepository : LibraryRepository {
        var docItem: LibraryItem? = null
        val savedProgress = mutableListOf<Triple<Long, Int, Int>>()
        val pageCountWrites = mutableListOf<Pair<Long, Int>>()
        val passwordWrites = mutableListOf<Pair<Long, Boolean>>()
        val thumbnailWrites = mutableListOf<Pair<Long, String?>>()

        override fun foldersIn(parentId: Long?): Flow<List<com.myreader.core.model.Folder>> = TODO()
        override fun observeAllFolders(): Flow<List<com.myreader.core.model.Folder>> = TODO()
        override suspend fun getFolder(id: Long): com.myreader.core.model.Folder? = TODO()
        override suspend fun subtree(rootId: Long): List<com.myreader.core.model.Folder> = TODO()
        override suspend fun createFolder(parentId: Long?, name: String): Long = TODO()
        override suspend fun renameFolder(id: Long, name: String) = TODO()
        override suspend fun moveFolder(id: Long, newParentId: Long?) = TODO()
        override suspend fun deleteFolder(id: Long) = TODO()
        override fun docsIn(parentId: Long?, sort: com.myreader.core.model.DocSort): Flow<List<LibraryItem>> = TODO()
        override fun search(query: String): Flow<List<LibraryItem>> = TODO()
        override fun recent(limit: Int): Flow<List<LibraryItem>> = TODO()
        override suspend fun getDocument(id: Long): LibraryItem? = docItem
        override suspend fun findByHash(hash: String): LibraryItem? = TODO()
        override suspend fun addDocument(
            id: Long?, folderId: Long?, title: String, relPath: String, fileType: String,
            mimeType: String?, sourceUri: String?, fileSize: Long, contentHash: String?,
            pageCount: Int, progressType: String, thumbnailRelPath: String?, needsPassword: Boolean,
        ): Long = TODO()
        override suspend fun renameDocument(id: Long, title: String) = TODO()
        override suspend fun moveDocument(id: Long, newFolderId: Long?) = TODO()
        override suspend fun deleteDocument(id: Long) = TODO()
        override suspend fun savePageProgress(id: Long, page: Int, scrollOffsetY: Int) {
            savedProgress += Triple(id, page, scrollOffsetY)
        }
        override suspend fun saveCfiProgress(id: Long, payload: String) = TODO()
        override suspend fun setPageCount(id: Long, pageCount: Int) {
            pageCountWrites += id to pageCount
            docItem = docItem?.copy(pageCount = pageCount)
        }
        override suspend fun setNeedsPassword(id: Long, value: Boolean) {
            passwordWrites += id to value
            docItem = docItem?.copy(needsPassword = value)
        }
        override suspend fun setThumbnail(id: Long, relPath: String?) {
            thumbnailWrites += id to relPath
        }
    }
}

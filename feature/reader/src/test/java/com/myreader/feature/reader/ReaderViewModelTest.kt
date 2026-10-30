package com.myreader.feature.reader

import androidx.compose.ui.graphics.ImageBitmap
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import com.myreader.core.common.DispatcherProvider
import com.myreader.core.data.prefs.SettingsPrefs
import com.myreader.core.data.repo.LibraryRepository
import com.myreader.core.document.CorruptDocumentException
import com.myreader.core.document.Document
import com.myreader.core.document.DocumentEngine
import com.myreader.core.document.DocumentEngineRouter
import com.myreader.core.document.DocumentViewerProvider
import com.myreader.core.document.OutlineEntry
import com.myreader.core.document.PasswordRequiredException
import com.myreader.core.document.ProgressPayload
import com.myreader.core.model.LibraryItem
import com.myreader.core.model.ReaderMode
import com.myreader.core.storage.DocumentStorage
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

    private fun newSettingsPrefs(): SettingsPrefs {
        // 每用例独立文件（DataStore 1.1.x 同文件第二实例会挂起等待）；scope 共享 runTest 调度器
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(UnconfinedTestDispatcher(dispatcher.scheduler) + Job()),
        ) { File(tmp.root, "settings-${System.nanoTime()}.preferences_pb") }
        return SettingsPrefs(dataStore)
    }

    private fun newViewModel(
        engine: DocumentEngine,
        item: LibraryItem,
        settingsPrefs: SettingsPrefs = newSettingsPrefs(),
    ): ReaderViewModel {
        repo.docItem = item
        val router = FakeRouter(engine, ThrowingViewer())
        return ReaderViewModel(
            savedStateHandle = SavedStateHandle(mapOf("documentId" to item.id)),
            repository = repo,
            storage = storage,
            router = router,
            settingsPrefs = settingsPrefs,
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
        assertEquals(2, (state.initialProgress as ProgressPayload.Page).page)
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
    fun `加密文档先弹密码 密码正确后 Ready 回写页数且加密标记保留`() = runTest(dispatcher) {
        writeFile("1.pdf")
        var calls = 0
        val engine = ScriptedEngine { password ->
            calls++
            if (password == null) throw PasswordRequiredException()
            FakeDocument(pageCount = 9)
        }
        val vm = newViewModel(engine, item(pageCount = -1, needsPassword = true, thumbnailRelPath = null))
        advanceUntilIdle()

        assertTrue(vm.state.value is ReaderUiState.NeedsPassword)
        assertTrue(!(vm.state.value as ReaderUiState.NeedsPassword).wrongPassword)

        vm.submitPassword("1234")
        advanceUntilIdle()

        val ready = vm.state.value as ReaderUiState.Ready
        assertEquals(9, ready.pageCount)
        assertEquals(1L to 9, repo.pageCountWrites.single())
        // 加密文件始终加密（密码不持久化，每次打开仍需输入）：标记已为 true，自愈守卫跳过写库
        assertTrue(repo.passwordWrites.isEmpty())
        // 加密文档不保留内容预览：不补生成缩略图
        assertTrue(repo.thumbnailWrites.isEmpty())
    }

    @Test
    fun `加密标记被误清 首开自愈置回true`() = runTest(dispatcher) {
        writeFile("1.pdf")
        val engine = ScriptedEngine { password ->
            if (password == null) throw PasswordRequiredException()
            FakeDocument(pageCount = 3)
        }
        // 旧版本误清（needsPassword=false 但文件仍加密）：首开弹密码时自愈置回 true
        val vm = newViewModel(engine, item(needsPassword = false))
        advanceUntilIdle()

        assertTrue(vm.state.value is ReaderUiState.NeedsPassword)
        assertEquals(1L to true, repo.passwordWrites.single())
    }

    @Test
    fun `加密文档遗留缩略图 密码打开后清除预览`() = runTest(dispatcher) {
        writeFile("1.pdf")
        val engine = ScriptedEngine { password ->
            if (password == null) throw PasswordRequiredException()
            FakeDocument(pageCount = 3)
        }
        // 旧版本兜底给加密文档生成过缩略图（遗留预览泄露）
        val vm = newViewModel(engine, item(needsPassword = true, thumbnailRelPath = "thumb/1.png"))
        advanceUntilIdle()
        assertTrue(vm.state.value is ReaderUiState.NeedsPassword)

        vm.submitPassword("1234")
        advanceUntilIdle()

        assertTrue(vm.state.value is ReaderUiState.Ready)
        assertEquals(1L to null, repo.thumbnailWrites.single())   // 清字段（自愈遗留预览）
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
        // 标记已为 true：错密码重试路径不产生冗余写库（code-review 修正）
        assertTrue(repo.passwordWrites.isEmpty())
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

        vm.onProgress(ProgressPayload.Page(3))
        vm.onProgress(ProgressPayload.Page(4))
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

        vm.onProgress(ProgressPayload.Page(7))
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

    // ---- M7：阅读模式 + 垂直偏移 ----

    @Test
    fun `初始模式取自设置偏好`() = runTest(dispatcher) {
        writeFile("1.pdf")
        val prefs = newSettingsPrefs()
        prefs.setDefaultReaderMode(ReaderMode.VERTICAL)
        val vm = newViewModel(ScriptedEngine { FakeDocument() }, item(), prefs)
        advanceUntilIdle()

        assertEquals(ReaderMode.VERTICAL, vm.mode.value)
    }

    @Test
    fun `toggleMode 切换会话模式且不回写偏好`() = runTest(dispatcher) {
        writeFile("1.pdf")
        val prefs = newSettingsPrefs()
        val vm = newViewModel(ScriptedEngine { FakeDocument() }, item(), prefs)
        advanceUntilIdle()

        assertEquals(ReaderMode.HORIZONTAL, vm.mode.value)
        vm.toggleMode()
        assertEquals(ReaderMode.VERTICAL, vm.mode.value)
        // 切换是会话态：默认偏好不受影响
        assertEquals(ReaderMode.HORIZONTAL, prefs.defaultReaderMode.first())
    }

    @Test
    fun `密码重试不重置用户已切换的模式`() = runTest(dispatcher) {
        writeFile("1.pdf")
        var opened = false
        val engine = ScriptedEngine { password ->
            if (password == null) throw PasswordRequiredException()
            opened = true
            FakeDocument(pageCount = 3)
        }
        val prefs = newSettingsPrefs()
        prefs.setDefaultReaderMode(ReaderMode.VERTICAL)
        val vm = newViewModel(engine, item(needsPassword = true), prefs)
        advanceUntilIdle()
        assertTrue(vm.state.value is ReaderUiState.NeedsPassword)
        assertEquals(ReaderMode.VERTICAL, vm.mode.value)

        // 会话内切到水平后重试密码：modeInitialized 守卫保证模式保持用户选择
        vm.toggleMode()
        assertEquals(ReaderMode.HORIZONTAL, vm.mode.value)
        vm.submitPassword("1234")
        advanceUntilIdle()
        assertTrue(opened)
        assertEquals(ReaderMode.HORIZONTAL, vm.mode.value)
    }

    @Test
    fun `垂直滚动偏移随页码防抖落库`() = runTest(dispatcher) {
        writeFile("1.pdf")
        val vm = newViewModel(ScriptedEngine { FakeDocument() }, item())
        advanceUntilIdle()

        vm.onProgress(ProgressPayload.Page(3, 250))
        vm.onProgress(ProgressPayload.Page(3, 400))   // 同页滚动只更新偏移，防抖重置
        advanceTimeBy(ReaderViewModel.PROGRESS_DEBOUNCE_MS)
        runCurrent()
        val saved = repo.savedProgress.single()
        assertEquals(3, saved.second)
        assertEquals(400, saved.third)
    }

    @Test
    fun `水平路径落库偏移恒为0`() = runTest(dispatcher) {
        writeFile("1.pdf")
        val vm = newViewModel(ScriptedEngine { FakeDocument() }, item())
        advanceUntilIdle()

        vm.onProgress(ProgressPayload.Page(4))   // offset 默认 0 = 水平回调路径
        advanceTimeBy(ReaderViewModel.PROGRESS_DEBOUNCE_MS)
        runCurrent()
        assertEquals(0, repo.savedProgress.single().third)
    }

    @Test
    fun `flush 落库包含垂直偏移`() = runTest(dispatcher) {
        writeFile("1.pdf")
        val vm = newViewModel(ScriptedEngine { FakeDocument() }, item())
        advanceUntilIdle()

        vm.onProgress(ProgressPayload.Page(7, 120))
        vm.flushProgress()
        advanceUntilIdle()

        val saved = repo.savedProgress.single()
        assertEquals(7, saved.second)
        assertEquals(120, saved.third)
    }

    @Test
    fun `Ready 透传库中垂直偏移`() = runTest(dispatcher) {
        writeFile("1.pdf")
        val vm = newViewModel(
            ScriptedEngine { FakeDocument(pageCount = 5) },
            item(currentPage = 2).copy(scrollOffsetY = 400),
        )
        advanceUntilIdle()

        val state = vm.state.value as ReaderUiState.Ready
        assertEquals(2, (state.initialProgress as ProgressPayload.Page).page)
        assertEquals(400, (state.initialProgress as ProgressPayload.Page).scrollOffsetY)
    }

    // ---- M9：流式 CFI 进度 + 重排 ----

    private fun epubItem(
        id: Long = 1L,
        progressType: String = "cfi",
        progressPayload: String? = null,
    ) = item(id = id, pageCount = -1).copy(
        relPath = "$id.epub",
        fileType = "epub",
        mimeType = "application/epub+zip",
        progressType = progressType,
        currentPage = 0,
        progressPayload = progressPayload,
        thumbnailRelPath = null,
    )

    @Test
    fun `epub 会话初值强制垂直 忽略水平偏好`() = runTest(dispatcher) {
        writeFile("1.epub")
        val prefs = newSettingsPrefs()
        prefs.setDefaultReaderMode(ReaderMode.HORIZONTAL)
        val vm = newViewModel(
            ScriptedEngine { FakeDocument(pageCount = 20, reflowable = true) },
            epubItem(),
            prefs,
        )
        advanceUntilIdle()

        // 重排文档强制 VERTICAL（顶栏仍可临时切水平）；不跟随用户的水平默认偏好
        assertEquals(ReaderMode.VERTICAL, vm.mode.value)
    }

    @Test
    fun `epub Ready 透传库中 cfi 进度`() = runTest(dispatcher) {
        writeFile("1.epub")
        val payload = com.myreader.core.document.EpubProgressCodec.encode(loc = "987654", page = 5)
        val vm = newViewModel(
            ScriptedEngine { FakeDocument(pageCount = 20, reflowable = true) },
            epubItem(progressType = "cfi", progressPayload = payload),
        )
        advanceUntilIdle()

        val ready = vm.state.value as ReaderUiState.Ready
        assertTrue(ready.reflowable)
        assertTrue(ready.initialProgress is ProgressPayload.Cfi)
        assertEquals(payload, (ready.initialProgress as ProgressPayload.Cfi).cfi)
    }

    @Test
    fun `cfi 进度防抖落库走 saveCfiProgress`() = runTest(dispatcher) {
        writeFile("1.epub")
        val vm = newViewModel(
            ScriptedEngine { FakeDocument(pageCount = 20, reflowable = true) },
            epubItem(),
        )
        advanceUntilIdle()

        val cfi = com.myreader.core.document.EpubProgressCodec.encode(loc = "111", page = 3)
        vm.onProgress(ProgressPayload.Cfi(cfi))
        vm.onProgress(ProgressPayload.Cfi(com.myreader.core.document.EpubProgressCodec.encode(loc = "222", page = 4)))
        advanceTimeBy(ReaderViewModel.PROGRESS_DEBOUNCE_MS - 1)
        runCurrent()
        assertTrue(repo.savedCfi.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        // 仅落最新一条，且走 cfi 通道（不写页码进度）
        assertEquals(1, repo.savedCfi.size)
        assertTrue(repo.savedProgress.isEmpty())
        assertTrue(repo.savedCfi.single().contains("222"))
    }

    @Test
    fun `flush 立即落 cfi`() = runTest(dispatcher) {
        writeFile("1.epub")
        val vm = newViewModel(
            ScriptedEngine { FakeDocument(pageCount = 20, reflowable = true) },
            epubItem(),
        )
        advanceUntilIdle()

        val cfi = com.myreader.core.document.EpubProgressCodec.encode(loc = "555", page = 8)
        vm.onProgress(ProgressPayload.Cfi(cfi))
        vm.flushProgress()
        advanceUntilIdle()

        assertEquals(cfi, repo.savedCfi.single())
    }

    @Test
    fun `epub 豁免 pageCount 回写`() = runTest(dispatcher) {
        writeFile("1.epub")
        val vm = newViewModel(
            ScriptedEngine { FakeDocument(pageCount = 20, reflowable = true) },
            epubItem(),   // 库中 pageCount=-1，运行期 doc.pageCount=20
        )
        advanceUntilIdle()

        assertTrue(vm.state.value is ReaderUiState.Ready)
        // 流式文档页数随排版变化、不落库：backfill 不得写 pageCount（G9）
        assertTrue(repo.pageCountWrites.isEmpty())
    }

    @Test
    fun `页码式文档仍回写 pageCount`() = runTest(dispatcher) {
        writeFile("1.pdf")
        val vm = newViewModel(
            ScriptedEngine { FakeDocument(pageCount = 9, reflowable = false) },
            item(pageCount = -1),   // 库中未知，运行期 9 → 应回写
        )
        advanceUntilIdle()

        assertEquals(1L to 9, repo.pageCountWrites.single())
    }

    // ---- T3：实时页数 ----

    @Test
    fun `视图回调更新实时页数`() = runTest(dispatcher) {
        writeFile("1.pdf")
        val vm = newViewModel(ScriptedEngine { FakeDocument() }, item())
        advanceUntilIdle()

        // 初始无回调：以 Ready.pageCount 快照兜底（null 语义）
        assertEquals(null, vm.displayPageCount.value)

        vm.onPageCountChanged(42)
        assertEquals(42, vm.displayPageCount.value)
        // 相等值幂等（StateFlow 同值不发射，此处断言语义不漂移）
        vm.onPageCountChanged(42)
        assertEquals(42, vm.displayPageCount.value)
    }

    @Test
    fun `密码重试重入时实时页数重置`() = runTest(dispatcher) {
        writeFile("1.pdf")
        val engine = ScriptedEngine { password ->
            if (password == null) throw PasswordRequiredException()
            FakeDocument(pageCount = 3)
        }
        val vm = newViewModel(engine, item(needsPassword = true))
        advanceUntilIdle()
        assertTrue(vm.state.value is ReaderUiState.NeedsPassword)

        vm.onPageCountChanged(99)   // 上一会话残留（防御性构造），重入打开必须失效
        vm.submitPassword("1234")
        advanceUntilIdle()
        assertTrue(vm.state.value is ReaderUiState.Ready)
        // 重入打开 = 新会话：实时页数重置，回到 Ready 快照兜底语义
        assertEquals(null, vm.displayPageCount.value)
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

    private class FakeDocument(
        override val pageCount: Int = 5,
        override val reflowable: Boolean = false,
    ) : Document {
        override fun gotoPage(page: Int) {}
        override suspend fun pageSize(page: Int): androidx.compose.ui.geometry.Size =
            androidx.compose.ui.geometry.Size(595f, 842f)
        @Suppress("UNUSED_PARAMETER")
        override fun drawPage(bitmap: android.graphics.Bitmap, page: Int, width: Int, height: Int, patchX: Int, patchY: Int, patchW: Int, patchH: Int) {}
        override suspend fun outline(): List<OutlineEntry> = emptyList()
        override fun close() {}
    }

    /** 缩略图兜底测试不覆盖成功路径（asAndroidBitmap 需 Android 运行时），仅验证失败不阻断阅读 */
    private class ThrowingViewer : DocumentViewerProvider {
        @androidx.compose.runtime.Composable
        override fun Content(doc: Document, initialProgress: ProgressPayload, mode: ReaderMode, onProgressChanged: (ProgressPayload) -> Unit, onPageCountChanged: (Int) -> Unit, modifier: androidx.compose.ui.Modifier) = Unit
        override suspend fun thumbnail(doc: Document): ImageBitmap = throw UnsupportedOperationException()
    }

    private class FakeRepository : LibraryRepository {
        var docItem: LibraryItem? = null
        val savedProgress = mutableListOf<Triple<Long, Int, Int>>()
        val savedCfi = mutableListOf<String>()
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
        // M10：阅读器尚未接入书签 UI，Fake 仅满足接口（C 阶段补编排用例）
        override fun observeBookmarks(documentId: Long): Flow<List<com.myreader.core.model.Bookmark>> = TODO()
        override suspend fun bookmarksOf(documentId: Long): List<com.myreader.core.model.Bookmark> = TODO()
        override suspend fun addBookmark(
            documentId: Long,
            page: Int,
            scrollOffsetY: Int,
            anchor: String?,
            label: String,
        ): com.myreader.core.model.BookmarkAddResult = TODO()
        override suspend fun deleteBookmark(id: Long) = TODO()
        override suspend fun savePageProgress(id: Long, page: Int, scrollOffsetY: Int) {
            savedProgress += Triple(id, page, scrollOffsetY)
        }
        override suspend fun saveCfiProgress(id: Long, payload: String) {
            savedCfi += payload
        }
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

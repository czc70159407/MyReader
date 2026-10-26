package com.myreader.feature.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModel
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.myreader.core.common.DispatcherProvider
import com.myreader.core.data.backup.BackupManager
import com.myreader.core.data.db.MyReaderDatabase
import com.myreader.core.data.prefs.LibraryPrefs
import com.myreader.core.data.prefs.SettingsPrefs
import com.myreader.core.model.DocSort
import com.myreader.core.model.ReaderMode
import com.myreader.core.model.ThemeMode
import com.myreader.core.storage.DocumentStorage
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** 双偏好真实 DataStore（纯 JVM File 落盘）；每用例独立文件，scope 共享 runTest 调度器。
     *  同时构造真实 BackupManager（inMemory Room + 临时 library 目录），供备份/恢复方法测试。 */
    private fun newViewModel(): Triple<SettingsViewModel, SettingsPrefs, LibraryPrefs> {
        val scope = CoroutineScope(UnconfinedTestDispatcher(dispatcher.scheduler) + Job())
        val settings = SettingsPrefs(
            PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "s-${System.nanoTime()}.preferences_pb") },
        )
        val library = LibraryPrefs(
            PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "l-${System.nanoTime()}.preferences_pb") },
        )
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), MyReaderDatabase::class.java
        ).allowMainThreadQueries().build()
        val storage = DocumentStorage(tmp.newFolder("library"))
        val dispatchers = object : DispatcherProvider {
            override val io: CoroutineDispatcher = UnconfinedTestDispatcher(dispatcher.scheduler)
            override val default: CoroutineDispatcher = UnconfinedTestDispatcher(dispatcher.scheduler)
            override val main: CoroutineDispatcher = UnconfinedTestDispatcher(dispatcher.scheduler)
        }
        val backupManager = BackupManager(db, db.folderDao(), db.documentDao(), storage, dispatchers)
        return Triple(SettingsViewModel(settings, library, backupManager), settings, library)
    }

    @Test
    fun `三流初始值为默认`() = runTest(dispatcher) {
        val (vm, _, _) = newViewModel()
        assertEquals(ReaderMode.HORIZONTAL, vm.defaultReaderMode.value)
        assertEquals(ThemeMode.SYSTEM, vm.themeMode.value)
        assertEquals(DocSort.TITLE, vm.docSort.value)
    }

    @Test
    fun `三 setter 写透偏好落盘`() = runTest(dispatcher) {
        val (vm, settings, library) = newViewModel()
        vm.setDefaultReaderMode(ReaderMode.VERTICAL)
        vm.setThemeMode(ThemeMode.DARK)
        vm.setDocSort(DocSort.SIZE)
        advanceUntilIdle()

        assertEquals(ReaderMode.VERTICAL, settings.defaultReaderMode.first())
        assertEquals(ThemeMode.DARK, settings.themeMode.first())
        assertEquals(DocSort.SIZE, library.docSort.first())
    }

    /** T1：确认后打开输入流失败（文件被移动/授权失效）必须有可见反馈，不得静默丢弃 */
    @Test
    fun `恢复流失效时上报可读错误`() = runTest(dispatcher) {
        val (vm, _, _) = newViewModel()
        vm.reportRestoreStreamUnavailable()
        advanceUntilIdle()

        assertEquals("备份文件无法识别或已损坏", vm.messages.first())
    }
}

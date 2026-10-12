package com.myreader.feature.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModel
import com.myreader.core.data.prefs.LibraryPrefs
import com.myreader.core.data.prefs.SettingsPrefs
import com.myreader.core.model.DocSort
import com.myreader.core.model.ReaderMode
import com.myreader.core.model.ThemeMode
import java.io.File
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

@OptIn(ExperimentalCoroutinesApi::class)
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

    /** 双偏好真实 DataStore（纯 JVM File 落盘）；每用例独立文件，scope 共享 runTest 调度器 */
    private fun newViewModel(): Triple<SettingsViewModel, SettingsPrefs, LibraryPrefs> {
        val scope = CoroutineScope(UnconfinedTestDispatcher(dispatcher.scheduler) + Job())
        val settings = SettingsPrefs(
            PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "s-${System.nanoTime()}.preferences_pb") },
        )
        val library = LibraryPrefs(
            PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "l-${System.nanoTime()}.preferences_pb") },
        )
        return Triple(SettingsViewModel(settings, library), settings, library)
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
}

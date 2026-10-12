package com.myreader.core.data.prefs

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.myreader.core.model.ReaderMode
import com.myreader.core.model.ThemeMode
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SettingsPrefs 单测（Robolectric，真实 DataStore 文件落 TemporaryFolder）。
 * scope 共享 runTest 的 testScheduler；每测试独立文件，避开同名文件多实例崩溃（纪律同 LibraryPrefsTest）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsPrefsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newDataStore(file: File, scheduler: TestCoroutineScheduler) =
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(UnconfinedTestDispatcher(scheduler) + Job()),
        ) { file }

    @Test
    fun defaults_areHorizontalAndSystem() = runTest {
        val prefs = SettingsPrefs(newDataStore(File(tmp.newFolder(), "default.preferences_pb"), testScheduler))
        assertEquals(ReaderMode.HORIZONTAL, prefs.defaultReaderMode.first())
        assertEquals(ThemeMode.SYSTEM, prefs.themeMode.first())
    }

    @Test
    fun setters_roundTrip() = runTest {
        val prefs = SettingsPrefs(newDataStore(File(tmp.newFolder(), "round.preferences_pb"), testScheduler))
        prefs.setDefaultReaderMode(ReaderMode.VERTICAL)
        prefs.setThemeMode(ThemeMode.DARK)
        assertEquals(ReaderMode.VERTICAL, prefs.defaultReaderMode.first())
        assertEquals(ThemeMode.DARK, prefs.themeMode.first())
    }

    @Test
    fun outOfBoundsOrdinals_fallBackToDefaults() = runTest {
        // 模拟损坏/枚举收缩后残留的越界 ordinal（绕过 SettingsPrefs 直接写存储）；
        // 同文件不允许第二个 DataStore 实例（1.1.x 主动拦截），经同一实例读取验证回退
        val dataStore = newDataStore(File(tmp.newFolder(), "corrupt.preferences_pb"), testScheduler)
        dataStore.edit {
            it[intPreferencesKey("default_reader_mode")] = 99
            it[intPreferencesKey("theme_mode")] = -1
        }
        val prefs = SettingsPrefs(dataStore)
        assertEquals(ReaderMode.HORIZONTAL, prefs.defaultReaderMode.first())
        assertEquals(ThemeMode.SYSTEM, prefs.themeMode.first())
    }
}

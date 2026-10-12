package com.myreader.core.data.prefs

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.myreader.core.model.DocSort
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
 * LibraryPrefs 单测（Robolectric，真实 DataStore 文件落 TemporaryFolder）。
 * DataStore scope 共享 runTest 的 testScheduler（无参 UnconfinedTestDispatcher 会新建独立调度器，
 * 虚拟时间推不动其内部协程）；每测试独立文件，避开同名文件多实例崩溃。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LibraryPrefsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newPrefs(file: File, scheduler: TestCoroutineScheduler) = LibraryPrefs(
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(UnconfinedTestDispatcher(scheduler) + Job()),
        ) { file },
    )

    @Test
    fun default_isTitle() = runTest {
        val prefs = newPrefs(File(tmp.newFolder(), "default.preferences_pb"), testScheduler)
        assertEquals(DocSort.TITLE, prefs.docSort.first())
    }

    @Test
    fun setDocSort_roundTrips() = runTest {
        val prefs = newPrefs(File(tmp.newFolder(), "round.preferences_pb"), testScheduler)
        prefs.setDocSort(DocSort.SIZE)
        assertEquals(DocSort.SIZE, prefs.docSort.first())
    }

    @Test
    fun outOfBoundsOrdinal_fallsBackToTitle() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job()),
        ) { File(tmp.newFolder(), "corrupt.preferences_pb") }
        // 模拟损坏/枚举收缩后残留的越界 ordinal（绕过 LibraryPrefs 直接写存储）。
        // 同文件不允许第二个 DataStore 实例（1.1.x 主动拦截），故经同一实例读取验证回退映射
        dataStore.edit { it[intPreferencesKey("doc_sort")] = 99 }
        assertEquals(DocSort.TITLE, LibraryPrefs(dataStore).docSort.first())
    }
}

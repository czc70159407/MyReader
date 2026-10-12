package com.myreader.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myreader.core.data.prefs.LibraryPrefs
import com.myreader.core.data.prefs.SettingsPrefs
import com.myreader.core.model.DocSort
import com.myreader.core.model.ReaderMode
import com.myreader.core.model.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 设置页 ViewModel（M7）：三组偏好（默认阅读模式 / 主题 / 书架排序），全部 DataStore 持久化。
 * 书架排序与书架 SortMenu 读写同一 LibraryPrefs 流——双向同步零额外代码
 * （设置页改 → 书架 documents 流 flatMapLatest 重排；书架改 → 本页 stateIn 跟随）。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsPrefs: SettingsPrefs,
    private val libraryPrefs: LibraryPrefs,
) : ViewModel() {

    /** 默认阅读模式（打开文档初值；阅读器内切换是会话态，不回写此处） */
    val defaultReaderMode: StateFlow<ReaderMode> =
        settingsPrefs.defaultReaderMode
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReaderMode.HORIZONTAL)

    /** 主题（全 APP colorScheme；MainActivity 消费同一流即时生效） */
    val themeMode: StateFlow<ThemeMode> =
        settingsPrefs.themeMode
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.SYSTEM)

    /** 书架排序（与书架 SortMenu 同源） */
    val docSort: StateFlow<DocSort> =
        libraryPrefs.docSort
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DocSort.TITLE)

    fun setDefaultReaderMode(mode: ReaderMode) = set { settingsPrefs.setDefaultReaderMode(mode) }
    fun setThemeMode(mode: ThemeMode) = set { settingsPrefs.setThemeMode(mode) }
    fun setDocSort(sort: DocSort) = set { libraryPrefs.setDocSort(sort) }

    /** 写偏好：磁盘故障静默降级（偏好维持旧值，无副作用可吞，分工同 LibraryViewModel.setSort） */
    private fun set(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // DataStore 写失败（IO/磁盘故障）
            }
        }
    }
}

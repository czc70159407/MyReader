package com.myreader.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myreader.core.data.backup.BackupManager
import com.myreader.core.data.prefs.LibraryPrefs
import com.myreader.core.data.prefs.SettingsPrefs
import com.myreader.core.model.DocSort
import com.myreader.core.model.ReaderMode
import com.myreader.core.model.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream

/**
 * 设置页 ViewModel（M7）：三组偏好（默认阅读模式 / 主题 / 书架排序），全部 DataStore 持久化。
 * 书架排序与书架 SortMenu 读写同一 LibraryPrefs 流——双向同步零额外代码
 * （设置页改 → 书架 documents 流 flatMapLatest 重排；书架改 → 本页 stateIn 跟随）。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsPrefs: SettingsPrefs,
    private val libraryPrefs: LibraryPrefs,
    private val backupManager: BackupManager,
) : ViewModel() {

    // ---- 备份 / 恢复（M8）----
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    /** 导出备份：UI 侧已用 SAF 打开 OutputStream 并传入；内部打包 library/ + 缩略图 + DB 快照。
     *  流的关闭必须等到后台协程真正写完之后——故在协程内 finally 关闭，UI 不得提前 .use 关闭。 */
    fun exportBackup(output: OutputStream) = runBackup {
        try {
            backupManager.export(output, "MyReader") { f, s -> _progress.value = f; _status.value = s }
        } finally {
            runCatching { output.close() }
        }
        _messages.send("备份完成")
    }

    /** 从备份恢复：UI 侧已用 SAF 打开 InputStream 并传入；合并式 upsert 恢复目录树/文档/进度。 */
    fun restoreBackup(input: InputStream) = runBackup {
        val r = try {
            backupManager.restore(input) { f, s -> _progress.value = f; _status.value = s }
        } finally {
            runCatching { input.close() }
        }
        _messages.send("已恢复 ${r.documents} 个文档 / ${r.folders} 个目录")
    }

    /** 恢复流程：SAF 返回的 zip 无法打开输入流（文件被移动/授权失效）。
     *  静默丢弃会让用户以为"点了没反应"，故显式上报（设计纪律：失败不得静默）。 */
    fun reportRestoreStreamUnavailable() {
        viewModelScope.launch { _messages.send("备份文件无法识别或已损坏") }
    }

    /** 统一备份操作编排：busy 状态机 + 进度回写 + 异常转 snackbar 文案（不吞 CancellationException）。 */
    private fun runBackup(block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            _progress.value = 0f
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send("备份操作失败：${e.message ?: e.javaClass.simpleName}")
            } finally {
                _busy.value = false
                _progress.value = 0f
                _status.value = ""
            }
        }
    }

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

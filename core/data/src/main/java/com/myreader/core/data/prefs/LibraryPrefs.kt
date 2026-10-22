package com.myreader.core.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.myreader.core.model.DocSort
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * 书架轻量偏好（DataStore，M6）：当前排序记忆。
 * 实例由 app 层 DI 提供（[LibraryPrefsStore] 限定 library_prefs 文件，单文件必须单实例）。
 */
@Singleton
class LibraryPrefs @Inject constructor(@LibraryPrefsStore private val dataStore: DataStore<Preferences>) {

    /**
     * DocSort 存枚举 name 字符串（T6）：消除 ordinal 错位隐患——旧方案在枚举中间插入新值时，
     * 已持久化 ordinal 会静默映射到错误语义（仅越界才回落默认）。name 匹配对枚举重排免疫。
     * 兼容读取旧 int key（M6 起的历史数据）；写入一律新 key 并清除旧 key，单次写入即完成迁移。
     */
    private val keyDocSort = stringPreferencesKey("doc_sort_name")
    private val legacyKeyDocSort = intPreferencesKey("doc_sort")

    /** 当前排序：读失败（IO 异常/损坏）回落空偏好 → 默认 TITLE，不沿 stateIn 崩溃书架主列表 */
    val docSort: Flow<DocSort> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            prefs[keyDocSort]?.let { name -> DocSort.entries.firstOrNull { it.name == name } }
                ?: prefs[legacyKeyDocSort]?.let { ordinal -> DocSort.entries.getOrNull(ordinal) }
                ?: DocSort.TITLE
        }

    suspend fun setDocSort(sort: DocSort) {
        dataStore.edit {
            it[keyDocSort] = sort.name
            it.remove(legacyKeyDocSort)
        }
    }
}

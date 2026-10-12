package com.myreader.core.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import com.myreader.core.model.DocSort
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * 书架轻量偏好（DataStore，M6）：当前排序记忆。
 * 实例由 app 层 DI 提供（单文件单实例），core:data 不自建 Hilt module。
 */
@Singleton
class LibraryPrefs @Inject constructor(private val dataStore: DataStore<Preferences>) {

    /** DocSort 以 ordinal 存储：枚举未来不可重排/插入（重排仅回落默认值，可接受，见 docs/data-layer.md） */
    private val keyDocSort = intPreferencesKey("doc_sort")

    /** 当前排序：读失败（IO 异常/损坏）回落空偏好 → 默认 TITLE，不沿 stateIn 崩溃书架主列表 */
    val docSort: Flow<DocSort> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            val ordinal = prefs[keyDocSort] ?: DocSort.TITLE.ordinal
            if (ordinal in DocSort.entries.indices) DocSort.entries[ordinal] else DocSort.TITLE
        }

    suspend fun setDocSort(sort: DocSort) {
        dataStore.edit { it[keyDocSort] = sort.ordinal }
    }
}

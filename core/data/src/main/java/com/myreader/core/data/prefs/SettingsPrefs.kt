package com.myreader.core.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import com.myreader.core.model.ReaderMode
import com.myreader.core.model.ThemeMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * 应用设置轻量偏好（DataStore，M7）：默认阅读模式 + 主题。
 * 实例由 app 层 DI 提供（[SettingsPrefsStore] 限定 settings_prefs 文件，单文件必须单实例）。
 * 枚举以 ordinal 存储：不可重排/中间插入（重排仅回落默认值，纪律同 DocSort，见 docs/data-layer.md）。
 */
@Singleton
class SettingsPrefs @Inject constructor(@SettingsPrefsStore private val dataStore: DataStore<Preferences>) {

    private val keyReaderMode = intPreferencesKey("default_reader_mode")
    private val keyThemeMode = intPreferencesKey("theme_mode")

    /** 默认阅读模式（打开文档时的初值；阅读器内切换是会话态，不回写本偏好）：读失败回落 HORIZONTAL */
    val defaultReaderMode: Flow<ReaderMode> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            val ordinal = prefs[keyReaderMode] ?: ReaderMode.HORIZONTAL.ordinal
            if (ordinal in ReaderMode.entries.indices) ReaderMode.entries[ordinal] else ReaderMode.HORIZONTAL
        }

    /** 主题（全 APP colorScheme 生效）：读失败回落 SYSTEM（跟随系统） */
    val themeMode: Flow<ThemeMode> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            val ordinal = prefs[keyThemeMode] ?: ThemeMode.SYSTEM.ordinal
            if (ordinal in ThemeMode.entries.indices) ThemeMode.entries[ordinal] else ThemeMode.SYSTEM
        }

    suspend fun setDefaultReaderMode(mode: ReaderMode) {
        dataStore.edit { it[keyReaderMode] = mode.ordinal }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[keyThemeMode] = mode.ordinal }
    }
}

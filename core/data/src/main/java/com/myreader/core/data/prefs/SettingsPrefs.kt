package com.myreader.core.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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
 * 枚举存 name 字符串（T6，纪律同 LibraryPrefs）：对枚举重排/插入免疫；兼容读取旧 int key，
 * 写入一律新 key 并清除旧 key（docs/data-layer.md）。
 */
@Singleton
class SettingsPrefs @Inject constructor(@SettingsPrefsStore private val dataStore: DataStore<Preferences>) {

    private val keyReaderMode = stringPreferencesKey("default_reader_mode_name")
    private val legacyKeyReaderMode = intPreferencesKey("default_reader_mode")
    private val keyThemeMode = stringPreferencesKey("theme_mode_name")
    private val legacyKeyThemeMode = intPreferencesKey("theme_mode")

    /** 默认阅读模式（打开文档时的初值；阅读器内切换是会话态，不回写本偏好）：读失败回落 HORIZONTAL */
    val defaultReaderMode: Flow<ReaderMode> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            prefs[keyReaderMode]?.let { name -> ReaderMode.entries.firstOrNull { it.name == name } }
                ?: prefs[legacyKeyReaderMode]?.let { ordinal -> ReaderMode.entries.getOrNull(ordinal) }
                ?: ReaderMode.HORIZONTAL
        }

    /** 主题（全 APP colorScheme 生效）：读失败回落 SYSTEM（跟随系统） */
    val themeMode: Flow<ThemeMode> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            prefs[keyThemeMode]?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
                ?: prefs[legacyKeyThemeMode]?.let { ordinal -> ThemeMode.entries.getOrNull(ordinal) }
                ?: ThemeMode.SYSTEM
        }

    suspend fun setDefaultReaderMode(mode: ReaderMode) {
        dataStore.edit {
            it[keyReaderMode] = mode.name
            it.remove(legacyKeyReaderMode)
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit {
            it[keyThemeMode] = mode.name
            it.remove(legacyKeyThemeMode)
        }
    }
}

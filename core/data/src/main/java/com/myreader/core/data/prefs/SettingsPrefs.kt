package com.myreader.core.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.myreader.core.model.ReaderFontScale
import com.myreader.core.model.ReaderMode
import com.myreader.core.model.ReaderZoomScale
import com.myreader.core.model.ThemeMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * 应用设置轻量偏好（DataStore，M7）：默认阅读模式 + 主题 + M15 阅读字号 / 显示缩放。
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
    private val keyFontScale = stringPreferencesKey("reader_font_scale_name")
    private val legacyKeyFontScale = intPreferencesKey("reader_font_scale")
    private val keyZoomScale = stringPreferencesKey("reader_zoom_scale_name")
    private val legacyKeyZoomScale = intPreferencesKey("reader_zoom_scale")

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

    /** 阅读字号档位（M15，epub 重排排版字号；全局偏好非会话态）：读失败回落 DEFAULT */
    val readerFontScale: Flow<ReaderFontScale> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            prefs[keyFontScale]?.let { name -> ReaderFontScale.entries.firstOrNull { it.name == name } }
                ?: prefs[legacyKeyFontScale]?.let { ordinal -> ReaderFontScale.entries.getOrNull(ordinal) }
                ?: ReaderFontScale.FALLBACK
        }

    /** 固定版式默认显示缩放（M15，相对适合宽度的倍数，仅水平模式；全局偏好非会话态）：读失败回落 FIT_WIDTH */
    val readerZoomScale: Flow<ReaderZoomScale> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            prefs[keyZoomScale]?.let { name -> ReaderZoomScale.entries.firstOrNull { it.name == name } }
                ?: prefs[legacyKeyZoomScale]?.let { ordinal -> ReaderZoomScale.entries.getOrNull(ordinal) }
                ?: ReaderZoomScale.FALLBACK
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

    suspend fun setReaderFontScale(scale: ReaderFontScale) {
        dataStore.edit {
            it[keyFontScale] = scale.name
            it.remove(legacyKeyFontScale)
        }
    }

    suspend fun setReaderZoomScale(scale: ReaderZoomScale) {
        dataStore.edit {
            it[keyZoomScale] = scale.name
            it.remove(legacyKeyZoomScale)
        }
    }
}

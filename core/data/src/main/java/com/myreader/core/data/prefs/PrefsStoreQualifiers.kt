package com.myreader.core.data.prefs

import javax.inject.Qualifier

/** 书架偏好 DataStore（library_prefs 文件）限定符：M7 起存在多个 DataStore 绑定，须按文件区分 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class LibraryPrefsStore

/** 设置偏好 DataStore（settings_prefs 文件）限定符 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class SettingsPrefsStore

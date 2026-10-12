package com.myreader.core.model

/**
 * 主题偏好：跟随系统 / 浅色 / 深色（M7 设置页，全 APP Material3 colorScheme 切换）。
 * 由 SettingsPrefs 以 ordinal 持久化——枚举不可重排/中间插入（仅尾部追加）。
 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** 解析为主题是否深色：SYSTEM 跟随系统，LIGHT/DARK 显式指定 */
fun ThemeMode.resolve(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

package com.myreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.myreader.core.data.prefs.SettingsPrefs
import com.myreader.core.model.ThemeMode
import com.myreader.core.model.resolve
import com.myreader.nav.MyReaderNavHost
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsPrefs: SettingsPrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 主题首读（M7）：本地小文件毫秒级；300ms 超时兜底防磁盘异常 ANR，回落跟随系统。
        // 双保险防首帧闪错色：values/themes.xml 用 DayNight parent，窗口背景先随系统深色
        val initialTheme = runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(300) { settingsPrefs.themeMode.first() }
        } ?: ThemeMode.SYSTEM

        setContent {
            var themeMode by remember { mutableStateOf(initialTheme) }
            // 设置页改动实时生效：DataStore 流持续发射（含从设置页改完返回的场景）
            LaunchedEffect(Unit) {
                settingsPrefs.themeMode.collect { themeMode = it }
            }
            val dark = themeMode.resolve(isSystemInDarkTheme())
            MaterialTheme(
                // Material3 默认色板：项目未定义品牌色；不接 dynamicColor（与三选一语义冲突，不过度工程）
                colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
            ) {
                Surface(modifier = Modifier) {
                    MyReaderNavHost()
                }
            }
        }
    }
}

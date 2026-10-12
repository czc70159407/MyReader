package com.myreader.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * DispatcherProvider 默认实现（生产环境用）；测试注入 TestDispatcher。
 * main 用 lazy：纯 JVM 单测构造本类安全（不触 Main dispatcher 初始化），仅访问 .main 才需要 Android 运行时。
 */
class DefaultDispatcherProvider : DispatcherProvider {
    override val io: CoroutineDispatcher = Dispatchers.IO
    override val default: CoroutineDispatcher = Dispatchers.Default
    override val main: CoroutineDispatcher by lazy { Dispatchers.Main }
}

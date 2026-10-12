package com.myreader.core.common

import kotlinx.coroutines.CoroutineDispatcher

/**
 * 协程调度器抽象：业务层经此切换 IO 线程，测试时可注入 TestDispatcher。
 */
interface DispatcherProvider {
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
    val main: CoroutineDispatcher
}

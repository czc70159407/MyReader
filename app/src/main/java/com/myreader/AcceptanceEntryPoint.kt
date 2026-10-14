package com.myreader

import com.myreader.core.data.backup.BackupManager
import com.myreader.core.data.db.MyReaderDatabase
import com.myreader.core.storage.DocumentStorage
import com.myreader.feature.library.DocumentImporter
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * 验收/调试入口：从已注入的 Hilt SingletonComponent 取出业务依赖，供真机插桩测试在进程内
 * 直接驱动导入/备份/恢复逻辑（绕过 SAF UI）。仅声明依赖获取，无副作用，可安全随主包发布。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AcceptanceEntryPoint {
    fun documentImporter(): DocumentImporter
    fun backupManager(): BackupManager
    fun database(): MyReaderDatabase
    fun documentStorage(): DocumentStorage
}

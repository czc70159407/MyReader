package com.myreader.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.myreader.core.data.db.dao.BookmarkDao
import com.myreader.core.data.db.dao.DocumentDao
import com.myreader.core.data.db.dao.FolderDao
import com.myreader.core.data.db.entity.BookmarkEntity
import com.myreader.core.data.db.entity.DocumentEntity
import com.myreader.core.data.db.entity.FolderEntity

@Database(
    entities = [FolderEntity::class, DocumentEntity::class, BookmarkEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class MyReaderDatabase : RoomDatabase() {
    abstract fun folderDao(): FolderDao
    abstract fun documentDao(): DocumentDao
    abstract fun bookmarkDao(): BookmarkDao

    companion object {
        /**
         * v1 → v2（M10）：仅新增 `bookmarks` 表与 `documentId` 索引，既有表列零变更。
         *
         * 语句与 Room 生成的 `2.json` 逐字一致（MigrationTest 会做表结构校验）；
         * 严禁 `fallbackToDestructiveMigration`（工作规则 3：阅读进度是核心数据）。
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 语句逐字取自 Room 生成的 2.json createSql：Kotlin 默认值（scrollOffsetY = 0）
                // 不生成 SQL DEFAULT，此处不得自作聪明加 DEFAULT（MigrationTest 会校验表结构）
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bookmarks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`documentId` INTEGER NOT NULL, `page` INTEGER NOT NULL, " +
                        "`scrollOffsetY` INTEGER NOT NULL, `anchor` TEXT, `label` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, FOREIGN KEY(`documentId`) REFERENCES `documents`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_bookmarks_documentId` ON `bookmarks` (`documentId`)",
                )
            }
        }
    }
}

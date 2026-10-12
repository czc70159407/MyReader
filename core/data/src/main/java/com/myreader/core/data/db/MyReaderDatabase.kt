package com.myreader.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.myreader.core.data.db.dao.DocumentDao
import com.myreader.core.data.db.dao.FolderDao
import com.myreader.core.data.db.entity.DocumentEntity
import com.myreader.core.data.db.entity.FolderEntity

@Database(
    entities = [FolderEntity::class, DocumentEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class MyReaderDatabase : RoomDatabase() {
    abstract fun folderDao(): FolderDao
    abstract fun documentDao(): DocumentDao
}

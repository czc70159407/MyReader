package com.myreader.di

import android.content.Context
import androidx.room.Room
import com.myreader.core.data.db.MyReaderDatabase
import com.myreader.core.data.db.dao.BookmarkDao
import com.myreader.core.data.db.dao.DocumentDao
import com.myreader.core.data.db.dao.FolderDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MyReaderDatabase =
        // 不用 fallbackToDestructiveMigration（工作规则 3：阅读进度是核心数据）
        Room.databaseBuilder(context, MyReaderDatabase::class.java, "myreader.db")
            .addMigrations(MyReaderDatabase.MIGRATION_1_2)   // M10：v1→v2 仅新增 bookmarks 表
            .build()

    @Provides
    fun provideFolderDao(db: MyReaderDatabase): FolderDao = db.folderDao()

    @Provides
    fun provideDocumentDao(db: MyReaderDatabase): DocumentDao = db.documentDao()

    @Provides
    fun provideBookmarkDao(db: MyReaderDatabase): BookmarkDao = db.bookmarkDao()
}

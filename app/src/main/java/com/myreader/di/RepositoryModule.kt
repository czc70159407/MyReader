package com.myreader.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.myreader.core.common.DefaultDispatcherProvider
import com.myreader.core.common.DispatcherProvider
import com.myreader.core.data.prefs.LibraryPrefsStore
import com.myreader.core.data.prefs.SettingsPrefsStore
import com.myreader.core.data.repo.LibraryRepository
import com.myreader.core.data.repo.LibraryRepositoryImpl
import com.myreader.core.storage.DocumentStorage
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    abstract fun bindLibraryRepository(impl: LibraryRepositoryImpl): LibraryRepository

    companion object {

        @Provides
        @Singleton
        fun provideDocumentStorage(@ApplicationContext context: Context): DocumentStorage =
            DocumentStorage(File(context.filesDir, "library"))

        @Provides
        @Singleton
        fun provideDispatcherProvider(): DispatcherProvider = DefaultDispatcherProvider()

        /** 偏好 DataStore：单文件必须单实例，@Singleton 保证；限定符按文件区分（M7 起两个偏好文件） */
        @Provides
        @Singleton
        @LibraryPrefsStore
        fun provideLibraryPrefsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
                context.preferencesDataStoreFile("library_prefs")
            }

        /** 设置偏好（M7：默认阅读模式 + 主题） */
        @Provides
        @Singleton
        @SettingsPrefsStore
        fun provideSettingsPrefsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
                context.preferencesDataStoreFile("settings_prefs")
            }
    }
}

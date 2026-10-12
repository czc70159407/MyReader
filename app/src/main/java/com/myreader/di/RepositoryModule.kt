package com.myreader.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.myreader.core.common.DefaultDispatcherProvider
import com.myreader.core.common.DispatcherProvider
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

        /** 书架偏好存储（M6 排序记忆）：单文件必须单实例，@Singleton 保证 */
        @Provides
        @Singleton
        fun providePreferencesDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
                context.preferencesDataStoreFile("library_prefs")
            }
    }
}

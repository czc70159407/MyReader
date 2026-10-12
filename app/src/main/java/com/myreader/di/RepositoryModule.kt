package com.myreader.di

import android.content.Context
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
    }
}

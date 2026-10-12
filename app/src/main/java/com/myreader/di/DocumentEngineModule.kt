package com.myreader.di

import com.myreader.core.document.DocumentEngine
import com.myreader.core.document.DocumentEngineRouter
import com.myreader.core.document.DocumentViewerProvider
import com.myreader.core.document.FileTypeKey
import com.myreader.core.document.SimpleDocumentEngineRouter
import com.myreader.feature.viewermupdf.MuPdfDocumentEngine
import com.myreader.feature.viewermupdf.MuPdfViewerProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import javax.inject.Singleton

/**
 * 文档引擎多绑定（核心扩展点）：新格式 = 新建 feature:viewer-xxx 并在此追加两个 @IntoMap 绑定，
 * reader/library/schema 均不改（见 docs/architecture.md「模块依赖关系」）。
 */
@Module
@InstallIn(SingletonComponent::class)
object DocumentEngineModule {

    @Provides
    @Singleton
    @IntoMap
    @FileTypeKey("pdf")
    fun providePdfDocumentEngine(engine: MuPdfDocumentEngine): DocumentEngine = engine

    @Provides
    @Singleton
    @IntoMap
    @FileTypeKey("pdf")
    fun providePdfViewerProvider(provider: MuPdfViewerProvider): DocumentViewerProvider = provider

    @Provides
    @Singleton
    fun provideDocumentEngineRouter(
        engines: Map<String, @JvmSuppressWildcards DocumentEngine>,
        viewers: Map<String, @JvmSuppressWildcards DocumentViewerProvider>,
    ): DocumentEngineRouter = SimpleDocumentEngineRouter(engines, viewers)
}

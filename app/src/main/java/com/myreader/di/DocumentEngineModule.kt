package com.myreader.di

import com.myreader.core.document.DocumentEngine
import com.myreader.core.document.DocumentEngineRouter
import com.myreader.core.document.DocumentViewerProvider
import com.myreader.core.document.FileTypeKey
import com.myreader.core.document.SimpleDocumentEngineRouter
import com.myreader.feature.viewermupdf.MuPdfDocumentEngine
import com.myreader.feature.viewermupdf.MuPdfViewerProvider
import com.myreader.feature.viewertxt.TxtDocumentEngine
import com.myreader.feature.viewertxt.TxtViewerProvider
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

    // M9-F 扩展点验证：TXT 由独立 feature:viewer-txt 模块提供（纯 Compose，非 MuPDF），
    // 同样仅追加两个 @IntoMap 绑定即接入路由——reader/library/schema 零改动（工作规则 1）。
    @Provides
    @Singleton
    @IntoMap
    @FileTypeKey("txt")
    fun provideTxtDocumentEngine(engine: TxtDocumentEngine): DocumentEngine = engine

    @Provides
    @Singleton
    @IntoMap
    @FileTypeKey("txt")
    fun provideTxtViewerProvider(provider: TxtViewerProvider): DocumentViewerProvider = provider

    // M9：epub/cbz/xps 复用同一 MuPDF 引擎与渲染器（fitz 原生解析），仅以不同 key 注册。
    // 详见 docs/m9-plan.md「关键技术决策」（不加模块/依赖，多 key 路由）。

    @Provides
    @Singleton
    @IntoMap
    @FileTypeKey("epub")
    fun provideEpubDocumentEngine(engine: MuPdfDocumentEngine): DocumentEngine = engine

    @Provides
    @Singleton
    @IntoMap
    @FileTypeKey("epub")
    fun provideEpubViewerProvider(provider: MuPdfViewerProvider): DocumentViewerProvider = provider

    @Provides
    @Singleton
    @IntoMap
    @FileTypeKey("cbz")
    fun provideCbzDocumentEngine(engine: MuPdfDocumentEngine): DocumentEngine = engine

    @Provides
    @Singleton
    @IntoMap
    @FileTypeKey("cbz")
    fun provideCbzViewerProvider(provider: MuPdfViewerProvider): DocumentViewerProvider = provider

    @Provides
    @Singleton
    @IntoMap
    @FileTypeKey("xps")
    fun provideXpsDocumentEngine(engine: MuPdfDocumentEngine): DocumentEngine = engine

    @Provides
    @Singleton
    @IntoMap
    @FileTypeKey("xps")
    fun provideXpsViewerProvider(provider: MuPdfViewerProvider): DocumentViewerProvider = provider

    @Provides
    @Singleton
    fun provideDocumentEngineRouter(
        engines: Map<String, @JvmSuppressWildcards DocumentEngine>,
        viewers: Map<String, @JvmSuppressWildcards DocumentViewerProvider>,
    ): DocumentEngineRouter = SimpleDocumentEngineRouter(engines, viewers)
}

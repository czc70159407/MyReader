package com.myreader.core.document

import dagger.MapKey

/**
 * IntoMap 多绑定 key：按 fileType 把各引擎的 [DocumentEngine] 与 [DocumentViewerProvider]
 * 注册进 `Map<String, _>`（app 的 DocumentEngineModule 用 @IntoMap 装配）。
 * 官方 multibindings 契约：@MapKey（unwrapValue 默认 true，取 value 作 map key）；
 * 新增格式 = 新建 feature:viewer-xxx 并以 @FileTypeKey("xxx") 注册，reader/library 不改。
 */
@Retention(AnnotationRetention.RUNTIME)
@MapKey
annotation class FileTypeKey(val value: String)

/** 引擎/渲染路由：按 doc.fileType 取对应实现 */
interface DocumentEngineRouter {
    fun engineFor(fileType: String): DocumentEngine
    fun viewerFor(fileType: String): DocumentViewerProvider
}

/** 默认路由实现：Map 查表，未注册格式抛错 */
class SimpleDocumentEngineRouter(
    private val engines: Map<String, DocumentEngine>,
    private val viewers: Map<String, DocumentViewerProvider>,
) : DocumentEngineRouter {

    override fun engineFor(fileType: String): DocumentEngine =
        engines[fileType] ?: throw IllegalArgumentException("未注册的文档格式: $fileType")

    override fun viewerFor(fileType: String): DocumentViewerProvider =
        viewers[fileType] ?: throw IllegalArgumentException("未注册的渲染器格式: $fileType")
}

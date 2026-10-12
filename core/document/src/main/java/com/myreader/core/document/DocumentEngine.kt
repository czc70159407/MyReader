package com.myreader.core.document

/**
 * 文档引擎抽象工厂：按 fileType 打开文档。实现以 `@FileTypeKey` 多绑定注册，
 * 运行时经 [DocumentEngineRouter] 按 fileType 路由（见 [FileTypeKey]）。
 */
interface DocumentEngine {

    /**
     * 打开本地文档。
     * @param path 本地文件绝对路径（导入流程已复制到内部存储）
     * @param fileType 格式标识（"pdf"/"epub"/...），同时作为解析 magic
     * @param password 加密文档的密码；null 表示未提供
     * @return [Document] 句柄
     * @throws CorruptDocumentException 文件损坏或非支持格式
     * @throws PasswordRequiredException 加密且未提供密码 / 密码错误
     */
    fun open(path: String, fileType: String, password: String? = null): Document
}

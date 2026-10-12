package com.myreader.core.document

/**
 * 文档打开异常基类：导入验证据此分流（PasswordRequired → 入库标记 needsPassword，
 * CorruptDocument → 拒收并清理半成品），打开流程据此引导用户。
 */
sealed class DocumentException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 文档已加密：需要密码才能打开（密码缺失或认证失败） */
class PasswordRequiredException(message: String = "文档需要密码", cause: Throwable? = null) :
    DocumentException(message, cause)

/** 文件损坏或非支持格式，无法打开 */
class CorruptDocumentException(message: String = "文档损坏或无法读取", cause: Throwable? = null) :
    DocumentException(message, cause)

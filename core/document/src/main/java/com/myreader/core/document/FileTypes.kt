package com.myreader.core.document

/**
 * 多格式类型契约（M9）。单一事实源：fileType 标识 → 导入归一化 / SAF mime 白名单 / fitz 打开 magic。
 *
 * 复用同一 MuPDF 引擎（fitz 原生解析 epub/cbz/xps），故本对象只描述「格式标识 ↔ 扩展名/mime/magic」
 * 的映射，不含任何解析实现。新增格式 = 在此登记 + app 的 DocumentEngineModule 追加 @IntoMap 绑定，
 * reader/library/Room schema 均不改（工作规则 1）。
 */
object FileTypes {

    const val PDF = "pdf"
    const val EPUB = "epub"
    const val CBZ = "cbz"
    const val XPS = "xps"
    const val TXT = "txt"   // M9-F：自研扩展点格式（feature:viewer-txt，非 MuPDF）

    /** 引擎已注册的全部格式（导入期拒收未命中者，防未注册格式流入 router 崩溃）。 */
    val ALL: Set<String> = setOf(PDF, EPUB, CBZ, XPS, TXT)

    /** 流式重排格式集合（进度走 CFI 锚点，pageCount 恒 -1 不落库）。 */
    val REFLOWABLE: Set<String> = setOf(EPUB)

    /**
     * 该 fileType 的默认进度类型：epub 流式 → "cfi"，其余页码式 → "page"。
     * 入库时据此写 progressType（替换 M3 硬编码 "page"）。
     */
    fun defaultProgressType(fileType: String): String = if (fileType in REFLOWABLE) "cfi" else "page"

    /** 是否已注册格式（引擎可路由）。 */
    fun isKnown(fileType: String): Boolean = fileType in ALL

    /** 是否流式重排格式（阅读器 relayout / cfi 进度 / pageCount=-1 判定）。 */
    fun isReflowable(fileType: String): Boolean = fileType in REFLOWABLE

    /** 扩展名 → fileType（大小写不敏感；.oxps 归并到 xps）。未知返回 null。 */
    fun fromExtension(ext: String): String? = when (ext.lowercase()) {
        "pdf" -> PDF
        "epub" -> EPUB
        "cbz" -> CBZ
        "xps", "oxps" -> XPS
        "txt" -> TXT
        else -> null
    }

    /** mime → fileType。未知/空返回 null。 */
    fun fromMime(mime: String?): String? = when (mime?.lowercase()) {
        "application/pdf" -> PDF
        "application/epub+zip" -> EPUB
        "application/x-cbz", "application/vnd.comicbook+zip" -> CBZ
        "application/oxps", "application/vnd.ms-xpsdocument" -> XPS
        "text/plain" -> TXT
        else -> null
    }

    /**
     * 导入归一化：显示名后缀优先，mime 兜底；均不命中返回 null（调用方据此拒收，不复制/不入库）。
     * @param displayName SAF DISPLAY_NAME（可能无后缀）
     * @param mimeType SAF MIME（可能为空）
     */
    fun normalize(displayName: String?, mimeType: String?): String? {
        val ext = displayName
            ?.takeIf { it.contains('.') }
            ?.substringAfterLast('.', "")
            ?.lowercase()
        if (!ext.isNullOrEmpty()) {
            fromExtension(ext)?.let { return it }
            // 有扩展名但未登记（如 .log/.csv/.md）→ 拒收，不靠 mime 兜底误导入（M9 review M2）
            return null
        }
        // 无扩展名才用 mime 兜底（如 .txt 经 text/plain 导入）
        return fromMime(mimeType)
    }

    /** SAF 导入 mime 白名单（保持系统选择器干净；暂不含 application/octet-stream）。 */
    fun mimeFilters(): Array<String> = arrayOf(
        "application/pdf",
        "application/epub+zip",
        "application/x-cbz",
        "application/vnd.comicbook+zip",
        "application/oxps",
        "application/vnd.ms-xpsdocument",
        "text/plain",   // M9-F：仅「无扩展名且 mime=text/plain」按 txt 导入（有扩展名走 fromExtension 拒收）
    )

    /** fileType → 打开 magic（直传 fitz Document.openDocument；txt 不走 fitz，此值不被使用）。 */
    fun magicOf(fileType: String): String = when (fileType) {
        EPUB -> "application/epub+zip"
        CBZ -> "application/x-cbz"
        XPS -> "application/oxps"
        else -> "application/pdf"
    }
}

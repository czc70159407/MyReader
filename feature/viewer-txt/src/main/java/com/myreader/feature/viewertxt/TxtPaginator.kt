package com.myreader.feature.viewertxt

/**
 * 纯文本分页（M9-F，纯 JVM 逻辑，可单测）。
 *
 * 把整篇文本按字符预算切成若干「页」：优先在预算内的最后一个空白处断页（避免切断单词），
 * 无空白可断则硬断。空/纯空白文本返回单空页（保证 pageCount ≥ 1）。
 *
 * 归属 [com.myreader.feature.viewertxt]（新格式自带模块，验证工作规则 1 扩展点：
 * reader/library/Room schema 一行不改，仅新增本模块 + DI 绑定即可跑通导入/阅读/页码进度）。
 */
object TxtPaginator {

    /** 默认每页字符预算（约一屏 14sp 文本量；drawPage 超长按位图裁剪） */
    const val DEFAULT_CHARS_PER_PAGE = 1200

    /**
     * @param charsPerPage 每页字符预算（>0）
     * @return 分页结果；任取连续页拼接（joinToString("")）恒等于原文（不断字、不丢字）
     */
    fun paginate(text: String, charsPerPage: Int = DEFAULT_CHARS_PER_PAGE): List<String> {
        require(charsPerPage > 0) { "charsPerPage must be > 0" }
        if (text.isBlank()) return listOf("")
        val pages = mutableListOf<String>()
        var i = 0
        val n = text.length
        while (i < n) {
            if (n - i <= charsPerPage) {
                pages += text.substring(i)
                break
            }
            val windowEnd = i + charsPerPage
            // 在 [i, windowEnd) 内从后往前找最后一个空白作为断点，尽量不断词
            var breakAt = -1
            var k = windowEnd - 1
            while (k >= i) {
                if (text[k].isWhitespace()) { breakAt = k; break }
                k--
            }
            // breakAt > i 才在空白后断（含该空白）；否则（无空白或首字符即空白）硬断到窗口末
            val end = if (breakAt > i) breakAt + 1 else windowEnd
            pages += text.substring(i, end)
            i = end
        }
        return pages
    }
}

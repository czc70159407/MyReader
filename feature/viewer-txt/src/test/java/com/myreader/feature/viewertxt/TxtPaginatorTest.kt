package com.myreader.feature.viewertxt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** TxtPaginator 分页纯逻辑测试（JVM）。 */
class TxtPaginatorTest {

    @Test
    fun `空或纯空白文本返回单空页`() {
        assertEquals(listOf(""), TxtPaginator.paginate(""))
        assertEquals(listOf(""), TxtPaginator.paginate("   \n\t  "))
    }

    @Test
    fun `短于预算的文本为单页`() {
        val text = "hello world"
        assertEquals(listOf(text), TxtPaginator.paginate(text, charsPerPage = 100))
    }

    @Test
    fun `长度恰等于预算为单页`() {
        val text = "a".repeat(50)
        assertEquals(listOf(text), TxtPaginator.paginate(text, charsPerPage = 50))
    }

    @Test
    fun `拼接恒等于原文_不断字不丢字`() {
        val text = (1..5000).joinToString(" ") { "word$it" }
        val pages = TxtPaginator.paginate(text, charsPerPage = 300)
        assertEquals(text, pages.joinToString(""))
        assertTrue("每页均非空", pages.all { it.isNotEmpty() })
    }

    @Test
    fun `无空白长文本硬断且不超预算`() {
        val text = "x".repeat(1000)
        val pages = TxtPaginator.paginate(text, charsPerPage = 300)
        assertEquals(4, pages.size)                 // ceil(1000/300)
        assertTrue(pages.dropLast(1).all { it.length == 300 })
        assertEquals(100, pages.last().length)
        assertEquals(text, pages.joinToString(""))
    }

    @Test
    fun `优先在空白处断页`() {
        // "aaaa bbbb" 预算 6：窗口 [0,6) 内最后空白在 index 4 → 断成 "aaaa " + "bbbb"
        val pages = TxtPaginator.paginate("aaaa bbbb", charsPerPage = 6)
        assertEquals(listOf("aaaa ", "bbbb"), pages)
    }

    @Test
    fun `页数随内容增长`() {
        val one = TxtPaginator.paginate("a ".repeat(100), charsPerPage = 100)   // ~200 字符
        val two = TxtPaginator.paginate("a ".repeat(1000), charsPerPage = 100)
        assertTrue(two.size > one.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `非正预算抛异常`() {
        TxtPaginator.paginate("abc", charsPerPage = 0)
    }
}

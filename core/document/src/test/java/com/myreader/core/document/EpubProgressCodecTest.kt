package com.myreader.core.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** EpubProgressCodec 编解码 / 版本门禁 / 脏输入拒绝 纯逻辑测试（JVM）。 */
class EpubProgressCodecTest {

    @Test
    fun `encode decode 往返保留 loc page chapter`() {
        val s = EpubProgressCodec.encode(loc = "987654321", page = 12, chapter = "Chapter 3")
        val d = EpubProgressCodec.decode(s)!!
        assertEquals(EpubProgressCodec.VERSION, d.version)
        assertEquals("987654321", d.loc)
        assertEquals(12, d.page)
        assertEquals("Chapter 3", d.chapter)
    }

    @Test
    fun `无 loc 时仅保留 page（L3 兜底）`() {
        val s = EpubProgressCodec.encode(loc = null, page = 7)
        val d = EpubProgressCodec.decode(s)!!
        assertNull(d.loc)
        assertEquals(7, d.page)
    }

    @Test
    fun `负 page 被钳到 0`() {
        val d = EpubProgressCodec.decode(EpubProgressCodec.encode(loc = "1", page = -5))!!
        assertEquals(0, d.page)
    }

    @Test
    fun `null 与空串返回 null`() {
        assertNull(EpubProgressCodec.decode(null))
        assertNull(EpubProgressCodec.decode(""))
        assertNull(EpubProgressCodec.decode("   "))
    }

    @Test
    fun `脏 JSON 返回 null`() {
        assertNull(EpubProgressCodec.decode("not-json"))
        assertNull(EpubProgressCodec.decode("{ broken"))
        assertNull(EpubProgressCodec.decode("[]"))
    }

    @Test
    fun `版本不匹配拒绝`() {
        val future = """{"v":999,"loc":"1","page":2}"""
        assertNull(EpubProgressCodec.decode(future))
    }

    @Test
    fun `无 loc 且 page 非负视为有效的起始进度`() {
        // {"v":1} → page 默认 0：表示文档起始，属有效（可恢复到第 0 页）
        val d = EpubProgressCodec.decode("""{"v":1}""")!!
        assertNull(d.loc)
        assertEquals(0, d.page)
    }

    @Test
    fun `无 loc 且 page 为负视为脏数据`() {
        // 无 loc 兜底、page 又非法（负）→ 拒绝，避免落到负页
        assertNull(EpubProgressCodec.decode("""{"v":1,"page":-3}"""))
    }

    @Test
    fun `容忍未知字段向前兼容`() {
        val withExtra = """{"v":1,"loc":"42","page":3,"futureField":"x"}"""
        val d = EpubProgressCodec.decode(withExtra)!!
        assertEquals("42", d.loc)
        assertEquals(3, d.page)
    }

    @Test
    fun `chapter 含特殊字符可往返`() {
        val s = EpubProgressCodec.encode(loc = "9", page = 1, chapter = "第 1 章 \"引号\" \\ 斜杠")
        val d = EpubProgressCodec.decode(s)!!
        assertEquals("第 1 章 \"引号\" \\ 斜杠", d.chapter)
        assertTrue(d.loc == "9")
    }
}

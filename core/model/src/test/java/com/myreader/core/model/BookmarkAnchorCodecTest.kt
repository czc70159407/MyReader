package com.myreader.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 书签锚点编解码（M11 比例锚点 + M9/M10 十进制串兼容）纯逻辑测试。 */
class BookmarkAnchorCodecTest {

    @Test
    fun `encodeRatio 往返`() {
        val anchor = BookmarkAnchorCodec.encodeRatio(chapter = 1, pageInChapter = 2, chapterPages = 4)!!
        assertEquals("cp:1:2:4", anchor)
        val parsed = BookmarkAnchorCodec.parse(anchor)
        assertEquals(BookmarkAnchorCodec.Anchor.Ratio(1, 2, 4), parsed)
    }

    @Test
    fun `encodeRatio 分量不自洽返回 null`() {
        assertNull(BookmarkAnchorCodec.encodeRatio(chapter = -1, pageInChapter = 0, chapterPages = 4))
        assertNull(BookmarkAnchorCodec.encodeRatio(chapter = 0, pageInChapter = 0, chapterPages = 0))
        assertNull(BookmarkAnchorCodec.encodeRatio(chapter = 0, pageInChapter = 4, chapterPages = 4))
    }

    @Test
    fun `旧十进制锚点（含负值）识别为 LegacyMark`() {
        assertEquals(
            BookmarkAnchorCodec.Anchor.LegacyMark("-5476376651105784552"),
            BookmarkAnchorCodec.parse("-5476376651105784552"),
        )
        assertEquals(BookmarkAnchorCodec.Anchor.LegacyMark("0"), BookmarkAnchorCodec.parse("0"))
    }

    @Test
    fun `脏锚点识别为 Invalid`() {
        assertEquals(BookmarkAnchorCodec.Anchor.Invalid, BookmarkAnchorCodec.parse(null))
        assertEquals(BookmarkAnchorCodec.Anchor.Invalid, BookmarkAnchorCodec.parse(""))
        assertEquals(BookmarkAnchorCodec.Anchor.Invalid, BookmarkAnchorCodec.parse("12a"))
        assertEquals(BookmarkAnchorCodec.Anchor.Invalid, BookmarkAnchorCodec.parse("cp:1:2"))
        assertEquals(BookmarkAnchorCodec.Anchor.Invalid, BookmarkAnchorCodec.parse("cp:1:9:4"))
        assertEquals(BookmarkAnchorCodec.Anchor.Invalid, BookmarkAnchorCodec.parse("cp:-1:0:4"))
        assertEquals(BookmarkAnchorCodec.Anchor.Invalid, BookmarkAnchorCodec.parse("cp:0:0:0"))
    }

    @Test
    fun `同章比例相同视为同一位置（分母可不同）`() {
        // (2+0.5)/4 == (12+0.5)/20 == 0.625：排版变化后章页数不同仍判同位置
        val a = BookmarkAnchorCodec.parse(BookmarkAnchorCodec.encodeRatio(1, 2, 4)!!)
        val b = BookmarkAnchorCodec.parse(BookmarkAnchorCodec.encodeRatio(1, 12, 20)!!)
        assertTrue(BookmarkAnchorCodec.isSamePosition(a, b))
    }

    @Test
    fun `比例差在 1% 容差内视为同一位置`() {
        // (2.5/4)=0.625 vs (63.5/100)=0.635 → 差 1.0%，正好在容差边界
        val a = BookmarkAnchorCodec.parse(BookmarkAnchorCodec.encodeRatio(1, 2, 4)!!)
        val near = BookmarkAnchorCodec.parse(BookmarkAnchorCodec.encodeRatio(1, 63, 100)!!)
        assertTrue(BookmarkAnchorCodec.isSamePosition(a, near))
    }

    @Test
    fun `跨章或比例差超容差不算同一位置`() {
        val a = BookmarkAnchorCodec.parse(BookmarkAnchorCodec.encodeRatio(1, 2, 4)!!)
        val otherChapter = BookmarkAnchorCodec.parse(BookmarkAnchorCodec.encodeRatio(2, 2, 4)!!)
        val farRatio = BookmarkAnchorCodec.parse(BookmarkAnchorCodec.encodeRatio(1, 64, 100)!!)
        assertFalse(BookmarkAnchorCodec.isSamePosition(a, otherChapter))
        assertFalse(BookmarkAnchorCodec.isSamePosition(a, farRatio))
    }

    @Test
    fun `混合格式与非法锚点不与任何位置判重`() {
        val ratio = BookmarkAnchorCodec.parse(BookmarkAnchorCodec.encodeRatio(1, 2, 4)!!)
        val legacy = BookmarkAnchorCodec.parse("-5476376651105784552")
        assertFalse(BookmarkAnchorCodec.isSamePosition(ratio, legacy))
        assertFalse(BookmarkAnchorCodec.isSamePosition(legacy, ratio))
        assertFalse(BookmarkAnchorCodec.isSamePosition(legacy, BookmarkAnchorCodec.Anchor.Invalid))
    }
}

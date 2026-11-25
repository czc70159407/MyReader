package com.myreader.core.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [AnnotationAnchorV2Codec] 单测（M16）：往返 / 截断 / 非法输入拒绝 / 旧锚点降级。 */
class AnnotationAnchorV2CodecTest {

    @Test
    fun roundTrip_preservesAllFields() {
        val encoded = AnnotationAnchorV2Codec.encode(
            chapter = 2,
            pageInChapter = 5,
            quote = "Chapter 1 paragraph 0",
            pre = "before text",
            post = "after text",
        )
        val decoded = AnnotationAnchorV2Codec.decode(encoded)
        assertEquals(2, decoded?.chapter)
        assertEquals(5, decoded?.pageInChapter)
        assertEquals("Chapter 1 paragraph 0", decoded?.quote)
        assertEquals("before text", decoded?.pre)
        assertEquals("after text", decoded?.post)
    }

    @Test
    fun context_isTruncatedToTwentyChars() {
        val longPre = "p".repeat(50)
        val longPost = "q".repeat(50)
        val decoded = AnnotationAnchorV2Codec.decode(
            AnnotationAnchorV2Codec.encode(0, 0, "quote", longPre, longPost),
        )
        assertEquals(20, decoded?.pre?.length)
        assertEquals(20, decoded?.post?.length)
        assertTrue(decoded!!.pre!!.endsWith("p"))
        assertTrue(decoded.post!!.startsWith("q"))
    }

    @Test
    fun blankQuote_isRejected() {
        assertNull(AnnotationAnchorV2Codec.encode(0, 0, "   "))
    }

    @Test
    fun negativeLocation_isRejected() {
        assertNull(AnnotationAnchorV2Codec.encode(-1, 0, "quote"))
        assertNull(AnnotationAnchorV2Codec.encode(0, -1, "quote"))
    }

    @Test
    fun v1NormalizedQuads_fallsBackToNull() {
        // M12 旧锚点：归一化 quad JSON 数组（无 v 字段）→ 非 v2 → null（渲染端降级不绘制）
        assertNull(AnnotationAnchorV2Codec.decode("""[[0.1,0.2,0.3,0.2,0.1,0.25,0.3,0.25]]"""))
    }

    @Test
    fun dirtyJson_returnsNull() {
        assertNull(AnnotationAnchorV2Codec.decode("{not json"))
        assertNull(AnnotationAnchorV2Codec.decode(""))
        assertNull(AnnotationAnchorV2Codec.decode(null))
    }

    @Test
    fun futureVersion_isRejected() {
        assertNull(AnnotationAnchorV2Codec.decode("""{"v":99,"ch":0,"pi":0,"q":"quote"}"""))
    }
}

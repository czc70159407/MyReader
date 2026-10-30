package com.myreader.feature.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T7 名称规则纯 JVM 测试：控制字符过滤 / 64 字符边界 / trim 后判定。
 */
class NameRulesTest {

    @Test
    fun filterNameInput_removesControlCharsOnly() {
        assertEquals("ab", filterNameInput("a\u0001b"))
        assertEquals("ab", filterNameInput("a\tb"))
        assertEquals("a b", filterNameInput("a b"))   // 普通空格保留（提交时再 trim）
        assertEquals("中文", filterNameInput("中文"))
    }

    @Test
    fun nameInputError_acceptsBoundary_andRejectsOverflow() {
        assertNull(nameInputError("x".repeat(MAX_NAME_LEN)))
        assertEquals("名称最多 $MAX_NAME_LEN 个字符", nameInputError("x".repeat(MAX_NAME_LEN + 1)))
    }

    @Test
    fun nameInputError_ignoresSurroundingSpaces() {
        assertNull(nameInputError("  " + "x".repeat(MAX_NAME_LEN) + "  "))
    }
}

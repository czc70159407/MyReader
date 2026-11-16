package com.myreader.feature.reader

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class DetectLanguageTest {

    @Test
    fun `纯英文选 US`() {
        assertEquals(Locale.US, detectLanguage("The quick brown fox jumps over the lazy dog."))
    }

    @Test
    fun `含中文选简体中文`() {
        assertEquals(Locale.SIMPLIFIED_CHINESE, detectLanguage("这是一段中文句子。"))
    }

    @Test
    fun `空白回落英文`() {
        assertEquals(Locale.US, detectLanguage(""))
        assertEquals(Locale.US, detectLanguage("   "))
    }

    @Test
    fun `中英混排按含汉字判中文`() {
        assertEquals(Locale.SIMPLIFIED_CHINESE, detectLanguage("This is English 但包含中文。"))
    }
}

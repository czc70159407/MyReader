package com.myreader.feature.reader

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * 选中文本语言检测（M14 文本朗读）：含汉字（Han 脚本）即中文，否则英文。
 *
 * 纯函数下沉于此供 JVM 单测；[Tts] 运行时据此选 TTS 语音包。
 * 近似边界（已知限制）：日文汉字/韩文汉字归入中文；中英混排整段按「含汉字」判中文。
 */
internal fun detectLanguage(text: String): Locale =
    if (text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) {
        Locale.SIMPLIFIED_CHINESE
    } else {
        Locale.US
    }

/**
 * 系统 TTS 薄封装（M14）：Reader 会话内持有一份，退出 [shutdown]。
 *
 * `TextToSpeech` 初始化是异步的（真机验收实测：引擎未就绪即 `setLanguage` 会报
 * "not bound to TTS engine" 并静默无声）。故维护三态：
 * - PENDING：初始化未完成 → [speak] 暂存待播文本，`onInit` 成功后自动播放；
 * - READY：可播；
 * - FAILED：初始化失败（无引擎/被系统限制）→ [speak] 返回 false 供调用方提示。
 */
internal class Tts(context: Context) : TextToSpeech.OnInitListener {

    private enum class InitState { PENDING, READY, FAILED }

    private val engine = TextToSpeech(context.applicationContext, this)
    private var initState = InitState.PENDING
    /** 初始化完成前点朗读的待播文本（onInit 成功后自动播放） */
    private var pending: String? = null

    override fun onInit(status: Int) {
        initState = if (status == TextToSpeech.SUCCESS) InitState.READY else InitState.FAILED
        val text = pending
        pending = null
        if (initState == InitState.READY && text != null) speakNow(text)
    }

    /** 朗读选中文本；语音包缺失/不支持/引擎不可用返回 false（未发起朗读） */
    fun speak(text: String): Boolean {
        if (text.isBlank()) return false
        return when (initState) {
            InitState.PENDING -> {
                pending = text
                true
            }
            InitState.FAILED -> false
            InitState.READY -> speakNow(text)
        }
    }

    private fun speakNow(text: String): Boolean {
        val result = engine.setLanguage(detectLanguage(text))
        val supported = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        if (supported) {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        }
        return supported
    }

    fun stop() = engine.stop()

    fun shutdown() = engine.shutdown()

    private companion object {
        const val UTTERANCE_ID = "myreader-reader"
    }
}

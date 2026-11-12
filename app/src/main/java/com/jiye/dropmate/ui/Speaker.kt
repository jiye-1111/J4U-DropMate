package com.jiye.dropmate.ui

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** 음성 안내(안드로이드 기본 TTS, 한국어). 엔진이 준비되지 않았거나 한국어를 지원하지 않으면 조용히 건너뛴다 */
class Speaker(ctx: Context) {
    @Volatile var ready = false; private set
    private var n = 0
    private val tts: TextToSpeech = TextToSpeech(ctx.applicationContext) { st ->
        if (st == TextToSpeech.SUCCESS) {
            val r = tts.setLanguage(Locale.KOREAN)
            ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        }
    }
    fun say(text: String) { if (ready) tts.speak(text, TextToSpeech.QUEUE_ADD, null, "dm-${n++}") }
    fun stop() { runCatching { tts.stop() } }
    fun shutdown() { runCatching { tts.stop(); tts.shutdown() } }
}

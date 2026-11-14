package com.jiye.dropmate.ui

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * 음성 안내(안드로이드 기본 TTS, 한국어). Pleos SDK(NaviHelper 3.0.5·FusedLocation)에는 TTS API가 없어 기본 TTS를 쓴다
 * (Pleos 에뮬레이터에서는 caas SpeechService 가 기본 엔진). 엔진이 준비되지 않았거나 한국어를 지원하지 않으면 조용히 건너뛴다.
 * 2026-11-14: 준비·발화 시작/완료/오류를 logcat(태그 DropMateTTS)에 남겨 확인할 수 있게 함(안내 문장만, 키·위치 없음).
 */
class Speaker(ctx: Context) {
    /** 기본 엔진이 정해져 있지 않은 Pleos 에뮬레이터 대비: caas 음성 엔진이 있으면 그것을 지정 */
    private val engine: String? = runCatching { ctx.packageManager.getPackageInfo(PLEOS_TTS, 0); PLEOS_TTS }.getOrNull()
    @Volatile var ready = false; private set
    private var n = 0
    private val tts: TextToSpeech = TextToSpeech(ctx.applicationContext, { st ->
        if (st == TextToSpeech.SUCCESS) {
            val r = tts.setLanguage(Locale.KOREAN)
            ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
            Log.i(TAG, "init ok engine=${tts.defaultEngine} lang=$r ready=$ready")
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { Log.i(TAG, "start $id") }
                override fun onDone(id: String?) { Log.i(TAG, "done $id") }
                @Deprecated("Deprecated in Java") override fun onError(id: String?) { Log.w(TAG, "error $id") }
                override fun onError(id: String?, code: Int) { Log.w(TAG, "error $id code=$code") }
            })
        } else Log.w(TAG, "init failed status=$st engine=$engine")
    }, engine)
    fun say(text: String) {
        val id = "dm-${n++}"
        if (!ready) { Log.w(TAG, "skip(not ready) $id: $text"); return }
        Log.i(TAG, "speak $id: $text")
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, id)
    }
    fun stop() { runCatching { tts.stop() } }
    fun shutdown() { runCatching { tts.stop(); tts.shutdown() } }

    private companion object { const val TAG = "DropMateTTS"; const val PLEOS_TTS = "ai.pleos.playground.caas" }
}

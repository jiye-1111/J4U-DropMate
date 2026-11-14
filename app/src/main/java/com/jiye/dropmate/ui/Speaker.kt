package com.jiye.dropmate.ui

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * 음성 안내. 2026-11-14 순서:
 *  1) 차량 음성 SDK(Pleos 빌드: Pleos TextToSpeech SDK, ON_DEVICE) → 2) 안드로이드 기본 TTS(한국어) →
 *  3) 둘 다 안 되면 assets/voice 녹음 파일(Windows 한국어 음성으로 만든 주요 안내 문장)로 대체 재생.
 * logcat 태그 DropMateTTS 에 준비·발화·완료·오류를 남긴다(안내 문장만, 키·위치 없음).
 */
class Speaker(ctx: Context) {
    private val app = ctx.applicationContext
    private val queue = java.util.ArrayDeque<String>()
    private var busy = false
    private var n = 0

    @Volatile private var vehicleReady = false
    @Volatile private var androidReady = false
    private var player: android.media.MediaPlayer? = null

    private val vehicle: SpeechBackend? = com.jiye.dropmate.nav.createVehicleSpeech(app) { ok, why ->
        vehicleReady = ok; Log.i(TAG, "vehicle tts ready=$ok ($why)")
    }

    /** 안드로이드 TTS: 기본 엔진이 정해져 있지 않은 Pleos 에뮬레이터 대비 caas 엔진이 있으면 지정 */
    private val engine: String? = runCatching { app.packageManager.getPackageInfo(PLEOS_TTS, 0); PLEOS_TTS }.getOrNull()
    private val tts: TextToSpeech = TextToSpeech(app, { st ->
        if (st == TextToSpeech.SUCCESS) {
            val r = tts.setLanguage(Locale.KOREAN)
            androidReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
            Log.i(TAG, "android tts ok engine=${tts.defaultEngine} lang=$r ready=$androidReady")
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { Log.i(TAG, "start $id") }
                override fun onDone(id: String?) { Log.i(TAG, "done $id"); next() }
                @Deprecated("Deprecated in Java") override fun onError(id: String?) { Log.w(TAG, "error $id"); next() }
                override fun onError(id: String?, code: Int) { Log.w(TAG, "error $id code=$code"); next() }
            })
        } else Log.w(TAG, "android tts init failed status=$st engine=$engine")
    }, engine)

    /** 화면에서 '음성 준비됨' 판단용 */
    val ready: Boolean get() = vehicleReady || androidReady

    fun say(text: String) {
        synchronized(this) { queue.add(text); if (busy) return; busy = true }
        next()
    }

    private fun next() {
        val text = synchronized(this) { queue.poll() ?: run { busy = false; null } } ?: return
        val id = "dm-${n++}"
        when {
            vehicleReady && vehicle != null -> { Log.i(TAG, "speak(vehicle) $id: $text"); vehicle.speak(text) { next() } }
            androidReady -> { Log.i(TAG, "speak(android) $id: $text"); tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) }
            else -> playClip(id, text)
        }
    }

    /** 녹음 파일이 있는 주요 문장만 재생. 없는 문장(회전 안내 등)은 건너뛴다 */
    private fun clipFor(text: String): String? = when {
        text.contains("안내를 시작합니다") -> "start"
        text.contains("곧 하차 지점입니다") -> "soon_drop"
        text.contains("하차 지점에 도착했어요") -> "drop_arrived"
        text.contains("동승자 경로를 다시 계산했어요") -> "refreshed"
        text.contains("목적지에 도착했어요") -> "arrive"
        text.contains("경로를 벗어났어요") -> "reroute"
        else -> null
    }

    private fun playClip(id: String, text: String) {
        val c = clipFor(text) ?: run { Log.i(TAG, "clip none $id: $text"); next(); return }
        runCatching {
            val afd = app.assets.openFd("voice/$c.wav")
            val mp = android.media.MediaPlayer()
            mp.setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length); afd.close()
            mp.setOnCompletionListener { it.release(); player = null; Log.i(TAG, "clip done $c"); next() }
            mp.prepare(); mp.start(); player = mp; Log.i(TAG, "clip play $c")
        }.onFailure { Log.w(TAG, "clip fail $c ${it.javaClass.simpleName}"); player = null; next() }
    }

    fun stop() {
        synchronized(this) { queue.clear(); busy = false }
        runCatching { vehicle?.stop() }; runCatching { tts.stop() }
        runCatching { player?.stop(); player?.release() }; player = null
    }

    fun shutdown() { stop(); runCatching { vehicle?.release() }; runCatching { tts.shutdown() } }

    private companion object { const val TAG = "DropMateTTS"; const val PLEOS_TTS = "ai.pleos.playground.caas" }
}

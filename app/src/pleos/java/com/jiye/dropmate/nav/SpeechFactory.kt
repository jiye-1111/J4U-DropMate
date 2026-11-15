package com.jiye.dropmate.nav

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.util.Log
import com.jiye.dropmate.ui.SpeechBackend
import java.util.Locale

/**
 * 차량용 빌드: Pleos TextToSpeech SDK 3.3.1(ON_DEVICE 모드 → 네트워크·앱 등록 불필요). 2026-11-14 차량 SDK 사용.
 * 권한 pleos.car.permission.TTS_SERVICE(normal). 한 번에 한 문장씩 말하고, 끝(onDone/onStop/onError)나면 다음 문장.
 */
fun createVehicleSpeech(ctx: Context, onInit: (Boolean, String) -> Unit): SpeechBackend? = runCatching {
    val tts = TextToSpeech(ctx.applicationContext, Mode.ON_DEVICE)
    var finished: (() -> Unit)? = null
    fun finish() { val f = finished; finished = null; f?.invoke() }
    val listener = object : EventListener {
        override fun onReady() = Unit
        override fun onStart() { Log.i("DropMateTTS", "pleos start") }
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onDone() { Log.i("DropMateTTS", "pleos done"); finish() }
        override fun onStop() { finish() }
        override fun onError(errorType: ErrorType) { Log.w("DropMateTTS", "pleos error $errorType"); finish() }
    }
    tts.initialize(object : OnInitializeListener {
        override fun onSuccess() {
            runCatching { tts.addEventListener(listener); tts.setLocale(Locale.KOREA) }
            onInit(true, "pleos on-device")
        }
        override fun onFailure(errorType: ErrorType) { onInit(false, "pleos $errorType") }
    })
    object : SpeechBackend {
        override fun speak(text: String, onFinished: () -> Unit) { finished = onFinished; tts.speak(text) }
        override fun stop() { runCatching { tts.stop() } }
        override fun release() { runCatching { tts.removeEventListener(listener); tts.release() } }
    }
}.getOrElse { onInit(false, "pleos ${it.javaClass.simpleName}"); null }

package com.jiye.dropmate.ui

/** 차량 음성 엔진 연결부(Pleos 빌드는 Pleos TextToSpeech SDK, 일반 빌드는 없음). 2026-11-14 */
interface SpeechBackend {
    /** 한 문장 말하기. 끝나면(성공·중지·오류) onFinished 를 부른다 */
    fun speak(text: String, onFinished: () -> Unit)
    fun stop()
    fun release()
}

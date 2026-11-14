package com.jiye.dropmate.nav

import android.content.Context
import com.jiye.dropmate.ui.SpeechBackend

/** 일반 빌드: 차량 음성 SDK 없음(안드로이드 TTS 사용) */
fun createVehicleSpeech(ctx: Context, onInit: (Boolean, String) -> Unit): SpeechBackend? { onInit(false, "no vehicle sdk"); return null }

package com.jiye.dropmate

import android.app.Instrumentation
import android.os.Bundle
import java.io.File

/** 가장 최근 실행의 decision_log.md 를 앱 공유 저장소로 복사한다(API 호출 없음, 키·원문 미포함 파일). */
class DecisionLogDump : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val out = Bundle()
        try {
            val root = File(targetContext.getExternalFilesDir(null), "outputs/phase4/live")
            val latest = root.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.lastModified() } ?: error("기록 없음")
            val f = File(latest, "decision_log.md")
            val dst = File("/data/local/tmp/decision_log_latest.md")
            runCatching { f.copyTo(dst, overwrite = true) }
            out.putString("dump", "id=${latest.name} bytes=${f.length()}\n" + f.readText())
            finish(-1, out)
        } catch (e: Throwable) { out.putString("dump", "FAIL ${e.message}"); finish(0, out) }
    }
}

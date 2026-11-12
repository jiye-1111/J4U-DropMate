package com.jiye.dropmate.nav

import android.content.Context
import com.jiye.dropmate.engine.HandoffDecision
import com.jiye.dropmate.engine.LatLng

/** Pleos SDK 를 넣지 않은 기본 빌드. 어떤 경우에도 차량 내비로 전달하지 않는다. */
fun createNaviBridge(context: Context): NaviBridge = object : NaviBridge {
    override val available = false
    override val label = "Pleos SDK 미포함 빌드(-PpleosSdk=true 로 차량용 빌드)"
    override suspend fun addDropWaypoint(name: String, point: LatLng, decision: HandoffDecision): String {
        if (!decision.allowed) throw HandoffBlocked(decision.reasons)
        throw IllegalStateException(label)
    }
    override suspend fun startDropRoute(dropName: String, drop: LatLng, destName: String, dest: LatLng, decision: HandoffDecision): String {
        if (!decision.allowed) throw HandoffBlocked(decision.reasons)
        throw IllegalStateException(label)
    }
    override fun close() {}
}

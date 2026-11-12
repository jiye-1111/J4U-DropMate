package com.jiye.dropmate.nav

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import com.jiye.dropmate.engine.HandoffDecision
import com.jiye.dropmate.engine.LatLng
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

fun createNaviBridge(context: Context): NaviBridge = PleosNaviBridge(context.applicationContext)

/**
 * Pleos NaviHelper 3.0.5 연결. 메서드는 배포 AAR(javap)로 확인했다:
 * NaviHelper.initialize(onConnected, onFailed), addWaypoint(RequestWaypointInfo), addListener/removeListener, release,
 * NaviHelperEventListener.onWaypointChanged / onError.
 * 차량 실기·에뮬레이터의 실제 경유지 추가 동작은 아직 검증하지 않았다.
 * 전달은 사용자의 버튼 입력으로만 시작하며, 차단 판단이면 SDK 를 초기화하지 않는다.
 */
private class PleosNaviBridge(private val context: Context) : NaviBridge {
    override val available = true
    override val label = "Pleos NaviHelper 3.0.5"
    private var helper: NaviHelper? = null

    private suspend fun connect(): NaviHelper {
        helper?.let { return it }
        val h = NaviHelper(context)
        val ok = CompletableDeferred<Unit>()
        h.initialize(onConnected = { ok.complete(Unit) }, onFailed = { ok.completeExceptionally(it) })
        try { withTimeout(12_000) { ok.await() } } catch (e: Throwable) { h.release(); throw e }
        helper = h
        return h
    }

    override suspend fun addDropWaypoint(name: String, point: LatLng, decision: HandoffDecision): String {
        if (!decision.allowed) throw HandoffBlocked(decision.reasons)
        return withContext(Dispatchers.Main.immediate) {
            val h = connect()
            val done = CompletableDeferred<Unit>()
            val listener = object : NaviHelperEventListener {
                override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) { done.complete(Unit) }
                override fun onError(errorCode: NaviErrorCode) { done.completeExceptionally(IllegalStateException("Pleos 오류: ${errorCode.name}")) }
            }
            h.addListener(listener)
            try {
                // 기존 목적지는 유지하고 하차 지점을 첫 경유지로 추가한다.
                h.addWaypoint(RequestWaypointInfo(point.lat, point.lon, name, WaypointIndex.FIRST, "", "", "", ""))
                withTimeout(15_000) { done.await() }
                "차량 내비에 하차 경유지 추가 요청 완료 — 내비 화면에서 확인하세요"
            } finally { h.removeListener(listener) }
        }
    }

    override suspend fun startDropRoute(dropName: String, drop: LatLng, destName: String, dest: LatLng, decision: HandoffDecision): String {
        if (!decision.allowed) throw HandoffBlocked(decision.reasons)
        return withContext(Dispatchers.Main.immediate) {
            val h = connect()
            val started = CompletableDeferred<Unit>()
            val listener = object : NaviHelperEventListener {
                override fun onRouteStarted(routeStartInfo: ai.pleos.playground.navi.data.RouteStartInfo) { started.complete(Unit) }
                override fun onError(errorCode: NaviErrorCode) { started.completeExceptionally(IllegalStateException("Pleos 오류: ${errorCode.name}")) }
            }
            h.addListener(listener)
            try {
                val route = ai.pleos.playground.navi.data.RouteInfo(dest.lat, dest.lon, null, null, destName, "", "", "",
                    ai.pleos.playground.navi.constants.RouteOption.RECOMMENDED, "", false)
                // 하차 지점이 운전자 목적지와 같으면(D 하차) 경유지 없이 목적지 안내만 요청한다.
                val sameAsDest = com.jiye.dropmate.engine.Geo.haversineM(drop, dest) < 30.0
                if (sameAsDest) h.requestRoute(route)
                else h.requestRouteWithWaypoints(ai.pleos.playground.navi.data.RouteWithWaypointsRequest(route,
                    listOf(RequestWaypointInfo(drop.lat, drop.lon, dropName, WaypointIndex.FIRST, "", "", "", ""))))
                withTimeout(30_000) { started.await() }
                if (sameAsDest) "차량 내비 안내 시작: $destName (목적지에서 함께 하차)" else "차량 내비 안내 시작: ★ $dropName 경유 → $destName"
            } finally { h.removeListener(listener) }
        }
    }

    override fun close() { helper?.release(); helper = null }
}

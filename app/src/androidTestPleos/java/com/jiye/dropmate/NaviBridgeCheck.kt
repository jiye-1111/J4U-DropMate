package com.jiye.dropmate

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.app.Instrumentation
import android.os.Bundle
import com.jiye.dropmate.engine.HandoffDecision
import com.jiye.dropmate.engine.LatLng
import com.jiye.dropmate.nav.HandoffBlocked
import com.jiye.dropmate.nav.createNaviBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Pleos 에뮬레이터 전용 SDK 동작 확인(차량용 빌드에서만 컴파일).
 * 앱의 차단 규칙(NavHandoff)은 바꾸지 않는다. 여기서는 테스트 코드가 직접 '허용' 판단을 넣어
 * NaviHelper 경유지 추가가 실제로 내비에 반영되는지만 본다. 좌표는 합성 테스트 지점이며 실제 하차 추천이 아니다.
 * -e keep true 이면 확인 후 경로를 취소하지 않는다(화면 캡처용).
 */
private const val WP_LAT = 37.4005   // 합성 테스트 경유지(목적지 북쪽 약 0.6km). 하차 추천 아님
private const val WP_LON = 127.1125

class NaviBridgeCheck : Instrumentation() {
    private var keep = false
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); keep = arguments?.getString("keep") == "true"; start() }

    override fun onStart() {
        val out = Bundle()
        val log = StringBuilder()
        try {
            runBlocking {
                val bridge = createNaviBridge(targetContext)
                // 1) 차단 판단이면 SDK 를 부르지 않고 바로 거부
                val blocked = runCatching { bridge.addDropWaypoint("x", LatLng(0.0, 0.0), HandoffDecision(false, listOf("정차 적합성 미확인"))) }
                check(blocked.exceptionOrNull() is HandoffBlocked) { "차단 판단에서 거부되지 않음: ${blocked.exceptionOrNull()}" }
                log.append("blocked-ok;")

                withContext(Dispatchers.Main) {
                    val h = NaviHelper(targetContext)
                    val up = CompletableDeferred<Unit>()
                    h.initialize(onConnected = { up.complete(Unit) }, onFailed = { up.completeExceptionally(it) })
                    withTimeout(15_000) { up.await() }
                    log.append("connected;")
                    // 2) 앱 연결부로 "운전자 목적지 + 하차 경유지" 안내 요청(테스트가 '허용' 판단을 직접 넣음, 합성 좌표)
                    h.cancelRoute()
                    val msg = bridge.startDropRoute("합성 하차 테스트 지점", LatLng(WP_LAT, WP_LON), "합성 테스트 목적지", LatLng(37.3948, 127.1112), HandoffDecision(true, emptyList()))
                    log.append("start-ok;")

                    // 4) 내비가 보고하는 경유지 목록으로 반영 확인
                    val wps = CompletableDeferred<List<WaypointInfo>>()
                    val l2 = object : NaviHelperEventListener {
                        override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) { wps.complete(waypointInfoList) }
                        override fun onError(errorCode: NaviErrorCode) { wps.completeExceptionally(IllegalStateException("wp ${errorCode.name}")) }
                    }
                    h.addListener(l2)
                    h.getWaypointInfo()
                    val list = withTimeout(10_000) { wps.await() }
                    h.removeListener(l2)
                    log.append("waypoints=${list.map { "${it.name}@%.4f,%.4f".format(it.latitude, it.longitude) }};")
                    // 에뮬레이터 내비 3.0.4 는 getWaypointInfo 응답의 좌표를 0,0 으로 돌려준다(2026-11-12 관찰). 이름으로 판정하고 좌표는 기록만 한다.
                    check(list.any { it.name == "합성 하차 테스트 지점" }) { "경유지 목록에 추가 지점 없음" }
                    if (!keep) h.cancelRoute()
                    h.release()
                    bridge.close()
                    log.append("msg=$msg")
                }
            }
            out.putString("navi_check", "PASS: $log")
            finish(-1, out)
        } catch (e: Throwable) {
            out.putString("navi_check", "FAIL: ${e.javaClass.simpleName}: ${e.message} | $log")
            finish(0, out)
        }
    }
}

package com.jiye.dropmate

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import com.jiye.dropmate.engine.*
import com.jiye.dropmate.ui.AppViewModel
import com.jiye.dropmate.ui.Place

/**
 * 합성 결과(강남역→삼성역 테헤란로 직선 근사, TMAP 호출 없음)를 띄워 안내 중 3D 건물 화면을 확인한다.
 * 경로는 실제 도로 선형이 아닌 직선 근사이며 시연·화면 검사용이다. -e hold <초>
 */
class DemoNavCheck : Instrumentation() {
    private var hold = 600
    private var mode = "result"
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); hold = arguments?.getString("hold")?.toIntOrNull() ?: 600; mode = arguments?.getString("mode") ?: "result"; start() }
    override fun onStart() {
        val out = Bundle()
        try {
            val a = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val o = LatLng(37.4979, 127.0276); val dst = LatLng(37.5089, 127.0631)
            val n = 60; val total = 600
            val pts = (0..n).map { i -> LatLng(o.lat + (dst.lat - o.lat) * i / n, o.lon + (dst.lon - o.lon) * i / n) }
            val dist = Geo.haversineM(o, dst)
            val cumD = (0..n).map { dist * it / n }; val cumT = (0..n).map { total.toDouble() * it / n }
            val traffic = (0 until n).map { if (it in 10..20) 4 else if (it in 21..30) 2 else 1 }
            val turns = listOf(TurnPoint(pts[0], 0.0, 200, null, "테헤란로", null), TurnPoint(pts[40], cumD[40], 13, null, "선릉로", null), TurnPoint(dst, dist, 201, null, null, null))
            val base = CarRoute(total, dist.toInt(), pts, cumT, cumD, emptyList(), turns, traffic)
            val t0 = System.currentTimeMillis() / 1000
            val drop = pts[30]
            // -e mode label: 문 앞 경유 하차점 짧은 이름 확인(2026-11-13, 합성·호출 없음)
            val plan = if (mode == "label") PlannedCandidate("P-VIA", CandidateType.P_VIA, drop, "아크로리버파크 게이트1 앞", null, null, null, GtfsLink.NOT_FOUND, null,
                PrefilterState.QUERY_TARGET, "demo", 0.0, stopSuitability = StopSuitability.DESTINATION)
            else PlannedCandidate("M-demo", CandidateType.MIDDLE, drop, "역삼역 인근 경로상 하차", cumT[30], null, null, GtfsLink.DIRECT, 0,
                PrefilterState.QUERY_TARGET, "demo", 0.0, stopSuitability = StopSuitability.ROAD_OK)
            val best = CandidateResult(plan, CandStatus.EVALUATED, "합성", dropCompleteEpochSec = t0 + 360, driverAddSec = 60, driverArrivalEpochSec = t0 + total + 60,
                passengerArrivalEpochSec = t0 + 1500, totalWalkSec = 300, transferCount = 1)
            val r = RecommendResult("DEMO-3D", "demo", t0, "synthetic", Verdict.RECOMMEND, "합성 시연", best, "합성", true, 600,
                SoloResult(CandStatus.EVALUATED, "합성", t0 + 2100), base, listOf(best), emptyMap(), emptyList(), 0, 0, null, emptyList())
            if (mode == "places") {
                // 동승자 없이: 출발지·운전자 목적지만 넣는다(호출 없음). 화면에서 '안내 시작'을 누르면 TMAP 1회
                runOnMainSync {
                    val vm = ViewModelProvider(a)[AppViewModel::class.java]
                    vm.setPlace(com.jiye.dropmate.ui.Role.ORIGIN, Place("강남역", o, "demo"))
                    vm.setPlace(com.jiye.dropmate.ui.Role.DRIVER, Place("삼성역", dst, "demo"))
                }
                out.putString("demo_nav", "places"); Thread.sleep(hold * 1000L); finish(-1, out); return
            }
            runOnMainSync {
                val vm = ViewModelProvider(a)[AppViewModel::class.java]
                vm.showSyntheticResult(Place("강남역 (합성)", o, "demo"), Place("삼성역 (합성)", dst, "demo"), Place("동승자 목적지 (합성)", LatLng(37.52, 127.04), "demo"), r)
            }
            out.putString("demo_nav", "shown")
            Thread.sleep(hold * 1000L)
            finish(-1, out)
        } catch (e: Throwable) { out.putString("demo_nav", "FAIL ${e.javaClass.simpleName}: ${e.message}"); finish(0, out) }
    }
}

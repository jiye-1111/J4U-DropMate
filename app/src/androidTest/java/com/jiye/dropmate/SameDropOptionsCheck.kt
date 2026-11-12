package com.jiye.dropmate

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import com.jiye.dropmate.data.DecisionLog
import com.jiye.dropmate.engine.*
import com.jiye.dropmate.ui.MapPane
import com.jiye.dropmate.ui.RouteComparison

/**
 * 같은 하차점 경로 선택지(빠른 도착·최소 환승·최소 보행)와 배지 병합 화면을 합성 결과로 그린다.
 * 실제 API 호출 없음. 표시 수치는 합성값이며 실제 경로가 아니다.
 */
class SameDropOptionsCheck : Instrumentation() {
    /** -e map false 로 지도 숨김, -e scroll <px> 로 목록 시작 위치 지정(캡처용) */
    private var showMap = true
    private var scrollTo = 0
    /** -e tab FEWEST_TRANSFERS 등으로 동승자 경로 탭 선택 */
    private var tab = RouteOptionKind.FASTEST
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        showMap = arguments?.getString("map") != "false"
        scrollTo = arguments?.getString("scroll")?.toIntOrNull() ?: 0
        tab = arguments?.getString("tab")?.let { t -> RouteOptionKind.entries.firstOrNull { it.name == t } } ?: RouteOptionKind.FASTEST
        start()
    }
    override fun onStart() {
        val out = Bundle()
        try {
            val t = 1800000000L
            val s = LatLng(37.55, 126.93)
            fun itin(total: Int, walk: Int, tr: Int, vararg legs: TransitLeg) = TransitItinerary(total, walk, walk, tr, legs.toList())
            fun w(sec: Int) = TransitLeg(LegMode.WALK, null, "a", "b", null, null, sec, sec)
            fun sub(r: String, sec: Int, c: String) = TransitLeg(LegMode.SUBWAY, r, "역", "역", null, null, 5000, sec, routeColor = c)
            fun bus(r: String, sec: Int, c: String) = TransitLeg(LegMode.BUS, r, "정류장", "정류장", null, null, 3000, sec, routeColor = c)
            // 0: 빠르지만 환승 2회, 1: 환승 0회 느림·보행 짧음(최소 환승+최소 보행 병합), 2: 다른 여정
            val ev = listOf(
                EvaluatedItinerary(0, itin(1500, 420, 2, w(240), sub("2호선", 500, "00A84D"), bus("2012", 400, "5BB025"), w(180), sub("5호선", 180, "996CAC")), t + 1680, CandStatus.EVALUATED, "합성"),
                EvaluatedItinerary(1, itin(1860, 120, 0, w(60), bus("7016", 1740, "3D5BAB"), w(60)), t + 2040, CandStatus.EVALUATED, "합성"),
                EvaluatedItinerary(2, itin(1700, 300, 1, w(150), sub("2호선", 900, "00A84D"), bus("7737", 500, "5BB025"), w(150)), t + 1880, CandStatus.EVALUATED, "합성"),
            )
            val options = RouteOptions.select(ev)
            check(options.size == 2) { "선택지 수 ${options.size}" }
            check(options[0].kinds == listOf(RouteOptionKind.FASTEST)) { "첫 줄 ${options[0].kinds}" }
            check(options[1].kinds == listOf(RouteOptionKind.FEWEST_TRANSFERS, RouteOptionKind.LEAST_WALK)) { "둘째 줄 ${options[1].kinds}" }

            // TMAP 자동차 응답 해석: 구간 roadType·facilityType 저장(합성 JSON, tmapRouteDoc 응답 형식)
            val carJson = """{"type":"FeatureCollection","features":[
              {"type":"Feature","geometry":{"type":"Point","coordinates":[127.0,37.5]},"properties":{"totalDistance":2000,"totalTime":300,"pointType":"S"}},
              {"type":"Feature","geometry":{"type":"LineString","coordinates":[[127.0,37.5],[127.01,37.5]]},"properties":{"time":100,"roadType":5,"facilityType":0}},
              {"type":"Feature","geometry":{"type":"LineString","coordinates":[[127.01,37.5],[127.02,37.5]]},"properties":{"time":200,"roadType":0,"facilityType":2}}]}"""
            val car = (com.jiye.dropmate.data.TmapParser.car(carJson) as ProviderResult.Ok).value
            check(car.segRoad == listOf(RoadSeg(5, 0), RoadSeg(0, 2))) { "구간 코드 ${car.segRoad}" }
            check(roadStopSuitability(car.segRoad[0], Policy()) == StopSuitability.ROAD_OK && roadStopSuitability(car.segRoad[1], Policy()) == StopSuitability.NO_STOP) { "하차 가능 판정" }

            val plan = PlannedCandidate("synthetic", CandidateType.MIDDLE, s, "합성역 인근 하차", 120.0, null, null,
                GtfsLink.DIRECT, 0, PrefilterState.QUERY_TARGET, "test", 0.0, stopSuitability = StopSuitability.ROAD_OK)
            val chosen = ev[0]
            val best = CandidateResult(plan, CandStatus.EVALUATED, "합성 검증", dropCompleteEpochSec = t + 180, driverAddSec = 60,
                driverArrivalEpochSec = t + 1860, passengerArrivalEpochSec = chosen.arrivalEpochSec, totalWalkSec = 420,
                transferCount = 2, chosen = chosen, itineraries = ev, routeOptions = options)
            val base = CarRoute(1800, 5000, listOf(LatLng(37.54, 126.94), s, LatLng(37.57, 126.95)), listOf(0.0, 120.0, 1800.0), listOf(0.0, 1000.0, 5000.0))
            // 여러 하차점 비교용 합성 후보: 2위(평가 완료), D 하차(평가 완료), P 경유(제외)
            val s2 = LatLng(37.56, 126.945)
            val second = CandidateResult(PlannedCandidate("M-2", CandidateType.MIDDLE, s2, "합성2역 인근 하차", 900.0, null, null,
                GtfsLink.DIRECT, 0, PrefilterState.QUERY_TARGET, "t2", 0.0), CandStatus.EVALUATED, "합성",
                dropCompleteEpochSec = t + 960, driverAddSec = 60, driverArrivalEpochSec = t + 1860,
                passengerArrivalEpochSec = t + 1920, totalWalkSec = 300, transferCount = 1)
            val dDrop = CandidateResult(PlannedCandidate("D-DROP", CandidateType.D_DROP, LatLng(37.57, 126.95), "운전자 목적지까지 동승 후 이동", 1800.0, null, null,
                GtfsLink.DIRECT, 0, PrefilterState.QUERY_TARGET, "D", 0.0, stopSuitability = StopSuitability.DESTINATION), CandStatus.EVALUATED, "합성",
                dropCompleteEpochSec = t + 1860, driverAddSec = 60, driverArrivalEpochSec = t + 1860,
                passengerArrivalEpochSec = t + 2400, totalWalkSec = 240, transferCount = 0)
            val pVia = CandidateResult(PlannedCandidate("P-VIA", CandidateType.P_VIA, LatLng(37.56, 126.91), "동승자 목적지 문 앞 경유", null, null, null,
                GtfsLink.DIRECT, null, PrefilterState.QUERY_TARGET, "P", 0.0, stopSuitability = StopSuitability.DESTINATION), CandStatus.EXCLUDED_DETOUR, "운전자 추가 1240초 > 900초 (합성)")
            val r = RecommendResult("UI-SAMEDROP", "test", t, "synthetic", Verdict.RECOMMEND, "합성 데이터 화면 검사", best, "합성 후보", true, 360,
                SoloResult(CandStatus.EVALUATED, "합성", t + 2040, ev[1]), base, listOf(best, pVia, dDrop, second), emptyMap(), emptyList(), 0, 0, null, emptyList())
            val ranking = r.dropRanking()
            check(ranking.map { it.candidate.plan.id } == listOf("synthetic", "M-2", "D-DROP", "P-VIA")) { "비교 순서 ${ranking.map { it.candidate.plan.id }}" }
            check(ranking.last().rank == null) { "제외 후보 순위 없음" }

            val md = DecisionLog.write(targetContext, r, emptyMap())
            val text = md.readText()
            check(text.contains("같은 하차점 경로 선택지")) { "판단 기록 섹션 누락" }
            check(text.contains("[최소환승·최소도보]")) { "판단 기록 배지 병합 누락" }
            md.delete()

            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            runOnMainSync {
                activity.setContent {
                    MaterialTheme(colorScheme = darkColorScheme()) {
                        Surface(Modifier.fillMaxSize()) {
                            Column(Modifier.fillMaxSize().padding(16.dp)) {
                                Text("합성 데이터 · 실제 경로 아님")
                                var kind by remember { mutableStateOf(tab) }
                                val legs = RouteOptions.pick(options, kind)?.itinerary?.itinerary?.legs
                                MapPane(Modifier.fillMaxWidth().height(if (showMap) 300.dp else 0.dp).clipToBounds(), emptyMap(), r, null, emptyList(), {}, legs) {}
                                Column(Modifier.weight(1f).verticalScroll(rememberScrollState(scrollTo))) { RouteComparison(r, kind) { kind = it } }
                            }
                        }
                    }
                }
            }
            waitForIdleSync()
            // 화면 캡처 시간을 확보한다(adb screencap).
            Thread.sleep(8000)
            out.putString("same_drop_check", "PASS: 2 options, badges merged, 4 drop points ranked, decision log verified, TMAP road codes parsed; zero route API calls")
            finish(-1, out)
        } catch (e: Throwable) {
            out.putString("same_drop_check", "FAIL: ${e.javaClass.simpleName}: ${e.message}")
            finish(0, out)
        }
    }
}

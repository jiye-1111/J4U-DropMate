package com.jiye.dropmate

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import com.jiye.dropmate.engine.*
import com.jiye.dropmate.ui.AppViewModel
import com.jiye.dropmate.ui.Place
import com.jiye.dropmate.ui.Role

/**
 * 실제 TMAP 1회 실행(2026-11-12). 앱 화면 그대로 결과를 띄우고 요약만 출력한다(키·응답 원문 미출력).
 * 좌표는 앱에 들어 있는 GTFS 역 좌표(남동인더스파크 / 논현(7호선) / 망포). -e hold <초> 로 화면 유지.
 */
class LiveRunCheck : Instrumentation() {
    private var hold = 90
    private var case = "nd"
    private var arguments0: Bundle? = null
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments); arguments0 = arguments; hold = arguments?.getString("hold")?.toIntOrNull() ?: 90
        case = arguments?.getString("case") ?: "nd"; start()
    }
    /** 시연 후보 동선(좌표는 앱 GTFS 정류장·역 좌표) */
    private val cases = mapOf(
        "nd" to listOf(Place("남동인더스파크역 (GTFS 좌표)", LatLng(37.407729, 126.69525), "gtfs"),
            Place("논현역 7호선 (GTFS 좌표)", LatLng(37.511223, 127.021779), "gtfs"), Place("망포역 (GTFS 좌표)", LatLng(37.245837, 127.056822), "gtfs")),
        "snu" to listOf(Place("서울대학교 정류장 (GTFS 좌표)", LatLng(37.466933, 126.948817), "gtfs"),
            Place("강남역 2호선 (GTFS 좌표)", LatLng(37.498103, 127.028079), "gtfs"), Place("홍대입구역 2호선 (GTFS 좌표)", LatLng(37.556965, 126.92391), "gtfs")),
        "yuldong" to listOf(Place("율동공원 정류장 (GTFS 좌표)", LatLng(37.37275, 127.14645), "gtfs"),
            Place("판교역 신분당선 (GTFS 좌표)", LatLng(37.394763, 127.111224), "gtfs"), Place("왕십리역 수인분당선 (GTFS 좌표)", LatLng(37.561532, 127.03853), "gtfs")),
    )
    override fun onStart() {
        val out = Bundle()
        // -e kq "검색어1|검색어2": 카카오 키워드 검색 결과(이름·분류·좌표)만 출력하고 끝낸다. TMAP 호출 없음.
        arguments0?.getString("kq")?.let { kq ->
            try {
                val key = com.jiye.dropmate.data.AppKeyStore(targetContext).kakaoRestApiKey() ?: error("카카오 키 없음")
                val s = StringBuilder()
                kq.split("|").map { it.trim() }.filter { it.isNotEmpty() }.forEach { q ->
                    val ps = kotlinx.coroutines.runBlocking { com.jiye.dropmate.data.KakaoPlaceSearch().search(key, q, 1) }.places
                    s.append("\nQ $q (${ps.size})")
                    ps.take(8).forEach { p -> s.append("\n  ${p.name} | ${p.category} | ${p.position.lat},${p.position.lon}") }
                }
                out.putString("kakao", s.toString())
                finish(-1, out)
            } catch (e: Throwable) { out.putString("kakao", "FAIL: ${e.javaClass.simpleName}: ${e.message}"); finish(0, out) }
            return
        }
        try {
            val a = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            lateinit var vm: AppViewModel
            runOnMainSync { vm = ViewModelProvider(a)[AppViewModel::class.java] }
            // case=user: 입력한 장소명을 카카오 장소 검색 첫 결과로 좌표화(검색 3회)
            val queries = mapOf("user" to listOf("푸조비즈타워", "관악동부센트레빌아파트", "신구강변연가아파트"),
                "seomun" to listOf("서문여자고등학교", "아크로리버파크", "래미안원베일리"))
            val qArg = arguments0?.getString("q")?.split("|")?.map { it.trim() }?.takeIf { it.size == 3 }
            val chosen: List<Place> = if (case in queries || qArg != null) {
                val key = com.jiye.dropmate.data.AppKeyStore(targetContext).kakaoRestApiKey() ?: error("카카오 키 없음")
                val ks = com.jiye.dropmate.data.KakaoPlaceSearch()
                (qArg ?: queries.getValue(case)).map { q ->
                    val pl = kotlinx.coroutines.runBlocking { ks.search(key, q, 1) }.places.firstOrNull() ?: error("검색 결과 없음: $q")
                    // -e noent 1: 입구 끄기(A/B 비교용). 앱은 카카오 장소에만 입구를 쓰므로 source 만 바꿔 입구·입구 검색을 모두 건너뛴다.
                    Place("${pl.name} · ${pl.address ?: ""}", pl.position, if (arguments0?.getString("noent") == "1") "test-noentrance" else "kakao", category = pl.category)
                }
            } else cases.getValue(case)
            // GTFS 로드 완료까지 대기(호출 전)
            var w0 = 0; var loading = true
            while (w0 < 60) { runOnMainSync { loading = vm.gtfsLoading }; if (!loading) break; Thread.sleep(1000); w0++ }
            check(!loading) { "GTFS 로드 대기 초과" }
            val sent0 = vm.ledger.totalSent()
            runOnMainSync {
                val c = chosen
                vm.setPlace(Role.ORIGIN, c[0]); vm.setPlace(Role.DRIVER, c[1]); vm.setPlace(Role.PASSENGER, c[2])
                vm.runConfigured()
            }
            var waited = 0
            while (waited < 120) { Thread.sleep(1000); waited++; var busy = true; runOnMainSync { busy = vm.running }; if (!busy) break }
            var r: RecommendResult? = null; var msg: String? = null
            runOnMainSync { r = vm.result; msg = vm.message }
            val res = r
            val s = StringBuilder()
            s.append("case=$case places=${chosen.map { "${it.label}@${it.pos}" }}; ")
            s.append("cats=${chosen.map { it.category?.substringBefore('|') }}; ")
            if (res == null) s.append("NO_RESULT msg=$msg")
            else {
                val base = res.baseline
                val segs = base?.segRoad.orEmpty()
                s.append("verdict=${res.verdict} reason=${res.verdictReason}; calls=${res.callsSent} halted=${res.haltedBy}; ")
                s.append("baseSegs=${segs.size}/${(base?.points?.size ?: 1) - 1} withRoadType=${segs.count { it.roadType != null }} withFacility=${segs.count { it.facilityType != null }} ")
                s.append("roadTypes=${segs.mapNotNull { it.roadType }.groupingBy { it }.eachCount()} facility=${segs.mapNotNull { it.facilityType }.groupingBy { it }.eachCount()}; ")
                s.append("prefilter=${res.prefilterCounts}; ")
                res.dropRanking().forEach { d -> val c = d.candidate
                    if (d.rank == null && c.status == CandStatus.NOT_QUERIED_BUDGET) return@forEach
                    s.append("[${d.rank ?: "-"} ${c.plan.type} ${c.plan.label} ${c.status} suit=${c.plan.stopSuitability} pArr=${c.passengerArrivalEpochSec?.let { it - res.t0EpochSec }} add=${c.driverAddSec} opts=${c.routeOptions.map { o -> o.kinds.map { it.name } }} legsWithShape=${c.routeOptions.map { o -> o.itinerary.itinerary.legs.count { it.shape.size >= 2 } }}] ")
                }
                s.append("solo=${res.solo?.arrivalEpochSec?.let { it - res.t0EpochSec }} handoff=${NavHandoff.decide(res, res.best)}; ")
                // 상위 3 후보의 동승자 하차 후 경로(빠른 도착 여정) 요약
                res.dropRanking().filter { it.rank != null }.take(3).forEach { d -> val c = d.candidate
                    val legs = c.chosen?.itinerary?.legs.orEmpty()
                    s.append("\nROUTE ${d.rank} ${c.plan.label}: " + (if (legs.isEmpty()) "문 앞(대중교통 없음)" else legs.joinToString(" → ") { l ->
                        "${l.mode}${l.route?.let { "($it)" } ?: ""} ${l.startName ?: ""}→${l.endName ?: ""} ${l.sectionTimeSec?.let { "${it}s" } ?: ""}${l.distanceM?.let { " ${it}m" } ?: ""}" }))
                }
                res.dropRanking().firstOrNull { it.candidate.plan.type == CandidateType.P_VIA }?.candidate?.let { c ->
                    s.append("\nPVIA ${c.plan.label} ${c.status} add=${c.driverAddSec} reason=${c.reason} note=${c.plan.note}")
                    // 문 앞 경유 구간별 시간: O→입구, 입구→운전자 목적지 (이미 조회한 경로, 추가 호출 없음)
                    s.append("\nPVIA_SEG drop=${c.plan.dropPoint} O→P=${c.carToDrop?.totalTimeSec}s/${c.carToDrop?.totalDistanceM}m P→D=${c.carAfterDrop?.totalTimeSec ?: "미조회"}s/${c.carAfterDrop?.totalDistanceM ?: "-"}m BASE=${res.baseline?.totalTimeSec}s/${res.baseline?.totalDistanceM}m")
                }
                // 내비(Pleos·앱 안내)로 넘기는 좌표: 경유지 = 1위 하차점, 목적지 = 그 후보의 운전자 입구
                s.append("\nNAV_HANDOFF via=${res.best?.plan?.dropPoint} dest=${res.driverDestFor(res.best)} destLabel=${res.best?.driverDestLabel ?: "기본 운전자 입구"} input=${chosen[1].pos}")
                s.append("\nLEDGER_DELTA=${vm.ledger.totalSent() - sent0} (엔진 calls=${res.callsSent}, 차이=POI)")
                res.notes.forEach { s.append("\nNOTE ").append(it) }
            }
            out.putString("live_run", s.toString())
            android.util.Log.i("DropMateLive", s.toString())
            Thread.sleep(hold * 1000L)
            finish(-1, out)
        } catch (e: Throwable) {
            out.putString("live_run", "FAIL: ${e.javaClass.simpleName}: ${e.message}")
            finish(0, out)
        }
    }
}

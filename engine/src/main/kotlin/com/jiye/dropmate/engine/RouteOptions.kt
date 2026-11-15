package com.jiye.dropmate.engine

/**
 * 같은 하차점(같은 대중교통 조회 결과) 안에서 고르는 경로 기준.
 * 하차점 간 순위는 RankMode(최소 시간/최소 환승, DropComparison)로 정한다(2026-11-14).
 * FEWEST_TRANSFERS·LEAST_WALK 는 같은 하차점의 참고 선택지(하차점 순위 근거 아님).
 */
enum class RouteOptionKind(val label: String) {
    FASTEST("최소시간"),
    FEWEST_TRANSFERS("최소환승"),
    LEAST_WALK("최소도보"),
}

/** 하나의 여정과, 그 여정이 해당하는 기준(배지) 목록. 같은 여정은 배지를 합쳐 한 번만 둔다. */
data class RouteOption(
    val kinds: List<RouteOptionKind>,
    val itinerary: EvaluatedItinerary,
)

/** 대표 여정 고르기 허용 범위(초): 가장 빠른 여정보다 이 값 이내로 늦으면 환승 적은 여정을 고른다 */
const val ROUTE_TRANSFER_TOLERANCE_SEC = 60L

object RouteOptions {
    /**
     * 같은 출발점(하차점·각자 가기)에서 대표 여정 고르기(2026-11-14 결정: 1분 안 차이면 환승이 적은 여정):
     * 가장 빠른 여정 도착 + [ROUTE_TRANSFER_TOLERANCE_SEC] 이내 여정 중 환승 적은 순 → 도보 적은 순 → 도착 빠른 순 → 응답 순서.
     */
    fun pickFastest(evaluated: List<EvaluatedItinerary>): EvaluatedItinerary? {
        val ok = evaluated.filter { it.status == CandStatus.EVALUATED && it.arrivalEpochSec != null }
        val first = ok.minOfOrNull { it.arrivalEpochSec!! } ?: return null
        return ok.filter { it.arrivalEpochSec!! - first <= ROUTE_TRANSFER_TOLERANCE_SEC }.minWith(compareBy<EvaluatedItinerary>(
            { it.itinerary.transferCount ?: Int.MAX_VALUE }, { it.itinerary.totalWalkTimeSec ?: Int.MAX_VALUE }, { it.arrivalEpochSec!! }, { it.index }))
    }

    /**
     * 평가 완료(EVALUATED) 여정만 대상으로 세 기준의 대표 여정을 고른다.
     * - FASTEST: 가장 빠른 도착 + 60초 이내 중 환승 수 → 총 보행시간 → 도착 → 응답 순서(pickFastest). 엔진의 chosen 과 같은 기준.
     * - FEWEST_TRANSFERS: 환승 수 → 도착시각 → 총 보행시간 → 응답 순서. 환승 수 누락 여정은 대상 아님(0으로 간주하지 않음).
     * - LEAST_WALK: 총 보행시간 → 도착시각 → 환승 수 → 응답 순서. 보행시간 누락 여정은 대상 아님.
     * 결과는 FASTEST 가 들어간 항목이 먼저 오고, 같은 여정은 배지를 병합한다.
     * 동일 여정 판정: 같은 응답 순서(index)이거나, 구간 구성(수단·노선·출발/도착 이름·구간 시간)과 도착시각이 모두 같은 경우.
     */
    fun select(evaluated: List<EvaluatedItinerary>): List<RouteOption> {
        val ok = evaluated.filter { it.status == CandStatus.EVALUATED && it.arrivalEpochSec != null }
        if (ok.isEmpty()) return emptyList()
        val big = Int.MAX_VALUE
        // 2026-11-14: 최소시간 = 가장 빠른 도착 + 60초 안에서 환승 → 도보 → 도착. 엔진의 하차점 대표 여정(chosen)과 같은 기준
        val fastest = pickFastest(ok)!!
        val fewest = ok.filter { it.itinerary.transferCount != null }.minWithOrNull(compareBy<EvaluatedItinerary>(
            { it.itinerary.transferCount!! }, { it.arrivalEpochSec!! }, { it.itinerary.totalWalkTimeSec ?: big }, { it.index }))
        val leastWalk = ok.filter { it.itinerary.totalWalkTimeSec != null }.minWithOrNull(compareBy<EvaluatedItinerary>(
            { it.itinerary.totalWalkTimeSec!! }, { it.arrivalEpochSec!! }, { it.itinerary.transferCount ?: big }, { it.index }))

        val out = mutableListOf<Pair<EvaluatedItinerary, MutableList<RouteOptionKind>>>()
        fun put(e: EvaluatedItinerary?, k: RouteOptionKind) {
            if (e == null) return
            val same = out.firstOrNull { sameRoute(it.first, e) }
            if (same != null) same.second += k else out += e to mutableListOf(k)
        }
        put(fastest, RouteOptionKind.FASTEST)
        put(fewest, RouteOptionKind.FEWEST_TRANSFERS)
        put(leastWalk, RouteOptionKind.LEAST_WALK)
        return out.map { RouteOption(it.second.toList(), it.first) }
    }

    /** 탭(기준)에 해당하는 여정. 해당 기준의 값이 없어 고를 수 없으면 null */
    fun pick(options: List<RouteOption>, kind: RouteOptionKind): RouteOption? = options.firstOrNull { kind in it.kinds }

    fun sameRoute(a: EvaluatedItinerary, b: EvaluatedItinerary): Boolean =
        a.index == b.index || (a.arrivalEpochSec == b.arrivalEpochSec && signature(a.itinerary) == signature(b.itinerary))

    private fun signature(i: TransitItinerary): List<Any?> =
        i.legs.map { listOf(it.mode, it.route, it.startName, it.endName, it.sectionTimeSec) }
}

/**
 * 2026-11-14 버스 묶음 표시: 버스 구간마다 같은 승차·하차 정류장으로 가는 다른 버스 번호를 붙인다.
 * 자료: TMAP 응답 구간의 Lane 목록 + 같은 응답의 다른 여정 버스 구간(승차·하차 정류장 이름이 같은 것). 추가 호출 없음, 시간 계산은 그대로.
 */
object BusAlternatives {
    fun attach(list: List<TransitItinerary>): List<TransitItinerary> {
        val pool = HashMap<Pair<String, String>, LinkedHashSet<String>>()
        for (it in list) for (l in it.legs) if (l.mode == LegMode.BUS && l.startName != null && l.endName != null) {
            val set = pool.getOrPut(l.startName to l.endName) { LinkedHashSet() }
            l.route?.let { r -> set += r }; set += l.altRoutes
        }
        return list.map { it.copy(legs = it.legs.map { l ->
            if (l.mode != LegMode.BUS || l.startName == null || l.endName == null) l
            else l.copy(altRoutes = (l.altRoutes + pool[l.startName to l.endName].orEmpty()).distinct().filter { r -> r != l.route })
        }) }
    }
}

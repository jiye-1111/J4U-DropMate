package com.jiye.dropmate.engine

/**
 * 같은 하차점(같은 대중교통 조회 결과) 안에서 고르는 경로 기준.
 * 하차점 간 순위·추천 판정은 계속 FASTEST(동승자 최종 도착)만 사용한다.
 * FEWEST_TRANSFERS·LEAST_WALK 는 같은 하차점의 참고 선택지이며 순위 근거가 아니다.
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

object RouteOptions {
    /**
     * 평가 완료(EVALUATED) 여정만 대상으로 세 기준의 대표 여정을 고른다.
     * - FASTEST: 도착시각 → 총 보행시간(누락 후순위) → 환승 수(누락 후순위) → 응답 순서. 기존 엔진의 chosen 과 같은 기준.
     * - FEWEST_TRANSFERS: 환승 수 → 도착시각 → 총 보행시간 → 응답 순서. 환승 수 누락 여정은 대상 아님(0으로 간주하지 않음).
     * - LEAST_WALK: 총 보행시간 → 도착시각 → 환승 수 → 응답 순서. 보행시간 누락 여정은 대상 아님.
     * 결과는 FASTEST 가 들어간 항목이 먼저 오고, 같은 여정은 배지를 병합한다.
     * 동일 여정 판정: 같은 응답 순서(index)이거나, 구간 구성(수단·노선·출발/도착 이름·구간 시간)과 도착시각이 모두 같은 경우.
     */
    fun select(evaluated: List<EvaluatedItinerary>): List<RouteOption> {
        val ok = evaluated.filter { it.status == CandStatus.EVALUATED && it.arrivalEpochSec != null }
        if (ok.isEmpty()) return emptyList()
        val big = Int.MAX_VALUE
        // 2026-11-12 최적성 감사: 도착·보행이 같으면 환승 적은 여정(지배되지 않는 여정)을 대표로
        val fastest = ok.minWith(compareBy<EvaluatedItinerary>(
            { it.arrivalEpochSec!! }, { it.itinerary.totalWalkTimeSec ?: big }, { it.itinerary.transferCount ?: big }, { it.index }))
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

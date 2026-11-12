package com.jiye.dropmate.engine

/**
 * 하차점 정차 가능 상태. 2026-11-12 사용자 결정: 터널·고속도로처럼 실제로 내려줄 수 없는 도로만 제외한다.
 * 판단 근거는 이미 조회한 TMAP 자동차 경로의 구간 도로·시설물 코드다(추가 호출 없음).
 */
enum class StopSuitability {
    /** 도로 정보가 없어 판단할 수 없음 → 길안내 전달 차단 */
    UNVERIFIED,
    /** TMAP 구간 코드상 일반도로(고속도로·자동차전용·터널·교량·고가·지하도로 등 아님) */
    ROAD_OK,
    /** 사용자가 입력한 목적지(운전자 목적지 D, 동승자 목적지 P) */
    DESTINATION,
    /** 고속도로·자동차전용·터널 등 하차 불가 구간 → 후보에서 제외 */
    NO_STOP,
    /** 별도 현장 확인을 거친 지점(현재 사용처 없음) */
    VERIFIED,
}

/** 경로 구간 코드로 하차 가능 여부 판단 */
fun roadStopSuitability(seg: RoadSeg?, policy: Policy): StopSuitability = when {
    seg == null || seg.roadType == null || seg.facilityType == null -> StopSuitability.UNVERIFIED
    seg.roadType in policy.noStopRoadTypes -> StopSuitability.NO_STOP
    seg.facilityType !in policy.stoppableFacilityTypes -> StopSuitability.NO_STOP
    else -> StopSuitability.ROAD_OK
}

/**
 * 하차점이 놓인 구간 + 앞뒤 policy.stopClearanceM 안의 구간을 함께 본다.
 * 놓인 구간은 기존 규칙 그대로, 앞뒤 구간은 고속도로·자동차전용·교량·터널 등 '단단한' 하차 불가만 본다.
 */
fun roadStopSuitabilityAround(route: CarRoute, segIndex: Int, t: Double, policy: Policy): StopSuitability {
    val own = roadStopSuitability(route.segRoad.getOrNull(segIndex), policy)
    if (own != StopSuitability.ROAD_OK) return own
    val cd = route.cumDistM
    if (cd.size != route.points.size || segIndex + 1 >= cd.size) return own
    val pos = cd[segIndex] + (cd[segIndex + 1] - cd[segIndex]) * t
    val lo = pos - policy.stopClearanceM; val hi = pos + policy.stopClearanceM
    for (i in route.segRoad.indices) {
        if (i + 1 >= cd.size) break
        if (cd[i + 1] < lo) continue
        if (cd[i] > hi) break
        val sg = route.segRoad[i]
        if (sg.roadType != null && sg.roadType in policy.noStopRoadTypes) return StopSuitability.NO_STOP
        if (sg.facilityType != null && sg.facilityType in policy.hardNoStopFacilityTypes) return StopSuitability.NO_STOP
    }
    return own
}

fun stopSuitabilityText(s: StopSuitability) = when (s) {
    StopSuitability.UNVERIFIED -> "도로 정보 없음(정차 가능 판단 불가)"
    StopSuitability.ROAD_OK -> "일반도로(터널·고속도로 등 아님, TMAP 구간 코드 기준)"
    StopSuitability.DESTINATION -> "입력한 목적지"
    StopSuitability.NO_STOP -> "하차 불가 도로(고속도로·자동차전용·터널 등)"
    StopSuitability.VERIFIED -> "현장 확인됨"
}

data class HandoffDecision(val allowed: Boolean, val reasons: List<String>)

/**
 * 차량 내비(Pleos NaviHelper)로 하차 지점을 실제 길안내 경유지로 넘겨도 되는지 판단한다.
 * 하나라도 막히면 전달하지 않는다. 화면 표시·비교는 이 판단과 무관하게 계속 가능하다.
 */
object NavHandoff {
    fun decide(r: RecommendResult?, c: CandidateResult?): HandoffDecision {
        val reasons = mutableListOf<String>()
        if (r == null || c == null) return HandoffDecision(false, listOf("선택된 하차점 없음"))
        if (r.best?.plan?.id != c.plan.id) reasons += "추천 엔진이 선택한(★) 하차점이 아님"
        if (!c.rankable) reasons += "평가 완료 후보가 아님(${c.status})"
        when (c.plan.stopSuitability) {
            StopSuitability.ROAD_OK, StopSuitability.DESTINATION, StopSuitability.VERIFIED -> {}
            StopSuitability.UNVERIFIED -> reasons += "하차 지점 도로 정보가 없어 정차 가능 여부를 판단할 수 없음"
            StopSuitability.NO_STOP -> reasons += "고속도로·자동차전용·터널 등 하차 불가 도로"
        }
        if (r.verdict != Verdict.RECOMMEND && r.verdict != Verdict.SIMILAR) reasons += "추천 판정이 아님(${r.verdict})"
        return HandoffDecision(reasons.isEmpty(), reasons)
    }
}

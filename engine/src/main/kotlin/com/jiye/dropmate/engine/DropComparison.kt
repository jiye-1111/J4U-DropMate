package com.jiye.dropmate.engine

/**
 * 여러 하차점 비교용 순위 목록. 추가 호출 없이 이미 평가한 후보만 정렬한다.
 * 순위 기준(2026-11-12 사용자 확정): ① 동승자 도착(분 변환) 빠른 순 → ② 운전자 우회(분 변환) 작은 순 → ③ 환승 적은 순
 * → ④ 함께 탄 시간이 긴 순(하차가 늦은 쪽 = 더 멀리 같이 감) → 총 보행 → 후보 ID.
 * 순위를 매길 수 없는 후보(제외·보류·실패·미조회)는 순위 없이 뒤에 두고 사유를 그대로 보존한다.
 */
data class RankedDrop(
    /** 1부터. 순위 비교 대상이 아니면 null */
    val rank: Int?,
    val candidate: CandidateResult,
    /** 1위 대비 동승자 도착 차이(초, 원본 초 단위). 순위 대상이 아니거나 1위가 없으면 null */
    val gapToBestSec: Long?,
)

object DropComparison {
    fun comparator(rounding: MinuteRounding): Comparator<CandidateResult> = compareBy(
        { TimeUtil.rankMinute(it.passengerArrivalEpochSec!!, rounding) },
        { TimeUtil.rankMinute(it.driverArrivalEpochSec!!, rounding) },
        { it.transferCount ?: Int.MAX_VALUE },
        { -(it.dropCompleteEpochSec ?: 0L) },
        { it.driverArrivalEpochSec!! },
        { it.totalWalkSec ?: Int.MAX_VALUE },
        { it.plan.id },
    )

    fun rank(candidates: List<CandidateResult>, rounding: MinuteRounding): List<RankedDrop> {
        val ranked = candidates.filter { it.rankable }.sortedWith(comparator(rounding))
        val bestArr = ranked.firstOrNull()?.passengerArrivalEpochSec
        val head = ranked.mapIndexed { i, c -> RankedDrop(i + 1, c, bestArr?.let { c.passengerArrivalEpochSec!! - it }) }
        val tail = candidates.filter { !it.rankable }.map { RankedDrop(null, it, null) }
        return head + tail
    }
}

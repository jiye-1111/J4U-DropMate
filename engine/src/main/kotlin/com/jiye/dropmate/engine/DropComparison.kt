package com.jiye.dropmate.engine

/**
 * 하차점 순위 기준(2026-11-14 사용자 확정).
 * - 최소 시간(기본, 2026-11-12 원래 규칙): 동승자 도착(분 반올림) → 운전자 도착(분, 우회 적은 순) → 환승 → 더 오래 같이 타는 순
 *   → 운전자 도착(초) → 총 보행 → 후보 ID. 판정도 원래대로(절약 180초 이상 추천, 0초 초과 비슷, 그 외 각자 가기).
 * - 오래 같이 타기(15:13 사용자 요청 "좀 더 가면 가까운 역·버스정류장이 있으면 그걸 추천, 최소환승 빼고"):
 *   최소 시간 1위 대비 동승자 도착이 [STAY_LONGER_TOLERANCE_SEC] 이내로 늦고, 운전자 추가도 같은 값 이내로 더 드는 후보만 앞에 둔다.
 *   그 안에서 같이 타는 시간(출발 → 하차) 긴 순 → 동승자 도착 빠른 순 → 운전자 추가 적은 순 → 후보 ID.
 *   허용 범위 밖 후보는 그 뒤에 최소 시간 순서로. 판정(추천/비슷/각자 가기)은 최소 시간 판정을 그대로 쓴다.
 * - 운전자 우회 한도(min(15분, 직행×30%)) 밖 후보는 두 탭 모두 제외(평가 단계).
 * 추가 호출 없이 이미 받은 결과만 정렬한다.
 */
enum class RankMode(val label: String) {
    FASTEST("최소 시간"),
    STAY_LONGER("오래 같이 타기"),
}

/** 오래 같이 타기 허용 범위(초): 최소 시간 1위 대비 동승자 도착 지연·운전자 추가 증가 각각 이 값 이내. 임시값 */
const val STAY_LONGER_TOLERANCE_SEC = 300L

data class RankedDrop(
    /** 1부터. 순위 비교 대상이 아니면 null */
    val rank: Int?,
    val candidate: CandidateResult,
    /** 1위 대비 동승자 도착 차이(초, 원본 초 단위). 순위 대상이 아니거나 1위가 없으면 null */
    val gapToBestSec: Long?,
)

/** 화면 표시용 값. driverArrival = 운전자 도착(각자 가기는 직행 도착), rideUntil = 함께 탄 마지막 시각(각자 가기는 출발 시각) */
data class RankKey(val arrival: Long, val driverArrival: Long, val transfers: Int?, val rideUntil: Long, val walk: Int?, val id: String)

object DropComparison {
    private const val BIG = Int.MAX_VALUE

    fun key(c: CandidateResult): RankKey = RankKey(
        arrival = c.passengerArrivalEpochSec!!, driverArrival = c.driverArrivalEpochSec!!, transfers = c.transferCount,
        rideUntil = c.dropCompleteEpochSec ?: 0L, walk = c.totalWalkSec, id = c.plan.id,
    )

    /** 각자 가기(동승자 혼자 대중교통)의 표시 값. 빠른 도착 여정 기준. */
    fun soloKey(solo: SoloResult, t0: Long, directDriverArrival: Long = t0): RankKey? {
        val it = solo.chosen ?: return null
        val arr = solo.arrivalEpochSec ?: it.arrivalEpochSec ?: return null
        return RankKey(arr, directDriverArrival, it.itinerary.transferCount, t0, it.itinerary.totalWalkTimeSec, "~SOLO")
    }

    /** 최소 시간(원래 규칙) 비교. */
    fun comparator(rounding: MinuteRounding): Comparator<CandidateResult> = compareBy(
        { TimeUtil.rankMinute(it.passengerArrivalEpochSec!!, rounding) },
        { TimeUtil.rankMinute(it.driverArrivalEpochSec!!, rounding) },
        { it.transferCount ?: BIG },
        { -(it.dropCompleteEpochSec ?: 0L) },
        { it.driverArrivalEpochSec!! },
        { it.totalWalkSec ?: BIG },
        { it.plan.id },
    )

    /** 모드별 순서(순위 대상만). */
    fun ordered(candidates: List<CandidateResult>, rounding: MinuteRounding, mode: RankMode): List<CandidateResult> {
        val fast = candidates.filter { it.rankable }.sortedWith(comparator(rounding))
        if (mode == RankMode.FASTEST || fast.isEmpty()) return fast
        val f = fast.first()
        val fArr = f.passengerArrivalEpochSec!!; val fAdd = (f.driverAddSec ?: 0).toLong()
        fun ok(c: CandidateResult) = c.passengerArrivalEpochSec!! - fArr <= STAY_LONGER_TOLERANCE_SEC &&
            (c.driverAddSec?.toLong() ?: Long.MAX_VALUE) - fAdd <= STAY_LONGER_TOLERANCE_SEC
        val (inside, outside) = fast.partition { ok(it) }
        val stay = inside.sortedWith(compareBy<CandidateResult>(
            { -(it.dropCompleteEpochSec ?: 0L) }, { it.passengerArrivalEpochSec!! }, { it.driverAddSec ?: BIG }, { it.plan.id }))
        return stay + outside
    }

    fun rank(candidates: List<CandidateResult>, rounding: MinuteRounding, mode: RankMode = RankMode.FASTEST): List<RankedDrop> {
        val ranked = ordered(candidates, rounding, mode)
        val bestArr = ranked.firstOrNull()?.passengerArrivalEpochSec
        val head = ranked.mapIndexed { i, c -> RankedDrop(i + 1, c, bestArr?.let { c.passengerArrivalEpochSec!! - it }) }
        val tail = candidates.filter { !it.rankable }.map { RankedDrop(null, it, null) }
        return head + tail
    }

    data class Judgement(val best: CandidateResult?, val verdict: Verdict, val reason: String, val savingSec: Long?)

    /**
     * 1위와 판정. 판정(추천/비슷/각자 가기)은 두 탭 모두 최소 시간 원래 규칙:
     * 최소 시간 1위의 절약(각자 가기 도착 − 1위 도착)이 180초 이상 RECOMMEND, 0초 초과 SIMILAR, 그 외 NO_GAIN.
     * savingSec 은 화면에 보이는 1위(모드별) 기준 절약.
     */
    fun judge(results: List<CandidateResult>, solo: SoloResult?, rounding: MinuteRounding, mode: RankMode): Judgement {
        val order = ordered(results, rounding, mode)
        val best = order.firstOrNull()
        if (best == null) {
            val anyIncomplete = results.any { it.status in setOf(CandStatus.FAILED, CandStatus.NOT_QUERIED_BUDGET, CandStatus.NOT_QUERIED_HALTED, CandStatus.ACCESS_WALK_UNKNOWN, CandStatus.WALK_DIRECT_UNRATED, CandStatus.NO_ROUTE) }
            return if (anyIncomplete || results.isEmpty()) Judgement(null, Verdict.INCOMPLETE, "INCOMPLETE: 평가 완료 후보 없음(실패·미조회·미확정 포함)", null)
            else Judgement(null, Verdict.NO_CANDIDATE, if (results.all { it.status == CandStatus.EXCLUDED_DETOUR }) "NO_CANDIDATE.DETOUR_LIMIT" else "NO_CANDIDATE.WALK_LIMIT", null)
        }
        val soloArr = solo?.arrivalEpochSec ?: return Judgement(best, Verdict.INCOMPLETE,
            if (solo?.status == CandStatus.NO_ROUTE) "INCOMPLETE.SOLO_NO_ROUTE: 단독 대중교통 경로 없음 — 동승 경로만 표시, 절약시간 미계산"
            else "INCOMPLETE.SOLO_MISSING: 단독 경로를 확인하지 못함(이동 불가로 단정하지 않음)", null)
        val fastSaving = soloArr - ordered(results, rounding, RankMode.FASTEST).first().passengerArrivalEpochSec!!
        val verdict = when { fastSaving >= 180 -> Verdict.RECOMMEND; fastSaving > 0 -> Verdict.SIMILAR; else -> Verdict.NO_GAIN }
        val saving = soloArr - best.passengerArrivalEpochSec!!
        return Judgement(best, verdict, "단독 대비 ${fastSaving}초 (원본 초 단위 비교)" + if (mode != RankMode.FASTEST) " · ${mode.label} 1위 단독 대비 ${saving}초" else "", saving)
    }
}

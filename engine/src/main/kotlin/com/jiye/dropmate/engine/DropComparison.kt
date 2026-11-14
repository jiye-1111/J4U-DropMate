package com.jiye.dropmate.engine

/**
 * 하차점 순위 기준(2026-11-14 사용자 확정: "편하게 타고 싶어, 운전자 손해 봐도").
 * - 운전자 우회 시간은 한도(min(15분, 직행×30%)) 안이면 순위·동률 처리에 쓰지 않는다.
 * - 최소 시간: 동승자 도착(분 반올림) 빠른 순 → 환승 적은 순 → 더 오래 같이 타는 순(하차가 늦은 쪽)
 * - 최소 환승: 환승 적은 순 → 동승자 도착(분 반올림) 빠른 순 → 더 오래 같이 타는 순
 * - 그다음 총 보행 → 후보 ID(결과를 항상 같게 하기 위한 마지막 기준).
 * - '각자 가기'(동승자 혼자 대중교통)도 같은 기준으로 함께 비교한다(같이 타는 시간 0).
 * - 최소 환승 모드에서는 각 하차점·각자 가기 모두 그 안에서 환승이 가장 적은 여정으로 비교한다.
 * 추가 호출 없이 이미 받은 결과만 정렬한다.
 */
enum class RankMode(val label: String) {
    FASTEST("최소 시간"),
    FEWEST_TRANSFERS("최소 환승"),
}

data class RankedDrop(
    /** 1부터. 순위 비교 대상이 아니면 null */
    val rank: Int?,
    val candidate: CandidateResult,
    /** 1위 대비 동승자 도착 차이(초, 원본 초 단위). 순위 대상이 아니거나 1위가 없으면 null */
    val gapToBestSec: Long?,
)

/** 순위 비교에 쓰는 값. rideUntil = 함께 탄 마지막 시각(각자 가기는 출발 시각) */
data class RankKey(val arrival: Long, val transfers: Int?, val rideUntil: Long, val walk: Int?, val id: String)

object DropComparison {
    private const val BIG = Int.MAX_VALUE

    /** 후보의 모드별 비교 값. 최소 환승이면 그 하차점의 최소 환승 여정(없으면 빠른 도착 여정)을 쓴다. */
    fun key(c: CandidateResult, mode: RankMode): RankKey {
        val it = if (mode == RankMode.FEWEST_TRANSFERS) RouteOptions.pick(c.routeOptions, RouteOptionKind.FEWEST_TRANSFERS)?.itinerary else null
        return RankKey(
            arrival = it?.arrivalEpochSec ?: c.passengerArrivalEpochSec!!,
            transfers = if (it != null) it.itinerary.transferCount else c.transferCount,
            rideUntil = c.dropCompleteEpochSec ?: 0L,
            walk = if (it != null) it.itinerary.totalWalkTimeSec else c.totalWalkSec,
            id = c.plan.id,
        )
    }

    /** 각자 가기(동승자 혼자 대중교통)의 모드별 비교 값. 같이 타는 시간은 0(출발 시각). */
    fun soloKey(solo: SoloResult, t0: Long, mode: RankMode): RankKey? {
        val opts = RouteOptions.select(solo.itineraries)
        val kind = if (mode == RankMode.FEWEST_TRANSFERS) RouteOptionKind.FEWEST_TRANSFERS else RouteOptionKind.FASTEST
        val it = RouteOptions.pick(opts, kind)?.itinerary ?: solo.chosen ?: return null
        val arr = it.arrivalEpochSec ?: solo.arrivalEpochSec ?: return null
        return RankKey(arr, it.itinerary.transferCount, t0, it.itinerary.totalWalkTimeSec, "~SOLO")
    }

    fun keyComparator(rounding: MinuteRounding, mode: RankMode): Comparator<RankKey> {
        val arr = compareBy<RankKey> { TimeUtil.rankMinute(it.arrival, rounding) }
        val tr = compareBy<RankKey> { it.transfers ?: BIG }
        val head = if (mode == RankMode.FASTEST) arr.then(tr) else tr.then(arr)
        return head.thenBy { -it.rideUntil }.thenBy { it.walk ?: BIG }.thenBy { it.id }
    }

    fun comparator(rounding: MinuteRounding, mode: RankMode = RankMode.FASTEST): Comparator<CandidateResult> {
        val k = keyComparator(rounding, mode)
        return Comparator { a, b -> k.compare(key(a, mode), key(b, mode)) }
    }

    fun rank(candidates: List<CandidateResult>, rounding: MinuteRounding, mode: RankMode = RankMode.FASTEST): List<RankedDrop> {
        val ranked = candidates.filter { it.rankable }.sortedWith(comparator(rounding, mode))
        val bestArr = ranked.firstOrNull()?.let { key(it, mode).arrival }
        val head = ranked.mapIndexed { i, c -> RankedDrop(i + 1, c, bestArr?.let { key(c, mode).arrival - it }) }
        val tail = candidates.filter { !it.rankable }.map { RankedDrop(null, it, null) }
        return head + tail
    }

    data class Judgement(val best: CandidateResult?, val verdict: Verdict, val reason: String, val savingSec: Long?)

    /**
     * 순위 1위와 판정. 각자 가기가 같은 기준에서 1위보다 앞서면 NO_GAIN.
     * 함께 가는 쪽이 앞설 때: 최소 시간은 절약 180초 이상이면 RECOMMEND, 아니면 SIMILAR.
     * 최소 환승은 환승이 더 적거나 절약 180초 이상이면 RECOMMEND, 아니면 SIMILAR.
     * savingSec = 각자 가기 도착 − 1위 도착(모드별 여정 기준, 원본 초).
     */
    fun judge(results: List<CandidateResult>, solo: SoloResult?, t0: Long, rounding: MinuteRounding, mode: RankMode): Judgement {
        val best = results.filter { it.rankable }.minWithOrNull(comparator(rounding, mode))
        if (best == null) {
            val anyIncomplete = results.any { it.status in setOf(CandStatus.FAILED, CandStatus.NOT_QUERIED_BUDGET, CandStatus.NOT_QUERIED_HALTED, CandStatus.ACCESS_WALK_UNKNOWN, CandStatus.WALK_DIRECT_UNRATED, CandStatus.NO_ROUTE) }
            return if (anyIncomplete || results.isEmpty()) Judgement(null, Verdict.INCOMPLETE, "INCOMPLETE: 평가 완료 후보 없음(실패·미조회·미확정 포함)", null)
            else Judgement(null, Verdict.NO_CANDIDATE, if (results.all { it.status == CandStatus.EXCLUDED_DETOUR }) "NO_CANDIDATE.DETOUR_LIMIT" else "NO_CANDIDATE.WALK_LIMIT", null)
        }
        val sk = solo?.takeIf { it.arrivalEpochSec != null }?.let { soloKey(it, t0, mode) }
        if (sk == null) return Judgement(best, Verdict.INCOMPLETE,
            if (solo?.status == CandStatus.NO_ROUTE) "INCOMPLETE.SOLO_NO_ROUTE: 단독 대중교통 경로 없음 — 동승 경로만 표시, 절약시간 미계산"
            else "INCOMPLETE.SOLO_MISSING: 단독 경로를 확인하지 못함(이동 불가로 단정하지 않음)", null)
        val bk = key(best, mode)
        val saving = sk.arrival - bk.arrival
        val soloFirst = keyComparator(rounding, mode).compare(sk, bk) < 0
        val verdict = when {
            soloFirst -> Verdict.NO_GAIN
            saving >= 180 -> Verdict.RECOMMEND
            mode == RankMode.FEWEST_TRANSFERS && (bk.transfers ?: BIG) < (sk.transfers ?: BIG) -> Verdict.RECOMMEND
            else -> Verdict.SIMILAR
        }
        val why = "${mode.label} 기준 · 단독 대비 ${saving}초, 환승 ${bk.transfers ?: "?"}회(단독 ${sk.transfers ?: "?"}회)" +
            if (soloFirst) " → 각자 가기가 앞섬" else ""
        return Judgement(best, verdict, why, saving)
    }
}

package com.jiye.dropmate.engine

import kotlin.math.max

data class RecommendRequest(
    val requestId: String,
    val origin: LatLng,
    val driverDest: LatLng,
    val passengerDest: LatLng,
    val t0EpochSec: Long,
    /** 실시간 검증(true)과 과거 재현(false)을 구분. 실시간에서 과거 t0 금지 */
    val live: Boolean = true,
    /** 운전자 목적지의 차량 입구 후보(공공·공개 자료). 있으면 입구마다 실제 자동차 경로를 비교해 가장 빠른 입구를 목적지로 쓴다 */
    val driverEntrances: List<Entrance> = emptyList(),
    /** 동승자 목적지의 입구 후보(차량·보행). 문 앞 경유(P_VIA)에서 내려 줄 지점 후보로 쓴다 */
    val passengerEntrances: List<Entrance> = emptyList(),
    /** 동승자 목적지 이름('아파트' 접미사 뺀 것). 문 앞 경유 하차점 이름을 '<이름> 앞'으로 짧게 쓰는 데만 쓴다 */
    val passengerName: String? = null,
)

enum class EntranceKind { CAR, WALK }

/** 단지·건물 입구. kind=CAR 은 차량 진입용, WALK 는 사람 출입용(차량 진입 여부 미확인 포함). */
data class Entrance(val pos: LatLng, val label: String, val kind: EntranceKind, val source: String)

/** 사전 계획 대상 후보 (호출 전) */
data class PlannedCandidate(
    val id: String,
    val type: CandidateType,
    val dropPoint: LatLng,
    val label: String,
    /** 직행 경로상 하차점까지 누적 차량시간(직행 응답 기반). P_VIA 는 null */
    val routeOffsetSec: Double?,
    val accessStop: GtfsStop?,
    val accessStraightM: Double?,
    val gtfsLink: GtfsLink,
    val gtfsTransfers: Int?,
    val prefilter: PrefilterState,
    val groupKey: String,
    /** 조회 우선순위 점수(초). 제외·최적성 근거가 아님 */
    val priorityScore: Double,
    val note: String = "",
    /** 정차 가능 상태. 도로 정보가 없으면 미확인이며 길안내 전달을 막는다. */
    val stopSuitability: StopSuitability = StopSuitability.UNVERIFIED,
)

data class EvaluatedItinerary(
    val index: Int,
    val itinerary: TransitItinerary,
    val arrivalEpochSec: Long?,
    val status: CandStatus,
    val reason: String,
)

data class CandidateResult(
    val plan: PlannedCandidate,
    val status: CandStatus,
    val reason: String,
    val dropCompleteEpochSec: Long? = null,
    val driverAddSec: Int? = null,
    val driverAddBasis: String? = null,
    val driverArrivalEpochSec: Long? = null,
    val searchDttm: String? = null,
    val alignDiffSec: Int? = null,
    val passengerArrivalEpochSec: Long? = null,
    val totalWalkSec: Int? = null,
    val transferCount: Int? = null,
    val chosen: EvaluatedItinerary? = null,
    val itineraries: List<EvaluatedItinerary> = emptyList(),
    val nearLimit: Boolean = false,
    val callsUsed: Int = 0,
    /** 이미 조회한 선택 차량 경로. 시각화를 위한 추가 호출 없음. */
    val carToDrop: CarRoute? = null,
    val carAfterDrop: CarRoute? = null,
    /** 같은 하차점의 빠른 도착·최소 환승·최소 보행 여정(동일 여정은 배지 병합). 순위는 chosen(빠른 도착)만 사용 */
    val routeOptions: List<RouteOption> = emptyList(),
    /** 문 앞 경유(P_VIA)에서 기본과 다른 운전자 입구를 고른 경우 그 입구(내비 안내 목적지) */
    val driverDestPos: LatLng? = null,
    val driverDestLabel: String? = null,
) {
    val rankable: Boolean get() = status == CandStatus.EVALUATED && passengerArrivalEpochSec != null && driverArrivalEpochSec != null
}

data class SoloResult(
    val status: CandStatus,
    val reason: String,
    val arrivalEpochSec: Long? = null,
    val chosen: EvaluatedItinerary? = null,
    val itineraries: List<EvaluatedItinerary> = emptyList(),
    val searchDttm: String? = null,
)

data class Preflight(
    val localRemaining: Int?,
    val providerQuotaKnown: Boolean,
    val baselineCalls: Int,
    val perCandidateCalls: Map<CandidateType, String>,
    val revalidationReserve: Int,
    val maxCallsThisRequest: Int,
    val evaluableCandidates: Int,
    val text: String,
)

data class RecommendResult(
    val requestId: String,
    val policyVersion: String,
    val t0EpochSec: Long,
    val dataVersion: String,
    val verdict: Verdict,
    val verdictReason: String,
    val best: CandidateResult?,
    val bestScope: String,
    val provisional: Boolean,
    val savingSec: Long?,
    val solo: SoloResult?,
    val baseline: CarRoute?,
    val candidates: List<CandidateResult>,
    val prefilterCounts: Map<String, Int>,
    val calls: List<CallRecord>,
    val callsSent: Int,
    val dedupSaved: Int,
    val haltedBy: String?,
    val notes: List<String>,
    /** 엔진이 쓴 운전자 목적지(차량 입구를 골랐으면 그 입구, 아니면 입력 좌표) */
    val driverDest: LatLng? = null,
    /** 하차점 순위 기준(기본 최소 시간). withRankMode 로만 바꾼다. */
    val rankMode: RankMode = RankMode.FASTEST,
) {
    /** 순위 기준을 바꿔 1위·판정·절약시간을 다시 계산한다. 이미 받은 결과만 쓰며 TMAP 호출은 없다. */
    fun withRankMode(mode: RankMode, rounding: MinuteRounding = MinuteRounding.ROUND): RecommendResult {
        if (mode == rankMode) return this
        val j = DropComparison.judge(candidates, solo, t0EpochSec, rounding, mode)
        return copy(rankMode = mode, best = j.best, verdict = j.verdict, verdictReason = j.reason, savingSec = j.savingSec)
    }

    /** 후보별 운전자 목적지(내비 안내 목적지): 문 앞 경유에서 다시 고른 입구 → 기본 운전자 입구 → 입력 좌표 */
    fun driverDestFor(c: CandidateResult?): LatLng? = c?.driverDestPos ?: driverDest

    /** 여러 하차점 비교(순위 대상 먼저, 그 외는 사유와 함께 뒤에). 선택 기준과 같은 정렬. */
    fun dropRanking(rounding: MinuteRounding = MinuteRounding.ROUND): List<RankedDrop> = DropComparison.rank(candidates, rounding, rankMode)
}

class DropMateEngine(
    private val gtfs: GtfsIndex?,
    private val policy: Policy = Policy(),
) {
    /** 호출 전 예산 출력. 후보 계획은 직행 경로가 있어야 하므로 최대치 기준으로 계산 */
    fun preflight(ledger: CallLedger): Preflight {
        val local = ledger.localRemaining()
        val midCost = if (policy.carTimeMode == CarTimeMode.ROUTE_SEGMENT) 1 else 3
        val worstPerCand = max(midCost, 2)
        val maxCalls = minOf(policy.maxCallsPerRequest, 2 + policy.maxCandidatesPerRequest * worstPerCand)
        val evaluable = run {
            val usable = minOf(local ?: Int.MAX_VALUE, policy.maxCallsPerRequest) - 2
            if (usable <= 0) 0 else minOf(policy.maxCandidatesPerRequest, usable / 1)
        }
        val per = mapOf(
            CandidateType.MIDDLE to (if (midCost == 1) "1 (대중교통 1, 차량은 직행 구간시간 사용)" else "3 (자동차 2 + 대중교통 1)"),
            CandidateType.D_DROP to "1 (대중교통 1)",
            CandidateType.P_VIA to "1~2 (자동차 O→P, 한도 초과가 증명되면 P→D 생략)",
        )
        val text = buildString {
            appendLine("남은 로컬 호출 예산: ${local ?: "미설정(제한 없음, 요청당 상한 적용)"}")
            appendLine("제공사 사용 가능량: 미확인 (앱에서 조회 불가)")
            appendLine("기준 경로 호출: 2 (자동차 O→D 1 + 단독 대중교통 O→P 1)")
            per.forEach { (t, v) -> appendLine("후보당 호출(${t.name}): $v") }
            appendLine("별도 시간표·실시간 조회: 0 (미연결)")
            appendLine("선택 직전 재검증 예약: 0 (자동 재검증 없음)")
            appendLine("이번 요청 최대 호출: $maxCalls (상한 ${policy.maxCallsPerRequest})")
            appendLine("완결 평가 가능 후보 수: 최대 $evaluable")
        }
        return Preflight(local, false, 2, per, 0, maxCalls, evaluable, text.trim())
    }

    fun recommend(reqIn: RecommendRequest, provider: RouteProvider, ledger: CallLedger, isCancelled: () -> Boolean = { false }, nowEpochSec: Long = System.currentTimeMillis() / 1000): RecommendResult {
        val notes = mutableListOf<String>()
        // 2026-11-12 입구 보강: 운전자 목적지는 차량 입구만, 동승자 문 앞 경유는 차량·보행 입구 모두 후보. 입구가 늘어난 만큼만 요청당 상한을 늘린다.
        val dEnt = reqIn.driverEntrances.filter { it.kind == EntranceKind.CAR }.take(policy.maxEntrancesPerDest)
        val pEnt = reqIn.passengerEntrances.take(policy.maxEntrancesPerDest)
        // 문 앞 경유에서 운전자 입구 다시 고르기: 동승자 입구 상위 2곳 × 나머지 운전자 입구 최대 2곳 = 최대 4회
        val pViaAltCalls = minOf(2, maxOf(0, dEnt.size - 1)) * minOf(2, maxOf(1, pEnt.size))
        val extraCalls = maxOf(0, dEnt.size - 1) + 2 * maxOf(0, pEnt.size - 1) + pViaAltCalls
        var req = reqIn.copy(passengerEntrances = pEnt)
        val gw = CallGateway(provider, if (extraCalls > 0) policy.copy(maxCallsPerRequest = policy.maxCallsPerRequest + extraCalls) else policy, ledger, req.requestId, isCancelled)
        val dataVersion = gtfs?.sourceNote ?: "GTFS 미적재"
        fun result(verdict: Verdict, reason: String, baseline: CarRoute? = null, solo: SoloResult? = null, cands: List<CandidateResult> = emptyList(), pf: Map<String, Int> = emptyMap(), best: CandidateResult? = null, saving: Long? = null, scope: String = "", provisional: Boolean = true) =
            RecommendResult(req.requestId, policy.version, req.t0EpochSec, dataVersion, verdict, reason, best, scope, provisional, saving, solo, baseline, cands, pf, gw.log.toList(), gw.sent, gw.dedupSaved, gw.haltedBy, notes, req.driverDest)

        if (req.live && req.t0EpochSec < nowEpochSec - 120) return result(Verdict.INCOMPLETE, "실시간 검증에 과거 t0 사용 금지")

        // 1. 기준: 자동차 직행
        val base: CarRoute
        if (dEnt.isEmpty()) {
            base = when (val r = gw.car("BASE O→D", req.origin, req.driverDest, req.t0EpochSec)) {
                is ProviderResult.Ok -> r.value
                is ProviderResult.NoRoute -> return result(Verdict.INCOMPLETE, "INCOMPLETE.FETCH_FAILED: 직행 자동차 경로 없음(${r.code})")
                is ProviderResult.Fail -> return result(Verdict.INCOMPLETE, "INCOMPLETE.FETCH_FAILED: 직행 자동차 조회 실패(${r.kind})")
            }
        } else {
            // 운전자 목적지 차량 입구별 실제 자동차 경로(일방통행·진입 방향 반영)를 비교해 가장 빠른 입구를 고른다
            var bestR: CarRoute? = null; var bestE: Entrance? = null; var lastFail: String? = null
            for (e in dEnt) {
                when (val r = gw.car("BASE O→D입구 ${e.label}", req.origin, e.pos, req.t0EpochSec)) {
                    is ProviderResult.Ok -> if (bestR == null || r.value.totalTimeSec < bestR.totalTimeSec) { bestR = r.value; bestE = e }
                    is ProviderResult.NoRoute -> lastFail = "경로 없음(${r.code})"
                    is ProviderResult.Fail -> { lastFail = "조회 실패(${r.kind})"; if (gw.haltedBy != null) break }
                }
            }
            if (bestR == null || bestE == null) return result(Verdict.INCOMPLETE, "INCOMPLETE.FETCH_FAILED: 운전자 목적지 입구 자동차 경로 ${lastFail ?: "없음"}")
            base = bestR
            req = req.copy(driverDest = bestE.pos)
            notes += "운전자 목적지 차량 입구: ${bestE.label}(${bestE.source}) — 입구 ${dEnt.size}곳 실제 경로 비교, 가장 빠른 곳"
        }
        notes += "운전자 우회 한도: ${policy.allowedDetourSec(base.totalTimeSec)}초 = min(${policy.detourLimitSec}초, 직행 ${base.totalTimeSec}초 × ${(policy.detourRatio * 100).toInt()}%) · 2026-11-12 사용자 결정"
        notes += "자동차 시간: TMAP 자동차 경로 응답(요청 시점 교통). 미래 출발시각 예측 API 미사용 — 후보 간 교통 상태 동일 보장 없음"
        // 2. 기준: 단독 대중교통
        val solo = evalSolo(req, gw)

        // 3. 후보 계획 (호출 없음)
        val (planned, pfCounts) = planCandidates(req, base)
        // 4. 평가
        // 2026-11-12 정확도 개선: 중간 하차를 1개만 보던 모순(감사: 경로 앞쪽 정류장 편향)을 고쳐
        // 경로 전체를 시간 구간으로 나눠 구간마다 가장 유망한 정류장을 고르고, D 하차·P 경유와 함께 비교한다.
        val selected = selectForEvaluation(planned, base)
        val results = mutableListOf<CandidateResult>()
        for (c in selected) results += evaluate(c, req, base, gw)
        results.firstOrNull { it.plan.type == CandidateType.P_VIA && it.driverDestLabel != null }?.let { notes += "P_VIA 운전자 입구: ${it.driverDestLabel}" }
        for (c in planned.filter { p -> selected.none { it.id == p.id } }) results += CandidateResult(c, CandStatus.NOT_QUERIED_BUDGET, "우선순위 밖 보류(요청당 후보 ${policy.maxCandidatesPerRequest}개 상한, 휴리스틱) — 경로 없음·제외 아님")

        // 5. 순위 (기본: 최소 시간)
        val rankable = results.filter { it.rankable }
        val best = rankable.minWithOrNull(DropComparison.comparator(policy.rankRounding, RankMode.FASTEST))
        val notQueried = results.count { it.status == CandStatus.NOT_QUERIED_BUDGET || it.status == CandStatus.NOT_QUERIED_HALTED }
        val undetermined = results.count { it.status in setOf(CandStatus.FAILED, CandStatus.ACCESS_WALK_UNKNOWN, CandStatus.WALK_DIRECT_UNRATED) }
        val scope = "평가 완료 ${rankable.size}개 / 계획 ${planned.size}개 (미조회 $notQueried, 미확정·실패 $undetermined) 중 가장 빠름 — 직행 경로 주변 ${policy.corridorStraightLimitM}m 후보 탐색, 경로 밖 우회 하차는 미탐색"
        val provisional = best != null && best.plan.type != CandidateType.P_VIA && !policy.transitTimeBasisVerified
        if (provisional) notes += "TMAP 대중교통 totalTime 의 초기 대기·운행편 반영 의미가 공급자 문서로 확인되지 않음 → 잠정 결과"
        if (policy.alternativeEnabled.not()) notes += "DEC-03 대안 표시: 조건 미확정으로 비활성"
        notes += policy.walkDirectLimitSec?.let { if (it == WALK_DIRECT_NO_LIMIT) "도보 직행 한도 없음 — 동승자 도착 시간으로 비교, 대중교통이 너무 가까우면 TMAP 보행자 경로로 계산" else "도보 직행 한도: ${it}초 — 대중교통이 너무 가까우면 TMAP 보행자 경로로 계산" }
            ?: "도보 직행 한도 미확정: 도보만으로 가는 여정은 순위 미반영"

        // 2026-11-14: 판정·순위는 DropComparison.judge 하나로(기본 최소 시간). 화면에서 최소 환승으로 바꾸면 같은 결과로 다시 판정한다(호출 없음).
        val j = DropComparison.judge(results, solo, req.t0EpochSec, policy.rankRounding, RankMode.FASTEST)
        return result(j.verdict, j.reason, base, solo, results, pfCounts, j.best, j.savingSec, scope, provisional)
    }

    private fun evalSolo(req: RecommendRequest, gw: CallGateway): SoloResult {
        val dttm = TimeUtil.searchDttm(req.t0EpochSec)
        return when (val r = gw.transit("SOLO O→P", req.origin, req.passengerDest, dttm)) {
            is ProviderResult.Fail -> SoloResult(CandStatus.FAILED, "단독 조회 실패(${r.kind})", searchDttm = dttm)
            is ProviderResult.NoRoute -> SoloResult(CandStatus.NO_ROUTE, "단독 경로 없음(${r.code} ${r.message})", searchDttm = dttm)
            is ProviderResult.Ok -> {
                val ev = evalItineraries(r.value, req.t0EpochSec)
                val best = ev.filter { it.status == CandStatus.EVALUATED }.minByOrNull { it.arrivalEpochSec!! }
                if (best == null) SoloResult(summarize(ev), "유효 단독 여정 없음", itineraries = ev, searchDttm = dttm)
                else SoloResult(CandStatus.EVALUATED, "단독 여정 ${ev.size}개 중 최조 도착", best.arrivalEpochSec, best, ev, dttm)
            }
        }
    }

    /** 반환된 모든 여정을 비교한다. 첫 응답을 최단으로 가정하지 않는다. */
    fun evalItineraries(list: List<TransitItinerary>, dropCompleteEpochSec: Long): List<EvaluatedItinerary> {
        val aligned = TimeUtil.ceilToMinute(dropCompleteEpochSec)
        return list.mapIndexed { i, it ->
            when {
                it.totalTimeSec == null -> EvaluatedItinerary(i, it, null, CandStatus.FAILED, "totalTime 누락")
                it.isWalkOnly -> {
                    val lim = policy.walkDirectLimitSec
                    val d = it.totalWalkDistanceM ?: it.legs.sumOf { l -> l.distanceM ?: 0 }
                    val t = it.totalTimeSec
                    if (lim == null) EvaluatedItinerary(i, it, aligned + t, CandStatus.WALK_DIRECT_UNRATED, "도보 직행 ${d}m — 한도 미확정")
                    else if (t <= lim) EvaluatedItinerary(i, it, aligned + t, CandStatus.EVALUATED, "도보 직행 ${d}m·${t}초" + (if (lim == WALK_DIRECT_NO_LIMIT) "" else " (한도 ${lim}초 이내)"))
                    else EvaluatedItinerary(i, it, null, CandStatus.EXCLUDED_ACCESS_WALK, "도보 직행 ${t}초 > ${lim}초")
                }
                else -> {
                    val w = it.firstAccessWalkSec
                    when {
                        w == null -> EvaluatedItinerary(i, it, null, CandStatus.ACCESS_WALK_UNKNOWN, "첫 접근 보행시간 누락")
                        w > policy.accessWalkLimitSec -> EvaluatedItinerary(i, it, null, CandStatus.EXCLUDED_ACCESS_WALK, "첫 접근 보행 ${w}초 > ${policy.accessWalkLimitSec}초")
                        else -> EvaluatedItinerary(i, it, aligned + it.totalTimeSec, CandStatus.EVALUATED, "첫 접근 보행 ${w}초 (10분 이내)")
                    }
                }
            }
        }
    }

    private fun summarize(ev: List<EvaluatedItinerary>): CandStatus = when {
        ev.isEmpty() -> CandStatus.NO_ROUTE
        ev.any { it.status == CandStatus.ACCESS_WALK_UNKNOWN } -> CandStatus.ACCESS_WALK_UNKNOWN
        ev.any { it.status == CandStatus.WALK_DIRECT_UNRATED } -> CandStatus.WALK_DIRECT_UNRATED
        ev.any { it.status == CandStatus.FAILED } -> CandStatus.FAILED
        else -> CandStatus.EXCLUDED_ACCESS_WALK
    }

    private fun interpTime(route: CarRoute, seg: Int, t: Double): Double =
        route.cumTimeSec[seg] + (route.cumTimeSec[seg + 1] - route.cumTimeSec[seg]) * t

    /** 후보 계획: GTFS 사전 필터 + 조회 우선순위. 호출하지 않는다. */
    fun planCandidates(req: RecommendRequest, base: CarRoute): Pair<List<PlannedCandidate>, Map<String, Int>> {
        val counts = linkedMapOf("stops_scanned" to 0, "outside_search_corridor" to 0, "query_target" to 0, "undetermined" to 0, "groups" to 0)
        val drop = policy.curbDropoffSec
        val avgCarMps = if (base.totalTimeSec > 0) base.totalDistanceM.toDouble() / base.totalTimeSec else 8.0
        val reach = gtfs?.reachabilityTo(req.passengerDest, policy.gtfsTargetProbeM, policy.gtfsTransferProbeM)
        val pool = mutableListOf<PlannedCandidate>()

        if (gtfs != null && reach != null && base.points.size >= 2) {
            // 경로 주변 정류장 수집: 경로 정점마다 반경 탐색(중복 제거)
            val seen = HashSet<Int>()
            val near = ArrayList<GtfsStop>()
            val step = max(1, base.points.size / 400)
            for (i in base.points.indices step step) for (s in gtfs.within(base.points[i], policy.corridorStraightLimitM + 300.0)) if (seen.add(s.idx)) near.add(s)
            counts["stops_scanned"] = near.size
            val byGroup = LinkedHashMap<String, MutableList<PlannedCandidate>>()
            for (s in near) {
                val pr = Geo.projectOnPolyline(s.pos, base.points)
                if (pr.distToLineM > policy.corridorStraightLimitM) {
                    // 탐색 반경 밖이므로 이번 후보 탐색에서 생략. 10분 도보 불가로 판정하지 않는다.
                    counts["outside_search_corridor"] = counts["outside_search_corridor"]!! + 1; continue
                }
                val (link, tr) = reach.link(s.idx)
                val state = if (link == GtfsLink.NOT_FOUND) PrefilterState.UNDETERMINED else PrefilterState.QUERY_TARGET
                if (state == PrefilterState.UNDETERMINED) counts["undetermined"] = counts["undetermined"]!! + 1 else counts["query_target"] = counts["query_target"]!! + 1
                val suit = roadStopSuitabilityAround(base, pr.segIndex, pr.t, policy)
                // 2026-11-12 감사 지적: TMAP 코드가 일반도로여도 정류장 이름이 지하차도·터널·고가면 하차 불가로 본다(예: 낙생지하차도)
                if (policy.noStopNameWords.any { s.name.contains(it) }) { counts["no_stop_name"] = (counts["no_stop_name"] ?: 0) + 1; continue }
                if (suit == StopSuitability.NO_STOP) {
                    // 사용자 결정 2026-11-12: 고속도로·자동차전용·터널 등 하차 불가 구간 위 투영점은 후보에서 제외(호출 0)
                    counts["no_stop_road"] = (counts["no_stop_road"] ?: 0) + 1; continue
                }
                val dropPt = pr.point
                val off = interpTime(base, pr.segIndex, pr.t)
                val ts = req.t0EpochSec + off + drop
                val score = ts + Geo.haversineM(dropPt, req.passengerDest) / 8.33
                val g = groupKey(s)
                byGroup.getOrPut(g) { mutableListOf() } += PlannedCandidate(
                    id = "M-${s.id}", type = CandidateType.MIDDLE, dropPoint = dropPt,
                    label = "${s.name} 인근 경로상 하차", routeOffsetSec = off, accessStop = s, accessStraightM = pr.distToLineM,
                    gtfsLink = link, gtfsTransfers = tr, prefilter = state, groupKey = g, priorityScore = score,
                    note = "하차 지점 도로: ${stopSuitabilityText(suit)}",
                    stopSuitability = suit,
                )
            }
            counts["groups"] = byGroup.size
            // 그룹 대표: 연결 확인 > 보류, 그다음 직선 접근거리 짧은 순
            var extra = 0
            for ((_, list) in byGroup) {
                list.sortWith(compareBy({ linkRank(it.gtfsLink) }, { it.accessStraightM }))
                pool += list.first(); extra += list.size - 1
            }
            counts["group_members_not_planned"] = extra
        } else counts["gtfs_missing"] = 1

        // D 하차
        val dLink = reach?.let { r -> gtfs!!.within(req.driverDest, policy.corridorStraightLimitM.toDouble()).map { r.link(it.idx) }.minByOrNull { linkRank(it.first) * 100 + (it.second ?: 0) } } ?: (GtfsLink.NOT_FOUND to null)
        val tD = base.totalTimeSec.toDouble()
        pool += PlannedCandidate("D-DROP", CandidateType.D_DROP, req.driverDest, "운전자 목적지까지 동승 후 이동", tD, null, null,
            dLink.first, dLink.second, if (dLink.first == GtfsLink.NOT_FOUND) PrefilterState.UNDETERMINED else PrefilterState.QUERY_TARGET, "D",
            req.t0EpochSec + tD + drop + Geo.haversineM(req.driverDest, req.passengerDest) / 8.33,
            stopSuitability = StopSuitability.DESTINATION)
        // P 경유
        val estOP = Geo.haversineM(req.origin, req.passengerDest) / avgCarMps
        val estPD = Geo.haversineM(req.passengerDest, req.driverDest) / avgCarMps
        val estAdd = estOP + drop + estPD - base.totalTimeSec
        val lim0 = policy.allowedDetourSec(base.totalTimeSec)
        val pScore = req.t0EpochSec + estOP + drop + (if (estAdd > lim0) 1e7 else 0.0)
        pool += PlannedCandidate("P-VIA", CandidateType.P_VIA, req.passengerDest, req.passengerName?.let { "$it 앞" } ?: "동승자 목적지 문 앞 경유", null, null, null,
            GtfsLink.DIRECT, null, PrefilterState.QUERY_TARGET, "P", pScore,
            note = if (estAdd > lim0) "직선거리 기반 우회 추정 ${estAdd.toInt()}초 — 우선순위만 낮춤(제외 아님)" else "",
            stopSuitability = StopSuitability.DESTINATION)

        // 조회 순서: 그룹별 1개씩, 연결 확인 우선, 다음 우선순위 점수
        val ordered = pool.sortedWith(compareBy({ if (it.type == CandidateType.MIDDLE) linkRank(it.gtfsLink) else 0 }, { it.priorityScore }))
        return ordered to counts
    }

    private fun linkRank(l: GtfsLink) = when (l) { GtfsLink.DIRECT -> 0; GtfsLink.TRANSFER -> 1; GtfsLink.NOT_FOUND -> 2 }

    // 역명/정류장명이 같아도 방향·출입구가 다르면 다른 접근점이다.
    // 방향을 증명할 자료가 없는 상태에서 이름만으로 합치지 않는다.
    private fun groupKey(s: GtfsStop): String = (if (s.isRail) "R:" else "B:") + s.id

    private fun evaluate(c: PlannedCandidate, req: RecommendRequest, base: CarRoute, gw: CallGateway): CandidateResult {
        val drop = policy.curbDropoffSec
        val before = gw.sent
        var carToDrop: CarRoute? = null
        var carAfterDrop: CarRoute? = null
        fun done(r: CandidateResult) = r.copy(callsUsed = gw.sent - before, carToDrop = carToDrop, carAfterDrop = carAfterDrop)
        val directArrive = req.t0EpochSec + base.totalTimeSec
        // 2026-11-12 사용자 결정: 요청별 우회 한도 = min(900초, 직행 × 30%)
        val lim = policy.allowedDetourSec(base.totalTimeSec)

        val dropComplete: Long; val add: Int; val basis: String
        when (c.type) {
            CandidateType.MIDDLE -> {
                if (policy.carTimeMode == CarTimeMode.ROUTE_SEGMENT) {
                    dropComplete = req.t0EpochSec + c.routeOffsetSec!!.toLong() + drop
                    add = drop
                    basis = "직행 경로 구간시간 기반 추정(O→s=${c.routeOffsetSec.toInt()}초, s→D=직행 잔여). 재출발·차로 변경 지연 미반영 + 하차 ${drop}초(개발 가정)"
                } else {
                    val os = when (val r = gw.car("MID O→s ${c.id}", req.origin, c.dropPoint, req.t0EpochSec)) {
                        is ProviderResult.Ok -> r.value
                        is ProviderResult.NoRoute -> return done(CandidateResult(c, CandStatus.NO_ROUTE, "O→s 경로 없음"))
                        is ProviderResult.Fail -> return done(failOrHalt(c, r))
                    }
                    if (os.totalTimeSec + drop - base.totalTimeSec > lim)
                        return done(CandidateResult(c, CandStatus.EXCLUDED_DETOUR, "O→s+하차만으로 한도 초과(하한) — s→D 생략"))
                    val tsC = req.t0EpochSec + os.totalTimeSec + drop
                    val sd = when (val r = gw.car("MID s→D ${c.id}", c.dropPoint, req.driverDest, tsC)) {
                        is ProviderResult.Ok -> r.value
                        is ProviderResult.NoRoute -> return done(CandidateResult(c, CandStatus.NO_ROUTE, "s→D 경로 없음"))
                        is ProviderResult.Fail -> return done(failOrHalt(c, r))
                    }
                    carToDrop = os; carAfterDrop = sd
                    dropComplete = tsC; add = os.totalTimeSec + drop + sd.totalTimeSec - base.totalTimeSec
                    basis = "TMAP O→s·s→D 조회 + 하차 ${drop}초"
                }
            }
            CandidateType.D_DROP -> {
                dropComplete = req.t0EpochSec + base.totalTimeSec + drop; add = drop
                basis = "직행 그대로 + D 하차 ${drop}초(개발 가정)"
            }
            CandidateType.P_VIA -> {
                // 2026-11-12 입구 보강: 동승자 목적지 입구가 있으면 입구마다 O→입구·입구→D 를 실제로 조회해 운전자 추가가 가장 적은 곳을 고른다
                val spots = if (req.passengerEntrances.isEmpty()) listOf(c) else req.passengerEntrances.map { e ->
                    // 2026-11-13 사용자: 하차점 이름을 짧게('아크로리버파크 게이트1 앞'). 입구 라벨에는 단지명이 들어 있다(AppViewModel)
                    c.copy(dropPoint = e.pos, label = "${e.label} 앞", note = "입구 출처: ${e.source}")
                }
                val outs = spots.map { sp -> pVia(sp, req, base, gw, drop, lim) }.toMutableList()
                // 2026-11-13 사용자 결정 "문 앞 경유만 같이 고르기": 운전자 입구는 출발지에서 가장 빠른 곳이 기본이지만,
                // 문 앞 경유에서는 O→동승자 입구가 짧은 상위 2곳에 대해 나머지 운전자 입구(최대 2곳)로도 입구→운전자 입구를 조회해 운전자 추가가 가장 적은 조합을 쓴다.
                val alts = req.driverEntrances.filter { it.kind == EntranceKind.CAR }.take(policy.maxEntrancesPerDest)
                    .filter { Geo.haversineM(it.pos, req.driverDest) > 1.0 }.take(2)
                if (alts.isNotEmpty()) {
                    val tops = outs.filter { o -> o.carToDrop != null && o.carToDrop.totalTimeSec + drop - base.totalTimeSec <= lim }
                        .sortedBy { it.carToDrop!!.totalTimeSec }.take(2)
                    for (o in tops) for (alt in alts) {
                        if (!gw.canCall()) break
                        outs += pViaAfterDrop(o.plan, o.carToDrop!!, req, base, gw, drop, lim, alt.pos, alt.label)
                    }
                }
                val ok = outs.filter { it.status == CandStatus.EVALUATED }
                val byAdd = compareBy<CandidateResult>({ it.driverAddSec ?: Int.MAX_VALUE }, { it.passengerArrivalEpochSec ?: Long.MAX_VALUE })
                val chosen = ok.minWithOrNull(byAdd)
                    ?: outs.filter { it.status == CandStatus.EXCLUDED_DETOUR && it.driverAddSec != null }.minWithOrNull(byAdd)
                    ?: outs.firstOrNull { it.status == CandStatus.EXCLUDED_DETOUR } ?: outs.first()
                val final = if (chosen.driverDestLabel != null) {
                    val defBest = outs.filter { it.driverDestLabel == null && it.driverAddSec != null }.minOfOrNull { it.driverAddSec!! }
                    val defLabel = req.driverEntrances.firstOrNull { Geo.haversineM(it.pos, req.driverDest) <= 1.0 }?.label ?: "기본 운전자 목적지"
                    chosen.copy(driverDestLabel = "${chosen.driverDestLabel}(기본 $defLabel" + (if (defBest != null) " 대비 ${defBest - chosen.driverAddSec!!}초 단축)" else ", 기본은 한도 초과 또는 미조회)"))
                } else chosen
                return final.copy(callsUsed = gw.sent - before)
            }
        }
        val driverArr = directArrive + add.coerceAtLeast(0)
        if (add < 0) return done(evaluateTransit(c, req, gw, dropComplete, 0, basis + " · 계산상 ${-add}초 빠름 → 0초로 봄", driverArr, lim))
        if (add > lim) return done(CandidateResult(c, CandStatus.EXCLUDED_DETOUR, "운전자 추가 ${add}초 > ${lim}초 → 대중교통 조회 생략", dropComplete, add, basis, driverArr))
        return done(evaluateTransit(c, req, gw, dropComplete, add, basis, driverArr, lim))
    }

    /** 문 앞 경유 1곳 평가: O→c.dropPoint, c.dropPoint→D (자동차 2회, 한도 초과가 증명되면 1회) */
    private fun pVia(c: PlannedCandidate, req: RecommendRequest, base: CarRoute, gw: CallGateway, drop: Int, lim: Int): CandidateResult {
        var carToDrop: CarRoute? = null
        var carAfterDrop: CarRoute? = null
        fun done(r: CandidateResult) = r.copy(carToDrop = carToDrop, carAfterDrop = carAfterDrop)
        val op = when (val r = gw.car("PVIA O→P ${c.label}", req.origin, c.dropPoint, req.t0EpochSec)) {
            is ProviderResult.Ok -> r.value
            is ProviderResult.NoRoute -> return done(CandidateResult(c, CandStatus.NO_ROUTE, "O→P 자동차 경로 없음"))
            is ProviderResult.Fail -> return done(failOrHalt(c, r))
        }
        carToDrop = op
        // 2026-11-13 사용자: 입구까지 안내하고 끝(당겨 내리기·도보 시간 추가 없음).
        val lower = op.totalTimeSec + drop - base.totalTimeSec
        if (lower > lim)
            return done(CandidateResult(c, CandStatus.EXCLUDED_DETOUR, "O→P+하차−직행 = ${lower}초 > ${lim}초 (P→D ≥ 0 이므로 확정). P→D 호출 생략", driverAddSec = null))
        return pViaAfterDrop(c, op, req, base, gw, drop, lim, req.driverDest, null)
    }

    /** 문 앞 경유의 하차 뒤 구간: c.dropPoint→운전자 목적지(destPos). destLabel 이 있으면 기본과 다른 운전자 입구 */
    private fun pViaAfterDrop(c: PlannedCandidate, op: CarRoute, req: RecommendRequest, base: CarRoute, gw: CallGateway, drop: Int, lim: Int,
                              destPos: LatLng, destLabel: String?): CandidateResult {
        val carToDrop = op
        var carAfterDrop: CarRoute? = null
        fun done(r: CandidateResult) = r.copy(carToDrop = carToDrop, carAfterDrop = carAfterDrop,
            driverDestPos = if (destLabel != null) destPos else null, driverDestLabel = destLabel)
        val toDropSec = op.totalTimeSec
        val tsP = req.t0EpochSec + toDropSec + drop
        val pd = when (val r = gw.car("PVIA P→D ${c.label}" + (destLabel?.let { " → $it" } ?: ""), c.dropPoint, destPos, tsP)) {
            is ProviderResult.Ok -> r.value
            is ProviderResult.NoRoute -> return done(CandidateResult(c, CandStatus.NO_ROUTE, "P→D 자동차 경로 없음"))
            is ProviderResult.Fail -> return done(failOrHalt(c, r))
        }
        carAfterDrop = pd
        val raw = toDropSec + drop + pd.totalTimeSec - base.totalTimeSec
        // 경유가 직행보다 빠르게 나오는 것은 조회 시점 교통 차이라 '추가 0초'로 본다.
        val a = raw.coerceAtLeast(0)
        val arr = req.t0EpochSec + toDropSec + drop + pd.totalTimeSec
        if (a > lim) return done(CandidateResult(c, CandStatus.EXCLUDED_DETOUR, "운전자 추가 ${a}초 > ${lim}초", tsP, a, "TMAP O→P·P→D", arr))
        return done(CandidateResult(c, CandStatus.EVALUATED, "동승자 목적지(입구) 앞 하차 — 입구까지 안내", tsP, a,
            "TMAP O→P + 하차 ${drop}초 + TMAP P→D(현재 교통 기준, 출발시각 미반영)" + (if (raw < 0) " · 계산상 ${-raw}초 빠름 → 0초로 봄" else ""), arr,
            passengerArrivalEpochSec = tsP, totalWalkSec = 0, transferCount = 0, nearLimit = a >= 0.8 * lim))
    }

    /** 하차 완료 시각부터 동승자 대중교통(가까우면 보행자 경로) 평가 */
    private fun evaluateTransit(c: PlannedCandidate, req: RecommendRequest, gw: CallGateway, dropComplete: Long, add: Int, basis: String, driverArr: Long, lim: Int): CandidateResult {
        if (!gw.canCall()) return CandidateResult(c, if (gw.haltedBy != null) CandStatus.NOT_QUERIED_HALTED else CandStatus.NOT_QUERIED_BUDGET,
            "대중교통 미조회(${gw.haltedBy ?: "예산"})", dropComplete, add, basis, driverArr)
        val dttm = TimeUtil.searchDttm(dropComplete)
        val align = TimeUtil.alignDiffSec(dropComplete)
        fun fromItins(list: List<TransitItinerary>, how: String): CandidateResult {
            val ev = evalItineraries(list, dropComplete)
            val best = ev.filter { it.status == CandStatus.EVALUATED }.minWithOrNull(compareBy({ it.arrivalEpochSec!! }, { it.itinerary.totalWalkTimeSec ?: Int.MAX_VALUE }, { it.itinerary.transferCount ?: Int.MAX_VALUE }))
            return if (best == null) CandidateResult(c, summarize(ev), "유효 여정 없음(${ev.size}개 검사)$how", dropComplete, add, basis, driverArr, dttm, align, itineraries = ev, nearLimit = add >= 0.8 * lim)
            else CandidateResult(c, CandStatus.EVALUATED, "여정 ${ev.size}개 중 최조 도착(정렬 ${align}초 1회 반영)$how", dropComplete, add, basis, driverArr, dttm, align,
                best.arrivalEpochSec, best.itinerary.totalWalkTimeSec, best.itinerary.transferCount, best, ev, add >= 0.8 * lim,
                routeOptions = RouteOptions.select(ev))
        }
        return when (val r = gw.transit("TRANSIT s→P ${c.id}", c.dropPoint, req.passengerDest, dttm)) {
            is ProviderResult.Fail -> failOrHalt(c, r).copy(dropCompleteEpochSec = dropComplete, driverAddSec = add, driverAddBasis = basis, driverArrivalEpochSec = driverArr, searchDttm = dttm)
            is ProviderResult.NoRoute -> {
                if (r.code != "11") return CandidateResult(c, CandStatus.NO_ROUTE, "대중교통 경로 없음(${r.code})", dropComplete, add, basis, driverArr, dttm, align)
                // 2026-11-12 정확도 개선: 너무 가까워 대중교통이 없으면(code 11) 걸어가는 시간을 TMAP 보행자 경로로 구해 비교한다.
                if (policy.walkDirectLimitSec == null || !gw.canCall())
                    return CandidateResult(c, CandStatus.WALK_DIRECT_UNRATED, "출발·도착이 가까워 대중교통 경로 없음(code 11) — 도보 시간 미조회", dropComplete, add, basis, driverArr, dttm, align)
                when (val w = gw.walk("WALK s→P ${c.id}", c.dropPoint, req.passengerDest)) {
                    is ProviderResult.Ok -> fromItins(listOf(w.value), " · 가까워서 도보(TMAP 보행자 경로)")
                    is ProviderResult.NoRoute -> CandidateResult(c, CandStatus.WALK_DIRECT_UNRATED, "가까워 대중교통 없음(code 11), 보행자 경로 없음(${w.code})", dropComplete, add, basis, driverArr, dttm, align)
                    is ProviderResult.Fail -> failOrHalt(c, w).copy(dropCompleteEpochSec = dropComplete, driverAddSec = add, driverAddBasis = basis, driverArrivalEpochSec = driverArr, searchDttm = dttm)
                }
            }
            is ProviderResult.Ok -> fromItins(r.value, "")
        }
    }

    /**
     * 평가할 후보 고르기. 중간 하차는 직행 경로를 maxMiddleCandidates 개 시간 구간으로 나눠 구간마다 조회 순서상 첫 후보를 고르고,
     * 남은 자리는 조회 순서대로 채운다. D 하차·P 경유는 항상 포함한다.
     */
    fun selectForEvaluation(planned: List<PlannedCandidate>, base: CarRoute): List<PlannedCandidate> {
        val fixed = listOfNotNull(planned.firstOrNull { it.type == CandidateType.D_DROP }, planned.firstOrNull { it.type == CandidateType.P_VIA })
        val midCap = minOf(policy.maxMiddleCandidates, (policy.maxCandidatesPerRequest - fixed.size).coerceAtLeast(0))
        val mids = planned.filter { it.type == CandidateType.MIDDLE }
        val picked = LinkedHashSet<String>()
        if (midCap > 0 && mids.isNotEmpty()) {
            val total = base.totalTimeSec.coerceAtLeast(1).toDouble()
            fun bucket(m: PlannedCandidate) = (((m.routeOffsetSec ?: 0.0) / total) * midCap).toInt().coerceIn(0, midCap - 1)
            for (b in 0 until midCap) mids.firstOrNull { bucket(it) == b }?.let { picked += it.id }
            for (m in mids) { if (picked.size >= midCap) break; picked += m.id }
        }
        // 평가 순서: 조회 우선순위 그대로
        return (mids.filter { it.id in picked } + fixed).take(policy.maxCandidatesPerRequest)
    }

    private fun failOrHalt(c: PlannedCandidate, r: ProviderResult.Fail) =
        if (r.kind == FailKind.BUDGET) CandidateResult(c, CandStatus.NOT_QUERIED_HALTED, r.message)
        else CandidateResult(c, CandStatus.FAILED, "조회 실패(${r.kind}) — 이후 호출 중단")
}

/** 요청ID가 일치하는 최신 결과만 화면에 반영 */
class LatestResultGate {
    @Volatile var latestRequestId: String? = null; private set
    fun begin(id: String) { latestRequestId = id }
    fun accept(r: RecommendResult): Boolean = r.requestId == latestRequestId
}

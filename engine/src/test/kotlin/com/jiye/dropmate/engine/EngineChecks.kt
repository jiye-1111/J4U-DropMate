package com.jiye.dropmate.engine

/**
 * 합성 데이터 검증. JUnit 없이도 실행 가능하도록 순수 Kotlin 으로 작성했다.
 * - Android Studio: EngineJUnitTest 가 runAll() 을 호출
 * - CLI: main() 실행 (tools/run_engine_checks.sh)
 * 실제 API 원문은 사용하지 않는다(장기 테스트는 합성 자료).
 */
object EngineChecks {
    class Fail(msg: String) : AssertionError(msg)
    private fun check(cond: Boolean, msg: String) { if (!cond) throw Fail(msg) }
    private fun eq(a: Any?, b: Any?, msg: String) { if (a != b) throw Fail("$msg: expected=<$b> actual=<$a>") }

    // ---------- 합성 도구 ----------
    val O = LatLng(37.5000, 127.0000)
    val D = LatLng(37.5000, 127.1000)
    const val T0 = 1_793_800_800L // 2026-11-05 00:00 KST 근방, 분 경계(:00)

    fun line(a: LatLng, b: LatLng, totalSec: Int, n: Int = 50): CarRoute {
        val pts = (0..n).map { i -> LatLng(a.lat + (b.lat - a.lat) * i / n, a.lon + (b.lon - a.lon) * i / n) }
        val dist = Geo.haversineM(a, b)
        return CarRoute(totalSec, dist.toInt(), pts, (0..n).map { totalSec.toDouble() * it / n }, (0..n).map { dist * it / n })
    }

    fun itin(total: Int, firstWalkM: Int?, walkSec: Int = 300, transfers: Int = 0, walkOnly: Boolean = false): TransitItinerary {
        val legs = if (walkOnly) listOf(TransitLeg(LegMode.WALK, null, "s", "P", null, null, firstWalkM, total))
        else listOf(
            TransitLeg(LegMode.WALK, null, "출발", "정류장", null, null, firstWalkM, walkSec / 2),
            TransitLeg(LegMode.BUS, "100", "정류장", "하차", null, null, 3000, total - walkSec),
            TransitLeg(LegMode.WALK, null, "하차", "도착", null, null, 100, walkSec / 2),
        )
        return TransitItinerary(total, walkSec, (firstWalkM ?: 0) + 100, transfers, legs)
    }

    class FakeProvider(
        val car: (LatLng, LatLng, Long) -> ProviderResult<CarRoute>,
        val transit: (LatLng, LatLng, String) -> ProviderResult<List<TransitItinerary>>,
        val walkFn: ((LatLng, LatLng) -> ProviderResult<TransitItinerary>)? = null,
    ) : RouteProvider {
        val calls = mutableListOf<String>()
        override fun car(origin: LatLng, dest: LatLng, departEpochSec: Long): ProviderResult<CarRoute> { calls += "CAR $origin>$dest@$departEpochSec"; return car.invoke(origin, dest, departEpochSec) }
        override fun transit(origin: LatLng, dest: LatLng, searchDttm: String): ProviderResult<List<TransitItinerary>> { calls += "TR $origin>$dest@$searchDttm"; return transit.invoke(origin, dest, searchDttm) }
        override fun walk(origin: LatLng, dest: LatLng): ProviderResult<TransitItinerary> { calls += "WALK $origin>$dest"; return walkFn?.invoke(origin, dest) ?: ProviderResult.NoRoute("UNSUPPORTED", "-") }
    }

    fun req(p: LatLng, id: String = "R1") = RecommendRequest(id, O, D, p, T0, live = false)

    /** GTFS 없이 D 하차 + P 경유만 비교하는 엔진 */
    fun engineNoGtfs(policy: Policy = Policy()) = DropMateEngine(null, policy)

    /** 같은 하차점 경로 선택 검사용 여정. route 로 구간 구성을 구분한다. 환승 수·보행시간 null 허용 */
    fun opt(index: Int, arrival: Long, walkSec: Int?, transfers: Int?, route: String = "R$index",
            status: CandStatus = CandStatus.EVALUATED): EvaluatedItinerary {
        val legs = listOf(
            TransitLeg(LegMode.WALK, null, "s", route, null, null, 100, 60),
            TransitLeg(LegMode.BUS, route, route, "P", null, null, 3000, 600),
        )
        return EvaluatedItinerary(index, TransitItinerary(arrival.toInt(), walkSec, 100, transfers, legs),
            if (status == CandStatus.EVALUATED) T0 + arrival else null, status, "합성")
    }

    private fun kindsOf(o: List<RouteOption>) = o.map { it.itinerary.index to it.kinds }

    val routeOptionChecks: List<Pair<String, () -> Unit>> = listOf(
        "같은 하차점: 세 기준이 모두 다른 여정이면 3개, 빠른 도착이 먼저" to {
            val o = RouteOptions.select(listOf(
                opt(0, 1300, 100, 1), opt(1, 1000, 600, 2), opt(2, 1200, 500, 0)))
            eq(kindsOf(o), listOf(
                1 to listOf(RouteOptionKind.FASTEST),
                2 to listOf(RouteOptionKind.FEWEST_TRANSFERS),
                0 to listOf(RouteOptionKind.LEAST_WALK)), "선택·순서")
        },
        "같은 하차점: 같은 여정은 배지 병합(빠른 도착+최소 보행)" to {
            val o = RouteOptions.select(listOf(opt(0, 1000, 100, 1), opt(1, 1200, 300, 0)))
            eq(kindsOf(o), listOf(
                0 to listOf(RouteOptionKind.FASTEST, RouteOptionKind.LEAST_WALK),
                1 to listOf(RouteOptionKind.FEWEST_TRANSFERS)), "병합")
        },
        "같은 하차점: 한 여정이 세 기준 모두면 배지 3개 한 줄" to {
            val o = RouteOptions.select(listOf(opt(0, 1000, 100, 0), opt(1, 1100, 200, 1)))
            eq(kindsOf(o), listOf(0 to RouteOptionKind.entries.toList()), "전부 병합")
        },
        "같은 하차점: 응답 순서만 다른 동일 구성·동일 도착 여정은 같은 경로로 병합" to {
            val a = opt(0, 1000, 300, 1, route = "X")
            val b = opt(1, 1000, 300, 1, route = "X")
            check(RouteOptions.sameRoute(a, b), "동일 구성")
            check(!RouteOptions.sameRoute(a, opt(2, 1060, 300, 1, route = "X")), "도착이 다르면 별도")
            check(!RouteOptions.sameRoute(a, opt(3, 1000, 300, 1, route = "Y")), "노선이 다르면 별도")
            val o = RouteOptions.select(listOf(opt(0, 1000, 300, 1, route = "X"), opt(1, 1000, 300, 1, route = "X")))
            eq(o.size, 1, "중복 응답 1줄")
        },
        "같은 하차점: 환승 수 누락은 0회로 보지 않음" to {
            val o = RouteOptions.select(listOf(opt(0, 1000, 200, null), opt(1, 1100, 300, 2)))
            eq(o.first { RouteOptionKind.FEWEST_TRANSFERS in it.kinds }.itinerary.index, 1, "누락 제외")
            eq(o.first { RouteOptionKind.FASTEST in it.kinds }.itinerary.index, 0, "빠른 도착은 누락과 무관")
        },
        "같은 하차점: 보행시간 누락 여정은 최소 보행 대상 아님, 모두 누락이면 배지 없음" to {
            val o = RouteOptions.select(listOf(opt(0, 1000, null, 1), opt(1, 1100, null, 0)))
            check(o.none { RouteOptionKind.LEAST_WALK in it.kinds }, "최소 보행 없음")
            eq(o.size, 2, "빠른 도착·최소 환승만")
        },
        "같은 하차점: 제외·미확정 여정은 어떤 기준에도 쓰지 않음" to {
            val o = RouteOptions.select(listOf(
                opt(0, 1000, 600, 2),
                opt(1, 900, 50, 0, status = CandStatus.EXCLUDED_ACCESS_WALK),
                opt(2, 950, 40, 0, status = CandStatus.ACCESS_WALK_UNKNOWN)))
            eq(kindsOf(o), listOf(0 to RouteOptionKind.entries.toList()), "평가 완료만")
            eq(RouteOptions.select(listOf(opt(0, 900, 50, 0, status = CandStatus.FAILED))).size, 0, "평가 완료 없음")
        },
        "같은 하차점: 동률 처리(환승 동률→빠른 도착, 보행 동률→빠른 도착, 도착 동률→보행)" to {
            val o = RouteOptions.select(listOf(opt(0, 1200, 300, 0), opt(1, 1100, 300, 0), opt(2, 1100, 200, 1)))
            eq(o.first { RouteOptionKind.FASTEST in it.kinds }.itinerary.index, 2, "도착 동률 → 보행 짧은 쪽")
            eq(o.first { RouteOptionKind.FEWEST_TRANSFERS in it.kinds }.itinerary.index, 1, "환승 동률 → 빠른 도착")
            eq(o.first { RouteOptionKind.LEAST_WALK in it.kinds }.itinerary.index, 2, "최소 보행")
        },
        "탭 선택: 병합된 여정은 두 탭에서 같은 여정, 값 없는 기준은 null" to {
            val o = RouteOptions.select(listOf(opt(0, 1000, 600, 2), opt(1, 1200, 100, 0)))
            eq(RouteOptions.pick(o, RouteOptionKind.FASTEST)?.itinerary?.index, 0, "최소시간")
            eq(RouteOptions.pick(o, RouteOptionKind.FEWEST_TRANSFERS)?.itinerary?.index, 1, "최소환승")
            eq(RouteOptions.pick(o, RouteOptionKind.LEAST_WALK)?.itinerary?.index, 1, "최소도보 = 최소환승 여정")
            val none = RouteOptions.select(listOf(opt(0, 1000, null, null)))
            eq(RouteOptions.pick(none, RouteOptionKind.LEAST_WALK), null, "보행 누락 → 탭 비활성")
        },
        "엔진 연결: D 하차 후보에 경로 선택지가 붙고 순위는 빠른 도착 그대로" to {
            val p = LatLng(37.52, 127.05)
            fun it3(total: Int, walk: Int, tr: Int, r: String) = TransitItinerary(total, walk, 300, tr, listOf(
                TransitLeg(LegMode.WALK, null, "s", r, null, null, 100, 60),
                TransitLeg(LegMode.BUS, r, r, "P", null, null, 3000, total - 60)))
            val prov = FakeProvider({ o, d, _ -> ProviderResult.Ok(line(o, d, if (o == O && d == D) 1000 else 5000)) },
                { _, _, _ -> ProviderResult.Ok(listOf(it3(1500, 400, 0, "느린직통"), it3(1200, 700, 2, "빠른환승"), it3(1600, 100, 1, "적은보행"))) })
            val r = engineNoGtfs().recommend(req(p), prov, MemoryLedger(26))
            val dd = r.candidates.first { it.plan.type == CandidateType.D_DROP }
            eq(dd.status, CandStatus.EVALUATED, "D 하차 평가")
            eq(dd.routeOptions.map { it.itinerary.itinerary.legs[1].route to it.kinds }, listOf(
                "빠른환승" to listOf(RouteOptionKind.FASTEST),
                "느린직통" to listOf(RouteOptionKind.FEWEST_TRANSFERS),
                "적은보행" to listOf(RouteOptionKind.LEAST_WALK)), "선택지")
            eq(dd.chosen?.index, dd.routeOptions.first().itinerary.index, "chosen = 빠른 도착")
            eq(dd.passengerArrivalEpochSec, dd.routeOptions.first().itinerary.arrivalEpochSec, "순위 기준 불변")
            eq(dd.transferCount, 2, "카드 환승 수는 빠른 도착 여정 기준")
        },
    )

    private fun cand(id: String, status: CandStatus, pArr: Long?, dArr: Long?, walk: Int? = 300) =
        CandidateResult(PlannedCandidate(id, CandidateType.MIDDLE, O, id, 0.0, null, null, GtfsLink.DIRECT, 0,
            PrefilterState.QUERY_TARGET, id, 0.0), status, "합성 $id",
            driverArrivalEpochSec = dArr, passengerArrivalEpochSec = pArr, totalWalkSec = walk)

    val dropComparisonChecks: List<Pair<String, () -> Unit>> = listOf(
        "여러 하차점: 분 반올림 → 운전자 도착 → 보행 → ID 순, 순위 외 후보는 사유 보존해 뒤로" to {
            val list = listOf(
                cand("M-c", CandStatus.EVALUATED, T0 + 1800, T0 + 1100),
                cand("M-a", CandStatus.EXCLUDED_DETOUR, null, T0 + 2000),
                cand("M-b", CandStatus.EVALUATED, T0 + 1789, T0 + 1200),   // 1789초 = 29.8분 → 30분, c 와 분 동률
                cand("M-d", CandStatus.EVALUATED, T0 + 1500, T0 + 1300),
                cand("M-e", CandStatus.NOT_QUERIED_BUDGET, null, null),
            )
            val r = DropComparison.rank(list, MinuteRounding.ROUND)
            eq(r.map { it.candidate.plan.id to it.rank }, listOf("M-d" to 1, "M-c" to 2, "M-b" to 3, "M-a" to null, "M-e" to null), "순서")
            eq(r.map { it.gapToBestSec }, listOf(0L, 300L, 289L, null, null), "1위 대비 원본 초 차이")
            eq(r[3].candidate.reason, "합성 M-a", "제외 사유 보존")
        },
        "여러 하차점: 순위 대상이 없으면 전부 순위 없음" to {
            val r = DropComparison.rank(listOf(cand("x", CandStatus.FAILED, null, null)), MinuteRounding.ROUND)
            eq(r.single().rank, null, "순위 없음"); eq(r.single().gapToBestSec, null, "차이 없음")
        },
        "여러 하차점: 엔진 선택(best)과 비교 1위가 같고 추가 호출 없음" to {
            val P = LatLng(37.52, 127.05)
            val prov = FakeProvider({ o, d, _ -> when {
                o == O && d == D -> ProviderResult.Ok(line(O, D, 3600))
                o == O && d == P -> ProviderResult.Ok(line(O, P, 3600))
                else -> ProviderResult.Ok(line(P, D, 500)) } }, { _, _, _ -> ProviderResult.Ok(listOf(itin(5000, 100))) })
            val r = engineNoGtfs().recommend(req(P), prov, MemoryLedger(26))
            val sent = prov.calls.size
            val ranking = r.dropRanking()
            eq(prov.calls.size, sent, "비교 목록은 호출 0")
            eq(ranking.size, r.candidates.size, "모든 후보 표시")
            eq(ranking.first().candidate.plan.id, r.best?.plan?.id, "1위 = 선택")
            check(ranking.count { it.rank != null } >= 2, "2개 이상 비교")
        },
    )

    private fun handoffResult(verdict: Verdict, suit: StopSuitability): Pair<RecommendResult, CandidateResult> {
        val c0 = cand("M-x", CandStatus.EVALUATED, T0 + 1500, T0 + 1300)
        val c = c0.copy(plan = c0.plan.copy(stopSuitability = suit))
        val other = cand("M-y", CandStatus.EVALUATED, T0 + 1600, T0 + 1300)
        val r = RecommendResult("H", "t", T0, "s", verdict, "", c, "", true, 300, null, null, listOf(c, other), emptyMap(), emptyList(), 0, 0, null, emptyList())
        return r to c
    }

    val handoffChecks: List<Pair<String, () -> Unit>> = listOf(
        "하차 가능 도로: 이름에 지하차도·터널·고가가 든 정류장은 구간 코드와 무관하게 제외" to {
            val s = synthGtfs()
            val renamed = GtfsIndex(s.stops.map { if (it.id == "A1") it.copy(name = "낙생지하차도") else it }, s.patterns, "synthetic")
            val (plan, counts) = DropMateEngine(renamed).planCandidates(req(LatLng(37.53, 127.05)), line(O, D, 1000))
            check(plan.none { it.accessStop?.id == "A1" }, "지하차도 이름 제외")
            check((counts["no_stop_name"] ?: 0) >= 1, "제외 수 기록")
            check(plan.any { it.accessStop?.id == "A1_opp" }, "다른 이름은 유지")
        },
        "하차 가능 도로: TMAP 코드표대로 고속도로·자동차전용·터널·교량 등 제외, 누락은 미확인" to {
            val pol = Policy()
            eq(roadStopSuitability(RoadSeg(0, 0), pol), StopSuitability.NO_STOP, "고속도로")
            eq(roadStopSuitability(RoadSeg(1, 0), pol), StopSuitability.NO_STOP, "자동차전용")
            eq(roadStopSuitability(RoadSeg(16, 2), pol), StopSuitability.NO_STOP, "터널")
            eq(roadStopSuitability(RoadSeg(6, 1), pol), StopSuitability.NO_STOP, "교량")
            eq(roadStopSuitability(RoadSeg(5, 4), pol), StopSuitability.NO_STOP, "지하도로")
            eq(roadStopSuitability(RoadSeg(5, 0), pol), StopSuitability.ROAD_OK, "주요도로 일반")
            eq(roadStopSuitability(RoadSeg(null, 0), pol), StopSuitability.UNVERIFIED, "도로 종류 누락")
            eq(roadStopSuitability(null, pol), StopSuitability.UNVERIFIED, "구간 정보 없음")
        },
        "하차 가능 도로: 터널 구간 위 투영 후보는 계획에서 제외(호출 0), 나머지는 일반도로·목적지 표시" to {
            val base0 = line(O, D, 1000)
            // 50개 구간 중 경도 127.018~127.022(구간 9·10)를 터널로, 나머지는 일반도로로 표시
            val segs = (0 until 50).map { i -> if (i == 9 || i == 10) RoadSeg(16, 2) else RoadSeg(5, 0) }
            val (plan, counts) = DropMateEngine(synthGtfs()).planCandidates(req(LatLng(37.53, 127.05)), base0.copy(segRoad = segs))
            check(plan.none { it.accessStop?.id == "A1" || it.accessStop?.id == "A1_opp" }, "터널 위 후보 제외")
            eq(counts["no_stop_road"], 2, "제외 수 기록")
            check(plan.filter { it.type == CandidateType.MIDDLE }.all { it.stopSuitability == StopSuitability.ROAD_OK }, "나머지 일반도로")
            check(plan.filter { it.type != CandidateType.MIDDLE }.all { it.stopSuitability == StopSuitability.DESTINATION }, "D·P 목적지")
            val (plan2, _) = DropMateEngine(synthGtfs()).planCandidates(req(LatLng(37.53, 127.05)), base0)
            check(plan2.filter { it.type == CandidateType.MIDDLE }.all { it.stopSuitability == StopSuitability.UNVERIFIED }, "도로 정보 없으면 미확인")
        },
        "길안내 전달: 미확인 지점은 추천 판정이어도 차단" to {
            val (r, c) = handoffResult(Verdict.RECOMMEND, StopSuitability.UNVERIFIED)
            eq(NavHandoff.decide(r, c).allowed, false, "미확인 차단")
        },
        "길안내 전달: 일반도로·목적지·확인 지점 + ★ + 추천일 때만 허용, ★ 아님·이득 없음·하차 불가는 차단" to {
            val (r, c) = handoffResult(Verdict.RECOMMEND, StopSuitability.VERIFIED)
            eq(NavHandoff.decide(r, c), HandoffDecision(true, emptyList()), "허용")
            check(NavHandoff.decide(handoffResult(Verdict.RECOMMEND, StopSuitability.ROAD_OK).first, handoffResult(Verdict.RECOMMEND, StopSuitability.ROAD_OK).second).allowed, "일반도로 허용")
            check(NavHandoff.decide(handoffResult(Verdict.SIMILAR, StopSuitability.DESTINATION).first, handoffResult(Verdict.SIMILAR, StopSuitability.DESTINATION).second).allowed, "목적지 허용")
            check(!NavHandoff.decide(handoffResult(Verdict.RECOMMEND, StopSuitability.NO_STOP).first, handoffResult(Verdict.RECOMMEND, StopSuitability.NO_STOP).second).allowed, "하차 불가 도로 차단")
            check(!NavHandoff.decide(r, r.candidates[1]).allowed, "★ 아님 차단")
            val (r2, c2) = handoffResult(Verdict.NO_GAIN, StopSuitability.VERIFIED)
            check(!NavHandoff.decide(r2, c2).allowed, "이득 없음 차단")
            check(!NavHandoff.decide(null, null).allowed, "결과 없음 차단")
        },
    )

    private fun navRoute(): CarRoute {
        val r = line(O, D, 1000)
        val L = r.cumDistM.last()
        fun tp(f: Double, t: Int, road: String?) = TurnPoint(LatLng(0.0, 0.0), L * f, t, null, road, null)
        return r.copy(turns = listOf(tp(0.0, 200, "출발로"), tp(0.3, 13, "우회전로"), tp(0.45, 211, null), tp(0.7, 12, "좌회전로"), tp(1.0, 201, null)))
    }

    private fun s0(r: CarRoute, f: Double) = NavSession(r, null).pointAt(r.cumDistM.last() * f)

    val guidanceChecks: List<Pair<String, () -> Unit>> = listOf(
        "길안내: 다음 회전·그다음(하차 지점 포함)·남은 거리, 안내 대상 아닌 코드(출발지·횡단보도) 제외" to {
            val r = navRoute(); val L = r.cumDistM.last()
            val s = NavSession(r, L * 0.5)
            val st = s.update(s.pointAt(L * 0.1))
            eq(st.next?.kind, TurnArrow.RIGHT, "다음 = 우회전"); eq(st.next?.roadName, "우회전로", "도로명")
            check(kotlin.math.abs(st.next!!.distanceM - L * 0.2) < 2.0, "우회전까지 거리 ${st.next.distanceM}")
            eq(st.after?.isDrop, true, "그다음 = 하차 지점")
            check(kotlin.math.abs(st.toDropM!! - L * 0.4) < 2.0, "하차까지 거리")
            check(kotlin.math.abs(st.remainingM - L * 0.9) < 2.0, "남은 거리")
            check(kotlin.math.abs(st.remainingSec - 900.0) < 2.0, "남은 시간(구간 시간 비례)")
            check(st.offRouteM < 5.0 && !s.offRoute(st), "경로 위")
        },
        "길안내: 하차 지점을 지나면 하차 안내 사라짐, 끝에서 도착" to {
            val r = navRoute(); val L = r.cumDistM.last()
            val s = NavSession(r, L * 0.5)
            val st = s.stateAt(L * 0.6)
            eq(st.toDropM, null, "하차 지남"); eq(st.next?.kind, TurnArrow.LEFT, "다음 = 좌회전")
            eq(st.after?.kind, TurnArrow.ARRIVE, "그다음 = 도착")
            check(s.stateAt(L - 5).arrived, "도착 판정")
        },
        "길안내: 경로에서 벗어나면 이탈 표시(재탐색 호출 없음)" to {
            val r = navRoute(); val s = NavSession(r, null)
            val st = s.update(LatLng(O.lat + 0.002, O.lon + 0.03)) // 약 220m 북쪽
            check(s.offRoute(st), "이탈 ${st.offRouteM}")
        },
        "길안내: P 경유는 O→P + P→D 를 이어 붙이고 하차 지점 = 첫 경로 끝" to {
            val P = LatLng(37.52, 127.05)
            val a = line(O, P, 600).copy(turns = listOf(TurnPoint(P, 0.0, 201, null, null, null)))
            val c = line(P, D, 500).copy(turns = listOf(TurnPoint(D, 0.0, 13, null, "다음길", null)))
            val m = NavSession.concat(a, c)
            eq(m.totalTimeSec, 1100, "시간 합")
            check(m.turns.none { it.turnType == 201 && it.cumDistM < a.cumDistM.last() }, "앞 경로 도착점 제거")
            check(kotlin.math.abs(m.turns.last().cumDistM - a.cumDistM.last()) < 0.01, "뒤 경로 안내점 거리 이동")
            eq(m.cumTimeSec.last(), 1100.0, "누적 시간")
        },
        "음성 안내: 1km·300m·직전 단계마다 한 번씩, 하차 지점은 준비 문장, 도착 한 번" to {
            val r = navRoute(); val L = r.cumDistM.last()
            val s = NavSession(r, L * 0.5); val v = VoiceGuide()
            val turnAt = L * 0.3
            fun at(dist: Double) = v.next(s.stateAt(turnAt - dist))
            check(at(900.0)?.startsWith("1킬로미터 앞에서 우회전") == true, "1km")
            eq(at(850.0), null, "같은 단계 반복 없음")
            check(at(250.0)?.startsWith("300미터 앞에서 우회전, 우회전로") == true, "300m + 도로명")
            check(at(40.0)?.startsWith("곧 우회전") == true, "직전")
            eq(at(30.0), null, "직전 반복 없음")
            val dropMsg = v.next(s.stateAt(L * 0.5 - 50.0))
            check(dropMsg?.contains("동승자는 내릴 준비") == true, "하차 지점 직전 문장: $dropMsg")
            check(v.dropReached() != null && v.dropReached() == null, "하차 도착 한 번")
            check(v.next(s.stateAt(L - 5)) == "목적지에 도착했어요. 안내를 마칩니다.", "도착")
        },
        "재탐색 세션: 새 경로에 하차 지점 투영, 하차 지점과 겹치는 TMAP 경유지 안내는 하나로" to {
            val r0 = line(O, D, 1000); val L = r0.cumDistM.last()
            val r = r0.copy(turns = listOf(TurnPoint(O, L * 0.5, 185, null, null, null), TurnPoint(D, L, 201, null, null, null)))
            val s = NavSession.fromRoute(r, s0(r0, 0.5))
            check(kotlin.math.abs(s.dropCumDistM!! - L * 0.5) < 2.0, "투영 위치")
            val st = s.stateAt(L * 0.3)
            eq(st.next?.isDrop, true, "하차 지점 안내")
            eq(st.after?.kind, TurnArrow.ARRIVE, "경유지 중복 안내 없음")
        },
        "길안내: 모의 주행은 구간 시간대로 진행(시간→거리)" to {
            val r = navRoute(); val L = r.cumDistM.last(); val s = NavSession(r, null)
            check(kotlin.math.abs(s.distAtTime(250.0) - L * 0.25) < 1.0, "시간 비례 거리")
            check(kotlin.math.abs(s.stateAt(L * 0.25).bearing - 90.0) < 3.0, "동쪽 진행 방향")
        },
    )

    // ---------- 검사 ----------
    val checks: List<Pair<String, () -> Unit>> get() = baseChecks + routeOptionChecks + dropComparisonChecks + handoffChecks + guidanceChecks

    private val baseChecks: List<Pair<String, () -> Unit>> = listOf(
        "경유가 직행보다 빠르게 나오면 우회 0초로 보고 순위 포함(2026-11-12 정확도 개선)" to {
            val p = LatLng(37.52,127.05)
            val provider = FakeProvider({ o,d,_ ->
                ProviderResult.Ok(line(o,d, if(o==O && d==D) 1800 else 100))
            }, { _,_,_ -> ProviderResult.Ok(listOf(itin(1200,100))) })
            val r = engineNoGtfs().recommend(req(p),provider,MemoryLedger(26))
            val via = r.candidates.first { it.plan.type == CandidateType.P_VIA }
            eq(via.status,CandStatus.EVALUATED,"음수 우회도 평가")
            eq(via.driverAddSec,0,"우회 0초")
            check(via.rankable,"순위 포함")
            check(via.driverAddBasis!!.contains("0초로 봄"),"판단 근거")
        },
        "PLAN 4.1 같은 이름 반대 방향 접근점을 합치지 않음" to {
            val (plan,_) = DropMateEngine(synthGtfs()).planCandidates(req(LatLng(37.53,127.05)),line(O,D,1000))
            val forward=plan.first { it.accessStop?.id=="A1" }
            val reverse=plan.first { it.accessStop?.id=="A1_opp" }
            check(forward.groupKey != reverse.groupKey,"다른 정류장 ID 보존")
        },
        "운전자 우회 한도 30% 규칙: 직행 600/1800/3600/7200초 → 한도 180/540/900/900초(상한 15분)" to {
            val pol = Policy()
            for ((direct, lim) in listOf(600 to 180, 1800 to 540, 3000 to 900, 3600 to 900, 7200 to 900)) eq(pol.allowedDetourSec(direct), lim, "직행 $direct")
            check(pol.nearLimit(432, 1800) && !pol.nearLimit(431, 1800), "근접 = 0.8 × 한도")
            // 직행 1800초: 우회 539/540/541초 → 평가/평가/제외
            for ((y, expect) in listOf(479 to CandStatus.EVALUATED, 480 to CandStatus.EVALUATED, 481 to CandStatus.EXCLUDED_DETOUR)) {
                val P = LatLng(37.52, 127.05)
                val prov = FakeProvider({ o, d, _ -> when {
                    o == O && d == D -> ProviderResult.Ok(line(O, D, 1800))
                    o == O && d == P -> ProviderResult.Ok(line(O, P, 1800))
                    else -> ProviderResult.Ok(line(P, D, y)) } }, { _, _, _ -> ProviderResult.Ok(listOf(itin(5000, 100))) })
                val r = engineNoGtfs().recommend(req(P), prov, MemoryLedger(26))
                eq(r.candidates.first { it.plan.type == CandidateType.P_VIA }.status, expect, "add=${60 + y} / 한도 540")
            }
        },
        "운전자 추가시간 899/900/901초(직행 3600초, 상한 15분)" to {
            for ((y, expect) in listOf(839 to CandStatus.EVALUATED, 840 to CandStatus.EVALUATED, 841 to CandStatus.EXCLUDED_DETOUR)) {
                val P = LatLng(37.52, 127.05)
                // add = O→P(3600) + 60 + P→D(y) − O→D(3600) = 60+y
                val prov = FakeProvider({ o, d, _ -> when {
                    o == O && d == D -> ProviderResult.Ok(line(O, D, 3600))
                    o == O && d == P -> ProviderResult.Ok(line(O, P, 3600))
                    else -> ProviderResult.Ok(line(P, D, y)) } }, { _, _, _ -> ProviderResult.Ok(listOf(itin(5000, 100))) })
                val r = engineNoGtfs().recommend(req(P), prov, MemoryLedger(26))
                val pv = r.candidates.first { it.plan.type == CandidateType.P_VIA }
                eq(pv.status, expect, "add=${60 + y}")
                if (expect == CandStatus.EVALUATED) eq(pv.driverAddSec, 60 + y, "driverAdd")
            }
        },
        "P 경유: O→P 하한만으로 한도 초과면 P→D 호출 생략" to {
            val P = LatLng(37.60, 127.05)
            val prov = FakeProvider({ o, d, _ -> if (d == D && o == O) ProviderResult.Ok(line(O, D, 1000)) else ProviderResult.Ok(line(o, d, 2000)) },
                { _, _, _ -> ProviderResult.Ok(listOf(itin(3000, 100))) })
            val r = engineNoGtfs().recommend(req(P), prov, MemoryLedger(26))
            val pv = r.candidates.first { it.plan.type == CandidateType.P_VIA }
            eq(pv.status, CandStatus.EXCLUDED_DETOUR, "status"); eq(pv.callsUsed, 1, "P_VIA 호출 수")
            check(prov.calls.none { it.startsWith("CAR ${P}>") }, "P→D 호출이 없어야 함")
        },
        "실제 접근 보행 599/600/601초" to {
            for ((w, expect) in listOf(599 to CandStatus.EVALUATED, 600 to CandStatus.EVALUATED, 601 to CandStatus.EXCLUDED_ACCESS_WALK)) {
                val P = LatLng(37.45, 127.10)
                val prov = FakeProvider({ o, d, _ -> ProviderResult.Ok(line(o, d, if (o == O && d == D) 1000 else 5000)) },
                    { o, _, _ -> if (o == D) ProviderResult.Ok(listOf(itin(2400, 900, walkSec = w * 2))) else ProviderResult.Ok(listOf(itin(4000, 100))) })
                val r = engineNoGtfs().recommend(req(P), prov, MemoryLedger(26))
                eq(r.candidates.first { it.plan.type == CandidateType.D_DROP }.status, expect, "walk=$w")
            }
        },
        "첫 보행시간 누락은 0이 아니라 미확인" to {
            val P = LatLng(37.45, 127.10)
            val prov = FakeProvider({ o, d, _ -> ProviderResult.Ok(line(o, d, if (o == O && d == D) 1000 else 5000)) },
                { o, _, _ -> if (o == D) ProviderResult.Ok(listOf(itin(1200, 100).let { it.copy(legs = it.legs.mapIndexed { index, leg -> if (index == 0) leg.copy(sectionTimeSec = null) else leg }) })) else ProviderResult.Ok(listOf(itin(4000, 100))) })
            val r = engineNoGtfs().recommend(req(P), prov, MemoryLedger(26))
            val dd = r.candidates.first { it.plan.type == CandidateType.D_DROP }
            eq(dd.status, CandStatus.ACCESS_WALK_UNKNOWN, "status"); check(dd.passengerArrivalEpochSec == null, "도착 미산정")
        },
        "분 정렬 :00→0초, :01→59초, :59→1초" to {
            eq(TimeUtil.alignDiffSec(T0), 0, ":00"); eq(TimeUtil.alignDiffSec(T0 + 1), 59, ":01"); eq(TimeUtil.alignDiffSec(T0 + 59), 1, ":59")
        },
        "순위 분 변환(반올림)과 동률 처리: 분 → 운전자 D 도착 → 보행" to {
            eq(TimeUtil.rankMinute(T0 + 29, MinuteRounding.ROUND), T0 / 60, "29초 내림"); eq(TimeUtil.rankMinute(T0 + 30, MinuteRounding.ROUND), T0 / 60 + 1, "30초 올림")
            // D 하차(추가 60초, 도착 T0+1000+60+60+1200) vs P 경유 동일 분 도착이지만 운전자 도착이 늦음
            val P = LatLng(37.45, 127.10)
            val dropD = T0 + 1000 + 60
            val arrD = TimeUtil.ceilToMinute(dropD) + 1200
            // P 경유 도착 = T0 + opT + 60 → arrD 와 같은 분(+10초)이 되도록
            val opT = (arrD + 10 - T0 - 60).toInt()
            val prov = FakeProvider({ o, d, _ -> when {
                o == O && d == D -> ProviderResult.Ok(line(O, D, 1000)); o == O && d == P -> ProviderResult.Ok(line(O, P, opT)); else -> ProviderResult.Ok(line(P, D, 600)) } },
                { o, _, _ -> if (o == D) ProviderResult.Ok(listOf(itin(1200, 100))) else ProviderResult.Ok(listOf(itin(9000, 100))) })
            val r = engineNoGtfs().recommend(req(P), prov, MemoryLedger(26))
            eq(r.best?.plan?.type, CandidateType.D_DROP, "같은 분이면 운전자 D 도착이 이른 D 하차가 선택")
        },
        "보행·대기 중복 합산 방지: 도착 = 정렬 하차시각 + totalTime 1회" to {
            val P = LatLng(37.45, 127.10)
            val prov = FakeProvider({ o, d, _ -> ProviderResult.Ok(line(o, d, if (o == O && d == D) 961 else 9000)) },
                { o, _, _ -> if (o == D) ProviderResult.Ok(listOf(itin(1500, 200, walkSec = 400))) else ProviderResult.Ok(listOf(itin(9000, 100))) })
            val r = engineNoGtfs().recommend(req(P), prov, MemoryLedger(26))
            val dd = r.candidates.first { it.plan.type == CandidateType.D_DROP }
            val drop = T0 + 961 + 60 // 1021초 = :01
            eq(dd.passengerArrivalEpochSec, TimeUtil.ceilToMinute(drop) + 1500, "arrival"); eq(dd.alignDiffSec, 59, "정렬 차이")
        },
        "반환된 모든 여정 비교(첫 여정이 최단이라고 가정하지 않음)" to {
            val P = LatLng(37.45, 127.10)
            val prov = FakeProvider({ o, d, _ -> ProviderResult.Ok(line(o, d, if (o == O && d == D) 1000 else 9000)) },
                { o, _, _ -> if (o == D) ProviderResult.Ok(listOf(itin(2000, 100), itin(1500, 100, walkSec = 1202), itin(1600, 300))) else ProviderResult.Ok(listOf(itin(9000, 100))) })
            val r = engineNoGtfs().recommend(req(P), prov, MemoryLedger(26))
            eq(r.candidates.first { it.plan.type == CandidateType.D_DROP }.chosen?.index, 2, "600초 초과 여정 제외 후 1600초 여정 선택")
        },
        "도보 직행 여정: 한도 미확정(null)이면 순위 미반영" to {
            val P = LatLng(37.45, 127.10)
            val prov = FakeProvider({ o, d, _ -> ProviderResult.Ok(line(o, d, if (o == O && d == D) 1000 else 9000)) },
                { o, _, _ -> if (o == D) ProviderResult.Ok(listOf(itin(500, 600, walkOnly = true))) else ProviderResult.Ok(listOf(itin(9000, 100))) })
            val r = engineNoGtfs(Policy(walkDirectLimitSec = null)).recommend(req(P), prov, MemoryLedger(26))
            eq(r.candidates.first { it.plan.type == CandidateType.D_DROP }.status, CandStatus.WALK_DIRECT_UNRATED, "status")
        },
        "도보 직행 한도를 정하면(600초): 600초 이하 평가, 601초 제외" to {
            val P = LatLng(37.45, 127.10)
            for ((t, st) in listOf(600 to CandStatus.EVALUATED, 601 to CandStatus.EXCLUDED_ACCESS_WALK)) {
                val prov = FakeProvider({ o, d, _ -> ProviderResult.Ok(line(o, d, if (o == O && d == D) 1000 else 9000)) },
                    { o, _, _ -> if (o == D) ProviderResult.Ok(listOf(itin(t, 700, walkOnly = true))) else ProviderResult.Ok(listOf(itin(9000, 100))) })
                val r = engineNoGtfs(Policy(walkDirectLimitSec = 600)).recommend(req(P), prov, MemoryLedger(26))
                eq(r.candidates.first { it.plan.type == CandidateType.D_DROP }.status, st, "도보 ${t}초")
            }
        },
        "도보 직행 기본값 = 한도 없음(DEC-14): 30분 도보도 평가" to {
            val P = LatLng(37.45, 127.10)
            for (t in listOf(601, 1800)) {
                val prov = FakeProvider({ o, d, _ -> ProviderResult.Ok(line(o, d, if (o == O && d == D) 1000 else 9000)) },
                    { o, _, _ -> if (o == D) ProviderResult.Ok(listOf(itin(t, 2000, walkOnly = true))) else ProviderResult.Ok(listOf(itin(9000, 100))) })
                val r = engineNoGtfs().recommend(req(P), prov, MemoryLedger(26))
                eq(r.candidates.first { it.plan.type == CandidateType.D_DROP }.status, CandStatus.EVALUATED, "도보 ${t}초")
            }
        },
        "문 앞 경유: 기본 운전자 입구가 불리하면 다른 운전자 입구를 고름(2026-11-13)" to {
            val P = LatLng(37.4950, 127.0500)
            val e1 = LatLng(37.5001, 127.1000); val e2 = LatLng(37.4999, 127.0990)
            // 출발지에서는 E1 이 가장 빠르지만(1000 < 1100), 동승자 입구에서는 E2 가 훨씬 가깝다(150 < 600)
            val prov = FakeProvider({ o, d, _ -> ProviderResult.Ok(line(o, d, when {
                o == O && d == e1 -> 1000; o == O && d == e2 -> 1100; o == O && d == P -> 900
                o == P && d == e1 -> 600; o == P && d == e2 -> 150; else -> 9000 })) },
                { _, _, _ -> ProviderResult.Ok(listOf(itin(9000, 100))) })
            val r = engineNoGtfs().recommend(req(P).copy(
                driverEntrances = listOf(Entrance(e1, "E1", EntranceKind.CAR, "t"), Entrance(e2, "E2", EntranceKind.CAR, "t")),
                passengerEntrances = listOf(Entrance(P, "PG", EntranceKind.CAR, "t"))), prov, MemoryLedger(40))
            val pv = r.candidates.first { it.plan.type == CandidateType.P_VIA }
            eq(pv.status, CandStatus.EVALUATED, "다른 입구로 한도 안")
            eq(pv.driverDestPos, e2, "운전자 입구 E2 선택")
            eq(pv.driverAddSec, 900 + 60 + 150 - 1000, "운전자 추가 = O→P + 하차 + P→E2 − 직행(E1)")
            check(r.notes.any { it.startsWith("P_VIA 운전자 입구: E2") }, "notes 기록: ${r.notes}")
        },
        "대중교통 code 11(너무 가까움) → 보행자 경로로 도보 시간 계산, D 하차가 최선" to {
            val P = LatLng(37.5010, 127.1010)
            val walkLeg = TransitItinerary(420, 420, 500, 0, listOf(TransitLeg(LegMode.WALK, null, "D", "P", D, P, 500, 420)))
            val prov = FakeProvider({ o, d, _ -> ProviderResult.Ok(line(o, d, if (o == O && d == D) 1000 else 9000)) },
                { o, _, _ -> if (o == D) ProviderResult.NoRoute("11", "가까움") else ProviderResult.Ok(listOf(itin(3000, 100))) },
                { o, _ -> if (o == D) ProviderResult.Ok(walkLeg) else ProviderResult.NoRoute("X", "-") })
            val r = engineNoGtfs().recommend(req(P), prov, MemoryLedger(26))
            val dd = r.candidates.first { it.plan.type == CandidateType.D_DROP }
            eq(dd.status, CandStatus.EVALUATED, "도보로 평가")
            eq(dd.passengerArrivalEpochSec, TimeUtil.ceilToMinute(T0 + 1000 + 60) + 420, "도착 = 하차 + 도보 420초")
            check(prov.calls.any { it.startsWith("WALK") }, "보행자 경로 1회")
        },
        "순위: 동승자 도착(분) → 운전자 우회(분) → 환승 → 더 멀리 함께 감(2026-11-12 사용자 확정)" to {
            fun cand(id: String, drop: Long, walk: Int) = CandidateResult(
                PlannedCandidate(id, CandidateType.MIDDLE, O, id, (drop - T0).toDouble(), null, null, GtfsLink.DIRECT, 0, PrefilterState.QUERY_TARGET, id, 0.0),
                CandStatus.EVALUATED, "합성", dropCompleteEpochSec = drop, driverAddSec = 60, driverArrivalEpochSec = T0 + 1060,
                passengerArrivalEpochSec = T0 + 1500, totalWalkSec = walk)
            val near = cand("A-near", T0 + 300, 100); val far = cand("B-far", T0 + 800, 400)
            eq(listOf(near, far).sortedWith(DropComparison.comparator(MinuteRounding.ROUND)).first().plan.id, "B-far", "더 오래 함께 탄 쪽")
            val farLate = far.copy(passengerArrivalEpochSec = T0 + 1620)
            eq(listOf(near, farLate).sortedWith(DropComparison.comparator(MinuteRounding.ROUND)).first().plan.id, "A-near", "동승자 도착 분이 다르면 도착 우선")
            val farMoreTr = far.copy(transferCount = 2); val nearTr = near.copy(transferCount = 1)
            eq(listOf(nearTr, farMoreTr).sortedWith(DropComparison.comparator(MinuteRounding.ROUND)).first().plan.id, "A-near", "환승이 적은 쪽이 함께 탄 시간보다 먼저")
        },
        "하차점 앞뒤 50m 안에 자동차전용·교량이 있으면 하차 불가, 60m 밖이면 허용" to {
            // 0~1000m 직선, 10구간(100m씩). 구간 6(600~700m)만 자동차전용(roadType 1)
            val r0 = line(O, D, 1000, n = 10)
            val total = r0.totalDistanceM.toDouble()
            val segs = (0 until 10).map { if (it == 6) RoadSeg(1, 0) else RoadSeg(3, 0) }
            val r = r0.copy(segRoad = segs, cumDistM = (0..10).map { 1000.0 * it / 10 })
            eq(roadStopSuitabilityAround(r, 5, 0.6, Policy()), StopSuitability.NO_STOP, "560m: 40m 앞이 자동차전용")
            eq(roadStopSuitabilityAround(r, 5, 0.3, Policy()), StopSuitability.ROAD_OK, "530m: 70m 앞이라 허용")
            eq(roadStopSuitabilityAround(r, 7, 0.4, Policy()), StopSuitability.NO_STOP, "740m: 40m 뒤가 자동차전용")
            val rb = r.copy(segRoad = (0 until 10).map { if (it == 2) RoadSeg(3, 1) else RoadSeg(3, 0) })
            eq(roadStopSuitabilityAround(rb, 1, 0.8, Policy()), StopSuitability.NO_STOP, "180m: 20m 앞이 교량")
            val rc = r.copy(segRoad = (0 until 10).map { if (it == 2) RoadSeg(3, 5) else RoadSeg(3, 0) })
            eq(roadStopSuitabilityAround(rc, 1, 0.8, Policy()), StopSuitability.ROAD_OK, "교차로 통과 구간은 앞뒤 검사 대상 아님")
            check(total > 0, "합성")
        },
        "중간 하차는 경로 전체에 고르게 고른다(앞쪽 편향 제거)" to {
            val g = synthGtfs()
            val P = LatLng(37.5300, 127.0500)
            val base = line(O, D, 1000)
            val eng = DropMateEngine(g)
            val (plan, _) = eng.planCandidates(req(P), base)
            val sel = eng.selectForEvaluation(plan, base).filter { it.type == CandidateType.MIDDLE }
            val allMid = plan.filter { it.type == CandidateType.MIDDLE }
            check(sel.size == minOf(Policy().maxMiddleCandidates, allMid.size), "중간 후보 수: ${sel.size}")
            if (allMid.size > sel.size) {
                val maxOff = allMid.maxOf { it.routeOffsetSec ?: 0.0 }
                val firstN = allMid.take(sel.size).maxOf { it.routeOffsetSec ?: 0.0 }
                check(sel.maxOf { it.routeOffsetSec ?: 0.0 } >= firstN, "뒤쪽 구간 후보 포함")
                check(maxOff >= 0, "합성")
            }
            check(eng.selectForEvaluation(plan, base).any { it.type == CandidateType.D_DROP } && eng.selectForEvaluation(plan, base).any { it.type == CandidateType.P_VIA }, "D·P 항상 포함")
        },
        "판정 경계: 절약 179/180/0초" to {
            for ((sv, expect) in listOf(179L to Verdict.SIMILAR, 180L to Verdict.RECOMMEND, 0L to Verdict.NO_GAIN)) {
                val P = LatLng(37.45, 127.10)
                val dropD = T0 + 1000 + 60
                val arrD = TimeUtil.ceilToMinute(dropD) + 1200
                val soloTotal = (arrD + sv - T0).toInt()
                val prov = FakeProvider({ o, d, _ -> ProviderResult.Ok(line(o, d, if (o == O && d == D) 1000 else 99999)) },
                    { o, _, _ -> if (o == D) ProviderResult.Ok(listOf(itin(1200, 100))) else ProviderResult.Ok(listOf(itin(soloTotal, 100))) })
                val r = engineNoGtfs().recommend(req(P), prov, MemoryLedger(26))
                eq(r.verdict, expect, "saving=$sv"); eq(r.savingSec, sv, "saving")
            }
        },
        "단독 조회 실패 → INCOMPLETE.SOLO_MISSING (0 처리 금지)" to {
            val P = LatLng(37.45, 127.10)
            var n = 0
            val prov = FakeProvider({ o, d, _ -> ProviderResult.Ok(line(o, d, if (o == O && d == D) 1000 else 99999)) },
                { o, _, _ -> n++; if (o == O) ProviderResult.NoRoute("14", "기타") else ProviderResult.Ok(listOf(itin(1200, 100))) })
            val r = engineNoGtfs().recommend(req(P), prov, MemoryLedger(26))
            eq(r.verdict, Verdict.INCOMPLETE, "verdict"); check(r.savingSec == null, "절약시간 미계산"); check(r.best != null, "동승 경로는 표시")
        },
        "인증 오류 후 추가 호출 중단(재시도 없음)" to {
            val P = LatLng(37.45, 127.10)
            val prov = FakeProvider({ o, d, _ -> ProviderResult.Ok(line(o, d, 1000)) }, { _, _, _ -> ProviderResult.Fail(FailKind.AUTH, "401") })
            val r = engineNoGtfs().recommend(req(P), prov, MemoryLedger(26))
            eq(prov.calls.size, 2, "자동차 1 + 대중교통 1 후 중단"); eq(r.haltedBy, "AUTH", "halted")
            check(r.candidates.all { it.status != CandStatus.EVALUATED }, "평가 완료 후보 없음")
            eq(r.verdict, Verdict.INCOMPLETE, "verdict")
        },
        "로컬 예산 소진 시 호출 금지·미설정 시 요청당 상한만 적용" to {
            val P = LatLng(37.45, 127.10)
            val prov = FakeProvider({ o, d, _ -> ProviderResult.Ok(line(o, d, 1000)) }, { _, _, _ -> ProviderResult.Ok(listOf(itin(1200, 100))) })
            val led = MemoryLedger(3)
            engineNoGtfs().recommend(req(P), prov, led)
            eq(prov.calls.size, 3, "잔여 3회만 사용"); eq(led.localRemaining(), 0, "잔여")
            val prov2 = FakeProvider({ o, d, _ -> ProviderResult.Ok(line(o, d, 1000)) }, { _, _, _ -> ProviderResult.Ok(listOf(itin(1200, 100))) })
            val r2 = engineNoGtfs().recommend(req(P), prov2, MemoryLedger(null))
            check(prov2.calls.size in 1..Policy().maxCallsPerRequest, "예산 미설정 → 호출 가능, 요청당 상한 이내")
            check(r2.verdict != Verdict.INCOMPLETE || prov2.calls.size > 0, "예산 미설정만으로 불완전 처리하지 않음")
        },
        "요청당 호출 상한(11) 준수" to {
            val P = LatLng(37.45, 127.10)
            val prov = FakeProvider({ o, d, _ -> ProviderResult.Ok(line(o, d, 1000)) }, { _, _, _ -> ProviderResult.Ok(listOf(itin(1200, 100))) })
            val pol = Policy(maxCallsPerRequest = 2)
            val r = engineNoGtfs(pol).recommend(req(P), prov, MemoryLedger(26))
            eq(prov.calls.size, 2, "상한 2"); check(r.candidates.any { it.status == CandStatus.NOT_QUERIED_BUDGET || it.status == CandStatus.NOT_QUERIED_HALTED }, "미조회 표시")
        },
        "같은 요청 내 완전히 같은 호출 중복 방지" to {
            val prov = FakeProvider({ o, d, _ -> ProviderResult.Ok(line(o, d, 1000)) }, { _, _, _ -> ProviderResult.Ok(listOf(itin(1200, 100))) })
            val gw = CallGateway(prov, Policy(), MemoryLedger(26), "R")
            gw.transit("a", O, D, "202611050000"); gw.transit("b", O, D, "202611050000"); gw.transit("c", O, D, "202611050001")
            eq(prov.calls.size, 2, "다른 시각은 합치지 않음"); eq(gw.dedupSaved, 1, "절약")
        },
        "취소 후 호출 중단" to {
            var cancel = false
            val prov = FakeProvider({ o, d, _ -> cancel = true; ProviderResult.Ok(line(o, d, 1000)) }, { _, _, _ -> ProviderResult.Ok(listOf(itin(1200, 100))) })
            val r = engineNoGtfs().recommend(req(LatLng(37.45, 127.10)), prov, MemoryLedger(26), isCancelled = { cancel })
            eq(prov.calls.size, 1, "취소 이후 0회"); eq(r.haltedBy, "CANCELLED", "halted")
        },
        "실시간 검증에 과거 t0 금지" to {
            val prov = FakeProvider({ o, d, _ -> ProviderResult.Ok(line(o, d, 1000)) }, { _, _, _ -> ProviderResult.Ok(listOf(itin(1200, 100))) })
            val r = engineNoGtfs().recommend(RecommendRequest("R", O, D, LatLng(37.45, 127.1), T0, live = true), prov, MemoryLedger(26), nowEpochSec = T0 + 3600)
            eq(prov.calls.size, 0, "호출 없음"); eq(r.verdict, Verdict.INCOMPLETE, "verdict")
        },
        "TMAP_QUERY 모드: O→s 는 t0, s→D 는 하차 완료시각 출발" to {
            val g = synthGtfs()
            val P = LatLng(37.5300, 127.0500)
            val prov = FakeProvider({ o, d, t -> ProviderResult.Ok(line(o, d, if (o == O && d == D) 1000 else 400)) }, { _, _, _ -> ProviderResult.Ok(listOf(itin(900, 100))) })
            DropMateEngine(g, Policy(carTimeMode = CarTimeMode.TMAP_QUERY, maxCandidatesPerRequest = 3, maxMiddleCandidates = 1)).recommend(req(P), prov, MemoryLedger(26))
            val cars = prov.calls.filter { it.startsWith("CAR") }
            val os = cars.firstOrNull { it.startsWith("CAR $O>") && !it.contains(">$D@") }
            if (os != null) {
                check(os.endsWith("@$T0"), "O→s 출발 t0: $os")
                val sd = cars.first { it.contains(">$D@") && !it.startsWith("CAR $O>") }
                check(sd.endsWith("@${T0 + 400 + 60}"), "s→D 출발 = t0+400+60: $sd")
            }
        },
        "GTFS: 직통 없음·환승 연결 / 반대 방향 / transfers 없이 보행 환승 / 자료에 없는 정류장" to {
            val g = synthGtfs()
            val P = LatLng(37.5300, 127.0500)
            val reach = g.reachabilityTo(P, 300, 400)
            eq(reach.link(idx(g, "A1")).first, GtfsLink.DIRECT, "A1 → 직통")
            eq(reach.link(idx(g, "B1")), GtfsLink.TRANSFER to 1, "B1 → 보행 환승 1회 (transfers 파일 없음)")
            eq(reach.link(idx(g, "A1_opp")).first, GtfsLink.NOT_FOUND, "같은 이름 반대 방향은 직통 아님")
            eq(reach.link(idx(g, "NEW")).first, GtfsLink.NOT_FOUND, "자료에 노선 없는 정류장")
        },
        "후보 탐색: 연결 못 찾음은 판단 보류, 직선 601m는 탐색 반경 밖" to {
            val g = synthGtfs()
            val (plan, counts) = DropMateEngine(g).planCandidates(req(LatLng(37.5300, 127.0500)), line(O, D, 1000))
            check(plan.any { it.accessStop?.id == "NEW" && it.prefilter == PrefilterState.UNDETERMINED }, "NEW 는 판단 보류로 남음")
            check(plan.none { it.accessStop?.id == "FAR601" }, "직선 601m 정류장은 이 하차점 접근점에서 제외")
            check(plan.any { it.accessStop?.id == "NEAR599" }, "직선 599m 정류장은 유지")
            check(counts["outside_search_corridor"]!! >= 1, "제외 집계")
        },
        "여러 접근점에 호출 분산(같은 그룹은 대표 1개)" to {
            val g = synthGtfs()
            val (plan, _) = DropMateEngine(g).planCandidates(req(LatLng(37.5300, 127.0500)), line(O, D, 1000))
            val mids = plan.filter { it.type == CandidateType.MIDDLE }
            eq(mids.map { it.groupKey }.toSet().size, mids.size, "그룹당 1개")
        },
        "여섯 번째 이후 후보가 가장 빠름: 전수면 찾고, 상한 3이면 미조회로 표시" to {
            val g = synthGtfs()
            val P = LatLng(37.5300, 127.0500)
            val base = line(O, D, 1000)
            val (plan, _) = DropMateEngine(g, Policy(maxCandidatesPerRequest = 99, maxMiddleCandidates = 99)).planCandidates(req(P), base)
            check(plan.size >= 6, "후보 6개 이상 필요: ${plan.size}")
            val lastId = plan.last().dropPoint
            val prov = FakeProvider({ o, d, _ -> ProviderResult.Ok(if (o == O && d == D) base else line(o, d, 99999)) },
                { o, _, _ -> ProviderResult.Ok(listOf(itin(if (o == lastId) 60 else 3000, 100))) })
            val full = DropMateEngine(g, Policy(maxCandidatesPerRequest = 99, maxMiddleCandidates = 99, maxCallsPerRequest = 99)).recommend(req(P), prov, MemoryLedger(999))
            eq(full.best?.plan?.dropPoint, lastId, "전수 평가 시 마지막 후보가 최선")
            val prov3 = FakeProvider({ o, d, _ -> ProviderResult.Ok(if (o == O && d == D) base else line(o, d, 99999)) },
                { o, _, _ -> ProviderResult.Ok(listOf(itin(if (o == lastId) 60 else 3000, 100))) })
            val capped = DropMateEngine(g, Policy(maxCandidatesPerRequest = 3, maxMiddleCandidates = 1, maxCallsPerRequest = 11)).recommend(req(P), prov3, MemoryLedger(26))
            check(capped.best?.plan?.dropPoint != lastId, "상한 3에서는 찾지 못함")
            check(capped.candidates.count { it.status == CandStatus.NOT_QUERIED_BUDGET } >= plan.size - 3, "미조회 범위 기록")
            check(capped.bestScope.contains("미조회"), "범위 표시")
        },
        "사전 필터 안전성: 필터 전 전수 vs 필터 후 최선안 동일" to {
            val g = synthGtfs()
            val P = LatLng(37.5300, 127.0500)
            val base = line(O, D, 1000)
            // 가짜 공급자: 첫 보행 = 하차점→가장 가까운 정류장 직선거리×1.2 (보행망 ≥ 직선)
            fun prov() = FakeProvider({ o, d, _ -> ProviderResult.Ok(if (o == O && d == D) base else line(o, d, 99999)) }, { o, dd, _ ->
                val near = g.stops.minBy { Geo.haversineM(o, it.pos) }
                val w = (Geo.haversineM(o, near.pos) * 1.2).toInt()
                ProviderResult.Ok(listOf(itin((Geo.haversineM(o, dd) / 6).toInt() + 300, w))) })
            val pAll = Policy(maxCandidatesPerRequest = 999, maxMiddleCandidates = 999, maxCallsPerRequest = 999, corridorStraightLimitM = 5000)
            val pFil = Policy(maxCandidatesPerRequest = 999, maxMiddleCandidates = 999, maxCallsPerRequest = 999)
            val a = DropMateEngine(g, pAll).recommend(req(P), prov(), MemoryLedger(9999))
            val b = DropMateEngine(g, pFil).recommend(req(P), prov(), MemoryLedger(9999))
            eq(b.best?.passengerArrivalEpochSec, a.best?.passengerArrivalEpochSec, "최선 도착 동일")
        },
        "방식 B: 먼저 오는 일반보다 늦게 오는 급행이 먼저 도착" to {
            val c = TripConnector.earliest(0, listOf(Hop(180, listOf(Trip("local", "일반", 300, 2400), Trip("exp", "급행", 600, 1800)))), 60, 120)
            eq(c.chosen.single().id, "exp", "급행 선택"); eq(c.arrivalSec, 1920, "도착")
        },
        "방식 B: 탑승 준비 전에 떠난 편은 놓치고 다음 편" to {
            val c = TripConnector.earliest(100, listOf(Hop(180, listOf(Trip("t1", "a", 300, 900), Trip("t2", "a", 600, 1200)))), 60, 0)
            eq(c.chosen.single().id, "t2", "100+180+60=340 > 300 → t1 놓침")
        },
        "방식 B: 환승을 놓쳐 후보 순위 변경" to {
            val hop2 = Hop(240, listOf(Trip("x1", "B", 1000, 1500), Trip("x2", "B", 2000, 2500)))
            val candA = TripConnector.earliest(0, listOf(Hop(60, listOf(Trip("a", "A", 200, 700))), hop2), 60, 0)
            val candB = TripConnector.earliest(0, listOf(Hop(60, listOf(Trip("b", "A", 200, 720))), hop2), 60, 0)
            eq(candA.arrivalSec, 1500, "A: 700+240+60=1000 ≤ 1000 환승 성공"); eq(candB.arrivalSec, 2500, "B: 1020 > 1000 환승 실패")
        },
        "방식 B: 반대 방향·무정차·취소 차량 제외" to {
            val c = TripConnector.earliest(0, listOf(Hop(0, listOf(Trip("c", "x", 100, 200, TripState.CANCELLED), Trip("o", "x", 100, 200, TripState.OPPOSITE_DIRECTION),
                Trip("n", "x", 100, 200, TripState.NO_STOP), Trip("ok", "x", 500, 900)))), 0, 0)
            eq(c.chosen.single().id, "ok", "정상 편만")
        },
        "방식 B: 자정 이후(25:10:00)·휴일 운행일" to {
            eq(TripConnector.gtfsTimeToSec("25:10:00"), 90600, "24시 초과")
            val c = TripConnector.earliest(86000, listOf(Hop(60, listOf(Trip("late", "N", 90600, 92000, serviceDays = setOf("WEEKDAY")), Trip("hol", "N", 91000, 93000, serviceDays = setOf("HOLIDAY"))))), 60, 0, serviceDay = "HOLIDAY")
            eq(c.chosen.single().id, "hol", "휴일에는 평일 편 제외")
        },
        "방식 B: 입구→승강장 이동·탑승 여유 미확인이면 계산 불가(0·3분 대체 금지)" to {
            check(TripConnector.earliest(0, listOf(Hop(null, listOf(Trip("t", "a", 100, 200)))), 60, 0).arrivalSec == null, "역 내부 미확인")
            check(TripConnector.earliest(0, listOf(Hop(60, listOf(Trip("t", "a", 100, 200)))), null, 0).arrivalSec == null, "탑승 여유 미확정")
        },
        "방식 B: 같은 차량 계속 탑승은 환승으로 세지 않음" to {
            val c = TripConnector.earliest(0, listOf(Hop(0, listOf(Trip("v", "a", 100, 500))), Hop(null, listOf(Trip("v", "a", 500, 900)), sameVehicleAsPrevious = true)), 0, 0)
            eq(c.transfers, 0, "환승 0"); eq(c.arrivalSec, 900, "도착")
        },
        "시간 연결 예시 A 40분 / B 28분 (하차는 차량 시간에 포함, 중복 추가 없음)" to {
            val a = TripConnector.earliest(120, listOf(Hop(180, listOf(Trip("A", "a", 25 * 60, 40 * 60)))), 0, 0)
            val b = TripConnector.earliest(300, listOf(Hop(180, listOf(Trip("B", "b", 10 * 60, 28 * 60)))), 0, 0)
            eq(a.arrivalSec, 40 * 60, "A"); eq(b.arrivalSec, 28 * 60, "B"); check(b.arrivalSec!! < a.arrivalSec!!, "B 우선")
        },
        "지도·상세 안내의 요청ID 일치" to {
            val gate = LatestResultGate(); gate.begin("R2")
            val prov = FakeProvider({ o, d, _ -> ProviderResult.Ok(line(o, d, 1000)) }, { _, _, _ -> ProviderResult.Ok(listOf(itin(1200, 100))) })
            val old = engineNoGtfs().recommend(req(LatLng(37.45, 127.1), "R1"), prov, MemoryLedger(26))
            check(!gate.accept(old), "이전 요청 결과 거부")
        },
    )

    fun idx(g: GtfsIndex, id: String) = g.stops.first { it.id == id }.idx

    /**
     * 합성 GTFS: 경로 O(127.00)→D(127.10) 위도 37.50.
     * P = (37.53, 127.05) 근처 목표 정류장 TGT.
     * A 노선: A1(경로 옆) → TGT 직통. A_opp: 같은 이름 반대 방향(TGT → A1_opp).
     * B 노선: B1(경로 옆) → XFER (A2 근처 300m) / A 노선 A2 → TGT. transfers 없음.
     * NEW: 노선 없음(신규 노선 가정). FAR601/NEAR599: 경로에서 직선 601/599m.
     * 기타 C1..C6 : 다른 그룹 다수(여섯 번째 이후 후보 검사용), 각자 TGT 직통.
     */
    fun synthGtfs(): GtfsIndex {
        val dLat = 180.0 / (Math.PI * 6_371_008.8) // haversine 과 같은 구면 기준 1m 당 위도
        val s = mutableListOf<GtfsStop>()
        fun add(id: String, name: String, lat: Double, lon: Double) { s += GtfsStop(s.size, id, name, LatLng(lat, lon)) }
        add("TGT", "목표", 37.5300, 127.0500)
        add("A1", "에이", 37.5003, 127.0200)
        add("A1_opp", "에이", 37.4997, 127.0200)
        add("A2", "에이투", 37.5200, 127.0700)
        add("B1", "비", 37.5003, 127.0800)
        add("XFER", "환승점", 37.5200 + 300 * dLat, 127.0700)
        add("NEW", "신규", 37.5002, 127.0400)
        add("FAR601", "먼정류장", 37.5000 + 601 * dLat, 127.0600)
        add("NEAR599", "가까운정류장", 37.5000 - 599 * dLat, 127.0650)
        for (i in 1..6) add("C$i", "씨$i", 37.5001, 127.0050 + i * 0.012)
        val st = s.associateBy { it.id }
        fun pat(vararg ids: String) = GtfsPattern("0", "x", 1, ids.map { st[it]!!.idx }.toIntArray())
        val pats = mutableListOf(pat("A1", "A2", "TGT"), pat("TGT", "A1_opp"), pat("B1", "XFER"), pat("NEAR599", "TGT"), pat("FAR601", "TGT"))
        for (i in 1..6) pats += pat("C$i", "TGT")
        return GtfsIndex(s, pats, "synthetic")
    }

    fun runAll(): Pair<Int, List<String>> {
        val fails = mutableListOf<String>()
        for ((name, f) in checks) {
            try { f(); println("PASS  $name") } catch (e: Throwable) { println("FAIL  $name :: ${e.message}"); fails += "$name :: ${e.message}" }
        }
        println("---- ${checks.size - fails.size}/${checks.size} passed")
        return checks.size to fails
    }
}

fun main() {
    val (_, fails) = EngineChecks.runAll()
    if (fails.isNotEmpty()) kotlin.system.exitProcess(1)
}

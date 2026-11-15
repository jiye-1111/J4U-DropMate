package com.jiye.dropmate.engine

/**
 * 앱 안 길안내. TMAP 자동차 경로 응답의 Point 안내점(turnType·description·nextRoadName)을 그대로 쓴다.
 * 코드표 출처: TMAP 자동차 경로 안내 문서(tmapRouteDoc) 응답 turnType 표. 추가 API 호출 없음.
 */
data class TurnPoint(
    val pos: LatLng,
    /** 경로 시작부터 이 안내점까지 누적 거리(m) */
    val cumDistM: Double,
    val turnType: Int,
    val description: String?,
    val nextRoadName: String?,
    val name: String?,
)

enum class TurnArrow { STRAIGHT, LEFT, RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, SHARP_LEFT, SHARP_RIGHT, UTURN, WAYPOINT, ARRIVE, OTHER }

object TurnCodes {
    /** 회전 안내로 보여 줄 코드(안내없음·도곽점·출발지·시설물·횡단보도 등 제외) */
    fun guidable(t: Int): Boolean = t in 11..19 || t == 43 || t == 44 || t in 101..118 || t in 125..142 || t in 182..189 || t == 201 || t == 203

    fun label(t: Int): String = when (t) {
        11 -> "직진"; 12 -> "좌회전"; 13 -> "우회전"; 14 -> "U턴"; 15 -> "P턴"
        16 -> "8시 방향 좌회전"; 17 -> "10시 방향 좌회전"; 18 -> "2시 방향 우회전"; 19 -> "4시 방향 우회전"
        43 -> "오른쪽"; 44 -> "왼쪽"
        101 -> "오른쪽 고속도로 입구"; 102 -> "왼쪽 고속도로 입구"; 103 -> "전방 고속도로 입구"
        104 -> "오른쪽 고속도로 출구"; 105 -> "왼쪽 고속도로 출구"; 106 -> "전방 고속도로 출구"
        111 -> "오른쪽 도시고속도로 입구"; 112 -> "왼쪽 도시고속도로 입구"; 113 -> "전방 도시고속도로 입구"
        114 -> "오른쪽 도시고속도로 출구"; 115 -> "왼쪽 도시고속도로 출구"; 116 -> "전방 도시고속도로 출구"
        117 -> "오른쪽 방향"; 118 -> "왼쪽 방향"
        in 131..142 -> "${t - 130}시 방향"
        182 -> "왼쪽에 도착"; 183 -> "오른쪽에 도착"; 184 -> "경유지"
        in 185..189 -> "${t - 184}번째 경유지"
        201 -> "목적지 도착"; 203 -> "목적지 건너편"
        else -> "안내"
    }

    fun arrow(t: Int): TurnArrow = when (t) {
        11, 51, 103, 106, 113, 116, 142, 136 -> TurnArrow.STRAIGHT
        12, 44, 118, 102, 105, 112, 115, 139 -> TurnArrow.LEFT
        13, 43, 117, 101, 104, 111, 114, 133 -> TurnArrow.RIGHT
        17, 140, 141 -> TurnArrow.SLIGHT_LEFT
        18, 131, 132 -> TurnArrow.SLIGHT_RIGHT
        16, 137, 138 -> TurnArrow.SHARP_LEFT
        19, 134, 135 -> TurnArrow.SHARP_RIGHT
        14, 15 -> TurnArrow.UTURN
        in 184..189 -> TurnArrow.WAYPOINT
        182, 183, 201, 203 -> TurnArrow.ARRIVE
        else -> TurnArrow.OTHER
    }
}

/** 안내 중 다음 행동 하나 */
data class NavCue(val kind: TurnArrow, val label: String, val roadName: String?, val distanceM: Double, val isDrop: Boolean = false,
                  /** 경로 위 절대 위치(누적 m). 같은 안내를 두 번 말하지 않도록 식별에 쓴다 */
                  val atM: Double = 0.0)

data class NavState(
    /** 경로 위로 맞춘 현재 위치 */
    val snapped: LatLng,
    val traveledM: Double,
    val remainingM: Double,
    /** 남은 시간(초): TMAP 구간 시간 기준. 실시간 재탐색 없음 */
    val remainingSec: Double,
    val next: NavCue?,
    val after: NavCue?,
    /** 하차 지점까지 남은 거리. 이미 지났거나 없으면 null */
    val toDropM: Double?,
    val toDropSec: Double?,
    /** 경로선에서 벗어난 거리(m). 큰 값이면 경로 이탈 */
    val offRouteM: Double,
    val arrived: Boolean,
    /** 진행 방향(도, 북=0) */
    val bearing: Double,
)

/**
 * 안내 세션: 한 경로(차량 시작 → (하차점) → 운전자 목적지)와 하차 지점 누적 거리.
 * update(현재 위치)로 다음 회전·남은 거리·시간을 계산한다. 재탐색은 하지 않는다(이탈 시 표시만).
 */
class NavSession(val route: CarRoute, val dropCumDistM: Double?, val offRouteLimitM: Double = 60.0) {
    val totalM: Double = route.cumDistM.lastOrNull() ?: 0.0

    fun update(pos: LatLng): NavState {
        val pr = Geo.projectOnPolyline(pos, route.points)
        val i = pr.segIndex
        val traveled = route.cumDistM[i] + (route.cumDistM[i + 1] - route.cumDistM[i]) * pr.t
        return stateAt(traveled, pr.point, pr.distToLineM)
    }

    /** 경로 위 누적 거리 d 지점의 상태(모의 주행용) */
    fun stateAt(traveledIn: Double, snapped: LatLng = pointAt(traveledIn), offRoute: Double = 0.0): NavState {
        val traveled = traveledIn.coerceIn(0.0, totalM)
        val turns = route.turns.filter { TurnCodes.guidable(it.turnType) && it.cumDistM > traveled + 3.0 }
        val cues = ArrayList<Pair<Double, NavCue>>()
        turns.forEach { t -> cues += t.cumDistM to NavCue(TurnCodes.arrow(t.turnType), TurnCodes.label(t.turnType), t.nextRoadName?.takeIf { it.isNotBlank() } ?: t.name, t.cumDistM - traveled, atM = t.cumDistM) }
        dropCumDistM?.takeIf { it > traveled + 3.0 && it < totalM - 3.0 }?.let { d ->
            // 하차 지점과 겹치는(±30m) TMAP 경유지 안내점은 하차 지점 안내 하나로 합친다
            cues.removeAll { kotlin.math.abs(it.first - d) < 30.0 && it.second.kind == TurnArrow.WAYPOINT }
            cues += d to NavCue(TurnArrow.WAYPOINT, "★ 하차 지점", null, d - traveled, isDrop = true, atM = d)
        }
        if (turns.none { TurnCodes.arrow(it.turnType) == TurnArrow.ARRIVE } && totalM > traveled + 3.0)
            cues += totalM to NavCue(TurnArrow.ARRIVE, "목적지 도착", null, totalM - traveled, atM = totalM)
        cues.sortBy { it.first }
        val timeAt = { d: Double -> interp(route.cumDistM, route.cumTimeSec, d) }
        val total = route.cumTimeSec.lastOrNull() ?: 0.0
        val toDrop = dropCumDistM?.takeIf { it > traveled }
        return NavState(
            snapped = snapped, traveledM = traveled, remainingM = totalM - traveled,
            remainingSec = (total - timeAt(traveled)).coerceAtLeast(0.0),
            next = cues.getOrNull(0)?.second, after = cues.getOrNull(1)?.second,
            toDropM = toDrop?.let { it - traveled }, toDropSec = toDrop?.let { (timeAt(it) - timeAt(traveled)).coerceAtLeast(0.0) },
            offRouteM = offRoute, arrived = totalM - traveled < 20.0, bearing = bearingAt(traveled),
        )
    }

    fun offRoute(s: NavState) = s.offRouteM > offRouteLimitM

    /** 누적 거리 d 지점 좌표 */
    fun pointAt(d: Double): LatLng {
        val c = route.cumDistM
        if (c.isEmpty()) return route.points.first()
        val x = d.coerceIn(0.0, c.last())
        var k = c.indexOfFirst { it >= x }.coerceAtLeast(1)
        if (k >= c.size) k = c.size - 1
        val a = route.points[k - 1]; val b = route.points[k]
        val seg = c[k] - c[k - 1]
        val t = if (seg > 0) (x - c[k - 1]) / seg else 0.0
        return LatLng(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
    }

    /** 누적 시간 t(초) 지점의 누적 거리(모의 주행: 경로의 구간 속도대로 진행) */
    fun distAtTime(t: Double): Double = interp(route.cumTimeSec, route.cumDistM, t)

    private fun bearingAt(d: Double): Double {
        val a = pointAt(d); val b = pointAt((d + 25.0).coerceAtMost(totalM))
        if (a == b) return 0.0
        val y = Math.sin(Math.toRadians(b.lon - a.lon)) * Math.cos(Math.toRadians(b.lat))
        val x = Math.cos(Math.toRadians(a.lat)) * Math.sin(Math.toRadians(b.lat)) -
            Math.sin(Math.toRadians(a.lat)) * Math.cos(Math.toRadians(b.lat)) * Math.cos(Math.toRadians(b.lon - a.lon))
        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0
    }

    companion object {
        private fun interp(xs: List<Double>, ys: List<Double>, x: Double): Double {
            if (xs.isEmpty()) return 0.0
            if (x <= xs.first()) return ys.first()
            if (x >= xs.last()) return ys.last()
            val k = xs.indexOfFirst { it >= x }
            val x0 = xs[k - 1]; val x1 = xs[k]
            val t = if (x1 > x0) (x - x0) / (x1 - x0) else 0.0
            return ys[k - 1] + (ys[k] - ys[k - 1]) * t
        }

        /** 두 경로를 이어 붙인다(P 경유: O→P + P→D). 누적 거리·시간·안내점을 앞 경로 끝만큼 밀어 준다 */
        fun concat(a: CarRoute, b: CarRoute): CarRoute {
            val dOff = a.cumDistM.lastOrNull() ?: 0.0
            val tOff = a.cumTimeSec.lastOrNull() ?: 0.0
            val gap = RoadSeg(null, null)
            return CarRoute(a.totalTimeSec + b.totalTimeSec, a.totalDistanceM + b.totalDistanceM,
                a.points + b.points, a.cumTimeSec + b.cumTimeSec.map { it + tOff }, a.cumDistM + b.cumDistM.map { it + dOff },
                if (a.segRoad.isNotEmpty() || b.segRoad.isNotEmpty()) a.segRoad + listOf(gap) + b.segRoad else emptyList(),
                a.turns.filter { it.turnType != 201 } + b.turns.map { it.copy(cumDistM = it.cumDistM + dOff) },
                if (a.segTraffic.isNotEmpty() || b.segTraffic.isNotEmpty()) a.segTraffic + listOf(0) + b.segTraffic else emptyList())
        }

        /** 재탐색 등으로 새로 받은 경로에 하차 지점(있으면)을 투영해 세션을 만든다 */
        fun fromRoute(route: CarRoute, drop: LatLng?): NavSession {
            val d = drop?.let {
                val pr = Geo.projectOnPolyline(it, route.points)
                route.cumDistM[pr.segIndex] + (route.cumDistM[pr.segIndex + 1] - route.cumDistM[pr.segIndex]) * pr.t
            }
            return NavSession(route, d)
        }

        /**
         * 추천 결과로 안내 세션을 만든다.
         * - 중간 하차·D 하차: 이미 조회한 직행 경로(O→D) 그대로, 하차 지점은 직행 경로 위 투영 위치
         * - P 경유: O→P 와 P→D 를 이어 붙임
         */
        fun from(r: RecommendResult): NavSession? {
            val b = r.best ?: return null
            val base = r.baseline ?: return null
            return when (b.plan.type) {
                CandidateType.P_VIA -> {
                    val a = b.carToDrop ?: return null; val c = b.carAfterDrop ?: return null
                    NavSession(concat(a, c), a.cumDistM.lastOrNull())
                }
                CandidateType.D_DROP -> NavSession(base, null)
                CandidateType.MIDDLE -> {
                    val pr = Geo.projectOnPolyline(b.plan.dropPoint, base.points)
                    val d = base.cumDistM[pr.segIndex] + (base.cumDistM[pr.segIndex + 1] - base.cumDistM[pr.segIndex]) * pr.t
                    NavSession(base, d)
                }
            }
        }
    }
}


/**
 * 음성 안내 문장 결정(말하기 자체는 앱이 한다). 같은 안내점·같은 거리 단계는 한 번만 말한다.
 * 단계: 1km 전(700~1000m), 300m 전(150~300m), 직전(60m 이내). 하차 지점은 동승자 준비 문장을 붙인다.
 */
class VoiceGuide {
    private val said = HashSet<String>()

    fun next(st: NavState): String? {
        if (st.arrived) return once("arrive", "목적지에 도착했어요.")
        val n = st.next ?: return null
        val d = n.distanceM
        val what = when {
            n.isDrop -> "하차 지점입니다"
            n.kind == TurnArrow.ARRIVE -> "목적지입니다"
            else -> n.label + (n.roadName?.let { ", $it 방향입니다" } ?: "입니다")
        }
        val id = n.atM.toInt().toString()
        return when {
            n.isDrop && d in 400.0..550.0 -> once("$id:500", "곧 하차 지점입니다.")
            d <= 60 -> once("$id:0", if (n.isDrop) "곧 하차 지점입니다. 동승자는 내릴 준비를 해 주세요." else "곧 " + what)
            d in 150.0..300.0 -> once("$id:300", "300미터 앞에서 " + what)
            d in 700.0..1000.0 -> once("$id:1000", "1킬로미터 앞에서 " + what)
            else -> null
        }
    }

    /** 안내 시작 때 한 번(2026-11-14 추가 문장) */
    fun start(dropName: String?): String? = once("start", if (dropName != null) "${dropName}에서 동승자가 내려요. 안내를 시작합니다." else "안내를 시작합니다.")

    /** 하차 후 동승자 경로를 다시 계산했을 때 한 번 */
    fun refreshed(): String? = once("refreshed", "동승자 경로를 다시 계산했어요.")

    /** 하차 지점을 지났을 때 한 번 */
    fun dropReached(): String? = once("drop-reached", "하차 지점에 도착했어요.")

    fun reset() = said.clear()

    private fun once(key: String, text: String): String? = if (said.add(key)) text else null
}

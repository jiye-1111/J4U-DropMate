package com.jiye.dropmate.engine

/**
 * 방식 B(별도 시간표·실시간으로 운행편 연결) 계산기.
 * 주의: 현재 실데이터에 연결되어 있지 않다. 보유 GTFS(2025)는 calendar 가 단일 서비스(B1, 2017-01-01~2030-12-31, 전 요일)라
 * 운행일·휴일 구분을 검증할 수 없어 현재 탑승편 확정에 쓰지 않는다. 합성 데이터 검증용으로만 사용한다.
 *
 * 시각은 '서비스일 0시 기준 초'를 쓰며 24시 이후 운행(예: 25:10:00 = 90600초)을 그대로 표현한다.
 */
enum class TripState { OK, CANCELLED, NO_STOP, OPPOSITE_DIRECTION, ENDED }

data class Trip(
    val id: String,
    val route: String,
    /** 탑승 정류장 출발(=탑승 마감 기준) 시각, 서비스일 기준 초 */
    val boardDepartSec: Int,
    /** 하차 정류장 도착 시각 */
    val alightArriveSec: Int,
    val state: TripState = TripState.OK,
    /** 이 편이 서비스일 serviceDay 에 운행하는지 */
    val serviceDays: Set<String> = setOf("ALL"),
)

/** 한 구간: 직전 위치 → 탑승 위치 보행 후 이 구간 운행편 중 선택 */
data class Hop(val walkToBoardSec: Int?, val trips: List<Trip>, val sameVehicleAsPrevious: Boolean = false)

data class Connection(val arrivalSec: Int?, val chosen: List<Trip>, val reason: String, val transfers: Int)

object TripConnector {
    /**
     * readySec: 하차 완료 시각(서비스일 기준 초)
     * boardingMarginSec: 탑승 여유. 미확정(null)이면 계산 불가로 반환(0으로 채우지 않음)
     * finalWalkSec: 마지막 하차 정류장 → 목적지 보행
     * 각 구간에서 가장 먼저 오는 편이 아니라, 이후 구간까지 이어 최종 도착이 가장 이른 조합을 찾는다(전수, 구간·편 수가 작다는 전제).
     */
    fun earliest(readySec: Int, hops: List<Hop>, boardingMarginSec: Int?, finalWalkSec: Int?, serviceDay: String = "ALL"): Connection {
        if (boardingMarginSec == null) return Connection(null, emptyList(), "탑승 여유 미확정 → 계산 불가", 0)
        if (finalWalkSec == null) return Connection(null, emptyList(), "최종 보행 미확인 → 계산 불가", 0)
        if (hops.any { it.walkToBoardSec == null && !it.sameVehicleAsPrevious }) return Connection(null, emptyList(), "탑승 위치까지 보행(역 내부 이동 포함) 미확인 → 계산 불가", 0)
        var best: Connection? = null
        fun dfs(i: Int, t: Int, chosen: List<Trip>) {
            if (i == hops.size) {
                val arr = t + finalWalkSec
                val transfers = (hops.count { !it.sameVehicleAsPrevious } - 1).coerceAtLeast(0)
                if (best == null || arr < best!!.arrivalSec!!) best = Connection(arr, chosen, "OK", transfers)
                return
            }
            val h = hops[i]
            if (h.sameVehicleAsPrevious) {
                // 같은 차량 계속 탑승: 이전 편과 같은 id 의 다음 구간만 허용, 환승으로 세지 않음
                val prev = chosen.lastOrNull() ?: return
                h.trips.filter { it.id == prev.id && it.state == TripState.OK }.forEach { dfs(i + 1, it.alightArriveSec, chosen + it) }
                return
            }
            val readyAtStop = t + h.walkToBoardSec!!
            for (tr in h.trips) {
                if (tr.state != TripState.OK) continue
                if ("ALL" !in tr.serviceDays && serviceDay !in tr.serviceDays) continue
                if (readyAtStop + boardingMarginSec > tr.boardDepartSec) continue // 놓친 편
                dfs(i + 1, tr.alightArriveSec, chosen + tr)
            }
        }
        dfs(0, readySec, emptyList())
        return best ?: Connection(null, emptyList(), "탑승 가능한 편 없음", 0)
    }

    /** GTFS "HH:MM:SS"(24 초과 허용) → 서비스일 기준 초 */
    fun gtfsTimeToSec(s: String): Int {
        val p = s.trim().split(':').map { it.toInt() }
        return p[0] * 3600 + p[1] * 60 + p[2]
    }
}

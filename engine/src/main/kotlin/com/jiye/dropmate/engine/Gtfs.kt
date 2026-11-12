package com.jiye.dropmate.engine

import java.io.BufferedReader
import kotlin.math.cos
import kotlin.math.floor

data class GtfsStop(val idx: Int, val id: String, val name: String, val pos: LatLng) {
    val isRail: Boolean get() = id.startsWith("RS_")
}

data class GtfsPattern(val routeType: String, val shortName: String, val tripCount: Int, val stops: IntArray)

/**
 * 정적 GTFS 인덱스. 정류장 연결 가능성(사전 필터·조회 우선순위) 계산 전용.
 * 2025년 기준 자료이며 현재 운행을 확인한 자료가 아니므로, '연결 못 찾음'은 확정 제외가 아니라 판단 보류로만 쓴다.
 * GTFS 시간을 TMAP 결과에 더하거나 대체하지 않는다.
 */
class GtfsIndex(val stops: List<GtfsStop>, val patterns: List<GtfsPattern>, val sourceNote: String) {
    private val cell = 0.005 // 약 550m(위도)
    private val grid = HashMap<Long, MutableList<Int>>()
    private val byStop: Array<MutableList<Pair<Int, Int>>> = Array(stops.size) { mutableListOf() } // (patternIdx, pos)

    init {
        for (s in stops) grid.getOrPut(key(s.pos.lat, s.pos.lon)) { mutableListOf() }.add(s.idx)
        patterns.forEachIndexed { pi, p -> p.stops.forEachIndexed { pos, si -> if (si in stops.indices) byStop[si].add(pi to pos) } }
    }

    private fun key(lat: Double, lon: Double): Long {
        val a = floor(lat / cell).toLong(); val b = floor(lon / cell).toLong()
        return (a shl 32) xor (b and 0xffffffffL)
    }

    fun within(p: LatLng, radiusM: Double): List<GtfsStop> {
        val dLat = radiusM / 110_574.0
        val dLon = radiusM / (111_320.0 * cos(Math.toRadians(p.lat)))
        val out = ArrayList<GtfsStop>()
        var la = floor((p.lat - dLat) / cell)
        while (la <= floor((p.lat + dLat) / cell)) {
            var lo = floor((p.lon - dLon) / cell)
            while (lo <= floor((p.lon + dLon) / cell)) {
                val k = (la.toLong() shl 32) xor (lo.toLong() and 0xffffffffL)
                grid[k]?.forEach { i -> if (Geo.haversineM(p, stops[i].pos) <= radiusM) out.add(stops[i]) }
                lo += 1.0
            }
            la += 1.0
        }
        return out
    }

    fun patternsAt(stopIdx: Int): List<Pair<Int, Int>> = byStop[stopIdx]

    /**
     * 목적지 P 방향 연결 가능성을 한 번 계산해 모든 후보가 공유한다.
     * 결과: 정류장 idx → 필요한 최소 환승 수(0 = 직통). 없으면 연결 못 찾음.
     * 환승 횟수 상한 없음(고정점까지 반복). 환승은 transfers 파일이 아니라 보행 근접 탐색으로 찾는다.
     * 정차 순서를 따르므로 같은 이름의 반대 방향 정류장은 직통으로 잡히지 않는다.
     */
    fun reachabilityTo(p: LatLng, targetProbeM: Int, transferProbeM: Int): Reachability {
        val level = HashMap<Int, Int>()
        val targets = within(p, targetProbeM.toDouble()).map { it.idx }.toSet()
        var frontier: Set<Int> = targets
        val usedAsAlight = HashSet<Int>(targets)
        val done = IntArray(patterns.size) // 패턴별로 이미 표시한 위치(배타적 상한)
        var k = 0
        // frontier = 하차 정류장 집합. 그 이전 정차 정류장이 level k 의 탑승 가능 정류장
        while (frontier.isNotEmpty()) {
            val newly = HashSet<Int>()
            for (t in frontier) for ((pi, pos) in byStop[t]) {
                val seq = patterns[pi].stops
                if (pos <= done[pi]) continue
                val from = done[pi]; done[pi] = pos
                for (i in from until pos) {
                    val s = seq[i]
                    if (s !in level) { level[s] = k; newly.add(s) }
                }
            }
            // 다음 단계: 새로 탑승 가능해진 정류장(자기 자신 포함) 근처 보행 환승 범위에서 내리는 정류장
            val next = HashSet<Int>()
            for (s in newly) for (n in within(stops[s].pos, transferProbeM.toDouble())) {
                if (usedAsAlight.add(n.idx)) next.add(n.idx)
            }
            frontier = next
            k++
            if (k > 50) break // 안전장치(데이터 순환 방지). 상한 도달 시 이후는 '연결 못 찾음=판단 보류'
        }
        return Reachability(level, targets)
    }

    class Reachability(val levelByStop: Map<Int, Int>, val targetStops: Set<Int>) {
        fun link(stopIdx: Int): Pair<GtfsLink, Int?> {
            val l = levelByStop[stopIdx] ?: return GtfsLink.NOT_FOUND to null
            return (if (l == 0) GtfsLink.DIRECT else GtfsLink.TRANSFER) to l
        }
    }

    companion object {
        fun load(stopsReader: BufferedReader, patternsReader: BufferedReader): GtfsIndex {
            val stops = ArrayList<GtfsStop>()
            var note = ""
            stopsReader.forEachLine { line ->
                if (line.startsWith("#")) { note = line.removePrefix("#"); return@forEachLine }
                if (line.isBlank()) return@forEachLine
                val c = line.split('\t')
                stops.add(GtfsStop(stops.size, c[0], c[1], LatLng(c[2].toDouble(), c[3].toDouble())))
            }
            val pats = ArrayList<GtfsPattern>()
            patternsReader.forEachLine { line ->
                if (line.startsWith("#") || line.isBlank()) return@forEachLine
                val c = line.split('\t')
                pats.add(GtfsPattern(c[0], c[1], c[2].toInt(), c[3].split(',').map { it.toInt() }.toIntArray()))
            }
            return GtfsIndex(stops, pats, note)
        }
    }
}

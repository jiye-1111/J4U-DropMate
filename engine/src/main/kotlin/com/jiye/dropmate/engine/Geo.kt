package com.jiye.dropmate.engine

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.*

object Geo {
    /** a → b 방위각(도, 북쪽 0·시계방향) */
    fun bearingDeg(a: LatLng, b: LatLng): Double {
        val y = Math.sin(Math.toRadians(b.lon - a.lon)) * Math.cos(Math.toRadians(b.lat))
        val x = Math.cos(Math.toRadians(a.lat)) * Math.sin(Math.toRadians(b.lat)) - Math.sin(Math.toRadians(a.lat)) * Math.cos(Math.toRadians(b.lat)) * Math.cos(Math.toRadians(b.lon - a.lon))
        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0
    }

    private const val R = 6_371_008.8

    fun haversineM(a: LatLng, b: LatLng): Double {
        val p1 = Math.toRadians(a.lat); val p2 = Math.toRadians(b.lat)
        val dp = p2 - p1; val dl = Math.toRadians(b.lon - a.lon)
        val h = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * R * asin(min(1.0, sqrt(h)))
    }

    data class Projection(val point: LatLng, val segIndex: Int, val t: Double, val distToLineM: Double)

    /** 점 p 를 폴리라인에 투영 (국소 평면 근사, 수 km 범위에서 오차 미미). */
    fun projectOnPolyline(p: LatLng, line: List<LatLng>): Projection {
        require(line.size >= 2)
        var best: Projection? = null
        val kx = cos(Math.toRadians(p.lat)) * 111_320.0
        val ky = 110_574.0
        for (i in 0 until line.size - 1) {
            val a = line[i]; val b = line[i + 1]
            val ax = (a.lon - p.lon) * kx; val ay = (a.lat - p.lat) * ky
            val bx = (b.lon - p.lon) * kx; val by = (b.lat - p.lat) * ky
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
            val q = LatLng(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
            val d = haversineM(p, q)
            if (best == null || d < best.distToLineM) best = Projection(q, i, t, d)
        }
        return best!!
    }
}

object TimeUtil {
    val KST: ZoneId = ZoneId.of("Asia/Seoul")
    private val DTTM = DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(KST)
    private val HMS = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(KST)

    /** searchDttm 정렬: 분 단위 올림. 이미 :00초면 그대로. 정렬 차이는 0~59초 */
    fun ceilToMinute(epochSec: Long): Long = if (epochSec % 60 == 0L) epochSec else (epochSec / 60 + 1) * 60
    fun alignDiffSec(epochSec: Long): Int = (ceilToMinute(epochSec) - epochSec).toInt()
    fun searchDttm(epochSec: Long): String = DTTM.format(Instant.ofEpochSecond(ceilToMinute(epochSec)))
    fun hms(epochSec: Long): String = HMS.format(Instant.ofEpochSecond(epochSec))

    /** 순위용 분 변환 (이득 판정에는 사용하지 않음) */
    fun rankMinute(epochSec: Long, mode: MinuteRounding): Long = when (mode) {
        MinuteRounding.FLOOR -> Math.floorDiv(epochSec, 60L)
        MinuteRounding.CEIL -> Math.floorDiv(epochSec + 59, 60L)
        MinuteRounding.ROUND -> Math.floorDiv(epochSec + 30, 60L)
    }
}

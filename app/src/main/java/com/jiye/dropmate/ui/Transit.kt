package com.jiye.dropmate.ui

import com.jiye.dropmate.engine.LegMode
import com.jiye.dropmate.engine.TransitLeg

/** 운전자 경로선 색(동승자 선과 항상 구분되는 흰색). */
const val DRIVER_LINE_ARGB: Int = 0xFFFFFFFF.toInt()

/** 도보 구간 색(점선으로 그린다). */
const val WALK_LINE_ARGB: Int = 0xFFFFC857.toInt()

private val SUBWAY_COLORS: List<Pair<String, Long>> = listOf(
    "신분당" to 0xFFD4003B, "경의중앙" to 0xFF77C4A3, "공항철도" to 0xFF0090D2, "수인분당" to 0xFFF5A200,
    "경춘" to 0xFF0C8E72, "우이신설" to 0xFFB0CE18, "신림" to 0xFF6789CA, "김포" to 0xFFA17800,
    "의정부" to 0xFFFDA600, "에버라인" to 0xFF56AD2D, "경강" to 0xFF003DA5, "서해" to 0xFF81A914, "GTX-A" to 0xFF9A6292,
    "1호선" to 0xFF0052A4, "2호선" to 0xFF00A84D, "3호선" to 0xFFEF7C1C, "4호선" to 0xFF00A5DE, "5호선" to 0xFF996CAC,
    "6호선" to 0xFFCD7C2F, "7호선" to 0xFF747F00, "8호선" to 0xFFE6186C, "9호선" to 0xFFBDB092,
)

/**
 * 노선 고유색. TMAP 이 준 routeColor 를 우선 사용하고, 없으면 지하철 호선·버스 종류의 공식색 표로 대체한다.
 * 어떤 경우에도 운전자 선(흰색)·도보(회색 점선)와 겹치지 않는다.
 */
fun legArgb(l: TransitLeg): Int {
    if (l.mode == LegMode.WALK) return WALK_LINE_ARGB
    val hex = l.routeColor?.removePrefix("#")?.takeIf { it.length == 6 && it.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' } }
    if (hex != null) return (0xFF000000L or hex.toLong(16)).toInt()
    val name = l.route ?: ""
    return when (l.mode) {
        LegMode.SUBWAY, LegMode.TRAIN ->
            (SUBWAY_COLORS.firstOrNull { name.contains(it.first) }?.second ?: 0xFF8E9AA6).toInt()
        LegMode.EXPRESSBUS -> 0xFFE60012.toInt()
        LegMode.BUS -> when {
            name.contains("광역") || name.startsWith("M") -> 0xFFE60012
            name.contains("순환") -> 0xFFF2B70A
            name.contains("지선") -> 0xFF5BB025
            name.contains("마을") -> 0xFF53B332
            else -> 0xFF3D5BAB // 간선(파랑)
        }.toInt()
        else -> 0xFF8E9AA6.toInt()
    }
}

fun legKind(m: LegMode): String = when (m) {
    LegMode.WALK -> "도보"; LegMode.BUS -> "버스"; LegMode.EXPRESSBUS -> "광역버스"
    LegMode.SUBWAY -> "지하철"; LegMode.TRAIN -> "철도"; LegMode.AIRPLANE -> "항공"; LegMode.FERRY -> "선박"; LegMode.OTHER -> "기타"
}

fun minText(sec: Int?): String = when {
    sec == null -> "시간 미확인"
    sec < 30 -> "1분 미만"
    else -> "${(sec + 30) / 60}분"
}

/** "도보 11분", "지하철 수도권2호선 14분", "버스 7013B 8분" */
fun legLabel(l: TransitLeg): String =
    if (l.mode == LegMode.WALK) "도보 ${minText(l.sectionTimeSec)}"
    else "${legKind(l.mode)} ${l.route ?: "노선 미확인"} ${minText(l.sectionTimeSec)}"

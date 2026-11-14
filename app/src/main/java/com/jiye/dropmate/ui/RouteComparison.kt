package com.jiye.dropmate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiye.dropmate.engine.*

private val Muted = Color(0xFFA5B2C2)

/** 동일 출발시각의 실제 결과만 표시한다. 참조 이미지의 예시 수치는 사용하지 않는다. */
@Composable
fun RouteComparison(r: RecommendResult, routeKind: RouteOptionKind = RouteOptionKind.FASTEST,
                    onRouteKind: (RouteOptionKind) -> Unit = {}) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("같은 출발 조건으로 경로 비교", fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Text("동승자 도착 우선 · 운전자 추가 직행의 30%(최대 15분) 이내 · 접근 도보 10분 이내",
            color = Muted, fontSize = 15.sp)
        CompareCard("A · 동승자 혼자 대중교통",
            r.solo?.arrivalEpochSec?.let { minText((it - r.t0EpochSec).toInt()) } ?: "미확인",
            r.solo?.reason ?: "조회되지 않음", false,
            r.solo?.chosen?.itinerary?.legs.orEmpty(), true)
        val b = r.best
        CompareCard("B · 함께 타고 하차 후 이동",
            b?.passengerArrivalEpochSec?.let { minText((it - r.t0EpochSec).toInt()) } ?: "미확인",
            b?.let { "★ ${it.plan.label} · 도보 ${minText(it.totalWalkSec)} · 환승 ${it.transferCount ?: "?"}회" }
                ?: "조건을 만족하는 선택 경로 없음",
            b != null, b?.chosen?.itinerary?.legs.orEmpty(), b?.chosen != null)
        b?.routeOptions?.takeIf { it.isNotEmpty() }?.let { PassengerRouteTabs(r.t0EpochSec, it, routeKind, onRouteKind) }
        CompareCard("C · 운전자 혼자 직행", minText(r.baseline?.totalTimeSec),
            "출발지 → 운전자 목적지", false, emptyList(), false)
        CompareCard("D · 동승 후 운전자 목적지",
            b?.driverArrivalEpochSec?.let { minText((it - r.t0EpochSec).toInt()) } ?: "미확인",
            b?.driverAddSec?.let { "직행 대비 추가 ${it}초" } ?: "추가시간 미확인",
            false, emptyList(), false)
        if (r.candidates.isNotEmpty()) DropPointComparison(r)
        if (r.best != null) NaviHandoffCard(r)
        r.savingSec?.let {
            Text(if (it > 0) "동승자 단독 이동보다 ${it}초 빠름" else "단독 이동 대비 단축 없음",
                color = Color(0xFF76B9FF), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Text("TMAP 응답 기준 추정 · 실시간 대중교통 대기는 아직 검증되지 않았습니다.",
            fontSize = 14.sp, color = Muted)
    }
}

/**
 * 같은 하차점(★)에서 내린 뒤 동승자 경로를 최소시간·최소환승·최소도보 탭으로 바꿔 본다(네이버 지도 방식).
 * 같은 여정이 여러 기준에 해당하면 탭은 같은 경로를 보여 주고 병합 배지로 알린다.
 * 하차점 순위·★·추천 판정은 계속 최소시간(빠른 도착) 기준이며, 탭은 보기만 바꾼다.
 */
@Composable
private fun PassengerRouteTabs(t0: Long, options: List<RouteOption>, selected: RouteOptionKind, onSelect: (RouteOptionKind) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF1C232D))
        .border(1.dp, Color(0xFF303B49), RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("하차 후 동승자 경로", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RouteOptionKind.entries.forEach { k ->
                val o = RouteOptions.pick(options, k)
                val on = k == selected && o != null
                Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .background(if (on) Color(0xFF293F5C) else Color(0xFF222A35))
                    .border(1.dp, if (on) kindColor(k) else Color(0xFF303B49), RoundedCornerShape(10.dp))
                    .then(if (o != null) Modifier.clickable { onSelect(k) } else Modifier)
                    .padding(horizontal = 8.dp, vertical = 6.dp)) {
                    Text(k.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        color = if (o == null) Muted else if (on) kindColor(k) else Color(0xFFF0F4FA))
                    Text(o?.itinerary?.arrivalEpochSec?.let { minText((it - t0).toInt()) } ?: "정보 없음",
                        fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (o == null) Muted else Color(0xFFF0F4FA))
                    o?.itinerary?.itinerary?.let { i ->
                        Text("환승 ${i.transferCount?.let { "${it}회" } ?: "?"} · 도보 ${minText(i.totalWalkTimeSec)}", fontSize = 13.sp, color = Muted)
                    }
                }
            }
        }
        RouteOptions.pick(options, selected)?.let { o ->
            val it = o.itinerary.itinerary
            if (o.kinds.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                o.kinds.forEach { k -> OptionBadge(k) }
                Text("같은 경로", fontSize = 14.sp, color = Muted)
            }
            Row(Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                it.legs.forEach { leg ->
                    Box(Modifier.weight((leg.sectionTimeSec ?: 1).coerceAtLeast(1).toFloat()).fillMaxHeight().background(Color(legArgb(leg))))
                }
            }
            it.legs.forEach { l -> Text("· " + legLabel(l), fontSize = 14.sp, color = Muted) }
        }
        Text("★ 하차점 순위·추천은 최소시간 기준 · 탭은 경로 보기만 바꿉니다", fontSize = 13.sp, color = Muted)
    }
}

private fun kindColor(k: RouteOptionKind) = when (k) {
    RouteOptionKind.FASTEST -> Color(0xFF5AA9FF)
    RouteOptionKind.FEWEST_TRANSFERS -> Color(0xFF7BD389)
    RouteOptionKind.LEAST_WALK -> Color(0xFFFFC857)
}

/** 평가한 하차점 전체 비교. 추가 호출 없이 이미 계산된 후보만 보여 준다. */
@Composable
private fun DropPointComparison(r: RecommendResult) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF1C232D))
        .border(1.dp, Color(0xFF303B49), RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("하차점 비교", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        r.dropRanking().forEach { d ->
            val c = d.candidate
            val isBest = r.best?.plan?.id == c.plan.id
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(d.rank?.let { if (isBest) "★ 1" else "$it" } ?: "–", fontWeight = FontWeight.Bold, fontSize = 16.sp,
                        color = if (isBest) Color(0xFF79BAFF) else Color(0xFFF0F4FA))
                    Text(c.plan.label, Modifier.weight(1f), fontSize = 16.sp,
                        fontWeight = if (isBest) FontWeight.SemiBold else FontWeight.Normal)
                    Text(c.passengerArrivalEpochSec?.let { minText((it - r.t0EpochSec).toInt()) } ?: "—",
                        fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                val detail = if (d.rank != null) listOfNotNull(
                    d.gapToBestSec?.takeIf { it > 0 }?.let { "1위보다 ${it}초 늦음" },
                    c.driverAddSec?.let { "운전자 +${it}초" },
                    "도보 ${minText(c.totalWalkSec)}",
                    "환승 ${c.transferCount?.let { "${it}회" } ?: "미확인"}",
                ).joinToString(" · ") else "${statusText(c.status)} · ${c.reason}"
                Text(detail, fontSize = 14.sp, color = Muted)
                Text("하차 지점: " + stopSuitabilityText(c.plan.stopSuitability), fontSize = 13.sp,
                    color = if (c.plan.stopSuitability == StopSuitability.UNVERIFIED) Color(0xFFFFB74D) else Muted)
            }
        }
        Text("터널·고속도로 등 하차 불가 도로는 후보에서 제외 · 평가 후보 최대 ${Policy().maxCandidatesPerRequest}개(경로 중간·운전자 목적지·동승자 입구), 전역 최적 아님",
            fontSize = 13.sp, color = Color(0xFFFFB74D))
    }
}

private fun statusText(s: CandStatus) = when (s) {
    CandStatus.EVALUATED -> "평가 완료"
    CandStatus.EXCLUDED_DETOUR -> "제외(운전자 추가시간 초과)"
    CandStatus.EXCLUDED_ACCESS_WALK -> "제외(접근 보행 초과)"
    CandStatus.ACCESS_WALK_UNKNOWN -> "보류(접근 보행 누락)"
    CandStatus.WALK_DIRECT_UNRATED -> "보류(도보 직행 한도 미확정)"
    CandStatus.NO_ROUTE -> "경로 없음"
    CandStatus.FAILED -> "조회 실패"
    CandStatus.NOT_QUERIED_BUDGET -> "미조회(후보·호출 한도)"
    CandStatus.NOT_QUERIED_HALTED -> "미조회(호출 중단)"
}

@Composable
private fun OptionBadge(k: RouteOptionKind) {
    val c = kindColor(k)
    Text(k.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF10161E),
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(c).padding(horizontal = 7.dp, vertical = 2.dp))
}

@Composable
private fun CompareCard(title: String, time: String, detail: String, selected: Boolean,
                        legs: List<TransitLeg>, transitEstimate: Boolean) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
        .background(if (selected) Color(0xFF293F5C) else Color(0xFF222A35))
        .border(1.dp, if (selected) Color(0xFF5AA9FF) else Color(0xFF303B49), RoundedCornerShape(14.dp))
        .padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold,
                color = if (selected) Color(0xFF79BAFF) else Color(0xFFF0F4FA), fontSize = 17.sp)
            Text(time, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        if (transitEstimate) Text("예상 소요 · 실시간 대기 미검증", fontSize = 13.sp, color = Muted)
        Text(detail, fontSize = 15.sp, color = Muted)
        if (legs.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)),
                horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                legs.forEach { leg ->
                    Box(Modifier.weight((leg.sectionTimeSec ?: 1).coerceAtLeast(1).toFloat())
                        .fillMaxHeight().background(Color(legArgb(leg))))
                }
            }
            Text(legs.joinToString(" → ") {
                if (it.mode == LegMode.WALK) "도보" else "${legKind(it.mode)} ${routeName(it, "노선 미확인")}"
            }, fontSize = 14.sp, color = Muted)
        }
    }
}

package com.jiye.dropmate.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiye.dropmate.engine.*
import java.text.SimpleDateFormat
import java.util.*

/** 지도에 그릴 안내 상태: 지난 경로·남은 경로·차 위치·진행 방향 */
data class NavView(val passed: List<LatLng>, val ahead: List<LatLng>, val car: LatLng, val bearing: Double, val drop: LatLng?, val dest: LatLng?,
                   /** true: 진행 방향이 위(헤딩업) + 화면 기울임(원근 3D 보기). 입체 건물은 없음 */
                   val tilt: Boolean = false, val lookAhead: LatLng? = null,
                   /** 남은 경로를 혼잡도 색으로 나눈 조각(TMAP traffic). 비어 있으면 한 색 */
                   val aheadTraffic: List<Pair<List<LatLng>, Int>> = emptyList())

/** TMAP 혼잡도 → 선 색(1 원활 초록, 2 서행 주황, 3·4 지체·정체 빨강, 0 정보없음 파랑) */
fun trafficArgb(c: Int): Int = when (c) { 1 -> 0xFF2DB55D.toInt(); 2 -> 0xFFF5A623.toInt(); 3, 4 -> 0xFFE53935.toInt(); else -> 0xFF1F6FD1.toInt() }

val LocalNavView = androidx.compose.runtime.staticCompositionLocalOf<NavView?> { null }

fun navView(s: NavSession, st: NavState, drop: LatLng?, tilt: Boolean = false): NavView {
    val c = s.route.cumDistM
    val k = c.indexOfFirst { it > st.traveledM }.let { if (it < 0) c.size else it }
    val passed = s.route.points.take(k) + st.snapped
    val ahead = listOf(st.snapped) + s.route.points.drop(k)
    // 3D 보기: 차가 화면 아래쪽에 오도록 진행 방향 앞 지점을 지도 중심으로 쓴다
    val ahead2 = if (tilt) s.pointAt(st.traveledM + 160.0) else null
    // 남은 경로 혼잡도 조각: ahead[j]→ahead[j+1] 은 경로 구간 (k-1+j)
    val tr = s.route.segTraffic
    val pieces = ArrayList<Pair<List<LatLng>, Int>>()
    if (tr.isNotEmpty() && ahead.size >= 2) {
        var cur = ArrayList<LatLng>(); var curC = -1
        for (j in 0 until ahead.size - 1) {
            val c = tr.getOrElse(k - 1 + j) { 0 }
            if (c != curC) { if (cur.size >= 2) pieces += cur to curC; cur = arrayListOf(ahead[j]); curC = c }
            cur += ahead[j + 1]
        }
        if (cur.size >= 2) pieces += cur to curC
    }
    return NavView(passed, ahead, st.snapped, st.bearing, drop, s.route.points.lastOrNull(), tilt, ahead2, pieces)
}

fun distText(m: Double): String = when {
    m < 1000 -> "${(Math.round(m / 10.0) * 10).toInt()}m"
    else -> String.format(Locale.KOREA, "%.1fkm", m / 1000.0)
}

/** 회전 방향 화살표(앱 고유 그림). TMAP 아이콘을 복제하지 않는다 */
@Composable
fun TurnIcon(kind: TurnArrow, size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val sw = w * 0.13f
        val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (kind) {
            TurnArrow.ARRIVE -> {
                drawCircle(color, radius = w * 0.30f, center = Offset(w / 2, h * 0.42f), style = stroke)
                drawCircle(color, radius = w * 0.09f, center = Offset(w / 2, h * 0.42f))
                drawLine(color, Offset(w / 2, h * 0.72f), Offset(w / 2, h * 0.92f), sw, StrokeCap.Round)
            }
            TurnArrow.WAYPOINT -> {
                val p = Path(); val cx = w / 2; val cy = h / 2; val R = w * 0.42f; val r = w * 0.18f
                for (i in 0 until 10) {
                    val a = Math.toRadians(-90.0 + i * 36.0); val rr = if (i % 2 == 0) R else r
                    val x = cx + (rr * Math.cos(a)).toFloat(); val y = cy + (rr * Math.sin(a)).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close(); drawPath(p, color)
            }
            TurnArrow.UTURN -> {
                val p = Path()
                p.moveTo(w * 0.65f, h * 0.9f); p.lineTo(w * 0.65f, h * 0.38f)
                p.cubicTo(w * 0.65f, h * 0.12f, w * 0.30f, h * 0.12f, w * 0.30f, h * 0.38f); p.lineTo(w * 0.30f, h * 0.62f)
                drawPath(p, color, style = stroke)
                val head = Path(); head.moveTo(w * 0.12f, h * 0.55f); head.lineTo(w * 0.48f, h * 0.55f); head.lineTo(w * 0.30f, h * 0.80f); head.close()
                drawPath(head, color)
            }
            else -> {
                val angle = when (kind) {
                    TurnArrow.LEFT -> -90f; TurnArrow.RIGHT -> 90f; TurnArrow.SLIGHT_LEFT -> -45f; TurnArrow.SLIGHT_RIGHT -> 45f
                    TurnArrow.SHARP_LEFT -> -135f; TurnArrow.SHARP_RIGHT -> 135f; else -> 0f
                }
                val base = Offset(w * 0.5f, h * 0.92f); val joint = Offset(w * 0.5f, h * 0.52f)
                drawLine(color, base, joint, sw, StrokeCap.Round)
                rotate(angle, pivot = joint) {
                    val tip = Offset(joint.x, h * 0.12f)
                    drawLine(color, joint, Offset(tip.x, tip.y + h * 0.12f), sw, StrokeCap.Round)
                    val head = Path(); head.moveTo(tip.x, tip.y); head.lineTo(tip.x - w * 0.2f, tip.y + h * 0.22f); head.lineTo(tip.x + w * 0.2f, tip.y + h * 0.22f); head.close()
                    drawPath(head, color)
                }
            }
        }
    }
}

/** 지도 위 안내 화면 요소(배너·하차 칩·하단 막대). 지도 자체는 MapPane 이 LocalNavView 로 그린다 */
@Composable
fun NavOverlay(st: NavState, sim: Boolean, simAllowedOnly: Boolean, destName: String, offRoute: Boolean, waitingFix: Boolean,
               onToggleMode: () -> Unit, onSendPleos: (() -> Unit)?, onEnd: () -> Unit, tilt: Boolean = false, onToggleTilt: () -> Unit = {},
               voiceOn: Boolean = true, onToggleVoice: () -> Unit = {}, note: String? = null,
               detourText: String? = null, savingText: String? = null, passengerReady: String? = null) {
    // 목업 v3 안내 화면: 흰 회전 카드(보라 화살표) · 오른쪽 위 '하차점까지' 알약 · 오른쪽 동승자 카드 · 어두운 하단 막대
    Box(Modifier.fillMaxSize()) {
        val n = st.next
        Column(Modifier.align(Alignment.TopStart).padding(20.dp).widthIn(min = 300.dp, max = 460.dp).clip(RoundedCornerShape(26.dp)).background(Hd.Card)
            .padding(horizontal = 22.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (n != null) Box(Modifier.size(78.dp).clip(RoundedCornerShape(20.dp)).background(Hd.SkySoft), contentAlignment = Alignment.Center) { TurnIcon(n.kind, 58.dp, Hd.Purple) }
                Column {
                    Text(n?.let { distText(it.distanceM).replace("m", " m").replace("k m", " km") } ?: "—", color = Hd.Ink, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                    Text(n?.let { listOfNotNull(it.roadName, it.label).joinToString(" · ") } ?: "안내 정보 없음", color = Hd.Sub, fontSize = 18.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            st.after?.let { a -> Text("이후 ${distText(a.distanceM)} " + (if (a.isDrop) "하차 지점" else a.label), color = Hd.Sub, fontSize = 16.sp) }
            if (offRoute) Text("경로를 벗어났어요 · 경로로 돌아가 주세요", color = Hd.Warn, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            note?.let { Text(it, color = Hd.Purple, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
        }
        // 오른쪽 위: 하차점까지 알약 + 보기 버튼
        Column(Modifier.align(Alignment.TopEnd).padding(20.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.clip(RoundedCornerShape(28.dp)).background(Hd.Ink).padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(12.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Hd.Mint))
                Text(st.toDropM?.let { "하차점까지 ${((st.toDropSec ?: 0.0) / 60.0 + 0.5).toInt()}분" } ?: "목적지까지 ${(st.remainingSec / 60.0 + 0.5).toInt()}분",
                    color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Text(if (sim) "시연 모드" + (if (simAllowedOnly) " · 실제 안내 차단 지점" else "") else if (waitingFix) "실제 위치 대기 중" else "실제 위치",
                Modifier.clip(RoundedCornerShape(16.dp)).background(if (sim) Hd.WarnBg else Hd.Card).padding(horizontal = 12.dp, vertical = 6.dp),
                color = if (sim) Hd.Warn else Hd.Sub, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallChip(if (tilt) "3D" else "2D", onToggleTilt)
                SmallChip(if (voiceOn) "음성 켬" else "음성 끔", onToggleVoice)
                if (!simAllowedOnly) SmallChip(if (sim) "실제 위치로" else "모의 주행", onToggleMode)
            }
            passengerReady?.let {
                Column(Modifier.padding(top = 8.dp).width(300.dp).clip(RoundedCornerShape(22.dp)).background(Hd.Card).padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("동승자 경로 준비 완료", color = Hd.Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(it, color = Hd.Sub, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text("정차 후 확인해요 · 휴대폰 이어보기는 준비 중", color = Hd.Muted, fontSize = 12.sp)
                }
            }
        }
        // 어두운 하단 막대
        Row(Modifier.align(Alignment.BottomCenter).padding(20.dp).fillMaxWidth().height(96.dp).clip(RoundedCornerShape(28.dp)).background(Hd.Ink)
            .padding(start = 26.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column {
                Text("운전자 목적지 · $destName", color = Color(0xFFB9BACB), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 280.dp))
                Text(SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(System.currentTimeMillis() + (st.remainingSec * 1000).toLong())) +
                    " · ${(st.remainingSec / 60.0 + 0.5).toInt()}분 · ${distText(st.remainingM)}", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            if (detourText != null || savingText != null) Column(Modifier.weight(1f)) {
                Text("함께 이동하는 이득", color = Color(0xFFB9BACB), fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    detourText?.let { Text("우회 $it", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                    savingText?.let { Text("동승자 $it", color = Hd.Mint, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                }
            } else Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("안내 중", color = Color.White, fontSize = 16.sp)
                Box(Modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Hd.Mint))
            }
            Box(Modifier.fillMaxHeight().width(130.dp).background(Hd.DarkSub).clickable(onClick = onEnd), contentAlignment = Alignment.Center) {
                Text("안내 종료", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (st.arrived) Text("목적지에 도착했어요", Modifier.align(Alignment.Center).clip(RoundedCornerShape(24.dp)).background(Hd.Ink).padding(26.dp),
            color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SmallChip(text: String, onClick: () -> Unit) {
    Text(text, Modifier.clip(RoundedCornerShape(14.dp)).background(Hd.Card).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
        color = Hd.Purple, fontSize = 15.sp, fontWeight = FontWeight.Bold)
}

package com.jiye.dropmate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiye.dropmate.engine.NavHandoff
import com.jiye.dropmate.engine.RecommendResult
import com.jiye.dropmate.nav.createNaviBridge
import kotlinx.coroutines.launch

/**
 * 차량 내비로 ★ 하차 지점 전달. 판단(NavHandoff)이 막으면 버튼을 비활성화하고 사유를 보여 준다.
 * 사용자가 버튼을 누를 때만 전달을 시도한다(자동 전달 없음).
 */
@Composable
fun NaviHandoffCard(r: RecommendResult) {
    val context = LocalContext.current
    val bridge = remember { createNaviBridge(context) }
    DisposableEffect(bridge) { onDispose { bridge.close() } }
    val scope = rememberCoroutineScope()
    var status by remember(r.requestId) { mutableStateOf<String?>(null) }
    var busy by remember(r.requestId) { mutableStateOf(false) }
    val best = r.best
    val decision = NavHandoff.decide(r, best)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF1C232D))
        .border(1.dp, Color(0xFF303B49), RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("차량 내비로 하차 지점 안내", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text("연결: ${bridge.label}", fontSize = 11.sp, color = Color(0xFFA5B2C2))
        Button(onClick = {
            if (best == null) return@Button
            busy = true
            scope.launch {
                status = runCatching { bridge.addDropWaypoint(best.plan.label, best.plan.dropPoint, decision) }
                    .fold({ it }, { it.message ?: "전달 실패" })
                busy = false
            }
        }, enabled = decision.allowed && bridge.available && !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (decision.allowed) "★ 하차 지점을 경유지로 추가" else "길안내 전달 차단됨")
        }
        if (!decision.allowed) decision.reasons.forEach {
            Text("· $it", fontSize = 11.sp, color = Color(0xFFFFB74D))
        }
        status?.let { Text(it, fontSize = 11.sp, color = Color(0xFF76B9FF)) }
    }
}

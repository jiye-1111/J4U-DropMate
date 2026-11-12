package com.jiye.dropmate

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiye.dropmate.engine.*
import com.jiye.dropmate.ui.*

private val BgDark = Color(0xFF111418)
private val CardDark = Color(0xFF1B2028)
private val CardSel = Color(0xFF22344F)
private val Accent = Color(0xFF5AA9FF)
private val TextMain = Color(0xFFF2F5F8)
private val TextSub = Color(0xFF9AA7B4)
private val Warn = Color(0xFFFFB74D)
private val Err = Color(0xFFFF6B6B)

private val DropMateColors = darkColorScheme(
    primary = Accent, onPrimary = Color(0xFF04213D), background = BgDark, onBackground = TextMain,
    surface = CardDark, onSurface = TextMain, surfaceVariant = CardDark, onSurfaceVariant = TextSub, outline = Color(0xFF3A4452),
)

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 2026-11-12 차량 화면 시안 기반 새 화면(DriveScreen). 기존 DropMateScreen 은 비교용으로 남겨 둠.
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Hd.Navy, background = Hd.Bg, surface = Hd.Card)) { DriveScreen(vm) } }
    }

    // 휠은 MapPane의 실제 지도 뷰에서 로컬 좌표로 처리한다.
}

@Composable
fun DropMateScreen(vm: AppViewModel) {
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { g ->
        if (g.values.any { it }) vm.locateOrigin() else vm.message = "위치 권한이 없어 출발지를 자동으로 정하지 못했습니다. 검색하거나 지도에서 선택하세요."
    }
    LaunchedEffect(Unit) { permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
    val askLoc = { permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }

    var showInputs by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(vm.result?.requestId) { if (vm.result != null) showInputs = false }
    // 동승자 경로 탭(최소시간·최소환승·최소도보). 새 결과마다 최소시간으로 돌아간다.
    var routeKind by remember(vm.result?.requestId) { mutableStateOf(RouteOptionKind.FASTEST) }
    val passengerLegs = vm.result?.best?.routeOptions?.let { RouteOptions.pick(it, routeKind) }?.itinerary?.itinerary?.legs

    val mapBox: @Composable (Modifier) -> Unit = { mod ->
        // clipToBounds: 지도(AndroidView)가 자기 영역 밖으로 그려져 다른 영역과 겹치는 것을 막는다.
        Box(mod.clip(RoundedCornerShape(16.dp)).clipToBounds()) {
            MapPane(Modifier.fillMaxSize(), vm.places, vm.result, vm.picking, vm.searchHits?.second ?: emptyList(), { h -> vm.searchHits?.let { (role, _) -> vm.setPlace(role, h) } }, passengerLegs) { p ->
                vm.picking?.let { r -> vm.setPlace(r, Place("지도 선택 ${p}", p, "map-tap")) }
            }
            vm.picking?.let { r ->
                Text("지도를 눌러 ${r.title}를 정하세요", Modifier.align(Alignment.TopCenter).padding(10.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xE6000000)).padding(horizontal = 14.dp, vertical = 8.dp), color = Color.White, fontSize = 13.sp)
            }
            Text("© OpenStreetMap contributors", Modifier.align(Alignment.BottomStart).padding(8.dp), color = TextSub, fontSize = 9.sp)
            // 확대/축소 버튼(더블 탭은 확대만 되므로 축소 수단을 보장). 휠·핀치와 함께 동작한다.
            Column(Modifier.align(Alignment.TopEnd).padding(10.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xE61B2028))) {
                Text("＋", Modifier.size(44.dp).clickable { MapHandle.zoomIn() }.wrapContentSize(), fontSize = 22.sp, color = TextMain)
                HorizontalDivider(Modifier.width(44.dp), color = Color(0xFF3A4452))
                Text("－", Modifier.size(44.dp).clickable { MapHandle.zoomOut() }.wrapContentSize(), fontSize = 22.sp, color = TextMain)
            }
            vm.result?.let { r -> RouteLegend(r, Modifier.align(Alignment.BottomEnd).padding(10.dp), passengerLegs) }
        }
    }
    val panel: @Composable (Modifier) -> Unit = { mod ->
        Column(mod.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (showInputs) Role.entries.forEach { r -> PlaceRow(vm, r, askLoc) }
            Text("구간별 탑승 안내", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { vm.runConfigured() }, enabled = !vm.running, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(26.dp)) {
                    Text(if (vm.running) "계산 중…" else if (vm.result != null) "다시 계산하기" else "최적 하차 지점 계산", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                if (vm.running) OutlinedButton(onClick = { vm.cancel() }, modifier = Modifier.height(52.dp), shape = RoundedCornerShape(26.dp)) { Text("취소") }
            }
            vm.message?.let { Text(it, color = Err, fontSize = 13.sp) }
            vm.result?.let { RouteComparison(it, routeKind) { k -> routeKind = k }; ResultPanel(it) }
            vm.decisionLogPath?.let { Text("판단 기록 저장: $it", fontSize = 10.sp, color = TextSub) }
            Text(vm.gtfsStatus, fontSize = 10.sp, color = TextSub)
        }
    }
    Scaffold(containerColor = BgDark) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("DropMate", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("  ·  PLEOS", color = TextSub, fontSize = 16.sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showInputs = !showInputs }) { Text(if (showInputs) "입력 접기" else "입력 보기") }
            }
            if (!showInputs) Text(vm.places[Role.PASSENGER]?.label ?: "동승자 목적지",
                Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(10.dp))
                    .background(CardDark).padding(12.dp), fontWeight = FontWeight.SemiBold)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                // 가로(차량 화면)는 좌우 분할, 세로는 상하 분할 — 서로 다른 영역이라 겹치지 않는다.
                if (maxWidth > maxHeight) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    mapBox(Modifier.weight(0.62f).fillMaxHeight()); panel(Modifier.weight(0.38f).fillMaxHeight())
                } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    mapBox(Modifier.weight(0.45f).fillMaxWidth()); panel(Modifier.weight(0.55f).fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun SectionCard(selected: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (selected) CardSel else CardDark).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
}

@Composable
fun PlaceRow(vm: AppViewModel, role: Role, askLocation: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val p = vm.places[role]
    // 확정된 장소 이름(주소 부분 제외). 확정되면 검색창 자리에 이 이름을 띄운다.
    val shown = p?.label?.substringBefore(" · ")
    LaunchedEffect(p) { if (shown != null) q = shown }
    SectionCard(selected = p != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(role.title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Accent)
            Text(p?.let { "   ${it.address ?: it.label}" } ?: "   미정", fontSize = 13.sp, color = if (p != null) TextMain else TextSub, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(q, { q = it; vm.search(role, it, auto = true) }, Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(12.dp), placeholder = { Text("카카오 장소 검색 (지역·장소명)", fontSize = 13.sp) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { vm.search(role, q) }))
            TextButton(onClick = { vm.search(role, q) }) { Text("검색") }
            TextButton(onClick = { vm.picking = role }) { Text("지도") }
            if (role == Role.ORIGIN) TextButton(onClick = askLocation) { Text("현위치") }
        }
        vm.searchHits?.takeIf { it.first == role }?.let {
            if (vm.searchLoading) Text("카카오 장소 검색 중…", fontSize = 11.sp, color = TextSub)
            vm.searchNotice?.let { notice -> Text(notice, fontSize = 11.sp, color = TextSub) }
        }
        // 카카오 정확도 순서 유지. 한 화면에 5칸, 그 이상은 스크롤.
        vm.searchHits?.takeIf { it.first == role && it.second.isNotEmpty() }?.let { (_, hits) ->
            val rowH = 56.dp
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            val nearEnd by remember {
                androidx.compose.runtime.derivedStateOf {
                    val layout = listState.layoutInfo
                    layout.totalItemsCount > 0 &&
                        (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) >= layout.totalItemsCount - 3
                }
            }
            // 끝에 가까워지면 다음 페이지를 한 번 요청한다. 실패 시 자동 재시도하지 않는다.
            LaunchedEffect(nearEnd, hits.size) {
                if (nearEnd && vm.searchHasMore && !vm.searchLoading) vm.loadMorePlaces()
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(BgDark)) {
                Text("카카오 검색 결과 ${hits.size}개 — 눌러서 확정",
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp), fontSize = 11.sp, color = TextSub)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = rowH * 5), state = listState) {
                    items(hits.size) { i ->
                        val h = hits[i]
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = rowH).clickable { vm.setPlace(role, h) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(h.label, modifier = Modifier.weight(0.4f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(h.address ?: "상세 주소 정보 없음", modifier = Modifier.weight(0.6f), fontSize = 11.sp, color = TextSub)
                        }
                        if (i < hits.lastIndex) HorizontalDivider(color = Color(0xFF2A323D))
                    }
                }
            }
        }
    }
}

/** 지도 선과 안내 카드에 동일한 교통수단 색상을 사용한다. */
@Composable
fun RouteLegend(r: RecommendResult, modifier: Modifier, passengerLegs: List<TransitLeg>? = null) {
    val legs = passengerLegs ?: r.best?.chosen?.itinerary?.legs.orEmpty()
    if (r.baseline == null && legs.isEmpty()) return
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xD9111418)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (r.best == null) {
            LineRow(CONTINUE_CAR_COLOR, "비교 기준 경로 · 선택된 하차점 없음", true)
        } else {
            Text("★ 하차 → 번호 순서로 이동", color = Color(DROP_COLOR), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            LineRow(SHARED_CAR_COLOR, "차량 동승", false)
            LineRow(CONTINUE_CAR_COLOR, "하차 후 운전자 이동", true)
            LineRow(WALK_LINE_ARGB, "도보", true)
            legs.filter { it.mode != LegMode.WALK }.distinctBy { it.mode to it.route }.forEach {
                LineRow(legArgb(it), "${legKind(it.mode)} ${it.route ?: "노선 미확인"}", false)
            }
        }
    }
}

@Composable
private fun LineRow(argb: Int, text: String, dashed: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(26.dp).height(if (dashed) 3.dp else 6.dp).clip(RoundedCornerShape(3.dp)).background(Color(argb).copy(alpha = if (dashed) 0.8f else 1f)))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 12.sp, color = TextMain)
    }
}

@Composable
fun ResultPanel(r: RecommendResult) {
    val vtxt = when (r.verdict) {
        Verdict.RECOMMEND -> "동승 하차 추천"; Verdict.SIMILAR -> "단독 이동과 비슷함(작은 차이)"; Verdict.NO_GAIN -> "현재 조건에서는 따로 이동하는 편이 빠르거나 같음"
        Verdict.NO_CANDIDATE -> "조건을 만족하는 하차 경로 없음"; Verdict.INCOMPLETE -> "비교 불완전"
    }
    SectionCard(selected = true) {
        Text(vtxt + if (r.provisional) " (잠정)" else "", fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Text(r.verdictReason, fontSize = 12.sp, color = TextSub)
        r.solo?.let { s -> Text("단독 대중교통: " + (s.arrivalEpochSec?.let { "도착 ${TimeUtil.hms(it)} (환승 ${s.chosen?.itinerary?.transferCount ?: "미확인"})" } ?: s.reason), fontSize = 12.sp) }
        r.best?.let { b -> BestDetail(r, b) }
            ?: Text("선정된 하차지점이 없습니다. 현재 지도 선은 운전자 비교 기준 경로입니다.", color = Warn, fontSize = 12.sp)
    }
}

@Composable
fun BestDetail(r: RecommendResult, b: CandidateResult) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HorizontalDivider(color = Color(0xFF3A4452))
        Text("★ ${b.plan.label}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text("선정 기준: 동승자 총 도착시간 우선 · 운전자 추가 ≤ 직행의 30%(최대 15분) · 역/정류장 접근 도보 ≤ 10분", fontSize = 12.sp, color = Accent)
        b.passengerArrivalEpochSec?.let { Text("출발부터 동승자 목적지까지 ${minText((it - r.t0EpochSec).toInt())}", fontWeight = FontWeight.Bold) }
        b.chosen?.itinerary?.firstAccessWalkSec?.let { Text("첫 탑승 지점까지 도보 ${minText(it)}", fontSize = 12.sp) }
        Text("하차 지점: ${stopSuitabilityText(b.plan.stopSuitability)}", fontSize = 11.sp, color = Warn)
        val carSec = b.carToDrop?.totalTimeSec ?: b.plan.routeOffsetSec?.toInt()
        Detail(SHARED_CAR_COLOR, "함께 차량 이동", "출발지 → ★ 하차지점", minText(carSec), false)
        Text("선정 범위: ${r.bestScope}", fontSize = 11.sp, color = TextSub)
        Text("대중교통 실시간 도착·대기 검증: 미연결", fontSize = 12.sp, color = Warn)
        b.driverAddSec?.let { Text("운전자 추가시간 ${it}초" + (if (b.nearLimit) " (한도 근접)" else "") + " — ${b.driverAddBasis}", fontSize = 12.sp, color = TextSub) }
        b.dropCompleteEpochSec?.let { Text("차량 동승 후 하차 완료 ${TimeUtil.hms(it)}", fontSize = 12.sp) }
        b.chosen?.itinerary?.legs?.forEachIndexed { index, l ->
            val route = if (l.mode == LegMode.WALK) "${l.distanceM ?: "?"}m" else (l.route ?: "노선 미확인")
            val instruction = if (l.mode == LegMode.WALK) "걸어서 이동" else "탑승 → 하차"
            Detail(legArgb(l), "${index + 1}. ${legKind(l.mode)} · $instruction",
                "$route · ${l.startName ?: "?"} → ${l.endName ?: "?"}", minText(l.sectionTimeSec), l.mode == LegMode.WALK)
            if (l.shape.size < 2) Text("이 구간의 상세 선형은 제공되지 않아 지도에 생략했습니다.", fontSize = 10.sp, color = Warn)
        }
        if (b.plan.type == CandidateType.P_VIA)
            Detail(DROP_COLOR, "★ 동승자 목적지 도착", "목적지에서 하차 · 이후 대중교통 이동 없음", "", false)
        if (b.chosen != null) Text("탑승 편·대기: 운행편별 도착시각을 아직 검증하지 않았습니다.", fontSize = 11.sp, color = TextSub)
        b.passengerArrivalEpochSec?.let { Text("동승자 최종 도착 ${TimeUtil.hms(it)}" + (b.transferCount?.let { t -> " · 환승 ${t}회" } ?: ""), fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
        b.searchDttm?.let { Text("시간 기준: TMAP 대중교통 여정(searchDttm=$it, 정렬 ${b.alignDiffSec}초) — 대기 포함 여부 미확인", fontSize = 10.sp, color = TextSub) }
    }
}

/** 한 구간: 선 색 막대 + 종류·노선·구간명 + 소요시간. */
@Composable
private fun Detail(argb: Int, kind: String, desc: String, time: String, dashed: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(6.dp).height(34.dp).clip(RoundedCornerShape(3.dp)).background(Color(argb).copy(alpha = if (dashed) 0.7f else 1f)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(kind, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(desc, fontSize = 12.sp, color = TextSub)
        }
        Text(time, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

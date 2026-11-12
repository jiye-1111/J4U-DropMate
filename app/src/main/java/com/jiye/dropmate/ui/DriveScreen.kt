package com.jiye.dropmate.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiye.dropmate.car.DrivingMonitor
import com.jiye.dropmate.engine.*
import com.jiye.dropmate.nav.createNaviBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * 차량 화면(시안 레이아웃: 왼쪽 레일 · 머리말 · 왼쪽 작업 패널 · 오른쪽 지도).
 * 색: 현대차 브랜드로 널리 알려진 Hyundai Blue(#002C5F)·Active Blue(#00AAD2)·Light Sand(#E4DCD3) 계열.
 *     공식 브랜드 가이드 원문 대조는 하지 않았다.
 * 흐름: 01 입력 → 02 하차점 추천 → 03 이동 방법 → 04 길안내. 상세 비교는 '자세히 보기'에서만.
 */
object Hd {
    // 목업 v3 "Quiet Companion"(2026-11-12 사용자 제공): 짙은 남보라 패널·보라 강조·민트 절약
    val Ink = Color(0xFF252737)
    val Purple = Color(0xFF6552D9)
    val Mint = Color(0xFFC5EDBB)
    val DarkSub = Color(0xFF39394B)
    val Navy = Purple          // 기본 버튼·선택 강조
    val NavyDeep = Ink
    val NavyRailActive = DarkSub
    val Sky = Purple
    val SkySoft = Color(0xFFF0EEF9)
    val Sand = Color(0xFFF0EEF9)
    val Bg = Color(0xFFF6F5FA)
    val Card = Color(0xFFFFFFFF)
    val Stroke = Color(0xFFE7E5F0)
    val Text = Ink
    val Sub = Color(0xFF767885)
    val Muted = Color(0xFF9A9CA8)
    val Warn = Color(0xFF8A5A00)
    val WarnBg = Color(0xFFFFF1D6)
    val Bad = Color(0xFFB3261E)
}

private enum class Step { INPUT, RECOMMEND, OPTIONS, GUIDE, NAV }

@Composable
fun DriveScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { g ->
        if (g.values.any { it }) vm.locateOrigin() else vm.message = "위치 권한이 없어 현재 위치를 쓰지 못했어요. 검색하거나 지도에서 고르세요."
    }
    val askLoc = { permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
    // 차량 위치: Pleos FusedLocation(차량용 빌드). 없으면 기기 위치 권한 요청으로 대체
    val vloc = remember { com.jiye.dropmate.nav.createVehicleLocation(ctx.applicationContext) }
    var fix by remember { mutableStateOf<com.jiye.dropmate.car.VehicleFix?>(null) }
    var locErr by remember { mutableStateOf<String?>(null) }
    DisposableEffect(vloc) {
        vloc.start({ f ->
            fix = f
            // 사용자가 출발지를 직접 고르지 않았으면 차량 위치를 출발지로 쓴다
            val o = vm.places[Role.ORIGIN]
            if (vm.result == null && (o == null || o.source == "pleos")) vm.setPlace(Role.ORIGIN, Place("차량 현재 위치 (Pleos)", f.pos, "pleos"))
        }, { e -> locErr = e })
        onDispose { vloc.stop() }
    }
    // 차량용 빌드: Pleos 위치 권한(pleos.car.permission.FUSED_LOCATION, 위험 권한)을 먼저 요청하고 허용되면 위치를 다시 시작
    val pleosPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { g ->
        if (g.values.all { it }) { vloc.stop(); locErr = null; vloc.start({ f -> fix = f
            val o = vm.places[Role.ORIGIN]
            if (vm.result == null && (o == null || o.source == "pleos")) vm.setPlace(Role.ORIGIN, Place("차량 현재 위치 (Pleos)", f.pos, "pleos")) }, { e -> locErr = e }) }
        else locErr = "Pleos 위치 권한이 거부됐어요"
    }
    LaunchedEffect(Unit) {
        if (!vloc.available) askLoc()
        else if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, "pleos.car.permission.FUSED_LOCATION") != android.content.pm.PackageManager.PERMISSION_GRANTED)
            pleosPerm.launch(arrayOf("pleos.car.permission.FUSED_LOCATION"))
    }
    val useMyLocation: () -> Unit = {
        val f = fix
        if (f != null) vm.setPlace(Role.ORIGIN, Place("차량 현재 위치 (Pleos)", f.pos, "pleos")) else askLoc()
    }

    val driving = remember { DrivingMonitor(ctx.applicationContext) }
    DisposableEffect(driving) { driving.start(); onDispose { driving.stop() } }

    val r = vm.result
    var step by remember { mutableStateOf(Step.INPUT) }
    LaunchedEffect(r?.requestId) { step = if (r != null) Step.RECOMMEND else Step.INPUT }
    var routeKind by remember(r?.requestId) { mutableStateOf(RouteOptionKind.FASTEST) }
    var showDetail by remember(r?.requestId) { mutableStateOf(false) }
    var guideMsg by remember(r?.requestId) { mutableStateOf<String?>(null) }
    val selected = r?.best?.routeOptions?.let { RouteOptions.pick(it, routeKind) }
    val passengerLegs = selected?.itinerary?.itinerary?.legs

    // 앱 안 길안내: TMAP 자동차 경로의 안내점으로 회전 안내. sim=true 는 시연용 모의 주행(경로 구간 시간을 8배속으로 진행)
    var navSession by remember(r?.requestId) { mutableStateOf(r?.let { NavSession.from(it) }) }
    var navSim by remember(r?.requestId) { mutableStateOf(true) }
    var navSimOnly by remember(r?.requestId) { mutableStateOf(false) }
    var navState by remember(r?.requestId) { mutableStateOf<NavState?>(null) }
    // 안내를 시작하면 3D 보기(헤딩업 + 기울임)로 시작, 버튼으로 2D 전환
    var navTilt by remember(r?.requestId) { mutableStateOf(true) }
    // 음성 안내
    val speaker = remember { Speaker(ctx) }
    DisposableEffect(speaker) { onDispose { speaker.shutdown() } }
    var voiceOn by remember { mutableStateOf(true) }
    var voice by remember(r?.requestId) { mutableStateOf(VoiceGuide()) }
    // 하차 지점 도착 카드, 재탐색 상태
    var dropArrived by remember(r?.requestId) { mutableStateOf(false) }
    var navNote by remember(r?.requestId) { mutableStateOf<String?>(null) }
    var rerouteCount by remember(r?.requestId) { mutableStateOf(0) }
    val scopeNav = rememberCoroutineScope()
    // 동승자 없는 일반 길안내
    var driveOnly by remember { mutableStateOf(false) }
    val navDrop = if (driveOnly) null else r?.best?.plan?.takeIf { it.type != CandidateType.D_DROP }?.dropPoint
    var driveOnlyBusy by remember { mutableStateOf(false) }
    // 처음 정한 운전자 도착 한도(직행 도착 + 우회 한도). 재탐색해도 새로 늘리지 않는다
    val driverDeadline = remember(r?.requestId) { r?.let { rr -> rr.baseline?.let { rr.t0EpochSec + it.totalTimeSec + Policy().allowedDetourSec(it.totalTimeSec) } } }

    // 하차 시점 재계산 결과(실제 하차 위치·현재 시각 기준)
    var refreshed by remember(r?.requestId) { mutableStateOf<List<RouteOption>?>(null) }
    var refreshNote by remember(r?.requestId) { mutableStateOf<String?>(null) }
    fun refreshAtDrop(at: LatLng) {
        val dest = vm.places[Role.PASSENGER]?.pos ?: return
        refreshNote = "지금 시각으로 다시 찾는 중…"
        scopeNav.launch {
            val o = vm.refreshPassengerOptions(at, dest)
            refreshed = o
            refreshNote = if (o != null) "하차 시점 기준으로 다시 계산했어요 · " + SimpleDateFormat("HH:mm", Locale.KOREA).format(Date())
                else "다시 계산 실패(${vm.lastRouteError ?: "원인 미확인"}) · 출발 전 계산한 경로를 보여 줘요"
        }
    }

    fun onNavState(st: NavState) {
        val prev = navState
        navState = st
        if (navDrop != null && prev?.toDropM != null && st.toDropM == null && !dropArrived) {
            dropArrived = true
            // 하차하면 그 자리·그 시각으로 동승자 경로를 다시 계산(TMAP 1회)
            refreshed = null; refreshAtDrop(st.snapped)
            voice.dropReached()?.let { if (voiceOn) speaker.say(it) }
        }
        voice.next(st)?.let { if (voiceOn) speaker.say(it) }
    }

    // 안내를 시작하면 차 따라가기를 켠 상태로 시작(안내 전 지도를 옮긴 기록은 지운다)
    LaunchedEffect(step) { if (step == Step.NAV) MlHandle.followPaused = false }
    LaunchedEffect(step, navSim, navSession) {
        val ns = navSession
        if (step != Step.NAV || ns == null) return@LaunchedEffect
        if (navSim) {
            var t = 0.0
            while (true) {
                val st = ns.stateAt(ns.distAtTime(t)); onNavState(st)
                if (st.arrived) break
                delay(500); t += 4.0 // 0.5초마다 경로 시간 4초 진행(8배속)
            }
        } else {
            var offSince = 0L; var lastReroute = 0L
            while (true) {
                val f = fix
                if (f == null) { if (navState == null) navState = ns.stateAt(0.0) }
                else {
                    val st = ns.update(f.pos); onNavState(st)
                    val now = System.currentTimeMillis()
                    // 자동 재탐색: 경로 이탈이 5초 이어지고, 직전 재탐색 후 30초가 지났고, 안내당 5회 이하일 때만(TMAP 1회 호출)
                    if (ns.offRoute(st)) { if (offSince == 0L) offSince = now } else offSince = 0L
                    if (offSince != 0L && now - offSince >= 5_000 && now - lastReroute >= 30_000 && rerouteCount < 5) {
                        lastReroute = now; rerouteCount++
                        // 2026-11-13: 엔진이 고른 운전자 입구(문 앞 경유면 다시 고른 입구)까지 안내
                        val dest = (if (driveOnly) null else r?.driverDestFor(r.best)) ?: vm.places[Role.DRIVER]?.pos
                        val via = if (!dropArrived) navDrop else null
                        navNote = "경로를 다시 찾는 중…"
                        if (voiceOn) speaker.say("경로를 벗어났어요. 경로를 다시 찾습니다.")
                        if (dest != null) scopeNav.launch {
                            val nr = vm.rerouteCar(f.pos, via, dest)
                            if (nr != null) {
                                voice = VoiceGuide(); navSession = NavSession.fromRoute(nr, via)
                                val eta = System.currentTimeMillis() / 1000 + nr.totalTimeSec
                                val over = driverDeadline != null && via != null && eta > driverDeadline
                                // 하차점은 자동으로 바꾸지 않는다. 처음 우회 한도를 넘으면 알리기만 한다
                                navNote = if (over) "새 경로로는 처음 정한 우회 한도를 ${(eta - driverDeadline!!) / 60}분 넘어요 · 하차점은 그대로" else "새 경로로 안내해요 (재탐색 ${rerouteCount}/5)"
                                if (over && voiceOn) speaker.say("새 경로는 처음 정한 우회 한도를 넘습니다. 하차 지점은 그대로 유지합니다.")
                            }
                            else navNote = "재탐색 실패 · 기존 경로로 안내 중"
                        }
                    }
                }
                delay(1000)
            }
        }
    }

    // 목업 v2: 지도 전체 화면 + 왼쪽 레일 + 떠 있는 작업 패널 + 상단 상태 알약 + (안내 중) 하단 막대
    Row(Modifier.fillMaxSize().background(Hd.Bg)) {
        // 3D 보기에서 기울어진 지도 위쪽에 보이는 하늘색 배경
        Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFFBFDDF2), Color(0xFFE9F3F9), Hd.Bg)))) {
            val nsv = navSession
            val nv = if (step == Step.NAV && nsv != null) navState?.let { navView(nsv, it, if (dropArrived) null else navDrop, navTilt) } else null
            // 3D 건물 지도(MapLibre + OpenFreeMap). 안내 중에는 실제 카메라 기울기로 건물이 입체로 보인다
            MapLibrePane(Modifier.fillMaxSize(), vm.places, r, vm.picking, vm.searchHits?.second ?: emptyList(),
                { h -> vm.searchHits?.let { (role, _) -> vm.setPlace(role, h) } }, passengerLegs, if (nv != null) null else fix?.pos, nv) { p ->
                vm.picking?.let { role -> vm.setPlace(role, Place("지도 선택 ${p}", p, "map-tap")) }
            }
            if (step == Step.NAV && nsv != null) {
                navState?.let { st ->
                    NavOverlay(st, navSim, navSimOnly, vm.places[Role.DRIVER]?.label?.substringBefore(" · ") ?: "운전자 목적지",
                        offRoute = !navSim && nsv.offRoute(st), waitingFix = !navSim && fix == null,
                        onToggleMode = { navSim = !navSim; navState = null; voice = VoiceGuide(); dropArrived = false }, onSendPleos = null,
                        onEnd = { step = if (driveOnly) Step.INPUT else Step.OPTIONS; navState = null; speaker.stop(); voice = VoiceGuide(); dropArrived = false; driveOnly = false },
                        tilt = navTilt, onToggleTilt = { navTilt = !navTilt },
                        voiceOn = voiceOn, onToggleVoice = { voiceOn = !voiceOn; if (!voiceOn) speaker.stop() }, note = navNote,
                        detourText = if (driveOnly) null else r?.best?.driverAddSec?.let { "+" + mins(it.toLong()) },
                        savingText = if (driveOnly) null else r?.savingSec?.let { if (it > 0) "${mins(it)} 절약" else "${mins(it)} 늦음" },
                        passengerReady = if (driveOnly || dropArrived) null else selected?.itinerary?.itinerary?.legs?.filter { it.mode != LegMode.WALK }
                            ?.joinToString(" → ") { "${legKind(it.mode)} ${it.route ?: ""}".trim() }?.let { it + " · 도착 " + hhmm(selected.itinerary.arrivalEpochSec) })
                    // 하차 지점 도착: 동승자가 이어서 갈 경로를 보여 주고, 운전자 목적지 안내는 계속된다
                    if (dropArrived && !st.arrived) DropArrivedCard(selected, refreshed, refreshNote, Modifier.align(Alignment.CenterEnd).padding(end = 96.dp),
                        onRefresh = { navState?.let { refreshAtDrop(it.snapped) } }) { dropArrived = false }
                }
            } else Row(Modifier.align(Alignment.TopCenter).padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusPill(driving, step)
                Clock()
            }
            // 안내 중에는 확대·축소 버튼을 숨긴다(오른쪽 위 '하차점까지' 알약과 겹침 방지, 카메라는 차를 따라감)
            if (step != Step.NAV) Column(Modifier.align(Alignment.TopEnd).padding(16.dp).clip(RoundedCornerShape(14.dp)).background(Hd.Card).border(1.dp, Hd.Stroke, RoundedCornerShape(14.dp))) {
                Text("＋", Modifier.size(54.dp).clickable { MlHandle.zoomIn() }.wrapContentSize(), fontSize = 24.sp, color = Hd.Navy)
                Text("－", Modifier.size(54.dp).clickable { MlHandle.zoomOut() }.wrapContentSize(), fontSize = 24.sp, color = Hd.Navy)
            }
            // 2026-11-13 '내 위치로': 줌 버튼 바로 아래. 차 위치(없으면 출발지)로 지도를 되돌린다
            if (step != Step.NAV) RecenterButton(Modifier.align(Alignment.TopEnd).padding(top = 16.dp + 108.dp + 12.dp, end = 16.dp)) {
                (fix?.pos ?: vm.places[Role.ORIGIN]?.pos)?.let { MlHandle.recenter(it) }
            }
            // 안내 중: 손으로 지도를 옮겨 따라가기가 멈췄을 때만 보이고, 누르면 차로 돌아가 다시 따라간다
            else if (MlHandle.followPaused) RecenterButton(Modifier.align(Alignment.CenterEnd).padding(end = 16.dp)) { MlHandle.followPaused = false }
            if (step != Step.NAV) LogoPill(Modifier.align(Alignment.TopStart).padding(start = 24.dp, top = 20.dp)) {
                // 로고를 누르면 처음(입력)으로
                vm.clearResult(); step = Step.INPUT
            }
            vm.picking?.let { role ->
                Text("지도를 눌러 ${role.title}를 정하세요", Modifier.align(Alignment.TopCenter).padding(top = 72.dp)
                    .clip(RoundedCornerShape(20.dp)).background(Hd.Navy).padding(horizontal = 16.dp, vertical = 8.dp), color = Color.White, fontSize = 16.sp)
            }
            Text("© OpenStreetMap · OpenFreeMap", Modifier.align(Alignment.BottomEnd).padding(8.dp), color = Hd.Muted, fontSize = 10.sp)
            if (step == Step.OPTIONS && r != null && !(driving.restricted)) {
                OptionsLayout(r, routeKind, { routeKind = it }, vm, navSession != null,
                    onNav = { sim, simOnly -> navSim = sim; navSimOnly = simOnly; navState = null; driveOnly = false
                        navSession = NavSession.from(r); voiceOn = guideMsg == null; step = Step.NAV },
                    onBack = { step = Step.RECOMMEND }) { msg -> guideMsg = msg; step = Step.GUIDE }
            } else if (step != Step.NAV) BoxWithConstraints(Modifier.fillMaxSize().padding(start = 24.dp, top = 96.dp, bottom = if (step == Step.GUIDE) 128.dp else 24.dp)) {
                val w = (maxWidth * 0.36f).coerceIn(380.dp, 500.dp)
                Column(Modifier.width(w).wrapContentHeight().heightIn(max = maxHeight).clip(RoundedCornerShape(28.dp)).background(if (step == Step.RECOMMEND) Color.Transparent else Hd.Card)
                    .verticalScroll(rememberScrollState()).padding(if (step == Step.RECOMMEND) 0.dp else 26.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    val blockInput = driving.restricted && (step == Step.INPUT || step == Step.OPTIONS)
                    if (blockInput) DrivingBlock()
                    else when (step) {
                        Step.INPUT -> {
                            InputStep(vm, useMyLocation, onDriveOnly = {
                                val o = vm.places[Role.ORIGIN]; val d = vm.places[Role.DRIVER]
                                if (o != null && d != null) { driveOnlyBusy = true; scopeNav.launch {
                                    val rt = vm.rerouteCar(o.pos, null, d.pos)
                                    driveOnlyBusy = false
                                    if (rt != null) { navSession = NavSession.fromRoute(rt, null); driveOnly = true; navSim = fix == null; navSimOnly = false
                                        navState = null; voice = VoiceGuide(); dropArrived = false; step = Step.NAV }
                                    else vm.message = "경로를 찾지 못했어요: " + (vm.lastRouteError ?: "원인 미확인")
                                } }
                            }, driveOnlyBusy = driveOnlyBusy)
                            Text(when {
                                fix != null -> "내 위치: ${vloc.label} · ${SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(fix!!.timeMillis))} 받음"
                                locErr != null -> "내 위치: $locErr"
                                vloc.available -> "내 위치: Pleos 차량 위치 기다리는 중"
                                else -> "내 위치: 기기 위치 사용(차량 위치 SDK 없는 빌드)"
                            }, color = Hd.Muted, fontSize = 13.sp)
                        }
                        Step.RECOMMEND -> r?.let { RecommendStep(it, { step = Step.OPTIONS }, { vm.clearResult(); step = Step.INPUT }, showDetail) { showDetail = !showDetail } }
                        Step.OPTIONS -> r?.let { OptionsStep(it, routeKind, { routeKind = it }, vm, navSession != null,
                            // Pleos 내장 내비로 이미 안내 중이면(guideMsg) DropMate 음성은 꺼서 겹치지 않게
                            onNav = { sim, simOnly -> navSim = sim; navSimOnly = simOnly; navState = null; driveOnly = false
                                navSession = NavSession.from(it); voiceOn = guideMsg == null; step = Step.NAV }) { msg -> guideMsg = msg; step = Step.GUIDE } }
                        Step.GUIDE -> r?.let { GuideStep(it, selected, guideMsg, vm) { step = Step.RECOMMEND } }
                        Step.NAV -> {}
                    }
                    vm.message?.let { Text(it, color = Hd.Bad, fontSize = 16.sp) }
                }
            }
            if (step == Step.GUIDE && r?.best != null) GuideBar(r, driving, Modifier.align(Alignment.BottomCenter)) { step = Step.RECOMMEND }
            if (step == Step.RECOMMEND && r?.best != null) CompareBar(r, Modifier.align(Alignment.BottomEnd)) { step = Step.OPTIONS }
            if (step == Step.INPUT) Text("운전자 경로는 그대로,\n동승자에게 더 좋은 하차점을.", Modifier.align(Alignment.BottomEnd).padding(28.dp)
                .clip(RoundedCornerShape(24.dp)).background(Hd.Ink).padding(horizontal = 26.dp, vertical = 18.dp), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp)
        }
    }
}

@Composable
private fun StatusPill(driving: DrivingMonitor, step: Step) {
    val phase = if (step == Step.GUIDE) "안내 중" else "출발 전"
    val (txt, fg, bg) = when {
        !driving.available -> Triple("$phase · 주행 상태 확인 불가", Hd.Sub, Hd.Card)
        driving.restricted -> Triple("$phase · 주행 중", Hd.Warn, Hd.WarnBg)
        else -> Triple("$phase · 정차 중", Hd.Navy, Hd.Card)
    }
    Text(txt, Modifier.clip(RoundedCornerShape(22.dp)).background(bg).border(1.dp, Hd.Stroke, RoundedCornerShape(22.dp))
        .padding(horizontal = 18.dp, vertical = 9.dp), color = fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun Clock() {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(20_000) } }
    Text(SimpleDateFormat("HH:mm", Locale.KOREA).format(now), Modifier.clip(RoundedCornerShape(22.dp)).background(Hd.Navy)
        .padding(horizontal = 18.dp, vertical = 9.dp), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
}

/** 안내 중 하단 막대: 하차점까지 · 운전자 최종 도착 · 주행 중 제한 · 닫기 */
@Composable
private fun GuideBar(r: RecommendResult, driving: DrivingMonitor, mod: Modifier, onEnd: () -> Unit) {
    val b = r.best ?: return
    Row(mod.padding(16.dp).fillMaxWidth().height(96.dp).clip(RoundedCornerShape(22.dp)).background(Hd.Card).border(1.dp, Hd.Stroke, RoundedCornerShape(22.dp)),
        verticalAlignment = Alignment.CenterVertically) {
        BarCell(Modifier.weight(1f), "하차점까지", mins((b.dropCompleteEpochSec ?: r.t0EpochSec) - r.t0EpochSec))
        BarCell(Modifier.weight(1f), "운전자 최종 도착", hhmm(b.driverArrivalEpochSec))
        BarCell(Modifier.weight(1.3f), "주행 중 제한", if (driving.restricted) "입력·경로 변경 잠김" else "정차 중 · 변경 가능")
        Box(Modifier.fillMaxHeight().width(150.dp).background(Hd.Navy).clickable(onClick = onEnd), contentAlignment = Alignment.Center) {
            Text("안내 화면 닫기", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun BarCell(mod: Modifier, k: String, v: String) {
    Column(mod.padding(horizontal = 18.dp)) {
        Text(k, color = Hd.Sub, fontSize = 14.sp)
        Text(v, color = Hd.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Rail(step: Step, onSearch: () -> Unit, onCompare: () -> Unit, onGuide: () -> Unit, onSettings: () -> Unit) {
    Column(Modifier.width(92.dp).fillMaxHeight().background(Hd.Navy).padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(Hd.Sky), contentAlignment = Alignment.Center) {
            Text("DM", color = Hd.Navy, fontSize = 20.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(10.dp))
        RailItem("검색", step == Step.INPUT, onSearch)
        RailItem("비교", step == Step.RECOMMEND, onCompare)
        RailItem("안내", step == Step.OPTIONS || step == Step.GUIDE, onGuide)
        Spacer(Modifier.weight(1f))
        RailItem("자세히", false, onSettings)
    }
}

@Composable
private fun RailItem(label: String, active: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)).background(if (active) Hd.NavyRailActive else Color.Transparent).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        Text(label, color = if (active) Color.White else Color(0xFFB7C6D8), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StepTitle(no: String, title: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(no, color = Hd.Sky, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(title, color = Hd.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BigButton(text: String, enabled: Boolean = true, primary: Boolean = true, onClick: () -> Unit) {
    val bg = when { !enabled -> Color(0xFFCFD6DD); primary -> Hd.Navy; else -> Hd.Card }
    Box(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(18.dp)).background(bg)
        .then(if (!primary) Modifier.border(1.5.dp, Hd.Navy, RoundedCornerShape(18.dp)) else Modifier)
        .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier), contentAlignment = Alignment.Center) {
        Text(text, color = if (primary) Color.White else Hd.Navy, fontSize = 21.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CardBox(selected: Boolean = false, pad: Dp = 18.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Hd.Card)
        .border(if (selected) 2.dp else 1.dp, if (selected) Hd.Sky else Hd.Stroke, RoundedCornerShape(20.dp)).padding(pad),
        verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

// ---------------- 01 입력 ----------------
@Composable
private fun InputStep(vm: AppViewModel, askLoc: () -> Unit, onDriveOnly: () -> Unit, driveOnlyBusy: Boolean) {
    Text("함께 가는 길,\n각자의 목적지까지.", color = Hd.Text, fontSize = 32.sp, fontWeight = FontWeight.Bold, lineHeight = 42.sp)
    Text("출발 전 · 정차 상태", color = Hd.Sub, fontSize = 17.sp)
    // 기본은 일반 내비: 출발지·운전자 목적지만 있으면 바로 안내. 동승자 목적지는 선택
    BigPlaceCard(vm, Role.ORIGIN, askLoc)
    BigPlaceCard(vm, Role.DRIVER, askLoc)
    var withPassenger by remember { mutableStateOf(vm.places[Role.PASSENGER] != null) }
    if (withPassenger) BigPlaceCard(vm, Role.PASSENGER, askLoc)
    else Text("+ 동승자 목적지 추가 (함께 타고 내려 줄 때)", Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Hd.SkySoft)
        .clickable { withPassenger = true }.padding(16.dp), color = Hd.Navy, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    val canDrive = vm.places[Role.ORIGIN] != null && vm.places[Role.DRIVER] != null
    if (withPassenger) {
        BigButton(if (vm.running) "하차점을 찾는 중…" else "함께 갈 경로 찾기  →", enabled = !vm.running && vm.ready() == null) { vm.runConfigured() }
        vm.ready()?.takeIf { !vm.running }?.let { Text(it, color = Hd.Sub, fontSize = 15.sp) }
        BigButton("동승자 없이 바로 안내", primary = false, enabled = canDrive && !driveOnlyBusy, onClick = onDriveOnly)
    } else {
        BigButton(if (driveOnlyBusy) "경로를 찾는 중…" else "안내 시작", enabled = canDrive && !driveOnlyBusy, onClick = onDriveOnly)
        if (!canDrive) Text("출발지와 운전자 목적지를 정하세요", color = Hd.Sub, fontSize = 15.sp)
    }
}

@Composable
private fun BigPlaceCard(vm: AppViewModel, role: Role, askLoc: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val p = vm.places[role]
    LaunchedEffect(p) { p?.label?.substringBefore(" · ")?.let { q = it } }
    val tag = when (role) { Role.ORIGIN -> "출발지"; Role.DRIVER -> "운전자 목적지"; Role.PASSENGER -> "동승자 목적지" }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Hd.Bg).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(if (p != null) Hd.Purple else Color.White), contentAlignment = Alignment.Center) {
                Text(when (role) { Role.ORIGIN -> "O"; Role.DRIVER -> "D"; Role.PASSENGER -> "P" }, color = if (p != null) Color.White else Hd.Navy, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            Text(tag, color = Hd.Sub, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            SmallLink("지도") { vm.picking = role }
            if (role == Role.ORIGIN) SmallLink("내 위치", askLoc)
        }
        BasicTextField(q, { q = it; vm.search(role, it, auto = true) }, singleLine = true,
            textStyle = TextStyle(color = Hd.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold), cursorBrush = SolidColor(Hd.Sky),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { vm.search(role, q) }),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White).padding(horizontal = 16.dp, vertical = 12.dp)) {
                    if (q.isEmpty()) Text("장소 이름으로 검색", color = Hd.Muted, fontSize = 20.sp)
                    inner()
                }
            })
        p?.address?.let { Text(it, color = Hd.Sub, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        vm.searchHits?.takeIf { it.first == role }?.let { (_, hits) ->
            if (vm.searchLoading) Text("검색 중…", color = Hd.Sub, fontSize = 15.sp)
            vm.searchNotice?.let { Text(it, color = Hd.Sub, fontSize = 14.sp) }
            hits.take(5).forEach { h ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { vm.setPlace(role, h) }.padding(horizontal = 8.dp, vertical = 8.dp)) {
                    Text(h.label, color = Hd.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(h.address ?: "", color = Hd.Sub, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun SmallLink(text: String, onClick: () -> Unit) {
    Text(text, Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).background(Hd.SkySoft).padding(horizontal = 12.dp, vertical = 6.dp),
        color = Hd.Navy, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
}

// ---------------- 02 하차점 추천 ----------------
private fun mins(sec: Long?): String = sec?.let { "${(kotlin.math.abs(it) + 30) / 60}분" } ?: "—"
private fun hhmm(epoch: Long?): String = epoch?.let { SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(it * 1000)) } ?: "—"

@Composable
private fun RecommendStep(r: RecommendResult, onOptions: () -> Unit, onReset: () -> Unit, showDetail: Boolean, toggleDetail: () -> Unit) {
    val b = r.best
    val title = when (r.verdict) {
        Verdict.RECOMMEND -> "어디서 내리면 좋을까요?"
        Verdict.SIMILAR -> "함께 가도 차이가 거의 없어요"
        Verdict.NO_GAIN -> "이번엔 따로 가는 게 더 빨라요"
        Verdict.NO_CANDIDATE -> "조건에 맞는 하차점이 없어요"
        Verdict.INCOMPLETE -> "비교를 끝내지 못했어요"
    }
    // 머리말 카드
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Hd.Card).padding(22.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = Hd.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        // 2026-11-13 사용자: 시간대마다 결과가 달라지므로 '현재 교통 기준'과 조회 시각을 함께 보여 준다
        Text(if (b != null) "조회한 후보 중 추천 · 현재 교통 기준 ${hhmm(r.t0EpochSec)} 조회" else r.verdictReason, color = Hd.Sub, fontSize = 15.sp)
    }
    val ranked = r.dropRanking().filter { it.rank != null }
    fun saveOf(c: CandidateResult) = r.solo?.arrivalEpochSec?.let { s -> c.passengerArrivalEpochSec?.let { s - it } }
    if (b != null) {
        // 01 추천 하차점(어두운 카드)
        val sv = r.savingSec
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Hd.Ink).padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (r.verdict == Verdict.RECOMMEND) "01  추천 하차점" else "01  함께 간다면 가장 나은 곳", color = Hd.Mint, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(b.plan.label.replace(" 인근 경로상 하차", " 근처").replace("운전자 목적지까지 동승 후 이동", "운전자 목적지에서 함께 하차"),
                color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(stopSuitabilityText(b.plan.stopSuitability), color = Color(0xFFB9BACB), fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(Hd.DarkSub).padding(14.dp)) {
                    Text("운전자 우회", color = Color(0xFFB9BACB), fontSize = 14.sp)
                    Text(b.driverAddSec?.let { "+" + mins(it.toLong()) } ?: "미확인", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                }
                val good = sv != null && sv > 0
                Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(if (good) Hd.Mint else Color(0xFFF6D9D6)).padding(14.dp)) {
                    Text(if (sv == null) "동승자 절약" else if (good) "동승자 절약" else "동승자 손해", color = Hd.Ink, fontSize = 14.sp)
                    Text(sv?.let { mins(it) } ?: "미확인", color = Hd.Ink, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                }
            }
            val direct = r.baseline?.totalTimeSec
            b.driverAddSec?.let { add ->
                val pct = direct?.takeIf { it > 0 }?.let { (add * 100.0 / it).toInt() }
                Text("직행 대비 " + (pct?.let { "+$it%" } ?: "미확인") + " · 한도 ${direct?.let { mins(Policy().allowedDetourSec(it).toLong()) } ?: "미확인"}(직행 30%·최대 15분)" + if (b.nearLimit) " · 한도에 가까움" else "",
                    color = if (b.nearLimit) Color(0xFFFFD08A) else Color(0xFFB9BACB), fontSize = 13.sp)
            }
        }
        // 02 다른 하차점
        ranked.filter { it.candidate.plan.id != b.plan.id }.take(2).forEachIndexed { i, d ->
            val c = d.candidate; val s2 = saveOf(c)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Hd.Card).padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("0${i + 2}  다른 하차점", color = Hd.Purple, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(c.plan.label.replace(" 인근 경로상 하차", " 근처").replace("운전자 목적지까지 동승 후 이동", "운전자 목적지"),
                    color = Hd.Text, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("우회 +${mins(c.driverAddSec?.toLong())} · 동승자 " + (s2?.let { if (it > 0) "${mins(it)} 절약" else "${mins(it)} 늦음" } ?: "미확인"),
                    color = Hd.Sub, fontSize = 15.sp)
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (showDetail) "자세히 닫기" else "자세히 보기", Modifier.clip(RoundedCornerShape(16.dp)).background(Hd.Card).clickable(onClick = toggleDetail)
            .padding(horizontal = 18.dp, vertical = 12.dp), color = Hd.Purple, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text("다시 입력", Modifier.clip(RoundedCornerShape(16.dp)).background(Hd.Card).clickable(onClick = onReset)
            .padding(horizontal = 18.dp, vertical = 12.dp), color = Hd.Sub, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
    if (showDetail) DetailCompare(r)
}

/** 02 하단 비교 막대: 단독 이동 → 함께 이동, '하차 후 경로 보기' */
@Composable
private fun CompareBar(r: RecommendResult, mod: Modifier, onOptions: () -> Unit) {
    val b = r.best ?: return
    fun rel(e: Long?) = e?.let { minText((it - r.t0EpochSec).toInt()) } ?: "미확인"
    Row(mod.padding(24.dp).widthIn(max = 760.dp).clip(RoundedCornerShape(26.dp)).background(Hd.Card).padding(start = 26.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column {
            Text("각자의 시간을 함께 비교해요", color = Hd.Sub, fontSize = 14.sp)
            Text("단독 이동 ${rel(r.solo?.arrivalEpochSec)}  →  함께 이동 ${rel(b.passengerArrivalEpochSec)}", color = Hd.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Text("하차 후 경로 보기", Modifier.clip(RoundedCornerShape(18.dp)).background(Hd.Purple).clickable(onClick = onOptions)
            .padding(horizontal = 22.dp, vertical = 16.dp), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

/** 왼쪽 위 로고 알약 "dropmate" + 민트 점 */
@Composable
private fun LogoPill(mod: Modifier, onClick: () -> Unit) {
    Row(mod.clip(RoundedCornerShape(28.dp)).background(Hd.Ink).clickable(onClick = onClick).padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(Hd.Mint))
        Text("dropmate", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Tile(mod: Modifier, label: String, value: String, valueColor: Color, bg: Color) {
    Column(mod.clip(RoundedCornerShape(16.dp)).background(bg).padding(14.dp)) {
        Text(label, color = Hd.Sub, fontSize = 15.sp)
        Text(value, color = valueColor, fontSize = 40.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DetailCompare(r: RecommendResult) {
    CardBox {
        Text("상세 비교", color = Hd.Text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        fun rel(e: Long?) = e?.let { minText((it - r.t0EpochSec).toInt()) } ?: "미확인"
        DetailRow("동승자 혼자 대중교통", rel(r.solo?.arrivalEpochSec))
        DetailRow("함께 타고 하차 후 이동", rel(r.best?.passengerArrivalEpochSec))
        DetailRow("운전자 혼자 직행", minText(r.baseline?.totalTimeSec))
        DetailRow("동승 후 운전자 도착", rel(r.best?.driverArrivalEpochSec))
        Spacer(Modifier.height(4.dp))
        Text("비교한 하차점", color = Hd.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        r.dropRanking().filter { it.rank != null || it.candidate.status != CandStatus.NOT_QUERIED_BUDGET }.forEach { d ->
            val c = d.candidate
            DetailRow((d.rank?.let { "$it. " } ?: "– ") + c.plan.label, c.passengerArrivalEpochSec?.let { rel(it) } ?: c.reason.take(28))
        }
        Text(r.bestScope, color = Hd.Muted, fontSize = 12.sp)
    }
}

@Composable
private fun DetailRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(k, Modifier.weight(1f), color = Hd.Sub, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(v, color = Hd.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ---------------- 03 이동 방법 ----------------
@Composable
private fun OptionsStep(r: RecommendResult, kind: RouteOptionKind, onKind: (RouteOptionKind) -> Unit, vm: AppViewModel, navReady: Boolean,
                        onNav: (sim: Boolean, simOnly: Boolean) -> Unit, onGuide: (String) -> Unit) {
    val b = r.best ?: return
    StepTitle("03", "내게 맞는 이동 방법을 골라요")
    Text("★ ${b.plan.label.replace(" 인근 경로상 하차", " 근처")}에서 내린 뒤", color = Hd.Sub, fontSize = 17.sp)
    RouteOptionKind.entries.forEach { k ->
        val o = RouteOptions.pick(b.routeOptions, k)
        val on = k == kind && o != null
        CardBox(selected = on, pad = 16.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (o?.kinds ?: listOf(k)).forEach { kk ->
                            Text(kk.label, Modifier.clip(RoundedCornerShape(8.dp)).background(if (kk == k) Hd.Navy else Hd.SkySoft).padding(horizontal = 10.dp, vertical = 3.dp),
                                color = if (kk == k) Color.White else Hd.Navy, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    val fastestArr = RouteOptions.pick(b.routeOptions, RouteOptionKind.FASTEST)?.itinerary?.arrivalEpochSec
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(o?.itinerary?.arrivalEpochSec?.let { "도착 ${hhmm(it)}" } ?: "정보 없음", color = Hd.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        // 빠른 도착 대비 늦어지는 시간(+N분). 빠른 도착 자신은 '가장 빠름'
                        val diff = o?.itinerary?.arrivalEpochSec?.let { a -> fastestArr?.let { a - it } }
                        Text(when { diff == null -> ""; diff <= 0 -> "가장 빠름"; else -> "+${mins(diff)}" },
                            color = if ((diff ?: 0) > 0) Hd.Warn else Hd.Sky, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                    o?.itinerary?.itinerary?.let { i ->
                        Text("도보 ${minText(i.totalWalkTimeSec)} · 환승 ${i.transferCount?.let { "${it}회" } ?: "?"}", color = Hd.Sub, fontSize = 16.sp)
                        Text(i.legs.joinToString(" → ") { l -> if (l.mode == LegMode.WALK) "도보" else "${legKind(l.mode)} ${l.route ?: ""}".trim() },
                            color = Hd.Sub, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (o != null) Text(if (on) "선택됨" else "선택", Modifier.clip(RoundedCornerShape(12.dp)).background(if (on) Hd.Navy else Hd.SkySoft)
                    .clickable { onKind(k) }.padding(horizontal = 16.dp, vertical = 10.dp), color = if (on) Color.White else Hd.Navy, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
    Text("같은 경로가 여러 기준에 해당하면 배지를 함께 표시해요 · 하차점 순위는 최소시간 기준", color = Hd.Muted, fontSize = 13.sp)
    // 앱 안 길안내: 하차 가능 판단이 허용될 때만 실제 위치 안내, 막히면 모의 주행 미리보기만
    val decision = NavHandoff.decide(r, r.best)
    if (navReady) {
        if (decision.allowed) {
            BigButton("DropMate로 안내 시작") { onNav(false, false) }
            BigButton("모의 주행으로 미리보기", primary = false) { onNav(true, false) }
        } else BigButton("모의 주행으로 미리보기(실제 안내 차단)", primary = false) { onNav(true, true) }
    } else Text("안내 경로 정보가 없어 앱 안 길안내를 시작할 수 없어요", color = Hd.Muted, fontSize = 13.sp)
    HandoffButton(r, vm, onGuide)
}

@Composable
private fun HandoffButton(r: RecommendResult, vm: AppViewModel, onGuide: (String) -> Unit) {
    val ctx = LocalContext.current
    val bridge = remember { createNaviBridge(ctx) }
    DisposableEffect(bridge) { onDispose { bridge.close() } }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var err by remember(r.requestId) { mutableStateOf<String?>(null) }
    val b = r.best ?: return
    val decision = NavHandoff.decide(r, b)
    val dest = vm.places[Role.DRIVER]
    BigButton(if (busy) "차량 내비에 보내는 중…" else "Pleos 차량 내비로 보내기", primary = false, enabled = decision.allowed && bridge.available && !busy && dest != null) {
        busy = true
        scope.launch {
            val dropName = b.plan.label.replace(" 인근 경로상 하차", " 하차점").replace("운전자 목적지까지 동승 후 이동", "운전자 목적지").replace("동승자 목적지 문 앞 경유", "동승자 목적지")
            runCatching { bridge.startDropRoute(dropName, b.plan.dropPoint, dest!!.label.substringBefore(" · ").substringBefore(" ("), r.driverDestFor(b) ?: dest.pos, decision) }
                .onSuccess { onGuide(it) }.onFailure { err = it.message ?: "전달 실패" }
            busy = false
        }
    }
    if (!bridge.available) Text("이 빌드에는 차량 내비 연결이 없어요(차량용 빌드에서 동작)", color = Hd.Muted, fontSize = 13.sp)
    if (!decision.allowed) decision.reasons.forEach { Text("· $it", color = Hd.Warn, fontSize = 15.sp) }
    err?.let { Text(it, color = Hd.Bad, fontSize = 15.sp) }
}

// ---------------- 04 길안내 ----------------
@Composable
private fun GuideStep(r: RecommendResult, opt: RouteOption?, msg: String?, vm: AppViewModel, onBack: () -> Unit) {
    val b = r.best ?: return
    StepTitle("04", "하차점까지 안내 중이에요")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Hd.Navy).padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("차량 내비에서 길안내", color = Color(0xFF9FD8EA), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(msg ?: "", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("방향 안내는 Pleos 차량 내비 화면에 표시돼요", color = Color(0xFFB7C6D8), fontSize = 14.sp)
    }
    CardBox {
        val toDrop = (b.dropCompleteEpochSec ?: r.t0EpochSec) - r.t0EpochSec
        DetailRow("다음 · ★ 하차 지점", "약 ${mins(toDrop)}")
        DetailRow("그다음 · 운전자 목적지 ${vm.places[Role.DRIVER]?.label?.substringBefore(" · ") ?: ""}", "도착 ${hhmm(b.driverArrivalEpochSec)}")
    }
    opt?.let { o ->
        CardBox {
            Text("하차 후 동승자 경로 · ${o.kinds.joinToString("·") { it.label }}", color = Hd.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            o.itinerary.itinerary.legs.forEach { l -> Text("· " + legLabel(l), color = Hd.Sub, fontSize = 16.sp) }
            Text("동승자 도착 예상 ${hhmm(o.itinerary.arrivalEpochSec)}", color = Hd.Navy, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
    }
    Text("주행 중에는 입력·비교 화면이 잠겨요", color = Hd.Muted, fontSize = 14.sp)
    BigButton("추천 화면으로", primary = false, onClick = onBack)
}

@Composable
private fun DrivingBlock() {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Hd.WarnBg).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("주행 중이에요", color = Hd.Warn, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("안전을 위해 입력과 경로 비교는 정차 후에 할 수 있어요.", color = Hd.Text, fontSize = 19.sp)
    }
}


/** 하차 지점 도착 카드: 동승자 후속 경로(선택한 이동 방법). 닫아도 운전자 목적지 안내는 계속된다 */
@Composable
private fun DropArrivedCard(planned: RouteOption?, refreshed: List<RouteOption>?, note: String?, mod: Modifier, onRefresh: () -> Unit, onClose: () -> Unit) {
    Column(mod.width(460.dp).clip(RoundedCornerShape(26.dp)).background(Hd.Card).padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(Hd.Mint))
            Text("하차 지점 도착", color = Hd.Ink, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
        note?.let { Text(it, color = Hd.Purple, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        if (!refreshed.isNullOrEmpty()) {
            // 하차 시점 재계산 결과: 최소시간·최소환승·최소도보(같은 경로는 배지 병합)
            refreshed.forEach { o ->
                val i = o.itinerary.itinerary
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (RouteOptionKind.FASTEST in o.kinds) Hd.Ink else Hd.Bg).padding(12.dp)) {
                    val dark = RouteOptionKind.FASTEST in o.kinds
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        o.kinds.forEach { k -> Text(k.label, Modifier.clip(RoundedCornerShape(8.dp)).background(if (dark) Hd.Mint else Hd.SkySoft).padding(horizontal = 8.dp, vertical = 2.dp),
                            color = if (dark) Hd.Ink else Hd.Purple, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                    }
                    Text("도착 " + hhmm(o.itinerary.arrivalEpochSec) + " · 환승 ${i.transferCount ?: "?"}회 · 도보 ${minText(i.totalWalkTimeSec)}",
                        color = if (dark) Color.White else Hd.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text(i.legs.filter { it.mode != LegMode.WALK }.joinToString(" → ") { "${legKind(it.mode)} ${it.route ?: ""}".trim() },
                        color = if (dark) Color(0xFFB9BACB) else Hd.Sub, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        } else planned?.let { o ->
            Text("출발 전 계산: " + o.kinds.joinToString("·") { it.label } + " · 도착 예정 " + hhmm(o.itinerary.arrivalEpochSec), color = Hd.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            o.itinerary.itinerary.legs.forEach { l -> Text("· " + legLabel(l), color = Hd.Sub, fontSize = 15.sp) }
        } ?: Text("동승자 경로 정보 없음", color = Hd.Muted, fontSize = 15.sp)
        Text("운전자는 목적지로 안내를 이어가요 · 시간은 TMAP 예상(실시간 배차 대기 미반영)", color = Hd.Muted, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("지금 시각으로 다시 계산", false, Modifier.weight(1f), onClick = onRefresh)
            PillButton("확인", true, Modifier.weight(0.6f), onClick = onClose)
        }
    }
}

/** 03 동승자 이동 방법(목업 v3): 위 머리말 + 아래 가로 카드 3장. 선택한 카드는 어두운 카드 */
@Composable
private fun BoxScope.OptionsLayout(r: RecommendResult, kind: RouteOptionKind, onKind: (RouteOptionKind) -> Unit, vm: AppViewModel, navReady: Boolean,
                                    onNav: (Boolean, Boolean) -> Unit, onBack: () -> Unit, onGuide: (String) -> Unit) {
    val b = r.best ?: return
    val dropName = b.plan.label.replace(" 인근 경로상 하차", " 인근").replace("운전자 목적지까지 동승 후 이동", "운전자 목적지").replace("동승자 목적지 문 앞 경유", "출발")
    val destName = vm.places[Role.PASSENGER]?.label?.substringBefore(" · ")?.substringBefore(" (") ?: "동승자 목적지"
    // 머리말
    Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(start = 240.dp, end = 96.dp, top = 20.dp).clip(RoundedCornerShape(24.dp)).background(Hd.Card)
        .padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(1f)) {
            Text("$dropName  →  $destName", color = Hd.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (b.plan.type == CandidateType.P_VIA) "동승자 목적지 문 앞까지 함께 가요" else "하차 후 동승자 이동 방법 · 하차점 순위는 빠른 도착 기준", color = Hd.Sub, fontSize = 14.sp)
        }
        Text("← 하차점", Modifier.clip(RoundedCornerShape(14.dp)).background(Hd.Bg).clickable(onClick = onBack).padding(horizontal = 14.dp, vertical = 10.dp),
            color = Hd.Sub, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
    // 아래: 카드 3장 + 안내 버튼 줄
    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (b.plan.type == CandidateType.P_VIA) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Hd.Ink).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("문 앞 하차", Modifier.clip(RoundedCornerShape(10.dp)).background(Hd.Mint).padding(horizontal = 10.dp, vertical = 4.dp), color = Hd.Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text("도착 ${hhmm(b.passengerArrivalEpochSec)}", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Text("동승자 목적지 앞에서 내려요 · 대중교통·도보 이동 없음 · 운전자 우회 +${mins(b.driverAddSec?.toLong())}", color = Color(0xFFB9BACB), fontSize = 16.sp)
        } else Row(Modifier.fillMaxWidth().height(250.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val fastestArr = RouteOptions.pick(b.routeOptions, RouteOptionKind.FASTEST)?.itinerary?.arrivalEpochSec
            // 같은 경로가 여러 기준을 만족하면 카드 하나에 배지를 합친다(카드를 중복으로 만들지 않음)
            val cards: List<Pair<RouteOptionKind, RouteOption?>> = b.routeOptions.map { it.kinds.first() to it }.ifEmpty { listOf(RouteOptionKind.FASTEST to null) }
            cards.forEach { (k, o) ->
                val on = o != null && kind in o.kinds
                Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(24.dp)).background(if (on) Hd.Ink else Hd.Card)
                    .then(if (o != null) Modifier.clickable { onKind(k) } else Modifier).padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (o?.kinds ?: listOf(k)).forEach { kk ->
                            Text(kk.label, Modifier.clip(RoundedCornerShape(10.dp)).background(if (on) Hd.Mint else Hd.SkySoft).padding(horizontal = 10.dp, vertical = 4.dp),
                                color = if (on) Hd.Ink else Hd.Purple, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    val fg = if (on) Color.White else Hd.Text; val sub = if (on) Color(0xFFB9BACB) else Hd.Sub
                    val arr = o?.itinerary?.arrivalEpochSec
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(arr?.let { hhmm(it) } ?: "정보 없음", color = fg, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                        val diff = arr?.let { a -> fastestArr?.let { a - it } }
                        if (diff != null && diff > 0) Text("+${mins(diff)}", color = if (on) Hd.Mint else Hd.Purple, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                    o?.itinerary?.itinerary?.let { i ->
                        Text("환승 ${i.transferCount?.let { "${it}회" } ?: "?"} · 도보 ${minText(i.totalWalkTimeSec)}", color = sub, fontSize = 16.sp)
                        Text(i.legs.filter { it.mode != LegMode.WALK }.joinToString(" → ") { l -> "${legKind(l.mode)} ${l.route ?: ""}".trim() },
                            color = sub, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.weight(1f))
                    if (o != null) Text(if (on) "선택됨 ✓" else "이 경로 선택 →", color = if (on) Hd.Mint else Hd.Purple, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        // 안내 버튼 줄(정차 중에만 이 화면이 보인다)
        val decision = NavHandoff.decide(r, b)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Hd.Card).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (navReady && decision.allowed) {
                PillButton("이 경로로 안내 시작", true, Modifier.weight(1.3f)) { onNav(false, false) }
                PillButton("모의 주행", false, Modifier.weight(1f)) { onNav(true, false) }
            } else if (navReady) PillButton("모의 주행 미리보기(실제 안내 차단)", false, Modifier.weight(1.6f)) { onNav(true, true) }
            Box(Modifier.weight(1f)) { HandoffCompact(r, vm, onGuide) }
        }
        if (!decision.allowed) Text(decision.reasons.joinToString(" · "), color = Hd.Warn, fontSize = 14.sp,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Hd.WarnBg).padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

@Composable
private fun PillButton(text: String, primary: Boolean, mod: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(mod.height(58.dp).clip(RoundedCornerShape(18.dp)).background(if (!enabled) Color(0xFFDCDBE4) else if (primary) Hd.Purple else Hd.SkySoft)
        .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier), contentAlignment = Alignment.Center) {
        Text(text, color = if (primary) Color.White else Hd.Purple, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

/** Pleos 차량 내비로 보내기(작은 버튼) */
@Composable
private fun HandoffCompact(r: RecommendResult, vm: AppViewModel, onGuide: (String) -> Unit) {
    val ctx = LocalContext.current
    val bridge = remember { createNaviBridge(ctx) }
    DisposableEffect(bridge) { onDispose { bridge.close() } }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val b = r.best ?: return
    val decision = NavHandoff.decide(r, b)
    val dest = vm.places[Role.DRIVER]
    PillButton(if (busy) "보내는 중…" else "Pleos 내비로", false, Modifier.fillMaxWidth(), enabled = decision.allowed && bridge.available && !busy && dest != null) {
        busy = true
        scope.launch {
            val dropName = b.plan.label.replace(" 인근 경로상 하차", " 하차점").replace("운전자 목적지까지 동승 후 이동", "운전자 목적지")
            runCatching { bridge.startDropRoute(dropName, b.plan.dropPoint, dest!!.label.substringBefore(" · ").substringBefore(" ("), r.driverDestFor(b) ?: dest.pos, decision) }
                .onSuccess { onGuide(it) }.onFailure { vm.message = it.message ?: "전달 실패" }
            busy = false
        }
    }
}

/** '내 위치로' 버튼(Quiet Companion v3): 남색 원형 56dp, 보라 조준점 아이콘. 운전 중 누르기 쉬운 크기 */
@Composable
private fun RecenterButton(modifier: Modifier, onClick: () -> Unit) {
    androidx.compose.foundation.Canvas(modifier.size(56.dp).clip(CircleShape).background(Hd.Ink).clickable(onClick = onClick)
        .semantics { contentDescription = "내 위치로" }) {
        val c = center; val r = size.minDimension * 0.22f
        val w = 3.dp.toPx()
        drawCircle(Hd.Purple, radius = r, center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = w))
        drawCircle(Hd.Purple, radius = r * 0.35f, center = c)
        val l = size.minDimension * 0.34f
        listOf(androidx.compose.ui.geometry.Offset(0f, -1f), androidx.compose.ui.geometry.Offset(0f, 1f), androidx.compose.ui.geometry.Offset(-1f, 0f), androidx.compose.ui.geometry.Offset(1f, 0f)).forEach { d ->
            drawLine(Hd.Purple, c + d * r, c + d * l, strokeWidth = w)
        }
    }
}

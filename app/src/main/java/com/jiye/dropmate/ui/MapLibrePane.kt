package com.jiye.dropmate.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.jiye.dropmate.engine.*
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory as PF
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.android.geometry.LatLng as MLatLng

/**
 * 3D 건물 지도(2026-11-12 사용자 승인: MapLibre 오픈소스 SDK + OpenFreeMap 무료 벡터 지도, 키·요금 없음).
 * OpenFreeMap 'liberty' 스타일의 building-3d(fill-extrusion, OSM render_height) 레이어로 건물을 입체로 세운다.
 * 건물 높이는 OSM 데이터 수준이며, 높이 정보가 없는 건물은 스타일 기본 높이로 보인다.
 * 경로·마커·안내 표시는 이 지도의 GeoJSON 레이어로 그린다(추천 알고리즘·TMAP 호출과 무관).
 */
const val OPENFREEMAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"

object MlHandle {
    private var ref: java.lang.ref.WeakReference<MapLibreMap>? = null
    /** 2026-11-13: 사용자가 손으로 지도를 옮기면 true → 안내 중 차 따라가기를 멈추고 '내 위치로' 버튼을 보인다 */
    var followPaused by mutableStateOf(false)
    fun set(m: MapLibreMap) { ref = java.lang.ref.WeakReference(m) }
    fun zoomIn() { ref?.get()?.animateCamera(CameraUpdateFactory.zoomIn()) }
    fun zoomOut() { ref?.get()?.animateCamera(CameraUpdateFactory.zoomOut()) }
    /** '내 위치로': 지도를 옮겨 보다가 누르면 차 위치(없으면 출발지)로 카메라를 되돌린다. 왼쪽 떠 있는 패널(폭 42%)에 가리지 않게 */
    fun recenter(p: LatLng) {
        followPaused = false
        val m = ref?.get() ?: return
        m.cancelTransitions()
        m.easeCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(p.ml()).zoom(maxOf(m.cameraPosition.zoom, 15.0))
            .tilt(0.0).bearing(0.0).padding(m.width * 0.42, 0.0, 0.0, 0.0).build()), 500)
    }
}

private fun LatLng.ml() = MLatLng(lat, lon)
private fun hex(argb: Int) = String.format("#%06X", argb and 0xFFFFFF)

private fun lineF(ptsIn: List<LatLng>, color: Int, width: Float): Feature? {
    // 같은 점이 연속되거나 길이가 거의 0인 선은 에뮬레이터 GL 드라이버에서 그리기 오류(SIGSEGV)를 낸 적이 있어 정리한다
    val pts = ArrayList<LatLng>(ptsIn.size)
    ptsIn.forEach { p -> if (p.lat.isFinite() && p.lon.isFinite() && (pts.isEmpty() || Geo.haversineM(pts.last(), p) > 0.5)) pts += p }
    if (pts.size < 2) return null
    val f = Feature.fromGeometry(LineString.fromLngLats(pts.map { Point.fromLngLat(it.lon, it.lat) }))
    f.addStringProperty("color", hex(color)); f.addNumberProperty("width", width)
    return f
}

private fun pointF(p: LatLng, icon: String, sortKey: Int = 0): Feature {
    val f = Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat))
    f.addStringProperty("icon", icon); f.addNumberProperty("z", sortKey)
    return f
}

/** 원 + 글자 핀 */
private fun pinBitmap(d: Float, color: Int, label: String): Bitmap {
    val px = (34 * d).toInt()
    val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = android.graphics.Color.WHITE; c.drawCircle(px / 2f, px / 2f, px / 2f, p)
    p.color = color; c.drawCircle(px / 2f, px / 2f, px / 2f - px * 0.1f, p)
    p.color = android.graphics.Color.WHITE; p.textSize = px * 0.5f; p.textAlign = Paint.Align.CENTER; p.isFakeBoldText = true
    c.drawText(label, px / 2f, px / 2f - (p.descent() + p.ascent()) / 2f, p)
    return bmp
}

/** 둥근 말풍선 라벨(아래쪽 꼬리) */
private fun bubbleBitmap(d: Float, text: String, bg: Int, fg: Int, tail: Boolean = true, textSp: Float = 13f): Bitmap {
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textSize = textSp * d; textAlign = Paint.Align.CENTER }
    val w = (p.measureText(text) + 24f * d).toInt(); val bh = (32f * d).toInt(); val h = bh + if (tail) (9f * d).toInt() else 0
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(bmp)
    val bgP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg }
    c.drawRoundRect(0f, 0f, w.toFloat(), bh.toFloat(), bh / 2f, bh / 2f, bgP)
    if (tail) c.drawPath(Path().apply { moveTo(w / 2f - 8 * d, bh - 1f); lineTo(w / 2f, h.toFloat()); lineTo(w / 2f + 8 * d, bh - 1f); close() }, bgP)
    p.color = fg
    c.drawText(text, w / 2f, bh / 2f - (p.ascent() + p.descent()) / 2, p)
    return bmp
}

/** 내 차 화살표(지도 회전과 함께 돌도록 iconRotate=bearing, map 정렬) */
private fun carBitmap(d: Float): Bitmap {
    val s = (60 * d).toInt()
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888); val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = 0x336552D9; c.drawCircle(s / 2f, s / 2f, s * 0.48f, p)
    p.color = android.graphics.Color.WHITE; c.drawCircle(s / 2f, s / 2f, s * 0.36f, p)
    p.color = 0xFF6552D9.toInt()
    c.drawPath(Path().apply { moveTo(s / 2f, s * 0.16f); lineTo(s * 0.76f, s * 0.78f); lineTo(s / 2f, s * 0.62f); lineTo(s * 0.24f, s * 0.78f); close() }, p)
    return bmp
}

@Composable
fun MapLibrePane(
    modifier: Modifier, places: Map<Role, Place>, result: RecommendResult?, picking: Role?, hits: List<Place>,
    onHit: (Place) -> Unit, passengerLegs: List<TransitLeg>?, vehicle: LatLng?, nav: NavView?, onPick: (LatLng) -> Unit,
) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        MapLibre.getInstance(ctx.applicationContext)
        // TextureView 모드: SurfaceView 는 Compose 패널·버튼 위로 그려져 화면을 가리므로 사용하지 않는다
        MapView(ctx, org.maplibre.android.maps.MapLibreMapOptions.createFromAttributes(ctx).textureMode(true)).also { it.onCreate(null) }
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    val pickRef = rememberUpdatedState(picking); val onPickRef = rememberUpdatedState(onPick)
    val hitsRef = rememberUpdatedState(hits); val onHitRef = rememberUpdatedState(onHit)

    DisposableEffect(lifecycle, mapView) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart(); Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause(); Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose { lifecycle.removeObserver(obs); mapView.onPause(); mapView.onStop(); mapView.onDestroy() }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            map = m; MlHandle.set(m)
            m.uiSettings.isCompassEnabled = false
            m.uiSettings.isAttributionEnabled = true; m.uiSettings.isLogoEnabled = false
            m.cameraPosition = CameraPosition.Builder().target(MLatLng(37.5326, 126.99)).zoom(11.0).build()
            // 손 제스처로 카메라가 움직이면 따라가기 멈춤(앱이 옮기는 카메라는 해당 없음)
            m.addOnCameraMoveStartedListener { reason -> if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) MlHandle.followPaused = true }
            m.addOnMapClickListener { p ->
                val pt = LatLng(p.latitude, p.longitude)
                // 검색 결과 핀 근처를 누르면 그 장소로 확정
                val near = hitsRef.value.minByOrNull { Geo.haversineM(it.pos, pt) }
                if (near != null && Geo.haversineM(near.pos, pt) < 40.0 * Math.pow(2.0, 15 - m.cameraPosition.zoom)) { onHitRef.value(near); return@addOnMapClickListener true }
                if (pickRef.value != null) { onPickRef.value(pt); true } else false
            }
            m.setStyle(Style.Builder().fromUri(OPENFREEMAP_STYLE)) { st ->
                val d = ctx.resources.displayMetrics.density
                // 입체 건물: 스타일의 building-3d 를 더 진하게(차량 화면 가독성), 없으면 직접 추가
                val b3d = st.getLayer("building-3d") as? FillExtrusionLayer ?: FillExtrusionLayer("building-3d", "openmaptiles").withSourceLayer("building")
                    .withProperties(PF.fillExtrusionHeight(Expression.get("render_height")), PF.fillExtrusionBase(Expression.get("render_min_height"))).also { st.addLayer(it) }
                b3d.setProperties(PF.fillExtrusionColor("#D9DEE6"), PF.fillExtrusionOpacity(0.9f), PF.fillExtrusionVerticalGradient(true))
                b3d.minZoom = 14f
                listOf("routes", "routes-dash", "nav-passed", "nav-ahead", "marks").forEach { st.addSource(GeoJsonSource(it)) }
                fun line(id: String, src: String, dash: Boolean) = LineLayer(id, src).withProperties(
                    PF.lineColor(Expression.toColor(Expression.get("color"))), PF.lineWidth(Expression.get("width")),
                    PF.lineCap(Property.LINE_CAP_ROUND), PF.lineJoin(Property.LINE_JOIN_ROUND)).also { if (dash) it.setProperties(PF.lineDasharray(arrayOf(1.2f, 1.4f))) }
                st.addLayer(line("routes-l", "routes", false))
                st.addLayer(line("routes-dash-l", "routes-dash", true))
                st.addLayer(line("nav-passed-l", "nav-passed", false))
                st.addLayer(line("nav-ahead-l", "nav-ahead", false))
                st.addLayer(SymbolLayer("marks-l", "marks").withProperties(
                    PF.iconImage(Expression.get("icon")), PF.iconAllowOverlap(true), PF.iconIgnorePlacement(true),
                    PF.iconAnchor(Property.ICON_ANCHOR_BOTTOM), PF.symbolSortKey(Expression.get("z")),
                    PF.iconRotate(Expression.coalesce(Expression.get("rot"), Expression.literal(0))),
                    PF.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP), PF.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT)))
                // 고정 아이콘
                st.addImage("pin-O", pinBitmap(d, android.graphics.Color.rgb(66, 165, 245), "O"))
                st.addImage("pin-D", pinBitmap(d, android.graphics.Color.rgb(239, 83, 80), "D"))
                st.addImage("pin-P", pinBitmap(d, android.graphics.Color.rgb(102, 187, 106), "P"))
                st.addImage("pin-hit", pinBitmap(d, android.graphics.Color.rgb(171, 71, 188), "?"))
                st.addImage("drop", bubbleBitmap(d, "1  추천 하차점", 0xFF6552D9.toInt(), android.graphics.Color.WHITE, textSp = 16f))
                st.addImage("drop-nav", bubbleBitmap(d, "하차점", 0xFF252737.toInt(), 0xFFC5EDBB.toInt(), textSp = 16f))
                st.addImage("car", carBitmap(d))
                st.addImage("mycar", bubbleBitmap(d, "내 차", 0xFF252737.toInt(), android.graphics.Color.WHITE))
                style = st
            }
        }
    }

    // 데이터 → 레이어
    val st = style; val m = map
    // 안내 중 0.5초마다 오는 갱신에서 경로선·이미지를 매번 다시 올리면 에뮬레이터(SwiftShader) TextureView 렌더 스레드가 SIGSEGV 로 죽었다(2026-11-12).
    // 안내 중에는 차 위치(마커)만 매번, 경로선은 2초에 한 번만 다시 올린다.
    val lastLineMs = remember { longArrayOf(0L) }
    val wasNav = remember { booleanArrayOf(false) }
    LaunchedEffect(st, places, result, hits, passengerLegs, vehicle, nav) {
        if (st == null || m == null) return@LaunchedEffect
        val d = ctx.resources.displayMetrics.density
        val solid = ArrayList<Feature>(); val dash = ArrayList<Feature>(); val marks = ArrayList<Feature>()
        val passed = ArrayList<Feature>(); val ahead = ArrayList<Feature>()
        var imgN = 0
        // 이전 갱신에서 만든 말풍선 이미지는 지운다(갱신마다 텍스처가 쌓이지 않게)
        if (nav == null) (0 until 64).forEach { k -> runCatching { st.removeImage("b$k") } }
        val now = android.os.SystemClock.uptimeMillis()
        val pushLines = nav == null || !wasNav[0] || now - lastLineMs[0] >= 2000
        if (pushLines) lastLineMs[0] = now
        fun bubble(p: LatLng, text: String, bg: Int, z: Int) {
            if (imgN >= 64) return
            val id = "b${imgN++}"
            val lum = android.graphics.Color.red(bg) * 0.299 + android.graphics.Color.green(bg) * 0.587 + android.graphics.Color.blue(bg) * 0.114
            st.addImage(id, bubbleBitmap(d, text.take(28), bg, if (lum > 160) 0xFF17212E.toInt() else android.graphics.Color.WHITE))
            marks += pointF(p, id, z)
        }
        if (nav != null) {
            if (pushLines) lineF(nav.passed, 0xFFAEB8C2.toInt(), 9f)?.let { passed += it }
            // 목업 v3: 흰 테두리 18px 위에 보라 10px 경로. 정체(지체·정체) 구간만 위에 붉게 얹는다
            if (pushLines) {
                lineF(nav.ahead, android.graphics.Color.WHITE, 18f)?.let { ahead += it }
                lineF(nav.ahead, 0xFF6552D9.toInt(), 10f)?.let { ahead += it }
            }
            if (pushLines) nav.aheadTraffic.filter { it.second >= 3 }.forEach { (pts, c) -> lineF(pts, trafficArgb(c), 6f)?.let { ahead += it } }
            nav.drop?.let { marks += pointF(it, "drop-nav", 5) }
            nav.dest?.let { marks += pointF(it, "pin-D", 4) }
            val car = pointF(nav.car, "car", 10); car.addNumberProperty("rot", nav.bearing); marks += car
        } else {
            val best = result?.best
            if (result != null) {
                if (best == null) result.baseline?.let { lineF(it.points, CONTINUE_CAR_COLOR, 6f)?.let { f -> dash += f } }
                else {
                    val cs = selectedCarShapes(result)
                    lineF(cs.afterDrop, CONTINUE_CAR_COLOR, 6f)?.let { dash += it }
                    lineF(cs.toDrop, android.graphics.Color.WHITE, 14f)?.let { solid += it }
                    lineF(cs.toDrop, 0xFF6552D9.toInt(), 8f)?.let { solid += it }
                    (passengerLegs ?: best.chosen?.itinerary?.legs).orEmpty().forEachIndexed { i, leg ->
                        lineF(leg.shape, legArgb(leg), 7f)?.let { if (leg.mode == LegMode.WALK) dash += it else solid += it }
                        leg.start?.let { if (leg.mode != LegMode.WALK) bubble(it, "${i + 1}. ${legKind(leg.mode)} ${leg.route ?: ""}".trim(), legArgb(leg), 2) }
                    }
                    result.dropRanking().filter { it.rank != null && it.candidate.plan.id != best.plan.id }.forEach { r ->
                        bubble(r.candidate.plan.dropPoint, "${r.rank}  다른 하차점", android.graphics.Color.WHITE, 1)
                    }
                    marks += pointF(best.plan.dropPoint, "drop", 6)
                }
            }
            val tag = mapOf(Role.ORIGIN to "pin-O", Role.DRIVER to "pin-D", Role.PASSENGER to "pin-P")
            // 2026-11-13: D 마커는 엔진이 고른 운전자 입구(내비 안내 목적지와 같은 좌표)
            val dPos = result?.driverDestFor(result.best)
            places.forEach { (r, p) -> marks += pointF(if (r == Role.DRIVER && dPos != null) dPos else p.pos, tag.getValue(r), 4) }
            hits.forEach { marks += pointF(it.pos, "pin-hit", 3) }
            vehicle?.let { marks += pointF(it, "mycar", 8) }
        }
        if (nav == null || !wasNav[0]) {
            st.getSourceAs<GeoJsonSource>("routes")?.setGeoJson(FeatureCollection.fromFeatures(solid))
            st.getSourceAs<GeoJsonSource>("routes-dash")?.setGeoJson(FeatureCollection.fromFeatures(dash))
        }
        if (pushLines) {
            st.getSourceAs<GeoJsonSource>("nav-passed")?.setGeoJson(FeatureCollection.fromFeatures(passed))
            st.getSourceAs<GeoJsonSource>("nav-ahead")?.setGeoJson(FeatureCollection.fromFeatures(ahead))
        }
        st.getSourceAs<GeoJsonSource>("marks")?.setGeoJson(FeatureCollection.fromFeatures(marks))
        wasNav[0] = nav != null
    }

    // 카메라: 안내 중이면 차를 따라감(3D: 진행 방향 위 + 60° 기울임), 아니면 결과·입력 전체가 보이게
    val focusKey = if (nav != null) null else "${result?.requestId}|${places.hashCode()}|${hits.hashCode()}"
    LaunchedEffect(m, st, focusKey) {
        if (m == null || st == null || nav != null) return@LaunchedEffect
        val pts = if (hits.isNotEmpty()) hits.map { it.pos } else places.values.map { it.pos } +
            (result?.let { r -> selectedCarShapes(r).let { it.toDrop + it.afterDrop }.ifEmpty { r.baseline?.points.orEmpty() } } ?: emptyList())
        when {
            pts.isEmpty() -> {}
            pts.size == 1 -> m.easeCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(pts[0].ml()).zoom(15.0).tilt(0.0).bearing(0.0)
                .padding(mapView.width * 0.42, 0.0, 0.0, 0.0).build()), 600)
            else -> {
                val b = LatLngBounds.Builder().apply { pts.forEach { include(it.ml()) } }.build()
                val pad = (60 * ctx.resources.displayMetrics.density).toInt()
                // 왼쪽 떠 있는 패널에 가리지 않도록 왼쪽 여백을 크게
                val cam = m.getCameraForLatLngBounds(b, intArrayOf((mapView.width * 0.42).toInt(), pad, pad, pad), 0.0, 0.0)
                if (cam != null) m.easeCamera(CameraUpdateFactory.newCameraPosition(cam), 700)
            }
        }
    }
    LaunchedEffect(m, nav, MlHandle.followPaused) {
        if (m == null || nav == null || MlHandle.followPaused) return@LaunchedEffect
        val cam = if (nav.tilt) CameraPosition.Builder().target(nav.car.ml()).zoom(17.0).bearing(nav.bearing).tilt(55.0)
            // 위쪽 여백을 크게 줘서 차가 화면 아래쪽에 오고 앞길이 넓게 보이게
            .padding(0.0, mapView.height * 0.42, 0.0, 0.0).build()
        else CameraPosition.Builder().target(nav.car.ml()).zoom(16.5).bearing(0.0).tilt(0.0).padding(0.0, 0.0, 0.0, 0.0).build()
        // 0.5초마다 오는 위치 갱신에 애니메이션을 겹쳐 걸면 에뮬레이터(SwiftShader)에서 GL 오류로 앱이 종료된 적이 있어 즉시 이동으로
        m.cancelTransitions()
        m.moveCamera(CameraUpdateFactory.newCameraPosition(cam))
    }

    AndroidView(modifier = modifier, factory = { mapView })
}

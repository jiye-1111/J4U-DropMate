package com.jiye.dropmate.ui

import android.graphics.DashPathEffect
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.jiye.dropmate.engine.*
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

private fun LatLng.gp() = GeoPoint(lat, lon)

/**
 * 어두운 지도: API 키가 필요 없는 OpenStreetMap 기본 타일에 색 반전+색상 회전(invert + hue-rotate 180°)을 적용해
 * 물·녹지의 색 계열은 유지하면서 배경만 어둡게 만든다. (© OpenStreetMap contributors)
 * CARTO 등 키가 필요한 타일 서버는 쓰지 않는다 — 키가 없으면 "API KEY REQUIRED" 타일이 나온다.
 */
private val DARK_FILTER = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(floatArrayOf(
    0.574f, -1.430f, -0.144f, 0f, 255f,
    -0.426f, -0.430f, -0.144f, 0f, 255f,
    -0.426f, -1.430f, 0.856f, 0f, 255f,
    0f, 0f, 0f, 1f, 0f,
)))

/**
 * 밝은 지도(2026-11-12: 흰 지도 대신 카카오·TMAP·네이버 지도처럼 색이 있는 지도).
 * OSM 기본 타일 색(녹지·물·도로 색)을 거의 그대로 쓰고 채도만 살짝 낮춰 차량 화면에서 덜 번쩍이게 한다. 새 외부 서비스·키 없음.
 */
private val LIGHT_FILTER = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply {
    setSaturation(0.9f)
})

/** 차량 현재 위치(Pleos). 있으면 '내 차' 표식을 그린다 */
val LocalVehiclePos = androidx.compose.runtime.staticCompositionLocalOf<LatLng?> { null }

/** true 이면 MapPane 이 밝은 지도 필터를 쓴다(새 차량 화면). 기존 화면은 어두운 필터 유지 */
val LocalMapLight = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * 선택 경로: 차량 동승은 파란색, 하차 후 운전자는 회색 점선.
 * 별표는 하차점, 번호는 동승자 이동 순서다.
 * 대중교통은 노선별 색상, 도보는 노란 점선.
 * 선을 누르면 "도보 11분", "지하철 2호선 14분" 같은 종류·소요시간이 표시된다.
 * 같은 요청ID 결과만 그린다(result 는 ViewModel 의 LatestResultGate 를 통과한 값).
 */
@Composable
fun MapPane(modifier: Modifier, places: Map<Role, Place>, result: RecommendResult?, picking: Role?, hits: List<Place>, onHit: (Place) -> Unit,
            /** 동승자 경로 탭에서 고른 여정의 구간. null 이면 ★ 하차점의 최소시간 여정 */
            passengerLegs: List<TransitLeg>? = null, onPick: (LatLng) -> Unit) {
    // 터치 두 손가락 핀치 확대/축소는 MapView 의 멀티터치 컨트롤이 그대로 처리한다.
    val light = LocalMapLight.current
    val vehicle = LocalVehiclePos.current
    val navV = LocalNavView.current
    AndroidView(modifier = modifier, factory = { ctx ->
        org.osmdroid.config.Configuration.getInstance().apply {
            userAgentValue = ctx.packageName
            // 외부 저장소 권한 없이 앱 전용 캐시 사용(차량 OS 호환)
            osmdroidBasePath = java.io.File(ctx.cacheDir, "osmdroid").apply { mkdirs() }
            osmdroidTileCache = java.io.File(osmdroidBasePath, "tiles").apply { mkdirs() }
        }
        object : MapView(ctx) {
            override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean {
                if (event.actionMasked == android.view.MotionEvent.ACTION_SCROLL &&
                    MapHandle.wheelLocal(this, event.x, event.y,
                        event.getAxisValue(android.view.MotionEvent.AXIS_VSCROLL))) return true
                return super.dispatchGenericMotionEvent(event)
            }
        }.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            overlayManager.tilesOverlay.setColorFilter(if (light) LIGHT_FILTER else DARK_FILTER)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(12.0)
            controller.setCenter(GeoPoint(37.5326, 126.99))
            MapHandle.set(this)
        }
    }, update = { map ->
        map.overlays.clear()
        map.overlays += MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean { if (picking != null) { onPick(LatLng(p.latitude, p.longitude)); return true }; return false }
            override fun longPressHelper(p: GeoPoint) = false
        })
        // 앱 안 길안내: 지난 경로(회색)·남은 경로(파랑)·하차 지점·목적지·차 화살표, 차를 따라 지도 이동
        if (navV != null) {
            drawRoute(map, navV.passed, 0xFFAEB8C2.toInt(), "지난 경로", false, 10f)
            if (navV.aheadTraffic.isEmpty()) drawRoute(map, navV.ahead, 0xFF1F6FD1.toInt(), "남은 경로", false, 13f)
            else {
                // 혼잡도 색 아래에 남색 테두리를 깔아 밝은 지도에서도 경로가 또렷하게
                drawRoute(map, navV.ahead, 0xFF002C5F.toInt(), "남은 경로", false, 17f)
                navV.aheadTraffic.forEach { (pts, c) -> drawRoute(map, pts, trafficArgb(c), "남은 경로", false, 12f) }
            }
            navV.drop?.let { map.overlays += stepMarker(map, it, "★", "★ 하차 지점", DROP_COLOR) }
            navV.dest?.let { map.overlays += stepMarker(map, it, "D", "운전자 목적지", android.graphics.Color.rgb(239, 83, 80)) }
            // 헤딩업(3D 보기): 지도를 진행 방향으로 돌리면 차 화살표는 화면 위쪽(0°)을 향한다
            map.mapOrientation = if (navV.tilt) (-navV.bearing).toFloat() else 0f
            map.overlays += carMarker(map, navV.car, if (navV.tilt) 0.0 else navV.bearing)
            val wantTag = if (navV.tilt) "nav3d" else "nav"
            if (map.tag != wantTag) { map.tag = wantTag; map.controller.setZoom(if (navV.tilt) 17.5 else 17.0) }
            map.controller.setCenter((navV.lookAhead ?: navV.car).gp())
            map.invalidate()
            return@AndroidView
        }
        if (map.mapOrientation != 0f) map.mapOrientation = 0f
        val best = result?.best
        val carShapes = result?.let { selectedCarShapes(it) }
        if (best == null) {
            result?.baseline?.let { drawRoute(map, it.points, CONTINUE_CAR_COLOR, "비교 기준 · 운전자 직행", true, 4f) }
        } else {
            drawRoute(map, carShapes!!.afterDrop, CONTINUE_CAR_COLOR, "하차 후 운전자 목적지로", true, 4f)
            drawRoute(map, carShapes.toDrop, SHARED_CAR_COLOR, "함께 차량 이동 → ★ 하차", false, 7f)
            (passengerLegs ?: best.chosen?.itinerary?.legs)?.forEachIndexed { index, leg ->
                // 형상 누락 시 직선을 실제 도보/노선으로 꾸며 그리지 않는다.
                drawRoute(map, leg.shape, legArgb(leg), "${index + 1}. ${legLabel(leg)} · ${leg.startName} → ${leg.endName}", leg.mode == LegMode.WALK, 6f)
                leg.start?.let { point ->
                    map.overlays += stepMarker(map, point, "${index + 1}",
                        "${index + 1}. ${legLabel(leg)} · ${leg.startName ?: "출발"} → ${leg.endName ?: "도착"}", legArgb(leg))
                }
            }
        }
        val colors = mapOf(Role.ORIGIN to android.graphics.Color.rgb(66, 165, 245), Role.DRIVER to android.graphics.Color.rgb(239, 83, 80), Role.PASSENGER to android.graphics.Color.rgb(102, 187, 106))
        val tag = mapOf(Role.ORIGIN to "O", Role.DRIVER to "D", Role.PASSENGER to "P")
        places.forEach { (r, p) ->
            map.overlays += Marker(map).apply {
                position = p.pos.gp(); title = "${tag[r]} ${r.title}"; snippet = p.label
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = pin(map, colors[r]!!, tag[r]!!)
            }
        }
        hits.forEach { h ->
            map.overlays += Marker(map).apply {
                position = h.pos.gp(); title = h.label; snippet = "눌러서 확정"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = pin(map, android.graphics.Color.rgb(171, 71, 188), "?")
                setOnMarkerClickListener { _, _ -> onHit(h); true }
            }
        }
        // 비교한 다른 하차점(순위 대상만)은 회색 표식. 선택(★) 표식이 위에 오도록 먼저 그린다.
        result?.dropRanking()?.filter { it.rank != null && it.candidate.plan.id != best?.plan?.id }?.forEach { d ->
            val c = d.candidate
            map.overlays += stepMarker(map, c.plan.dropPoint, "${d.rank}",
                "${d.rank}위 ${c.passengerArrivalEpochSec?.let { minText((it - result.t0EpochSec).toInt()) } ?: "미확인"} · ${c.plan.label}",
                android.graphics.Color.rgb(120, 132, 148))
        }
        vehicle?.let { v -> map.overlays += stepMarker(map, v, "내 차", "내 차 · Pleos 차량 위치", 0xFF002C5F.toInt()) }
        best?.let { map.overlays += dropMarker(map, it) }
        // 선택된 경로 전체와 하차점을 함께 보여 준다.
        val pts = if (hits.isNotEmpty()) hits.map { it.pos.gp() }
            else places.values.map { it.pos.gp() } +
                (carShapes?.let { it.toDrop + it.afterDrop } ?: result?.baseline?.points.orEmpty()).map { it.gp() } +
                (passengerLegs ?: best?.chosen?.itinerary?.legs).orEmpty().flatMap { it.shape }.map { it.gp() } +
                listOfNotNull(best?.plan?.dropPoint?.gp())
        val focusKey = "${result?.requestId}|${places.hashCode()}|${hits.hashCode()}"
        if (pts.isNotEmpty() && map.tag != focusKey) {
            map.tag = focusKey
            map.post {
                runCatching {
                    if (pts.size == 1) { map.controller.setZoom(16.0); map.controller.setCenter(pts[0]) }
                    else map.zoomToBoundingBox(BoundingBox.fromGeoPoints(pts).increaseByScale(1.3f), false)
                }
            }
        }
        map.invalidate()
    })
}

/**
 * 화면에 떠 있는 지도에 대한 약한 참조. 확대/축소 버튼이 이 지도를 조작한다.
 */
object MapHandle {
    private var ref: java.lang.ref.WeakReference<MapView>? = null
    fun set(m: MapView) { ref = java.lang.ref.WeakReference(m) }

    fun zoomIn() { ref?.get()?.controller?.zoomIn() }
    fun zoomOut() { ref?.get()?.controller?.zoomOut() }

    // 고정 동작: 휠만 줌, 좌클릭 드래그는 MapView 기본 이동.
    fun wheelLocal(m: MapView, x: Float, y: Float, vscroll: Float): Boolean {
        if (!m.isShown || !x.isFinite() || !y.isFinite() || !vscroll.isFinite() || vscroll == 0f) return false
        if (x < 0f || y < 0f || x >= m.width || y >= m.height) return false
        val visible = android.graphics.Rect()
        if (!m.getLocalVisibleRect(visible) || !visible.contains(x.toInt(), y.toInt())) return false
        m.controller.stopAnimation(true)
        val target = (m.zoomLevelDouble + vscroll.coerceIn(-1f, 1f).toDouble())
            .coerceIn(m.minZoomLevel, m.maxZoomLevel)
        m.controller.zoomToFixing(target, x.toInt(), y.toInt(), 0L)
        return true
    }


}

/** 색 원 + 글자(O/D/P/?) 마커 아이콘. */
private fun pin(map: MapView, color: Int, label: String): android.graphics.drawable.Drawable {
    val px = (34 * map.resources.displayMetrics.density).toInt()
    val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    p.color = android.graphics.Color.WHITE; c.drawCircle(px / 2f, px / 2f, px / 2f, p)
    p.color = color; c.drawCircle(px / 2f, px / 2f, px / 2f - px * 0.1f, p)
    p.color = android.graphics.Color.WHITE; p.textSize = px * 0.5f; p.textAlign = android.graphics.Paint.Align.CENTER; p.isFakeBoldText = true
    c.drawText(label, px / 2f, px / 2f - (p.descent() + p.ascent()) / 2f, p)
    return android.graphics.drawable.BitmapDrawable(map.resources, bmp)
}

package com.jiye.dropmate.ui

import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import com.jiye.dropmate.engine.*
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

const val SHARED_CAR_COLOR: Int = 0xFF4DA6FF.toInt()
const val CONTINUE_CAR_COLOR: Int = 0xFF78899B.toInt()
const val DROP_COLOR: Int = 0xFFFFD166.toInt()

data class SelectedCarShapes(val toDrop: List<LatLng>, val afterDrop: List<LatLng>)

/** 조회한 실제 도로 형상만 사용. 중간 하차는 직행 선을 하차점에서 분할한다. */
fun selectedCarShapes(r: RecommendResult): SelectedCarShapes {
    val best = r.best ?: return SelectedCarShapes(emptyList(), emptyList())
    val carToDrop = best.carToDrop
    if (carToDrop != null) return SelectedCarShapes(carToDrop.points, best.carAfterDrop?.points.orEmpty())
    val base = r.baseline?.points.orEmpty()
    if (best.plan.type == CandidateType.D_DROP) return SelectedCarShapes(base, emptyList())
    if (best.plan.type != CandidateType.MIDDLE || base.size < 2)
        return SelectedCarShapes(emptyList(), emptyList())
    val projection = Geo.projectOnPolyline(best.plan.dropPoint, base)
    return SelectedCarShapes(
        base.take(projection.segIndex + 1) + projection.point,
        listOf(projection.point) + base.drop(projection.segIndex + 1)
    )
}

fun drawRoute(map: MapView, points: List<LatLng>, color: Int, titleText: String, dashed: Boolean = false, width: Float = 7f) {
    if (points.size < 2) return
    val density = map.resources.displayMetrics.density
    for (halo in listOf(true, false)) {
        map.overlays += Polyline(map).apply {
            setPoints(points.map { GeoPoint(it.lat, it.lon) })
            outlinePaint.color = if (halo) 0xE6111824.toInt() else color
            outlinePaint.strokeWidth = (width + if (halo) 3f else 0f) * density
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
            if (dashed) outlinePaint.pathEffect = DashPathEffect(floatArrayOf(9f * density, 7f * density), 0f)
            title = titleText
        }
    }
}

/** 별 라벨은 지리 좌표에 붙이며 P/D 마커보다 위에 그린다. */
fun dropMarker(map: MapView, best: CandidateResult): Marker {
    val density = map.resources.displayMetrics.density
    val w = (168 * density).toInt()
    val h = (58 * density).toInt()
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = DROP_COLOR
    canvas.drawRoundRect(2f * density, 2f * density, w - 2f * density, h - 12f * density, 14f * density, 14f * density, paint)
    val tip = Path().apply {
        moveTo(w / 2f - 9f * density, h - 13f * density)
        lineTo(w / 2f, h.toFloat())
        lineTo(w / 2f + 9f * density, h - 13f * density)
        close()
    }
    canvas.drawPath(tip, paint)
    paint.color = 0xFF17212E.toInt()
    paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    paint.textSize = 16f * density
    paint.textAlign = Paint.Align.CENTER
    canvas.drawText("★ 여기서 하차", w / 2f, 30f * density, paint)
    return Marker(map).apply {
        position = GeoPoint(best.plan.dropPoint.lat, best.plan.dropPoint.lon)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        icon = BitmapDrawable(map.resources, bitmap)
        title = "★ " + best.plan.label
        snippet = "하차 완료 " + (best.dropCompleteEpochSec?.let { TimeUtil.hms(it) } ?: "미확인") +
            " · " + stopSuitabilityText(best.plan.stopSuitability)
    }
}

fun stepMarker(map: MapView, point: LatLng, label: String, titleText: String, color: Int): Marker {
    val density = map.resources.displayMetrics.density
    val text = titleText.substringBefore(" · ").take(42)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textSize = 12f * density
        textAlign = Paint.Align.CENTER
    }
    val w = (paint.measureText(text) + 24f * density).toInt()
    val h = (34f * density).toInt()
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    paint.color = Color.WHITE
    canvas.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), h / 2f, h / 2f, paint)
    paint.color = color
    canvas.drawRoundRect(2f * density, 2f * density, w - 2f * density, h - 2f * density, h / 2f, h / 2f, paint)
    // 밝은 도보/호선 배경에서도 글자가 읽히도록 대비를 선택한다.
    val luminance = (Color.red(color) * 0.299 + Color.green(color) * 0.587 + Color.blue(color) * 0.114)
    paint.color = if (luminance > 160) 0xFF17212E.toInt() else Color.WHITE
    canvas.drawText(text, w / 2f, h / 2f - (paint.ascent() + paint.descent()) / 2, paint)
    return Marker(map).apply {
        position = GeoPoint(point.lat, point.lon)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        icon = BitmapDrawable(map.resources, bitmap)
        title = titleText
    }
}


/** 안내 중 내 차 화살표(진행 방향으로 회전). 앱 고유 그림 */
fun carMarker(map: MapView, pos: LatLng, bearing: Double): Marker {
    val d = map.resources.displayMetrics.density
    val s = (56 * d).toInt()
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.rotate(bearing.toFloat(), s / 2f, s / 2f)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = Color.WHITE
    c.drawCircle(s / 2f, s / 2f, s * 0.46f, p)
    p.color = 0xFF002C5F.toInt()
    val path = Path().apply {
        moveTo(s / 2f, s * 0.12f); lineTo(s * 0.80f, s * 0.82f); lineTo(s / 2f, s * 0.64f); lineTo(s * 0.20f, s * 0.82f); close()
    }
    c.drawPath(path, p)
    return Marker(map).apply {
        position = GeoPoint(pos.lat, pos.lon)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        icon = BitmapDrawable(map.resources, bmp)
        title = "내 차"
    }
}

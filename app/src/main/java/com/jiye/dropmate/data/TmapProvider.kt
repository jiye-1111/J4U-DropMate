package com.jiye.dropmate.data

import com.jiye.dropmate.engine.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.InterruptedIOException
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * TMAP 공급자 구현.
 * - appKey 는 생성자로만 받아 메모리에만 둔다. 로그·파일·예외 메시지에 넣지 않는다.
 * - 자동 재시도 없음(OkHttp retryOnConnectionFailure=false). 호출 제한은 엔진의 CallGateway 가 담당.
 * - 응답 원문을 저장하지 않는다.
 *
 * 자동차: POST https://apis.openapi.sk.com/tmap/routes?version=1  (현재 교통 기준. 미래 출발 예측 API 미사용)
 * 대중교통: POST https://apis.openapi.sk.com/transit/routes  (searchDttm = yyyymmddhhmi, '타임머신' 운행여부 기준시각)
 */
class TmapProvider(private val appKey: String, timeoutMs: Int) : RouteProvider {
    private val http = OkHttpClient.Builder()
        .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .callTimeout(timeoutMs.toLong() + 2000, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .build()
    private val json = "application/json; charset=utf-8".toMediaType()

    private sealed class Http { data class Body(val code: Int, val text: String) : Http(); data class Err(val f: ProviderResult.Fail) : Http() }

    private fun post(url: String, body: JSONObject): Http {
        val req = Request.Builder().url(url)
            .header("appKey", appKey)
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody(json)).build()
        return try {
            http.newCall(req).execute().use { r -> Http.Body(r.code, r.body?.string() ?: "") }
        } catch (e: InterruptedIOException) {
            Http.Err(ProviderResult.Fail(FailKind.TIMEOUT, "시간 초과"))
        } catch (e: IOException) {
            Http.Err(ProviderResult.Fail(FailKind.NETWORK, "네트워크 오류(${e.javaClass.simpleName})"))
        }
    }

    private fun httpFail(code: Int, text: String): ProviderResult.Fail {
        val upper = text.uppercase()
        val kind = when {
            code == 401 || code == 403 || upper.contains("INVALID_API_KEY") || upper.contains("UNAUTHORIZED") -> FailKind.AUTH
            code == 429 || upper.contains("QUOTA") || upper.contains("LIMIT_EXCEEDED") -> FailKind.QUOTA
            code in 400..499 -> FailKind.BAD_REQUEST
            code >= 500 -> FailKind.SERVER
            else -> FailKind.OTHER
        }
        // 공급자 메시지의 code 만 남김(키가 섞일 가능성을 배제하기 위해 원문 전체를 남기지 않음)
        val c = runCatching { JSONObject(text).optJSONObject("error")?.optString("code") }.getOrNull()
        return ProviderResult.Fail(kind, "HTTP $code${if (!c.isNullOrBlank()) " $c" else ""}")
    }

    override fun car(origin: LatLng, dest: LatLng, departEpochSec: Long): ProviderResult<CarRoute> = carVia(origin, null, dest)

    /** 경유지 1개(하차 지점)를 넣은 자동차 경로. 공식 문서 passList 형식 "X,Y". 길안내 재탐색에 쓴다 */
    fun carVia(origin: LatLng, via: LatLng?, dest: LatLng): ProviderResult<CarRoute> {
        val body = JSONObject()
            .put("startX", origin.lon.toString()).put("startY", origin.lat.toString())
            .put("endX", dest.lon.toString()).put("endY", dest.lat.toString())
            .put("reqCoordType", "WGS84GEO").put("resCoordType", "WGS84GEO")
            .put("searchOption", "0").put("trafficInfo", "Y")
            .put("startName", "O").put("endName", "D")
        via?.let { body.put("passList", "${it.lon},${it.lat}") }
        return when (val r = post("https://apis.openapi.sk.com/tmap/routes?version=1&format=json", body)) {
            is Http.Err -> r.f
            is Http.Body -> if (r.code != 200) httpFail(r.code, r.text) else runCatching { TmapParser.car(r.text) }
                .getOrElse { ProviderResult.Fail(FailKind.OTHER, "자동차 응답 해석 실패") }
        }
    }

    override fun transit(origin: LatLng, dest: LatLng, searchDttm: String): ProviderResult<List<TransitItinerary>> {
        val body = JSONObject()
            .put("startX", origin.lon.toString()).put("startY", origin.lat.toString())
            .put("endX", dest.lon.toString()).put("endY", dest.lat.toString())
            .put("count", 10).put("lang", 0).put("format", "json").put("searchDttm", searchDttm)
        return when (val r = post("https://apis.openapi.sk.com/transit/routes", body)) {
            is Http.Err -> r.f
            is Http.Body -> if (r.code != 200) {
                // 400: 21~24 입력 오류, 500: 31~32
                val st = runCatching { JSONObject(r.text).optJSONObject("result")?.optInt("status") }.getOrNull()
                if (st != null && st in 21..24) ProviderResult.Fail(FailKind.BAD_REQUEST, "대중교통 code $st") else httpFail(r.code, r.text)
            } else runCatching { TmapParser.transit(r.text) }.getOrElse { ProviderResult.Fail(FailKind.OTHER, "대중교통 응답 해석 실패") }
        }
    }
    /** 장소 검색(POI) 결과의 이름·중심 좌표(noorLat/noorLon). 응답은 저장하지 않는다(TMAP 약관: 24시간 넘게 보관 금지). 카카오 키가 없을 때 입구 이름 검색에 쓴다. */
    fun poiSearch(keyword: String, near: LatLng): ProviderResult<List<Pair<String, LatLng>>> {
        val url = "https://apis.openapi.sk.com/tmap/pois".toHttpUrl().newBuilder()
            .addQueryParameter("version", "1").addQueryParameter("searchKeyword", keyword)
            .addQueryParameter("searchType", "all").addQueryParameter("searchtypCd", "R")
            .addQueryParameter("centerLat", near.lat.toString()).addQueryParameter("centerLon", near.lon.toString())
            .addQueryParameter("radius", "1").addQueryParameter("count", "5")
            .addQueryParameter("reqCoordType", "WGS84GEO").addQueryParameter("resCoordType", "WGS84GEO").build()
        val req = Request.Builder().url(url).header("appKey", appKey).header("Accept", "application/json").get().build()
        val r = try { http.newCall(req).execute().use { Http.Body(it.code, it.body?.string() ?: "") } }
            catch (e: InterruptedIOException) { return ProviderResult.Fail(FailKind.TIMEOUT, "시간 초과") }
            catch (e: IOException) { return ProviderResult.Fail(FailKind.NETWORK, "네트워크 오류(${e.javaClass.simpleName})") }
        if (r.code == 204 || (r.code == 200 && r.text.isBlank())) return ProviderResult.Ok(emptyList())
        if (r.code != 200) return httpFail(r.code, r.text)
        return runCatching {
            val arr = JSONObject(r.text).optJSONObject("searchPoiInfo")?.optJSONObject("pois")?.optJSONArray("poi") ?: JSONArray()
            val out = mutableListOf<Pair<String, LatLng>>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val c = LatLng(o.optString("noorLat").toDoubleOrNull() ?: continue, o.optString("noorLon").toDoubleOrNull() ?: continue)
                out += o.optString("name") to c
            }
            ProviderResult.Ok(out.toList())
        }.getOrElse { ProviderResult.Fail(FailKind.OTHER, "장소 검색 응답 해석 실패") }
    }

    /** 보행자 경로: POST /tmap/routes/pedestrian. 도보만 걷는 여정 1개로 돌려준다(2026-11-12 정확도 개선) */
    override fun walk(origin: LatLng, dest: LatLng): ProviderResult<TransitItinerary> {
        val body = JSONObject()
            .put("startX", origin.lon.toString()).put("startY", origin.lat.toString())
            .put("endX", dest.lon.toString()).put("endY", dest.lat.toString())
            .put("reqCoordType", "WGS84GEO").put("resCoordType", "WGS84GEO")
            .put("startName", "S").put("endName", "P")
        return when (val r = post("https://apis.openapi.sk.com/tmap/routes/pedestrian?version=1&format=json", body)) {
            is Http.Err -> r.f
            is Http.Body -> if (r.code != 200) httpFail(r.code, r.text) else runCatching { TmapParser.walk(r.text, origin, dest) }
                .getOrElse { ProviderResult.Fail(FailKind.OTHER, "보행자 응답 해석 실패") }
        }
    }
}

object TmapParser {
    fun walk(text: String, origin: LatLng, dest: LatLng): ProviderResult<TransitItinerary> {
        val feats = JSONObject(text).optJSONArray("features") ?: return ProviderResult.NoRoute("NO_FEATURES", "features 없음")
        var t: Int? = null; var d: Int? = null
        val pts = ArrayList<LatLng>()
        for (i in 0 until feats.length()) {
            val f = feats.getJSONObject(i); val g = f.getJSONObject("geometry"); val p = f.getJSONObject("properties")
            if (p.has("totalTime") && t == null) { t = p.optInt("totalTime"); d = p.optInt("totalDistance") }
            if (g.optString("type") == "LineString") {
                val c = g.getJSONArray("coordinates")
                for (k in 0 until c.length()) { val a = c.getJSONArray(k); val ll = LatLng(a.getDouble(1), a.getDouble(0)); if (pts.lastOrNull() != ll) pts += ll }
            }
        }
        if (t == null) return ProviderResult.NoRoute("NO_TIME", "보행 시간 없음")
        val leg = TransitLeg(LegMode.WALK, null, "하차점", "도착", origin, dest, d, t, pts)
        return ProviderResult.Ok(TransitItinerary(t, t, d, 0, listOf(leg)))
    }

    fun car(text: String): ProviderResult<CarRoute> {
        val o = JSONObject(text)
        val feats = o.optJSONArray("features") ?: return ProviderResult.NoRoute("NO_FEATURES", "features 없음")
        var totalTime: Int? = null; var totalDist: Int? = null
        val pts = ArrayList<LatLng>(); val cumT = ArrayList<Double>(); val cumD = ArrayList<Double>()
        // 구간(정점 i→i+1)별 도로·시설물 코드. tmapRouteDoc: LineString properties roadType·facilityType
        val segs = ArrayList<RoadSeg>()
        // 구간 혼잡도(geometry.traffic: [시작 index, 끝 index, 혼잡도, 속도], 선형 좌표 기준 index)
        val traffic = ArrayList<Int>()
        // Point 안내점(turnType·description·nextRoadName). 누적 거리는 직전까지 쌓인 선형 거리
        val turns = ArrayList<TurnPoint>()
        var accT = 0.0; var accD = 0.0
        for (i in 0 until feats.length()) {
            val f = feats.getJSONObject(i)
            val g = f.getJSONObject("geometry"); val p = f.getJSONObject("properties")
            if (p.has("totalTime") && totalTime == null) { totalTime = p.getInt("totalTime"); totalDist = p.optInt("totalDistance") }
            if (g.getString("type") == "Point") {
                val c = g.getJSONArray("coordinates")
                p.intOrNull("turnType")?.let { tt ->
                    turns += TurnPoint(LatLng(c.getDouble(1), c.getDouble(0)), accD, tt,
                        p.optString("description").ifBlank { null }, p.optString("nextRoadName").ifBlank { null }, p.optString("name").ifBlank { null })
                }
                continue
            }
            if (g.getString("type") != "LineString") continue
            val coords = g.getJSONArray("coordinates")
            val line = (0 until coords.length()).map { val c = coords.getJSONArray(it); LatLng(c.getDouble(1), c.getDouble(0)) }
            if (line.isEmpty()) continue
            val segTime = p.optDouble("time", Double.NaN)
            val road = RoadSeg(p.intOrNull("roadType"), p.intOrNull("facilityType"))
            val lineTraffic = IntArray((line.size - 1).coerceAtLeast(0))
            g.optJSONArray("traffic")?.let { arr ->
                for (ti in 0 until arr.length()) {
                    val e = arr.optJSONArray(ti) ?: continue
                    if (e.length() < 3) continue
                    val s0 = e.optInt(0); val e0 = e.optInt(1); val c = e.optInt(2)
                    for (k in s0.coerceAtLeast(0) until e0.coerceAtMost(lineTraffic.size)) lineTraffic[k] = c
                }
            }
            val lens = line.zipWithNext { a, b -> Geo.haversineM(a, b) }
            val sumLen = lens.sum()
            if (pts.isEmpty()) { pts += line[0]; cumT += 0.0; cumD += 0.0 }
            else if (pts.last() != line[0]) { pts += line[0]; cumT += accT; cumD += accD; segs += RoadSeg(null, null); traffic += 0 }
            for (k in 1 until line.size) {
                val frac = if (sumLen > 0) lens[k - 1] / sumLen else 0.0
                accD += lens[k - 1]
                accT += if (segTime.isNaN()) 0.0 else segTime * frac
                pts += line[k]; cumT += accT; cumD += accD; segs += road; traffic += lineTraffic.getOrElse(k - 1) { 0 }
            }
        }
        val tt = totalTime ?: return ProviderResult.NoRoute("NO_TOTAL", "totalTime 없음")
        if (pts.size < 2) return ProviderResult.NoRoute("NO_GEOMETRY", "경로 형상 없음")
        // 구간 time 합과 totalTime 차이(교차로 지연 등)는 비례 배분해 마지막 누적값이 totalTime 이 되도록 맞춘다
        val scale = if (accT > 0) tt / accT else 1.0
        return ProviderResult.Ok(CarRoute(tt, totalDist ?: accD.toInt(), pts, cumT.map { it * scale }, cumD, segs, turns, traffic))
    }

    private fun parseLine(s: String?): List<LatLng> = s?.trim()?.split(' ')?.mapNotNull {
        val c = it.split(','); if (c.size == 2) runCatching { LatLng(c[1].toDouble(), c[0].toDouble()) }.getOrNull() else null
    } ?: emptyList()

    private fun JSONObject.intOrNull(k: String): Int? = if (has(k) && !isNull(k)) runCatching { getDouble(k).toInt() }.getOrNull() else null

    fun transit(text: String): ProviderResult<List<TransitItinerary>> {
        val o = JSONObject(text)
        o.optJSONObject("result")?.let { r ->
            val st = r.optInt("status", -1)
            if (st in 11..14) return ProviderResult.NoRoute(st.toString(), r.optString("message"))
        }
        val its: JSONArray = o.optJSONObject("metaData")?.optJSONObject("plan")?.optJSONArray("itineraries")
            ?: return ProviderResult.NoRoute("NO_ITINERARY", "itineraries 없음")
        val out = ArrayList<TransitItinerary>()
        for (i in 0 until its.length()) {
            val it = its.getJSONObject(i)
            val legsJ = it.optJSONArray("legs") ?: JSONArray()
            val legs = (0 until legsJ.length()).map { j ->
                val l = legsJ.getJSONObject(j)
                val mode = when (l.optString("mode")) {
                    "WALK" -> LegMode.WALK; "BUS" -> LegMode.BUS; "SUBWAY" -> LegMode.SUBWAY; "EXPRESSBUS" -> LegMode.EXPRESSBUS
                    "TRAIN" -> LegMode.TRAIN; "AIRPLANE" -> LegMode.AIRPLANE; "FERRY" -> LegMode.FERRY; else -> LegMode.OTHER
                }
                val st = l.optJSONObject("start"); val en = l.optJSONObject("end")
                val shape = if (mode == LegMode.WALK) {
                    val steps = l.optJSONArray("steps")
                    if (steps != null) (0 until steps.length()).flatMap { k -> parseLine(steps.getJSONObject(k).optString("linestring")) } else emptyList()
                } else parseLine(l.optJSONObject("passShape")?.optString("linestring"))
                TransitLeg(
                    mode = mode,
                    route = l.optString("route").ifBlank { null },
                    startName = st?.optString("name"), endName = en?.optString("name"),
                    start = st?.let { s -> LatLng(s.getDouble("lat"), s.getDouble("lon")) },
                    end = en?.let { s -> LatLng(s.getDouble("lat"), s.getDouble("lon")) },
                    distanceM = l.intOrNull("distance"), sectionTimeSec = l.intOrNull("sectionTime"), shape = shape,
                    routeColor = l.optString("routeColor").removePrefix("#").ifBlank { null },
                )
            }
            out += TransitItinerary(it.intOrNull("totalTime"), it.intOrNull("totalWalkTime"), it.intOrNull("totalWalkDistance"), it.intOrNull("transferCount"), legs)
        }
        return if (out.isEmpty()) ProviderResult.NoRoute("EMPTY", "여정 0개") else ProviderResult.Ok(out)
    }
}

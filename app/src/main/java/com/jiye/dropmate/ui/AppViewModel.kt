package com.jiye.dropmate.ui

import android.annotation.SuppressLint
import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import android.location.Location
import android.location.LocationManager
import com.jiye.dropmate.data.AppKeyStore
import com.jiye.dropmate.data.EntranceStore
import com.jiye.dropmate.data.PrefsLedger
import com.jiye.dropmate.data.ResultExporter
import com.jiye.dropmate.data.TmapProvider
import com.jiye.dropmate.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

enum class Role(val title: String) { ORIGIN("출발지"), DRIVER("운전자 도착지"), PASSENGER("동승자 도착지") }

/** label = 장소 이름, address = 상세 주소(도로명/지번 등, 없으면 null). */
data class Place(val label: String, val pos: LatLng, val source: String, val address: String? = null, val category: String? = null)

/** 비교에 꼭 필요한 최소 호출: 운전자 직행(자동차 O→D) 1 + 동승자 단독 대중교통(O→P) 1 */
private const val MIN_CALLS_TO_COMPARE = 2
/** 입구 이름 검색 결과를 단지 좌표에서 몇 m 안까지 인정할지. 임시값, 미확정. */
private const val ENTRANCE_SEARCH_RADIUS_M = 500.0
/** 같은 입구로 묶는 거리(카카오·TMAP·입구 파일 점). 임시값, 미확정 */
private const val SAME_ENTRANCE_M = 30.0
private val STATION_NAME = Regex("역(\\s*\\d+번\\s*출구)?$")

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val policy = Policy()
    val ledger = PrefsLedger(app)
    val keys = AppKeyStore(app)
    val entrances = EntranceStore(app)
    private val jusoEntrances = com.jiye.dropmate.data.JusoEntranceStore()
    private val gate = LatestResultGate()

    var gtfs by mutableStateOf<GtfsIndex?>(null); private set
    var gtfsStatus by mutableStateOf("GTFS 자산 불러오는 중…"); private set
    /** GTFS 로드가 끝나기 전 계산하면 중간 하차 후보가 0개가 된다(2026-11-12 실제 실행에서 확인) → 로드 완료까지 계산 보류 */
    var gtfsLoading by mutableStateOf(true); private set
    var places by mutableStateOf<Map<Role, Place>>(emptyMap()); private set
    var picking by mutableStateOf<Role?>(null)
    var searchHits by mutableStateOf<Pair<Role, List<Place>>?>(null); private set
    var running by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)
    var result by mutableStateOf<RecommendResult?>(null); private set
    var exportedTo by mutableStateOf<String?>(null); private set
    private var cancelFlag = AtomicBoolean(false)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            gtfsStatus = try {
                val am = app.assets
                val g = GtfsIndex.load(am.open("gtfs/stops.tsv").bufferedReader(), am.open("gtfs/patterns.tsv").bufferedReader())
                gtfs = g
                "GTFS 2025(사전 필터 전용) 정류장 ${g.stops.size} · 패턴 ${g.patterns.size} — 현재 운행 미확인"
            } catch (e: Exception) { "GTFS 자산 로드 실패: ${e.javaClass.simpleName} (중간 하차 후보 생성 불가)" }
            gtfsLoading = false
        }
    }

    fun setPlace(role: Role, p: Place) { clearSearch(); places = places + (role to p); picking = null; result = null }

    /** 차량 위치가 갱신될 때 출발지만 조용히 바꾼다. 입력 중인 장소 검색·지도 선택은 건드리지 않는다(2026-11-13: 위치 갱신마다 검색 결과가 지워지던 문제). */
    fun setAutoOrigin(p: Place) { places = places + (Role.ORIGIN to p) }

    @SuppressLint("MissingPermission")
    fun locateOrigin() {
        // Google Play 서비스 없는 기기(Pleos 등)에서도 동작하도록 플랫폼 LocationManager 의 마지막 위치만 사용한다.
        val loc: Location? = runCatching {
            val lm = getApplication<Application>().getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            lm?.getProviders(true)?.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }?.maxByOrNull { it.time }
        }.getOrNull()
        if (loc == null) message = "현재 위치를 얻지 못했습니다. 검색하거나 지도에서 직접 선택하세요."
        else { setPlace(Role.ORIGIN, Place("현재 위치(정확도 ${loc.accuracy.toInt()}m)", LatLng(loc.latitude, loc.longitude), "device-location")); message = null }
    }

    private val kakaoSearch = com.jiye.dropmate.data.KakaoPlaceSearch()
    private var searchJob: kotlinx.coroutines.Job? = null
    private var searchVersion = 0
    private var searchRequest: Pair<Role, String>? = null
    private var searchPage = 0
    private var kakaoPlaces = emptyList<com.jiye.dropmate.data.KakaoPlace>()
    var searchLoading by mutableStateOf(false); private set
    var searchHasMore by mutableStateOf(false); private set
    var searchNotice by mutableStateOf<String?>(null); private set
    var decisionLogPath by mutableStateOf<String?>(null); private set

    private fun clearSearch() {
        searchVersion++
        searchJob?.cancel()
        searchRequest = null
        searchHits = null
        searchPage = 0
        kakaoPlaces = emptyList()
        searchLoading = false
        searchHasMore = false
        searchNotice = null
    }

    /** 입력마다 기존 요청을 취소한다. 자동 검색은 500ms 대기하며 버튼 검색은 즉시 실행한다. */
    fun search(role: Role, rawQuery: String, auto: Boolean = false) {
        val query = rawQuery.trim()
        val request = role to query
        if (request == searchRequest && (searchPage > 0 || (auto && searchLoading))) return
        clearSearch()
        if (query.isBlank()) return
        searchRequest = request
        searchHits = role to emptyList()
        requestSearchPage(role, query, 1, if (auto) 500L else 0L)
    }

    fun loadMorePlaces() {
        val (role, query) = searchRequest ?: return
        if (searchLoading || !searchHasMore) return
        requestSearchPage(role, query, searchPage + 1, 0L)
    }

    private fun requestSearchPage(role: Role, query: String, page: Int, delayMs: Long) {
        val version = searchVersion
        searchLoading = true
        searchNotice = null
        searchJob = viewModelScope.launch {
            try {
                kotlinx.coroutines.delay(delayMs)
                val key = keys.kakaoRestApiKey()
                    ?: throw com.jiye.dropmate.data.PlaceSearchException("이 버전은 장소 이름 검색이 꺼져 있어요. '지도' 버튼을 누르고 지도를 찍어 정해 주세요.")
                val response = kakaoSearch.search(key, query, page)
                if (version != searchVersion) return@launch
                kakaoPlaces = (if (page == 1) response.places else kakaoPlaces + response.places)
                    .distinctBy { it.id }
                searchHits = role to kakaoPlaces.map { Place(it.name, it.position, "kakao-local", it.address, it.category) }
                searchPage = page
                searchHasMore = response.hasMore
                searchNotice = when {
                    kakaoPlaces.isEmpty() -> "카카오 검색 결과가 없습니다. 장소명이나 지역명을 바꿔 검색하세요."
                    !response.hasMore && response.totalCount > kakaoPlaces.size ->
                        "더 정확한 결과는 지역명·지점명을 함께 입력하세요."
                    else -> null
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (version == searchVersion) {
                    // 결과 목록 자리에 안내 문구가 보이도록 빈 결과로 둔다(카카오 키 없는 공개 APK 등)
                    searchHits = role to emptyList()
                    searchNotice = (e as? com.jiye.dropmate.data.PlaceSearchException)?.userMessage
                        ?: "장소 검색에 실패했습니다. 다시 검색하세요."
                }
            } finally {
                if (version == searchVersion) searchLoading = false
            }
        }
    }

    fun preflight(): Preflight = DropMateEngine(gtfs, policy).preflight(ledger)

    fun ready(): String? = when {
        Role.entries.any { it !in places } -> "출발지·운전자 도착지·동승자 도착지를 모두 정하세요."
        running -> "이미 계산 중입니다."
        gtfsLoading -> "정류장 자료(GTFS)를 불러오는 중입니다. 잠시 후 다시 눌러 주세요."
        else -> null
    }

    /** 버튼 한 번으로 실행: appKey·예산은 assets/api.properties(개발자 설정)에서 읽는다. 예산은 설정값이 바뀔 때 장부에 반영. */
    fun runConfigured() {
        ready()?.let { message = it; return }
        val key = keys.tmapAppKey() ?: run { message = "TMAP 키가 없습니다. 오른쪽 '설정'에서 본인 TMAP 키를 넣어 주세요."; return }
        // 예산 기준은 api.properties 하나: 값이 바뀌면 장부 잔여를 그 값으로 다시 설정, 비어 있으면 로컬 제한 해제(요청당 상한만 적용).
        ledger.applyConfiguredBudget(keys.budget())
        val rem = ledger.localRemaining()
        if (rem != null && rem < MIN_CALLS_TO_COMPARE) {
            // 기준 경로 2회(자동차 O→D + 단독 대중교통 O→P)도 못 하면 비교가 불가능하므로 호출하지 않고 바로 알린다.
            message = "기본 키의 호출 한도를 다 썼어요(남은 ${rem}회). 오른쪽 '설정'에서 본인 TMAP 키를 넣으면 계속 쓸 수 있어요."
            return
        }
        run(key, null)
    }

    /** appKey 는 이 함수 인자로만 전달되고 저장되지 않는다. */
    fun run(appKey: String, setBudget: Int?) {
        ready()?.let { message = it; return }
        if (appKey.isBlank()) { message = "TMAP appKey 가 필요합니다."; return }
        setBudget?.let { ledger.setRemaining(it) }
        val id = "R" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().take(4)
        gate.begin(id); cancelFlag = AtomicBoolean(false); val flag = cancelFlag
        running = true; result = null; exportedTo = null; message = "계산 중… (TMAP 호출은 상한 내에서 순차 실행)"
        val dPlace = places[Role.DRIVER]!!; val pPlace = places[Role.PASSENGER]!!
        val req0 = RecommendRequest(id, places[Role.ORIGIN]!!.pos, dPlace.pos, pPlace.pos, System.currentTimeMillis() / 1000, live = true,
            passengerName = placeName(pPlace)?.removeSuffix("아파트")?.trim())
        viewModelScope.launch {
            val r = try {
                withContext(Dispatchers.IO) {
                    val provider = TmapProvider(appKey, policy.callTimeoutMs)
                    val req = req0.copy(
                        driverEntrances = destEntrances(provider, id, dPlace, forDriver = true),
                        passengerEntrances = destEntrances(provider, id, pPlace, forDriver = false))
                    DropMateEngine(gtfs, policy).recommend(req, provider, ledger, isCancelled = { flag.get() })
                }
            } catch (e: Exception) {
                running = false; message = "계산 실패: ${e.javaClass.simpleName}"; return@launch
            }
            running = false
            if (gate.accept(r)) {
                result = r; message = if (r.haltedBy == "QUOTA" || r.haltedBy == "AUTH") "TMAP 키 한도 초과 또는 사용 불가예요. 오른쪽 '설정'에서 본인 TMAP 키를 넣어 주세요." else null
                saveDecisionLog(r)
            } else message = "이전 요청 결과는 표시하지 않습니다."
        }
    }

    fun cancel() { cancelFlag.set(true) }

    /** 카카오 검색으로 고른 장소 이름(지도 선택·현재 위치는 이름 없음) */
    private fun placeName(p: Place): String? = p.label.substringBefore(" · ").takeIf { p.source.startsWith("kakao") && it.isNotBlank() }

    /** 2026-11-12: 역 목적지는 입구로 바꾸지 않는다(카카오 지하철역 SW8, 또는 이름이 '역'·'역 N번 출구'로 끝남). */
    private fun isStation(p: Place): Boolean =
        p.category?.startsWith("SW8|") == true || placeName(p)?.trim()?.let { STATION_NAME.containsMatchIn(it) } == true

    private fun isApartment(p: Place): Boolean = p.category?.substringAfter('|')?.contains("아파트") == true

    /**
     * 목적지 입구. 역은 입구를 쓰지 않는다. 운전자는 차량 입구(CAR)만, 동승자는 차가 닿는 입구 모두.
     * 2026-11-13 정한 순서: ① 주소기반 내비게이션용DB 출입구(자료 받기 전이라 지금은 비어 있음)
     * → ② (아파트) 카카오 '<단지명> 정문/후문/주차장 입구/GATE' 검색 → ③ 하나도 못 찾았거나 카카오 키가 없으면 같은 검색어로 TMAP 장소 검색
     * → ④ 입구 파일(OSM·OA-21700, 이름 맞을 때만). 아파트가 아닌 건물은 ① 다음 바로 ④. 검색 결과는 저장하지 않는다(요청 때만).
     */
    private suspend fun destEntrances(provider: TmapProvider, id: String, p: Place, forDriver: Boolean): List<Entrance> {
        // 입구 라벨은 '<단지명> <입구>'로 짧게(단지명의 '아파트' 접미사 뺌). 예: '아크로리버파크 게이트1', '아크로리버파크 주차장 입구(GATE 1)'
        val short = placeName(p)?.removeSuffix("아파트")?.trim()
        fun tidy(e: Entrance): Entrance {
            if (short.isNullOrBlank()) return e
            val l = e.label.replace(Regex("아파트(?=\\s|$)"), "").replace(Regex("\\s+"), " ").trim()
            return e.copy(label = if (l.replace(" ", "").contains(short.replace(" ", ""))) l else "$short $l")
        }
        return pickNearest(provider, id, p, forDriver).map(::tidy)
    }

    private suspend fun pickNearest(provider: TmapProvider, id: String, p: Place, forDriver: Boolean): List<Entrance> {
        val found = findEntrances(provider, id, p, forDriver)
        if (forDriver || found.isEmpty()) return found
        // 2026-11-13 가장 가까운 곳에 내려 주고, 입구가 가까우면 입구에 내려 준다: 같은 입구를 가리키는 점(카카오·TMAP·입구 파일, SAME_ENTRANCE_M 안)이
        // 여러 개면 동승자 목적지에 더 가까운 점을 하차점으로 쓴다. 서로 다른 입구 사이 선택은 엔진(운전자 추가가 가장 적은 곳) 그대로.
        val name = placeName(p) ?: return found
        val pool = found + entrances.forPlace(name, p.pos, Int.MAX_VALUE)
        return found.map { e ->
            pool.filter { Geo.haversineM(it.pos, e.pos) <= SAME_ENTRANCE_M }.minByOrNull { Geo.haversineM(it.pos, p.pos) }
                ?.takeIf { it !== e }?.let { e.copy(pos = it.pos, source = "${e.source} · 좌표: ${it.source}(동승자 목적지에 더 가까운 같은 입구 점)") } ?: e
        }.fold(mutableListOf<Entrance>()) { acc, e -> if (acc.none { Geo.haversineM(it.pos, e.pos) < 1.0 }) acc += e; acc }
    }

    private suspend fun findEntrances(provider: TmapProvider, id: String, p: Place, forDriver: Boolean): List<Entrance> {
        if (isStation(p)) return emptyList()
        val name = placeName(p) ?: return emptyList()
        jusoEntrances.forPlace(name, p.pos, forDriver).take(policy.maxEntrancesPerDest).let { if (it.isNotEmpty()) return it }
        val fromFile = { entrances.forPlace(name, p.pos, Int.MAX_VALUE).filter { !forDriver || it.kind == EntranceKind.CAR }.take(policy.maxEntrancesPerDest) }
        if (!isApartment(p)) return fromFile()
        val complex = name.removeSuffix("아파트").trim()
        // 2026-11-12: 'GATE' 검색어 추가(결과 이름에 GATE/게이트, 차량 입구로 보고 라벨은 결과 이름 그대로).
        val gate = "GATE" to "gate"
        val words = if (forDriver) listOf("주차장 입구" to "주차장", gate) else listOf("정문" to "정문", "후문" to "후문", "주차장 입구" to "주차장", gate)
        val kakaoKey = keys.kakaoRestApiKey()
        if (kakaoKey != null) {
            val kakao = searchEntrances(complex, words, p, "카카오") { label, q ->
                val r = runCatching { com.jiye.dropmate.data.KakaoPlaceSearch().search(kakaoKey, q, 1).places.map { it.name to it.position } }
                ledger.record(CallRecord(id, 0, CallKind.POI, "카카오 입구 검색: $label", p.pos, p.pos, "kakao", false, if (r.isSuccess) "OK" else "FAIL"))
                r.getOrDefault(emptyList())
            }
            if (kakao.isNotEmpty()) return kakao
        }
        val tmap = searchEntrances(complex, words, p, "TMAP") { label, q ->
            val rem = ledger.localRemaining()
            if (rem != null && rem <= MIN_CALLS_TO_COMPARE + 1) return@searchEntrances null
            val r = provider.poiSearch(q, p.pos)
            ledger.record(CallRecord(id, 0, CallKind.POI, "TMAP 입구 검색: $label", p.pos, p.pos, "-", true, if (r is ProviderResult.Ok) "OK" else r.javaClass.simpleName))
            (r as? ProviderResult.Ok)?.value.orEmpty()
        }
        return tmap.ifEmpty { fromFile() }
    }

    /**
     * 검색어마다 search(라벨, 검색어) 결과에서 이름에 단지명과 그 단어가 모두 있고 ENTRANCE_SEARCH_RADIUS_M 안인 것만 입구로 채택.
     * 정문·후문·주차장은 가장 가까운 1곳, GATE 는 이름이 다른 게이트를 모두. search 가 null 이면(예산 부족) 거기서 멈춘다.
     */
    private suspend fun searchEntrances(complex: String, words: List<Pair<String, String>>, p: Place, src: String,
                                        search: suspend (String, String) -> List<Pair<String, LatLng>>?): List<Entrance> {
        val norm = { s: String -> s.replace(" ", "").lowercase() }
        val out = mutableListOf<Entrance>()
        for ((label, word) in words) {
            val hits = search(label, "$complex $label") ?: break
            val isGate = word == "gate"
            val matched = hits.filter { (n, pos) -> norm(n).contains(norm(complex)) && (if (isGate) norm(n).contains("gate") || n.contains("게이트") else norm(n).contains(word)) &&
                Geo.haversineM(pos, p.pos) <= ENTRANCE_SEARCH_RADIUS_M }
                .sortedBy { Geo.haversineM(it.second, p.pos) }
            for (best in if (isGate) matched else matched.take(1)) {
                if (out.any { Geo.haversineM(it.pos, best.second) < 5.0 }) continue
                out += Entrance(best.second, if (isGate) best.first else label, if (word == "주차장" || isGate) EntranceKind.CAR else EntranceKind.WALK,
                    "$src 장소 검색 '${best.first}'(요청 시 조회, 저장 안 함)")
            }
        }
        return out.take(policy.maxEntrancesPerDest)
    }

    /**
     * 길안내 재탐색: 현재 위치 → (아직 안 지난 하차 지점) → 운전자 목적지 자동차 경로 1회 호출.
     * 로컬 예산(장부)을 차감하고 기록한다. 키·응답 원문은 남기지 않는다. 실패하면 null.
     */
    @Volatile var lastRouteError: String? = null; private set

    /**
     * 하차 시점 재계산(2026-11-12): 실제 하차 위치·현재 시각으로 동승자 대중교통을 다시 조회(TMAP 1회).
     * 같은 기준(접근 보행 10분, 도보 직행 한도 없음)으로 평가하고 최소시간·최소환승·최소도보를 다시 고른다. 실패 시 null.
     */
    suspend fun refreshPassengerOptions(from: LatLng, dest: LatLng): List<RouteOption>? = withContext(Dispatchers.IO) {
        val key = keys.tmapAppKey() ?: run { lastRouteError = "TMAP 키 없음"; return@withContext null }
        ledger.applyConfiguredBudget(keys.budget())
        val rem = ledger.localRemaining()
        if (rem != null && rem <= 0) { lastRouteError = "기본 키 호출 한도 소진 · 설정에서 본인 TMAP 키 입력"; return@withContext null }
        val now = System.currentTimeMillis() / 1000
        val dttm = TimeUtil.searchDttm(now)
        val res = TmapProvider(key, policy.callTimeoutMs).transit(from, dest, dttm)
        ledger.record(CallRecord("DROP-" + System.currentTimeMillis(), 1, CallKind.TRANSIT, "하차 시점 동승자 경로 재계산", from, dest, dttm, true,
            if (res is ProviderResult.Ok) "OK" else res.javaClass.simpleName))
        when (res) {
            is ProviderResult.Ok -> RouteOptions.select(DropMateEngine(null, policy).evalItineraries(res.value, now)).also { lastRouteError = if (it.isEmpty()) "조건에 맞는 경로 없음" else null }
            is ProviderResult.NoRoute -> {
                if (res.code != "11") { lastRouteError = "경로 없음(${res.code})"; return@withContext null }
                // 너무 가까워 대중교통이 없으면(code 11) 보행자 경로로 걷는 시간을 보여 준다(엔진과 같은 규칙)
                val w = TmapProvider(key, policy.callTimeoutMs).walk(from, dest)
                ledger.record(CallRecord("DROPW-" + System.currentTimeMillis(), 1, CallKind.WALK, "하차 시점 동승자 도보 경로", from, dest, "-", true,
                    if (w is ProviderResult.Ok) "OK" else w.javaClass.simpleName))
                when (w) {
                    is ProviderResult.Ok -> RouteOptions.select(DropMateEngine(null, policy).evalItineraries(listOf(w.value), now))
                        .also { lastRouteError = if (it.isEmpty()) "도보 경로를 평가하지 못했어요" else null }
                    is ProviderResult.NoRoute -> { lastRouteError = "도보 경로 없음(${w.code})"; null }
                    is ProviderResult.Fail -> { lastRouteError = "TMAP 조회 실패(${w.kind})"; null }
                }
            }
            is ProviderResult.Fail -> { lastRouteError = "TMAP 조회 실패(${res.kind})"; null }
        }
    }

    suspend fun rerouteCar(from: LatLng, via: LatLng?, dest: LatLng): CarRoute? = withContext(Dispatchers.IO) {
        val key = keys.tmapAppKey() ?: run { lastRouteError = "TMAP 키 없음"; return@withContext null }
        ledger.applyConfiguredBudget(keys.budget())
        val rem = ledger.localRemaining()
        if (rem != null && rem <= 0) { lastRouteError = "기본 키 호출 한도 소진 · 설정에서 본인 TMAP 키 입력"; return@withContext null }
        val res = TmapProvider(key, policy.callTimeoutMs).carVia(from, via, dest)
        lastRouteError = when (res) { is ProviderResult.Fail -> "TMAP 조회 실패(${res.kind})"; is ProviderResult.NoRoute -> "경로 없음(${res.code})"; else -> null }
        val ok = res is ProviderResult.Ok
        ledger.record(CallRecord("NAV-" + System.currentTimeMillis(), 1, CallKind.CAR, "길안내 재탐색", from, dest,
            (System.currentTimeMillis() / 1000).toString(), true, if (ok) "OK" else res.javaClass.simpleName))
        (res as? ProviderResult.Ok)?.value
    }

    /** 시연·검사용: 합성 결과를 화면에 바로 띄운다(API 호출 없음). 화면에는 결과 그대로 표시된다 */
    fun showSyntheticResult(o: Place, d: Place, pp: Place, r: RecommendResult) {
        places = mapOf(Role.ORIGIN to o, Role.DRIVER to d, Role.PASSENGER to pp); gate.begin(r.requestId); result = r; message = null
    }

    /** 결과를 지우고 입력 화면으로(입력값은 유지) */
    fun clearResult() { result = null; message = null }

    /** 설정: 음성 안내 기본값(기본 켬). 기기 안에만 저장 */
    private val settingsSp = app.getSharedPreferences("dropmate_settings", android.content.Context.MODE_PRIVATE)
    var voiceDefault by mutableStateOf(settingsSp.getBoolean("voice_default", true)); private set
    fun updateVoiceDefault(on: Boolean) { voiceDefault = on; settingsSp.edit().putBoolean("voice_default", on).apply() }

    /** 하차점 순위 기준 바꾸기(최소 시간/최소 환승). 이미 받은 결과만 다시 정렬하며 TMAP 호출은 없다. */
    fun setRankMode(m: RankMode) { result = result?.withRankMode(m, policy.rankRounding) }

    /** 경로 판단 과정을 decision_log.md 로 자동 저장(키·API 원문 제외). */
    private fun saveDecisionLog(r: RecommendResult) {
        val snapshot = places.map { (role, p) -> role.title to ((p.label + (p.address?.let { " — $it" } ?: "")) to p.pos) }.toMap()
        viewModelScope.launch(Dispatchers.IO) {
            decisionLogPath = runCatching { com.jiye.dropmate.data.DecisionLog.write(getApplication(), r, snapshot).absolutePath }
                .getOrElse { "판단 기록 저장 실패: ${it.javaClass.simpleName}" }
        }
    }

    fun export() {
        val r = result ?: return
        viewModelScope.launch(Dispatchers.IO) {
            exportedTo = runCatching { ResultExporter.export(getApplication(), r).absolutePath }.getOrElse { "저장 실패: ${it.javaClass.simpleName}" }
        }
    }
}

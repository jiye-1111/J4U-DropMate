package com.jiye.dropmate.engine

/** WGS84 좌표. lat=위도, lon=경도. */
data class LatLng(val lat: Double, val lon: Double) {
    override fun toString(): String = "%.6f,%.6f".format(lat, lon)
}

enum class MinuteRounding { ROUND, FLOOR, CEIL }

enum class CarTimeMode {
    /** O→D 직행 응답의 구간별 시간으로 O→s, s→D 산출 (추가 호출 0). 사용자 결정 2026-11-04 */
    ROUTE_SEGMENT,
    /** 후보마다 O→s, s→D를 TMAP으로 조회 (후보당 2회). */
    TMAP_QUERY,
}

/**
 * 정책 설정. 값은 PLAN_mj3 + 2026-11-04 사용자 답변 기준.
 * null = 미확정. 미확정 값은 0이나 임의 값으로 채우지 않는다.
 */
data class Policy(
    val version: String = "mj3-dev-20261112-acc",
    /** 운전자 우회 한도 상한(초). 2026-11-12 사용자 결정: 최대 15분 */
    val detourLimitSec: Int = 900,
    /** 2026-11-12 사용자 결정: 한도 = min(15분, 직행시간 × 30%) */
    val detourRatio: Double = 0.30,
    /** DEC-08 [합의·조정 예정] */
    val nearLimitSec: Int = 720,
    /** 사용자 결정: 하차점에서 첫 탑승 지점까지 실제 접근 보행 10분 이내 */
    val accessWalkLimitSec: Int = 600,
    /** DEC-07 [합의] 일반 도로변 하차 소요. 개발 가정값임을 결과에 표시 */
    val curbDropoffSec: Int = 60,
    /** DEC-15 [승인] 순위용 분 변환 방식: 사용자 결정 2026-11-04 = 반올림 */
    val rankRounding: MinuteRounding = MinuteRounding.ROUND,
    /**
     * 도보 직행(하차점→동승자 목적지 걸어서만) 한도(초). 2026-11-12 사용자 결정(DEC-14): 한도 없음 = WALK_DIRECT_NO_LIMIT.
     * 걷는 시간이 길면 동승자 도착이 늦어져 순위에서 자연히 밀린다. null 이면 도보 직행 여정은 순위 미반영(이전 동작).
     */
    val walkDirectLimitSec: Int? = WALK_DIRECT_NO_LIMIT,
    /** DEC-03 대안 표시: 미확정 → 비활성 */
    val alternativeEnabled: Boolean = false,
    /**
     * 요청당 평가 후보 수(D 하차·P 경유 포함). 2026-11-04 결정은 3개였으나, 감사에서 중간 하차 1개만 평가하는 것이
     * 정확도 손실의 주원인으로 확인되어 2026-11-12 사용자 요청(정확도 개선, 일 5,000회 허용)으로 8개로 늘림.
     */
    val maxCandidatesPerRequest: Int = 8,
    /** 중간 하차 후보 최대 수. 경로 전체 구간에 고르게 나눠 고른다 */
    val maxMiddleCandidates: Int = 6,
    /** 요청당 TMAP 호출 상한: 기준 2 + 중간 6 + D 하차 1(+도보 1) + P 경유 2 + 여유 */
    val maxCallsPerRequest: Int = 14,
    /** 목적지 하나당 비교할 입구 수 상한 */
    val maxEntrancesPerDest: Int = 3,
    val carTimeMode: CarTimeMode = CarTimeMode.ROUTE_SEGMENT,
    /** 실패 시 재시도 횟수. 사용량 최소화 원칙으로 0 */
    val maxRetries: Int = 0,
    val callTimeoutMs: Int = 10_000,
    /** 호출 절약용 후보 탐색 반경. 10분 보행 가능 여부를 증명하는 한도가 아님. */
    val corridorStraightLimitM: Int = 600,
    /** GTFS 환승 연결 탐색용 보행 반경(우선순위 산정 전용, 제외 근거 아님) */
    val gtfsTransferProbeM: Int = 400,
    /** GTFS 목적지 접근점 탐색 반경(우선순위 산정 전용, 최종 보행 한도 아님) */
    val gtfsTargetProbeM: Int = 600,
    /** TMAP 대중교통 totalTime 이 초기 대기를 포함하는지 공급자 문서로 확인되지 않음 */
    val transitTimeBasisVerified: Boolean = false,
    /**
     * 2026-11-12 사용자 결정: 실제로 내려줄 수 없는 도로만 하차 후보에서 제외한다.
     * TMAP roadType 0 고속도로, 1 자동차전용 은 하차 불가.
     */
    val noStopRoadTypes: Set<Int> = setOf(0, 1),
    /**
     * TMAP facilityType 0 일반도로 만 하차 가능으로 본다.
     * 1 교량, 2 터널, 3 고가도로, 4 지하도로, 5 교차로통과, 6 철도건널목, 7 댐/방파제, 13 토끼굴, 90 한강교량 은 하차 불가.
     */
    val stoppableFacilityTypes: Set<Int> = setOf(0),
    /** 이름에 들어 있으면 하차 후보에서 제외(구간 코드가 일반도로로 잡히는 지하차도·고가 보완) */
    val noStopNameWords: List<String> = listOf("지하차도", "터널", "고가", "IC", "JC", "나들목", "분기점"),
    /**
     * 2026-11-12 보강: 하차점 앞뒤 이 거리(m) 안에 고속도로·자동차전용도로·교량·터널·고가·지하차도·철도건널목이 있으면 하차 불가.
     * (시연에서 올림픽대로 진입로 바로 앞이 추천된 사례). 교차로 통과(5)는 하차점이 놓인 구간에만 적용.
     */
    val stopClearanceM: Double = 50.0,
    val hardNoStopFacilityTypes: Set<Int> = setOf(1, 2, 3, 4, 6, 7, 13, 90),
) {
    /** 요청마다 직행 차량시간으로 우회 한도를 정한다. 반올림하지 않고 내림(초) */
    fun allowedDetourSec(directSec: Int): Int = minOf(detourLimitSec, kotlin.math.floor(detourRatio * directSec.coerceAtLeast(0)).toInt())
    /** 한도 근접 표시: 0.8 × 한도 */
    fun nearLimit(addSec: Int, directSec: Int): Boolean = addSec >= 0.8 * allowedDetourSec(directSec)
}

enum class CandidateType { MIDDLE, D_DROP, P_VIA }

/** GTFS 사전 필터 3상태 */
enum class PrefilterState { EXCLUDED_CONFIRMED, QUERY_TARGET, UNDETERMINED }

enum class GtfsLink { DIRECT, TRANSFER, NOT_FOUND }

enum class CandStatus {
    /** 평가 완료(순위 사용 가능) */
    EVALUATED,
    /** 운전자 추가시간 초과 */
    EXCLUDED_DETOUR,
    /** 반환된 여정의 접근 보행시간 한도 초과 */
    EXCLUDED_ACCESS_WALK,
    /** 첫 접근 보행시간 누락 */
    ACCESS_WALK_UNKNOWN,
    /** 도보만으로 도착하는 여정뿐이며 도보 직행 한도 미확정 */
    WALK_DIRECT_UNRATED,
    /** 공급자: 경로 없음 */
    NO_ROUTE,
    /** 조회 실패 */
    FAILED,
    /** 예산·한도로 미조회 */
    NOT_QUERIED_BUDGET,
    /** 앞선 오류로 호출 중단되어 미조회 */
    NOT_QUERIED_HALTED,
}

enum class Verdict { RECOMMEND, SIMILAR, NO_GAIN, NO_CANDIDATE, INCOMPLETE }

enum class FailKind { AUTH, QUOTA, BAD_REQUEST, TIMEOUT, NETWORK, SERVER, CANCELLED, BUDGET, OTHER }

/** 자동차 경로: 형상 정점과 각 정점까지의 누적 거리·시간 */
data class CarRoute(
    val totalTimeSec: Int,
    val totalDistanceM: Int,
    val points: List<LatLng>,
    /** points 와 같은 길이. 출발점부터 해당 정점까지 누적 시간(초) — 구간 time 을 선형 보간해 배분 */
    val cumTimeSec: List<Double>,
    val cumDistM: List<Double>,
    /** 정점 i→i+1 구간의 TMAP 도로·시설물 코드(길이 points.size-1). 응답에 없으면 빈 목록 */
    val segRoad: List<RoadSeg> = emptyList(),
    /** TMAP Point 안내점(회전 안내). 응답에 없으면 빈 목록 */
    val turns: List<TurnPoint> = emptyList(),
    /** 정점 i→i+1 구간 혼잡도(TMAP traffic: 0 정보없음, 1 원활, 2 서행, 3 지체, 4 정체). 없으면 빈 목록 */
    val segTraffic: List<Int> = emptyList(),
)

/** TMAP 자동차 경로 LineString 의 roadType·facilityType(공식 문서 tmapRouteDoc 응답 코드). 누락은 null */
data class RoadSeg(val roadType: Int?, val facilityType: Int?)

enum class LegMode { WALK, BUS, SUBWAY, EXPRESSBUS, TRAIN, AIRPLANE, FERRY, OTHER }

data class TransitLeg(
    val mode: LegMode,
    val route: String?,
    val startName: String?,
    val endName: String?,
    val start: LatLng?,
    val end: LatLng?,
    val distanceM: Int?,
    val sectionTimeSec: Int?,
    val shape: List<LatLng> = emptyList(),
    /** TMAP 응답의 노선 고유색(RRGGBB, '#' 없음). 없으면 null */
    val routeColor: String? = null,
)

data class TransitItinerary(
    val totalTimeSec: Int?,
    val totalWalkTimeSec: Int?,
    val totalWalkDistanceM: Int?,
    val transferCount: Int?,
    val legs: List<TransitLeg>,
) {
    val isWalkOnly: Boolean get() = legs.isNotEmpty() && legs.all { it.mode == LegMode.WALK }
    /** 하차점 → 첫 탑승 지점까지 보행 거리. 첫 leg 가 탑승이면 0m(공급자 기준), WALK 인데 거리 누락이면 null */
    val firstAccessWalkM: Int?
        get() {
            val first = legs.firstOrNull() ?: return null
            return if (first.mode == LegMode.WALK) first.distanceM else 0
        }
    /** 첫 탑승 전 연속 도보 구간의 실제 소요시간. 누락/음수는 미확인. */
    val firstAccessWalkSec: Int?
        get() {
            if (legs.isEmpty()) return null
            val access = legs.takeWhile { it.mode == LegMode.WALK }
            if (access.any { it.sectionTimeSec == null || it.sectionTimeSec < 0 }) return null
            return access.sumOf { it.sectionTimeSec!! }
        }
    val sectionSumSec: Int? get() = if (legs.any { it.sectionTimeSec == null }) null else legs.sumOf { it.sectionTimeSec!! }
}

sealed class ProviderResult<out T> {
    data class Ok<T>(val value: T) : ProviderResult<T>()
    /** 공급자가 정상 응답했으나 경로 없음 (예: 대중교통 code 11~14) */
    data class NoRoute(val code: String, val message: String) : ProviderResult<Nothing>()
    data class Fail(val kind: FailKind, val message: String) : ProviderResult<Nothing>()
}

/** TMAP 등 공급자 인터페이스. 앱은 실제 HTTP 구현, 테스트는 가짜 구현을 주입한다. */
interface RouteProvider {
    fun car(origin: LatLng, dest: LatLng, departEpochSec: Long): ProviderResult<CarRoute>
    /** searchDttm: yyyyMMddHHmm (KST) */
    fun transit(origin: LatLng, dest: LatLng, searchDttm: String): ProviderResult<List<TransitItinerary>>
    /** 보행자 경로(도보만). 대중교통이 '너무 가까움(code 11)'일 때만 쓴다. 미지원 공급자는 NoRoute */
    fun walk(origin: LatLng, dest: LatLng): ProviderResult<TransitItinerary> = ProviderResult.NoRoute("UNSUPPORTED", "보행자 경로 미지원")
}

/** 도보 직행 한도 없음(2026-11-12 사용자 결정 DEC-14) */
const val WALK_DIRECT_NO_LIMIT: Int = Int.MAX_VALUE

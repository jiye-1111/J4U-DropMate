# DropMate

> 공모전 제출용 요약 (2026-11-13 갱신)

| 항목 | 내용 |
|---|---|
| 앱 이름 | DropMate (운전자·동승자 하차 지점 추천) |
| 패키지 | `com.jiye.dropmate` |
| 실행 방법 | Android Studio에서 이 폴더를 열고 Gradle JDK를 21로 지정 → `gradlew -PpleosSdk=true :app:assembleRelease` → Pleos Connect 에뮬레이터에 설치·실행. `app/src/main/assets/api.properties`(양식: `api.properties.example`)에 TMAP 키가 있어야 경로 계산이 동작한다 |
| 테스트 환경 | Windows 11, Android Studio, JDK 21, Gradle 8.11.1, AGP 8.7.3, Kotlin 2.0.21, compileSdk/targetSdk 35, **minSdk 28**(Pleos 요건) |
| 에뮬레이터 | Pleos Connect Emulator v2.1.0 (AVD `Pleos_Connect_v2.1.0`, API 34), 그래픽 Hardware(GPU) |
| 화면 사양 | 2560×1440, 160dpi (Pleos Connect 에뮬레이터 AVD 설정 기준, 시스템 이미지 android-34 pleos_car_emulator x86_64) |
| 팀명 | J4U (Journey for You) |
| APK 위치 | `apk/J4U_DropMate_release.apk` (release 빌드, `keystore.properties`가 없으면 디버그 키로 서명) |

## 개발 기록 (PLAN_mj3 기준, 2026-11-04 신규 생성)

운전자와 동승자가 같은 출발지(O)에서 출발할 때, 운전자 추가시간 min(15분, 직행시간 × 30%) 이하에서 **동승자 최종 도착이 가장 빠른 하차 방법**을 찾는 Android 앱.

## 현재 하차점 규칙 (2026-11-13)
- 하차 후보: 직행 경로 중간(최대 6곳, 직행 경로 600m 안 GTFS 정류장·역: KTDB 2025, 버스정류장 53,331·철도역 793), 운전자 목적지에서 함께 하차, 동승자 목적지(입구) 앞.
- 순위(2026-11-14 사용자 결정): 추천 화면 위 탭으로 고른다(기본 최소 시간). 운전자 우회 한도 min(15분, 직행×30%) 밖 후보는 제외.
  - 빠른 하차 모드(탭 이름 15:59 확정, 원래 최소 시간. 규칙은 원래 그대로): 동승자 도착(분 반올림) 빠른 순 → 운전자 우회 적은 순 → 환승 적은 순 → 더 오래 같이 타는 순(하차가 늦은 쪽) → 총 보행. 판정도 원래대로(절약 3분 이상 추천, 0초 초과 비슷, 그 외 각자 가기).
  - 함께 가자 모드(탭 이름 15:59 확정, 원래 오래 같이 타기. 15:13 요청 "좀 더 가면 가까운 역·버스정류장이 있으면 그걸 추천"): 최소 시간 1위 대비 동승자 도착 +8분 이내, 운전자 추가 +8분 이내(`STAY_LONGER_TOLERANCE_SEC` = 480초, 15:21 사용자 결정)인 후보 중 같이 타는 시간(출발 → 하차)이 긴 순 → 도착 빠른 순 → 우회 적은 순. 범위 밖은 그 뒤에 최소 시간 순서. 판정 문구는 최소 시간 판정 그대로.
  - 탭을 바꾸면 이미 받은 결과만 다시 정렬한다(TMAP 호출 0회). 캡처: `docs/screens/rank_mode_20261114/`.
- 동승자 경로 고르기(같은 하차점·각자 가기, 2026-11-14 "1분 내에 버스 1번만 탈 수 있으면 타야지"): TMAP이 준 여정 중 가장 빠른 도착 + 60초(`ROUTE_TRANSFER_TOLERANCE_SEC`) 이내에서 환승 적은 순 → 도보 적은 순 → 도착 빠른 순. 하차 후 경로의 '최소시간' 탭도 같은 기준. 예: 논현 근처 하차는 서초03→광역 M6427(환승 1) 대신 16초 늦은 신분당선(환승 0).
- 버스 묶음 표시: 같은 승차·하차 정류장으로 가는 다른 버스 번호를 함께 보여 준다("간선:360 · 간선:452 … 중 먼저 오는 버스"). TMAP 구간의 Lane 목록 + 같은 응답의 다른 여정. 표시만 하고 시간 계산은 그대로. 하차 후 경로 카드·안내 문구에 표시(지도 말풍선은 대표 노선만).
- 운전자 우회 한도: min(15분, 직행시간 × 30%). 넘는 후보는 제외.
- 못 내리는 곳: 경로 중간 하차 후보는 하차점 앞뒤 50m 안에 고속도로·자동차전용도로·터널·교량·고가·지하차도 등이 있으면 제외(TMAP 경로 구간 코드, 추가 호출 없음).
- 도보 직행: 한도 없음(걷는 시간이 길면 동승자 도착이 늦어져 순위에서 밀림). 하차점에서 첫 탑승 지점까지 접근 보행은 10분 이내.
- 목적지 입구(역은 입구를 쓰지 않고 검색한 좌표 그대로)
  - 찾는 순서: ① 주소기반산업지원서비스 내비게이션용DB 출입구(자료 수령 전, 빈 자리) → ② 아파트면 카카오 장소 검색 '<단지명> 정문/후문/주차장 입구/GATE' → ③ 못 찾으면 같은 검색어로 TMAP 장소 검색 → ④ 입구 파일(`assets/entrances/entrances.tsv`: OSM·서울시 차량 출입구 정보, 이름이 맞을 때만).
  - 검색 결과는 저장하지 않는다(요청 때만). 검색 반경 500m는 임시값.
  - 운전자 목적지는 차량 입구 중 출발지에서 실제 경로가 가장 빠른 곳. 동승자는 차가 닿는 입구 모두 후보(주차장 입구 포함).
  - 문 앞 경유에서는 운전자 입구를 다시 골라 운전자 우회가 가장 적은 조합을 쓴다.
  - 하차점은 입구 그 자리. 같은 입구를 가리키는 점(30m 안, 임시값)이 여럿이면 동승자 목적지에 더 가까운 점.
  - 내비(앱 안 안내·Pleos 내비) 목적지와 지도 D 마커는 엔진이 고른 운전자 입구.
- 지도 '내 위치로' 버튼: 지도를 옮겨 보다가 누르면 차 위치로 돌아간다. 안내 중에는 지도를 손으로 옮겼을 때만 나타나고, 누르면 다시 차를 따라간다.
- 모든 시간은 조회 시점의 실시간 TMAP 기준이라 시간대마다 숫자가 다르다(화면에 '현재 교통 기준 HH:mm 조회' 표시). 실제 주행으로는 아직 검증하지 않았다. 변경 경위: `docs/ENTRANCE_CHANGELOG.md`, 시연 준비: `docs/DEMO_SETUP.md`.
## 화면 사양
| 항목 | 값 |
|---|---|
| 기기 | Pleos Connect 에뮬레이터 v2.1.0 (Android Automotive, Android 14 / API 34) |
| 해상도 | 2560 × 1440, 160dpi |
| 방향 | 가로 |
| 기타 | 정차 중에만 입력·비교·설정이 열림 / 지원 Android 9(API 28) 이상 / 실제 차량은 아직 미확인(에뮬레이터에서 확인) |

## 배포 APK와 TMAP 키 (2026-11-13, 11-14 수정)
- 서버 없이 앱 하나로 동작한다. 배포 APK(`apk/J4U_DropMate_release.apk`)에는 개발자 TMAP 키가 기본 키로 들어 있고, 앱 안 호출 예산(`tmap.budget`)과 TMAP 콘솔의 일일 한도로 사용량을 제한한다(2026-11-14 사용자 결정).
- 추천 1회에 TMAP을 약 8~16회 부른다. 기본 키 한도가 차면 앱이 안내하고 더 부르지 않는다.
- 한도가 차면 지도 오른쪽 '설정' 버튼 → 'TMAP 키'에 본인 TMAP 앱 키를 붙여 넣고 저장한다.
  - 정차 중에만 열린다(주행 중에는 열리지 않고, 열려 있다가 주행이 시작되면 닫힌다).
  - 입력한 키는 이 기기 안(SharedPreferences)에만 저장되고, 화면에는 앞 4자리만 보인다.
  - 입력한 키가 있으면 그 키를 먼저 쓰고, '지우기'를 누르면 앱 기본 키로 돌아간다.
  - 바뀌는 것은 쓰는 키뿐이며 경로 계산 로직은 같다. 본인 키를 쓰는 동안에는 앱 안 호출 예산(`tmap.budget`)을 적용하지 않는다(그 키의 한도는 TMAP이 관리).
- 배포 APK에는 카카오 키(무료)가 들어 있어 장소 이름 검색이 된다. 출발지·목적지는 이름으로 검색하거나 '지도' 버튼으로 지도를 찍어 정한다(출발지는 '내 위치'도 가능).

## 안내·음성 (2026-11-14)
- 음성 안내: Pleos TextToSpeech SDK 3.3.1(ON_DEVICE, 권한 `pleos.car.permission.TTS_SERVICE`). 안내 시작·하차점 약 500m 전·하차 도착·동승자 경로 재계산·목적지 도착·회전 안내를 말한다. SDK가 없으면 안드로이드 TTS, 그것도 없으면 주요 문장 녹음 파일(`assets/voice`). 설정에서 음성 안내 켜기/끄기(기본 켬).
- 앱 안 길안내: 3D(기울임 55°, 160m 앞 기준 방향), 내 차는 MapLibre 기본 내비 화살표. 모의 주행은 TMAP 구간 시간 기준 8배속, 0.1초마다 갱신. 지도 마우스 휠은 확대·축소만, 이동은 드래그.

## 향후 계획
- 한도 안에서 동승자 목적지 쪽 역으로 우회 하차: 운전자가 우회 한도 min(15분, 직행×30%) 안에서 동승자 목적지 쪽으로 최대한 가서 목적지와 가까운 역에 내려 주는 후보(제출 후 구현 예정, 추천 1회에 TMAP 호출 약 6~9회 추가 예상).

## 구성
| 모듈 | 내용 |
|---|---|
| `engine/` | 순수 Kotlin 추천 엔진. 정책 설정(`Policy`), GTFS 사전필터(`Gtfs.kt`), 호출 예산·중복방지·중단(`Budget.kt`), 후보 계획·평가·순위·판정(`Engine.kt`), 방식 B 운행편 연결기(`TripConnector.kt`, 합성 검증 전용) |
| `app/` | Jetpack Compose UI, osmdroid 지도, 카카오 REST 장소 검색(`KakaoPlaceSearch.kt`)·기기 현위치, TMAP 클라이언트(`TmapProvider.kt`), 사용량 장부·CSV 출력(`PrefsLedger.kt`) |
| `app/src/main/assets/gtfs/` | 수도권 GTFS 정류장·정차패턴 (KTDB 2025, 사전필터 전용) |
| `tools/` | GTFS 점검·자산 생성 스크립트, CLI 엔진 검증 스크립트 |
| `docs/` | 데이터 점검 보고서, 검증 결과 |

## 실행
1. Android Studio에서 이 폴더(`DropMate`)를 연다 → Gradle Sync (인터넷 필요)
2. 에뮬레이터 또는 기기에서 `app` 실행
3. 출발지(현위치/검색/지도), 운전자 도착지, 동승자 도착지를 확정 → **최적 하차 지점 계산**
4. API 설정은 `app/src/main/assets/api.properties`에서 읽는다. 변경 후 앱을 다시 빌드·설치한다.
   - `tmap.appKey`: TMAP 키 (`AppKeyStore.tmapAppKey()`)
   - `tmap.budget`: TMAP 로컬 호출 예산 (`AppKeyStore.budget()`)
   - `bus.apiKey`: 실시간 버스 API 키 (`AppKeyStore.busApiKey()`)
   - `subway.apiKey`: 실시간 지하철 API 키 (`AppKeyStore.subwayApiKey()`)
   - `kakao.restApiKey`: 카카오 장소 검색 REST API 키 (`AppKeyStore.kakaoRestApiKey()`)
   - 빈 키는 `null`로 처리하며, 버스·지하철 실제 조회 연동은 아직 구현하지 않았다.
   - 장소 입력은 500ms 대기 후 카카오 키워드 검색, 검색 버튼은 즉시 검색한다. 지역·카테고리 제한 없이 정확도 순으로 15개씩 표시하고 `더 보기`로 최대 45개까지 조회한다. 카카오 API가 반환하는 범위 내에서 검색되므로 결과가 많으면 지역명·지점명을 추가한다.
   - 장소 검색에 TMAP·Photon·Nominatim·Geocoder를 사용하지 않는다. 검색어 변경·삭제·장소 확정 시 기존 요청을 취소한다. 키와 API 원문은 로그·결과 파일에 기록하지 않는다.
   - 이 파일은 Git 추적 제외 대상이지만 assets는 APK에 포함된다. 로컬 설정값은 사용자가 직접 관리한다.
   - 최적경로 계산은 `api.properties`의 `tmap.appKey`와 `tmap.budget`만 읽는다. 기존 `tmap.properties`는 삭제했다.

## 검증
- 2026-11-12 같은 하차점 경로 선택지(`engine/.../RouteOptions.kt`): 평가 완료 여정 중 빠른 도착·최소 환승·최소 보행을 고르고 같은 여정은 배지를 병합한다. 하차점 순위·지도·추천 판정은 기존대로 빠른 도착만 사용한다. 합성 검사 9개 추가(전체 43/43 통과), `:app:assembleDebug` 통과, 에뮬레이터 합성 화면 검사 `tools/same-drop-check.init.gradle`(`SameDropOptionsCheck`) 통과, 캡처 `docs/screens/step1_same_drop_options_synthetic.png`. 실제 TMAP 응답으로는 미검증.
- 2026-11-12 여러 하차점 비교(`engine/.../DropComparison.kt`, `RecommendResult.dropRanking()`): 평가한 후보를 선택 기준과 같은 순서로 정렬하고, 제외·보류·미조회 후보는 사유와 함께 뒤에 둔다. 추가 TMAP 호출 없음. 화면에 "하차점 비교" 목록, 지도에 다른 하차점 회색 순위 표식. 합성 검사 3개 추가(전체 46/46), 에뮬레이터 합성 화면 캡처 `docs/screens/step2_*`. 실제 응답 미검증.
- 2026-11-12 Pleos 내비 연결: `engine/.../NavHandoff.kt`(길안내 전달 판단, 정차 적합성 미확인이면 차단 — 현재 모든 후보 미확인), `app/.../nav/NaviBridge.kt` + `src/pleos`(NaviHelper 3.0.5 `addWaypoint`) / `src/nopleos`(연결 없음). 차량용 빌드: `gradlew -PpleosSdk=true :app:assembleDebug`. 합성 검사 3개 추가(49/49). Pleos_Connect_v2.1.0 에뮬레이터(API 34, 내비 서비스 3.0.4)에서 앱 실행·차단 화면 확인(`docs/screens/step3_*`). Pleos 내비 권한(`pleos.car.permission.NAVI_ROUTE`)은 승인 전이라 매니페스트에 넣지 않았고, 실제 경유지 추가는 미검증.
- 2026-11-12 동승자 경로 탭(네이버 방식): 최소시간·최소환승·최소도보 탭으로 ★ 하차점 이후 경로를 바꿔 보고, 지도·범례도 선택 탭 경로로 그린다. 같은 경로는 병합 배지 + "같은 경로" 표시. 순위·★·추천은 최소시간 유지. 합성 검사 1개 추가(50/50), Pleos 에뮬레이터 캡처 `docs/screens/step4_*`. 합성 여정에는 선형이 없어 탭 전환 시 지도 선 변화는 실제 응답으로 미검증.
- 2026-11-12 내비 권한: 사용자 승인으로 차량용 빌드에만 `pleos.car.permission.NAVI_ROUTE` 추가(`app/src/pleos/AndroidManifest.xml`). Pleos 에뮬레이터 확인(`tools/navi-bridge-check.init.gradle`, `NaviBridgeCheck`): 차단 판단 시 SDK 미호출 확인, NaviHelper 연결 성공, `addWaypoint` 호출 시 `SecurityException: NAVI_ROUTE_SEARCH is required` — 경유지 추가에는 NAVI_ROUTE_SEARCH 권한도 필요(미승인, 미추가). 실제 경유지 반영은 미검증.
- 2026-11-12 내비 권한 2: 사용자 승인으로 `NAVI_ROUTE_SEARCH`도 차량용 빌드에 추가. Pleos 에뮬레이터 `NaviBridgeCheck` PASS — 테스트 목적지 설정 후 앱 연결부(`NaviBridge.addDropWaypoint`)로 합성 경유지 추가, 내비 화면에 다음 경유지로 표시되고 `getWaypointInfo` 목록에 이름 확인(`docs/screens/step5_*`). 에뮬레이터는 경유지 좌표를 0,0으로 반환해 좌표 일치는 미확인. 앱 화면의 실제 전달은 정차 적합성 미확인으로 계속 차단.
- 2026-11-12 하차 불가 도로 제외: TMAP 자동차 응답 LineString 의 `roadType`·`facilityType`(tmapRouteDoc 공식 코드표 확인)을 구간별로 저장(`CarRoute.segRoad`). 고속도로(0)·자동차전용(1), 일반도로(0) 외 시설물(교량·터널·고가·지하도로·교차로통과·철도건널목·댐/방파제·토끼굴·한강교량) 위 투영 후보는 계획 단계에서 제외(호출 0). 일반도로·입력 목적지는 길안내 전달 허용, 도로 정보 없으면 차단. 합성 검사 51/51, 파서 확인은 에뮬레이터 계측(`SameDropOptionsCheck`), 캡처 `docs/screens/step6_*`. 실제 TMAP 응답에서 두 필드가 오는지는 미검증.
- 2026-11-12 실제 TMAP 실행(사용자 승인, 2회 · 총 11호출): 남동인더스파크역→논현역(운전자)/망포역(동승자), GTFS 역 좌표. 1차는 GTFS 로드 전 계산이 시작돼 중간 후보 0개 → GTFS 로드 완료 전 계산 보류로 수정. 2차: 직행 경로 783구간 모두 roadType·facilityType 수신, 하차 불가 도로 위 정류장 523개 제외, 최선은 출발지 인근 중간 하차(73분, 세 기준 같은 경로 배지 3개), 단독 대중교통보다 194초 늦어 판정 '이득 없음' → 내비 전달 차단(정상). 1차 결과에서 최소시간↔최소도보 탭 전환 시 지도 선 변경 확인. 캡처 `docs/screens/step7_*`.
- 2026-11-12 새 차량 화면(`ui/DriveScreen.kt`, 목업 v2 배치): 지도 전체 화면 + 왼쪽 레일(검색/비교/안내/자세히) + 떠 있는 패널 + 상단 상태 알약·시계 + 안내 중 하단 막대. 색은 현대차 브랜드로 알려진 남색(#002C5F)·하늘색(#00AAD2)·샌드(#E4DCD3) 계열(공식 가이드 원문 대조 안 함). 지도는 OSM 타일에 밝은 색 필터만 적용. 01 입력 → 02 하차점 추천(동승자 절약·운전자 추가, A/B 후보) → 03 최소시간·최소환승·최소도보 카드 → 04 버튼으로 Pleos 내비에 '운전자 목적지 + 하차 경유지' 안내 요청. 주행 제한은 `car/DrivingMonitor.kt`(CarUxRestrictions, useLibrary android.car). 실제 TMAP·Pleos 에뮬레이터로 01~04 확인(`docs/screens/v2_*`). D 하차일 때 경유지 없이 목적지 안내로 보내는 수정은 미검증. 디버그 APK 안에는 `api.properties` 키가 들어 있으니 외부로 배포하지 말 것.
- 2026-11-12 내 위치(Pleos FusedLocation 1.1.3, 차량용 빌드): 사용자 요청으로 추가. `nav/VehicleLocationFactory.kt` + `PleosLocationListener.java`(Kotlin override 인식 문제로 Java). 권한: AAR 이 `FUSED_LOCATION_BINDING` 병합, 서비스가 `pleos.car.permission.FUSED_LOCATION`(dangerous) 요구 → pleos 매니페스트 추가·앱 시작 시 요청. 에뮬레이터에서 위치 수신·출발지 자동 설정·지도 '내 차' 표식 확인(`docs/screens/v2_location_pleos.png`). 에뮬레이터 GPS 기본값은 미국 Mountain View 이므로 시연 전 `adb emu geo fix <경도> <위도>`로 바꿔야 함(내장 내비는 별도 위치 사용).
- 2026-11-12 앱 안 길안내(사용자 요청: TMAP 레퍼런스처럼): `engine/.../Guidance.kt`(TMAP 자동차 경로 Point 안내점 turnType·nextRoadName 저장, 현재 위치를 경로에 맞춰 다음 회전·그다음·남은 거리·남은 시간·하차 지점까지 거리·이탈 계산, 모의 주행), `ui/NavScreen.kt`(회전 배너·다음 회전·하차 칩·하단 남은 거리/도착 예정/안내 종료, 지난 경로 회색·남은 경로 파랑·차 화살표, 지도 차 따라 이동). turnType 코드표는 tmapRouteDoc 공식 표 기준. 실제 위치 모드는 Pleos 차량 위치, 시연 모드는 경로 구간 시간 8배속 모의 주행(화면에 '시연 모드' 표시). 하차 가능 판단이 막힌 결과는 모의 주행 미리보기만 허용. 자동 재탐색·차선·제한속도·단속카메라 정보는 TMAP 경로 REST 응답에 없어 미구현. 합성 검사 5개 추가(56/56), 실제 TMAP 응답(율동공원→판교)으로 에뮬레이터 모의 주행 확인(`docs/screens/v3_*`). 실제 위치 모드의 주행 중 갱신은 실차 미검증.
- 2026-11-12 안내 중 3D 보기: 진행 방향이 위(헤딩업) + 지도 화면을 뒤로 36° 기울인 원근 보기(Compose graphicsLayer), 2D/3D 버튼으로 전환, 안내 시작 시 3D. 입체 건물이 아닌 기울인 2D 지도임. 에뮬레이터 캡처 `docs/screens/v3_nav_3d.png`.
- 2026-11-12 내비 기능 보강: 음성 안내(`ui/Speaker.kt` 안드로이드 TTS, 문장은 `VoiceGuide` 1km·300m·직전·하차 지점·도착, 음성 켜기/끄기), 교통 혼잡도 색 경로(TMAP `trafficInfo=Y`, LineString traffic 원활 초록·서행 주황·지체/정체 빨강), 실제 위치 모드 자동 재탐색(이탈 5초 지속·30초 간격·안내당 최대 5회, `passList`로 하차 지점 경유 유지, 로컬 장부 차감), 하차 지점 도착 카드(동승자 후속 경로 표시 후 운전자 목적지 안내 계속). 합성 검사 58/58. 에뮬레이터에서 교통 색·하차 도착 카드 확인(`docs/screens/v4_*`). 음성은 에뮬레이터 TTS 엔진(Pleos caas) 존재만 확인, 실제 소리는 미확인. 재탐색은 실제 주행 이탈이 필요해 미검증.
- 2026-11-12 3D 건물 지도(사용자 승인): `ui/MapLibrePane.kt` — MapLibre Android 11.5.2 + OpenFreeMap liberty 스타일(키 없음), building-3d(fill-extrusion, OSM render_height) 레이어로 입체 건물, 안내 중 카메라 진행 방향·60° 기울기. TextureView 모드(SurfaceView 는 Compose 패널을 가림). 경로·하차점·마커는 GeoJSON 레이어. 새 차량 화면만 MapLibre, 기존 화면은 osmdroid 유지. 에뮬레이터에서 지도·경로·하차점 표시 확인(`docs/screens/v5_maplibre_recommend.png`). 안내 중 입체 건물 화면은 에뮬레이터 시스템 재시작·네트워크 실패로 아직 미확인.
- 빌드 JDK: Android Studio 내장 JBR 25는 Gradle 8.11과 맞지 않아 JDK 21(`%USERPROFILE%\.jdks\ms-21.0.13.0.2`)로 빌드했다.
- 카카오 장소 검색 전환: `:app:compileDebugKotlin` 통과. 실제 API 응답·기기 UI 동작은 미검증.
- Android Studio: `./gradlew :engine:test` (합성 검증 32개)
- CLI(그래들 없이): `KOTLIN_ALL_JAR=… KOTLIN_STDLIB=… tools/run_engine_checks.sh`

## TMAP 호출 최소화 규칙 (무조건 준수)
- 요청당 평가 후보 최대 8개(경로 중간 하차 최대 6 + 운전자 목적지 하차 + 동승자 목적지 입구 앞), 요청당 기본 상한 14회. 목적지 입구를 비교할 때만 그만큼(입구별 경로, 문 앞 경유 운전자 입구 재선택 최대 4회) 상한을 올린다
- 중간 하차 후보의 차량 시간은 직행 응답 구간시간으로 계산(추가 호출 0)
- P 경유는 O→P 결과만으로 한도 초과가 증명되면 P→D 호출 생략
- 운전자 한도 초과 후보는 대중교통 조회 생략
- 순차 호출, 자동 재시도 0, 어떤 실패든 이후 호출 중단, 같은 요청 내 완전 동일 호출 재사용, 장기 캐시 없음
- 로컬 장부 잔여가 0이면 호출하지 않음. `tmap.budget`이 비어 있으면 기존 구현대로 로컬 잔여 제한만 해제하며 요청당 상한은 유지

- 2026-11-12 정확도 개선: 중간 하차 최대 6곳을 경로 전체에 고르게 평가, 도보 직행은 한도 없이 동승자 도착 시간으로 비교(대중교통이 너무 가까우면 TMAP 보행자 경로), 음수 우회는 0초. 합성 63/63. 실경로 서문여고 2건 비교 PNG: `Downloads/DropMate_test_png_20261112/COMPARE_algorithm_before_after_v2.png`.

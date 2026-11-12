package com.jiye.dropmate.data

import android.content.Context
import com.jiye.dropmate.engine.*
import java.io.File

/**
 * 경로 판단 기록(Markdown). 결과가 나올 때마다 자동 저장한다.
 * 위치: 앱 외부 파일 폴더/outputs/phase4/live/<요청ID>/decision_log.md
 * API 원문과 appKey 는 기록하지 않는다. 엔진이 만든 파생 값과 판단 근거만 쓴다.
 */
object DecisionLog {
    private fun t(s: Long?) = s?.let { TimeUtil.hms(it) } ?: "미확인"
    private fun sec(s: Number?) = s?.let { "${it}초 (약 ${(it.toLong() + 30) / 60}분)" } ?: "미확인"

    private fun statusKo(s: CandStatus) = when (s) {
        CandStatus.EVALUATED -> "평가 완료(순위 비교 대상)"
        CandStatus.EXCLUDED_DETOUR -> "제외 — 운전자 추가시간 한도 초과"
        CandStatus.EXCLUDED_ACCESS_WALK -> "제외 — 접근 보행 한도 초과(대중교통 접근은 10분)"
        CandStatus.ACCESS_WALK_UNKNOWN -> "보류 — 첫 접근 보행시간 누락"
        CandStatus.WALK_DIRECT_UNRATED -> "보류 — 도보만으로 도착(도보 직행 한도 미확정)"
        CandStatus.NO_ROUTE -> "경로 없음(공급자 응답)"
        CandStatus.FAILED -> "조회 실패"
        CandStatus.NOT_QUERIED_BUDGET -> "미조회 — 예산/호출 한도"
        CandStatus.NOT_QUERIED_HALTED -> "미조회 — 앞선 오류로 호출 중단"
    }

    private fun typeKo(t: CandidateType) = when (t) {
        CandidateType.MIDDLE -> "중간 하차(운전자 경로 위 정류장 부근)"
        CandidateType.D_DROP -> "운전자 목적지(D)에서 하차"
        CandidateType.P_VIA -> "동승자 목적지(P) 경유"
    }

    private fun verdictKo(v: Verdict) = when (v) {
        Verdict.RECOMMEND -> "동승 하차 추천"
        Verdict.SIMILAR -> "단독 이동과 비슷함(작은 차이)"
        Verdict.NO_GAIN -> "따로 이동하는 편이 빠르거나 같음"
        Verdict.NO_CANDIDATE -> "조건을 만족하는 하차 경로 없음"
        Verdict.INCOMPLETE -> "비교 불완전"
    }

    private fun legLine(l: TransitLeg): String {
        val kind = when (l.mode) {
            LegMode.WALK -> "도보"; LegMode.BUS -> "버스"; LegMode.EXPRESSBUS -> "광역버스"; LegMode.SUBWAY -> "지하철"
            LegMode.TRAIN -> "철도"; LegMode.AIRPLANE -> "항공"; LegMode.FERRY -> "선박"; LegMode.OTHER -> "기타"
        }
        val route = if (l.mode == LegMode.WALK) "${l.distanceM ?: "?"}m" else (l.route ?: "노선 미확인")
        return "$kind $route — ${l.startName ?: "?"} → ${l.endName ?: "?"} · ${sec(l.sectionTimeSec)}"
    }

    fun write(ctx: Context, r: RecommendResult, places: Map<String, Pair<String, LatLng>>): File {
        val dir = File(ctx.getExternalFilesDir(null), "outputs/phase4/live/${r.requestId}").apply { mkdirs() }
        val f = File(dir, "decision_log.md")
        val md = buildString {
            appendLine("# DropMate 경로 판단 기록 — ${r.requestId}")
            appendLine()
            appendLine("- 기준시각(t0): ${t(r.t0EpochSec)}")
            appendLine("- 정책 버전: ${r.policyVersion}")
            appendLine("- 데이터: ${r.dataVersion}")
            appendLine("- TMAP 실제 호출 ${r.callsSent}회 · 중복 방지 ${r.dedupSaved}회" + (r.haltedBy?.let { " · 호출 중단 사유: $it" } ?: ""))
            appendLine()

            appendLine("## 1. 입력")
            places.forEach { (role, v) -> appendLine("- $role: ${v.first} (${v.second})") }
            appendLine()

            appendLine("## 2. 결론")
            appendLine("- 목적: 동승자의 출발부터 최종 목적지까지 도착시간 최소화")
            appendLine("- 제약: 운전자 직행 대비 추가 min(900초, 직행×30%) 이내 / 첫 탑승지점 접근 도보 600초 이내")
            appendLine("- 판정: **${verdictKo(r.verdict)}**" + if (r.provisional) " (잠정)" else "")
            appendLine("- 판정 이유: ${r.verdictReason}")
            r.best?.let { appendLine("- 선택된 경로: **${it.plan.label}** (${typeKo(it.plan.type)})") } ?: appendLine("- 선택된 경로: 없음")
            r.savingSec?.let { appendLine("- 단독 이동 대비 동승자 도착 차이: ${sec(it)}") }
            appendLine("- 선택 범위: ${r.bestScope}")
            appendLine()

            appendLine("## 3. 판단 과정")
            appendLine("### 3-1. 기준 경로 조회")
            r.baseline?.let { appendLine("- 운전자 직행(O→D): ${sec(it.totalTimeSec)}, ${it.totalDistanceM}m") } ?: appendLine("- 운전자 직행(O→D): 조회되지 않음")
            r.solo?.let { s ->
                appendLine("- 동승자 단독 대중교통(O→P): ${statusKo(s.status)} — 도착 ${t(s.arrivalEpochSec)}" +
                    (s.chosen?.itinerary?.transferCount?.let { " · 환승 ${it}회" } ?: ""))
                appendLine("  - 근거: ${s.reason}")
            } ?: appendLine("- 동승자 단독 대중교통(O→P): 조회되지 않음")
            appendLine()

            appendLine("### 3-2. 후보 생성(GTFS 2025 사전 필터 — 우선순위 산정 전용, 시간 계산에는 쓰지 않음)")
            if (r.prefilterCounts.isEmpty()) appendLine("- 사전 필터 집계 없음")
            r.prefilterCounts.forEach { (k, v) -> appendLine("- $k: ${v}개") }
            appendLine()

            appendLine("### 3-3. 후보별 평가")
            if (r.candidates.isEmpty()) appendLine("- 평가된 후보 없음")
            r.candidates.forEachIndexed { i, c ->
                val star = if (r.best?.plan?.id == c.plan.id) " ★ 선택" else ""
                appendLine("#### 후보 ${i + 1}. ${c.plan.label}$star")
                appendLine("- 유형: ${typeKo(c.plan.type)}")
                appendLine("- GTFS 사전 필터: ${c.plan.prefilter} · 연결 ${c.plan.gtfsLink}" + (c.plan.gtfsTransfers?.let { " · GTFS 환승 ${it}회" } ?: ""))
                appendLine("- 상태: ${statusKo(c.status)}")
                appendLine("- 하차 지점 도로: ${stopSuitabilityText(c.plan.stopSuitability)}")
                appendLine("- 사유: ${c.reason}")
                c.driverAddSec?.let { appendLine("- 운전자 추가시간: ${sec(it)}" + (if (c.nearLimit) " (한도 근접)" else "") + (c.driverAddBasis?.let { b -> " — 근거: $b" } ?: "")) }
                c.dropCompleteEpochSec?.let { appendLine("- 하차 완료: ${t(it)} · 출발부터 경과 ${sec(it - r.t0EpochSec)}") }
                c.passengerArrivalEpochSec?.let { arrival ->
                    appendLine("- 출발부터 최종 도착까지: ${sec(arrival - r.t0EpochSec)}")
                    c.dropCompleteEpochSec?.let { drop -> appendLine("- 하차 완료 이후 최종 도착까지: ${sec(arrival - drop)} (별도 실시간 대기 가산 없음)") }
                }
                c.passengerArrivalEpochSec?.let { appendLine("- 동승자 최종 도착: ${t(it)}" + (c.transferCount?.let { n -> " · 환승 ${n}회" } ?: "") + (c.totalWalkSec?.let { w -> " · 총 도보 ${sec(w)}" } ?: "")) }
                c.chosen?.itinerary?.firstAccessWalkSec?.let { appendLine("- 첫 탑승 역·정류장 접근 보행: ${sec(it)} / 한도 600초") }
                c.searchDttm?.let { appendLine("- 대중교통 조회 기준 searchDttm=$it, 하차 시각과의 정렬 차 ${c.alignDiffSec ?: "?"}초") }
                c.chosen?.itinerary?.legs?.takeIf { it.isNotEmpty() }?.let { legs ->
                    appendLine("- 동승자 구간:")
                    legs.forEach { appendLine("  - ${legLine(it)}") }
                }
                if (c.routeOptions.isNotEmpty()) {
                    appendLine("- 같은 하차점 경로 선택지(순위는 빠른 도착만 사용, 같은 여정은 배지 병합):")
                    c.routeOptions.forEach { o ->
                        val it2 = o.itinerary.itinerary
                        appendLine("  - [${o.kinds.joinToString("·") { k -> k.label }}] 응답 ${o.itinerary.index + 1}번 여정 · 도착 " +
                            (o.itinerary.arrivalEpochSec?.let { a -> t(a) } ?: "미확인") +
                            " · 환승 ${it2.transferCount?.let { n -> "${n}회" } ?: "미확인"} · 총 도보 ${it2.totalWalkTimeSec?.let { w -> sec(w) } ?: "미확인"}")
                    }
                }
                appendLine("- 사용한 TMAP 호출: ${c.callsUsed}회")
                appendLine()
            }

            appendLine("### 3-4. 최종 선택 근거")
            val ranked = r.candidates.filter { it.rankable }.sortedWith(compareBy<CandidateResult>(
                { kotlin.math.floor(it.passengerArrivalEpochSec!! / 60.0 + 0.5).toLong() },
                { it.driverArrivalEpochSec!! }, { it.totalWalkSec ?: Int.MAX_VALUE }, { it.plan.id }
            ))
            if (ranked.isEmpty()) appendLine("- 순위 비교가 가능한(평가 완료) 후보가 없어 경로를 고르지 못했습니다.")
            else {
                appendLine("- 평가 완료 ${ranked.size}개를 동승자 도착시각의 분 반올림 값으로 비교했습니다. 동률이면 운전자 도착시각 → 총 보행시간 → 후보 ID 순입니다. 전체 지역의 전역 최적을 보장하지 않습니다.")
                appendLine("- 실시간 운행편·탑승 가능·대기는 아직 검증하지 않았습니다. TMAP 시간에 별도 대기를 더하지 않았습니다.")
                ranked.forEachIndexed { i, c -> appendLine("  ${i + 1}. ${c.plan.label} — 동승자 도착 ${t(c.passengerArrivalEpochSec)}, 운전자 추가 ${sec(c.driverAddSec)}") }
                r.best?.let { best ->
                    ranked.filter { it.plan.id != best.plan.id }.forEach { other ->
                        val bestMinute = kotlin.math.floor(best.passengerArrivalEpochSec!! / 60.0 + 0.5).toLong()
                        val otherMinute = kotlin.math.floor(other.passengerArrivalEpochSec!! / 60.0 + 0.5).toLong()
                        val why = when {
                            bestMinute != otherMinute -> "동승자 도착 분 반올림 값이 더 빠름"
                            best.driverArrivalEpochSec != other.driverArrivalEpochSec -> "동승자 도착 분 동률 → 운전자 도착이 더 빠름"
                            best.totalWalkSec != other.totalWalkSec -> "동승자·운전자 순위 동률 → 총 보행시간이 더 짧음(누락값 후순위)"
                            else -> "시간·보행 동률 → 후보 ID 순으로 결정"
                        }
                        appendLine("- ${other.plan.label} 대신 ${best.plan.label} 선택: $why. 원본 동승자 도착 차이(다른 후보−선택 후보) ${sec(other.passengerArrivalEpochSec!! - best.passengerArrivalEpochSec!!)}.")
                    }
                    if (ranked.size == 1) appendLine("- 평가 가능한 후보가 하나뿐이므로 선택했습니다. 다른 후보보다 빠름이 입증된 것은 아닙니다.")
                }
                val solo = r.solo?.arrivalEpochSec
                r.best?.passengerArrivalEpochSec?.let { b ->
                    if (solo != null) appendLine("- 선택 경로의 동승자 도착(${t(b)})을 단독 대중교통 도착(${t(solo)})과 비교: 차이 ${sec(solo - b)} → ${verdictKo(r.verdict)}")
                }
            }
            if (r.notes.isNotEmpty()) {
                appendLine()
                appendLine("## 4. 참고")
                r.notes.forEach { appendLine("- $it") }
            }
            appendLine()
            appendLine("> 개발 비교 후보입니다. 실제 정차 가능 여부와 안전한 하차 가능 여부는 확인되지 않았습니다.")
        }
        f.writeText(md)
        return f
    }
}

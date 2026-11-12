package com.jiye.dropmate.nav

import com.jiye.dropmate.engine.HandoffDecision
import com.jiye.dropmate.engine.LatLng

/**
 * 차량 내비로 하차 지점을 경유지로 넘기는 연결부.
 * 실제 구현은 빌드 옵션으로 고른다: -PpleosSdk=true 이면 Pleos NaviHelper, 아니면 연결 없음.
 * 구현은 HandoffDecision.allowed 가 false 이면 SDK 를 초기화하거나 호출하지 않는다.
 */
interface NaviBridge {
    /** 이 빌드에 차량 내비 연결이 들어 있는지 */
    val available: Boolean
    val label: String
    /** 진행 중인 안내에 하차 지점을 첫 경유지로 추가. 성공 시 안내 문구, 실패·차단 시 예외 */
    suspend fun addDropWaypoint(name: String, point: LatLng, decision: HandoffDecision): String
    /**
     * 운전자 목적지로 가는 새 안내를 요청하면서 하차 지점을 첫 경유지로 넣는다(사용자가 버튼을 누를 때만).
     * 안내 중이 아니어도 동작한다. 성공 시 안내 문구, 실패·차단 시 예외
     */
    suspend fun startDropRoute(dropName: String, drop: LatLng, destName: String, dest: LatLng, decision: HandoffDecision): String
    fun close()
}

class HandoffBlocked(reasons: List<String>) : IllegalStateException("길안내 전달 차단: " + reasons.joinToString(" / "))

package com.jiye.dropmate.car

import com.jiye.dropmate.engine.LatLng

/** 차량 위치 한 점. heading 은 북쪽 기준 도(°), 없으면 null */
data class VehicleFix(val pos: LatLng, val heading: Float?, val timeMillis: Long, val source: String)

/**
 * 차량 위치 공급원. 차량용 빌드(-PpleosSdk=true)는 Pleos FusedLocation SDK, 기본 빌드는 없음.
 * 실제 위치와 시뮬레이션 위치를 구분하지 못하므로 화면에는 공급원 이름과 받은 시각을 함께 표시한다.
 */
interface VehicleLocationSource {
    val available: Boolean
    val label: String
    fun start(onFix: (VehicleFix) -> Unit, onError: (String) -> Unit)
    fun stop()
}

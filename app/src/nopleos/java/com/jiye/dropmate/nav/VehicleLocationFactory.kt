package com.jiye.dropmate.nav

import android.content.Context
import com.jiye.dropmate.car.VehicleFix
import com.jiye.dropmate.car.VehicleLocationSource

/** 기본 빌드: Pleos 차량 위치 없음(기기 위치는 기존 '내 위치' 기능 사용) */
fun createVehicleLocation(context: Context): VehicleLocationSource = object : VehicleLocationSource {
    override val available = false
    override val label = "Pleos 차량 위치 미포함 빌드"
    override fun start(onFix: (VehicleFix) -> Unit, onError: (String) -> Unit) {}
    override fun stop() {}
}

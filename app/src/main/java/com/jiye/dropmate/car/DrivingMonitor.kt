package com.jiye.dropmate.car

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 차량 주행 제한 상태(Android Automotive CarUxRestrictionsManager).
 * restricted=true 이면 주행 중(주의 분산 제한)으로 보고 입력·비교 화면을 막는다.
 * 차량이 아닌 기기이거나 서비스 연결에 실패하면 available=false, restricted=false(정차로 간주하지 않고 '확인 불가'로 표시).
 */
class DrivingMonitor(private val ctx: Context) {
    var available by mutableStateOf(false); private set
    var restricted by mutableStateOf(false); private set
    private var car: android.car.Car? = null

    fun start() {
        if (!ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return
        try {
            val c = android.car.Car.createCar(ctx) ?: return
            val m = c.getCarManager(android.car.Car.CAR_UX_RESTRICTION_SERVICE) as android.car.drivingstate.CarUxRestrictionsManager
            restricted = m.currentCarUxRestrictions?.isRequiresDistractionOptimization == true
            m.registerListener { r -> restricted = r.isRequiresDistractionOptimization }
            car = c; available = true
        } catch (t: Throwable) { available = false }
    }

    fun stop() { runCatching { car?.disconnect() }; car = null }
}

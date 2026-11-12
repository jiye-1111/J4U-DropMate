package com.jiye.dropmate.nav

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.jiye.dropmate.car.VehicleFix
import com.jiye.dropmate.car.VehicleLocationSource
import com.jiye.dropmate.engine.LatLng

fun createVehicleLocation(context: Context): VehicleLocationSource = PleosVehicleLocation(context.applicationContext)

/**
 * Pleos FusedLocation 1.1.3 (배포 AAR javap 로 확인한 메서드: initialize, isSupported, getLastKnownLocation,
 * registerFusedLocationCallback, unregisterFusedLocationCallback, release / LocationData: latitude, longitude, heading, timestampMillis).
 * 서비스 연결은 비동기일 수 있어 초기화 직후 1회 + 짧은 지연 후 재시도로 마지막 위치를 받는다(자동 재시도는 2회까지).
 */
private class PleosVehicleLocation(private val ctx: Context) : VehicleLocationSource {
    override val available = true
    override val label = "Pleos 차량 위치(FusedLocation 1.1.3)"
    private var fl: FusedLocation? = null
    private var listener: FusedLocationListener? = null
    private val main = Handler(Looper.getMainLooper())

    override fun start(onFix: (VehicleFix) -> Unit, onError: (String) -> Unit) {
        if (fl != null) return
        try {
            val f = FusedLocation(ctx)
            f.initialize()
            fl = f
            val l = PleosLocationListener(object : PleosLocationListener.Sink {
                override fun fix(lat: Double, lon: Double, heading: Float, timeMillis: Long) = onFix(VehicleFix(LatLng(lat, lon), heading, timeMillis, label))
                override fun error(message: String) = onError("Pleos 위치 오류: $message")
            })
            listener = l
            fun attempt(n: Int) {
                val cur = fl ?: return
                try {
                    if (!cur.isSupported()) { onError("이 차량(에뮬레이터)은 Pleos 위치를 지원하지 않아요"); return }
                    cur.getLastKnownLocation(l)
                    cur.registerFusedLocationCallback(l)
                } catch (e: Exception) {
                    if (n < 2) main.postDelayed({ attempt(n + 1) }, 1500) else onError("Pleos 위치 연결 실패: ${e.javaClass.simpleName}")
                }
            }
            main.postDelayed({ attempt(0) }, 800)
        } catch (t: Throwable) {
            onError("Pleos 위치 초기화 실패: ${t.javaClass.simpleName}")
        }
    }

    override fun stop() {
        runCatching { listener?.let { fl?.unregisterFusedLocationCallback(it) } }
        runCatching { fl?.release() }
        fl = null; listener = null
    }
}

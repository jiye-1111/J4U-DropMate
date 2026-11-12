package com.jiye.dropmate

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jiye.dropmate.engine.*
import com.jiye.dropmate.ui.*
import com.jiye.dropmate.data.DecisionLog

/** 합성 결과로 UI/Markdown 검사. 실제 API 호출 없음. */
class RouteDesignCheck : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val out = Bundle()
        try {
            val t = 1800000000L
            val o = LatLng(37.54, 126.94)
            val s = LatLng(37.55, 126.93)
            val p = LatLng(37.56, 126.91)
            val d = LatLng(37.57, 126.95)
            val legs = listOf(
                TransitLeg(LegMode.WALK, null, "하차점", "합성역", s, s, 200, 180, listOf(s, LatLng(37.552,126.93))),
                TransitLeg(LegMode.SUBWAY, "5호선", "합성역", "환승역", LatLng(37.552,126.93), LatLng(37.558,126.915), 2000, 600, listOf(LatLng(37.552,126.93),LatLng(37.558,126.915)), "996CAC"),
                TransitLeg(LegMode.BUS, "2012", "환승역", "목적지", LatLng(37.558,126.915), p, 500, 300, listOf(LatLng(37.558,126.915),p), "5BB025")
            )
            val itin = TransitItinerary(1080,180,200,1,legs)
            val plan = PlannedCandidate("synthetic", CandidateType.MIDDLE,s,"합성역 인근 하차",120.0,null,null,GtfsLink.DIRECT,0,PrefilterState.QUERY_TARGET,"test",0.0)
            val chosen = EvaluatedItinerary(0,itin,t+1260,CandStatus.EVALUATED,"합성 검증")
            val best = CandidateResult(plan,CandStatus.EVALUATED,"합성 검증",dropCompleteEpochSec=t+180,driverAddSec=60,driverArrivalEpochSec=t+1860,passengerArrivalEpochSec=t+1260,totalWalkSec=180,transferCount=1,chosen=chosen)
            val base = CarRoute(1800,5000,listOf(o,s,d),listOf(0.0,120.0,1800.0),listOf(0.0,1000.0,5000.0))
            val r = RecommendResult("UI-SYNTHETIC","test",t,"synthetic",Verdict.RECOMMEND,"합성 데이터 화면 검사",best,"합성 후보",true,600,SoloResult(CandStatus.EVALUATED,"합성",t+1860,chosen),base,listOf(best),emptyMap(),emptyList(),0,0,null,emptyList())
            check(legArgb(legs[1]) == 0xFF996CAC.toInt())
            check(legArgb(legs[2]) == 0xFF5BB025.toInt())
            check(legArgb(legs[0]) == WALK_LINE_ARGB)
            val file = DecisionLog.write(targetContext,r,emptyMap())
            val md = file.readText()
            check(md.contains("첫 탑승 역·정류장 접근 보행"))
            check(md.contains("평가 가능한 후보가 하나"))
            check(md.contains("1260초"))
            file.delete()
            val activity = startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            runOnMainSync {
                activity.setContent {
                    MaterialTheme(colorScheme = darkColorScheme()) {
                        Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                            MapPane(Modifier.weight(0.62f).fillMaxHeight(),emptyMap(),r,null,emptyList(),{}, null, {})
                            Column(Modifier.weight(0.38f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                                androidx.compose.material3.Text("합성 데이터 · 실제 경로 아님")
                                RouteComparison(r)
                                ResultPanel(r)
                            }
                        }
                    }
                }
            }
            waitForIdleSync()
            Thread.sleep(1500)
            out.putString("design_check","PASS: subway/bus/walk colors, comparison UI rendered, Markdown rationale saved and verified; zero API calls")
            finish(-1,out)
        } catch(e: Throwable) {
            out.putString("design_check","FAIL: ${e.javaClass.simpleName}: ${e.message}")
            finish(0,out)
        }
    }
}

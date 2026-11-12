package com.jiye.dropmate.data

import android.content.Context
import com.jiye.dropmate.engine.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 영속 사용량 장부(SharedPreferences). 키 값은 저장하지 않는다.
 * 로컬 예산(remaining)은 사용자가 직접 입력한 값만 쓰며, 새 키·날짜 변경으로 자동 충전하지 않는다.
 */
class PrefsLedger(ctx: Context) : CallLedger {
    private val sp = ctx.getSharedPreferences("dropmate_call_ledger", Context.MODE_PRIVATE)

    override fun localRemaining(): Int? = if (sp.contains("remaining")) sp.getInt("remaining", 0) else null

    fun setRemaining(n: Int) { sp.edit().putInt("remaining", n).putString("remaining_set_at", now()).apply() }

    /**
     * api.properties 의 tmap.budget 을 장부에 반영한다(개발자 설정이 기준).
     * - null(빈 값): 로컬 잔여 제한을 해제한다. 요청당 호출 상한(Policy.maxCallsPerRequest)만 적용된다.
     * - 값이 지난번에 반영한 값과 다를 때만 잔여를 그 값으로 다시 설정한다(같은 값이면 계속 차감).
     *   다시 채우려면 파일의 tmap.budget 을 다른 숫자로 바꾼다.
     * @return 이번에 잔여를 다시 설정했으면 true
     */
    fun applyConfiguredBudget(budget: Int?): Boolean {
        if (budget == null) {
            if (sp.contains("remaining") || sp.contains("budget_applied")) sp.edit().remove("remaining").remove("budget_applied").commit()
            return false
        }
        if (sp.contains("budget_applied") && sp.getInt("budget_applied", -1) == budget) return false
        sp.edit().putInt("remaining", budget).putInt("budget_applied", budget).putString("remaining_set_at", now()).commit()
        return true
    }
    fun totalSent(): Int = sp.getInt("total_sent", 0)
    fun lastBlock(): String? = sp.getString("last_block", null)

    @Synchronized
    override fun record(entry: CallRecord) {
        val e = sp.edit()
        if (entry.sentToProvider) {
            e.putInt("total_sent", totalSent() + 1)
            localRemaining()?.let { e.putInt("remaining", it - 1) }
            val day = SimpleDateFormat("yyyyMMdd", Locale.KOREA).apply { timeZone = TimeZone.getTimeZone("Asia/Seoul") }.format(Date())
            e.putInt("sent_$day", sp.getInt("sent_$day", 0) + 1)
        }
        if (entry.outcome.contains("AUTH") || entry.outcome.contains("QUOTA")) e.putString("last_block", "${now()} ${entry.outcome}") // 차단 이력 보존
        val log = (sp.getString("log", "") ?: "").lines().filter { it.isNotBlank() }.takeLast(299) +
            listOf(listOf(now(), entry.requestId, entry.seq, entry.kind, entry.purpose, entry.timeParam, entry.sentToProvider, entry.outcome).joinToString("\t"))
        e.putString("log", log.joinToString("\n"))
        e.apply()
    }

    private fun now() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.KOREA).format(Date())
}

/** 실행별 파생 결과 CSV (UTF-8 BOM). API 원문은 저장하지 않는다. 위치: 앱 외부 파일 폴더/outputs/phase4/live/<requestId>/ */
object ResultExporter {
    private fun q(v: Any?): String = if (v == null) "미확인" else "\"" + v.toString().replace("\"", "\"\"") + "\""

    fun export(ctx: Context, r: RecommendResult): File {
        val dir = File(ctx.getExternalFilesDir(null), "outputs/phase4/live/${r.requestId}").apply { mkdirs() }
        val bom = "﻿"
        File(dir, "candidates.csv").writeText(bom + buildString {
            appendLine("request_id,policy_version,candidate_id,type,label,gtfs_prefilter,gtfs_link,gtfs_transfers,status,reason,drop_complete,driver_add_sec,driver_add_basis,search_dttm,align_sec,passenger_arrival,total_walk_sec,transfer_count,calls_used,data_source")
            for (c in r.candidates) appendLine(listOf(r.requestId, r.policyVersion, c.plan.id, c.plan.type, c.plan.label, c.plan.prefilter, c.plan.gtfsLink, c.plan.gtfsTransfers,
                c.status, c.reason, c.dropCompleteEpochSec?.let { TimeUtil.hms(it) }, c.driverAddSec, c.driverAddBasis, c.searchDttm, c.alignDiffSec,
                c.passengerArrivalEpochSec?.let { TimeUtil.hms(it) }, c.totalWalkSec, c.transferCount, c.callsUsed,
                if (c.callsUsed > 0 || c.plan.type == CandidateType.D_DROP) "TMAP+GTFS(2025,사전필터)" else "GTFS(2025,사전필터)").joinToString(",") { q(it) })
        })
        File(dir, "calls.csv").writeText(bom + buildString {
            appendLine("request_id,seq,kind,purpose,origin,dest,time_param,sent_to_provider,outcome,dedup_hit,data_source,status")
            for (c in r.calls) appendLine(listOf(c.requestId, c.seq, c.kind, c.purpose, c.origin, c.dest, c.timeParam, c.sentToProvider, c.outcome, c.dedupHit, "TMAP", c.outcome).joinToString(",") { q(it) })
        })
        File(dir, "summary.csv").writeText(bom + buildString {
            appendLine("request_id,policy_version,t0,verdict,verdict_reason,best,best_scope,provisional,saving_sec,calls_sent,dedup_saved,halted_by,data_version,data_source,status")
            appendLine(listOf(r.requestId, r.policyVersion, TimeUtil.hms(r.t0EpochSec), r.verdict, r.verdictReason, r.best?.plan?.label, r.bestScope, r.provisional, r.savingSec,
                r.callsSent, r.dedupSaved, r.haltedBy, r.dataVersion, "TMAP+GTFS", r.verdict).joinToString(",") { q(it) })
            r.prefilterCounts.forEach { (k, v) -> appendLine(listOf("prefilter", k, v).joinToString(",") { q(it) }) }
        })
        return dir
    }
}

package com.jiye.dropmate.engine

/** 영속 사용량 장부. 앱은 SharedPreferences 구현, 테스트는 메모리 구현. 키 값은 절대 기록하지 않는다. */
interface CallLedger {
    /** 로컬 승인 예산 잔여. null = 로컬 예산 미설정 → 로컬 잔여 제한 없음(요청당 호출 상한은 항상 적용) */
    fun localRemaining(): Int?
    fun record(entry: CallRecord)
}

class MemoryLedger(private var remaining: Int?) : CallLedger {
    val records = mutableListOf<CallRecord>()
    override fun localRemaining(): Int? = remaining
    override fun record(entry: CallRecord) {
        records.add(entry)
        if (entry.sentToProvider) remaining = remaining?.minus(1)
    }
}

enum class CallKind { CAR, TRANSIT, WALK, POI }

data class CallRecord(
    val requestId: String,
    val seq: Int,
    val kind: CallKind,
    val purpose: String,
    val origin: LatLng,
    val dest: LatLng,
    /** 자동차: 요청 의도 출발시각(epoch) / 대중교통: searchDttm */
    val timeParam: String,
    /** 실제로 공급자에게 요청을 보냈는가(예산 차감 대상) */
    val sentToProvider: Boolean,
    val outcome: String,
    val dedupHit: Boolean = false,
)

/**
 * 모든 TMAP 호출이 지나가는 관문.
 * - 요청당 상한, 로컬 장부 잔여, 중단 상태를 호출 전에 검사한다.
 * - 완전히 같은 요청(종류·좌표·시각 파라미터)은 같은 요청 안에서만 재사용한다(장기 캐시 없음).
 * - 인증·한도·취소·네트워크 등 실패가 한 번이라도 나면 이후 호출을 모두 중단한다(자동 재시도 없음).
 */
class CallGateway(
    private val provider: RouteProvider,
    private val policy: Policy,
    private val ledger: CallLedger,
    private val requestId: String,
    private val isCancelled: () -> Boolean = { false },
) {
    var sent = 0; private set
    var dedupSaved = 0; private set
    var haltedBy: String? = null; private set
    val log = mutableListOf<CallRecord>()
    private val memo = HashMap<String, ProviderResult<*>>()

    val remainingThisRequest: Int get() = policy.maxCallsPerRequest - sent

    fun canCall(n: Int = 1): Boolean {
        if (haltedBy != null || isCancelled()) return false
        val local = ledger.localRemaining()
        return sent + n <= policy.maxCallsPerRequest && (local == null || local >= n)
    }

    private fun k(kind: CallKind, o: LatLng, d: LatLng, t: String) = "$kind|$o|$d|$t"

    @Suppress("UNCHECKED_CAST")
    private fun <T> run(kind: CallKind, purpose: String, o: LatLng, d: LatLng, t: String, call: () -> ProviderResult<T>): ProviderResult<T> {
        val key = k(kind, o, d, t)
        memo[key]?.let {
            dedupSaved++
            rec(kind, purpose, o, d, t, false, "DEDUP_REUSE", true)
            return it as ProviderResult<T>
        }
        if (isCancelled()) { haltedBy = haltedBy ?: "CANCELLED"; rec(kind, purpose, o, d, t, false, "NOT_SENT_CANCELLED"); return ProviderResult.Fail(FailKind.CANCELLED, "사용자 취소") }
        if (haltedBy != null) { rec(kind, purpose, o, d, t, false, "NOT_SENT_HALTED($haltedBy)"); return ProviderResult.Fail(FailKind.BUDGET, "앞선 오류로 호출 중단: $haltedBy") }
        val local = ledger.localRemaining()
        if ((local != null && local <= 0) || sent >= policy.maxCallsPerRequest) { rec(kind, purpose, o, d, t, false, "NOT_SENT_BUDGET"); return ProviderResult.Fail(FailKind.BUDGET, "예산 소진(요청당 ${policy.maxCallsPerRequest}회, 로컬 잔여 $local)") }
        sent++
        val r = try { call() } catch (e: Exception) { ProviderResult.Fail(FailKind.OTHER, e.javaClass.simpleName) }
        val outcome = when (r) {
            is ProviderResult.Ok -> "OK"
            is ProviderResult.NoRoute -> "NO_ROUTE(${r.code})"
            is ProviderResult.Fail -> "FAIL(${r.kind})"
        }
        if (r is ProviderResult.Fail) haltedBy = "${r.kind}"
        rec(kind, purpose, o, d, t, true, outcome)
        if (r !is ProviderResult.Fail) memo[key] = r
        return r
    }

    private fun rec(kind: CallKind, purpose: String, o: LatLng, d: LatLng, t: String, sentNow: Boolean, outcome: String, dedup: Boolean = false) {
        val r = CallRecord(requestId, log.size + 1, kind, purpose, o, d, t, sentNow, outcome, dedup)
        log.add(r); ledger.record(r)
    }

    fun car(purpose: String, o: LatLng, d: LatLng, departEpochSec: Long) =
        run(CallKind.CAR, purpose, o, d, departEpochSec.toString()) { provider.car(o, d, departEpochSec) }

    fun transit(purpose: String, o: LatLng, d: LatLng, searchDttm: String) =
        run(CallKind.TRANSIT, purpose, o, d, searchDttm) { provider.transit(o, d, searchDttm) }

    fun walk(purpose: String, o: LatLng, d: LatLng) =
        run(CallKind.WALK, purpose, o, d, "-") { provider.walk(o, d) }
}

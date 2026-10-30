package com.roadoffice.app.workmile

enum class EnergyKind(val label: String) { EV_CHARGE("충전"), FUEL("주유") }

/** 주차 중 배터리·연료가 오른 기록 — 금액은 사용자가 확인해 넣는다 (차량 신호로는 결제액을 알 수 없음). */
data class Refill(val kind: EnergyKind, val startedAt: Long, val fromPct: Int, val toPct: Int) {
    val risePct: Int get() = toPct - fromPct
}

/**
 * 주유·충전 자동 포착. 주차(P) 동안의 최저값 대비 최고값이 [minRisePct]%p 이상 오르면 주유·충전으로 본다.
 * - 주차 중에는 진행 중인 값을 돌려줘 화면에 "충전 중 55% → 71%" 를 바로 보여 준다
 * - 출발(P 해제) 때 최종 값을 돌려주고 초기화한다
 * - 센서 흔들림(1~2%p)·주행 중 변화는 무시한다
 */
class EnergyWatcher(private val kind: EnergyKind, private val minRisePct: Int = 5) {
    private var parkedAt: Long? = null
    private var low: Int? = null      // 지금까지의 최저값
    private var from: Int? = null     // 가장 크게 오른 구간의 시작
    private var to: Int? = null       // 가장 크게 오른 구간의 끝

    fun onGear(gear: Gear, now: Long, pct: Int?): Refill? {
        if (gear == Gear.UNKNOWN) return null
        if (gear == Gear.PARK) {
            if (parkedAt == null) { parkedAt = now; onLevel(pct, now) }
            return null
        }
        val done = current()
        parkedAt = null; low = null; from = null; to = null
        return done
    }

    /**
     * 주차 중 값 갱신 — 기준을 넘었으면 진행 중인 주유·충전을 돌려준다.
     * "최저값 → 그 뒤의 최고값" 상승폭만 본다 (높은 값으로 주차했다가 공조로 조금씩 줄어든 것을 충전으로 오인하지 않게).
     */
    fun onLevel(pct: Int?, now: Long): Refill? {
        val v = pct.valid() ?: return null
        if (parkedAt == null) return null
        val l = minOf(low ?: v, v).also { low = it }
        if (from == null || v - l > (to ?: 0) - (from ?: 0)) { from = l; to = v }
        return current()
    }

    private fun current(): Refill? {
        val f = from ?: return null
        val t = to ?: return null
        val at = parkedAt ?: return null
        return if (t - f >= minRisePct) Refill(kind, at, f, t) else null
    }

    private fun Int?.valid(): Int? = this?.takeIf { it in 0..100 }
}

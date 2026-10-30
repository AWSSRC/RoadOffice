package com.roadoffice.app.meeting

import com.roadoffice.app.workmile.Gear

/**
 * 회고 시점 판단 — "미팅 어땠어요?" 는 주행 중이 아니라 미팅을 마치고 **차에 다시 탄 순간**(P단·운전석 착석)에 묻는다.
 *
 * - P 상태에서 운전석이 비면 "자리를 떠난 시각"을 적는다
 * - P 상태에서 다시 앉았고 [minAwayMs] 이상 떠나 있었다면 → 지금 묻는다 (true)
 * - 잠깐 내렸다 탄 것(짐 꺼내기 등)이나 주행 기어로 바뀐 경우는 묻지 않는다
 */
class DebriefTrigger(private val minAwayMs: Long) {
    private var leftAt: Long? = null

    fun onSeat(driverSeated: Boolean, gear: Gear, now: Long): Boolean {
        if (gear != Gear.PARK) { leftAt = null; return false }
        if (!driverSeated) { if (leftAt == null) leftAt = now; return false }
        val away = leftAt ?: return false
        leftAt = null
        return now - away >= minAwayMs
    }
}

package com.roadoffice.app.workmile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyWatcherTest {
    @Test
    fun `주차 중 배터리가 5%p 이상 오르면 충전으로 포착, 출발 때 최종 값 확정`() {
        val w = EnergyWatcher(EnergyKind.EV_CHARGE)
        w.onGear(Gear.PARK, now = 1_000, pct = 55)
        assertNull(w.onLevel(57, 2_000)) // 아직 기준 미만
        val live = w.onLevel(71, 3_000)
        assertNotNull(live)
        assertEquals(55, live!!.fromPct)
        assertEquals(71, live.toPct)
        w.onLevel(78, 4_000)
        val done = w.onGear(Gear.DRIVE, 5_000, 78)!!
        assertEquals(23, done.risePct)
        assertEquals(1_000L, done.startedAt)
        assertNull(w.onGear(Gear.PARK, 9_000, 78)) // 다음 주차는 새로 시작
    }

    @Test
    fun `높게 주차했다가 조금씩 줄어든 것은 충전이 아니다`() {
        val w = EnergyWatcher(EnergyKind.EV_CHARGE)
        w.onGear(Gear.PARK, 0, 80)
        listOf(78, 76, 74).forEach { assertNull(w.onLevel(it, 1)) }
        assertNull(w.onGear(Gear.DRIVE, 2, 74))
    }

    @Test
    fun `조금 내려간 뒤 오르면 최저점부터 센다, 주행 중 변화는 무시`() {
        val w = EnergyWatcher(EnergyKind.FUEL)
        assertNull(w.onLevel(30, 0)) // 주차 전
        w.onGear(Gear.PARK, 0, 32)
        w.onLevel(30, 1)
        val r = w.onLevel(90, 2)!!
        assertEquals(30, r.fromPct)
        assertEquals(EnergyKind.FUEL, r.kind)
    }
}

class EnergyRestoreTest {
    @Test
    fun `앱이 꺼졌다 켜져도 저장한 주차 상태로 충전을 포착한다 - 실차는 충전 중 헤드유닛이 꺼짐`() {
        val before = EnergyWatcher(EnergyKind.EV_CHARGE)
        before.onGear(Gear.PARK, 1_000, 40)
        val snap = before.snapshot()!!
        val after = EnergyWatcher(EnergyKind.EV_CHARGE) // 앱 재시작
        after.restore(snap)
        after.onGear(Gear.PARK, 9_000_000, 85) // 다시 켜졌을 때 이미 85%
        val r = after.onLevel(85, 9_000_001)!!
        assertEquals(40, r.fromPct)
        assertEquals(1_000L, r.startedAt)
        assertNull(EnergyWatcher(EnergyKind.EV_CHARGE).snapshot())
    }
}

class DayCloseTest {
    private val base = DayCloseInput(trips = 4, businessKm = 38.25, unknownTrips = 1, refillsWithoutAmount = 1, openTasks = listOf("단가표 보내기", "원장님 연락"), tomorrowClients = listOf("한빛병원"))

    @Test
    fun `남은 정리를 숫자로 알려 주고 내일 일정 거래처를 말한다`() {
        val s = DayClose.speech(base, guestOnBoard = false)
        assertEquals(4, DayClose.leftovers(base))
        assertTrue(s.contains("운행 4건"))
        assertTrue(s.contains("38.3킬로미터") || s.contains("38.2킬로미터"))
        assertTrue(s.contains("미분류 운행 1건"))
        assertTrue(s.contains("남은 할 일 2개"))
        assertTrue(s.contains("한빛병원"))
    }

    @Test
    fun `동승자가 있으면 거래처 이름을 말하지 않는다`() {
        val s = DayClose.speech(base, guestOnBoard = true)
        assertFalse(s.contains("한빛병원"))
        assertTrue(s.contains("후속 일정 1건"))
    }

    @Test
    fun `정리할 게 없으면 그렇게 말한다`() {
        val clear = base.copy(unknownTrips = 0, refillsWithoutAmount = 0, openTasks = emptyList(), tomorrowClients = emptyList())
        assertEquals(0, DayClose.leftovers(clear))
        assertTrue(DayClose.speech(clear, false).contains("정리할 게 없어요"))
    }
}

class ClaimModeTest {
    private val t = ExportTrip(0, 1, 100.0, 120.0, 20.0, Purpose.BUSINESS, "한빛병원", 1_500)
    private val charged = ExportRefill(2, EnergyKind.EV_CHARGE, 40, 80, 18_000, Purpose.BUSINESS, "한빛병원")
    private val noAmount = ExportRefill(3, EnergyKind.FUEL, 20, 90, null, Purpose.BUSINESS, null)
    private val personal = ExportRefill(4, EnergyKind.EV_CHARGE, 10, 60, 9_000, Purpose.PERSONAL, null)

    @Test
    fun `km 단가 방식은 주유·충전을 청구하지 않는다 - 이중 청구 방지`() {
        val p = ExportProfile("김영업", "영업1팀", 300, ClaimMode.KM_RATE)
        val c = Exporter.claim(listOf(t), listOf(charged), p)
        assertEquals(6_000L, c.fuel)
        assertEquals(7_500L, c.total)
        val csv = Exporter.fuelClaimCsv(listOf(t), p, listOf(charged))
        assertTrue(csv.contains("청구하지 않음"))
    }

    @Test
    fun `실비 방식은 업무 주유·충전 금액만 더하고 미입력 건수를 센다`() {
        val p = ExportProfile("김영업", "영업1팀", 300, ClaimMode.ACTUAL)
        val c = Exporter.claim(listOf(t), listOf(charged, noAmount), p)
        assertEquals(18_000L, c.fuel)
        assertEquals(1, c.missingAmounts)
        val csv = Exporter.fuelClaimCsv(listOf(t), p, listOf(charged, noAmount, personal))
        assertTrue(csv.contains("18000"))
        assertTrue(csv.contains("미입력"))
        assertFalse(csv.contains("9000")) // 개인 충전은 실비 청구서에 넣지 않음
        assertTrue(csv.contains("19500")) // 18,000 + 통행료 1,500
    }
}

package com.roadoffice.app.cabin

import com.roadoffice.app.meeting.DebriefTrigger
import com.roadoffice.app.workmile.Gear
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CabinTest {
    @Test
    fun `운전석 외 좌석에 사람이 있으면 동승자, 센서가 없으면 판단하지 않는다`() {
        assertFalse(Cabin(setOf(Seat.DRIVER), known = true).guestOnBoard)
        assertTrue(Cabin(setOf(Seat.DRIVER, Seat.PASSENGER), known = true).guestOnBoard)
        assertTrue(Cabin(setOf(Seat.DRIVER, Seat.REAR_RIGHT), known = true).guestOnBoard)
        assertFalse(Cabin(setOf(Seat.PASSENGER), known = false).guestOnBoard)
    }
}

class SeatCalibrationTest {
    @Test
    fun `보정 전에는 좌석 정보를 쓰지 않고, 주행 중 같은 값이 5초 이어지면 뜻을 맞춘다`() {
        val c = SeatCalibration()
        val raw = mapOf(Seat.DRIVER to false, Seat.PASSENGER to true) // SDK 2.3.2: 착석이 false 로 옴
        assertFalse(c.cabin(raw).known)
        assertFalse(c.onDriving(rawDriver = false, now = 0))
        assertFalse(c.onDriving(false, 4_000)) // 아직 5초 전
        assertTrue(c.onDriving(false, 5_000))
        val cabin = c.cabin(raw)
        assertTrue(cabin.driverSeated)
        assertFalse(cabin.guestOnBoard) // 조수석 true = 빈자리
        assertFalse(c.onDriving(false, 9_000)) // 같은 값이면 변경 없음
    }

    @Test
    fun `순간 튄 값이나 주행이 끊긴 값으로는 뒤집지 않는다`() {
        val c = SeatCalibration(inverted = true)
        assertFalse(c.onDriving(rawDriver = true, now = 0))
        assertFalse(c.onDriving(false, 1_000)) // 다시 원래 값 → 후보 바뀜
        assertFalse(c.onDriving(true, 2_000))
        c.onNotDriving()
        assertFalse(c.onDriving(true, 6_000)) // 정차로 끊겼으니 다시 셈
        assertTrue(c.inverted == true)
    }

    @Test
    fun `SDK 가 표준대로 고쳐지면 그대로 쓴다`() {
        val c = SeatCalibration(inverted = true)
        c.onDriving(rawDriver = true, now = 0)
        assertTrue(c.onDriving(true, 5_000))
        assertTrue(c.cabin(mapOf(Seat.DRIVER to true, Seat.REAR_LEFT to true)).guestOnBoard)
    }
}

class DebriefTriggerTest {
    private val min = 3 * 60 * 1000L

    @Test
    fun `미팅을 마치고 P단에서 다시 앉으면 묻는다`() {
        val t = DebriefTrigger(min)
        assertFalse(t.onSeat(true, Gear.PARK, 0))
        assertFalse(t.onSeat(false, Gear.PARK, 10_000)) // 내림
        assertTrue(t.onSeat(true, Gear.PARK, 10_000 + 40 * 60_000L)) // 40분 뒤 탐
        assertFalse(t.onSeat(true, Gear.PARK, 10_000 + 41 * 60_000L)) // 한 번만
    }

    @Test
    fun `잠깐 내렸다 타면 묻지 않는다`() {
        val t = DebriefTrigger(min)
        t.onSeat(false, Gear.PARK, 0)
        assertFalse(t.onSeat(true, Gear.PARK, 60_000))
    }

    @Test
    fun `주행 기어에서는 묻지 않는다`() {
        val t = DebriefTrigger(min)
        t.onSeat(false, Gear.PARK, 0)
        assertFalse(t.onSeat(true, Gear.DRIVE, 10 * 60_000L))
    }
}

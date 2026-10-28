package com.roadoffice.app.workmile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripRecorderTest {
    private val home = LatLng(37.4127, 127.0946)
    private val client = LatLng(37.4959, 127.0293)

    @Test
    fun `출발에서 주차 확정까지 운행 1건, 거리는 주행거리계 차이`() {
        val r = TripRecorder(parkConfirmMs = 30_000)
        r.onGear(Gear.DRIVE, now = 0, odometerKm = 12345.0, location = home)
        assertTrue(r.isDriving)
        r.onGear(Gear.PARK, now = 600_000, odometerKm = 12359.0, location = client)
        assertNull(r.onTick(600_000 + 29_000)) // 아직 확정 전
        val trip = r.onTick(600_000 + 30_000)
        assertNotNull(trip)
        assertEquals(14.0, trip!!.distanceKm, 0.001)
        assertEquals(600_000L, trip.endAt)
        assertEquals(client, trip.endLocation)
    }

    @Test
    fun `잠깐 정차 후 재출발은 같은 운행으로 이어진다`() {
        val r = TripRecorder(parkConfirmMs = 30_000)
        r.onGear(Gear.DRIVE, 0, 100.0, home)
        r.onGear(Gear.PARK, 10_000, 103.0, null)
        assertNull(r.onTick(20_000))
        r.onGear(Gear.DRIVE, 25_000, 103.0, null)
        assertNull(r.onTick(100_000)) // 주행 중이므로 종료되지 않음
        r.onGear(Gear.PARK, 200_000, 110.0, client)
        val trip = r.onTick(230_000)!!
        assertEquals(10.0, trip.distanceKm, 0.001)
        assertEquals(0L, trip.startAt)
    }

    @Test
    fun `주차장 안 짧은 이동은 버린다`() {
        val r = TripRecorder(parkConfirmMs = 1_000, minDistanceKm = 0.1)
        r.onGear(Gear.REVERSE, 0, 50.0, null)
        r.onGear(Gear.PARK, 5_000, 50.05, null)
        assertNull(r.onTick(10_000))
        assertNull(r.active)
    }

    @Test
    fun `출발 때 주행거리 값이 없거나 0이면 첫 정상 값을 기준점으로 쓴다`() {
        val r = TripRecorder(parkConfirmMs = 1_000)
        r.onGear(Gear.DRIVE, 0, 0.0, null) // 연결 직후 0 이 먼저 오는 경우
        r.onOdometer(12359.0)
        r.onOdometer(12365.0)
        r.onGear(Gear.PARK, 5_000, 12373.0, null)
        assertEquals(14.0, r.onTick(6_000)!!.distanceKm, 0.001)
    }

    @Test
    fun `끝까지 주행거리를 못 받으면 저장하지 않는다`() {
        val r = TripRecorder(parkConfirmMs = 1_000)
        r.onGear(Gear.DRIVE, 0, null, null)
        r.onGear(Gear.PARK, 5_000, null, null)
        assertNull(r.onTick(6_000))
        assertNull(r.active)
    }

    @Test
    fun `비정상적으로 긴 거리는 센서 오류로 버린다`() {
        val r = TripRecorder(parkConfirmMs = 1_000)
        r.onGear(Gear.DRIVE, 0, 1.0, null)
        r.onGear(Gear.PARK, 5_000, 12373.0, null)
        assertNull(r.onTick(6_000))
    }

    @Test
    fun `P 이후 늦게 온 주행거리 갱신을 종료 값으로 쓴다`() {
        val r = TripRecorder(parkConfirmMs = 1_000)
        r.onGear(Gear.DRIVE, 0, 10.0, null)
        r.onGear(Gear.PARK, 5_000, 12.0, null)
        r.onOdometer(13.0)
        assertEquals(3.0, r.onTick(6_000)!!.distanceKm, 0.001)
    }

    @Test
    fun `내비 정보로 목적지와 통행료를 보강한다`() {
        val r = TripRecorder(parkConfirmMs = 1_000)
        r.onGear(Gear.DRIVE, 0, 10.0, null)
        r.onRouteStarted("○○병원", 2400)
        r.onArrived(null, "서울 강남구")
        r.onGear(Gear.PARK, 5_000, 30.0, null)
        val trip = r.onTick(6_000)!!
        assertEquals("○○병원", trip.destinationName)
        assertEquals("서울 강남구", trip.destinationAddress)
        assertEquals(2400, trip.tollFareEstimate)
    }
}

class ClassifierTest {
    private val clients = listOf(
        ClientPlace(1, "현대차 강남대로사옥", LatLng(37.4959, 127.0293), radiusM = 300.0),
        ClientPlace(2, "서울아산병원", null),
    )

    private fun trip(end: LatLng?, dest: String?) =
        TripDraft(0, 1, 0.0, 5.0, null, end, dest, null, null)

    @Test
    fun `거래처 반경 안에 도착하면 업무`() {
        val c = Classifier.classify(trip(LatLng(37.4965, 127.0295), null), clients)
        assertEquals(Purpose.BUSINESS, c.purpose)
        assertEquals(1L, c.clientId)
    }

    @Test
    fun `반경 밖이고 이름도 다르면 미분류`() {
        val c = Classifier.classify(trip(LatLng(37.4127, 127.0946), "집"), clients)
        assertEquals(Purpose.UNKNOWN, c.purpose)
    }

    @Test
    fun `내비 목적지 이름이 거래처를 포함하면 업무`() {
        val c = Classifier.classify(trip(null, "서울 아산병원 신관"), clients)
        assertEquals(Purpose.BUSINESS, c.purpose)
        assertEquals(2L, c.clientId)
    }

    @Test
    fun `하버사인 거리 - 강남역과 역삼역 약 800m`() {
        val d = distanceMeters(LatLng(37.4979, 127.0276), LatLng(37.5006, 127.0364))
        assertTrue(d in 700.0..900.0)
    }
}

class WorkMileSummaryTest {
    @Test
    fun `업무 km 곱하기 단가 더하기 통행료가 청구액`() {
        val s = WorkMileSummary.of(
            listOf(10.0 to Purpose.BUSINESS, 5.0 to Purpose.PERSONAL, 20.5 to Purpose.BUSINESS),
            tolls = listOf(1200, 2400),
            perKmRate = 300,
        )
        assertEquals(30.5, s.businessKm, 0.001)
        assertEquals(2, s.businessTrips)
        assertEquals(30.5 * 300 + 3600, s.claimAmount.toDouble(), 1.0)
    }
}

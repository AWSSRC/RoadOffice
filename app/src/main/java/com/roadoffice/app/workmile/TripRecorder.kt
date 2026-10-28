package com.roadoffice.app.workmile

/** 기어를 차량 SDK 와 분리한 내부 표현 — 판단 로직을 SDK 없이 테스트하기 위함. */
enum class Gear { PARK, DRIVE, REVERSE, NEUTRAL, UNKNOWN }

data class LatLng(val lat: Double, val lon: Double)

/** 진행 중인 운행. 완료되면 [TripDraft] 로 바뀌어 저장된다. */
data class ActiveTrip(
    val startAt: Long,
    /** 출발 시점 주행거리계 값. 출발 순간 값을 못 받았으면 null → 처음 들어오는 정상 값으로 채운다. */
    val startOdometerKm: Double?,
    val startLocation: LatLng?,
    val destinationName: String? = null,
    val destinationAddress: String? = null,
    val tollFareEstimate: Int? = null,
)

data class TripDraft(
    val startAt: Long,
    val endAt: Long,
    val startOdometerKm: Double,
    val endOdometerKm: Double,
    val startLocation: LatLng?,
    val endLocation: LatLng?,
    val destinationName: String?,
    val destinationAddress: String?,
    val tollFareEstimate: Int?,
) {
    /** 거리는 GPS 추정이 아니라 차량 주행거리계 차이로 계산한다 (워크마일 증빙의 핵심). */
    val distanceKm: Double get() = (endOdometerKm - startOdometerKm).coerceAtLeast(0.0)
}

/**
 * 워크마일 운행 기록기.
 *
 * - 주차(P)가 아닌 기어로 바뀌면 운행 시작 (주행거리계 값을 기준점으로 저장)
 * - P 로 바뀐 뒤 [parkConfirmMs] 동안 다시 출발하지 않으면 운행 종료
 *   (신호 대기·잠깐 정차는 같은 운행으로 이어 붙인다)
 * - 이동 거리가 [minDistanceKm] 미만이면 버린다 (주차장 안 이동 등)
 * - 주행거리계 값 0 이하는 "모름"으로 본다 (서비스 연결 직후 0 이 먼저 오는 경우 방지)
 * - 한 운행이 [maxDistanceKm] 를 넘으면 센서 오류로 보고 버린다
 */
class TripRecorder(
    private val parkConfirmMs: Long = 30_000,
    private val minDistanceKm: Double = 0.1,
    private val maxDistanceKm: Double = 2_000.0,
) {
    var active: ActiveTrip? = null
        private set
    private var parkedSince: Long? = null
    private var parkedOdometerKm: Double? = null
    private var parkedLocation: LatLng? = null

    val isDriving: Boolean get() = active != null && parkedSince == null

    /** 기어 변경. 운행이 끝났으면 완료된 운행을 돌려준다. */
    fun onGear(gear: Gear, now: Long, odometerKm: Double?, location: LatLng?): TripDraft? {
        if (gear == Gear.UNKNOWN) return null
        if (gear == Gear.PARK) {
            if (active != null && parkedSince == null) {
                parkedSince = now
                parkedOdometerKm = odometerKm
                parkedLocation = location
            }
            return null
        }
        // 주행 기어
        if (active == null) active = ActiveTrip(now, odometerKm.valid(), location)
        parkedSince = null // 잠깐 정차 후 재출발 → 같은 운행
        return null
    }

    /** 주기적으로 호출 — P 상태가 충분히 이어졌으면 운행을 마감한다. */
    fun onTick(now: Long): TripDraft? {
        val trip = active ?: return null
        val since = parkedSince ?: return null
        if (now - since < parkConfirmMs) return null
        val startOdo = trip.startOdometerKm
        val endOdo = parkedOdometerKm.valid()
        if (startOdo == null || endOdo == null) { reset(); return null } // 거리 증빙 불가 → 저장하지 않음
        val draft = TripDraft(
            startAt = trip.startAt,
            endAt = since,
            startOdometerKm = startOdo,
            endOdometerKm = endOdo,
            startLocation = trip.startLocation,
            endLocation = parkedLocation,
            destinationName = trip.destinationName,
            destinationAddress = trip.destinationAddress,
            tollFareEstimate = trip.tollFareEstimate,
        )
        reset()
        return draft.takeIf { it.distanceKm >= minDistanceKm && it.distanceKm <= maxDistanceKm }
    }

    /**
     * 주행거리 갱신.
     * - 출발 기준점이 비어 있으면 첫 정상 값으로 채운다
     * - P 상태에서 들어온 값은 종료 지점 값으로 반영 (신호가 기어보다 늦게 오는 경우)
     */
    fun onOdometer(odometerKm: Double) {
        val km = odometerKm.valid() ?: return
        val trip = active ?: return
        if (trip.startOdometerKm == null) active = trip.copy(startOdometerKm = km)
        if (parkedSince != null) parkedOdometerKm = km
    }

    private fun Double?.valid(): Double? = this?.takeIf { it > 0.0 && !it.isNaN() }

    /** 내비 경로 시작 정보로 목적지·예상 통행료를 보강. */
    fun onRouteStarted(name: String?, tollFare: Int?) {
        active = active?.copy(destinationName = name ?: active?.destinationName, tollFareEstimate = tollFare)
    }

    /** 내비 도착 정보로 목적지 이름·주소를 확정. */
    fun onArrived(name: String?, address: String?) {
        active = active?.copy(
            destinationName = name ?: active?.destinationName,
            destinationAddress = address ?: active?.destinationAddress,
        )
    }

    private fun reset() {
        active = null
        parkedSince = null
        parkedOdometerKm = null
        parkedLocation = null
    }
}

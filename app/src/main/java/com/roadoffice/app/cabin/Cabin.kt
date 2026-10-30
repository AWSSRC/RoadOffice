package com.roadoffice.app.cabin

/** 좌석 — 국내 좌핸들 기준 1열 왼쪽이 운전석. */
enum class Seat { DRIVER, PASSENGER, REAR_LEFT, REAR_RIGHT }

/**
 * 차 안 상태 (좌석 점유 센서). [known] 이 false 면 이 차량은 좌석 점유를 알려 주지 않는 것 —
 * 그때는 동승자 보호·착석 기반 회고를 쓰지 않고 예전 방식(출발 시점)으로 돌아간다.
 */
data class Cabin(val occupied: Set<Seat> = emptySet(), val known: Boolean = false) {
    val driverSeated: Boolean get() = Seat.DRIVER in occupied
    /** 운전석 말고 다른 자리에 누군가 있다 — 고객·동료 누구든 업무 내용을 소리·화면으로 내지 않는다. */
    val guestOnBoard: Boolean get() = known && occupied.any { it != Seat.DRIVER }
}

/**
 * 좌석 점유 값의 뜻 보정.
 * Pleos Vehicle SDK 2.3.2 는 AAOS 표준 SEAT_OCCUPANCY(1=빈자리, 2=착석)와 반대로 1 을 "착석"으로 돌려준다
 * (에뮬레이터에서 확인). 실차나 SDK 갱신에서 고쳐질 수 있어 고정으로 뒤집지 않고,
 * "주행 중에는 운전석에 사람이 있다"는 사실로 스스로 맞춘다. 맞추기 전([inverted] == null)에는 좌석 정보를 쓰지 않는다.
 * 순간 튀는 값·앱 시작 직후 남은 값으로 뒤집히지 않도록, 주행 중 같은 값이 [holdMs] 이상 이어질 때만 바꾼다.
 */
class SeatCalibration(inverted: Boolean? = null, private val holdMs: Long = 5_000) {
    var inverted: Boolean? = inverted
        private set
    private var candidate: Boolean? = null
    private var since = 0L

    /** 주행 중 운전석 원시 값 → 보정. 보정값이 바뀌었으면 true. */
    fun onDriving(rawDriver: Boolean, now: Long): Boolean {
        val inv = !rawDriver
        if (inv != candidate) { candidate = inv; since = now }
        if (inv == inverted || now - since < holdMs) return false
        inverted = inv
        return true
    }

    /** 주행이 끊기면(정차·저속) 후보를 버린다 — 연속 주행 중의 값만 믿는다. */
    fun onNotDriving() { candidate = null }

    fun occupied(raw: Boolean): Boolean? = inverted?.let { raw != it }

    fun cabin(raw: Map<Seat, Boolean>): Cabin {
        if (inverted == null || Seat.DRIVER !in raw) return Cabin() // 운전석 값이 아직 안 왔으면 모름
        return Cabin(raw.filter { occupied(it.value) == true }.keys, known = true)
    }
}

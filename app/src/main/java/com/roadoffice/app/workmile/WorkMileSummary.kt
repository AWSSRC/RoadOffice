package com.roadoffice.app.workmile

/** 워크마일 월 정산 요약 — km 단가 방식이면 업무 km × 회사 단가, 실비 방식이면 업무 주유·충전 금액 (+ 통행료). */
data class WorkMileSummary(
    val businessKm: Double,
    val personalKm: Double,
    val unknownKm: Double,
    val businessTrips: Int,
    val tollFareTotal: Int,
    val perKmRate: Int,
    val mode: ClaimMode = ClaimMode.KM_RATE,
    val actualFuel: Long = 0,
) {
    val claimAmount: Long
        get() = (if (mode == ClaimMode.KM_RATE) (businessKm * perKmRate).toLong() else actualFuel) + tollFareTotal
    val businessRatio: Double
        get() = (businessKm + personalKm + unknownKm).let { if (it == 0.0) 0.0 else businessKm / it }

    companion object {
        fun of(trips: List<Pair<Double, Purpose>>, tolls: List<Int>, perKmRate: Int, mode: ClaimMode = ClaimMode.KM_RATE, actualFuel: Long = 0) = WorkMileSummary(
            businessKm = trips.filter { it.second == Purpose.BUSINESS }.sumOf { it.first },
            personalKm = trips.filter { it.second == Purpose.PERSONAL }.sumOf { it.first },
            unknownKm = trips.filter { it.second == Purpose.UNKNOWN }.sumOf { it.first },
            businessTrips = trips.count { it.second == Purpose.BUSINESS },
            tollFareTotal = tolls.sum(),
            perKmRate = perKmRate,
            mode = mode,
            actualFuel = actualFuel,
        )
    }
}

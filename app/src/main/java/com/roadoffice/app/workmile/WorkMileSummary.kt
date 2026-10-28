package com.roadoffice.app.workmile

/** 워크마일 월 정산 요약 — 업무 km × 회사 km 단가 = 예상 청구액. */
data class WorkMileSummary(
    val businessKm: Double,
    val personalKm: Double,
    val unknownKm: Double,
    val businessTrips: Int,
    val tollFareTotal: Int,
    val perKmRate: Int,
) {
    val claimAmount: Long get() = (businessKm * perKmRate).toLong() + tollFareTotal
    val businessRatio: Double
        get() = (businessKm + personalKm + unknownKm).let { if (it == 0.0) 0.0 else businessKm / it }

    companion object {
        fun of(trips: List<Pair<Double, Purpose>>, tolls: List<Int>, perKmRate: Int) = WorkMileSummary(
            businessKm = trips.filter { it.second == Purpose.BUSINESS }.sumOf { it.first },
            personalKm = trips.filter { it.second == Purpose.PERSONAL }.sumOf { it.first },
            unknownKm = trips.filter { it.second == Purpose.UNKNOWN }.sumOf { it.first },
            businessTrips = trips.count { it.second == Purpose.BUSINESS },
            tollFareTotal = tolls.sum(),
            perKmRate = perKmRate,
        )
    }
}

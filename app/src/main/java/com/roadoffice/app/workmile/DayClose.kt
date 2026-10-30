package com.roadoffice.app.workmile

/** 하루 마감에 필요한 숫자 — DB 엔티티와 분리해 순수 로직으로 테스트. */
data class DayCloseInput(
    val trips: Int,
    val businessKm: Double,
    val unknownTrips: Int,
    val refillsWithoutAmount: Int,
    val openTasks: List<String>,
    /** 내일 후속 일정이 있는 거래처 이름 */
    val tomorrowClients: List<String>,
)

/**
 * 하루 마감 30초 — 마지막 주차 때 차에서 내리기 전에 오늘 남은 정리를 한 번에.
 * 미분류 운행·금액 없는 주유/충전·남은 할 일이 0 이면 "정리할 게 없어요".
 */
object DayClose {
    fun leftovers(i: DayCloseInput): Int = i.unknownTrips + i.refillsWithoutAmount + i.openTasks.size

    /** TTS 로 읽을 문장. 동승자가 있으면 거래처 이름·할 일 내용은 빼고 숫자만 말한다. */
    fun speech(i: DayCloseInput, guestOnBoard: Boolean): String = buildString {
        append("오늘 운행 ${i.trips}건, 업무 ${"%.1f".format(i.businessKm)}킬로미터 기록했어요. ")
        if (leftovers(i) == 0) append("정리할 게 없어요. ")
        else {
            val parts = listOfNotNull(
                i.unknownTrips.takeIf { it > 0 }?.let { "미분류 운행 ${it}건" },
                i.refillsWithoutAmount.takeIf { it > 0 }?.let { "금액 안 넣은 주유·충전 ${it}건" },
                i.openTasks.size.takeIf { it > 0 }?.let { "남은 할 일 ${it}개" },
            )
            append("아직 정리할 게 있어요. ").append(parts.joinToString(", ")).append(". 화면에서 바로 정리할 수 있어요. ")
        }
        if (i.tomorrowClients.isNotEmpty()) {
            if (guestOnBoard) append("내일 후속 일정 ${i.tomorrowClients.size}건이 있어요. ")
            else append("내일은 ${i.tomorrowClients.joinToString(", ")} 후속 일정이 있어요. ")
        }
        append("수고하셨어요.")
    }
}

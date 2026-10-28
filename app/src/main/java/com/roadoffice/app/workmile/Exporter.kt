package com.roadoffice.app.workmile

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 내보내기에 쓰는 운행 한 줄 (DB 엔티티와 분리해 순수 로직으로 테스트). */
data class ExportTrip(
    val startAt: Long,
    val endAt: Long,
    val startOdometerKm: Double,
    val endOdometerKm: Double,
    val distanceKm: Double,
    val purpose: Purpose,
    val destination: String?,
    val tollFare: Int?,
    /** 출퇴근 운행 여부 (운행기록부의 '출·퇴근용' 칸) */
    val commute: Boolean = false,
)

data class ExportProfile(val name: String, val department: String, val perKmRate: Int)

/**
 * 워크마일 정산 내보내기.
 * - 운행기록부: 국세청 '업무용승용차 운행기록부' 항목 구성(사용일자·사용자·주행 전/후 계기판 거리·주행거리·업무용 사용거리·비고)
 *   ※ 제출 전 국세청 별지 서식 원문과 항목명 대조 필요
 * - 유류비 청구서: 업무 운행만, km × 회사 단가 + 예상 통행료
 * CSV 는 엑셀에서 한글이 깨지지 않도록 UTF-8 BOM 을 붙인다.
 */
object Exporter {
    private const val BOM = "﻿"
    private val day = SimpleDateFormat("yyyy-MM-dd(E)", Locale.KOREA)
    private val hm = SimpleDateFormat("HH:mm", Locale.KOREA)

    fun mileageLogCsv(trips: List<ExportTrip>, p: ExportProfile): String = buildString {
        append(BOM)
        appendLine("사용일자,부서,성명,출발,도착,주행 전 계기판 거리(km),주행 후 계기판 거리(km),주행거리(km),출·퇴근용(km),일반 업무용(km),비고")
        trips.sortedBy { it.startAt }.forEach { t ->
            val business = t.purpose == Purpose.BUSINESS
            row(
                day.format(Date(t.startAt)), p.department, p.name,
                hm.format(Date(t.startAt)), hm.format(Date(t.endAt)),
                km(t.startOdometerKm), km(t.endOdometerKm), km(t.distanceKm),
                if (business && t.commute) km(t.distanceKm) else "",
                if (business && !t.commute) km(t.distanceKm) else "",
                listOfNotNull(t.destination, if (t.purpose == Purpose.PERSONAL) "개인" else null, if (t.purpose == Purpose.UNKNOWN) "미분류" else null).joinToString(" "),
            )
        }
        val total = trips.sumOf { it.distanceKm }
        val biz = trips.filter { it.purpose == Purpose.BUSINESS }.sumOf { it.distanceKm }
        appendLine()
        row("합계", "", "", "", "", "", "", km(total), "", km(biz), "업무 사용 비율 ${pct(biz, total)}")
    }

    fun fuelClaimCsv(trips: List<ExportTrip>, p: ExportProfile): String = buildString {
        append(BOM)
        appendLine("날짜,시간,방문처,업무 거리(km),km 단가(원),유류비(원),예상 통행료(원),합계(원)")
        val biz = trips.filter { it.purpose == Purpose.BUSINESS }.sortedBy { it.startAt }
        biz.forEach { t ->
            val fuel = (t.distanceKm * p.perKmRate).toLong()
            val toll = (t.tollFare ?: 0).toLong()
            row(day.format(Date(t.startAt)), "${hm.format(Date(t.startAt))}-${hm.format(Date(t.endAt))}", t.destination ?: "", km(t.distanceKm), p.perKmRate.toString(), fuel.toString(), toll.toString(), (fuel + toll).toString())
        }
        val s = claimTotal(biz, p.perKmRate)
        appendLine()
        row("합계", "", "${biz.size}건", km(biz.sumOf { it.distanceKm }), "", s.first.toString(), s.second.toString(), (s.first + s.second).toString())
    }

    /** (유류비 합, 통행료 합) */
    fun claimTotal(businessTrips: List<ExportTrip>, perKmRate: Int): Pair<Long, Long> =
        businessTrips.sumOf { (it.distanceKm * perKmRate).toLong() } to businessTrips.sumOf { (it.tollFare ?: 0).toLong() }

    private fun StringBuilder.row(vararg cells: String) = appendLine(cells.joinToString(",") { esc(it) })

    /** CSV 셀 이스케이프 + 수식 주입(=, +, -, @ 시작) 방지 */
    fun esc(v: String): String {
        val safe = if (v.isNotEmpty() && v[0] in "=+-@\t\r" && v.toDoubleOrNull() == null) "'$v" else v
        return if (safe.any { it == ',' || it == '"' || it == '\n' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    private fun km(v: Double) = "%.1f".format(Locale.US, v)
    private fun pct(a: Double, b: Double) = if (b == 0.0) "0%" else "%.0f%%".format(a / b * 100)
}

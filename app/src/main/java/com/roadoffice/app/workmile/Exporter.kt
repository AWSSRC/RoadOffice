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

/** 주유·충전 기록 한 줄. 금액은 사용자가 넣기 전엔 null. */
data class ExportRefill(val at: Long, val kind: EnergyKind, val fromPct: Int, val toPct: Int, val amount: Long?, val purpose: Purpose, val place: String?)

/**
 * 유류비 정산 방식 — 회사마다 다르다.
 * - KM_RATE: 업무 km × 회사 단가 (주유·충전 기록은 참고로만 붙이고 청구하지 않음 — 이중 청구 방지)
 * - ACTUAL: 업무로 쓴 주유·충전 실비
 */
enum class ClaimMode(val label: String) { KM_RATE("km 단가"), ACTUAL("실비") }

data class ExportProfile(val name: String, val department: String, val perKmRate: Int, val mode: ClaimMode = ClaimMode.KM_RATE)

/** 청구 합계 — 유류비(방식에 따라 km 단가 또는 실비), 통행료, 금액 미입력 업무 주유·충전 건수. */
data class Claim(val fuel: Long, val toll: Long, val missingAmounts: Int) {
    val total: Long get() = fuel + toll
}

/**
 * 워크마일 정산 내보내기.
 * - 운행기록부: 국세청 '업무용승용차 운행기록부' 항목 구성(사용일자·사용자·주행 전/후 계기판 거리·주행거리·업무용 사용거리·비고)
 *   ※ 제출 전 국세청 별지 서식 원문과 항목명 대조 필요
 * - 유류비 청구서: 업무 운행만, [ClaimMode] 에 따라 km × 회사 단가 또는 주유·충전 실비, + 예상 통행료
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

    fun fuelClaimCsv(trips: List<ExportTrip>, p: ExportProfile, refills: List<ExportRefill> = emptyList()): String = buildString {
        append(BOM)
        val biz = trips.filter { it.purpose == Purpose.BUSINESS }.sortedBy { it.startAt }
        val bizRefills = refills.filter { it.purpose == Purpose.BUSINESS }.sortedBy { it.at }
        val s = claim(biz, bizRefills, p)
        if (p.mode == ClaimMode.KM_RATE) {
            appendLine("날짜,시간,방문처,업무 거리(km),km 단가(원),유류비(원),예상 통행료(원),합계(원)")
            biz.forEach { t ->
                val fuel = (t.distanceKm * p.perKmRate).toLong()
                val toll = (t.tollFare ?: 0).toLong()
                row(day.format(Date(t.startAt)), "${hm.format(Date(t.startAt))}-${hm.format(Date(t.endAt))}", t.destination ?: "", km(t.distanceKm), p.perKmRate.toString(), fuel.toString(), toll.toString(), (fuel + toll).toString())
            }
            appendLine()
            row("합계", "", "${biz.size}건", km(biz.sumOf { it.distanceKm }), "", s.fuel.toString(), s.toll.toString(), s.total.toString())
            if (refills.isNotEmpty()) {
                appendLine()
                row("참고: 주유·충전 기록 (km 단가 정산이라 청구하지 않음)")
                refillRows(refills.sortedBy { it.at })
            }
        } else {
            appendLine("날짜,시간,구분,장소·방문처,내용,금액(원)")
            bizRefills.forEach { r -> refillRow(r) }
            biz.filter { (it.tollFare ?: 0) > 0 }.forEach { t ->
                row(day.format(Date(t.startAt)), hm.format(Date(t.startAt)), "통행료(예상)", t.destination ?: "", km(t.distanceKm) + "km 운행", (t.tollFare ?: 0).toString())
            }
            appendLine()
            row("합계", "", "", "", "유류비 실비 ${s.fuel} + 통행료 ${s.toll}" + if (s.missingAmounts > 0) " · 금액 미입력 ${s.missingAmounts}건" else "", s.total.toString())
        }
    }

    private fun StringBuilder.refillRows(list: List<ExportRefill>) {
        appendLine("날짜,시간,구분,장소·방문처,내용,금액(원)")
        list.forEach { refillRow(it) }
    }

    private fun StringBuilder.refillRow(r: ExportRefill) = row(
        day.format(Date(r.at)), hm.format(Date(r.at)), r.kind.label + if (r.purpose != Purpose.BUSINESS) "(${if (r.purpose == Purpose.PERSONAL) "개인" else "미분류"})" else "",
        r.place ?: "", "${r.fromPct}% → ${r.toPct}%", r.amount?.toString() ?: "미입력",
    )

    /** 업무 운행·업무 주유/충전만 넘겨 받아 청구 합계를 낸다. */
    fun claim(businessTrips: List<ExportTrip>, businessRefills: List<ExportRefill>, p: ExportProfile): Claim {
        val toll = businessTrips.sumOf { (it.tollFare ?: 0).toLong() }
        return if (p.mode == ClaimMode.KM_RATE) Claim(businessTrips.sumOf { (it.distanceKm * p.perKmRate).toLong() }, toll, 0)
        else Claim(businessRefills.sumOf { it.amount ?: 0L }, toll, businessRefills.count { it.amount == null })
    }

    private fun StringBuilder.row(vararg cells: String) = appendLine(cells.joinToString(",") { esc(it) })

    /** CSV 셀 이스케이프 + 수식 주입(=, +, -, @ 시작) 방지 */
    fun esc(v: String): String {
        val safe = if (v.isNotEmpty() && v[0] in "=+-@\t\r" && v.toDoubleOrNull() == null) "'$v" else v
        return if (safe.any { it == ',' || it == '"' || it == '\n' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    private fun km(v: Double) = "%.1f".format(Locale.US, v)
    private fun pct(a: Double, b: Double) = if (b == 0.0) "0%" else "%.0f%%".format(a / b * 100)
}

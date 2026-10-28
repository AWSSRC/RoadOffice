package com.roadoffice.app.workmile

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 워크마일 정산 파일 생성 (앱 내부 저장소 files/reports/ — 다른 앱이 읽을 수 없음). */
class ReportWriter(private val context: Context) {
    // 보안 가이드: 민감 정보는 앱 전용 내부 저장소에 (외부 저장소·공용 영역 사용 안 함)
    private val dir: File get() = File(context.filesDir, "reports").apply { mkdirs() }
    private val stamp = SimpleDateFormat("yyyyMM", Locale.KOREA)

    data class Result(val files: List<File>)

    fun write(trips: List<ExportTrip>, profile: ExportProfile, monthStart: Long): Result {
        val ym = stamp.format(Date(monthStart))
        val log = File(dir, "운행기록부_$ym.csv").apply { writeText(Exporter.mileageLogCsv(trips, profile)) }
        val claim = File(dir, "유류비청구서_$ym.csv").apply { writeText(Exporter.fuelClaimCsv(trips, profile)) }
        val pdf = File(dir, "워크마일_정산_$ym.pdf").also { writePdf(it, trips, profile, monthStart) }
        return Result(listOf(log, claim, pdf))
    }

    /** A4 세로 1장 이상 — 요약 + 업무 운행 목록. */
    private fun writePdf(file: File, trips: List<ExportTrip>, p: ExportProfile, monthStart: Long) {
        val doc = PdfDocument()
        val w = 595; val h = 842; val m = 40f
        val title = Paint().apply { textSize = 18f; typeface = Typeface.DEFAULT_BOLD; color = Color.rgb(0, 44, 95) }
        val body = Paint().apply { textSize = 10f; color = Color.rgb(31, 39, 51) }
        val head = Paint().apply { textSize = 10f; typeface = Typeface.DEFAULT_BOLD; color = Color.WHITE }
        val band = Paint().apply { color = Color.rgb(0, 44, 95) }
        val won = NumberFormat.getInstance(Locale.KOREA)
        val day = SimpleDateFormat("MM-dd(E) HH:mm", Locale.KOREA)
        val biz = trips.filter { it.purpose == Purpose.BUSINESS }.sortedBy { it.startAt }
        val (fuel, toll) = Exporter.claimTotal(biz, p.perKmRate)
        val total = trips.sumOf { it.distanceKm }
        val bizKm = biz.sumOf { it.distanceKm }

        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, pageNo).create())
        var c = page.canvas
        var y = m + 10
        c.drawText("워크마일 정산 · ${SimpleDateFormat("yyyy년 M월", Locale.KOREA).format(Date(monthStart))}", m, y, title)
        y += 22
        c.drawText("${p.department} ${p.name} · 거리 기준: 차량 주행거리계 · 로드오피스(RoadOffice)", m, y, body)
        y += 26
        listOf(
            "전체 운행" to "${trips.size}건 · %.1f km".format(total),
            "업무 운행" to "${biz.size}건 · %.1f km (%.0f%%)".format(bizKm, if (total == 0.0) 0.0 else bizKm / total * 100),
            "유류비 (km당 ${won.format(p.perKmRate)}원)" to "${won.format(fuel)}원",
            "예상 통행료" to "${won.format(toll)}원",
            "청구 합계" to "${won.format(fuel + toll)}원",
        ).forEach { (k, v) -> c.drawText(k, m, y, body); c.drawText(v, m + 200, y, body); y += 16 }
        y += 14

        val cols = floatArrayOf(m, m + 110, m + 330, m + 400, m + 470)
        fun header() {
            c.drawRect(m - 4, y - 12, w - m + 4, y + 6, band)
            listOf("일시", "방문처", "거리(km)", "유류비", "통행료").forEachIndexed { i, s -> c.drawText(s, cols[i], y, head) }
            y += 20
        }
        header()
        biz.forEach { t ->
            if (y > h - m) {
                doc.finishPage(page)
                page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, ++pageNo).create())
                c = page.canvas; y = m + 10; header()
            }
            c.drawText(day.format(Date(t.startAt)), cols[0], y, body)
            c.drawText((t.destination ?: "-").take(24), cols[1], y, body)
            c.drawText("%.1f".format(t.distanceKm), cols[2], y, body)
            c.drawText(won.format((t.distanceKm * p.perKmRate).toLong()), cols[3], y, body)
            c.drawText(won.format(t.tollFare ?: 0), cols[4], y, body)
            y += 16
        }
        doc.finishPage(page)
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
    }
}

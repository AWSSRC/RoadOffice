package com.roadoffice.app.demo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.roadoffice.app.data.ClientEntity
import com.roadoffice.app.data.MeetingNoteEntity
import com.roadoffice.app.data.RefillEntity
import com.roadoffice.app.data.TripEntity
import com.roadoffice.app.graph
import com.roadoffice.app.workmile.EnergyKind
import com.roadoffice.app.workmile.Purpose
import java.util.Calendar
import kotlin.random.Random

/**
 * 디버그 빌드 전용 — 시연 영상용 가상 데이터 주입 (src/debug 에만 있어 release APK 에는 포함되지 않음).
 * adb shell am broadcast -a com.roadoffice.app.DEMO_SEED -n com.roadoffice.app/.demo.DemoSeedReceiver [--ez month true]
 * 첫 거래처는 현재 위치로 등록해 도착 시 업무로 분류되게 한다. 이름은 모두 가상.
 * month=true 면 이번 달 1일~어제까지의 운행·상담 일지·충전 기록도 채운다 (월말 정산 화면·PDF 시연용).
 */
class DemoSeedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val g = context.graph
        val month = intent.getBooleanExtra("month", false)
        val pending = goAsync()
        g.launch {
            try {
                val here = g.signals.location.value
                val now = System.currentTimeMillis()
                if (g.db.clients().all().isEmpty()) {
                    g.db.clients().insert(ClientEntity(name = "한빛병원", lat = here?.lat, lon = here?.lon, createdAt = now))
                    listOf("새솔내과의원", "미래약국", "푸른정형외과", "다온소아과", "하늘약국").forEach {
                        g.db.clients().insert(ClientEntity(name = it, lat = null, lon = null, createdAt = now))
                    }
                }
                g.userName = "김영업"
                g.department = "영업1팀"
                if (month && g.db.trips().latest() == null) seedMonth(g, now)
                g.signals.log("시연 데이터 준비 완료${if (month) " (이번 달 기록 포함)" else ""}")
            } finally {
                pending.finish()
            }
        }
    }

    /** 평일 하루 2~4곳 방문, 주말 개인 운행 1건, 미분류 2건, 충전 4건(1건 금액 미입력). 주행거리계는 이어지게. */
    private suspend fun seedMonth(g: com.roadoffice.app.AppGraph, now: Long) {
        val rnd = Random(1028)
        val clients = g.db.clients().all().associateBy { it.name }
        val names = listOf("한빛병원", "새솔내과의원", "미래약국", "푸른정형외과", "다온소아과", "하늘약국")
        val day = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.DAY_OF_MONTH, 1) }
        val today = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.DAY_OF_MONTH)
        var odo = 11_840.0
        var unknownLeft = 2
        var charges = 0
        while (day.get(Calendar.DAY_OF_MONTH) < today) {
            val weekend = day.get(Calendar.DAY_OF_WEEK) in listOf(Calendar.SATURDAY, Calendar.SUNDAY)
            val stops = if (weekend) 1 else 2 + rnd.nextInt(3)
            var minute = 8 * 60 + 40 + rnd.nextInt(30)
            repeat(stops) { i ->
                val km = if (weekend) 18.0 + rnd.nextInt(25) else 6.0 + rnd.nextInt(28) + rnd.nextInt(10) / 10.0
                val start = at(day, minute)
                val drive = (km * 2.2 + 8).toInt()
                val end = start + drive * 60_000L
                val name = if (weekend) "마트" else names[(day.get(Calendar.DAY_OF_MONTH) + i) % names.size]
                val purpose = when {
                    weekend -> Purpose.PERSONAL
                    unknownLeft > 0 && day.get(Calendar.DAY_OF_MONTH) >= today - 3 && i == stops - 1 -> { unknownLeft--; Purpose.UNKNOWN }
                    else -> Purpose.BUSINESS
                }
                val tripId = g.db.trips().insert(
                    TripEntity(
                        startAt = start, endAt = end, startOdometerKm = odo, endOdometerKm = odo + km, distanceKm = km,
                        startLat = null, startLon = null, endLat = null, endLon = null,
                        destinationName = if (purpose == Purpose.UNKNOWN) null else name, destinationAddress = null,
                        tollFareEstimate = if (km > 25 && rnd.nextBoolean()) 1_800 else null,
                        purpose = purpose.name, clientId = if (purpose == Purpose.BUSINESS) clients[name]?.id else null,
                    ),
                )
                odo += km
                if (purpose == Purpose.BUSINESS && rnd.nextInt(3) == 0) {
                    val c = clients[name]
                    g.db.notes().insert(
                        MeetingNoteEntity(
                            clientId = c?.id, clientName = name, tripId = tripId, createdAt = end + 50 * 60_000L,
                            rawText = "샘플 반응 좋음, 다음 주 재방문", summary = "신제품 샘플 반응 좋음",
                            tasks = "단가표 보내기", followUpAt = null, source = "rule",
                            tasksDone = day.get(Calendar.DAY_OF_MONTH) < today - 4,
                        ),
                    )
                }
                if (purpose == Purpose.BUSINESS && charges < 4 && day.get(Calendar.DAY_OF_MONTH) % 7 == 3 && i == 0) {
                    charges++
                    g.db.refills().insert(
                        RefillEntity(
                            kind = EnergyKind.EV_CHARGE.name, startedAt = end + 5 * 60_000L, fromPct = 30 + rnd.nextInt(15), toPct = 80,
                            lat = null, lon = null, place = name, amount = if (charges == 4) null else 14_000L + rnd.nextInt(8) * 1_000L,
                            purpose = Purpose.BUSINESS.name,
                        ),
                    )
                }
                minute += drive + 50 + rnd.nextInt(70)
            }
            day.add(Calendar.DAY_OF_MONTH, 1)
        }
        // 한빛병원 최근 상담 — 다음 방문 브리핑에 남은 할 일이 나오게
        g.db.notes().insert(
            MeetingNoteEntity(
                clientId = clients["한빛병원"]?.id, clientName = "한빛병원", tripId = null, createdAt = now - 2 * 24 * 3_600_000L,
                rawText = "원장님이 견적 금액 조정 원하심, 금요일까지 수정 견적 보내기로", summary = "원장님 견적 금액 조정 요청",
                tasks = "수정 견적서 보내기", followUpAt = null, source = "rule",
            ),
        )
        // 내일 후속 일정 1건 (하루 마감의 "내일 일정" 시연)
        val tomorrow = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 10) }.timeInMillis
        g.db.notes().insert(
            MeetingNoteEntity(
                clientId = clients["새솔내과의원"]?.id, clientName = "새솔내과의원", tripId = null, createdAt = now - 26 * 3_600_000L,
                rawText = "원장님 내일 오전에 다시 연락 달라고 하심", summary = "원장님 재연락 요청", tasks = "원장님께 전화",
                followUpAt = tomorrow, source = "rule",
            ),
        )
    }

    private fun at(day: Calendar, minuteOfDay: Int): Long = (day.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, minuteOfDay / 60); set(Calendar.MINUTE, minuteOfDay % 60); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

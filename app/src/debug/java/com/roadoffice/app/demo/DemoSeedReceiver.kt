package com.roadoffice.app.demo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.roadoffice.app.data.ClientEntity
import com.roadoffice.app.data.MeetingNoteEntity
import com.roadoffice.app.data.RefillEntity
import com.roadoffice.app.data.TripEntity
import com.roadoffice.app.graph
import com.roadoffice.app.meeting.RuleNoteAssistant
import com.roadoffice.app.workmile.EnergyKind
import com.roadoffice.app.workmile.Purpose
import java.util.Calendar
import kotlin.random.Random

/**
 * 디버그 빌드 전용 — 시연 영상용 가상 데이터 주입 (src/debug 에만 있어 release APK 에는 포함되지 않음).
 * adb shell am broadcast -a com.roadoffice.app.DEMO_SEED -n com.roadoffice.app/.demo.DemoSeedReceiver [--ez month true]
 * 첫 거래처는 현재 위치로 등록해 도착 시 업무로 분류되게 한다. 이름은 모두 가상.
 * month=true 면 이번 달 1일~어제까지의 운행·상담 일지·충전 기록도 채운다 (월말 정산 화면·PDF 시연용).
 * 상담 일지는 회고 원문만 가상으로 쓰고, 요약·할 일·후속 일정은 앱의 규칙 정리기가 만든 결과를 그대로 저장한다.
 */
class DemoSeedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val g = context.graph
        val month = intent.getBooleanExtra("month", false)
        // 녹화 시각이 퇴근 시각을 넘으면 주차 때 하루 마감이 저절로 열려 장면이 멈춘다 — 시연에선 마감 시각을 고정
        // 시연용: 주요 거래처 최근 일지에 AI 후속 제안·다음 방문 한 줄을 미리 만들어 둔다 (--ez ai true, 거래처당 10~20초)
        if (intent.getBooleanExtra("ai", false)) {
            // 오래 걸리므로 브로드캐스트는 바로 끝내고(ANR 방지) 앱 범위에서 돌린다 — 앱이 앞에 떠 있어야 함(백그라운드 동결)
            context.graph.launch {
                val notes = context.graph.db.notes().all()
                listOf("한빛병원", "새솔내과의원", "푸른정형외과").mapNotNull { c -> notes.firstOrNull { it.clientName == c } }.forEach { context.graph.refreshFollowUp(it.id) }
                context.graph.signals.log("시연 AI 후속 제안 준비 끝")
            }
            return
        }
        intent.getIntExtra("day_end_hour", -1).takeIf { it in 0..23 }?.let { context.graph.dayEndHour = it }
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
        val rule = RuleNoteAssistant()
        val talks = listOf(
            "원장님이 신제품 반응이 좋대요 금요일까지 단가표 보내 드리기로 했어요",
            "이번 달은 재고가 많대요 다음 달 초에 다시 방문하기로 했어요",
            "약국장님이 샘플을 더 원하셨어요 내일 샘플 챙겨 가기로 했어요",
            "실장님이 경쟁사 견적을 받았대요 이번 주 목요일까지 비교표 보내 드리기로 했어요",
            "원장님이 바쁘셔서 간단히 인사만 드렸어요",
        )
        suspend fun note(clientId: Long?, name: String, tripId: Long?, at: Long, raw: String, done: Boolean) {
            val r = rule.structure(raw, name, at)
            g.db.notes().insert(
                MeetingNoteEntity(clientId = clientId, clientName = name, tripId = tripId, createdAt = at, rawText = raw, summary = r.summary,
                    tasks = r.tasks.joinToString("\n"), followUpAt = r.followUpAt, source = r.source, tasksDone = done),
            )
        }
        val clients = g.db.clients().all().associateBy { it.name }
        val names = listOf("한빛병원", "새솔내과의원", "미래약국", "푸른정형외과", "다온소아과", "하늘약국")
        // 지난달 1일부터 어제까지 — 월초에 지난달 정산을 시연할 수 있게
        val day = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.DAY_OF_MONTH, 1); add(Calendar.MONTH, -1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }
        val todayStart = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        fun daysAgo() = ((todayStart - day.timeInMillis) / 86_400_000L).toInt()
        var odo = 11_840.0
        var unknownLeft = 2
        var charges = 0
        while (day.timeInMillis < todayStart) {
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
                    unknownLeft > 0 && daysAgo() <= 3 && i == stops - 1 -> { unknownLeft--; Purpose.UNKNOWN }
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
                    note(clients[name]?.id, name, tripId, end + 50 * 60_000L, talks[(day.get(Calendar.DAY_OF_MONTH) + i) % talks.size] /* rnd 를 쓰지 않아야 운행·정산 숫자가 바뀌지 않는다 */, true /* 지난 상담의 할 일은 끝낸 것으로 — 남은 할 일은 아래 두 건만 */)
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
        note(clients["한빛병원"]?.id, "한빛병원", null, now - 2 * 24 * 3_600_000L, "원장님 견적 금액 조정 요청. 수정 견적서 보내 드리기로 했어요", false)
        // 내일 후속 일정 1건 (하루 마감의 "내일 일정" 시연)
        note(clients["새솔내과의원"]?.id, "새솔내과의원", null, now - 2 * 3_600_000L, "원장님이 내일 오전에 전화 달라고 하셨어요", false)
    }

    private fun at(day: Calendar, minuteOfDay: Int): Long = (day.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, minuteOfDay / 60); set(Calendar.MINUTE, minuteOfDay % 60); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

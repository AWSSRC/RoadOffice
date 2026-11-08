package com.roadoffice.app.meeting

import com.roadoffice.app.data.AppDatabase
import com.roadoffice.app.data.ClientEntity
import com.roadoffice.app.workmile.Purpose
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * "AI 에게 묻기"의 사실 찾기 — 질문에서 거래처·기간·물을 거리를 규칙으로 뽑아 DB 에서 숫자를 센다.
 * 답 문장은 AI 가 다듬지만 숫자·날짜는 여기서 나온 것만 쓴다(지어낸 숫자 방지).
 */
object AskFacts {
    private val md = SimpleDateFormat("M/d(E)", Locale.KOREA)

    data class Period(val label: String, val from: Long, val to: Long)

    fun period(q: String, now: Long = System.currentTimeMillis()): Period {
        val c = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        return when {
            q.contains("오늘") -> Period("오늘", c.timeInMillis, c.timeInMillis + DAY)
            q.contains("이번 주") || q.contains("이번주") -> { c.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY); if (c.timeInMillis > now) c.add(Calendar.DAY_OF_MONTH, -7); Period("이번 주", c.timeInMillis, c.timeInMillis + 7 * DAY) }
            q.contains("지난달") || q.contains("지난 달") -> { c.set(Calendar.DAY_OF_MONTH, 1); val to = c.timeInMillis; c.add(Calendar.MONTH, -1); Period("지난달", c.timeInMillis, to) }
            else -> { c.set(Calendar.DAY_OF_MONTH, 1); val from = c.timeInMillis; c.add(Calendar.MONTH, 1); Period("이번 달", from, c.timeInMillis) }
        }
    }

    fun client(q: String, clients: List<ClientEntity>, terms: List<String>): ClientEntity? {
        val fixed = TermFixer.fix(q, clients.map { it.name } + terms).text.replace(" ", "")
        return clients.filter { fixed.contains(it.name.replace(" ", "")) || fixed.contains(it.name.replace(" ", "").take(3)) }.maxByOrNull { it.name.length }
    }

    /** 답에 꼭 들어가야 할 말 — 기간 · 거래처 이름 */
    fun must(q: String, clients: List<ClientEntity>, terms: List<String>, now: Long = System.currentTimeMillis()): List<String> =
        listOfNotNull(period(q, now).label, client(q, clients, terms)?.name)

    suspend fun collect(q: String, db: AppDatabase, terms: List<String>, now: Long = System.currentTimeMillis()): List<String> {
        val clients = db.clients().all()
        val c = client(q, clients, terms)
        val p = period(q, now)
        val trips = db.trips().between(p.from, p.to)
        val notes = db.notes().all()
        val facts = mutableListOf<String>()
        val wantVisit = Regex("몇 ?번|방문|갔|다녀").containsMatchIn(q)
        val wantTask = Regex("할 ?일|해야|남은").containsMatchIn(q)
        val wantNext = Regex("일정|언제|다음|약속").containsMatchIn(q)
        val wantKm = Regex("거리|km|킬로|정산|청구|운행").containsMatchIn(q)
        if (c != null) {
            val visits = trips.filter { it.clientId == c.id }.sortedBy { it.startAt }
            val cn = notes.filter { it.clientId == c.id || it.clientName == c.name }
            if (wantVisit || !(wantTask || wantNext || wantKm)) facts += "${p.label} ${c.name} 업무 방문 ${visits.size}번" + (visits.lastOrNull()?.let { ", 마지막 방문 ${md.format(Date(it.startAt))}" } ?: "")
            val open = cn.filter { !it.tasksDone }.flatMap { it.tasks.lines() }.filter { it.isNotBlank() }
            if (wantTask || !(wantVisit || wantNext || wantKm)) facts += if (open.isEmpty()) "${c.name} 남은 할 일 없음" else "${c.name} 남은 할 일 ${open.size}개: ${open.take(2).joinToString(", ")}"
            if (wantNext) cn.filter { (it.followUpAt ?: 0) >= now - DAY }.minByOrNull { it.followUpAt!! }?.let { facts += "${c.name} 다음 후속 일정 ${md.format(Date(it.followUpAt!!))}" }
            if (!wantKm) cn.firstOrNull()?.let { facts += "${c.name} 지난 상담 ${md.format(Date(it.createdAt))}: ${it.summary}" }
        } else {
            val open = notes.filter { !it.tasksDone }.flatMap { n -> n.tasks.lines().filter { it.isNotBlank() }.map { "${n.clientName ?: "미지정"} ${it}" } }
            if (wantTask) facts += if (open.isEmpty()) "남은 할 일 없음" else "남은 할 일 ${open.size}개: ${open.take(3).joinToString(", ")}"
            if (wantNext) notes.filter { (it.followUpAt ?: 0) >= now - DAY }.sortedBy { it.followUpAt }.take(2).forEach { facts += "${it.clientName ?: "미지정"} 후속 일정 ${md.format(Date(it.followUpAt!!))}" }
            if (wantVisit) facts += "${p.label} 업무 운행 ${trips.count { it.purpose == Purpose.BUSINESS.name }}건"
        }
        if (wantKm) {
            val biz = trips.filter { it.purpose == Purpose.BUSINESS.name }.sumOf { it.distanceKm }
            facts += "${p.label} 업무 거리 %.1fkm".format(biz)
        }
        return facts
    }

    private const val DAY = 24 * 60 * 60 * 1000L
}

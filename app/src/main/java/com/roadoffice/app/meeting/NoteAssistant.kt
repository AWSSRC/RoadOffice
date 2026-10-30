package com.roadoffice.app.meeting

import java.util.Calendar

/** 미팅 회고를 정리한 결과. */
data class MeetingSummary(
    val summary: String,
    val tasks: List<String>,
    /** 후속 일정(자정 기준 epoch ms). 말에서 날짜를 못 찾으면 null */
    val followUpAt: Long?,
    val source: String,
)

/** 회고 텍스트 → 요약·할 일·후속 일정. 구현은 기기 내장 규칙 / Pleos LLM / 서버(Bedrock) 로 바꿔 끼운다. */
interface NoteAssistant {
    suspend fun structure(text: String, clientName: String?, now: Long = System.currentTimeMillis()): MeetingSummary
}

/**
 * 서버 없이 차 안에서 동작하는 규칙 기반 정리기 (오프라인·개인정보 외부 전송 0).
 * - 할 일: '보내/연락/전화/제출/준비/확인/방문/하기로/드리기로/까지' 가 들어간 문장
 * - 후속 일정: 오늘/내일/모레/다음 주 X요일/N일
 * - 요약: 첫 문장(최대 60자)
 */
class RuleNoteAssistant : NoteAssistant {
    private val taskWords = listOf("보내", "연락", "전화", "제출", "준비", "확인", "방문", "하기로", "드리기로", "까지", "회신", "미팅 잡")
    private val weekdays = mapOf('월' to Calendar.MONDAY, '화' to Calendar.TUESDAY, '수' to Calendar.WEDNESDAY, '목' to Calendar.THURSDAY, '금' to Calendar.FRIDAY, '토' to Calendar.SATURDAY, '일' to Calendar.SUNDAY)

    override suspend fun structure(text: String, clientName: String?, now: Long): MeetingSummary {
        val clean = text.trim().replace(Regex("\\s+"), " ")
        val sentences = clean.split(Regex("(?<=[.!?。])\\s+|(?<=요)\\s+|(?<=다)\\s+|,\\s*(?=그리고)|\\s+그리고\\s+"))
            .map { it.trim().trimEnd('.', ',').replace(Regex("^(그리고|그래서|또|그리고요|아 |음 )\\s*"), "") }
            .filter { it.isNotEmpty() }
        val tasks = sentences.filter { s -> taskWords.any { s.contains(it) } }.distinct()
        val summary = (sentences.firstOrNull() ?: clean).let { if (it.length > 60) it.take(59) + "…" else it }
        return MeetingSummary(summary, tasks, findDate(clean, now), "rule")
    }

    fun findDate(text: String, now: Long): Long? {
        val c = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        when {
            text.contains("모레") -> return c.apply { add(Calendar.DAY_OF_MONTH, 2) }.timeInMillis
            text.contains("내일") -> return c.apply { add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
        }
        Regex("(다음\\s?주|이번\\s?주)?\\s?([월화수목금토일])요일").find(text)?.let { m ->
            val target = weekdays.getValue(m.groupValues[2][0])
            val next = m.groupValues[1].startsWith("다음")
            // 이번 주 기준 해당 요일로 이동 (월요일 시작), 다음 주면 +7
            val today = c.get(Calendar.DAY_OF_WEEK)
            val mondayOffset = (today + 5) % 7 // 오늘이 월요일이면 0
            c.add(Calendar.DAY_OF_MONTH, -mondayOffset)
            c.add(Calendar.DAY_OF_MONTH, (target + 5) % 7)
            if (next) c.add(Calendar.DAY_OF_MONTH, 7)
            else if (c.timeInMillis < now - 86_400_000L) c.add(Calendar.DAY_OF_MONTH, 7)
            return c.timeInMillis
        }
        Regex("(\\d{1,2})일(까지|에|날)").find(text)?.let { m ->
            val d = m.groupValues[1].toInt()
            if (d in 1..31) {
                if (d < c.get(Calendar.DAY_OF_MONTH)) c.add(Calendar.MONTH, 1)
                c.set(Calendar.DAY_OF_MONTH, d.coerceAtMost(c.getActualMaximum(Calendar.DAY_OF_MONTH)))
                return c.timeInMillis
            }
        }
        if (text.contains("오늘")) return c.timeInMillis
        return null
    }
}

/** 미팅 전 브리핑 문장 (TTS 로 읽기 좋게 3문장 이내). */
object Briefing {
    /** 주행 화면용 한 줄 — 한눈(2초)에 읽히게 거래처와 첫 할 일만. */
    fun short(clientName: String, openTasks: List<String>): String =
        "$clientName · " + if (openTasks.isEmpty()) "남은 할 일 없음" else "할 일 ${openTasks.size}: ${openTasks.first()}"

    fun text(clientName: String, lastSummary: String?, openTasks: List<String>, visits: Int, arriving: Boolean = true): String = buildString {
        append(if (arriving) "곧 ${clientName}에 도착합니다. " else "$clientName 브리핑입니다. ")
        if (lastSummary == null) {
            append("첫 방문이에요. 기록된 상담 내용이 없습니다.")
        } else {
            append("지난 미팅: $lastSummary. ")
            if (openTasks.isNotEmpty()) append("남은 할 일 ${openTasks.size}개, ${openTasks.first()}.") else append("남은 할 일은 없어요.")
            if (visits > 1) append(" 이번이 ${visits + 1}번째 방문입니다.")
        }
    }
}

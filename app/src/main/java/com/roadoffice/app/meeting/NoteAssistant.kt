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

/** 회고 텍스트 → 요약·할 일·후속 일정. 구현은 기기 내장 규칙 / Pleos LLM / 서버(H Chat) 로 바꿔 끼운다. */
interface NoteAssistant {
    suspend fun structure(text: String, clientName: String?, now: Long = System.currentTimeMillis()): MeetingSummary
}

/**
 * 서버 없이 차 안에서 동작하는 규칙 기반 정리기 (오프라인·개인정보 외부 전송 0).
 * - 할 일: '보내/연락/전화/제출/준비/확인/방문/하기로/드리기로/까지' 가 들어간 문장 (상대가 할 일 "연락 주신대요" 는 제외)
 * - 후속 일정: 오늘/내일/모레/다음 주 X요일/N일
 * - 요약: 첫 문장을 짧게 ("원장님이 반응이 좋다고 하셨어요" → "원장님: 반응이 좋음", 최대 60자)
 * - 할 일 문장도 짧게, 기한은 날짜로 ("금요일까지 단가표 보내 드리기로 했어요" → "단가표 보내기 · 11/6(금)까지")
 */
class RuleNoteAssistant : NoteAssistant {
    private val taskWords = listOf("보내", "연락", "전화", "제출", "준비", "확인", "방문", "하기로", "드리기로", "까지", "회신", "미팅 잡")
    private val promise = Regex("기로\\s?(했|함|해요|하자)")
    private val othersAction = listOf("주신대", "주신다고", "주시기로", "하신대", "하신다고", "주실 거", "준대요", "준다고")
    private val weekdays = mapOf('월' to Calendar.MONDAY, '화' to Calendar.TUESDAY, '수' to Calendar.WEDNESDAY, '목' to Calendar.THURSDAY, '금' to Calendar.FRIDAY, '토' to Calendar.SATURDAY, '일' to Calendar.SUNDAY)

    override suspend fun structure(text: String, clientName: String?, now: Long): MeetingSummary {
        val clean = text.trim().replace(Regex("\\s+"), " ")
        val sentences = clean.split(Regex("(?<=[.!?。])\\s+|(?<=요)\\s+|(?<=(?:습니다|니다|했다|한다|이다|였다|었다|았다|겠다|된다|있다|없다|좋다))\\s+|,\\s*(?=그리고)|\\s+그리고\\s+"))
            .map { it.trim().trimEnd('.', ',').replace(Regex("^(그리고|그래서|또|그리고요|아 |음 )\\s*"), "") }
            .filter { it.isNotEmpty() }
        // 상대가 할 일("연락 주신대요")은 내 할 일이 아니다 — 후속 일정으로만 잡힌다
        // "~기로 했어요"(가져가기로·만나기로 등)는 내가 한 약속 → 할 일
        val tasks = sentences.filter { s -> (taskWords.any { s.contains(it) } || promise.containsMatchIn(s)) && othersAction.none { s.contains(it) } }
            .map { tidyTask(it, now) }.distinct()
        // 첫 문장이 거래처 이름으로 시작하면 빼고 요약 (거래처는 따로 표시된다)
        val first = (sentences.firstOrNull() ?: clean).let { f -> clientName?.takeIf { it.isNotBlank() && f.startsWith(it) }?.let { f.removePrefix(it).trim() } ?: f }
        val summary = tidySummary(first).let { if (it.length > 60) it.take(59) + "…" else it }
        return MeetingSummary(summary, tasks, findDate(clean, now), "rule")
    }

    private val dateWords = Regex("(다다음\\s?주|다음\\s?주|담주|이번\\s?주)?\\s?[월화수목금토일]요일(까지|에|날)?|(오늘|내일|모레)\\s?(중으로|중에|중)?(까지)?|\\d{1,2}일(까지|에|날)")
    private val md = java.text.SimpleDateFormat("M/d(E)", java.util.Locale.KOREA)

    /** "금요일까지 단가표 보내 드리기로 했어요" → "단가표 보내기 · 11/6(금)까지" */
    fun tidyTask(sentence: String, now: Long): String {
        val due = if (sentence.contains("까지")) findDate(sentence, now) else null
        var t = sentence.replace(dateWords, "").replace(Regex("\\s{2,}"), " ").trim()
        t = t.replace(Regex("^(에|에는|까지)\\s+"), "")
        // "원장님이 (오전에) 전화 달라고 하셨어요" → "원장님께 (오전에) 전화하기" — 부탁받은 일
        Regex("^([가-힣]{1,6}님)[이께서]{1,2}\\s+(.*?)(전화|연락|회신)\\s?(해\\s?)?(달라고|주라고)\\s?(하셨어요|하셨습니다|하심|했어요)$").find(t)?.let { m ->
            t = "${m.groupValues[1]}께 ${m.groupValues[2]}${m.groupValues[3]}하기".replace(Regex("\\s+"), " ")
        }
        t = t.replace(Regex("(보내|전달하|제출하|준비하|확인하|방문하|회신하|가져가|챙기)\\s?드리기로\\s?(했어요|했습니다|했음|함|했다)$"), "$1기")
            .replace(Regex("드리기로\\s?(했어요|했습니다|했음|함|했다)$"), "드리기")
            .replace(Regex("기로\\s?(했어요|했습니다|했음|함|했다)$"), "기")
            .replace(Regex("(해야|해야 해|해야 돼)(요|함)?$"), "하기")
            .trim()
        return if (due != null) "$t · ${md.format(java.util.Date(due))}까지" else t
    }

    /** 첫 문장을 메모처럼: "원장님이 신제품 샘플 반응이 좋다고 하셨어요" → "원장님: 신제품 샘플 반응이 좋음" */
    fun tidySummary(sentence: String): String {
        var t = sentence.trim()
        Regex("^([가-힣]{1,6}님)[이은께서]{1,2}\\s+").find(t)?.let { t = it.groupValues[1] + ": " + t.substring(it.range.last + 1) }
        Regex("([가-힣])(다고|대요|다고요)\\s?(하셨어요|하셨습니다|하시네요|하세요|했어요|하더라고요)?$").find(t)?.let { m ->
            nominal(m.groupValues[1][0])?.let { t = t.substring(0, m.range.first) + it }
        }
        return t
    }

    /** 형용사 어간 끝 글자 → 명사형 ("좋"→"좋음", "크"→"큼"). 받침 ㄴ(한·된 등 동사 현재형)은 그대로 두기 위해 null. */
    private fun nominal(c: Char): String? {
        if (c !in '가'..'힣') return null
        val jong = (c - '가') % 28
        return when (jong) {
            0 -> (c + 16).toString()          // 받침 없음 → ㅁ 받침
            4 -> null                          // ㄴ 받침(한다고·된다고)은 바꾸지 않음
            else -> "${c}음"
        }
    }

    fun findDate(text: String, now: Long): Long? {
        val c = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        when {
            text.contains("모레") -> return c.apply { add(Calendar.DAY_OF_MONTH, 2) }.timeInMillis
            text.contains("내일") -> return c.apply { add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
        }
        Regex("(다다음\\s?주|다음\\s?주|담주|이번\\s?주)?\\s?([월화수목금토일])요일").find(text)?.let { m ->
            val target = weekdays.getValue(m.groupValues[2][0])
            val p = m.groupValues[1].replace(" ", "")
            val weeksAhead = when { p.startsWith("다다음") -> 2; p.startsWith("다음") || p == "담주" -> 1; else -> 0 }
            val next = weeksAhead > 0
            // 이번 주 기준 해당 요일로 이동 (월요일 시작), 다음 주면 +7
            val today = c.get(Calendar.DAY_OF_WEEK)
            val mondayOffset = (today + 5) % 7 // 오늘이 월요일이면 0
            c.add(Calendar.DAY_OF_MONTH, -mondayOffset)
            c.add(Calendar.DAY_OF_MONTH, (target + 5) % 7)
            if (next) c.add(Calendar.DAY_OF_MONTH, 7 * weeksAhead)
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
        "$clientName · " + if (openTasks.isEmpty()) "남은 할 일 없음" else "남은 할 일 ${openTasks.size}개 · ${openTasks.first()}"

    fun text(clientName: String, lastSummary: String?, openTasks: List<String>, arriving: Boolean = true): String = buildString {
        // 주행 중에 듣는 말 — 두 문장 안쪽으로 짧게 (방문 횟수 등 부가 정보는 화면으로)
        append(if (arriving) "곧 ${clientName}입니다. " else "$clientName 브리핑입니다. ")
        if (lastSummary == null) {
            append("첫 방문이에요.")
        } else {
            append("지난번, $lastSummary. ")
            if (openTasks.isNotEmpty()) append("남은 할 일 ${openTasks.size}개, ${openTasks.first()}.") else append("남은 할 일은 없어요.")
        }
    }
}

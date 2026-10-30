package com.roadoffice.app.meeting

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class NoteAssistantTest {
    private val a = RuleNoteAssistant()

    /** 2026-10-28(수) 15:00 */
    private val now = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 28, 15, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    private fun day(y: Int, m: Int, d: Int) = Calendar.getInstance().apply { set(y, m, d, 0, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    @Test
    fun `회고에서 요약 할 일 후속 일정을 뽑는다`() = runBlocking {
        val s = a.structure("견적서 마음에 들어 하셨어요 배우자랑 상의하고 다음 주 화요일에 연락 주신대요 그리고 카탈로그 보내 드리기로 했어요", "김고객", now)
        assertEquals("견적서 마음에 들어 하셨어요", s.summary)
        assertEquals(2, s.tasks.size)
        assertTrue(s.tasks.any { it.contains("연락") })
        assertTrue(s.tasks.any { it.startsWith("카탈로그") }) // 앞의 '그리고' 제거
        assertEquals(day(2026, Calendar.NOVEMBER, 3), s.followUpAt)
        assertEquals("rule", s.source)
    }

    @Test
    fun `날짜 표현 - 내일 모레 이번 주 금요일 N일까지`() {
        assertEquals(day(2026, Calendar.OCTOBER, 29), a.findDate("내일 전화", now))
        assertEquals(day(2026, Calendar.OCTOBER, 30), a.findDate("모레 방문", now))
        assertEquals(day(2026, Calendar.OCTOBER, 30), a.findDate("금요일에 제출", now))
        assertEquals(day(2026, Calendar.NOVEMBER, 5), a.findDate("5일까지 회신", now))
        assertNull(a.findDate("좋은 미팅이었어요", now))
    }

    @Test
    fun `브리핑 문장 - 첫 방문과 재방문`() {
        assertTrue(Briefing.text("서울아산병원", null, emptyList(), 0).contains("첫 방문"))
        val t = Briefing.text("서울아산병원", "견적 검토 중", listOf("카탈로그 보내기"), 2)
        assertTrue(t.contains("지난 미팅: 견적 검토 중"))
        assertTrue(t.contains("남은 할 일 1개, 카탈로그 보내기"))
        assertNotNull(t)
    }
}

class LlmParseTest {
    // parse 는 LLM 객체 없이 검증 (Context 불필요한 정적 경로만 사용)
    private fun parse(raw: String) = LlmParser.parse(raw, "다음 주 화요일 연락", 0L)

    @Test
    fun `정상 JSON 은 그대로, 앞뒤 잡음은 무시`() {
        val s = parse("결과: {\"summary\":\"견적 긍정적\",\"tasks\":[\"카탈로그 보내기\"],\"followUp\":\"2026-11-03\"} 끝")!!
        assertEquals("견적 긍정적", s.summary)
        assertEquals(listOf("카탈로그 보내기"), s.tasks)
        assertEquals("llm", s.source)
    }

    @Test
    fun `요약이 비거나 JSON 이 아니면 null`() {
        assertNull(parse("{\"summary\":\"\",\"tasks\":[]}"))
        assertNull(parse("모르겠어요"))
    }

    @Test
    fun `주행 화면 브리핑은 한 줄 - 거래처와 첫 할 일만`() {
        org.junit.Assert.assertEquals("한빛병원 · 할 일 2: 수정 견적서 보내기", Briefing.short("한빛병원", listOf("수정 견적서 보내기", "샘플 전달")))
        org.junit.Assert.assertEquals("한빛병원 · 남은 할 일 없음", Briefing.short("한빛병원", emptyList()))
    }
}

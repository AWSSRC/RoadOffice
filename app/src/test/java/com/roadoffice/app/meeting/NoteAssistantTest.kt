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
        assertEquals(1, s.tasks.size) // "연락 주신대요"는 고객이 할 일 → 후속 일정으로만
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
        assertTrue(Briefing.text("서울아산병원", null, emptyList()).contains("첫 방문"))
        val t = Briefing.text("서울아산병원", "견적 검토 중", listOf("카탈로그 보내기"))
        assertTrue(t.contains("지난번, 견적 검토 중"))
        assertTrue(t.contains("남은 할 일 1개, 카탈로그 보내기"))
        assertNotNull(t)
    }

    @Test
    fun `할 일은 짧게 기한은 날짜로, 요약은 메모처럼`() = runBlocking {
        val r = RuleNoteAssistant().structure("원장님이 신제품 샘플 반응이 좋다고 하셨어요 다음 주 화요일에 다시 연락 주신대요 그리고 금요일까지 단가표 보내 드리기로 했어요", "한빛병원", now)
        assertEquals("원장님: 신제품 샘플 반응이 좋음", r.summary)
        assertEquals(1, r.tasks.size)
        assertTrue(r.tasks[0], r.tasks[0].startsWith("단가표 보내기 · ") && r.tasks[0].endsWith("(금)까지"))
        assertEquals("반응이 큼", RuleNoteAssistant().tidySummary("반응이 크대요"))
        assertEquals("다음 달 발주한다고 하셨어요", RuleNoteAssistant().tidySummary("다음 달 발주한다고 하셨어요")) // 동사 현재형은 그대로
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
        org.junit.Assert.assertEquals("한빛병원 · 남은 할 일 2개 · 수정 견적서 보내기", Briefing.short("한빛병원", listOf("수정 견적서 보내기", "샘플 전달")))
        org.junit.Assert.assertEquals("한빛병원 · 남은 할 일 없음", Briefing.short("한빛병원", emptyList()))
    }

    @Test
    fun `상대가 할 일은 내 할 일로 넣지 않고 후속 일정만 잡는다`() = kotlinx.coroutines.runBlocking {
        val r = RuleNoteAssistant().structure("원장님이 샘플 반응이 좋대요 다음 주 화요일에 다시 연락 주신대요 그리고 금요일까지 단가표 보내 드리기로 했어요", "한빛병원")
        org.junit.Assert.assertEquals(1, r.tasks.size)
        org.junit.Assert.assertTrue(r.tasks[0].startsWith("단가표 보내기"))
        org.junit.Assert.assertNotNull(r.followUpAt)
    }
}

class DemoTalkTest {
    private val now = java.util.Calendar.getInstance().apply { set(2026, java.util.Calendar.OCTOBER, 28, 15, 0, 0) }.timeInMillis

    @Test
    fun `시연 데이터 회고도 정리기 규칙대로 - 원문에 없는 할 일을 만들지 않는다`() = kotlinx.coroutines.runBlocking {
        val a = RuleNoteAssistant()
        org.junit.Assert.assertEquals(emptyList<String>(), a.structure("원장님이 바쁘셔서 간단히 인사만 드렸어요", "새솔", now).tasks)
        val b = a.structure("원장님 견적 금액 조정 요청. 수정 견적서 보내 드리기로 했어요", "한빛병원", now)
        org.junit.Assert.assertEquals("원장님 견적 금액 조정 요청", b.summary)
        org.junit.Assert.assertEquals(listOf("수정 견적서 보내기"), b.tasks)
    }

    @Test
    fun `약속 표현 "~기로 했어요"도 할 일`() = kotlinx.coroutines.runBlocking {
        val r = RuleNoteAssistant().structure("약국장님이 샘플을 더 원하셨어요 내일 샘플 챙겨 가기로 했어요", "미래약국", now)
        org.junit.Assert.assertEquals(listOf("샘플 챙겨 가기"), r.tasks)
    }

    @Test
    fun `요약이 거래처 이름으로 시작하면 뺀다`() = kotlinx.coroutines.runBlocking {
        val r = RuleNoteAssistant().structure("한빛병원 원장님이 신제품 반응이 좋대요", "한빛병원", now)
        org.junit.Assert.assertEquals("원장님: 신제품 반응이 좋음", r.summary)
    }

    @Test
    fun `부탁받은 연락은 할 일 문장으로`() = kotlinx.coroutines.runBlocking {
        val r = RuleNoteAssistant().structure("원장님이 내일 오전에 전화 달라고 하셨어요", "새솔내과의원", now)
        org.junit.Assert.assertEquals(listOf("원장님께 오전에 전화하기"), r.tasks)
        org.junit.Assert.assertNotNull(r.followUpAt)
    }
}

class NoteFixes1102Test {
    // 2026-11-04(수) 15:00
    private val now = java.util.Calendar.getInstance().apply { set(2026, java.util.Calendar.NOVEMBER, 4, 15, 0, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis
    private fun day(m: Int, d: Int) = java.util.Calendar.getInstance().apply { set(2026, m, d, 0, 0, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis

    @Test
    fun `갖다 드리기로 같은 말을 자르지 않는다`() = kotlinx.coroutines.runBlocking {
        val r = RuleNoteAssistant().structure("실장님이 샘플을 원하셔서 내일 샘플 갖다 드리기로 했어요", "미래약국", now)
        org.junit.Assert.assertEquals(1, r.tasks.size)
        org.junit.Assert.assertTrue(r.tasks[0], r.tasks[0].endsWith("샘플 갖다 드리기")) // "갖다"에서 잘리지 않음
    }

    @Test
    fun `담주와 다다음 주`() {
        val a = RuleNoteAssistant()
        org.junit.Assert.assertEquals(day(java.util.Calendar.NOVEMBER, 12), a.findDate("담주 목요일에 다시 뵙기로", now))
        org.junit.Assert.assertEquals(day(java.util.Calendar.NOVEMBER, 17), a.findDate("다다음 주 화요일에 미팅", now))
        org.junit.Assert.assertEquals(day(java.util.Calendar.NOVEMBER, 10), a.findDate("다음 주 화요일에 미팅", now))
    }

    @Test
    fun `날짜 말을 지운 뒤 공백이 겹치지 않는다`() {
        val t = RuleNoteAssistant().tidyTask("저는 오늘 중으로 행사 상품 목록 보내 드리기로 했어요", now)
        org.junit.Assert.assertEquals("저는 행사 상품 목록 보내기", t)
    }
}

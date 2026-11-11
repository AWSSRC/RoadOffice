package com.roadoffice.app.meeting

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiAssistTest {
    private val summary = "한빛병원 원장님: 신제품 샘플 반응 좋음"
    private val tasks = listOf("단가표 보내기 · 11/6(금)까지")

    @Test fun `AI 후속 제안이 검사를 통과하면 그대로 쓴다`() = runBlocking {
        val ai = AiAssist { """{"message":"원장님, 말씀드린 단가표는 금요일까지 보내 드리겠습니다.","tip":"신제품 샘플 써 보신 반응 먼저 여쭤보기"}""" }
        val f = ai.followUp("한빛병원", summary, tasks)
        assertEquals(AiAssist.AI, f.by)
        assertTrue(f.message.contains("단가표"))
    }

    @Test fun `AI 출력이 이상하면 문장 틀로 대신한다`() = runBlocking {
        val f = AiAssist { "모르겠어요" }.followUp("한빛병원", summary, tasks)
        assertEquals(AiAssist.TEMPLATE, f.by)
        assertEquals("원장님, 오늘 말씀 나눈 '단가표 보내기'는 11/6(금)까지 챙기겠습니다. 감사합니다.", f.message)
        val none = AiAssist { null }.followUp("한빛병원", summary, tasks)
        assertEquals(AiAssist.TEMPLATE, none.by)
    }

    @Test fun `요약의 말이 섞여 꼬인 문자나 조건문 한 줄은 문장 틀로`() = runBlocking {
        // 11/11 에뮬레이터 실제 출력 — 고객 반응(요약)이 내 약속 문장에 섞임
        val mixed = AiAssist { """{"message":"원장님, 신제품 샘플 반응이 11/6(금)까지 단가표를 보내드리겠습니다.","tip":"'단가표 보내기' 진행 상황부터 확인"}""" }
        assertEquals(AiAssist.TEMPLATE, mixed.followUp("한빛병원", summary, tasks).by)
        val cond = AiAssist { """{"message":"원장님, 단가표는 11/6(금)까지 보내 드리겠습니다.","tip":"전화를 하시면 먼저 여쭤보기"}""" }
        assertEquals(AiAssist.TEMPLATE, cond.followUp("한빛병원", summary, tasks).by)
    }

    @Test fun `답에 사실에 없는 숫자가 있으면 버린다`() = runBlocking {
        val facts = listOf("이번 달 한빛병원 업무 방문 4번, 마지막 방문 11/4(수)")
        val good = AiAssist { """{"answer":"이번 달 한빛병원은 4번 방문하셨고, 마지막은 11/4(수)이에요."}""" }.answer("한빛병원 몇 번 갔어?", facts)
        assertEquals(AiAssist.AI, good.by)
        val bad = AiAssist { """{"answer":"이번 달 한빛병원은 7번 방문하셨어요."}""" }.answer("한빛병원 몇 번 갔어?", facts)
        assertEquals(AiAssist.TEMPLATE, bad.by)
        assertEquals(facts.first(), bad.text)
    }

    @Test fun `질문의 기간을 읽는다`() {
        val now = java.util.Calendar.getInstance().apply { set(2026, 10, 4, 15, 0) }.timeInMillis
        assertEquals("이번 달", AskFacts.period("한빛병원 몇 번 갔어", now).label)
        assertEquals("지난달", AskFacts.period("지난달 업무 거리", now).label)
        assertEquals("오늘", AskFacts.period("오늘 남은 할 일", now).label)
        assertFalse(AiAssist.okTip("우주선 발사", listOf(summary)))
    }
}

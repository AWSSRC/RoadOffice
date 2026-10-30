package com.roadoffice.app.meeting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TermFixerTest {
    private val terms = listOf("한빛병원", "새솔내과의원", "미래약국", "로수바정")

    @Test
    fun `띄어쓰기·받침 오인식을 사전 이름으로 고친다`() {
        val r = TermFixer.fix("오늘 한빗 병원 원장님이 로수바 정 반응이 좋대요", terms)
        assertEquals("오늘 한빛병원 원장님이 로수바정 반응이 좋대요", r.text)
        assertEquals(2, r.fixes.size)
    }

    @Test
    fun `긴 이름도 띄어 말한 것을 붙인다`() {
        assertEquals("새솔내과의원 다녀왔어요", TermFixer.fix("새솔 내과 의원 다녀왔어요", terms).text)
    }

    @Test
    fun `비슷하지 않은 말은 건드리지 않는다`() {
        val s = "다음 주 화요일에 다시 연락 주신대요"
        val r = TermFixer.fix(s, terms)
        assertEquals(s, r.text)
        assertTrue(r.fixes.isEmpty())
    }
}

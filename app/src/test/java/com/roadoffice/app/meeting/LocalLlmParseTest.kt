package com.roadoffice.app.meeting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLlmParseTest {
    private val ctxFree = object { fun ok(s: String, o: String) = LocalLlmGuard.plausible(s, o) }

    @Test fun `바이트 토큰으로 깨진 한글을 되돌린다`() {
        // "했어요" 의 UTF-8 바이트를 GPT-2 bytes_to_unicode 로 바꾼 꼴 + 정상 글자 섞임
        val broken = "좋다고" + "했어요".toByteArray().joinToString("") { b -> ByteLevelTestMap.char(b.toInt() and 0xff).toString() }
        assertEquals("좋다고했어요", ByteLevel.fix(broken))
        assertEquals("원장님: 반응 좋음", ByteLevel.fix("원장님: 반응 좋음"))
    }

    @Test fun `쓸 만한 요약만 통과`() {
        val o = "한빛병원 원장님이 신제품 샘플 반응이 좋다고 하셨어요 금요일까지 단가표 보내 드리기로 했어요"
        assertTrue(ctxFree.ok("한빛병원 원장님: 신제품 샘플 반응 좋음", o))
        assertFalse(ctxFree.ok("음 한빛병원 샘플 반응", o))                  // 군더더기 시작
        assertFalse(ctxFree.ok("원장님: 금요일까지 단가표 보내기", o))        // 할 일 섞임
        assertFalse(ctxFree.ok("원장님: 반응 좋다고 하셨어요", o))            // 구어체 그대로
        assertFalse(ctxFree.ok("고객님: 우주선 발사 일정 협의", o))           // 원문에 없는 말
    }
}

/** 테스트용 GPT-2 bytes_to_unicode */
object ByteLevelTestMap {
    private val bs = (33..126) + (161..172) + (174..255)
    fun char(b: Int): Char { if (b in bs) return b.toChar(); var n = 0; for (x in 0..255) if (x !in bs) { if (x == b) return (256 + n).toChar(); n++ }; error("") }
}

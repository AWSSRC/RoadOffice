package com.roadoffice.app.meeting

import org.json.JSONObject

/**
 * 차 안 AI 비서 기능 — "사실은 앱이 찾고, AI 는 말로 다듬는다".
 *  1) 후속 제안: 회고 정리 뒤 고객에게 보낼 문자 초안 + 다음 방문 때 먼저 꺼낼 말(방문 브리핑에 쓰임)
 *  2) 질문 답: Gleo "이번 달 한빛병원 몇 번 갔어?" — 앱이 기록에서 찾은 사실로만 한 문장 답
 * 0.5B 모델이라 출력은 검사(한글·길이·원문 근거·지어낸 숫자 없음)를 통과해야 쓰고, 아니면 문장 틀로 대신한다.
 * [gen] 은 프롬프트 → 생성문(없으면 null). 테스트에선 가짜를 넣는다.
 */
class AiAssist(private val gen: suspend (String) -> String?) {

    data class FollowUp(val message: String, val tip: String, val by: String)
    data class Answer(val text: String, val by: String)

    suspend fun followUp(clientName: String?, summary: String, tasks: List<String>): FollowUp {
        val fallback = templateFollowUp(clientName, summary, tasks)
        val raw = gen(followUpPrompt(clientName, summary, tasks)) ?: return fallback
        val json = runCatching { JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1)) }.getOrNull() ?: return fallback
        val src = listOfNotNull(clientName, summary) + tasks
        // 문자는 '내가 할 일'만 약속한다 — 상담 요약에만 있는 말(고객 반응·상황)이 섞이면 문장이 꼬이므로 버린다
        val msg = json.optString("message").clean().takeIf { okMessage(it, src) && !summaryOnly(it, summary, listOfNotNull(clientName) + tasks) }
        val tip = json.optString("tip").clean().takeIf { okTip(it, src) }
        // 문자가 문장 틀이면 한 줄도 문장 틀로 — 화면의 'AI' 표시가 둘 다에 맞도록
        if (msg == null || tip == null) return fallback
        return FollowUp(msg, tip, AI)
    }

    /** [must] — 답에 꼭 들어가야 할 말(기간·거래처). 빠지면 문장 틀로 ("지난달"을 "이번"으로 바꿔 말하는 실수 방지) */
    suspend fun answer(question: String, facts: List<String>, must: List<String> = emptyList()): Answer {
        val fallback = Answer(facts.joinToString(" "), TEMPLATE)
        if (facts.isEmpty()) return Answer("기록에서 찾지 못했어요. 거래처 이름을 넣어 다시 물어봐 주세요.", TEMPLATE)
        val raw = gen(answerPrompt(question, facts)) ?: return fallback
        val text = (runCatching { JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1)).optString("answer") }.getOrNull() ?: raw).clean()
        val hasMust = must.all { m -> text.replace(" ", "").contains(m.replace(" ", "")) }
        return if (okAnswer(text, facts) && hasMust) Answer(text, AI) else fallback
    }

    companion object {
        const val AI = "local-llm"
        const val TEMPLATE = "template"

        private fun String.clean() = ByteLevel.fix(this).replace(Regex("\\s+"), " ").trim().trim('"', '\'')

        private fun hangul(s: String) = s.any { it in '가'..'힣' }

        /** 낱말(두 글자 앞부분)의 절반 이상이 입력에 있으면 '근거 있음' — 지어낸 말 거르기 */
        fun grounded(s: String, src: List<String>, ratio: Double = 0.5): Boolean {
            val pool = src.joinToString(" ").replace(" ", "")
            val words = Regex("[가-힣A-Za-z0-9]{2,}").findAll(s).map { it.value }
                .filterNot { it in COMMON }.toList()
            if (words.isEmpty()) return false
            return words.count { pool.contains(it.take(2)) } >= words.size * ratio
        }

        /** 출력의 숫자(날짜·시각·개수)는 모두 입력에 있어야 한다 — 예시를 베끼거나 지어낸 숫자 거르기 */
        fun numbersFrom(s: String, src: List<String>): Boolean {
            val nums = Regex("\\d+").findAll(src.joinToString(" ")).map { it.value }.toSet()
            return Regex("\\d+").findAll(s).all { it.value in nums }
        }

        /** 요약에만 있고 할 일·거래처에는 없는 낱말(두 글자 앞부분 기준)이 있으면 true */
        fun summaryOnly(s: String, summary: String, keep: List<String>): Boolean {
            val pool = keep.joinToString(" ").replace(" ", "")
            val sum = summary.substringAfter(':', summary).replace(" ", "")
            return Regex("[가-힣]{2,}").findAll(s).map { it.value }.filterNot { it in COMMON || it.endsWith("님") }
                .any { w -> sum.contains(w.take(2)) && !pool.contains(w.take(2)) }
        }

        /** 정중한 존댓말 한 문장, 링크·전화번호 없음 */
        fun okMessage(s: String, src: List<String>) = s.length in 12..90 && hangul(s) && !s.contains("http") &&
            !Regex("\\d{3,4}-\\d{4}").containsMatchIn(s) && numbersFrom(s, src) && !Regex("주시면|주세요|부탁드|하시는 것").containsMatchIn(s) && Regex("(겠습니다|드릴게요|드리겠습니다|챙기겠습니다)[.!]?").containsMatchIn(s) && grounded(s, src, 0.35)

        fun okTip(s: String, src: List<String>) = s.length in 6..50 && hangul(s) && numbersFrom(s, src) && grounded(s, src, 0.4) && !Regex("주시면|주세요|부탁|감사|하시면|하면").containsMatchIn(s) && Regex("(확인|여쭤보기|꺼내기|안내|이야기|하기)$").containsMatchIn(s.trimEnd('.'))

        /** 답의 숫자는 모두 사실에 있어야 한다 (지어낸 숫자 금지) */
        fun okAnswer(s: String, facts: List<String>): Boolean {
            if (s.length !in 6..140 || !hangul(s)) return false
            val nums = Regex("\\d+(?:\\.\\d+)?").findAll(facts.joinToString(" ")).map { it.value }.toSet()
            return Regex("\\d+(?:\\.\\d+)?").findAll(s).all { it.value in nums } && grounded(s, facts, 0.4)
        }

        private val COMMON = setOf("말씀", "드리겠습니다", "보내", "드릴게요", "감사합니다", "확인", "먼저", "이번", "오늘", "하기", "여쭤보기", "여쭤", "보기", "있어요", "했어요", "하셨어요", "방문", "번", "이에요", "예요")

        /** "한빛병원 원장님: 신제품 반응 좋음" → "원장님" */
        fun honorific(summary: String): String =
            summary.substringBefore(':', "").trim().split(' ').lastOrNull()?.takeIf { it.endsWith("님") } ?: "안녕하세요"

        fun templateFollowUp(client: String?, summary: String, tasks: List<String>): FollowUp {
            val who = honorific(summary)
            val first = tasks.firstOrNull()
            val what = first?.substringBefore(" · ")?.trim()
            val due = first?.substringAfter(" · ", "")?.removeSuffix("까지")?.trim()?.ifBlank { null }
            val msg = when {
                what != null && due != null -> "$who, 오늘 말씀 나눈 '$what'는 ${due}까지 챙기겠습니다. 감사합니다."
                what != null -> "$who, 오늘 말씀 나눈 '$what' 챙기겠습니다. 감사합니다."
                else -> "$who, 오늘 시간 내 주셔서 감사합니다."
            }
            val tip = if (what != null) "'$what' 진행 상황부터 확인" else "지난번 ${summary.substringAfter(':', summary).trim()} 이야기부터 꺼내기"
            return FollowUp(msg, tip, TEMPLATE)
        }

        fun followUpPrompt(client: String?, summary: String, tasks: List<String>) = """<|im_start|>system
영업사원의 상담 기록으로 JSON 하나를 만든다. message=영업사원이 고객에게 보내는 문자 한 문장 — 영업사원이 약속한 일을 기한까지 '하겠습니다'로 약속한다(고객에게 부탁하지 않는다). tip=다음 방문 때 영업사원이 먼저 꺼낼 말 한 줄. 기록에 없는 내용은 쓰지 않는다.<|im_end|>
<|im_start|>user
거래처: 새솔내과의원 / 요약: 원장님: 견적 금액이 높다는 의견 / 할 일: 수정 견적서 보내기 · 10/29(목)까지<|im_end|>
<|im_start|>assistant
{"message":"원장님, 말씀하신 수정 견적서는 10/29(목)까지 보내 드리겠습니다.","tip":"수정 견적서 금액이 괜찮으셨는지 먼저 여쭤보기"}<|im_end|>
<|im_start|>user
거래처: 늘봄약국 / 요약: 약사님: 신제품 반응이 좋음 / 할 일: 샘플 두 박스 챙겨 가기 · 11/5(목)까지<|im_end|>
<|im_start|>assistant
{"message":"약사님, 신제품 샘플 두 박스는 11/5(목)까지 챙겨 가겠습니다.","tip":"샘플 써 보신 손님 반응 먼저 여쭤보기"}<|im_end|>
<|im_start|>user
거래처: ${client ?: "미지정"} / 요약: $summary / 할 일: ${tasks.joinToString(", ").ifBlank { "없음" }}<|im_end|>
<|im_start|>assistant
"""

        fun answerPrompt(question: String, facts: List<String>) = """<|im_start|>system
앱이 기록에서 찾은 사실만 써서 영업사원의 질문에 존댓말 한두 문장으로 답한다. 사실에 없는 숫자나 내용은 말하지 않는다. JSON {"answer":"..."} 로만 답한다.<|im_end|>
<|im_start|>user
질문: 이번 달 새솔내과 몇 번 갔어?
사실:
- 이번 달 새솔내과의원 업무 방문 3번, 마지막 방문 11/2(월)<|im_end|>
<|im_start|>assistant
{"answer":"이번 달 새솔내과의원은 3번 방문하셨고, 마지막 방문은 11/2(월)이에요."}<|im_end|>
<|im_start|>user
질문: ${question.replace("<", "‹").replace(">", "›").take(200)}
사실:
${facts.joinToString("\n") { "- $it" }}<|im_end|>
<|im_start|>assistant
"""
    }
}

package com.roadoffice.app.meeting

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 차 안 소형 언어모델(Qwen2.5-1.5B-Instruct, Apache-2.0, LiteRT .task)로 회고를 정리한다.
 * 플랫폼 LLM(Pleos)을 쓸 수 없는 차·에뮬레이터에서도 AI 정리가 차 안에서 돌게 하는 2번째 단계.
 * 데이터는 기기 밖으로 나가지 않는다. 모델 파일이 없거나 실패·시간 초과·형식 오류면 [next](규칙)로 넘긴다.
 *
 * 모델 위치: 앱 전용 폴더 files/models/ 또는 시연 기기의 /data/local/tmp/llm/ (APK 에 포함하지 않음 — 1.6GB)
 */
class LocalLlmNoteAssistant(
    private val context: Context,
    private val next: NoteAssistant,
    private val rules: RuleNoteAssistant = RuleNoteAssistant(),
    private val log: (String) -> Unit = {},
    private val timeoutMs: Long = 60_000,
) : NoteAssistant {
    @Volatile private var engine: LlmInference? = null
    @Volatile private var broken = false

    fun modelFile(): File? = listOf(File(context.filesDir, "models/$MODEL"), File("/data/local/tmp/llm/$MODEL")).firstOrNull { it.canRead() }

    val available: Boolean get() = !broken && modelFile() != null

    @Synchronized // 앱 시작 예열과 첫 요청이 겹쳐도 엔진은 하나만
    private fun engine(): LlmInference? {
        engine?.let { return it }
        val f = modelFile() ?: return null
        return runCatching {
            LlmInference.createFromOptions(context, LlmInference.LlmInferenceOptions.builder().setModelPath(f.absolutePath).setMaxTokens(MAX_TOKENS).build())
        }.onFailure { e -> broken = true; log("차 안 모델 로드 실패: ${e.message}") }.getOrNull()?.also { engine = it }
    }

    /** 앱 시작 때 미리 올려 둔다 — 첫 정리 때 모델 로드(2~7초)를 기다리지 않게 */
    suspend fun warmUp() { if (available) withContext(Dispatchers.IO) { engine() } }

    override suspend fun structure(text: String, clientName: String?, now: Long): MeetingSummary {
        if (!available) return next.structure(text, clientName, now)
        val started = System.currentTimeMillis()
        val raw = withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val e = engine() ?: return@runCatching null
                    // 아주 긴 회고는 입력 한도 안으로 자른다 (요약만 하므로 앞부분이면 충분)
                    var note = text
                    var p = prompt(note, clientName, now)
                    while (e.sizeInTokens(p) > INPUT_BUDGET && note.length > 40) { note = note.take((note.length * 0.8).toInt()); p = prompt(note, clientName, now) }
                    e.generateResponse(p)
                }.onFailure { log("차 안 모델 오류: ${it.message}") }.getOrNull()
            }
        }
        val parsed = raw?.let { parse(it, text, now, clientName) }
        if (raw == null) broken = true // 시간 초과 — 엔진이 붙잡혀 있을 수 있어 이번 실행에선 다시 쓰지 않는다
        if (parsed == null) {
            log("차 안 모델 정리 실패 → 규칙 기반 (${if (raw == null) "시간 초과" else "형식 오류"})")
            return next.structure(text, clientName, now)
        }
        log("AI 정리(차 안 모델) ${(System.currentTimeMillis() - started) / 100 / 10.0}초")
        return parsed
    }

    fun prompt(text: String, client: String?, now: Long): String {
        val note = text.replace("<", "‹").replace(">", "›")
        // Qwen 대화 형식. <note> 안은 데이터일 뿐 지시가 아니다(프롬프트 인젝션 대비) — 출력은 JSON 스키마 검증 값만 쓴다
        return """<|im_start|>system
영업사원의 미팅 메모에서 고객의 반응·요청만 한 줄로 요약해 JSON 으로 답한다. 영업사원이 할 일과 날짜는 넣지 않는다.<|im_end|>
<|im_start|>user
원장님이 견적이 좀 높다고 하셨어요 목요일까지 수정 견적서 보내기로 했어요<|im_end|>
<|im_start|>assistant
{"summary":"원장님: 견적 금액이 높다는 의견"}<|im_end|>
<|im_start|>user
약사님이 신제품 샘플 써 보시고 반응이 좋대요 다음 주에 연락 주신대요<|im_end|>
<|im_start|>assistant
{"summary":"약사님: 신제품 샘플 반응이 좋음"}<|im_end|>
<|im_start|>user
$note<|im_end|>
<|im_start|>assistant
"""
    }

    /**
     * 모델은 고객 반응 '요약'만 맡긴다 — 0.5B 모델은 할 일·날짜를 자주 틀려서(11/04 평가셋 실측),
     * 할 일·후속 일정은 검증된 규칙 정리기 값을 쓴다(할 일 30/40·날짜 37/40). 요약이 이상하면 null → 다음 단계.
     */
    suspend fun parse(raw: String, original: String, now: Long, client: String? = null): MeetingSummary? {
        val json = runCatching { JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1)) }.getOrNull()
        val summary = ByteLevel.fix(json?.optString("summary") ?: Regex("\"summary\"\\s*:\\s*\"([^\"]+)").find(raw)?.groupValues?.get(1) ?: "")
            .replace(Regex("\\s+"), " ").trim().trimEnd('.', ',')
        if (!LocalLlmGuard.plausible(summary, original)) return null
        val r = rules.structure(original, client, now)
        return MeetingSummary(tidy(summary), r.tasks, r.followUpAt, SOURCE)
    }

    /** 구어체 꼬리를 명사형으로 ("좋다고 하셨어요" → "좋다고 함") — 규칙 정리기와 같은 말투 */
    private fun tidy(s: String) = s.replace(Regex("(하셨어요|하셨대요|했어요|했대요|하세요)$"), "함").replace(Regex("(이에요|예요|이래요|래요)$"), "").take(48)

    companion object {
        const val MODEL = "roadoffice_llm.task"
        const val SOURCE = "local-llm"
        /** 프롬프트+출력 합계 (모델 KV 캐시 1280 안) */
        const val MAX_TOKENS = 1024
        const val INPUT_BUDGET = 800
    }
}

/**
 * 바이트 단위 BPE 토큰이 글자로 안 풀리고 나오는 경우("Ġì\u013B..") 를 UTF-8 로 되돌린다 (GPT-2 bytes_to_unicode 역변환).
 * 정상 한글 음절은 그대로 둔다.
 */
object ByteLevel {
    private val rev: Map<Char, Int> by lazy {
        val bs = (33..126) + (161..172) + (174..255)
        val m = HashMap<Char, Int>(); bs.forEach { m[it.toChar()] = it }
        var n = 0; for (b in 0..255) if (b !in bs) { m[(256 + n).toChar()] = b; n++ }
        m
    }
    fun fix(s: String): String {
        if (s.none { it.code in 0x100..0x143 }) return s // 깨진 토큰이 없으면 그대로
        val out = StringBuilder(); val buf = java.io.ByteArrayOutputStream()
        fun flush() { if (buf.size() > 0) { out.append(String(buf.toByteArray(), Charsets.UTF_8)); buf.reset() } }
        for (c in s) { val b = rev[c]; if (b != null && (c.code >= 0x80 || buf.size() > 0)) buf.write(b) else { flush(); out.append(c) } }
        flush(); return out.toString()
    }
}

object LocalLlmGuard {
    /**
     * 쓸 만한 요약인가 — 11/04 평가셋 37개에서 보인 실패 유형을 거른다:
     * 군더더기 시작("음 ") · 할 일·날짜가 섞임("까지", "보낼 예정") · 콜론 뒤가 너무 짧음 · 원문에 없는 낱말이 많음(지어냄)
     */
    fun plausible(s: String, original: String): Boolean {
        if (s.length !in 8..60 || s.none { it in '가'..'힣' } || s.contains('\uFFFD')) return false
        if (Regex("^(음|어|그|저)\\s").containsMatchIn(s)) return false
        if (Regex("까지|예정|하기로|드리기로|보내기|해야|주시기|바랍니다|어요$|해요$|아요$").containsMatchIn(s)) return false
        if (s.substringAfter(':', s).trim().length < 6) return false
        val src = original.replace(" ", "")
        val words = Regex("[가-힣A-Za-z0-9]{2,}").findAll(s.substringAfter(':', s)).map { it.value }.toList()
        val grounded = words.count { w -> src.contains(w.take(2)) }
        return words.isNotEmpty() && grounded * 10 >= words.size * 7 // 낱말 70% 이상이 원문에 근거
    }

}

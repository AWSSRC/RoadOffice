package com.roadoffice.app.meeting

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.Calendar

/**
 * 상담 일지 정리기 실측 — 평가셋(resources/eval/notes_eval.json, 40개)을 앱과 같은 순서로 돌린다:
 *   음성 원문 → TermFixer(거래처 5 + 제품 5 사전) → 거래처 찾기(교정문에 들어 있는 가장 긴 거래처명) → RuleNoteAssistant.structure
 * 지표만 계산해 app/build/eval/note_eval_result.md 로 쓴다. 지표 값으로 테스트를 깨뜨리지 않는다(측정용).
 */
class NoteAssistantEvalTest {

    private data class Task(val canon: String, val keys: List<List<String>>)
    private data class Item(
        val id: String, val domain: String, val noisy: Boolean, val text: String,
        val client: String?, val names: List<String>, val tasks: List<Task>, val followUp: String?,
    )

    private fun norm(s: String) = s.lowercase().replace(Regex("[\\s\\p{Punct}·…’‘“”]"), "")

    /** 정리 결과 한 줄이 정답 할 일과 맞는가: keys 의 모든 묶음에서 하나 이상 들어 있으면 맞음 */
    private fun hit(pred: String, t: Task): Boolean {
        val p = norm(pred)
        return t.keys.all { group -> group.any { p.contains(norm(it)) } }
    }

    private fun load(): Pair<JSONObject, List<Item>> {
        val raw = javaClass.classLoader!!.getResourceAsStream("eval/notes_eval.json")!!.bufferedReader().readText()
        val root = JSONObject(raw)
        val arr = root.getJSONArray("items")
        val items = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Item(
                id = o.getString("id"), domain = o.getString("domain"), noisy = o.getBoolean("noisy"),
                text = o.getString("text"),
                client = if (o.isNull("client")) null else o.getString("client"),
                names = o.getJSONArray("names").strings(),
                tasks = o.getJSONArray("tasks").let { ts ->
                    (0 until ts.length()).map { j ->
                        val t = ts.getJSONObject(j)
                        Task(t.getString("canon"), t.getJSONArray("keys").strings().map { it.split("|") })
                    }
                },
                followUp = if (o.isNull("followUp")) null else o.getString("followUp"),
            )
        }
        return root to items
    }

    private fun JSONArray.strings() = (0 until length()).map { getString(it) }

    private fun ymd(ms: Long?): String? = ms?.let {
        val c = Calendar.getInstance().apply { timeInMillis = it }
        "%04d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    /** 맞힌 할 일이 '그대로 화면에 띄워도 되는 메모'인가 (자동 판정 근사): 기한 꼬리(" · M/d(E)까지")를 뺀 본문이
     *  30자 이하, 두 칸 공백 없음, 구어체 어미(~요/~다)로 끝나지 않음, 군더더기로 시작하지 않음 */
    private val filler = Regex("^(저는|제가|근데|그 그|그 |이건|그 전에|그래서|음|어 )")
    private fun tidy(pred: String): Boolean {
        val body = pred.substringBefore(" · ")
        return body.length <= 30 && !body.contains("  ") && !body.endsWith("요") && !body.endsWith("다") && !filler.containsMatchIn(body)
    }

    private fun pct(n: Int, d: Int) = if (d == 0) "-" else "%.1f%%".format(n * 100.0 / d)

    @Test
    fun `평가셋 40개 실측 - 할 일 후속 일정 고유명사 교정`() = runBlocking {
        val (root, items) = load()
        assertEquals(40, items.size)
        assertEquals(15, items.count { it.noisy })

        val dict = root.getJSONObject("dictionary")
        val clients = dict.getJSONArray("clients").strings()
        val terms = clients + dict.getJSONArray("products").strings()
        // 기준일 2026-11-04(수) 15:00
        val now = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 4, 15, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val assistant = RuleNoteAssistant()

        fun findClient(text: String) = clients.filter { text.replace(" ", "").contains(it.replace(" ", "")) }.maxByOrNull { it.length }

        // 집계
        var expTasks = 0; var predTasks = 0; var hitExp = 0; var hitPred = 0; var exactItems = 0; var tidyHits = 0
        var expTasksClean = 0; var hitExpClean = 0; var predTasksClean = 0; var hitPredClean = 0
        var dateOk = 0; var dateWithExp = 0; var dateWithExpOk = 0; var dateNullExp = 0; var dateNullOk = 0
        var noisyFixed = 0; var namesTotal = 0; var namesOk = 0
        var wrongFixes = 0; var wrongFixItemsClean = 0; var wrongFixItemsNoisy = 0
        var clientOk = 0; var clientOkNoisy = 0; var clientOkNoisyNoFix = 0
        val rows = StringBuilder()
        val failures = StringBuilder()

        for (it in items) {
            val fixed = TermFixer.fix(it.text, terms)
            val client = findClient(fixed.text)
            val s = assistant.structure(fixed.text, client, now)

            // 할 일: 정답 하나에 결과 한 줄씩만 짝지음
            val used = BooleanArray(s.tasks.size)
            var matched = 0
            val missed = mutableListOf<String>()
            for (t in it.tasks) {
                val k = s.tasks.indices.firstOrNull { i -> !used[i] && hit(s.tasks[i], t) }
                if (k != null) { used[k] = true; matched++; if (tidy(s.tasks[k])) tidyHits++ } else missed += t.canon
            }
            val extra = s.tasks.filterIndexed { i, _ -> !used[i] }
            expTasks += it.tasks.size; predTasks += s.tasks.size; hitExp += matched; hitPred += matched
            if (!it.noisy) { expTasksClean += it.tasks.size; predTasksClean += s.tasks.size; hitExpClean += matched; hitPredClean += matched }
            val taskExact = missed.isEmpty() && extra.isEmpty()
            if (taskExact) exactItems++

            // 후속 일정
            val got = ymd(s.followUpAt)
            val dOk = got == it.followUp
            if (dOk) dateOk++
            if (it.followUp != null) { dateWithExp++; if (dOk) dateWithExpOk++ } else { dateNullExp++; if (dOk) dateNullOk++ }

            // 고유명사 교정: 정답 이름이 아닌 쪽으로 바꾼 것 = 잘못 고침
            val wrong = fixed.fixes.filter { f -> f.to !in it.names }
            wrongFixes += wrong.size
            if (wrong.isNotEmpty()) { if (it.noisy) wrongFixItemsNoisy++ else wrongFixItemsClean++ }
            val compact = fixed.text.replace(" ", "")
            val namesHere = it.names.count { n -> compact.contains(n) }
            var fixOk = true
            if (it.noisy) {
                namesTotal += it.names.size; namesOk += namesHere
                fixOk = namesHere == it.names.size && wrong.isEmpty()
                if (fixOk) noisyFixed++
            }

            // 거래처 찾기 (교정 후 / 교정 없이)
            val cOk = client == it.client
            if (cOk) clientOk++
            if (it.noisy) {
                if (cOk) clientOkNoisy++
                if (findClient(it.text) == it.client) clientOkNoisyNoFix++
            }

            val mark = { b: Boolean -> if (b) "O" else "X" }
            rows.append("| ${it.id} | ${it.domain} | ${s.tasks.joinToString(" / ").ifEmpty { "(없음)" }} | ${mark(taskExact)} | ${got ?: "-"} / ${it.followUp ?: "-"} | ${mark(dOk)} | ")
            rows.append(if (fixed.fixes.isEmpty()) "-" else fixed.fixes.joinToString(", ") { f -> "${f.from}→${f.to}" })
            rows.append(" | ${if (it.noisy) mark(fixOk) else if (wrong.isEmpty()) "O" else "X(오교정)"} | ${client ?: "-"} |\n")

            if (!taskExact || !dOk || !fixOk || wrong.isNotEmpty()) {
                failures.append("- **${it.id}** `${it.text}`\n")
                if (fixed.fixes.isNotEmpty()) failures.append("  - 교정: ${fixed.fixes.joinToString(", ") { f -> "${f.from}→${f.to}" }}\n")
                if (missed.isNotEmpty()) failures.append("  - 놓친 할 일: ${missed.joinToString(" / ")}\n")
                if (extra.isNotEmpty()) failures.append("  - 정답에 없는 할 일: ${extra.joinToString(" / ")}\n")
                if (!dOk) failures.append("  - 후속 일정: 결과 ${got ?: "없음"} / 정답 ${it.followUp ?: "없음"}\n")
                if (it.noisy && namesHere < it.names.size) failures.append("  - 못 고친 이름: ${it.names.filter { n -> !compact.contains(n) }.joinToString()}\n")
                if (wrong.isNotEmpty()) failures.append("  - 잘못 고침: ${wrong.joinToString { f -> "${f.from}→${f.to}" }}\n")
            }
        }

        val precision = pct(hitPred, predTasks); val recall = pct(hitExp, expTasks)
        val report = buildString {
            append("# 상담 일지 정리기 실측 결과 (자동 생성)\n\n")
            append("평가셋 ${items.size}개(정상 ${items.count { !it.noisy }} · 오인식 ${items.count { it.noisy }}), 기준일 2026-11-04(수) 15:00, 규칙 정리기(RuleNoteAssistant) + TermFixer\n\n")
            append("| 지표 | 값 |\n|---|---|\n")
            append("| 할 일 — 항목별 정확 일치(놓친 것·남는 것 없음) | $exactItems/${items.size} (${pct(exactItems, items.size)}) |\n")
            append("| 할 일 — 재현율(정답 할 일 중 뽑은 것) | $hitExp/$expTasks ($recall) |\n")
            append("| 할 일 — 정밀도(뽑은 할 일 중 맞는 것) | $hitPred/$predTasks ($precision) |\n")
            append("| 할 일 — 맞힌 것 중 다듬어진 메모(30자 이하·군더더기·구어체 어미 없음) | $tidyHits/$hitPred (${pct(tidyHits, hitPred)}) |\n")
            append("| 할 일 — 정상 25개만 재현율 / 정밀도 | $hitExpClean/$expTasksClean (${pct(hitExpClean, expTasksClean)}) / $hitPredClean/$predTasksClean (${pct(hitPredClean, predTasksClean)}) |\n")
            append("| 후속 일정 — 날짜 정확 일치(없음=없음 포함) | $dateOk/${items.size} (${pct(dateOk, items.size)}) |\n")
            append("| 후속 일정 — 정답 날짜가 있는 항목 | $dateWithExpOk/$dateWithExp (${pct(dateWithExpOk, dateWithExp)}) |\n")
            append("| 후속 일정 — 정답이 '없음'인 항목 | $dateNullOk/$dateNullExp (${pct(dateNullOk, dateNullExp)}) |\n")
            append("| 고유명사 — 오인식 문장을 완전히 고친 수 | $noisyFixed/15 |\n")
            append("| 고유명사 — 이름 단위로 고친 수 | $namesOk/$namesTotal |\n")
            append("| 고유명사 — 잘못 고친 곳(전체 40개 문장) | ${wrongFixes}곳 (정상 문장 ${wrongFixItemsClean}개, 오인식 문장 ${wrongFixItemsNoisy}개) |\n")
            append("| 거래처 찾기 — 전체 | $clientOk/${items.size} |\n")
            append("| 거래처 찾기 — 오인식 15개: 교정 없이 → 교정 후 | $clientOkNoisyNoFix/15 → $clientOkNoisy/15 |\n\n")
            append("## 항목별\n\n| ID | 직군 | 정리된 할 일 | 할 일 | 날짜(결과/정답) | 날짜 | 교정 | 교정 판정 | 거래처 |\n|---|---|---|---|---|---|---|---|---|\n")
            append(rows)
            append("\n## 틀린 항목\n\n").append(failures)
        }
        val out = File("build/eval/note_eval_result.md")
        out.parentFile.mkdirs()
        out.writeText(report)
        println(report)
    }
}

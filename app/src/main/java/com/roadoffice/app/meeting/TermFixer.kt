package com.roadoffice.app.meeting

/**
 * 고유명사 사전 교정 — 음성 인식이 거래처·제품 이름을 잘못 받아 적은 것을 사전에 있는 이름으로 고친다.
 * ("한빗 병원" → "한빛병원", "새솔 내과 의원" → "새솔내과의원")
 *
 * 한글을 자모로 풀어 편집 거리(Levenshtein)로 비교한다. 띄어쓰기는 무시하고, 허용 거리는
 * 자모 8개당 1 (최소 1) — 짧은 말을 엉뚱하게 바꾸지 않도록 2글자 미만 용어는 쓰지 않는다.
 */
object TermFixer {
    data class Fix(val from: String, val to: String)
    data class Result(val text: String, val fixes: List<Fix>)

    fun fix(text: String, terms: Collection<String>): Result {
        val dict = terms.map { it.trim() }.filter { it.replace(" ", "").length >= 2 }.distinct()
        if (dict.isEmpty() || text.isBlank()) return Result(text, emptyList())
        // 띄어쓰기를 뺀 글자와 원문 위치
        val chars = text.indices.filter { !text[it].isWhitespace() }
        val out = StringBuilder()
        val fixes = mutableListOf<Fix>()
        var copied = 0   // 원문에서 out 으로 옮긴 위치
        var i = 0        // chars 인덱스
        while (i < chars.size) {
            var best: Triple<Int, String, Int>? = null // (끝 chars 인덱스, 용어, 거리)
            for (term in dict) {
                val t = term.replace(" ", "")
                val tj = jamo(t)
                val allowed = maxOf(1, tj.length / 8)
                for (w in (t.length - 1)..(t.length + 1)) {
                    if (w < 2 || i + w > chars.size) continue
                    val cand = chars.subList(i, i + w).map { text[it] }.joinToString("")
                    val d = if (cand == t) 0 else lev(jamo(cand), tj)
                    if (d <= allowed && (best == null || d < best.third || (d == best.third && w > best.first - i + 1))) {
                        best = Triple(i + w - 1, term, d)
                    }
                }
            }
            if (best != null) {
                val start = chars[i]
                val end = chars[best.first]
                val original = text.substring(start, end + 1)
                out.append(text, copied, start)
                out.append(best.second)
                if (original != best.second) fixes += Fix(original, best.second)
                copied = end + 1
                i = best.first + 1
            } else {
                i++
            }
        }
        out.append(text, copied, text.length)
        return Result(out.toString(), fixes)
    }

    private val cho = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
    private val jung = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"
    private val jong = " ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ"

    /** 한글 음절을 초·중·종성으로 푼다 (그 밖의 글자는 그대로). */
    fun jamo(s: String): String = buildString {
        for (c in s) {
            if (c in '가'..'힣') {
                val k = c - '가'
                append(cho[k / 588]); append(jung[(k % 588) / 28])
                if (k % 28 != 0) append(jong[k % 28])
            } else append(c.lowercaseChar())
        }
    }

    private fun lev(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..b.length) {
                val tmp = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return d[b.length]
    }
}

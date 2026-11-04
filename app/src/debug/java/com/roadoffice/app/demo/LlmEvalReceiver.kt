package com.roadoffice.app.demo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.roadoffice.app.graph
import com.roadoffice.app.meeting.LocalLlmNoteAssistant
import com.roadoffice.app.meeting.MeetingSummary
import com.roadoffice.app.meeting.NoteAssistant
import com.roadoffice.app.meeting.RuleNoteAssistant
import com.roadoffice.app.meeting.TermFixer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 디버그 전용 — 평가셋(adb push → /data/local/tmp/llm/notes_eval.json)을 차 안 모델로 돌려 예측을 files/eval_llm.jsonl 로 쓴다.
 * 앱과 같은 순서: TermFixer → 거래처 찾기 → 정리기. 채점은 PC 에서(scripts/score_llm_eval.py).
 */
class LlmEvalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val g = context.graph
        thread {
            val root = JSONObject(File("/data/local/tmp/llm/notes_eval.json").readText())
            val base = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.KOREA).parse(root.getString("baseDate"))!!.time
            val dict = root.getJSONObject("dictionary")
            val clients = dict.getJSONArray("clients").let { a -> (0 until a.length()).map { a.getString(it) } }
            val terms = clients + dict.getJSONArray("products").let { a -> (0 until a.length()).map { a.getString(it) } }
            val rules = RuleNoteAssistant()
            val strict: NoteAssistant = object : NoteAssistant { // 모델만의 실력을 보려고 실패 시 빈 결과
                override suspend fun structure(text: String, clientName: String?, now: Long) = MeetingSummary("", emptyList(), null, "fail")
            }
            val llm = LocalLlmNoteAssistant(context, next = strict, rules = rules, log = { Log.i(TAG, it) })
            val out = File(context.filesDir, "eval_llm.jsonl").apply { writeText("") }
            val items = root.getJSONArray("items")
            for (i in 0 until items.length()) {
                val o = items.getJSONObject(i)
                val fixed = TermFixer.fix(o.getString("text"), terms).text
                val client = clients.filter { fixed.replace(" ", "").contains(it.replace(" ", "")) }.maxByOrNull { it.length }
                val t = System.currentTimeMillis()
                val s = runBlocking { llm.structure(fixed, client, base) }
                val ms = System.currentTimeMillis() - t
                out.appendText(JSONObject().put("id", o.getString("id")).put("summary", s.summary).put("tasks", JSONArray(s.tasks))
                    .put("followUp", s.followUpAt?.let { SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(it) } ?: JSONObject.NULL)
                    .put("source", s.source).put("ms", ms).toString() + "\n")
                Log.i(TAG, "${o.getString("id")} ${ms}ms ${s.source} ${s.summary} | ${s.tasks}")
            }
            Log.i(TAG, "DONE")
        }
    }
    companion object { const val TAG = "LlmEval" }
}

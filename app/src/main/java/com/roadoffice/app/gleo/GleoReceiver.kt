package com.roadoffice.app.gleo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.roadoffice.app.graph
import com.roadoffice.app.workmile.Purpose
import org.json.JSONObject

/**
 * Gleo Interface 수신부. assets/schema.json 에 등록한 function 을 Gleo AI 가 broadcast 로 호출한다.
 * 결과는 APP_ACTION_RESPONSE broadcast 로 돌려준다 (Gleo 는 7초 뒤 타임아웃).
 *
 * 보안: 이 receiver 만 export 되며, 받은 값은 데이터로만 쓰고(코드·쿼리로 해석하지 않음) 길이를 제한한다.
 */
class GleoReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val params = runCatching { JSONObject(unquote(intent.getStringExtra("ACTION_PARAM") ?: "{}")) }.getOrElse { JSONObject() }
        val graph = context.graph
        // 발화 내용은 로그에 남기지 않는다 — 함수 이름과 인자 키만
        graph.signals.log("Gleo 호출: $action ${params.keys().asSequence().toList()}")
        val pending = goAsync()
        graph.launch {
            try {
                when (action) {
                    MARK_TRIP_PURPOSE -> {
                        val purpose = when (params.optString("purpose")) {
                            "business" -> Purpose.BUSINESS
                            "personal" -> Purpose.PERSONAL
                            else -> null
                        }
                        if (purpose == null) {
                            respond(context, action, "asking", "업무 운행인지 개인 운행인지 말씀해 주세요.")
                        } else {
                            val t = graph.setLatestPurpose(purpose)
                            if (t == null) respond(context, action, "fail", "아직 기록된 운행이 없어요.")
                            else respond(context, action, "success", "방금 운행 %.1f킬로미터를 %s 운행으로 바꿨어요.".format(t.distanceKm, if (purpose == Purpose.BUSINESS) "업무" else "개인"))
                        }
                    }
                    ADD_MEETING_NOTE -> {
                        val note = params.optString("note").take(MAX_TEXT)
                        if (note.isBlank()) respond(context, action, "asking", "상담 일지에 어떤 내용을 남길까요?")
                        else {
                            // Gleo 는 7초 뒤 타임아웃 — 원문 접수를 먼저 응답하고 정리(AI)는 뒤에서 이어간다
                            graph.addNote(note, params.optString("clientName").take(80).ifBlank { null }, fromVoice = true)
                            respond(context, action, "success", "상담 메모를 받았어요. 할 일로 정리할게요.", mode = "beep")
                        }
                    }
                    ASK_ROADOFFICE -> {
                        val q = params.optString("question").take(300)
                        if (q.isBlank()) respond(context, action, "asking", "무엇이 궁금하세요? 예를 들어 이번 달 한빛병원 몇 번 갔는지 물어보세요.")
                        else {
                            // 7초 제한 — 접수만 먼저 알리고, 기록을 찾아 차 안 AI 가 정리한 답은 앱이 직접 읽는다
                            respond(context, action, "success", "기록을 찾아서 말씀드릴게요.", mode = "beep")
                            graph.launch { graph.ask(q) }
                        }
                    }
                    else -> respond(context, action, "fail", "지원하지 않는 요청이에요.")
                }
            } catch (e: Exception) {
                respond(context, action, "fail", "처리 중 문제가 생겼어요. 잠시 뒤 다시 말씀해 주세요.")
                graph.signals.log("Gleo 처리 오류: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }

    private fun respond(context: Context, fn: String, status: String, message: String, data: String? = null, mode: String = "voice") {
        val json = JSONObject().put("functionName", fn).put("status", status).put("message", message).put("mode", mode)
        data?.let { json.put("data", it) }
        context.sendBroadcast(
            Intent(RESPONSE_ACTION)
                .putExtra("packageName", context.packageName)
                .putExtra("response", json.toString()),
        )
        context.graph.signals.log("Gleo 응답: $status · $message")
    }

    /** Gleo 예시는 ACTION_PARAM 을 따옴표로 한 번 더 감싸 보낸다 — 벗겨서 파싱. */
    private fun unquote(s: String): String {
        val t = s.trim()
        return if (t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")) t.substring(1, t.length - 1).replace("\\\"", "\"") else t
    }

    companion object {
        const val MARK_TRIP_PURPOSE = "MARK_TRIP_PURPOSE"
        const val ADD_MEETING_NOTE = "ADD_MEETING_NOTE"
        const val ASK_ROADOFFICE = "ASK_ROADOFFICE"
        const val RESPONSE_ACTION = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"
        private const val MAX_TEXT = 2_000
    }
}

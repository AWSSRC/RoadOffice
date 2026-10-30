package com.roadoffice.app.gleo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.roadoffice.app.graph
import com.roadoffice.app.workmile.Purpose
import org.json.JSONArray
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
                            respond(context, action, "asking", "Please tell whether the trip was business or personal.")
                        } else {
                            val t = graph.setLatestPurpose(purpose)
                            if (t == null) respond(context, action, "fail", "There is no recorded trip yet.")
                            else respond(context, action, "success", "The most recent trip of %.1f km was marked as %s.".format(t.distanceKm, purpose.name.lowercase()))
                        }
                    }
                    ADD_MEETING_NOTE -> {
                        val note = params.optString("note").take(MAX_TEXT)
                        if (note.isBlank()) respond(context, action, "asking", "What should be saved in the meeting note?")
                        else {
                            // Gleo 는 7초 뒤 타임아웃 — 원문 접수를 먼저 응답하고 정리(AI)는 뒤에서 이어간다
                            graph.addNote(note, params.optString("clientName").take(80).ifBlank { null }, fromVoice = true)
                            respond(context, action, "success", "The meeting note was received and is being organized into tasks.", mode = "beep")
                        }
                    }
                    GET_CLIENT_BRIEFING -> {
                        val name = params.optString("clientName").take(80)
                        // 동승자 보호: 고객이 타 있을 수 있어 거래처 정보를 소리 내 읽지 않는다
                        if (graph.guestOnBoard) {
                            respond(context, action, "fail", "A passenger is on board, so the client briefing was held to protect client information. Ask again when the driver is alone.")
                            return@launch
                        }
                        val text = if (name.isBlank()) null else graph.briefByName(name)
                        if (text == null) respond(context, action, "fail", "No registered client matches that name.")
                        else respond(
                            context, action, "success", "The client briefing was read to the user.",
                            data = JSONArray().put(JSONObject().put("clientName", name).put("briefing", text)).toString(),
                            mode = "silent", // 앱이 TTS 로 직접 읽었으므로 Gleo 는 소리 내지 않음
                        )
                    }
                    else -> respond(context, action, "fail", "Unsupported action.")
                }
            } catch (e: Exception) {
                respond(context, action, "fail", "An unexpected internal error occurred.")
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
        const val GET_CLIENT_BRIEFING = "GET_CLIENT_BRIEFING"
        const val RESPONSE_ACTION = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"
        private const val MAX_TEXT = 2_000
    }
}

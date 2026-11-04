package com.roadoffice.app.demo

import ai.pleos.playground.llm.LLM
import ai.pleos.playground.llm.constant.Mode
import ai.pleos.playground.llm.listener.ResultListener
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

/** 디버그 전용 — Pleos LLM SDK 모드·호출 방식별 응답 여부 확인. am broadcast -a com.roadoffice.app.LLM_PROBE --es mode server|ondevice --es call stream|content */
class LlmProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val mode = if (intent.getStringExtra("mode") == "server") Mode.SERVER else Mode.ON_DEVICE
        val stream = intent.getStringExtra("call") != "content"
        val prompt = intent.getStringExtra("prompt") ?: "한 문장으로 답해: 대한민국의 수도는?"
        val llm = LLM(context.applicationContext, mode)
        runCatching { llm.initialize() }.onFailure { Log.i(TAG, "init fail ${it.message}") }
        val l = object : ResultListener {
            override fun onResponse(response: String, isFinal: Boolean) { Log.i(TAG, "[$mode/${if (stream) "stream" else "content"}] resp final=$isFinal: $response") }
            override fun onError(error: String) { Log.i(TAG, "[$mode] error: $error") }
        }
        Handler(Looper.getMainLooper()).postDelayed({
            Log.i(TAG, "call $mode stream=$stream")
            runCatching { if (stream) llm.generateContentStream(prompt, l) else llm.generateContent(prompt, l) }.onFailure { Log.i(TAG, "call fail ${it.javaClass.simpleName} ${it.message}") }
        }, 3000)
    }
    companion object { const val TAG = "LlmProbe" }
}

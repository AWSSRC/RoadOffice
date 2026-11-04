package com.roadoffice.app.demo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.roadoffice.app.graph
import kotlin.concurrent.thread

/** 디버그 전용 — 차 안 모델 로드·생성 시간 측정. am broadcast -a com.roadoffice.app.LOCAL_LLM_PROBE [--es prompt "..."] [--ei tokens 256] */
class LocalLlmProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val g = context.graph
        val note = intent.getStringExtra("note") ?: "한빛병원 원장님이 신제품 샘플 반응이 좋다고 하셨어요 다음 주 화요일에 다시 연락 주신대요 그리고 금요일까지 단가표 보내 드리기로 했어요"
        val tokens = intent.getIntExtra("tokens", 512)
        thread {
            val f = g.localLlm.modelFile() ?: run { Log.i(TAG, "no model"); return@thread }
            var t = System.currentTimeMillis()
            val e = LlmInference.createFromOptions(context, LlmInference.LlmInferenceOptions.builder().setModelPath(f.absolutePath).setMaxTokens(tokens).build())
            Log.i(TAG, "load ${System.currentTimeMillis() - t} ms")
            val p = g.localLlm.prompt(note, "한빛병원", System.currentTimeMillis())
            Log.i(TAG, "prompt tokens ~ ${e.sizeInTokens(p)}")
            t = System.currentTimeMillis()
            val out = e.generateResponse(p)
            Log.i(TAG, "gen ${System.currentTimeMillis() - t} ms: $out")
            e.close()
        }
    }
    companion object { const val TAG = "LocalLlmProbe" }
}

package com.roadoffice.app.meeting

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.tts.TextToSpeech
import android.content.Context
import java.util.Locale
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInit
import ai.pleos.playground.stt.listener.ResultListener as SttResult
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.EventListener as TtsEvent
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInit

/**
 * Pleos STT/TTS 래퍼 — 기기 내장(ON_DEVICE) 모드로 앱 등록·서버 없이 동작.
 * 주행 중 손 대신 음성으로: 브리핑은 TTS, 회고는 STT.
 */
class Voice(context: Context, private val log: (String) -> Unit) {
    private val tts = TextToSpeech(context, TtsMode.ON_DEVICE)
    private val stt = SpeechToText(context, SttMode.ON_DEVICE)
    @Volatile var ttsReady = false; private set
    @Volatile var sttReady = false; private set
    private var onDone: (() -> Unit)? = null
    private var onText: ((String) -> Unit)? = null
    private var onFail: ((String) -> Unit)? = null

    fun start() {
        tts.initialize(object : TtsInit {
            override fun onSuccess() {
                ttsReady = true
                tts.setLocale(Locale.KOREA)
                tts.addEventListener(object : TtsEvent {
                    override fun onStart() = Unit
                    override fun onReady() = Unit
                    override fun onDone() { onDone?.invoke(); onDone = null }
                    override fun onError(errorType: TtsError) = log("TTS 오류: $errorType")
                    override fun onStop() = Unit
                    override fun onUpdatedRms(rms: Double) = Unit
                })
            }

            override fun onFailure(errorType: TtsError) = log("TTS 초기화 실패: $errorType")
        })
        stt.initialize(object : SttInit {
            override fun onSuccess() {
                sttReady = true
                stt.setLocale(Locale.KOREA)
                stt.addListener(object : SttResult {
                    override fun onUpdated(text: String, isFinal: Boolean) {
                        if (isFinal) { onText?.invoke(text); onText = null }
                    }
                    override fun onUpdatedEpdData(start: Long, end: Long) = Unit
                    override fun onStartedRecognition() = log("음성 인식 시작")
                    override fun onEndedRecognition() = Unit
                    override fun onError(errorType: SttError) { log("음성 인식 오류: $errorType"); onFail?.invoke(errorType.name); onFail = null }
                    override fun onReady() = Unit
                    override fun onUpdatedRms(rms: Double) = Unit
                })
            }

            override fun onFailure(errorType: SttError) = log("STT 초기화 실패: $errorType")
        })
    }

    fun speak(text: String, then: (() -> Unit)? = null) {
        log("🔊 $text")
        if (!ttsReady) { then?.invoke(); return }
        onDone = then
        runCatching { tts.speak(text, true) }.onFailure { log("TTS 실패: ${it.message}"); then?.invoke() }
    }

    /** 읽던 말 멈추기 (동승자가 탔을 때 업무 내용이 이어서 나오지 않게). */
    fun stop() {
        onDone = null
        runCatching { tts.stop() }
    }

    /** 한 번 듣기 — 최종 인식 결과 또는 실패를 콜백. */
    fun listen(onResult: (String) -> Unit, onError: (String) -> Unit) {
        if (!sttReady) { onError("STT 준비 안 됨"); return }
        onText = onResult; onFail = onError
        runCatching { stt.request() }.onFailure { onError(it.message ?: "request 실패") }
    }

    fun release() {
        runCatching { tts.release(); stt.release() }
    }
}

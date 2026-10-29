package com.example.highwaymode.alerts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.highwaymode.core.camera.CameraAlertPlanner
import com.example.highwaymode.core.engine.EngineStatus
import com.example.highwaymode.core.engine.HighwaySnapshot
import com.example.highwaymode.core.vehicle.TollCardState
import com.example.highwaymode.data.Prefs
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 音声案内（オービス接近・渋滞・ETC カード未挿入・給油）。
 * ナビ音声として再生するので、Android Auto 接続中は車のスピーカーから音楽を下げて流れる。
 * メインスレッドで使う。
 */
class AlertManager(context: Context) : TextToSpeech.OnInitListener {
    private val ctx = context.applicationContext
    private val prefs = Prefs(ctx)
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var tts: TextToSpeech? = TextToSpeech(ctx, this)
    private var ttsReady = false
    private val planner = CameraAlertPlanner()
    private var tone: ToneGenerator? = null
    private var etcWarned = false
    private var odometer = 0.0
    private var lastSnapMs = 0L
    private var announcedJamEnd = -1e12
    private var lastFuelText: String? = null
    private var lastFuelMs = 0L
    private var focus: AudioFocusRequest? = null

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    override fun onInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TTS init failed: $status")
            return
        }
        val r = t.setLanguage(Locale.JAPAN)
        ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        t.setAudioAttributes(attrs)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = abandonFocus()
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(utteranceId: String?) = abandonFocus()
            override fun onError(utteranceId: String?, errorCode: Int) = abandonFocus()
        })
    }

    fun onSnapshot(s: HighwaySnapshot) {
        val now = SystemClock.elapsedRealtime()
        if (lastSnapMs != 0L) odometer += (s.speedKmh ?: 0.0) / 3.6 * ((now - lastSnapMs) / 1000.0).coerceIn(0.0, 5.0)
        lastSnapMs = now
        if (s.status != EngineStatus.ON_HIGHWAY) {
            if (s.status != EngineStatus.REST_STOP) planner.reset()
            return
        }
        // ---- オービス
        if (prefs.cameraVoice) {
            val a = planner.update(s.cameras, s.speedKmh)
            if (a != null) {
                beep(if (a.stage == 0) 1 else 2)
                speak(a.text, "cam")
                return
            }
        }
        if (!prefs.trafficVoice) return
        // ---- ETC カード（車につながっていて、未挿入・無効のとき 1 回）
        val v = s.vehicle
        if (v != null && !etcWarned && (v.tollCard == TollCardState.NOT_INSERTED || v.tollCard == TollCardState.INVALID)) {
            etcWarned = true
            speak(if (v.tollCard == TollCardState.NOT_INSERTED) "ETCカードが挿入されていません" else "ETCカードを確認してください", "etc")
            return
        }
        if (v != null && v.tollCard == TollCardState.VALID) etcWarned = false
        // ---- 渋滞（前方 10km 以内に新しく現れた渋滞を 1 回）
        val tr = s.traffic
        val jd = tr?.nextJamDistanceM
        val jl = tr?.nextJamLengthM
        if (jd != null && jl != null && jd < 10_000 && jl >= 500) {
            // 走行距離（オドメーター）上の位置で、案内済みの渋滞より先にある渋滞だけを案内する
            if (odometer + jd > announcedJamEnd + 500) {
                announcedJamEnd = odometer + jd + jl
                val delay = tr.nextJamDelaySec?.let { if (it >= 60) "、およそ${(it / 60).roundToInt()}分の遅れです" else "" } ?: ""
                speak("この先${km(jd)}、渋滞${km(jl)}$delay", "jam")
                return
            }
        }
        // ---- 給油
        val fa = v?.fuelAdvice
        if (fa != null && v.fuelAdviceLevel >= 1) {
            val now = SystemClock.elapsedRealtime()
            if (fa != lastFuelText && now - lastFuelMs > 20 * 60_000L) {
                lastFuelText = fa
                lastFuelMs = now
                speak(fa.replace("km", "キロ"), "fuel")
            }
        }
    }

    private fun km(m: Double): String = if (m < 1000) "${(m / 100).roundToInt() * 100}メートル" else if (m < 10_000) String.format(Locale.JAPAN, "%.1fキロ", m / 1000).replace(".0キロ", "キロ") else "${(m / 1000).roundToInt()}キロ"

    private fun speak(text: String, id: String) {
        val t = tts ?: return
        if (!ttsReady) return
        requestFocus()
        val params = Bundle()
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        t.speak(text, TextToSpeech.QUEUE_ADD, params, id + SystemClock.elapsedRealtime())
    }

    private fun beep(times: Int) {
        try {
            val tg = tone ?: ToneGenerator(AudioManager.STREAM_MUSIC, 80).also { tone = it }
            tg.startTone(if (times >= 2) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP, 300)
        } catch (e: RuntimeException) {
            // 音声出力が使えない
        }
    }

    private fun requestFocus() {
        val req = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .build().also { focus = it }
        am.requestAudioFocus(req)
    }

    private fun abandonFocus() {
        focus?.let { am.abandonAudioFocusRequest(it) }
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        tone?.release()
        tone = null
    }

    companion object {
        private const val TAG = "AlertManager"
    }
}

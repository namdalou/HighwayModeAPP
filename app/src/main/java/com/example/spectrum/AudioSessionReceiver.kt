package com.example.spectrum

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect

/**
 * 音楽アプリがイコライザーアプリ向けに送る「オーディオセッション開始/終了」の通知を受け取る。
 * Spotify などは再生開始時にこれを送るので、そのアプリの音だけを直接解析できる。
 */
class AudioSessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SpectrumEngine.init(context)
        val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
        val pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: return
        when (intent.action) {
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION ->
                SpectrumEngine.onAudioSessionOpened(pkg, session)
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION ->
                SpectrumEngine.onAudioSessionClosed(pkg, session)
        }
    }
}

package com.example.highwaymode.car

import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.service.notification.NotificationListenerService
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import com.example.spectrum.MediaListenerService
import com.example.spectrum.MediaTracker
import com.example.spectrum.NowPlaying
import com.example.spectrum.SpectrumEngine

/**
 * Android Auto（車載ディスプレイ）用の入口。ナビアプリとして登録し、地図面（Surface）に
 * Spectrum（音楽）とハイウェイモードを描く（音楽アプリは画面に自由に描画できないため、描画が許されているナビの枠を使う）。
 * Android Auto 17.7 以降は Google Play（内部テスト配布を含む）からインストールしたものだけが一覧に出る。
 */
class HighwayCarAppService : CarAppService() {

    override fun onCreate() {
        super.onCreate()
        // Spectrum: 通知リスナーの接続を待たずに、再生中の曲をすぐ読み込む
        NowPlaying.init(this)
        SpectrumEngine.init(this)
        MediaTracker.start(this)
        if (!NowPlaying.listenerConnected) {
            try {
                NotificationListenerService.requestRebind(ComponentName(this, MediaListenerService::class.java))
            } catch (e: Exception) {
                // 通知へのアクセスが未許可
            }
        }
    }

    override fun createHostValidator(): HostValidator {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return if (debuggable) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }
    }

    override fun onCreateSession(): Session = HighwaySession()
}

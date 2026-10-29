package com.example.highwaymode.car

import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Android Auto（車載ディスプレイ）用の入口。ナビアプリとして登録し、地図面（Surface）にハイウェイモードを描く。
 * 開発中・個人利用（提供元不明のアプリ）では Android Auto の「デベロッパー設定 → 提供元不明のアプリ」を有効にする。
 */
class HighwayCarAppService : CarAppService() {

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

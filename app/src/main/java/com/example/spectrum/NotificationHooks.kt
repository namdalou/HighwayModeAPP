package com.example.spectrum

import android.content.Context
import android.service.notification.StatusBarNotification

/**
 * 通知へのアクセス（曲情報と同じ許可）で受け取った通知を、ほかの機能へも渡す口。
 * 統合したアプリでは、Google マップのナビの通知の読み取り（ハイウェイモードの経路合わせ）が使う。
 * 未設定なら何もしない（Spectrum 単体のときと同じ）。
 */
object NotificationHooks {
    @Volatile
    var onPosted: ((Context, StatusBarNotification) -> Unit)? = null

    @Volatile
    var onRemoved: ((StatusBarNotification) -> Unit)? = null
}

package com.example.spectrum

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.media.audiofx.AudioEffect
import android.media.session.MediaSession
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * 再生中の曲情報を集める。
 * 1. メディアセッション(追跡は MediaTracker。ここでは通知に埋め込まれたセッションを渡す)
 * 2. 通知の文字・画像(ZLink など。取得元は自動検出か診断画面で指定)
 * 3. 曲変更ブロードキャスト
 * 設定 > 通知へのアクセス でこのアプリを許可する必要がある。
 */
class MediaListenerService : NotificationListenerService() {

    companion object {
        const val PREFS = "spectrum"
        const val KEY_FALLBACK_PKG = "fallback_pkg"

        @Volatile var instance: MediaListenerService? = null
            private set

        /** 一般的な音楽アプリ/車載機が送る曲変更ブロードキャスト */
        private val META_ACTIONS = listOf(
            "com.android.music.metachanged",
            "com.android.music.playstatechanged",
            "com.android.music.playbackcomplete",
            "com.spotify.music.metadatachanged",
            "com.spotify.music.playbackstatechanged",
            "fm.last.android.metachanged",
            "com.sec.android.app.music.metachanged",
            "com.htc.music.metachanged",
            "com.miui.player.metachanged",
            "com.amazon.mp3.metachanged",
            "com.andrew.apollo.metachanged",
            "com.sonyericsson.music.metachanged",
            "com.samsung.sec.android.MusicPlayer.metachanged"
        )
    }

    private var receiverRegistered = false
    /** 音楽アプリのオーディオセッション通知(スペアナを直接そのアプリの音で解析するため) */
    private val sessionReceiver = AudioSessionReceiver()

    private val metaReceiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(context: Context, intent: Intent) {
            val ex = intent.extras ?: return
            val title = ex.get("track")?.toString().orEmpty()
            if (title.isEmpty()) return
            val artist = ex.get("artist")?.toString().orEmpty()
            val album = ex.get("album")?.toString().orEmpty()
            val playing = if (ex.containsKey("playing")) ex.getBoolean("playing") else null
            Diagnostics.lastBroadcast = "${intent.action}\n$title / $artist / $album"
            if (!MediaTracker.hasCurrent) {
                NowPlaying.applyFallback("broadcast", "", title, artist, album, null, playing)
            }
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onListenerConnected() {
        instance = this
        NowPlaying.init(this)
        NowPlaying.listenerConnected = true
        registerMetaReceiver()
        MediaTracker.start(this)
        try { activeNotifications?.forEach { onNotificationPosted(it) } } catch (_: Exception) { }
        MediaTracker.pick()
    }

    override fun onListenerDisconnected() {
        instance = null
        NowPlaying.listenerConnected = false
        if (receiverRegistered) {
            try { unregisterReceiver(metaReceiver) } catch (_: Exception) { }
            try { unregisterReceiver(sessionReceiver) } catch (_: Exception) { }
            receiverRegistered = false
        }
        // メディアセッションの追跡(MediaTracker)は通知リスナーが切れても続ける
        if (!MediaTracker.hasCurrent) NowPlaying.clearAll()
    }

    private fun registerMetaReceiver() {
        if (receiverRegistered) return
        val f = IntentFilter().apply { META_ACTIONS.forEach { addAction(it) } }
        val flags = if (Build.VERSION.SDK_INT >= 33) Context.RECEIVER_EXPORTED else 0
        registerReceiver(metaReceiver, f, flags)
        val sf = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        registerReceiver(sessionReceiver, sf, flags)
        receiverRegistered = true
    }

    // ---------------------------------------------------------------- 通知

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        val ex = n.extras
        val title = ex?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = ex?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        @Suppress("DEPRECATION")
        val token = ex?.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)

        Diagnostics.notifications[sbn.packageName] = Diagnostics.Entry(
            sbn.packageName, appLabel(sbn.packageName), title, text,
            token != null, System.currentTimeMillis()
        )

        // 追跡中のアプリの通知で、再生情報に曲名が無い場合は通知の文字で補う
        if (sbn.packageName == MediaTracker.currentPackage && NowPlaying.title.isEmpty()) {
            applyNotification(sbn)
        }
        if (token != null) {
            MediaTracker.putNotificationController(sbn.packageName, token)
            return
        }
        // 通知の文字を使うのは、音楽アプリの再生情報(セッション)が無いときだけ
        if (isFallbackSource(sbn.packageName) &&
            !MediaTracker.hasCurrent && title.isNotEmpty()
        ) {
            applyNotification(sbn)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        MediaTracker.removeNotificationController(sbn.packageName)
        if (NowPlaying.source == "notification" && NowPlaying.sourcePackage == sbn.packageName) {
            if (!MediaTracker.hasCurrent) NowPlaying.clearAll()
            refreshFallback()
        }
    }

    /**
     * 再生情報(セッション)はあるが曲名が空のアプリ用に、同じアプリの通知から曲情報を補う。
     * 別のアプリ(地図など)の通知は使わない。
     */
    fun fillFromAppNotification(pkg: String) {
        val list = try { activeNotifications } catch (_: Exception) { null } ?: return
        val sbn = list.filter { it.packageName == pkg }
            .filter { !it.notification?.extras?.getCharSequence(Notification.EXTRA_TITLE).isNullOrBlank() }
            .maxByOrNull { it.postTime } ?: return
        applyNotification(sbn)
    }

    /** 取得元に一致する最新の通知から曲情報を読み直す(診断画面からも呼ばれる) */
    fun refreshFallback(evenWithSession: Boolean = false) {
        if (MediaTracker.hasCurrent && !evenWithSession) return
        val list = try { activeNotifications } catch (_: Exception) { null } ?: return
        val sbn = list.filter { isFallbackSource(it.packageName) }
            .maxByOrNull { it.postTime } ?: return
        applyNotification(sbn)
    }

    private fun isFallbackSource(pkg: String): Boolean {
        if (pkg == packageName) return false
        val chosen = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_FALLBACK_PKG, null)
        return if (chosen != null) pkg == chosen else pkg.contains("zlink", ignoreCase = true)
    }

    private fun applyNotification(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        val ex = n.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        if (title.isEmpty()) return
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val sub = ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
            ?: ex.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString()
            ?: ""
        NowPlaying.applyFallback(
            "notification", sbn.packageName, title, text, sub, largeIcon(n), null, n.contentIntent
        )
    }

    @Suppress("DEPRECATION")
    private fun largeIcon(n: Notification): Bitmap? {
        val icon = n.getLargeIcon()
            ?: return n.extras?.get(Notification.EXTRA_LARGE_ICON) as? Bitmap
        val d = try { icon.loadDrawable(this) } catch (_: Exception) { null } ?: return null
        if (d is BitmapDrawable && d.bitmap != null) return d.bitmap
        val w = d.intrinsicWidth.coerceIn(1, 512)
        val h = d.intrinsicHeight.coerceIn(1, 512)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, w, h)
        d.draw(Canvas(b))
        return b
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) { pkg }
}

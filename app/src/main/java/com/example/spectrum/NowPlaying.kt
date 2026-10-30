package com.example.spectrum

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent

/**
 * 再生中の曲情報と操作の共有ステート(スマホUI・車載UI共通)。
 * 曲情報の取得元は 3 種類:
 *  - session      : 他アプリのメディアセッション(最も正確。操作・シークも可能)
 *  - notification : 通知の文字・画像(ZLink などセッションを公開しないアプリ用)
 *  - broadcast    : 曲変更ブロードキャスト(古い音楽アプリや一部の車載機)
 */
object NowPlaying {
    @Volatile var listenerConnected = false
    @Volatile var hasSession = false
    @Volatile var source = ""
    @Volatile var sourcePackage = ""
    @Volatile var sourceLabel = ""
    @Volatile var title = ""
    @Volatile var artist = ""
    @Volatile var album = ""
    @Volatile var art: Bitmap? = null
    @Volatile var durationMs = 0L
    @Volatile var state: PlaybackState? = null
    @Volatile var controller: MediaController? = null
    /** セッションがないときの再生状態(不明なら null) */
    @Volatile var fallbackPlaying: Boolean? = null
    /** 通知から取得したときの「タップで開く」先 */
    @Volatile var contentIntent: PendingIntent? = null

    @Volatile private var appContext: Context? = null

    fun init(c: Context) {
        appContext = c.applicationContext
    }

    val hasInfo: Boolean get() = title.isNotEmpty()

    val isPlaying: Boolean
        get() {
            val s = state
            return if (s != null) s.state == PlaybackState.STATE_PLAYING else fallbackPlaying == true
        }

    fun positionMs(): Long {
        val s = state ?: return 0L
        var pos = s.position
        if (s.state == PlaybackState.STATE_PLAYING) {
            val elapsed = SystemClock.elapsedRealtime() - s.lastPositionUpdateTime
            pos += (elapsed * s.playbackSpeed).toLong()
        }
        return if (durationMs > 0) pos.coerceIn(0L, durationMs) else pos.coerceAtLeast(0L)
    }

    // ---------------------------------------------------------------- 操作
    // セッションがあればそれを操作し、なければメディアキーを送る(ZLink 等に届く場合がある)

    fun prev() {
        val c = controller
        if (c != null) c.transportControls.skipToPrevious() else mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    }

    fun next() {
        val c = controller
        if (c != null) c.transportControls.skipToNext() else mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
    }

    fun playPause() {
        val c = controller
        if (c != null) {
            if (isPlaying) c.transportControls.pause() else c.transportControls.play()
            return
        }
        // 再生中のセッションをまだつかめていない
        if (fallbackPlaying == true) {
            mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
        } else {
            // 最後に使った音楽アプリを起こして再生する
            val ctx = appContext
            if (ctx != null) MediaResumer.play(ctx) else mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
        }
    }

    fun seekToFraction(f: Float) {
        if (durationMs > 0) controller?.transportControls?.seekTo((durationMs * f).toLong())
    }

    /**
     * 再生中のアプリを開く。
     * 1. メディアセッションが指定している画面  2. 通知のタップ先  3. アプリの起動画面
     * ZLink から曲情報を取っている場合は ZLink(Android Auto の画面)が開く。
     */
    fun openSourceApp(ctx: Context): Boolean {
        val pi = controller?.sessionActivity ?: contentIntent
        if (pi != null && sendPending(ctx, pi)) return true

        val pkg = controller?.packageName ?: sourcePackage
        if (pkg.isEmpty()) return false
        val launch = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(launch)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun sendPending(ctx: Context, pi: PendingIntent): Boolean = try {
        if (Build.VERSION.SDK_INT >= 34) {
            // Android 14 以降は他アプリの PendingIntent から画面を開く許可を明示する
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 36) {
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
            } else {
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            }
            val opts = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(mode)
            pi.send(ctx, 0, null, null, null, null, opts.toBundle())
        } else {
            pi.send()
        }
        true
    } catch (_: Exception) {
        false
    }

    fun mediaKey(code: Int) {
        val am = appContext?.getSystemService(AudioManager::class.java) ?: return
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    // ---------------------------------------------------------------- 更新

    fun setSession(c: MediaController, label: String) {
        controller = c
        hasSession = true
        sourceLabel = label
        sourcePackage = c.packageName
        state = c.playbackState
    }

    fun applySessionInfo(t: String, a: String, al: String, dur: Long, bmp: Bitmap?) {
        source = "session"
        contentIntent = null
        title = t; artist = a; album = al
        durationMs = dur
        art = bmp
    }

    fun applyFallback(
        src: String, pkg: String, t: String, a: String, al: String,
        bmp: Bitmap?, playing: Boolean?, tapIntent: PendingIntent? = null
    ) {
        source = src
        contentIntent = tapIntent
        if (controller == null) sourcePackage = pkg
        title = t; artist = a; album = al
        art = bmp
        if (playing != null) fallbackPlaying = playing
        if (controller == null) durationMs = 0L
    }

    fun clearSession() {
        hasSession = false
        controller = null
        state = null
        durationMs = 0L
        if (source == "session") {
            source = ""; title = ""; artist = ""; album = ""; art = null
        }
    }

    fun clearAll() {
        clearSession()
        source = ""; sourcePackage = ""; sourceLabel = ""
        title = ""; artist = ""; album = ""; art = null
        fallbackPlaying = null
        contentIntent = null
    }
}

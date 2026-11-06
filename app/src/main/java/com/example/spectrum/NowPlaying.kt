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
import android.os.Handler
import android.os.Looper
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
        if (isPlaying) {
            playRequest++            // 確かめ中の再生をやめる（一時停止したのに再生し直さないように）
            if (c != null) {
                try { c.transportControls.pause() } catch (_: Exception) { mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE) }
            } else {
                mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
            }
            return
        }
        requestPlay()
    }

    private val main = Handler(Looper.getMainLooper())
    /** 再生の要求の番号（確かめの途中で一時停止・次の再生が押されたら、古い確かめはやめる） */
    private var playRequest = 0

    /**
     * 再生（v2.11）。一時停止してしばらくたつと、音楽アプリがセッションを作り直したり止めたりして、
     * 覚えているセッションに「再生」を送っても届かないことがある（再生ボタンが効かない）。
     * そこで送ったあとに本当に再生が始まったか確かめ、始まらなければ次の方法で送り直す:
     *  1. 覚えているセッション（無ければ、最後に使った音楽アプリを起こす MediaResumer）
     *  2. セッションを選び直して再生 ＋ メディアボタンの「再生」（Bluetooth のボタンと同じ。押し直しで止まらない）
     *  3. 音楽アプリの通知の「再生」ボタン（通知から再生できるときは必ず効く）
     *  4. 最後に使った音楽アプリに接続して再生（メディアの再開）
     * どれも「再生」だけを送り、「再生/一時停止の切り替え」は送らない（重なっても止まらない）
     */
    private fun requestPlay() {
        val token = ++playRequest
        val c = controller
        if (c != null) {
            try { c.transportControls.play() } catch (_: Exception) { }
        } else {
            val ctx = appContext
            if (ctx != null) MediaResumer.play(ctx) else mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
        }
        main.postDelayed({ checkPlay(token, 1) }, 700L)
    }

    private fun checkPlay(token: Int, step: Int) {
        if (token != playRequest || playingNow()) return
        when (step) {
            1 -> {
                MediaTracker.pick()
                controller?.let { try { it.transportControls.play() } catch (_: Exception) { } }
                mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
                main.postDelayed({ checkPlay(token, 2) }, 900L)
            }
            2 -> {
                val ctx = appContext
                val pkg = controller?.packageName ?: sourcePackage.ifEmpty { ctx?.let { MediaResumer.lastPackage(it) } ?: "" }
                MediaListenerService.instance?.pressPlayAction(pkg)
                main.postDelayed({ checkPlay(token, 3) }, 1200L)
            }
            3 -> appContext?.let { MediaResumer.play(it, resumeOnly = true) }
        }
    }

    /** いま何かが再生中か（覚えているセッション・ほかのセッション・端末の音のどれか） */
    private fun playingNow(): Boolean {
        val c = controller
        val st = try { c?.playbackState } catch (_: Exception) { null }
        if (st?.state == PlaybackState.STATE_PLAYING) {
            state = st
            return true
        }
        if (MediaTracker.anyPlaying()) return true
        val am = appContext?.getSystemService(AudioManager::class.java)
        return try { am?.isMusicActive == true } catch (_: Exception) { false }
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

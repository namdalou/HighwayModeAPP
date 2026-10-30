package com.example.spectrum

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.media.MediaBrowserService
import android.view.KeyEvent

/**
 * 曲情報(再生中のセッション)をまだつかめていないときに、再生ボタンで音楽を再生させる。
 *
 * 1. まず再生中のセッションを探し直す → 見つかればそれを再生
 * 2. 最後に使った音楽アプリの「メディア再開」用の窓口(MediaBrowserService)に接続して再生を指示
 *    (Android の「メディアの再開」や Android Auto と同じ仕組み)
 * 3. それも無理ならメディアボタンの「再生」信号を送る
 */
object MediaResumer {

    private const val PREFS = "spectrum"
    private const val KEY_LAST_PKG = "last_media_pkg"
    /** 接続できなかったときに次の方法へ切り替えるまでの時間 */
    private const val CONNECT_TIMEOUT_MS = 2500L

    private val main = Handler(Looper.getMainLooper())
    private var browser: MediaBrowser? = null

    /** 最後に再生情報をつかめた音楽アプリを覚えておく(MediaTracker から呼ぶ) */
    fun rememberPackage(ctx: Context, pkg: String) {
        if (pkg.isEmpty() || pkg == ctx.packageName) return
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getString(KEY_LAST_PKG, null) != pkg) p.edit().putString(KEY_LAST_PKG, pkg).apply()
    }

    fun lastPackage(ctx: Context): String? =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_PKG, null)

    fun play(ctx: Context) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { play(ctx) }
            return
        }
        val c = ctx.applicationContext

        // 1. 再生中のセッションを探し直す
        MediaTracker.start(c)
        MediaTracker.pick()
        NowPlaying.controller?.let {
            it.transportControls.play()
            return
        }

        // 2. 最後に使った音楽アプリに接続して再生を指示
        val pkg = lastPackage(c)
        val comp = pkg?.let { findBrowserService(c, it) }
        if (comp != null) {
            connectAndPlay(c, comp)
            return
        }

        // 3. メディアボタンの「再生」
        NowPlaying.mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
    }

    private fun connectAndPlay(c: Context, comp: ComponentName) {
        browser?.let { try { it.disconnect() } catch (_: Exception) { } }
        // 「最近の曲を再開したい」という合図(メディア再開に対応したアプリはこれで応じる)
        val hints = Bundle().apply { putBoolean(MediaBrowserService.BrowserRoot.EXTRA_RECENT, true) }
        var done = false
        lateinit var b: MediaBrowser

        val fallback = Runnable {
            if (!done) {
                done = true
                try { b.disconnect() } catch (_: Exception) { }
                NowPlaying.mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            }
        }

        b = MediaBrowser(c, comp, object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                if (done) return
                done = true
                main.removeCallbacks(fallback)
                try {
                    MediaController(c, b.sessionToken).transportControls.play()
                } catch (_: Exception) {
                    NowPlaying.mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
                }
                // 再生が始まれば音楽アプリ側のセッションが有効になるので、少し待って取り込み直す
                main.postDelayed({
                    MediaTracker.pick()
                    try { b.disconnect() } catch (_: Exception) { }
                }, 3000L)
            }

            override fun onConnectionFailed() {
                main.removeCallbacks(fallback)
                fallback.run()
            }
        }, hints)
        browser = b
        main.postDelayed(fallback, CONNECT_TIMEOUT_MS)
        try {
            b.connect()
        } catch (_: Exception) {
            main.removeCallbacks(fallback)
            fallback.run()
        }
    }

    private fun findBrowserService(c: Context, pkg: String): ComponentName? = try {
        val intent = Intent("android.media.browse.MediaBrowserService").setPackage(pkg)
        c.packageManager.queryIntentServices(intent, 0).firstOrNull()?.serviceInfo?.let {
            ComponentName(it.packageName, it.name)
        }
    } catch (_: Exception) {
        null
    }
}

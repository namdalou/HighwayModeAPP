package com.example.spectrum

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper

/**
 * 再生中のメディアセッション(Spotify など)を追跡して NowPlaying に反映する。
 *
 * 通知へのアクセスが「許可されて」いれば、通知リスナーの接続を待たずに
 * スマホ画面・車の画面のどちらからでもすぐ開始できる。
 * (以前は通知リスナーの再接続を待っていたため、再起動直後に数十秒かかっていた)
 */
object MediaTracker {

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var msm: MediaSessionManager? = null
    private var me: ComponentName? = null
    private var started = false
    private var current: MediaController? = null
    /** 通知に埋め込まれていたセッション(getActiveSessions に出てこないもの用) */
    private val notifControllers = HashMap<String, MediaController>()

    val hasCurrent: Boolean get() = current != null

    /** いま追跡しているセッションのアプリ */
    val currentPackage: String? get() = current?.packageName

    /** 曲情報の取得元にしないアプリ(Android Auto 本体など) */
    private val IGNORED_PACKAGES = setOf(
        "com.google.android.projection.gearhead"
    )

    /**
     * 曲情報が空のままになっていないか確認して、必要なら取り直す。
     * 画面切り替えが速いときなどに取りこぼしても、自動で回復させるため定期的に呼ぶ。
     */
    fun heal(ctx: Context) {
        if (!started) { start(ctx); return }
        if (NowPlaying.title.isEmpty()) pick()
    }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { pick() }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) { applyMetadata(metadata) }
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            NowPlaying.state = state
            // 一時停止したら、他に再生中のセッションがないか選び直す
            if (state?.state != PlaybackState.STATE_PLAYING) pick()
        }
        override fun onSessionDestroyed() { pick() }
    }

    /** どこからでも呼べる。すでに開始済みなら選び直すだけ */
    fun start(ctx: Context) {
        val c = ctx.applicationContext
        if (Looper.myLooper() == Looper.getMainLooper()) startOnMain(c) else main.post { startOnMain(c) }
    }

    private fun startOnMain(c: Context) {
        NowPlaying.init(c)
        if (started) { pick(); return }
        val m = c.getSystemService(MediaSessionManager::class.java) ?: return
        val comp = ComponentName(c, MediaListenerService::class.java)
        try {
            m.addOnActiveSessionsChangedListener(sessionsListener, comp, main)
        } catch (_: SecurityException) {
            return   // 通知へのアクセスが未許可
        }
        app = c; msm = m; me = comp; started = true
        pick()
    }

    fun putNotificationController(pkg: String, token: MediaSession.Token) {
        val c = app ?: return
        if (notifControllers[pkg]?.sessionToken != token) notifControllers[pkg] = MediaController(c, token)
        pick()
    }

    fun removeNotificationController(pkg: String) {
        if (notifControllers.remove(pkg) != null) pick()
    }

    fun pick() {
        val c = app ?: return
        val active = (try { msm?.getActiveSessions(me) } catch (_: SecurityException) { null }
            ?: emptyList()).filter { it.packageName != c.packageName }
        val extra = notifControllers.values.filter { x -> active.none { it.sessionToken == x.sessionToken } }
        val all = active + extra

        Diagnostics.sessions = all.map { x ->
            val t = x.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
            "${appLabel(x.packageName)} (${x.packageName})  ${stateName(x.playbackState?.state)}  $t"
        }

        // 優先順: 再生中で曲名あり → 再生中 → 曲名あり → その他
        // (画面切り替え中に Android Auto 自身などの空のセッションを選ばないように)
        fun hasTitle(x: MediaController) =
            !x.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrEmpty()
        fun playing(x: MediaController) = x.playbackState?.state == PlaybackState.STATE_PLAYING
        val candidates = all.filter { it.packageName !in IGNORED_PACKAGES }
        val best = candidates.firstOrNull { playing(it) && hasTitle(it) }
            ?: candidates.firstOrNull { playing(it) }
            ?: candidates.firstOrNull { hasTitle(it) }
            ?: candidates.firstOrNull()

        if (best == null && current == null) { MediaListenerService.instance?.refreshFallback(); return }
        if (best?.sessionToken == current?.sessionToken) {
            // 同じセッションでも、曲情報が空になっていたら読み直す
            if (best != null && NowPlaying.title.isEmpty()) {
                NowPlaying.state = best.playbackState
                applyMetadata(best.metadata)
            }
            return
        }

        current?.unregisterCallback(callback)
        current = best
        if (best == null) {
            NowPlaying.clearSession()
            MediaListenerService.instance?.refreshFallback()
            return
        }
        best.registerCallback(callback, main)
        NowPlaying.setSession(best, appLabel(best.packageName))
        MediaResumer.rememberPackage(c, best.packageName)   // 再生ボタンで再開できるよう覚えておく
        applyMetadata(best.metadata)
    }

    private fun applyMetadata(m: MediaMetadata?) {
        // 空文字の項目は飛ばして、最初に中身のある項目を使う
        fun first(vararg keys: String): String {
            if (m == null) return ""
            for (k in keys) {
                val v = m.getString(k)
                if (!v.isNullOrBlank()) return v
            }
            return ""
        }
        val title = first(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            .ifEmpty { m?.description?.title?.toString().orEmpty() }
        val artist = first(
            MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
            MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE
        )
        val album = first(MediaMetadata.METADATA_KEY_ALBUM)
        val duration = m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val bmp = m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: m?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

        NowPlaying.applySessionInfo(title, artist, album, duration, bmp)

        // 曲名が入っていないアプリ(らじるらじる など)は、同じアプリの通知の文字で補う
        if (title.isEmpty()) {
            current?.packageName?.let { MediaListenerService.instance?.fillFromAppNotification(it) }
        }

        if (bmp == null && m != null) {
            val uri = m.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                ?: m.getString(MediaMetadata.METADATA_KEY_ART_URI)
                ?: m.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
            if (uri != null) loadArtFromUri(uri, title)
        }

    }

    /** content:// や file:// のジャケット画像を別スレッドで読み込む */
    private fun loadArtFromUri(uriStr: String, forTitle: String) {
        val c = app ?: return
        val uri = Uri.parse(uriStr)
        if (uri.scheme != "content" && uri.scheme != "file") return
        Thread {
            try {
                c.contentResolver.openInputStream(uri)?.use { ins ->
                    val b = BitmapFactory.decodeStream(ins)
                    if (b != null && NowPlaying.title == forTitle) NowPlaying.art = b
                }
            } catch (_: Exception) { }
        }.start()
    }

    private fun stateName(s: Int?): String = when (s) {
        PlaybackState.STATE_PLAYING -> "再生中"
        PlaybackState.STATE_PAUSED -> "一時停止"
        PlaybackState.STATE_STOPPED -> "停止"
        PlaybackState.STATE_BUFFERING -> "読み込み中"
        null -> "状態不明"
        else -> "状態=$s"
    }

    private fun appLabel(pkg: String): String {
        val pm = app?.packageManager ?: return pkg
        return try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            pkg
        }
    }
}

package com.example.spectrum

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

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
     * 曲名はあるのにジャケットが無いときは、ジャケットだけ取り直す。
     */
    fun heal(ctx: Context) {
        if (!started) { start(ctx); return }
        if (NowPlaying.title.isEmpty()) pick()
        else if (NowPlaying.art == null) refreshArt()
    }

    private var lastArtRefresh = 0L

    /** いまのジャケットは通知の画像（曲が変わって通知が更新されたら差し替える） */
    @Volatile var artFromNotification = false
        private set

    /** 追跡中のアプリが再生中の通知を出し直した（曲が変わった など）。ジャケットが無い・通知由来なら差し替える */
    fun onPlayingNotification(pkg: String, bmp: Bitmap?) {
        if (bmp == null || pkg != current?.packageName) return
        if (NowPlaying.art == null || artFromNotification) {
            NowPlaying.art = bmp
            artFromNotification = true
        }
    }

    /**
     * ジャケットの取り直し（4 秒に 1 回まで）。
     * 1. セッションの最新の曲情報（画像の更新を取りこぼした場合）
     * 2. 同じアプリの再生中の通知の画像
     * 3. 画像の場所（content:// / https://）から読み込み
     */
    private fun refreshArt() {
        val now = SystemClock.uptimeMillis()
        if (now - lastArtRefresh < 4000L) return
        lastArtRefresh = now
        val cur = current
        val m = try { cur?.metadata } catch (_: Exception) { null }
        bitmapOf(m)?.let { NowPlaying.art = it; return }
        val pkg = cur?.packageName ?: NowPlaying.sourcePackage
        if (pkg.isNotEmpty()) {
            MediaListenerService.instance?.artFromNotification(pkg)?.let {
                NowPlaying.art = it
                artFromNotification = true
                return
            }
        }
        artUriOf(m)?.let { loadArtFromUri(it, NowPlaying.title) }
    }

    private fun bitmapOf(m: MediaMetadata?): Bitmap? {
        if (m == null) return null
        return m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: m.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
    }

    private fun artUriOf(m: MediaMetadata?): String? {
        if (m == null) return null
        return listOf(
            MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
            MediaMetadata.METADATA_KEY_ART_URI,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI
        ).firstNotNullOfOrNull { k -> m.getString(k)?.takeIf { it.isNotBlank() } }
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
        var bmp = bitmapOf(m)
        artFromNotification = false
        // 画像を曲情報に入れず、再生中の通知にだけ出すアプリもある
        if (bmp == null) {
            current?.packageName?.let { pkg ->
                bmp = MediaListenerService.instance?.artFromNotification(pkg)
                artFromNotification = bmp != null
            }
        }

        NowPlaying.applySessionInfo(title, artist, album, duration, bmp)

        // 曲名が入っていないアプリ(らじるらじる など)は、同じアプリの通知の文字で補う
        if (title.isEmpty()) {
            current?.packageName?.let { MediaListenerService.instance?.fillFromAppNotification(it) }
        }

        if (bmp == null) artUriOf(m)?.let { loadArtFromUri(it, title) }
    }

    /** ジャケット画像の読み込み（1 本の作業スレッドで順に） */
    private val artLoader = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "spectrum-art").apply { isDaemon = true }
    }
    @Volatile private var webArtUrl: String? = null
    @Volatile private var webArt: Bitmap? = null
    @Volatile private var webArtFailedUrl: String? = null
    @Volatile private var webArtFailedAt = 0L

    /**
     * content:// / file:// / https:// のジャケット画像を別スレッドで読み込む。
     * https:// の画像（一部の配信アプリ）は、通信できるときだけ読む（同じ画像は使い回す）。
     */
    private fun loadArtFromUri(uriStr: String, forTitle: String) {
        val c = app ?: return
        val uri = Uri.parse(uriStr)
        when (uri.scheme) {
            "content", "file" -> artLoader.execute {
                try {
                    c.contentResolver.openInputStream(uri)?.use { ins ->
                        val b = decodeSampled(ins.readBytes())
                        if (b != null && NowPlaying.title == forTitle) NowPlaying.art = b
                    }
                } catch (_: Exception) { }
            }
            "http", "https" -> {
                val cached = webArt
                if (uriStr == webArtUrl && cached != null) {
                    NowPlaying.art = cached
                    return
                }
                // 失敗した画像は 1 分は取り直さない
                if (uriStr == webArtFailedUrl && SystemClock.uptimeMillis() - webArtFailedAt < 60_000L) return
                artLoader.execute {
                    try {
                        val conn = java.net.URL(uriStr).openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 5000
                        conn.readTimeout = 8000
                        conn.instanceFollowRedirects = true
                        val bytes = try {
                            conn.inputStream.use { ins -> readLimited(ins, 4 * 1024 * 1024) }
                        } finally {
                            conn.disconnect()
                        }
                        val b = bytes?.let { decodeSampled(it) }
                        if (b != null) {
                            webArtUrl = uriStr
                            webArt = b
                            if (NowPlaying.title == forTitle) NowPlaying.art = b
                        } else {
                            webArtFailedUrl = uriStr
                            webArtFailedAt = SystemClock.uptimeMillis()
                        }
                    } catch (_: Exception) {
                        webArtFailedUrl = uriStr
                        webArtFailedAt = SystemClock.uptimeMillis()
                    }
                }
            }
        }
    }

    private fun readLimited(ins: java.io.InputStream, max: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) return null
        }
        return out.toByteArray()
    }

    /** 大きすぎる画像は 512px 程度に縮めて読む（メモリ節約） */
    private fun decodeSampled(bytes: ByteArray): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= 512) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
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

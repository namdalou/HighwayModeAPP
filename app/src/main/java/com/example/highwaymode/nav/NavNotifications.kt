package com.example.highwaymode.nav

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import com.example.highwaymode.data.Prefs
import com.example.highwaymode.engine.EngineHost
import com.example.spectrum.Guidance
import com.example.spectrum.MediaListenerService
import com.example.spectrum.NotificationHooks

/**
 * Google マップのナビの通知を読み取り、次の案内（案内地点までの距離・案内の文）をエンジンへ渡す。
 * エンジンは、案内に出てくる JCT・IC の名前、分岐の先の路線名・路線番号・方面で照合して、
 * 前方の経路（JCT の乗り換え先）と降りる IC を合わせる（[com.example.highwaymode.core.route.NavGuide]）。
 *
 * v2.6 から、音楽の画面にも次の案内（矢印・距離・案内の文）を出す（[Guidance]。高速道路の外の一般道でも）。
 *
 * 通知へのアクセス（曲情報と同じ許可）を使う。Google マップは案内を通知の文字欄ではなく独自の表示（RemoteViews）で
 * 出すことがあるので、文字欄が空なら、その表示を組み立てて文字を集める。読み取った内容は端末の中だけで使い、保存・送信しない。
 */
object NavNotifications {

    private const val TAG = "NavNotifications"
    /** Google マップ（と Google マップ Go） */
    val PACKAGES = setOf("com.google.android.apps.maps", "com.google.android.apps.mapslite")
    /** 読み取りの間隔（通知は頻繁に更新されるので、まとめて読む） */
    private const val MIN_INTERVAL_MS = 1500L

    /** ナビの通知を受け取っている（ナビ中） */
    @Volatile
    var active = false
        private set

    /** 最後に読んだ案内（表示・確認用） */
    @Volatile
    var lastText: String? = null
        private set

    @Volatile
    var lastDistanceM: Double? = null
        private set

    /** 確認用（開発者向け設定）: 最後に読んだ通知の文字（部品の名前つき）と時刻 */
    @Volatile
    var lastPieces: List<String> = emptyList()
        private set

    @Volatile
    var lastReadWallMs = 0L
        private set

    /** ナビの通知を出しているアプリ（帯を押したときに開く） */
    @Volatile
    var activePackage: String? = null
        private set

    /** 確認用: 矢印の画像を読み取れたか */
    @Volatile
    var lastIconOk = false
        private set

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile
    private var pending: StatusBarNotification? = null
    @Volatile
    private var lastProcessed = 0L
    @Volatile
    private var activeKey: String? = null
    @Volatile
    private var appCtx: Context? = null

    /** 通知の受け取り口に登録し、いま出ているナビの通知を読む（アプリ・車の画面・エンジンの起動時に呼ぶ。何度呼んでもよい） */
    fun install(ctx: Context) {
        appCtx = ctx.applicationContext
        NotificationHooks.onPosted = { c, sbn -> onPosted(c, sbn) }
        NotificationHooks.onRemoved = { sbn -> onRemoved(sbn) }
        try {
            MediaListenerService.instance?.activeNotifications?.forEach { sbn ->
                if (sbn.packageName in PACKAGES) onPosted(ctx, sbn)
            }
        } catch (e: Exception) {
            // 通知へのアクセスが未許可・未接続
        }
    }

    @Synchronized
    private fun handler(): Handler {
        handler?.let { return it }
        val t = HandlerThread("nav-notify").also { it.start() }
        thread = t
        return Handler(t.looper).also { handler = it }
    }

    fun onPosted(ctx: Context, sbn: StatusBarNotification) {
        if (sbn.packageName !in PACKAGES) return
        val n = sbn.notification ?: return
        // ナビの案内は「実行中」の通知（終わった案内・ほかのお知らせは除く）
        if (n.flags and Notification.FLAG_ONGOING_EVENT == 0) return
        if (appCtx == null) appCtx = ctx.applicationContext
        val p = Prefs(ctx)
        if (!p.navSync && !p.navBanner) {
            if (active) clear()
            return
        }
        pending = sbn
        val h = handler()
        h.removeCallbacks(process)
        val wait = (MIN_INTERVAL_MS - (SystemClock.elapsedRealtime() - lastProcessed)).coerceAtLeast(0L)
        h.postDelayed(process, wait)
    }

    fun onRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName !in PACKAGES) return
        if (activeKey == null || sbn.key == activeKey) clear()
    }

    /** ナビが終わった（または連携をオフにした） */
    fun clear() {
        // 読み取り待ちの通知（終わる前のもの）で、ナビ中に戻らないように
        pending = null
        handler?.removeCallbacks(process)
        active = false
        activeKey = null
        lastText = null
        lastDistanceM = null
        EngineHost.setNavInstruction(null, null)
        Guidance.clear()
    }

    /** 設定を変えたとき: 両方オフなら消す。経路合わせだけオフならエンジンへの案内だけ、帯だけオフなら帯だけ消す */
    fun settingsChanged(ctx: Context) {
        val p = Prefs(ctx)
        when {
            !p.navSync && !p.navBanner -> clear()
            !p.navSync -> EngineHost.setNavInstruction(null, null)
            !p.navBanner -> Guidance.clear()
        }
    }

    /** ナビのアプリ（Google マップ）を開く（スマホの音楽の画面の案内の帯を押したとき） */
    fun openNavApp(ctx: Context) {
        val pkg = activePackage ?: PACKAGES.first()
        try {
            val launch = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(launch)
        } catch (e: Exception) {
            Log.i(TAG, "open nav app: ${e.message}")
        }
    }

    private val process = Runnable {
        val sbn = pending ?: return@Runnable
        pending = null
        lastProcessed = SystemClock.elapsedRealtime()
        val ctx = appCtx ?: return@Runnable
        try {
            read(ctx, sbn)
        } catch (t: Throwable) {
            Log.w(TAG, "read failed: ${t.message}")
        }
    }

    private fun read(ctx: Context, sbn: StatusBarNotification) {
        val pieces = texts(ctx, sbn)
        lastPieces = pieces.map { "${it.first}: ${it.second}" }
        lastReadWallMs = System.currentTimeMillis()
        val n = sbn.notification
        val navCategory = n.category == Notification.CATEGORY_NAVIGATION ||
            (n.channelId?.contains("nav", ignoreCase = true) == true)
        val parsed = NavText.parse(pieces)
        if (parsed == null || (!navCategory && parsed.distanceM == null)) return
        active = true
        activeKey = sbn.key
        activePackage = sbn.packageName
        lastText = parsed.instruction
        lastDistanceM = parsed.distanceM
        val p = Prefs(ctx)
        // 前方の経路（JCT の乗り換え先・降りる IC）をナビに合わせる
        if (p.navSync) EngineHost.setNavInstruction(parsed.instruction, parsed.distanceM)
        // 音楽の画面の案内の帯（一般道でも）
        if (p.navBanner) {
            val icon = maneuverIcon(ctx, n)
            lastIconOk = icon != null
            Guidance.set(parsed.instruction, parsed.distanceM, icon, if (sbn.packageName.endsWith("lite")) "Google マップ Go" else "Google マップ")
        }
    }

    /**
     * 曲がる方向の矢印の画像（通知の大きなアイコン）を、白い矢印にして返す。
     * 1 色の図形（矢印）でなければ（アプリのアイコンなど）使わない（null。帯には案内の文から推定した矢印を描く）
     */
    private fun maneuverIcon(ctx: Context, n: Notification): Bitmap? = try {
        val d = n.getLargeIcon()?.loadDrawable(ctx)
        if (d == null) null else {
            val size = 96
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, size, size)
            d.draw(Canvas(bmp))
            val px = IntArray(size * size)
            bmp.getPixels(px, 0, size, 0, 0, size, size)
            var clear = 0
            var opaque = 0
            var colorful = 0
            val hsv = FloatArray(3)
            for (c in px) {
                val a = Color.alpha(c)
                if (a < 40) {
                    clear++
                    continue
                }
                if (a < 160) continue
                opaque++
                Color.colorToHSV(c, hsv)
                if (hsv[1] > 0.35f && hsv[2] > 0.25f) colorful++
            }
            // 透明な地に 1 色の図形 → 矢印。白く塗り直す（形は透明度のまま）
            if (clear >= px.size / 4 && opaque > 0 && colorful <= opaque / 5) {
                for (i in px.indices) px[i] = (px[i] and 0xFF000000.toInt()) or 0x00FFFFFF
                bmp.setPixels(px, 0, size, 0, 0, size, size)
                bmp
            } else {
                bmp.recycle()
                null
            }
        }
    } catch (t: Throwable) {
        null
    }

    /** 通知の文字を集める（部品の名前, 文字）。文字欄 → 独自の表示（RemoteViews）の順 */
    private fun texts(ctx: Context, sbn: StatusBarNotification): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val n = sbn.notification
        val ex = n.extras
        fun add(name: String, cs: CharSequence?) {
            val t = cs?.toString()?.replace('\n', ' ')?.trim()
            if (!t.isNullOrEmpty() && out.none { it.second == t }) out.add(name to t)
        }
        if (ex != null) {
            add("title", ex.getCharSequence(Notification.EXTRA_TITLE))
            add("text", ex.getCharSequence(Notification.EXTRA_TEXT))
            add("bigText", ex.getCharSequence(Notification.EXTRA_BIG_TEXT))
            add("subText", ex.getCharSequence(Notification.EXTRA_SUB_TEXT))
            add("infoText", ex.getCharSequence(Notification.EXTRA_INFO_TEXT))
            add("summary", ex.getCharSequence(Notification.EXTRA_SUMMARY_TEXT))
            ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { add("line", it) }
        }
        @Suppress("DEPRECATION")
        val views = listOfNotNull(n.bigContentView, n.contentView)
        for (rv in views) collect(ctx, sbn.packageName, rv, out)
        return out
    }

    /** 独自の表示（RemoteViews）を、このアプリの中で組み立てて、見えている文字を集める */
    private fun collect(ctx: Context, pkg: String, rv: RemoteViews, out: MutableList<Pair<String, String>>) {
        try {
            val pctx = ctx.createPackageContext(pkg, Context.CONTEXT_IGNORE_SECURITY)
            val root = rv.apply(pctx, FrameLayout(pctx))
            walk(root, pctx, out)
        } catch (t: Throwable) {
            Log.i(TAG, "remote views: ${t.javaClass.simpleName} ${t.message}")
        }
    }

    private fun walk(v: View, pctx: Context, out: MutableList<Pair<String, String>>) {
        if (v.visibility != View.VISIBLE) return
        if (v is TextView) {
            val t = v.text?.toString()?.replace('\n', ' ')?.trim()
            if (!t.isNullOrEmpty() && out.none { it.second == t }) out.add(entryName(pctx, v.id) to t)
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), pctx, out)
    }

    private fun entryName(pctx: Context, id: Int): String = try {
        if (id > 0) pctx.resources.getResourceEntryName(id) else "view"
    } catch (e: Exception) {
        "view"
    }
}

/**
 * ナビの通知の文字から、案内地点までの距離と案内の文を取り出す。
 * 距離は「52 km」「1.2 km」「800 m」の形（題名の部品を優先）。到着時刻・残りの時間の部品は案内の文に入れない。
 */
object NavText {

    class Parsed(val distanceM: Double?, val instruction: String)

    private val DIST = Regex("(\\d+(?:[.,]\\d+)?)\\s*(km|ｋｍ|キロメートル|キロ|m|ｍ|メートル)(?![A-Za-z])", RegexOption.IGNORE_CASE)
    private val ONLY_DIST = Regex("^(\\d+(?:[.,]\\d+)?)\\s*(km|ｋｍ|キロメートル|キロ|m|ｍ|メートル)\\s*(先)?$", RegexOption.IGNORE_CASE)
    private val TIME = Regex("\\d{1,2}[:：]\\d{2}")

    fun distanceOf(s: String): Double? {
        val m = DIST.find(s) ?: return null
        val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val unit = m.groupValues[2].lowercase()
        return if (unit.startsWith("k") || unit.startsWith("ｋ") || unit.startsWith("キ")) v * 1000.0 else v
    }

    /** 到着時刻・残りの時間の部品（「13:05 到着」「1 時間 23 分 · 98 km · 13:05」） */
    private fun isEta(s: String): Boolean =
        (TIME.containsMatchIn(s) && (s.contains("着") || s.contains("到着") || s.contains("·") || s.contains("・") || s.contains("分"))) ||
            s.contains("到着予定")

    fun parse(pieces: List<Pair<String, String>>): Parsed? {
        if (pieces.isEmpty()) return null
        // 距離: 題名の部品（nav_title・title）→ 距離だけの部品
        var dist: Double? = null
        for ((name, t) in pieces) {
            if ((name == "nav_title" || name == "title") && !isEta(t)) {
                distanceOf(t)?.let { dist = it }
                if (dist != null) break
            }
        }
        if (dist == null) {
            for ((_, t) in pieces) {
                if (ONLY_DIST.matches(t.trim())) {
                    dist = distanceOf(t)
                    break
                }
            }
        }
        val words = ArrayList<String>()
        for ((_, t) in pieces) {
            val tt = t.trim()
            if (ONLY_DIST.matches(tt)) continue
            if (isEta(tt)) continue
            if (tt.length <= 1) continue
            words.add(tt)
        }
        val instruction = words.joinToString(" ").take(300)
        if (instruction.isBlank()) return null
        return Parsed(dist, instruction)
    }
}

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
import com.example.highwaymode.core.route.NavText
import com.example.highwaymode.data.Prefs
import com.example.highwaymode.engine.EngineHost
import com.example.spectrum.Guidance
import com.example.spectrum.MediaListenerService
import com.example.spectrum.NotificationHooks

/**
 * カーナビのアプリ（Google マップなど）のナビの通知を読み取り、次の案内（案内地点までの距離・案内の文）をエンジンへ渡す。
 * エンジンは、案内に出てくる JCT・IC の名前、分岐の先の路線名・路線番号・方面で照合して、
 * 前方の経路（JCT の乗り換え先）と降りる IC を合わせる（[com.example.highwaymode.core.route.NavGuide]）。
 *
 * v2.6 から、音楽の画面にも次の案内（矢印・距離・案内の文）を出す（[Guidance]。高速道路の外の一般道でも）。
 *
 * v2.16 から:
 *  - **案内をしているときだけ**出す。カーナビのアプリは、案内を始めていなくても「実行中」の通知を出すことがある
 *    （Android Auto につなぐと出る Google マップの「運転モード」など）。距離か、曲がる方向・出口・分岐などの言葉がある
 *    通知だけを案内として扱う（[NavText.Parsed.guidance]）。案内中だった通知が案内でなくなったら、少し待って消す。
 *  - Google マップ以外のカーナビのアプリ（[APPS]。Yahoo!カーナビ・moviLink・カーナビタイムなど）と、
 *    通知の種類が「ナビ」（CATEGORY_NAVIGATION）のアプリの通知も同じ読み方で読む。
 *    通知に次の案内（距離・曲がる方向）を出さないアプリでは、何も出ない（実機では未確認）。
 *
 * 通知へのアクセス（曲情報と同じ許可）を使う。カーナビのアプリは案内を通知の文字欄ではなく独自の表示（RemoteViews）で
 * 出すことがあるので、文字欄が空なら、その表示を組み立てて文字を集める。読み取った内容は端末の中だけで使い、保存・送信しない。
 */
object NavNotifications {

    private const val TAG = "NavNotifications"
    /** カーナビのアプリ（パッケージ名 → 表示する名前）。ここに無くても、通知の種類が「ナビ」のアプリは読む */
    val APPS: Map<String, String> = linkedMapOf(
        "com.google.android.apps.maps" to "Google マップ",
        "com.google.android.apps.mapslite" to "Google マップ Go",
        "jp.co.yahoo.android.apps.navi" to "Yahoo!カーナビ",
        "jp.co.toyotaconnected.tlbsapp" to "moviLink",
        "com.navitime.local.carnavitime" to "カーナビタイム",
        "com.navitime.local.navitimedrive" to "ドライブサポーター",
        "com.navitime.local.navitimetruck" to "トラックカーナビ",
        "jp.pioneer.cocchi.android" to "COCCHi",
        "jp.co.yahoo.android.apps.map" to "Yahoo!マップ",
        "com.waze" to "Waze"
    )
    /** 案内中だった通知が案内でなくなってから、消すまでの時間（経路を探し直している間などに、出たり消えたりしないように） */
    private const val CLEAR_AFTER_MS = 6000L
    /** 案内中のアプリの通知がこの時間内に更新されていれば、ほかのアプリの案内には切り替えない（2 つのナビを同時に動かしたとき） */
    private const val KEEP_ACTIVE_MS = 15_000L
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

    /** 確認用: 最後に読んだ通知のアプリの名前と、案内として扱ったか（理由つき） */
    @Volatile
    var lastApp: String? = null
        private set

    @Volatile
    var lastVerdict: String? = null
        private set

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    /**
     * 読み取り待ちの通知（通知ごとに最新の 1 件）。1 つの枠だけだと、同じアプリが「運転モード」と「案内」の 2 つの通知を
     * 出していて交互に更新されたとき、案内の通知がいつも後から来た方に上書きされて読まれないことがある
     */
    private val pending = LinkedHashMap<String, StatusBarNotification>()
    @Volatile
    private var lastProcessed = 0L
    @Volatile
    private var activeKey: String? = null
    /** 案内中の通知を最後に案内として読んだ時刻（SystemClock.elapsedRealtime） */
    @Volatile
    private var activeReadAt = 0L
    @Volatile
    private var appCtx: Context? = null

    /** 通知の受け取り口に登録し、いま出ているナビの通知を読む（アプリ・車の画面・エンジンの起動時に呼ぶ。何度呼んでもよい） */
    fun install(ctx: Context) {
        appCtx = ctx.applicationContext
        NotificationHooks.onPosted = { c, sbn -> onPosted(c, sbn) }
        NotificationHooks.onRemoved = { sbn -> onRemoved(sbn) }
        try {
            MediaListenerService.instance?.activeNotifications?.forEach { sbn ->
                if (candidate(ctx, sbn)) onPosted(ctx, sbn)
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

    /** カーナビのアプリの「実行中」の通知か（知っているアプリ、または通知の種類が「ナビ」。このアプリ自身の通知は除く） */
    private fun candidate(ctx: Context, sbn: StatusBarNotification): Boolean {
        val pkg = sbn.packageName ?: return false
        if (pkg == ctx.packageName) return false
        val n = sbn.notification ?: return false
        // ナビの案内は「実行中」の通知（終わった案内・ほかのお知らせは除く）
        if (n.flags and Notification.FLAG_ONGOING_EVENT == 0) return false
        return pkg in APPS || n.category == Notification.CATEGORY_NAVIGATION
    }

    /** アプリの名前（知っているアプリは決まった名前、ほかは端末に入っている名前） */
    private fun appName(ctx: Context, pkg: String): String = APPS[pkg] ?: try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().ifBlank { "カーナビ" }
    } catch (e: Exception) {
        "カーナビ"
    }

    fun onPosted(ctx: Context, sbn: StatusBarNotification) {
        if (!candidate(ctx, sbn)) return
        if (appCtx == null) appCtx = ctx.applicationContext
        val p = Prefs(ctx)
        if (!p.navSync && !p.navBanner) {
            if (active) clear()
            return
        }
        synchronized(pending) {
            pending.remove(sbn.key)
            pending[sbn.key] = sbn
        }
        val h = handler()
        h.removeCallbacks(process)
        val wait = (MIN_INTERVAL_MS - (SystemClock.elapsedRealtime() - lastProcessed)).coerceAtLeast(0L)
        h.postDelayed(process, wait)
    }

    fun onRemoved(sbn: StatusBarNotification) {
        // 案内を読んでいる通知が消えた → ナビが終わった（ほかのアプリの通知が消えても、いまの案内は消さない）
        synchronized(pending) { pending.remove(sbn.key) }
        if (activeKey != null && sbn.key == activeKey) clear()
    }

    /** ナビが終わった（または連携をオフにした） */
    fun clear() {
        // 読み取り待ちの通知（終わる前のもの）で、ナビ中に戻らないように
        synchronized(pending) { pending.clear() }
        handler?.removeCallbacks(process)
        handler?.removeCallbacks(clearLater)
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

    /** ナビのアプリ（案内を出しているアプリ）を開く（スマホの音楽の画面の案内の帯を押したとき） */
    fun openNavApp(ctx: Context) {
        val pkg = activePackage ?: APPS.keys.first()
        try {
            val launch = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(launch)
        } catch (e: Exception) {
            Log.i(TAG, "open nav app: ${e.message}")
        }
    }

    private val process = Runnable {
        val list = synchronized(pending) {
            val l = ArrayList(pending.values)
            pending.clear()
            l
        }
        if (list.isEmpty()) return@Runnable
        lastProcessed = SystemClock.elapsedRealtime()
        val ctx = appCtx ?: return@Runnable
        for (sbn in list) {
            try {
                read(ctx, sbn)
            } catch (t: Throwable) {
                Log.w(TAG, "read failed: ${t.message}")
            }
        }
    }

    /** 案内中だった通知が案内でなくなったまま → ナビが終わったとみなす */
    private val clearLater = Runnable { clear() }

    private fun read(ctx: Context, sbn: StatusBarNotification) {
        val pieces = texts(ctx, sbn)
        val name = appName(ctx, sbn.packageName)
        lastPieces = pieces.map { "${it.first}: ${it.second}" }
        lastReadWallMs = System.currentTimeMillis()
        lastApp = "$name（${sbn.packageName}）"
        val n = sbn.notification
        val parsed = NavText.parse(pieces)
        if (parsed == null || !parsed.guidance) {
            // 案内ではない通知（案内を始めていないときの「運転モード」など）は出さない。
            // 案内中だった通知が案内でなくなったら、少し待ってから消す（すぐ案内に戻れば消さない）
            lastVerdict = when {
                parsed == null -> "案内ではない（文字が無い）"
                NavText.isNotGuidance(parsed.instruction) -> "案内ではない（案内を始めていないときの通知）"
                else -> "案内ではない（距離も、曲がる方向・出口などの言葉も無い）"
            }
            if (active && sbn.key == activeKey) handler().let { h ->
                h.removeCallbacks(clearLater)
                h.postDelayed(clearLater, CLEAR_AFTER_MS)
            }
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (active && activeKey != null && sbn.key != activeKey && sbn.packageName != activePackage && now - activeReadAt < KEEP_ACTIVE_MS) {
            lastVerdict = "案内（ほかのアプリの案内を表示中なので使わない）"
            return
        }
        lastVerdict = if (parsed.distanceM != null) "案内（距離あり）" else "案内（距離は読み取れず）"
        handler().removeCallbacks(clearLater)
        active = true
        activeKey = sbn.key
        activeReadAt = now
        activePackage = sbn.packageName
        lastText = parsed.instruction
        lastDistanceM = parsed.distanceM
        val p = Prefs(ctx)
        // 前方の経路（JCT の乗り換え先・降りる IC）をナビに合わせる
        if (p.navSync) EngineHost.setNavInstruction(parsed.instruction, parsed.distanceM, name)
        // 音楽の画面の案内の帯（一般道でも）
        if (p.navBanner) {
            val icon = maneuverIcon(ctx, n)
            lastIconOk = icon != null
            Guidance.set(parsed.instruction, parsed.distanceM, icon, name)
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

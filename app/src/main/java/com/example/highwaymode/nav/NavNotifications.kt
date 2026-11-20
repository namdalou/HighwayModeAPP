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
 * v2.23 から（利用者の報告: スマホの Google マップで案内しているときは出るが、Android Auto の画面の Google マップで
 * 案内しているときは出ない）:
 *  - **「実行中」でない通知も読む**（知っているナビのアプリ・Android Auto・通知の種類が「ナビ」のもの）。車の画面で案内して
 *    いるとき、スマホには「運転モード」などの実行中の通知だけが出て、次の案内は別の通知（車の画面に出す案内の通知など）に
 *    なることがある。実行中でない通知は、距離と曲がる方向などの言葉の両方があるときだけ案内として扱う（お知らせの誤読を防ぐ）。
 *  - **通知の中の入れ子の情報も読む**: Android Auto 用の案内（Car App Library の CarAppExtender の content_title・content_text）、
 *    車・腕時計向けの拡張、ヘッドアップ表示の独自の表示、ロック画面用の文字（ほかに文字が無いとき）。
 *  - 案内の通知が消えても、すぐには消さない（[CLEAR_AFTER_MS]。曲がるたびに出し直すアプリのため）。
 *  - 確認用に、最近受け取った通知を [RECENT_MAX] 件まで覚える（開発者向け設定。どの通知に何が入っているかを確かめる）。
 *
 * 通知へのアクセス（曲情報と同じ許可）を使う。カーナビのアプリは案内を通知の文字欄ではなく独自の表示（RemoteViews）で
 * 出すことがあるので、その表示も組み立てて文字を集める。読み取った内容は端末の中だけで使い、保存・送信しない。
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
        "com.waze" to "Waze",
        // v2.23: 海外のナビのアプリ（Android Auto に対応したもの）
        "com.here.app.maps" to "HERE WeGo",
        "com.sygic.aura" to "Sygic",
        "com.tomtom.gplay.navapp" to "TomTom GO",
        "net.osmand" to "OsmAnd",
        "net.osmand.plus" to "OsmAnd+",
        "com.generalmagic.magicearth" to "Magic Earth"
    )
    /** Android Auto（車の画面に出すナビの案内を、スマホの通知としても出すことがある） */
    const val AUTO_PKG = "com.google.android.projection.gearhead"
    /** 確認用に覚えておく通知の数 */
    private const val RECENT_MAX = 15
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

    /** 確認用: 最近受け取った通知（新しい順。時刻・アプリ・実行中か・種類・判定・文字） */
    private val recent = ArrayDeque<String>()

    /** 確認用: 最近受け取った通知の一覧（新しい順） */
    fun recentReport(): String = synchronized(recent) {
        if (recent.isEmpty()) "（まだありません）" else recent.joinToString("\n\n")
    }

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

    /**
     * 読む通知か: 知っているナビのアプリ・Android Auto・通知の種類が「ナビ」の通知（このアプリ自身の通知は除く）。
     * v2.22 までは「実行中」の通知だけを読んでいた（車の画面で案内しているときの案内の通知を読めないことがあった）
     */
    private fun candidate(ctx: Context, sbn: StatusBarNotification): Boolean {
        val pkg = sbn.packageName ?: return false
        if (pkg == ctx.packageName) return false
        val n = sbn.notification ?: return false
        return pkg in APPS || pkg == AUTO_PKG || n.category == Notification.CATEGORY_NAVIGATION
    }

    /** アプリの名前（知っているアプリは決まった名前、ほかは端末に入っている名前） */
    private fun appName(ctx: Context, pkg: String): String = APPS[pkg] ?: if (pkg == AUTO_PKG) "Android Auto" else try {
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
        // 案内を読んでいる通知が消えた → 少し待って、新しい案内が来なければナビが終わったとみなす
        // （曲がるたびに案内の通知を出し直すアプリがあるため。ほかのアプリの通知が消えても、いまの案内は消さない）
        synchronized(pending) { pending.remove(sbn.key) }
        if (activeKey != null && sbn.key == activeKey) handler().let { h ->
            h.removeCallbacks(clearLater)
            h.postDelayed(clearLater, CLEAR_AFTER_MS)
        }
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
        val notes = ArrayList<String>()
        val pieces = texts(ctx, sbn, notes)
        val name = appName(ctx, sbn.packageName)
        val n = sbn.notification
        val ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0
        val parsed = NavText.parse(pieces)
        // 実行中でない通知・Android Auto の通知は、距離と曲がる方向などの言葉の両方があるときだけ案内（お知らせ・宣伝の誤読を防ぐ）
        val strict = !ongoing || sbn.packageName == AUTO_PKG
        val verdict: String
        val isGuidance = when {
            parsed == null -> {
                verdict = "案内ではない（文字が無い）"
                false
            }
            !parsed.guidance -> {
                verdict = if (NavText.isNotGuidance(parsed.instruction)) "案内ではない（案内を始めていないときの通知）"
                else "案内ではない（距離も、曲がる方向・出口などの言葉も無い）"
                false
            }
            strict && (parsed.distanceM == null || !NavText.hasManeuver(parsed.instruction)) -> {
                verdict = "案内ではない（実行中でない通知で、距離と曲がる方向などの言葉の両方は無い）"
                false
            }
            else -> {
                verdict = ""
                true
            }
        }
        // 「ほかの通知」の内容で、案内中の確認用の表示を上書きしない（案内中は案内の通知か、同じアプリの通知だけ）
        val showInDebug = !active || isGuidance || sbn.key == activeKey || sbn.packageName == activePackage
        if (showInDebug) {
            lastPieces = pieces.map { "${it.first}: ${it.second}" } + notes.map { "（$it）" }
            lastReadWallMs = System.currentTimeMillis()
            lastApp = "$name（${sbn.packageName}）"
        }
        if (!isGuidance) {
            // 案内ではない通知（案内を始めていないときの「運転モード」など）は出さない。
            // 案内中だった通知が案内でなくなったら、少し待ってから消す（すぐ案内に戻れば消さない）
            if (showInDebug) lastVerdict = verdict
            remember(sbn, name, ongoing, verdict, pieces, notes)
            if (active && sbn.key == activeKey) handler().let { h ->
                h.removeCallbacks(clearLater)
                h.postDelayed(clearLater, CLEAR_AFTER_MS)
            }
            return
        }
        parsed!!
        val now = SystemClock.elapsedRealtime()
        if (active && activeKey != null && sbn.key != activeKey && sbn.packageName != activePackage && now - activeReadAt < KEEP_ACTIVE_MS) {
            remember(sbn, name, ongoing, "案内（ほかのアプリの案内を表示中なので使わない）", pieces, notes)
            return
        }
        lastVerdict = (if (parsed.distanceM != null) "案内（距離あり）" else "案内（距離は読み取れず）") + if (ongoing) "" else "・実行中でない通知"
        remember(sbn, name, ongoing, lastVerdict!!, pieces, notes)
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

    /** 確認用に通知を覚える（新しい順に [RECENT_MAX] 件） */
    private fun remember(sbn: StatusBarNotification, name: String, ongoing: Boolean, verdict: String, pieces: List<Pair<String, String>>, notes: List<String>) {
        val n = sbn.notification
        val line = buildString {
            append(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.JAPAN).format(java.util.Date()))
            append(' ').append(name).append("（").append(sbn.packageName).append("）\n")
            append("  ").append(if (ongoing) "実行中" else "実行中でない")
            n.category?.let { append("・種類 ").append(it) }
            if (android.os.Build.VERSION.SDK_INT >= 26) n.channelId?.let { append("・チャンネル ").append(it) }
            append("・番号 ").append(sbn.id).append("\n")
            append("  判定: ").append(verdict)
            for ((k, v) in pieces) append("\n  ").append(k).append(": ").append(v.take(80))
            for (t in notes) append("\n  （").append(t).append("）")
        }
        synchronized(recent) {
            recent.addFirst(line)
            while (recent.size > RECENT_MAX) recent.removeLast()
        }
    }

    /**
     * 通知の文字を集める（部品の名前, 文字）。文字欄 → 入れ子の情報（Android Auto・車・腕時計向け）→ 流れる文字 →
     * 独自の表示（RemoteViews: 大きい表示・通常の表示・ヘッドアップ表示）→（ほかに文字が無いときだけ）ロック画面用の文字。
     * [notes] には確認用の補足（独自の表示を組み立てられなかった など）を入れる
     */
    private fun texts(ctx: Context, sbn: StatusBarNotification, notes: MutableList<String>): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val n = sbn.notification
        val ex = n.extras
        fun add(name: String, cs: CharSequence?) {
            val t = cs?.toString()?.replace('\n', ' ')?.trim()
            if (!t.isNullOrEmpty() && out.none { it.second == t }) out.add(name to t)
        }
        if (ex != null) {
            add("title", ex.getCharSequence(Notification.EXTRA_TITLE))
            add("title", ex.getCharSequence(Notification.EXTRA_TITLE_BIG))
            add("text", ex.getCharSequence(Notification.EXTRA_TEXT))
            add("bigText", ex.getCharSequence(Notification.EXTRA_BIG_TEXT))
            add("subText", ex.getCharSequence(Notification.EXTRA_SUB_TEXT))
            add("infoText", ex.getCharSequence(Notification.EXTRA_INFO_TEXT))
            add("summary", ex.getCharSequence(Notification.EXTRA_SUMMARY_TEXT))
            ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { add("line", it) }
            // 入れ子の情報（Android Auto の案内の通知の content_title・content_text など）
            try {
                nested(ex, "", 0, ::add)
            } catch (t: Throwable) {
                notes.add("入れ子の情報を読めませんでした: ${t.javaClass.simpleName}")
            }
        }
        add("ticker", n.tickerText)
        @Suppress("DEPRECATION")
        val views = listOfNotNull("big" to n.bigContentView, "content" to n.contentView, "headsUp" to n.headsUpContentView)
            .filter { it.second != null }
        for ((label, rv) in views) collect(ctx, sbn.packageName, rv!!, out)?.let { notes.add("独自の表示（$label）を組み立てられませんでした: $it") }
        if (views.isNotEmpty() && notes.none { it.startsWith("独自の表示") }) notes.add("独自の表示: " + views.joinToString("・") { it.first })
        // ロック画面用の文字（中身を隠した通知の代わり）。ほかに文字が無いときだけ（「ナビ中」などの決まり文句が多いため）
        if (out.isEmpty()) n.publicVersion?.extras?.let { pe ->
            add("public:title", pe.getCharSequence(Notification.EXTRA_TITLE))
            add("public:text", pe.getCharSequence(Notification.EXTRA_TEXT))
        }
        return out
    }

    /** 入れ子の Bundle を 3 段までたどって、文字らしい項目を集める（標準の文字欄は読み済みなので、2 段目から） */
    @Suppress("DEPRECATION")
    private fun nested(b: android.os.Bundle, prefix: String, depth: Int, add: (String, CharSequence?) -> Unit) {
        if (depth > 3) return
        for (key in b.keySet().sorted()) {
            val v = try {
                b.get(key)
            } catch (t: Throwable) {
                continue
            } ?: continue
            val lk = key.lowercase()
            if (SKIP_KEYS.any { lk.contains(it) }) continue
            when (v) {
                is android.os.Bundle -> nested(v, prefix + shortKey(key) + ":", depth + 1, add)
                is CharSequence -> if (depth > 0 && TEXT_KEYS.any { lk.contains(it) }) add(prefix + shortKey(key), v)
                is Array<*> -> for (e in v) when (e) {
                    is android.os.Bundle -> nested(e, prefix + shortKey(key) + ":", depth + 1, add)
                    is Notification -> e.extras?.let { nested(it, prefix + shortKey(key) + ":", depth + 1, add) }
                    is CharSequence -> if (depth > 0 && TEXT_KEYS.any { lk.contains(it) }) add(prefix + shortKey(key), e)
                }
                is java.util.ArrayList<*> -> for (e in v) when (e) {
                    is android.os.Bundle -> nested(e, prefix + shortKey(key) + ":", depth + 1, add)
                    is CharSequence -> if (depth > 0 && TEXT_KEYS.any { lk.contains(it) }) add(prefix + shortKey(key), e)
                }
            }
        }
    }

    /** 部品の名前を短く（「androidx.car.app.EXTENSIONS」→「car」、「android.wearable.EXTENSIONS」→「wear」） */
    private fun shortKey(key: String): String {
        val lk = key.lowercase()
        return when {
            lk.contains("car") && lk.contains("extensions") -> "car"
            lk.contains("wearable") -> "wear"
            lk == "android.pages" || lk == "pages" -> "page"
            else -> key.substringAfterLast('.')
        }
    }

    /** 文字として読む項目の名前（の一部） */
    private val TEXT_KEYS = listOf(
        "title", "text", "content", "summary", "info", "message", "line", "instruction", "maneuver", "distance", "cue",
        "road", "street", "direction", "description"
    )

    /** 読まない項目（操作のボタン・画像・識別子など） */
    private val SKIP_KEYS = listOf(
        "action", "intent", "icon", "bitmap", "image", "picture", "uri", "color", "channel", "template", "class", "package",
        "person", "people", "sender", "importance", "group", "sort", "remote", "substname", "progress", "chronometer",
        "showwhen", "appinfo", "_id", "res_id", "background", "gravity", "dismissal", "bridge", "custom"
    )

    /** 独自の表示（RemoteViews）を、このアプリの中で組み立てて、見えている文字を集める。組み立てられなければその理由 */
    private fun collect(ctx: Context, pkg: String, rv: RemoteViews, out: MutableList<Pair<String, String>>): String? {
        return try {
            val pctx = ctx.createPackageContext(pkg, Context.CONTEXT_IGNORE_SECURITY)
            val root = rv.apply(pctx, FrameLayout(pctx))
            walk(root, pctx, out)
            null
        } catch (t: Throwable) {
            Log.i(TAG, "remote views: ${t.javaClass.simpleName} ${t.message}")
            "${t.javaClass.simpleName}${t.message?.let { " " + it.take(60) } ?: ""}"
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

package com.example.spectrum

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.audiofx.Visualizer
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Visualizer で再生音を解析してバンド値(0..1)を配信する。
 *
 * 解析する音声:
 *  1. 音楽アプリが公開したオーディオセッション(イコライザー用の通知で受け取る)
 *     → Android Auto 接続中でも、そのアプリの音を直接解析できる
 *  2. DUMP 権限(Shizuku・パソコンで許可)があれば、Android の内部情報から読み取った番号
 *  3. 番号を知らせないアプリ(Amazon Music など)で無音が続くときは、音が出ている番号を自動で探す
 *     ([SessionProber]。Shizuku は不要)
 *  4. どれも無いときは端末全体の出力音(セッション 0)
 * 必要な権限は RECORD_AUDIO のみ。
 *
 * 止まらない・カクつかないための工夫:
 *  - 解析を止めて再開するとき、前の解析スレッドが Visualizer を放してから新しく付ける
 *    (同じ音声に 2 つ付くと、後から付けた方はデータを読めない)。画面のスレッドは待たせない
 *  - データを読めない状態(他のアプリに解析を取られた・音声の経路が変わった)が続いたら、付け直す。
 *    読めない間はバーを下げる(止まったように見せない)
 *  - 重い dumpsys の読み取り(DUMP 権限があるとき)は別スレッドで行い、解析を止めない
 */
object SpectrumEngine {
    /**
     * 解析する帯の数（v2.13 で 24 → 48）。横画面の LED（約 40〜50 本）で、1 本ごとに別の帯の値になるように。
     * なめらかなバー・ミラー・ライン・ドット・メーターは、SpectrumPainter が 2 本ずつまとめて 24 本で描く（見た目はこれまでどおり）
     */
    const val BANDS = 48

    enum class Status { STOPPED, NO_PERMISSION, RUNNING, UNSUPPORTED }

    @Volatile var status = Status.STOPPED
        private set

    /** いま解析しているセッション番号(診断用。-1 は未接続) */
    @Volatile var currentSession = -1
        private set

    val listeners = CopyOnWriteArraySet<(FloatArray) -> Unit>()

    /** 音楽アプリから通知されたオーディオセッション(パッケージ名 → セッション番号) */
    val appSessions = ConcurrentHashMap<String, Int>()

    /** 番号を知らせないアプリについて、自動で探して見つけた番号(パッケージ名 → セッション番号) */
    val probedSessions = ConcurrentHashMap<String, Int>()
    private const val PROBE_PREFIX = "probe:"
    /** 探す処理を次に行ってよい時刻(パッケージごと。見つからなければ間隔をあける) */
    private val probeNotBefore = ConcurrentHashMap<String, Long>()
    private val probeFailures = ConcurrentHashMap<String, Int>()
    /** 再生中なのに無音と判断するまで(ms) */
    private const val SILENT_PROBE_MS = 2500L
    @Volatile private var lastOpenedPkg: String? = null

    // ---- 感度調整用の値 ----
    /** これ未満の振幅は無音とみなす(8bit量子化ノイズ対策) */
    private const val GATE = 2.5f
    /** 高音域の補正(dB/オクターブ)。音楽は高音ほど小さいので持ち上げる */
    private const val TILT_DB_PER_OCT = 4f
    /**
     * 高音の持ち上げの上限(dB。v2.11)。上限が無いと 16kHz で +16 dB になり、8bit の FFT の細かさが足りないために出る
     * 雑音まで持ち上がって、右端の数本が同じ高さで上に張り付いていた
     */
    private const val TILT_MAX_DB = 9f
    /** FFT の 1 本ごとの強さ(re²+im²)から引く雑音の分(v2.11)。値が ±1 程度しかない本は雑音とみなして数えない */
    private const val QUANT_NOISE_POW = 2f
    /**
     * 帯の強さの下限(v2.13。FFT の 1 目盛りの強さ)。v2.12 までは 1e-6(-60 dB)まで下がったので、音の小さい高音の帯は
     * 「ハイハットが鳴ると +70 dB」のように 0 と天井を行き来して、右端が上に張り付いて見えた(利用者の録画)
     */
    private const val FLOOR_POW = 1f
    /** [FLOOR_POW] の dB(0 dB) */
    private const val FLOOR_DB = 0f
    /**
     * 床からこれだけ上(dB)で、帯の高さを 0 → 1 倍にする(v2.13)。床にいる帯(その音域に音が無い)は、
     * 床の値のままだと中ほどの高さで止まって見え、隣の帯も同じ高さで並んでしまうので、消す
     */
    private const val GATE_DB = 6f

    // ---- 曲の中の強弱(静かな部分・盛り上がる部分)の出し方 ----
    /**
     * 曲の音量の基準(直近の大きい音)から何 dB 下までを強弱として表すか。
     * 小さくすると静かな部分がより低く、メリハリが強くなる。
     */
    private const val LOUD_RANGE_DB = 18f
    /**
     * 基準が下がる速さ(dB/秒)。小さいほど、静かな部分が長く続いても低いまま(強弱が残る)。
     * 端末やカーナビの音量を変えたときは、この速さで自動的に合わせ直す。
     */
    private const val LOUD_RELEASE_DB_PER_SEC = 1.0f
    /** いちばん静かな部分でのバーの高さの割合(0..1)。0 に近いほど静かな部分でほぼ動かない */
    private const val LOUD_MIN = 0.35f
    /**
     * 曲の音量の基準からこれ以上小さい音は無音とみなす(dB)。
     * スマホの音量によらず「音が出ていれば動く」よう、絶対値ではなく基準からの差で判定する。
     */
    private const val SILENCE_BELOW_REF_DB = 60f
    /** これより小さい音(dBFS)は完全な無音(音声データが 0) */
    private const val DIGITAL_SILENCE_DB = -95f

    // ---- 高さと動き(v2.10) ----
    // v2.9 は、なめらかにするために帯の強さを時間でならしすぎて、バーが上の方に張り付いたまま、ほとんど動かなかった。
    // v2.10 は、ならしは FFT のばらつきを抑える程度にして、高さを「その帯の最近の平均からどれだけ大きいか」で付ける
    // (拍で上へ、拍の合間で下へ。ずっと鳴っている音は中ほどに落ち着く)。下がる速さは画面(PlayerRenderer)で一定にする
    /** 帯ごとの強さ(dB)を時間でならす割合(25ms ごとの読み取り 1 回あたり。上がる・下がる) */
    private const val SMOOTH_ATTACK = 0.8f
    private const val SMOOTH_RELEASE = 0.6f
    /** 隣の帯とならす割合(両隣それぞれ)。周波数の山の形がつながる */
    private const val SMOOTH_NEIGHBOR = 0.15f
    /** 帯ごとの「最近の平均」を取る長さ(時定数・秒)。v2.12 で 1.0 → 0.6(鳴り続ける音が早く中ほどへ戻り、動きが速く見える) */
    private const val BAND_AVG_SEC = 0.6f
    /**
     * 音が大きくなったときに平均が追いつく速さ(時定数・秒。v2.13)。静かなところから急に大きくなったとき、
     * 平均が遅れて 1 秒近く天井に張り付いていた(利用者の録画の左端)ので、上がるときだけ速く追いつく
     */
    private const val BAND_AVG_UP_SEC = 0.25f
    /** 全体の基準(最近いちばん大きい帯)が下がる速さ(dB/秒)。v2.12 で 6 → 10 */
    private const val TOP_RELEASE_DB_PER_SEC = 10f
    /** 周波数ごとの形(平均と基準の差)を縮める割合。小さいほど帯ごとの高さの差が小さい(v2.13 で 0.5 → 0.35) */
    private const val SPEC_K = 0.35f
    /**
     * 平均からの差(拍・音の立ち上がり)を広げる割合。大きいほどメリハリが強い(v2.12 で 2.0 → 2.4、v2.13 で 3.0)。
     * v2.13 は下の幅([SHOW_BELOW_DB])を広げて 1 dB あたりの高さが 2 割ほど小さくなったので、その分ここを大きくして、
     * 拍の振れ幅・上がる速さを v2.12 と同じくらいに保った
     */
    private const val DYN_K = 3.0f
    /** 基準より何 dB 下までを表すか(0)と、基準より何 dB 上で一番上になるか(v2.13 で下は 20 → 32) */
    private const val SHOW_BELOW_DB = 32f
    private const val SHOW_ABOVE_DB = 10f
    /**
     * 写した位置が 1 のときの高さ(v2.12)。1 を超えた分(とても強い拍。基準より [TOP_EXT_DB] 以上)だけで残りの上の 2 割を使う。
     * v2.11 までは画面の伸び(横画面 1.25)で天井に届かせていて、横画面では上に張り付いて見えた。v2.12 から伸びはどの画面も 1.0
     */
    private const val TOP_LEVEL = 0.88f
    private const val TOP_EXT_DB = 12f
    /** 曲の強弱(loudness)をならす割合(読み取り 1 回あたり。上がる・下がる) */
    private const val LOUD_ATTACK = 0.6f
    private const val LOUD_RELEASE = 0.25f
    /** スマホの音量の変化を確認する間隔(ms) */
    private const val VOLUME_CHECK_MS = 300L

    /** いまの曲の相対的な音量(0..1、測れない端末は -1)。診断画面で確認用 */
    @Volatile var loudness = 0f
        private set

    private var users = 0
    private var thread: Thread? = null
    @Volatile private var running = false
    /** 解析スレッドの世代(止めるたびに進める。古いスレッドは自分の世代でなくなったら終わる) */
    @Volatile private var generation = 0
    /** dumpsys の読み取り(重いので別スレッド) */
    private val scanExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "spectrum-scan").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private val scanning = AtomicBoolean(false)
    @Volatile private var lastScanDone = 0L

    private const val PREFS = "spectrum_audio_sessions"
    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var appContext: Context? = null

    /** dumpsys audio から読み取った再生中アプリの音声(パッケージ名 → 番号) */
    @Volatile private var scannedSessions: Map<String, Int> = emptyMap()
    /** 読み取りの間隔(ms) */
    private const val SCAN_MS = 2000L

    /**
     * 保存しておいた音声セッション番号を読み込む。
     * Android Auto の「終了」などでアプリが止まっても、再度開いたときにすぐ音楽アプリの音を解析できる。
     */
    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        appContext = context.applicationContext
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        for ((k, v) in p.all) {
            if (k == "_last") continue
            val id = (v as? Int) ?: continue
            if (k.startsWith(PROBE_PREFIX)) probedSessions.putIfAbsent(k.removePrefix(PROBE_PREFIX), id)
            else appSessions.putIfAbsent(k, id)
        }
        if (lastOpenedPkg == null) lastOpenedPkg = p.getString("_last", null)
    }

    fun onAudioSessionOpened(pkg: String, session: Int) {
        if (session <= 0 || pkg.isEmpty() || pkg == "_last" || pkg.startsWith(PROBE_PREFIX)) return
        appSessions[pkg] = session
        lastOpenedPkg = pkg
        prefs?.edit()?.putInt(pkg, session)?.putString("_last", pkg)?.apply()
    }

    fun onAudioSessionClosed(pkg: String, session: Int) {
        if (appSessions[pkg] == session) {
            appSessions.remove(pkg)
            prefs?.edit()?.remove(pkg)?.apply()
        }
    }

    /** 画面表示側が使い始めるときに呼ぶ */
    @Synchronized
    fun acquire(context: Context) {
        init(context)
        users++
        start(context)
    }

    @Synchronized
    fun release() {
        users = max(0, users - 1)
        if (users == 0) stop()
    }

    /** 権限が後から許可されたときの再試行用 */
    @Synchronized
    fun retry(context: Context) {
        if (users > 0 && !running) start(context)
    }

    private fun start(context: Context) {
        if (running) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            status = Status.NO_PERMISSION
            return
        }
        running = true
        status = Status.RUNNING
        val gen = ++generation
        val prev = thread
        thread = Thread({
            // 前の解析スレッドが Visualizer を放すのを待ってから始める(画面のスレッドでは待たない)
            try {
                prev?.join(2000)
            } catch (_: InterruptedException) {
            }
            loop(gen)
        }, "spectrum-visualizer").also { it.start() }
    }

    private fun stop() {
        running = false
        generation++
        currentSession = -1
        if (status == Status.RUNNING) status = Status.STOPPED
        val zero = FloatArray(BANDS)
        for (l in listeners) l(zero)
    }

    /** この世代の解析スレッドが動き続けてよいか */
    private fun alive(gen: Int): Boolean = running && gen == generation

    /** いま曲情報を出しているアプリ */
    private fun currentPackage(): String = NowPlaying.controller?.packageName ?: NowPlaying.sourcePackage

    /**
     * 解析したいセッション:
     *  再生中アプリが知らせた番号 → 再生中アプリの読み取った番号 → 曲情報に添えられた番号
     *  → 自動で探して見つけた番号 → 最後に知らされた番号 → 読み取った中の再生中の番号 → 端末全体(0)
     */
    private fun desiredSession(pkg: String): Int {
        if (pkg.isNotEmpty()) {
            appSessions[pkg]?.let { return it }
            scannedSessions[pkg]?.let { return it }
            extrasSession?.let { return it }
            probedSessions[pkg]?.let { return it }
        }
        lastOpenedPkg?.let { p -> appSessions[p]?.let { return it } }
        scannedSessions.values.firstOrNull()?.let { return it }
        return 0
    }

    /** 曲情報に添えられた番号(5 秒ごとに確認) */
    @Volatile private var extrasSession: Int? = null
    private var extrasCheckedAt = 0L
    private var extrasPkg = ""

    private fun refreshExtrasSession(pkg: String, now: Long) {
        if (pkg == extrasPkg && now - extrasCheckedAt < 5000L) return
        extrasPkg = pkg
        extrasCheckedAt = now
        extrasSession = sessionFromExtras()
    }

    /** 一部のアプリはメディアセッションの付加情報に音声の番号を入れている */
    private fun sessionFromExtras(): Int? {
        val c = NowPlaying.controller ?: return null
        val bundles = try {
            listOfNotNull(c.extras, c.playbackState?.extras)
        } catch (e: Exception) {
            return null
        }
        for (b in bundles) {
            for (k in EXTRA_SESSION_KEYS) {
                val v = try {
                    b.getInt(k, 0)
                } catch (e: Exception) {
                    0
                }
                if (v > 0) return v
            }
        }
        return null
    }

    private val EXTRA_SESSION_KEYS = listOf(
        "android.media.extra.AUDIO_SESSION", "audio_session_id", "audioSessionId", "AUDIO_SESSION_ID"
    )

    /**
     * dumpsys audio から再生中の音声の番号を読み取る(DUMP 権限があるときだけ)。
     * 重い処理(1〜3 秒かかることがある)なので別スレッドで行い、解析は止めない。
     * 番号が分からない・鳴っていないときは 2 秒ごと、分かっていて鳴っている間は 30 秒ごと。
     */
    private fun scanIfNeeded(pkg: String, silentWhilePlaying: Boolean, now: Long) {
        val c = appContext ?: return
        if (!SessionScanner.hasPermission(c) && !ShizukuHelper.isRunning()) {
            scannedSessions = emptyMap()
            return
        }
        if (pkg.isNotEmpty() && appSessions.containsKey(pkg)) return   // アプリが知らせてくれている
        val since = now - lastScanDone
        val known = pkg.isNotEmpty() && scannedSessions.containsKey(pkg)
        if (known && !silentWhilePlaying && since < 30_000L) return
        if (pkg.isEmpty() && since < 10_000L) return
        if (!scanning.compareAndSet(false, true)) return
        scanExecutor.execute {
            try {
                scannedSessions = SessionScanner.scan(c)
            } catch (_: Exception) {
            } finally {
                lastScanDone = SystemClock.uptimeMillis()
                scanning.set(false)
            }
        }
    }

    /** 自動で探した番号が鳴らなくなった(アプリが再生の仕組みを作り直した)ので忘れる */
    private fun forgetProbed(pkg: String) {
        probedSessions.remove(pkg)
        prefs?.edit()?.remove(PROBE_PREFIX + pkg)?.apply()
    }

    /**
     * 番号を知らせないアプリで無音が続くとき、音が出ている番号を別スレッドで探す。
     * 見つからなければ 30 秒 → 1 分 → 2 分 → 5 分と間隔をあける(電池のため)。
     */
    private fun maybeProbe(pkg: String, now: Long, gen: Int) {
        val c = appContext ?: return
        if (SessionProber.running) return
        if ((probeNotBefore[pkg] ?: 0L) > now) return
        // DUMP 権限で読み取れるなら、そちらが確実
        if (SessionScanner.hasPermission(c) || ShizukuHelper.isRunning()) return
        probeNotBefore[pkg] = now + 30_000L
        val exclude = HashSet<Int>()
        for ((p, id) in appSessions) if (p != pkg) exclude.add(id)
        for ((p, id) in probedSessions) if (p != pkg) exclude.add(id)
        currentSession.takeIf { it > 0 }?.let { exclude.add(it) }
        Thread({
            val found = try {
                SessionProber.probe(c, exclude) { alive(gen) && NowPlaying.isPlaying && currentPackage() == pkg }
            } catch (e: Exception) {
                null
            }
            val t = SystemClock.uptimeMillis()
            if (found != null) {
                probedSessions[pkg] = found
                prefs?.edit()?.putInt(PROBE_PREFIX + pkg, found)?.apply()
                probeFailures.remove(pkg)
                probeNotBefore[pkg] = t + 20_000L
            } else {
                val n = (probeFailures[pkg] ?: 0) + 1
                probeFailures[pkg] = n
                probeNotBefore[pkg] = t + PROBE_BACKOFF_MS[minOf(n - 1, PROBE_BACKOFF_MS.size - 1)]
            }
        }, "spectrum-probe").apply { isDaemon = true }.start()
    }

    private val PROBE_BACKOFF_MS = longArrayOf(30_000L, 60_000L, 120_000L, 300_000L)

    private fun open(session: Int): Analyzer? = try {
        val v = Visualizer(session)
        v.enabled = false
        v.captureSize = Visualizer.getCaptureSizeRange()[1]   // 通常 1024
        v.scalingMode = Visualizer.SCALING_MODE_NORMALIZED
        // 曲の音量(RMS)を測る。周波数の形は正規化したデータ、強弱はこの音量で付ける
        try { v.measurementMode = Visualizer.MEASUREMENT_MODE_PEAK_RMS } catch (_: Exception) { }
        v.enabled = true
        Analyzer(v, session)
    } catch (e: Exception) {
        null
    }

    private fun loop(gen: Int) {
        var a: Analyzer? = null
        var lastCheck = 0L
        var lastScan = 0L
        var lastVolCheck = 0L
        var lastVolume = -1
        /** 続けてデータを読めなかった回数と、最後に付け直した時刻 */
        var failures = 0
        var lastReopen = 0L
        val zero = FloatArray(BANDS)
        val am = appContext?.getSystemService(AudioManager::class.java)
        try {
            while (alive(gen)) {
                val now = SystemClock.uptimeMillis()
                val pkg = currentPackage()
                // 再生中なのに、いま解析している音声が鳴っていない(番号が違う・端末全体の音に出てこない)
                val cur = a
                val silentWhilePlaying = cur != null && NowPlaying.isPlaying &&
                    now - cur.openedAt > SILENT_PROBE_MS && now - cur.lastAudibleAt > SILENT_PROBE_MS
                // スマホの音量を変えたら、強弱の基準をその場で今の音に合わせ直す
                // (音量を下げてもバーが小さくならず、音が出ていればいつも同じように動く)
                if (am != null && now - lastVolCheck >= VOLUME_CHECK_MS) {
                    lastVolCheck = now
                    val vol = try { am.getStreamVolume(AudioManager.STREAM_MUSIC) } catch (_: Exception) { -1 }
                    if (vol != lastVolume) {
                        if (lastVolume >= 0) a?.resetLoudness()
                        lastVolume = vol
                    }
                }
                if (now - lastScan >= SCAN_MS) {
                    lastScan = now
                    try { scanIfNeeded(pkg, silentWhilePlaying, now) } catch (_: Exception) { }
                }
                if (a == null || now - lastCheck >= 1000) {
                    lastCheck = now
                    if (pkg.isNotEmpty() && !appSessions.containsKey(pkg) && !scannedSessions.containsKey(pkg)) {
                        refreshExtrasSession(pkg, now)
                    } else {
                        extrasSession = null
                    }
                    if (silentWhilePlaying && pkg.isNotEmpty() && !appSessions.containsKey(pkg)) {
                        // 自動で探した番号が鳴らなくなったら忘れて、探し直す
                        if (probedSessions[pkg] == cur!!.session && now - cur.lastAudibleAt > 6000L) forgetProbed(pkg)
                        if (!scannedSessions.containsKey(pkg) && probedSessions[pkg] == null) maybeProbe(pkg, now, gen)
                    }
                    val want = desiredSession(pkg)
                    if (a == null || a.session != want) {
                        val next = open(want) ?: (if (want != 0 && a == null) open(0) else null)
                        if (next != null) {
                            a?.release()
                            a = next
                            failures = 0
                        }
                    }
                    currentSession = a?.session ?: -1
                    status = if (a != null) Status.RUNNING else Status.UNSUPPORTED
                }
                val out = a?.read()
                if (out != null) {
                    failures = 0
                    for (l in listeners) l(out)
                } else if (a != null) {
                    failures++
                    // 読めない状態が続く(他のアプリに解析を取られた・音声の経路が変わった など):
                    // バーを下げ(止まって見えないように)、付け直す(2 秒に 1 回まで)
                    if (failures == 8) for (l in listeners) l(zero)
                    if (failures >= 16 && now - lastReopen >= 2000L) {
                        lastReopen = now
                        val session = a.session
                        a.release()
                        a = open(session) ?: open(desiredSession(pkg))
                        failures = 0
                        lastCheck = 0L
                    }
                }
                try { Thread.sleep(25) } catch (_: InterruptedException) { break }
            }
        } finally {
            a?.release()
        }
    }

    /** 1 つの Visualizer とその解析用バッファ */
    private class Analyzer(val v: Visualizer, val session: Int) {
        /** 開いた時刻と、最後に音が出ていた時刻(SystemClock.uptimeMillis) */
        val openedAt = SystemClock.uptimeMillis()
        var lastAudibleAt = 0L
            private set
        private val size = v.captureSize
        private val fft = ByteArray(size)
        private val bins = size / 2
        private val edges: IntArray
        private val tilt: FloatArray
        private val db = FloatArray(BANDS)
        /** 隣の帯とならした値(作業用)と、時間でならした値(v2.9) */
        private val dbF = FloatArray(BANDS)
        private val dbS = FloatArray(BANDS)
        /** 帯ごとの最近の平均(dB) */
        private val dbAvg = FloatArray(BANDS)
        private var smoothed = false
        /** 全体の基準(ならした値の最大。すぐ上がり、ゆっくり下がる) */
        private var topRef = -100f
        /** ならした強弱(0..1)。-1 = まだ無い */
        private var loudS = -1f
        private val out = FloatArray(BANDS)
        private var loudRef = -100f   // 曲の音量の基準(直近の大きい音)

        /** 強弱の基準を捨てて、次に届く音を新しい基準にする(音量変更時) */
        fun resetLoudness() {
            loudRef = -100f
        }
        private var lastTime = SystemClock.uptimeMillis()
        private val peakRms = Visualizer.MeasurementPeakRms()
        private var rmsSupported = true

        /** 再生中の音の大きさ(dBFS)。測れない端末では null */
        private fun readRmsDb(): Float? {
            if (!rmsSupported) return null
            return try {
                if (v.getMeasurementPeakRms(peakRms) == Visualizer.SUCCESS) {
                    peakRms.mRms / 100f            // mB → dB
                } else {
                    rmsSupported = false
                    null
                }
            } catch (_: Exception) {
                rmsSupported = false
                null
            }
        }

        init {
            val sampleRate = v.samplingRate / 1000f          // mHz → Hz
            val binHz = sampleRate / size
            val fMin = 40f
            val fMax = min(16000f, sampleRate / 2f)
            edges = IntArray(BANDS + 1) { i ->
                val f = fMin * (fMax / fMin).pow(i.toFloat() / BANDS)
                (f / binHz).toInt().coerceIn(1, bins - 1)
            }
            // 低い帯は FFT の 1 本(約 47Hz)より狭いので、いくつもの帯が同じ 1 本を読んで同じ高さで動いていた(左端の数本)。
            // v2.13 から、どの帯も前の帯より後ろの本から読む(低いところは 1 帯 = 1 本。周波数の目盛りは少し高い方へずれる)
            for (i in 1..BANDS) {
                if (edges[i] <= edges[i - 1]) edges[i] = min(edges[i - 1] + 1, bins - 1)
            }
            tilt = FloatArray(BANDS) { b ->
                val fc = fMin * (fMax / fMin).pow((b + 0.5f) / BANDS)
                min(TILT_MAX_DB, TILT_DB_PER_OCT * (ln(fc / 1000f) / ln(2f)))
            }
        }

        fun read(): FloatArray? {
            val ok = try { v.getFft(fft) == Visualizer.SUCCESS } catch (_: Exception) { false }
            if (!ok) return null
            var frameMaxMag = 0f
            var frameMaxDb = -100f
            for (b in 0 until BANDS) {
                val lo = edges[b]
                val hi = max(edges[b + 1], lo + 1)
                var sum = 0f
                var cnt = 0
                for (k in lo until min(hi, bins)) {
                    val re = fft[2 * k].toFloat()
                    val im = fft[2 * k + 1].toFloat()
                    val p = re * re + im * im
                    sum += max(0f, p - QUANT_NOISE_POW)
                    cnt++
                    val m = sqrt(p)
                    if (m > frameMaxMag) frameMaxMag = m
                }
                val meanPow = if (cnt > 0) sum / cnt else 0f
                db[b] = 10f * log10(max(meanPow, FLOOR_POW)) + tilt[b]
                if (db[b] > frameMaxDb) frameMaxDb = db[b]
            }
            val now = SystemClock.uptimeMillis()
            val dt = ((now - lastTime) / 1000f).coerceIn(0f, 0.5f)
            lastTime = now

            val rmsDb = readRmsDb()
            val silent = frameMaxMag < GATE || (rmsDb != null &&
                (rmsDb < DIGITAL_SILENCE_DB || rmsDb < loudRef - SILENCE_BELOW_REF_DB))
            if (!silent) lastAudibleAt = now
            if (silent) {
                out.fill(0f)                       // ほぼ無音
                loudness = 0f
                // ならした値は捨てる(次に鳴り始めたら、その音から始める)
                smoothed = false
                topRef = -100f
                loudS = -1f
                return out
            }
            // 帯の強さをならす(隣の帯・時間)。frameMaxDb はならした値の最大
            val k = (dt / 0.025f).coerceIn(0.2f, 4f)
            val att = 1f - (1f - SMOOTH_ATTACK).pow(k)
            val rel = 1f - (1f - SMOOTH_RELEASE).pow(k)
            for (b in 0 until BANDS) {
                val l = db[if (b > 0) b - 1 else b]
                val r = db[if (b < BANDS - 1) b + 1 else b]
                dbF[b] = db[b] * (1f - 2f * SMOOTH_NEIGHBOR) + (l + r) * SMOOTH_NEIGHBOR
            }
            val avgA = min(1f, dt / BAND_AVG_SEC)
            val avgUpA = min(1f, dt / BAND_AVG_UP_SEC)
            frameMaxDb = -100f
            for (b in 0 until BANDS) {
                dbS[b] = when {
                    !smoothed -> dbF[b]
                    dbF[b] > dbS[b] -> dbS[b] + (dbF[b] - dbS[b]) * att
                    else -> dbS[b] + (dbF[b] - dbS[b]) * rel
                }
                dbAvg[b] = if (!smoothed) dbS[b] else dbAvg[b] + (dbS[b] - dbAvg[b]) * (if (dbS[b] > dbAvg[b]) avgUpA else avgA)
                if (dbS[b] > frameMaxDb) frameMaxDb = dbS[b]
            }
            smoothed = true
            topRef = if (frameMaxDb > topRef) frameMaxDb else max(frameMaxDb, topRef - TOP_RELEASE_DB_PER_SEC * dt)
            // 曲の強弱(音量が測れる端末だけ): 静かな部分は全体を低く
            val loudFactor = if (rmsDb != null) {
                // 曲の音量の基準: 大きい音には即追従、静かになってもゆっくりしか下げない
                loudRef = if (rmsDb > loudRef) rmsDb else loudRef - LOUD_RELEASE_DB_PER_SEC * dt
                val loudNow = ((rmsDb - (loudRef - LOUD_RANGE_DB)) / LOUD_RANGE_DB).coerceIn(0f, 1f)
                val la = 1f - (1f - LOUD_ATTACK).pow(k)
                val lr = 1f - (1f - LOUD_RELEASE).pow(k)
                loudS = if (loudS < 0f) loudNow else loudS + (loudNow - loudS) * (if (loudNow > loudS) la else lr)
                loudness = loudS
                LOUD_MIN + (1f - LOUD_MIN) * loudS
            } else {
                loudness = -1f
                1f
            }
            // 高さ: 周波数ごとの形(縮めて)＋最近の平均からの差(広げて)を、基準の 32 dB 下〜10 dB 上に写す。
            // 10 dB 上を超えた分は、さらに 12 dB で残りの上の部分(天井まで)を使う(天井に届くのはとても強い拍だけ)
            for (b in 0 until BANDS) {
                val p = (dbAvg[b] - topRef) * SPEC_K + (dbS[b] - dbAvg[b]) * DYN_K
                val x = ((p + SHOW_BELOW_DB) / (SHOW_BELOW_DB + SHOW_ABOVE_DB)).coerceIn(0f, 1f)
                val over = ((p - SHOW_ABOVE_DB) / TOP_EXT_DB).coerceIn(0f, 1f)
                // 床にいる(音の無い)帯は消す。床から GATE_DB 上で元の高さ
                val gate = ((dbS[b] - tilt[b] - FLOOR_DB) / GATE_DB).coerceIn(0f, 1f)
                out[b] = (x * TOP_LEVEL + over * (1f - TOP_LEVEL)) * loudFactor * gate
            }
            // 受け取る側(画面)はその場で写すので、配列は使い回す(毎回の確保をしない)
            return out
        }

        fun release() {
            try { v.enabled = false } catch (_: Exception) { }
            try { v.release() } catch (_: Exception) { }
        }
    }
}

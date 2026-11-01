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
 */
object SpectrumEngine {
    const val BANDS = 24

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
    /** (音量が測れない端末用)表示するダイナミックレンジ(dB) */
    private const val RANGE_DB = 37f
    /** 最大音量時のバーの高さ(0..1)。1 未満にすると天井に張り付きにくい */
    private const val HEADROOM = 0.7f
    /** (音量が測れない端末用)基準レベルが下がる速さ(dB/フレーム) */
    private const val REF_RELEASE_DB = 0.08f
    /** これ未満の振幅は無音とみなす(8bit量子化ノイズ対策) */
    private const val GATE = 2.5f
    /** 高音域の補正(dB/オクターブ)。音楽は高音ほど小さいので持ち上げる */
    private const val TILT_DB_PER_OCT = 4f

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
    private const val LOUD_MIN = 0.15f
    /** 周波数ごとのバランス(形)を、その瞬間の一番大きい帯域から何 dB 下まで表すか */
    private const val SHAPE_RANGE_DB = 30f
    /**
     * 曲の音量の基準からこれ以上小さい音は無音とみなす(dB)。
     * スマホの音量によらず「音が出ていれば動く」よう、絶対値ではなく基準からの差で判定する。
     */
    private const val SILENCE_BELOW_REF_DB = 60f
    /** これより小さい音(dBFS)は完全な無音(音声データが 0) */
    private const val DIGITAL_SILENCE_DB = -95f
    /** スマホの音量の変化を確認する間隔(ms) */
    private const val VOLUME_CHECK_MS = 300L

    /** いまの曲の相対的な音量(0..1、測れない端末は -1)。診断画面で確認用 */
    @Volatile var loudness = 0f
        private set

    private var users = 0
    private var thread: Thread? = null
    @Volatile private var running = false

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
        thread = Thread({ loop() }, "spectrum-visualizer").also { it.start() }
    }

    private fun stop() {
        running = false
        thread?.join(500)
        thread = null
        currentSession = -1
        if (status == Status.RUNNING) status = Status.STOPPED
        val zero = FloatArray(BANDS)
        for (l in listeners) l(zero)
    }

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
            sessionFromExtras()?.let { return it }
            probedSessions[pkg]?.let { return it }
        }
        lastOpenedPkg?.let { p -> appSessions[p]?.let { return it } }
        scannedSessions.values.firstOrNull()?.let { return it }
        return 0
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
     * 重い処理なので、番号が分からない・鳴っていないときは 2 秒ごと、分かっていて鳴っている間は 30 秒ごと。
     */
    private fun scanIfNeeded(pkg: String, silentWhilePlaying: Boolean, sinceLastScanMs: Long): Boolean {
        val c = appContext ?: return false
        if (!SessionScanner.hasPermission(c) && !ShizukuHelper.isRunning()) {
            scannedSessions = emptyMap()
            return false
        }
        if (pkg.isNotEmpty() && appSessions.containsKey(pkg)) return false   // アプリが知らせてくれている
        val known = pkg.isNotEmpty() && scannedSessions.containsKey(pkg)
        if (known && !silentWhilePlaying && sinceLastScanMs < 30_000L) return false
        if (pkg.isEmpty() && sinceLastScanMs < 10_000L) return false
        scannedSessions = SessionScanner.scan(c)
        return true
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
    private fun maybeProbe(pkg: String, now: Long) {
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
                SessionProber.probe(c, exclude) { running && NowPlaying.isPlaying && currentPackage() == pkg }
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

    private fun loop() {
        var a: Analyzer? = null
        var lastCheck = 0L
        var lastScan = 0L
        var lastScanDone = 0L
        var lastVolCheck = 0L
        var lastVolume = -1
        val am = appContext?.getSystemService(AudioManager::class.java)
        while (running) {
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
                try {
                    if (scanIfNeeded(pkg, silentWhilePlaying, now - lastScanDone)) lastScanDone = now
                } catch (_: Exception) { }
            }
            if (a == null || now - lastCheck >= 1000) {
                lastCheck = now
                if (silentWhilePlaying && cur != null && pkg.isNotEmpty() && !appSessions.containsKey(pkg)) {
                    // 自動で探した番号が鳴らなくなったら忘れて、探し直す
                    if (probedSessions[pkg] == cur.session && now - cur.lastAudibleAt > 6000L) forgetProbed(pkg)
                    if (!scannedSessions.containsKey(pkg) && probedSessions[pkg] == null) maybeProbe(pkg, now)
                }
                val want = desiredSession(pkg)
                if (a == null || a.session != want) {
                    val next = open(want) ?: (if (want != 0 && a == null) open(0) else null)
                    if (next != null) {
                        a?.release()
                        a = next
                    }
                }
                currentSession = a?.session ?: -1
                status = if (a != null) Status.RUNNING else Status.UNSUPPORTED
            }
            val out = a?.read()
            if (out != null) for (l in listeners) l(out)
            try { Thread.sleep(25) } catch (_: InterruptedException) { break }
        }
        a?.release()
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
        private val out = FloatArray(BANDS)
        private var ref = -100f   // 自動追従する基準レベル(直近の最大値)。音量が測れない端末用
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
            tilt = FloatArray(BANDS) { b ->
                val fc = fMin * (fMax / fMin).pow((b + 0.5f) / BANDS)
                TILT_DB_PER_OCT * (ln(fc / 1000f) / ln(2f))
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
                    sum += p
                    cnt++
                    val m = sqrt(p)
                    if (m > frameMaxMag) frameMaxMag = m
                }
                val meanPow = if (cnt > 0) sum / cnt else 0f
                db[b] = 10f * log10(meanPow + 1e-6f) + tilt[b]
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
            } else if (rmsDb != null) {
                // ---- 曲の強弱を反映 ----
                // 曲の音量の基準: 大きい音には即追従、静かになってもゆっくりしか下げない
                // → 静かな部分は低く、盛り上がる部分は高く表示される
                loudRef = if (rmsDb > loudRef) rmsDb else loudRef - LOUD_RELEASE_DB_PER_SEC * dt
                val loud = ((rmsDb - (loudRef - LOUD_RANGE_DB)) / LOUD_RANGE_DB).coerceIn(0f, 1f)
                loudness = loud
                val loudFactor = LOUD_MIN + (1f - LOUD_MIN) * loud
                // 周波数ごとの形: その瞬間の一番大きい帯域を基準にする
                for (b in 0 until BANDS) {
                    val x = ((db[b] - frameMaxDb + SHAPE_RANGE_DB) / SHAPE_RANGE_DB).coerceIn(0f, 1f)
                    out[b] = x * x * HEADROOM * loudFactor
                }
            } else {
                // 音量が測れない端末: 従来の方法(基準を自動追従)
                ref = if (frameMaxDb > ref) frameMaxDb else ref - REF_RELEASE_DB
                val floor = ref - RANGE_DB
                for (b in 0 until BANDS) {
                    val x = ((db[b] - floor) / RANGE_DB).coerceIn(0f, 1f)
                    out[b] = x * x * HEADROOM      // 2乗で上の方を圧縮
                }
                loudness = -1f
            }
            return out.copyOf()
        }

        fun release() {
            try { v.enabled = false } catch (_: Exception) { }
            try { v.release() } catch (_: Exception) { }
        }
    }
}

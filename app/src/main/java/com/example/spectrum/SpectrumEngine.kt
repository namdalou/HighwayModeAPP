package com.example.spectrum

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.audiofx.Visualizer
import android.os.Process
import android.os.SystemClock
import com.example.highwaymode.core.audio.BandAnalyzer
import com.example.highwaymode.core.audio.SpectrumMapper
import com.example.highwaymode.core.audio.WaveStitcher
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import kotlin.math.max

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
 * 解析のしかた（v2.15）: Visualizer からは波形（1 回に 1024 サンプル）を約 12.5ms ごとに読み、つないで長い履歴にしてから、
 * 周波数に応じた長さの窓で自前の FFT にかける（[WaveStitcher]・[BandAnalyzer]・[SpectrumMapper]。計算は :core にあり、テストできる）。
 * v2.14 までは Visualizer の FFT（1024 サンプル・窓なし・結果は 1 バイト）を 25ms ごとに読んでいて、低音の細かさが足りず、
 * 強い音が画面の広い範囲ににじんでいた（聞こえている音と、光る場所が合わない）。
 *
 * 止まらない・カクつかないための工夫:
 *  - 解析を止めて再開するとき、前の解析スレッドが Visualizer を放してから新しく付ける
 *    (同じ音声に 2 つ付くと、後から付けた方はデータを読めない)。画面のスレッドは待たせない
 *  - データを読めない状態(他のアプリに解析を取られた・音声の経路が変わった)が続いたら、付け直す。
 *    読めない間はバーを下げる(止まったように見せない)
 *  - 重い dumpsys の読み取り(DUMP 権限があるとき)は別スレッドで行い、解析を止めない
 *  - 解析スレッドは表示用の優先度で、決まった間隔([READ_PERIOD_NS])の時刻に合わせて読む(v2.15)。
 *    読む間隔がぶれると、画面を描くたびに届いている値の数が変わって、動きがカクついて見える
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

    // 高さの付け方の数字（ならし・強弱・無音の判定など）は、:core の SpectrumMapper にある（v2.15 で移した）

    /**
     * 波形を読む間隔 [ns]（12.5ms = 1 秒に 80 回。v2.14 までは 25ms）。
     * 1 回に読める波形は約 21ms ぶんなので、半分ほど重ねて読んで、つなぐ。解析もこの間隔で行う
     * （車の画面の 30fps・スマホの 60fps のどちらでも、描くたびに新しい値が届いているように）
     */
    private const val READ_PERIOD_NS = 12_500_000L
    /** スマホの音量の変化を確認する間隔(ms) */
    private const val VOLUME_CHECK_MS = 300L

    /** いまの曲の相対的な音量(0..1、測れない端末は -1)。診断画面で確認用 */
    @Volatile var loudness = 0f
        private set

    /** 確認用(診断画面): 波形のつなぎ具合(履歴の長さ・つなぎ直した回数・読めた回数) */
    @Volatile var captureInfo = ""
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
        // 表示用の優先度(エンジンの計算などに待たされて、読む間隔がぶれないように。解析そのものは軽い)
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) } catch (_: Exception) { }
        var nextNs = System.nanoTime()
        var infoAt = 0L
        var reads = 0
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
                    reads++
                    for (l in listeners) l(out)
                } else if (a != null) {
                    failures++
                    // 読めない状態が続く(他のアプリに解析を取られた・音声の経路が変わった など):
                    // バーを下げ(止まって見えないように)、付け直す(2 秒に 1 回まで)
                    if (failures == 16) for (l in listeners) l(zero)
                    if (failures >= 32 && now - lastReopen >= 2000L) {
                        lastReopen = now
                        val session = a.session
                        a.release()
                        a = open(session) ?: open(desiredSession(pkg))
                        failures = 0
                        lastCheck = 0L
                    }
                }
                if (now - infoAt >= 1000L) {
                    captureInfo = a?.info(reads, now - infoAt) ?: ""
                    infoAt = now
                    reads = 0
                }
                // 決まった時刻に合わせて待つ(かかった時間を差し引く。大きく遅れたら、そこから数え直す)
                nextNs += READ_PERIOD_NS
                val t = System.nanoTime()
                if (t - nextNs > 4 * READ_PERIOD_NS) nextNs = t
                val wait = nextNs - t
                if (wait > 0L) LockSupport.parkNanos(wait)
                if (Thread.interrupted()) break
            }
        } finally {
            a?.release()
        }
    }

    /** 1 つの Visualizer と、その解析（波形 → つないだ履歴 → 帯の強さ → 高さ） */
    private class Analyzer(val v: Visualizer, val session: Int) {
        /** 開いた時刻と、最後に音が出ていた時刻(SystemClock.uptimeMillis) */
        val openedAt = SystemClock.uptimeMillis()
        var lastAudibleAt = 0L
            private set
        private val size = v.captureSize
        private val wave = ByteArray(size)
        private val sampleRate = (v.samplingRate / 1000f).let { if (it >= 8000f) it else 44100f }   // mHz → Hz
        private val stitcher = WaveStitcher()
        private val bands = BandAnalyzer(sampleRate, BANDS)
        private val mapper = SpectrumMapper(bands)
        private val power = FloatArray(BANDS)
        private val zeros = FloatArray(BANDS)
        /** 最後に波形が進んだ(新しい部分をつないだ)時刻と、最後に解析した時刻 [ns] */
        private var advancedAtNs = 0L
        private var mappedAtNs = 0L
        private val peakRms = Visualizer.MeasurementPeakRms()
        private var rmsSupported = true
        private var rmsDb: Float? = null
        private var tick = 0

        /** 強弱の基準を捨てて、次に届く音を新しい基準にする(音量変更時) */
        fun resetLoudness() = mapper.resetLoudness()

        /** 確認用: 波形のつなぎ具合 */
        fun info(reads: Int, ms: Long): String =
            "履歴 ${stitcher.length} サンプル・つなぎ直し ${stitcher.resets} 回・読み取り ${if (ms > 0) reads * 1000L / ms else 0} 回/秒" +
                "・${(sampleRate / 1000f).let { "%.1f".format(it) }}kHz"

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

        /** 波形を読んで履歴につなぎ、帯の値(0..1)を返す。読めなければ null */
        fun read(): FloatArray? {
            val ok = try { v.getWaveForm(wave) == Visualizer.SUCCESS } catch (_: Exception) { false }
            if (!ok) return null
            val nowNs = System.nanoTime()
            // 符号つきの値にする(Visualizer の波形は 0〜255。128 が 0)。ついでに最大値を見る
            var peak = 0
            for (i in 0 until size) {
                val sample = (wave[i].toInt() and 0xFF) - 128
                wave[i] = sample.toByte()
                val abs = if (sample < 0) -sample else sample
                if (abs > peak) peak = abs
            }
            // 前に波形が進んでからの時間ぶん、進んでいるはず(つなぐ位置を探し始める目安)
            val expected = if (advancedAtNs == 0L) 0 else ((nowNs - advancedAtNs) * 1e-9 * sampleRate).toInt()
            if (stitcher.push(wave, size, expected) != 0) advancedAtNs = nowNs
            // 曲の音量は 1 回おきに読む(約 0.4 秒の平均なので、毎回読まなくてよい)
            if (tick++ % 2 == 0) rmsDb = readRmsDb()
            if (!bands.compute(stitcher, power)) return zeros
            val dt = if (mappedAtNs == 0L) 0.0125f else ((nowNs - mappedAtNs) * 1e-9f).coerceIn(0f, 0.5f)
            mappedAtNs = nowNs
            val out = mapper.map(power, rmsDb, peak, dt)
            if (!mapper.silent) lastAudibleAt = SystemClock.uptimeMillis()
            loudness = mapper.loudness
            // 受け取る側(画面)はその場で写すので、配列は使い回す(毎回の確保をしない)
            return out
        }

        fun release() {
            try { v.enabled = false } catch (_: Exception) { }
            try { v.release() } catch (_: Exception) { }
        }
    }
}

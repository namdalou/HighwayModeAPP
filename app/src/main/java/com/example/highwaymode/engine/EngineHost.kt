package com.example.highwaymode.engine

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.example.highwaymode.TrackingService
import com.example.highwaymode.alerts.AlertManager
import com.example.highwaymode.core.engine.HighwayEngine
import com.example.highwaymode.core.engine.HighwaySnapshot
import com.example.highwaymode.core.engine.PowerLevel
import com.example.highwaymode.core.engine.PowerPolicy
import com.example.highwaymode.core.graph.RoadGraph
import com.example.highwaymode.core.model.GpsFix
import com.example.highwaymode.core.osm.TileId
import com.example.highwaymode.core.traffic.TrafficQuery
import com.example.highwaymode.core.vehicle.TollCardState
import com.example.highwaymode.data.Prefs
import com.example.highwaymode.data.TileRepository
import com.example.highwaymode.data.UserCameraStore
import com.example.highwaymode.traffic.TrafficUpdater
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.cos
import kotlin.math.max

/**
 * エンジン・センサー・GPS・地図データ・渋滞情報・音声案内をまとめて動かすプロセス内シングルトン。
 * エンジンへのアクセスはすべて専用スレッド（engine handler）上で行う。
 * 画面（スマホの Activity・Android Auto の車載画面）と常駐サービスは、メインスレッドでスナップショットを受け取る。
 *
 * 電池の節約（[PowerPolicy]）: 高速道路から離れている・停車しているときは GPS の間隔をあけ、
 * センサーと処理の周期を減らす。高速道路上・高速道路の近くを走行中は、これまでどおり毎秒測位する。
 */
object EngineHost {
    private const val TAG = "EngineHost"

    fun interface SnapshotListener {
        fun onSnapshot(s: HighwaySnapshot)
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<SnapshotListener>()

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var engine: HighwayEngine? = null
    private var sensors: SensorInput? = null
    private var location: LocationInput? = null
    private var repo: TileRepository? = null
    private var prefs: Prefs? = null
    private var traffic: TrafficUpdater? = null
    private var alerts: AlertManager? = null
    private var cameraStore: UserCameraStore? = null
    private var demo: DemoDriver? = null
    private var demoMessage: String? = null
    private var repoMessage: String? = null
    private var lastPublished: HighwaySnapshot? = null
    private var lastTileRequest = 0L
    private var lastGpsCheck = 0L
    private var gpsMessage: String? = null
    private var lastProfileSave = 0L

    /** スマホの GPS・車の GPS を最後に受信した時刻（elapsedRealtimeNanos、0 = 未受信） */
    @Volatile
    var lastPhoneFixNanos = 0L
        private set

    @Volatile
    var lastVehicleFixNanos = 0L
        private set

    @Volatile
    var latest: HighwaySnapshot? = null
        private set

    /** いまの電池の節約の段階（動作状態の画面用） */
    @Volatile
    var powerLevel: PowerLevel = PowerLevel.ACTIVE
        private set

    private val power = PowerPolicy()
    private var lastPowerEval = 0L
    private var nearestM: Double? = null
    private var nearestAt = 0L
    /** 最後に動き出しを検知した時刻 [秒]（elapsedRealtime 基準） */
    private var motionAtSec = Double.NaN
    private var sensorMovingSince = 0L
    private var lastSensorCheck = 0L
    private var tickMs = 250L

    @Volatile
    var running = false
        private set

    @Volatile
    var demoRunning = false
        private set

    /** Android Auto の画面が表示中 */
    @Volatile
    var carConnected = false
        private set

    private val alertListener = SnapshotListener { s -> alerts?.onSnapshot(s) }

    private val tick = object : Runnable {
        override fun run() {
            val e = engine ?: return
            val h = handler ?: return
            val now = SystemClock.elapsedRealtimeNanos()
            if (now - lastGpsCheck > 5_000_000_000L) {
                lastGpsCheck = now
                val loc = location
                // 電池の節約で測位の間隔をあけている間は、その分だけ待ってから「受信できない」と判断する
                val staleNanos = max(20_000L, powerLevel.gpsIntervalMs + 20_000L) * 1_000_000L
                val phoneStale = lastPhoneFixNanos == 0L || now - lastPhoneFixNanos > staleNanos
                val vehicleStale = lastVehicleFixNanos == 0L || now - lastVehicleFixNanos > staleNanos
                gpsMessage = when {
                    demo != null || loc == null -> null
                    !loc.hasPermission() -> "位置情報の権限がありません"
                    !loc.gpsEnabled -> "端末の位置情報（GPS）がオフです"
                    // Android Auto だけで使っていて、スマホの GPS が裏で止められている
                    carConnected && phoneStale && vehicleStale &&
                        TrackingService.mode != TrackingService.Mode.LOCATION ->
                        "スマホの GPS を受信できません（メニュー →「動作状態」）"
                    else -> null
                }
            }
            e.dataMessage = demoMessage ?: gpsMessage ?: repoMessage
            val snap = try {
                e.onTick(now, System.currentTimeMillis())
            } catch (ex: Exception) {
                // 想定外のデータで落ちないよう、状態をリセットして継続
                Log.e(TAG, "engine tick failed", ex)
                e.forceRematch()
                e.snapshot
            }
            if (now - lastPowerEval > 2_000_000_000L) {
                lastPowerEval = now
                try {
                    evaluatePower(e, now)
                } catch (ex: Exception) {
                    Log.w(TAG, "power policy failed", ex)
                }
            }
            val tileEvery = if (powerLevel == PowerLevel.ACTIVE) 2_000_000_000L else 10_000_000_000L
            if (now - lastTileRequest > tileEvery) {
                lastTileRequest = now
                try {
                    repo?.request(e.wantedTiles())
                    e.lastFix?.let { sensors?.updateGeomagnetic(it.lat, it.lon) }
                } catch (ex: Exception) {
                    Log.e(TAG, "tile request failed", ex)
                }
            }
            if (now - lastProfileSave > 60_000_000_000L) {
                lastProfileSave = now
                prefs?.driverProfile = e.driver.serialize()
            }
            if (snap !== lastPublished) {
                lastPublished = snap
                latest = snap
                main.post { for (l in listeners) l.onSnapshot(snap) }
            }
            h.postDelayed(this, tickMs)
        }
    }

    // ------------------------------------------------------------------ 電池の節約

    /** エンジン用スレッドで呼ぶ */
    private fun evaluatePower(e: HighwayEngine, now: Long) {
        val fix = e.lastFix
        val tSec = now / 1e9
        if (fix != null && (nearestAt == 0L || now - nearestAt > 5_000_000_000L)) {
            nearestM = nearestHighwayM(e, fix.lat, fix.lon)
            nearestAt = now
        }
        val fixAgeSec = fix?.let { (now - it.timeNanos) / 1e9 } ?: Double.MAX_VALUE
        val recentFix = fixAgeSec <= max(3.0, power.level.gpsIntervalMs / 1000.0 + 5.0)
        val gpsSpeed = fix?.speedMs ?: e.lastGpsSpeedMs.takeUnless { it.isNaN() }
        val vehicleFresh = e.vehicle.speedFresh(now)
        val sensorsOn = sensors?.running == true
        // 停車中か: 車速信号 → 直近の GPS の速度（歩く程度は停車とみなす）→ センサー
        val stationary = when {
            vehicleFresh -> e.vehicle.speedMs < 0.3
            recentFix && gpsSpeed != null -> gpsSpeed < 1.5
            sensorsOn -> e.stationary
            else -> false
        }
        // 節約中にセンサーが 5 秒以上 揺れをとらえたら、走り出したか確かめる（2 分に 1 回まで）
        if (power.level != PowerLevel.ACTIVE && sensorsOn && !e.stationary && !vehicleFresh) {
            if (sensorMovingSince == 0L) {
                sensorMovingSince = now
            } else if (now - sensorMovingSince > 5_000_000_000L && now - lastSensorCheck > 120_000_000_000L) {
                lastSensorCheck = now
                motionAtSec = tSec
            }
        } else {
            sensorMovingSince = 0L
        }
        val input = PowerPolicy.Input(
            tSec = tSec, status = e.snapshot.status, demo = demo != null, nearestHighwayM = nearestM,
            accuracyM = fix?.accuracyM ?: 50.0, stationary = stationary, motionAtSec = motionAtSec
        )
        if (power.update(input)) applyPower(e, power.level)
    }

    /** 段階に合わせて GPS の間隔・センサー・処理の周期を変える（エンジン用スレッド） */
    private fun applyPower(e: HighwayEngine, level: PowerLevel) {
        Log.i(TAG, "power: ${powerLevel.name} -> ${level.name}")
        powerLevel = level
        tickMs = level.tickMs
        if (demo == null) location?.setInterval(level.gpsIntervalMs)
        val s = sensors ?: return
        if (level.sensors) {
            s.start()
        } else if (s.running) {
            s.stop()
            e.onSensorsPaused()
        }
        // 節約中は「動き出し」の検知を待つ（検知したらすぐ測位を増やす）
        if (level == PowerLevel.ACTIVE) s.disarmMotion() else s.armMotion()
    }

    /** 動き出しを検知した（エンジン用スレッド） */
    private fun onMotion() {
        val e = engine ?: return
        if (powerLevel == PowerLevel.ACTIVE) return
        val now = SystemClock.elapsedRealtimeNanos()
        motionAtSec = now / 1e9
        lastPowerEval = now
        evaluatePower(e, now)
        // 次の検知も待つ（節約中のまま）
        if (powerLevel != PowerLevel.ACTIVE) sensors?.armMotion()
    }

    /**
     * いちばん近い高速道路（ハイウェイモードの対象の道路）までの距離 [m]。
     * 周辺 3.5km の地図データがまだそろっていなければ null（不明 → 節約しない）。
     */
    private fun nearestHighwayM(e: HighwayEngine, lat: Double, lon: Double): Double? {
        val g = e.graph
        val dLat = 3500.0 / 111_000.0
        val dLon = 3500.0 / (111_000.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.2))
        val probes = arrayOf(
            doubleArrayOf(lat, lon), doubleArrayOf(lat + dLat, lon), doubleArrayOf(lat - dLat, lon),
            doubleArrayOf(lat, lon + dLon), doubleArrayOf(lat, lon - dLon)
        )
        for (p in probes) {
            val t = TileId.of(p[0], p[1])
            if (!t.inJapan) continue
            if (t !in g.loadedTiles && repo?.hasNoData(t) != true) return null
        }
        return g.nearestDistance(lat, lon, 4500.0) ?: Double.POSITIVE_INFINITY
    }

    /** メインスレッドから呼ぶ */
    @Synchronized
    fun start(context: Context) {
        if (running) return
        val ctx = context.applicationContext
        val p = Prefs(ctx)
        prefs = p
        val t = HandlerThread("highway-engine", Process.THREAD_PRIORITY_DISPLAY)
        t.start()
        thread = t
        val h = Handler(t.looper)
        handler = h
        val e = HighwayEngine()
        e.driver.restore(p.driverProfile)
        engine = e
        val r = TileRepository(ctx, p, object : TileRepository.Listener {
            override fun onGraph(graph: RoadGraph) {
                h.post {
                    try {
                        e.setGraph(graph)
                    } catch (ex: Exception) {
                        Log.e(TAG, "setGraph failed", ex)
                    }
                }
            }

            override fun onStatus(pending: Int, message: String?) {
                h.post {
                    e.loadingTiles = pending
                    repoMessage = message
                }
            }
        })
        repo = r
        val cs = UserCameraStore(ctx)
        cameraStore = cs
        r.setUserCameras(cs.load().map { it.toNode() })
        h.post {
            sensors = SensorInput(ctx, h, e).also {
                it.onMotion = { onMotion() }
                it.start()
            }
            location = LocationInput(ctx, h) { fix ->
                lastPhoneFixNanos = SystemClock.elapsedRealtimeNanos()
                if (demo == null) {
                    try {
                        e.onGps(fix)
                    } catch (ex: Exception) {
                        Log.e(TAG, "onGps failed", ex)
                    }
                }
            }.also { it.start() }
            h.post(tick)
        }
        traffic = TrafficUpdater(ctx).also { it.start() }
        alerts = AlertManager(ctx)
        listeners.add(alertListener)
        running = true
    }

    @Synchronized
    fun stop() {
        if (!running) return
        val h = handler
        val e = engine
        val t = thread
        h?.post {
            demo?.stop()
            demo = null
            sensors?.stop()
            location?.stop()
            sensors?.disarmMotion()
            if (e != null) prefs?.driverProfile = e.driver.serialize()
            h.removeCallbacksAndMessages(null)
            t?.quitSafely()
        }
        listeners.remove(alertListener)
        alerts?.shutdown()
        alerts = null
        traffic?.shutdown()
        traffic = null
        repo?.shutdown()
        repo = null
        handler = null
        thread = null
        engine = null
        sensors = null
        location = null
        running = false
        demoRunning = false
        power.reset()
        powerLevel = PowerLevel.ACTIVE
        tickMs = 250L
        nearestM = null
        nearestAt = 0L
        motionAtSec = Double.NaN
    }

    fun addListener(l: SnapshotListener) {
        listeners.add(l)
    }

    fun removeListener(l: SnapshotListener) {
        listeners.remove(l)
    }

    /** エンジンスレッドで処理を実行 */
    fun post(action: (HighwayEngine) -> Unit) {
        val h = handler ?: return
        h.post { engine?.let(action) }
    }

    /** 位置権限が後から許可された場合などに GPS 受信を開始し直す */
    fun restartLocation() {
        handler?.post { location?.start() }
    }

    /** 常駐サービスが位置情報の種類になったときなど、GPS の受信を登録し直す */
    fun reregisterLocation() {
        handler?.post {
            location?.stop()
            location?.start()
        }
    }

    // ------------------------------------------------------------------ 渋滞情報

    /** 前方経路の問い合わせ範囲（エンジンスレッドで作り、そのまま cb を呼ぶ） */
    fun trafficQuery(maxLength: Double, cb: (TrafficQuery?) -> Unit) {
        val h = handler
        if (h == null) {
            cb(null)
            return
        }
        h.post { cb(engine?.trafficQuery(maxLength)) }
    }

    fun refreshTraffic() {
        traffic?.refreshNow()
    }

    // ------------------------------------------------------------------ 地図データ・オービス

    fun reloadMapData() {
        repo?.reloadStore()
    }

    fun reloadUserCameras() {
        val cs = cameraStore ?: return
        repo?.setUserCameras(cs.load().map { it.toNode() })
    }

    /** 現在地（進行方向つき）をオービスとして登録。@return 登録できたら true */
    fun registerCameraHere(done: (Boolean) -> Unit) {
        val h = handler ?: return done(false)
        h.post {
            val e = engine
            val f = e?.lastFix
            val ok = if (e != null && f != null) {
                val hdg = if (e.heading.initialized) e.heading.headingDeg else f.bearingDeg
                cameraStore?.add(f.lat, f.lon, hdg)
                true
            } else false
            if (ok) reloadUserCameras()
            main.post { done(ok) }
        }
    }

    fun clearCache() {
        repo?.clearCache()
    }

    fun cacheSizeBytes(): Long = repo?.cacheSizeBytes() ?: 0L

    fun loadedTileStats(): Pair<Int, Int> = repo?.loadedStats() ?: (0 to 0)

    // ------------------------------------------------------------------ 車両（Android Auto）

    fun setCarConnected(connected: Boolean) {
        carConnected = connected
        post { it.setVehicleConnected(connected) }
    }

    fun onVehicleSpeed(rawMs: Double?, displayMs: Double?) {
        val t = SystemClock.elapsedRealtimeNanos()
        post { if (demo == null) it.onVehicleSpeed(t, rawMs, displayMs) }
    }

    fun onVehicleGyro(x: Double, y: Double, z: Double) {
        val t = SystemClock.elapsedRealtimeNanos()
        post { if (demo == null) it.onVehicleGyro(t, x, y, z) }
    }

    fun onVehicleLocation(fix: GpsFix) {
        lastVehicleFixNanos = SystemClock.elapsedRealtimeNanos()
        post { if (demo == null) it.onVehicleLocation(fix) }
    }

    fun onVehicleTollCard(state: TollCardState) {
        post {
            it.vehicle.tollCard = state
            it.vehicle.tollCardKnown = state != TollCardState.UNKNOWN
        }
    }

    fun onVehicleEnergy(rangeM: Double?, fuelPct: Double?, batteryPct: Double?, low: Boolean?) {
        post {
            it.vehicle.rangeRemainingM = rangeM
            it.vehicle.fuelPercent = fuelPct
            it.vehicle.batteryPercent = batteryPct
            it.vehicle.energyLow = low
        }
    }

    fun onVehicleModel(name: String?, electric: Boolean?) {
        post {
            if (name != null) it.vehicle.model = name
            if (electric != null) it.vehicle.electric = electric
        }
    }

    // ------------------------------------------------------------------ デモ

    fun startDemo(preset: DemoPreset) {
        val h = handler ?: return
        h.post {
            val e = engine ?: return@post
            demo?.stop()
            var lat = preset.lat
            var lon = preset.lon
            var hdg = preset.headingDeg
            if (preset == DemoPreset.CURRENT) {
                val f = e.lastFix
                if (f == null) {
                    demoMessage = "デモ: 現在地が未取得です"
                    return@post
                }
                lat = f.lat
                lon = f.lon
                hdg = if (e.heading.initialized) e.heading.headingDeg else (f.bearingDeg ?: 0.0)
            }
            location?.stop()
            val d = DemoDriver(h, e, preset, lat, lon, hdg, { tiles -> repo?.request(tiles) }) { msg -> demoMessage = msg }
            demo = d
            demoRunning = true
            d.start()
        }
    }

    fun stopDemo() {
        handler?.post {
            demo?.stop()
            demo = null
            demoRunning = false
            demoMessage = null
            engine?.forceRematch()
            location?.start()
        }
    }
}

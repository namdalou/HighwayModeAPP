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
import com.example.highwaymode.core.graph.RoadGraph
import com.example.highwaymode.core.model.GpsFix
import com.example.highwaymode.core.traffic.TrafficQuery
import com.example.highwaymode.core.vehicle.TollCardState
import com.example.highwaymode.data.Prefs
import com.example.highwaymode.data.TileRepository
import com.example.highwaymode.data.UserCameraStore
import com.example.highwaymode.traffic.TrafficUpdater
import java.util.concurrent.CopyOnWriteArraySet

/**
 * エンジン・センサー・GPS・地図データ・渋滞情報・音声案内をまとめて動かすプロセス内シングルトン。
 * エンジンへのアクセスはすべて専用スレッド（engine handler）上で行う。
 * 画面（スマホの Activity・Android Auto の車載画面）と常駐サービスは、メインスレッドでスナップショットを受け取る。
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
                val phoneStale = lastPhoneFixNanos == 0L || now - lastPhoneFixNanos > 20_000_000_000L
                val vehicleStale = lastVehicleFixNanos == 0L || now - lastVehicleFixNanos > 20_000_000_000L
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
            if (now - lastTileRequest > 2_000_000_000L) {
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
            h.postDelayed(this, 250L)
        }
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
            sensors = SensorInput(ctx, h, e).also { it.start() }
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

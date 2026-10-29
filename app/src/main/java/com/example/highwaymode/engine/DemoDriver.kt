package com.example.highwaymode.engine

import android.os.Handler
import android.os.SystemClock
import com.example.highwaymode.core.engine.HighwayEngine
import com.example.highwaymode.core.geo.Geo
import com.example.highwaymode.core.model.GpsFix
import com.example.highwaymode.core.osm.TileId
import com.example.highwaymode.core.route.RouteAheadBuilder
import com.example.highwaymode.core.sim.JamZone
import com.example.highwaymode.core.sim.SimParams
import com.example.highwaymode.core.sim.SimPath
import com.example.highwaymode.core.sim.SimSink
import com.example.highwaymode.core.sim.TraceSimulator
import kotlin.math.abs

/** デモ走行の開始地点（付近 5km 以内の高速道路から、指定方位に近い車線を選ぶ） */
enum class DemoPreset(val label: String, val lat: Double, val lon: Double, val headingDeg: Double) {
    NAGANO_OKAYA("長野道 岡谷IC付近 → 中央道（南行き）", 36.090, 138.030, 180.0),
    CHUO_DANGOZAKA("中央道 談合坂付近（西行き）", 35.620, 139.040, 270.0),
    TOMEI_EBINA("東名 海老名付近（西行き）", 35.440, 139.390, 240.0),
    SHUTOKO_WANGAN("首都高 湾岸線 大井付近（南西行き）", 35.590, 139.770, 210.0),
    MEISHIN_KYOTO("名神 京都東IC付近（西行き）", 34.985, 135.830, 260.0),
    CURRENT("現在地付近の高速道路（進行方向）", Double.NaN, Double.NaN, Double.NaN)
}

/**
 * デモ走行: 実際の地図データ上で TraceSimulator が GPS・ジャイロ・加速度・コンパスを生成し、
 * 実機と同じ経路でエンジンに入力する（トンネルでは GPS が途切れ、推測航法になる）。
 * すべてエンジン用スレッドで動く。
 */
class DemoDriver(
    private val handler: Handler,
    private val engine: HighwayEngine,
    private val preset: DemoPreset,
    private val startLat: Double,
    private val startLon: Double,
    private val startHeading: Double,
    private val requestTiles: (List<TileId>) -> Unit,
    private val onMessage: (String?) -> Unit
) {
    private var sim: TraceSimulator? = null
    private var running = false
    private var waitStartMs = 0L
    private var lastExtendCheck = 0L

    private val sink = object : SimSink {
        override fun gyro(t: Long, x: Double, y: Double, z: Double) = engine.onGyro(t, x, y, z)
        override fun accel(t: Long, x: Double, y: Double, z: Double) = engine.onAccel(t, x, y, z)
        override fun gravity(t: Long, x: Double, y: Double, z: Double) = engine.onGravity(t, x, y, z)
        override fun rotation(t: Long, r: DoubleArray, fieldUt: Double, expectedUt: Double) =
            engine.onRotationMatrix(t, r, fieldUt, expectedUt)
        override fun gps(fix: GpsFix) = engine.onGps(fix)
    }

    private val step = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                tick()
            } catch (ex: Exception) {
                android.util.Log.e("DemoDriver", "demo step failed", ex)
                onMessage("デモ走行でエラーが発生しました: ${ex.message}")
                running = false
                return
            }
            handler.postDelayed(this, STEP_MS)
        }
    }

    fun start() {
        running = true
        waitStartMs = SystemClock.elapsedRealtime()
        onMessage("デモ: ${preset.label} の地図データを準備中…")
        handler.post(step)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(step)
        onMessage(null)
    }

    val isRunning: Boolean get() = running

    private fun tick() {
        val s = sim
        if (s == null) {
            tryStart()
            return
        }
        if (!s.advance(STEP_MS / 1000.0, sink)) {
            onMessage("デモ走行が終了しました（地図データの終端）")
            running = false
            return
        }
        // 前方のデータが増えたら経路を延長
        val now = SystemClock.elapsedRealtime()
        if (now - lastExtendCheck > 5000 && s.path.length - s.s < 8000) {
            lastExtendCheck = now
            buildPath(s.trueLatLon.lat, s.trueLatLon.lon, s.trueHeading, 120.0)?.let { p ->
                if (p.length > s.path.length - s.s + 500) s.rebase(p)
            }
        }
    }

    private fun tryStart() {
        val tiles = ArrayList<TileId>()
        val here = TileId.of(startLat, startLon)
        tiles.add(here)
        val ahead = Geo.destination(startLat, startLon, startHeading, 15_000.0)
        tiles.add(TileId.of(ahead.lat, ahead.lon))
        for (dy in -1..1) for (dx in -1..1) tiles.add(TileId(here.iy + dy, here.ix + dx))
        requestTiles(tiles.distinct())
        val p = buildPath(startLat, startLon, startHeading, 5000.0)
        if (p == null) {
            if (SystemClock.elapsedRealtime() - waitStartMs > 90_000) {
                onMessage("デモ: 付近に高速道路データが見つかりませんでした")
                running = false
            }
            return
        }
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val params = SimParams(
            driverRatio = 1.05,
            initialSpeedKmh = 80.0,
            // 8〜10km 地点に短い渋滞を入れて到着予測の追従を見せる
            jams = listOf(JamZone(8_000.0, 10_000.0, 25.0)),
            wallStartMs = System.currentTimeMillis(),
            declinationDeg = engine.declinationDeg
        )
        engine.forceRematch()
        sim = TraceSimulator(p, params, seed = (nowNanos % 1000).toInt(), startNanos = nowNanos)
        onMessage("デモ走行中: ${preset.label}")
    }

    /** 指定地点付近から指定方位に近い本線を探し、そこから前方経路を作る */
    private fun buildPath(lat: Double, lon: Double, heading: Double, radius: Double): SimPath? {
        val g = engine.graph
        if (g.isEmpty) return null
        val near = g.nearby(lat, lon, radius) { !it.attr.isLink }
        val best = near.minByOrNull { p ->
            val d = abs(Geo.diffDeg(g.edges[p.edge].bearingAt(p.offset), heading))
            p.dist + d * 30.0 + if (d > 70) 1e6 else 0.0
        } ?: return null
        val dBrg = abs(Geo.diffDeg(g.edges[best.edge].bearingAt(best.offset), heading))
        if (dBrg > 70) return null
        val route = RouteAheadBuilder(maxLength = 120_000.0).build(g, best.edge, best.offset)
        if (route.length < 1000) return null
        return SimPath.fromRoute(route, g)
    }

    companion object {
        const val STEP_MS = 100L
    }
}

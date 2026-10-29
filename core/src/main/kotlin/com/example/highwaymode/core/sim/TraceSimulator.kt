package com.example.highwaymode.core.sim

import com.example.highwaymode.core.eta.SpeedLimitModel
import com.example.highwaymode.core.geo.Geo
import com.example.highwaymode.core.geo.LatLon
import com.example.highwaymode.core.graph.RoadGraph
import com.example.highwaymode.core.model.GpsFix
import com.example.highwaymode.core.route.RouteAhead
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** シミュレーション用の走行経路（ポリライン＋区間属性） */
class SimPath(
    val lat: DoubleArray,
    val lon: DoubleArray,
    val cum: DoubleArray,
    /** 区間 i（点 i→i+1）の制限速度 [km/h] */
    val limitKmh: DoubleArray,
    val tunnel: BooleanArray,
    /** 本線料金所の位置 [m] */
    val tollBooths: List<Double>
) {
    val length: Double get() = cum[cum.size - 1]

    fun segAt(s: Double): Int {
        if (s <= 0) return 0
        if (s >= length) return cum.size - 2
        var lo = 0
        var hi = cum.size - 1
        while (hi - lo > 1) {
            val m = (lo + hi) ushr 1
            if (cum[m] <= s) lo = m else hi = m
        }
        return lo
    }

    fun pointAt(s: Double): LatLon {
        val i = segAt(s)
        val l = cum[i + 1] - cum[i]
        val f = if (l > 1e-9) ((s - cum[i]) / l).coerceIn(0.0, 1.0) else 0.0
        return LatLon(lat[i] + (lat[i + 1] - lat[i]) * f, lon[i] + (lon[i + 1] - lon[i]) * f)
    }

    /** 実車の滑らかな旋回を模した方位（±15m の弦） */
    fun headingAt(s: Double): Double {
        val a = pointAt((s - 15.0).coerceIn(0.0, length))
        val b = pointAt((s + 15.0).coerceIn(0.0, length))
        return Geo.bearing(a.lat, a.lon, b.lat, b.lon)
    }

    companion object {
        fun fromRoute(route: RouteAhead, graph: RoadGraph): SimPath {
            val la = ArrayList<Double>()
            val lo = ArrayList<Double>()
            val lim = ArrayList<Double>()
            val tun = ArrayList<Boolean>()
            val p = DoubleArray(2)
            for (seg in route.segments) {
                val e = graph.edges[seg.edge]
                val limit = SpeedLimitModel.limitFor(e.attr).kmh
                fun add(off: Double) {
                    e.pointAt(off, p)
                    if (la.isNotEmpty()) {
                        val d = Geo.distance(la.last(), lo.last(), p[0], p[1])
                        if (d < 0.05) return
                        lim.add(limit)
                        tun.add(e.attr.tunnel)
                    }
                    la.add(p[0]); lo.add(p[1])
                }
                add(seg.startOffset)
                for (i in 0 until e.pointCount) {
                    if (e.cum[i] > seg.startOffset && e.cum[i] < seg.endOffset) add(e.cum[i])
                }
                add(seg.endOffset)
            }
            val n = la.size
            val cum = DoubleArray(n)
            for (i in 1 until n) cum[i] = cum[i - 1] + Geo.distance(la[i - 1], lo[i - 1], la[i], lo[i])
            val tolls = route.facilities.filter { it.facility.type == com.example.highwaymode.core.facility.FacilityType.TB }.map { it.distance }
            return SimPath(la.toDoubleArray(), lo.toDoubleArray(), cum, lim.toDoubleArray(), tun.toBooleanArray(), tolls)
        }
    }
}

/** 渋滞区間 */
class JamZone(val start: Double, val end: Double, val speedKmh: Double)

class SimParams(
    /** 制限速度に対する運転者の巡航速度比 */
    val driverRatio: Double = 1.05,
    val initialSpeedKmh: Double = 80.0,
    val jams: List<JamZone> = emptyList(),
    val gpsNoise: Double = 2.5,
    val gpsWhite: Double = 1.0,
    val gyroBias: DoubleArray = doubleArrayOf(0.003, -0.004, 0.002),
    val gyroNoise: Double = 0.004,
    val accelBias: DoubleArray = doubleArrayOf(0.04, -0.03, 0.05),
    val mountTiltDeg: Double = 15.0,
    /** 取付の水平回転（車両前方に対するスマホ背面の向き）[deg] */
    val mountYawDeg: Double = 0.0,
    val compassErrorDeg: Double = 6.0,
    val declinationDeg: Double = -7.5,
    val gpsInTunnels: Boolean = false,
    /** GPS を止める区間（経路上の距離 [m]） */
    val gpsOutages: List<ClosedFloatingPointRange<Double>> = emptyList(),
    val wallStartMs: Long = 1_700_000_000_000L,
    /** 車速信号（Android Auto）の送信頻度 [Hz]。0 なら送らない */
    val vehicleSpeedHz: Double = 0.0,
    /** 車速信号の倍率誤差（1.02 = 2% 高めに出る） */
    val vehicleSpeedScale: Double = 1.0,
    /** 車両のジャイロ（Android Auto CarSensors, z 軸上向き）の送信頻度 [Hz]。0 なら送らない */
    val vehicleGyroHz: Double = 0.0
)

interface SimSink {
    fun gyro(t: Long, x: Double, y: Double, z: Double)
    fun accel(t: Long, x: Double, y: Double, z: Double)
    fun gravity(t: Long, x: Double, y: Double, z: Double)
    fun rotation(t: Long, r: DoubleArray, fieldUt: Double, expectedUt: Double)
    fun gps(fix: GpsFix)

    /** 車速信号 [m/s]（Android Auto 接続時） */
    fun vehicleSpeed(t: Long, rawMs: Double) {}

    /** 車両のジャイロ [rad/s] */
    fun vehicleGyro(t: Long, x: Double, y: Double, z: Double) {}
}

/**
 * 経路に沿った走行を模擬し、GPS・ジャイロ・加速度・重力・回転行列を生成する。
 * 単体テストでの精度評価と、アプリの「デモ走行」で使う。
 */
class TraceSimulator(path: SimPath, val params: SimParams = SimParams(), seed: Int = 1, startNanos: Long = 1_000_000_000L) {
    var path: SimPath = path
        private set
    private val rnd = Random(seed)

    var s = 0.0
        private set
    var v = params.initialSpeedKmh / 3.6
        private set
    var t = startNanos
        private set
    var aLong = 0.0
        private set
    var finished = false
        private set

    private var headingPrev = path.headingAt(0.0)
    private var yawRateCw = 0.0
    private var ouRatio = 0.0
    private var gmE = 0.0
    private var gmN = 0.0
    private var compassErr = 0.0
    private var nextGpsT = startNanos + 1_000_000_000L
    private var nextRotT = startNanos
    private var nextVehT = startNanos
    private var nextVehGyroT = startNanos
    private var tunnelExitT = Long.MIN_VALUE
    private var wasInTunnel = false
    private var vCap: DoubleArray

    // 取付姿勢（スマホ座標での上・前・左）
    private val upD: DoubleArray
    private val fwdD: DoubleArray
    private val leftD: DoubleArray

    init {
        val tilt = params.mountTiltDeg * Geo.DEG
        upD = doubleArrayOf(0.0, cos(tilt), sin(tilt))
        // 背面方向 (0,0,-1) を水平化し、取付ヨーだけ回す
        val back = doubleArrayOf(0.0, 0.0, -1.0)
        val d = dot(back, upD)
        val f0 = norm(doubleArrayOf(back[0] - d * upD[0], back[1] - d * upD[1], back[2] - d * upD[2]))
        val l0 = cross(upD, f0)
        val yaw = params.mountYawDeg * Geo.DEG
        // 車両前方 = スマホ背面を -yaw 回したもの
        fwdD = norm(doubleArrayOf(
            f0[0] * cos(yaw) - l0[0] * sin(yaw),
            f0[1] * cos(yaw) - l0[1] * sin(yaw),
            f0[2] * cos(yaw) - l0[2] * sin(yaw)
        ))
        leftD = cross(upD, fwdD)
        vCap = computeCurveCaps(path)
    }

    val trueLatLon: LatLon get() = path.pointAt(s)
    val trueHeading: Double get() = path.headingAt(s)
    val inTunnel: Boolean get() = path.tunnel[path.segAt(s)]
    fun wallMs(at: Long = t): Long = params.wallStartMs + (at - 1_000_000_000L) / 1_000_000L

    /** 走行中の経路を差し替える（デモで前方のデータが増えたとき）。新経路は現在地から始まること。 */
    fun rebase(newPath: SimPath) {
        path = newPath
        s = 0.0
        finished = false
        headingPrev = newPath.headingAt(0.0)
        vCap = computeCurveCaps(newPath)
    }

    private fun capAt(s: Double): Double {
        val c = vCap
        val i = min(c.size - 1, max(0, (s / 10.0).toInt()))
        return c[i]
    }

    /** dtSec だけ進める（内部 20ms 刻み）。経路終端で false */
    fun advance(dtSec: Double, sink: SimSink): Boolean {
        var remaining = dtSec
        while (remaining > 1e-9 && !finished) {
            val h = min(0.02, remaining)
            stepOnce(h, sink)
            remaining -= h
        }
        return !finished
    }

    private fun targetSpeed(sPos: Double): Double {
        val seg = path.segAt(sPos)
        var target = path.limitKmh[seg] / 3.6 * params.driverRatio * (1.0 + ouRatio)
        for (j in params.jams) if (sPos >= j.start && sPos <= j.end) target = min(target, j.speedKmh / 3.6)
        for (tb in path.tollBooths) if (abs(sPos - tb) < 60.0) target = min(target, 20.0 / 3.6)
        target = min(target, capAt(sPos))
        return max(0.0, target)
    }

    private fun stepOnce(h: Double, sink: SimSink) {
        val hn = (h * 1e9).toLong()
        t += hn
        // 運転者の速度ゆらぎ（OU 過程, σ≈3%, τ=30s）
        ouRatio += -ouRatio * h / 30.0 + 0.03 * sqrt(2 * h / 30.0) * gauss()
        // 前方の減速要因を見越して速度を決める
        var target = targetSpeed(s)
        var look = 10.0
        while (look <= 600.0) {
            val tv = targetSpeed(s + look)
            val allowed = sqrt(tv * tv + 2 * 1.0 * look)
            if (allowed < target) target = allowed
            look += 20.0
        }
        val acc = (target - v).coerceIn(-1.5 * h, 0.9 * h)
        val vNew = max(0.0, v + acc)
        aLong = (vNew - v) / h
        v = vNew
        s += v * h
        if (s >= path.length) {
            s = path.length
            finished = true
        }
        val hd = path.headingAt(s)
        val dHead = Geo.diffDeg(hd, headingPrev) * Geo.DEG
        headingPrev = hd
        yawRateCw = dHead / h
        val aLat = -v * yawRateCw // 左向き正

        // ---- 慣性センサー（50Hz）
        val wCcw = -yawRateCw
        val gb = params.gyroBias
        sink.gyro(
            t,
            upD[0] * wCcw + gb[0] + params.gyroNoise * gauss(),
            upD[1] * wCcw + gb[1] + params.gyroNoise * gauss(),
            upD[2] * wCcw + gb[2] + params.gyroNoise * gauss()
        )
        val g = 9.80665
        sink.gravity(t, upD[0] * g, upD[1] * g, upD[2] * g)
        val vib = if (v > 0.5) 0.15 else 0.02
        val ab = params.accelBias
        sink.accel(
            t,
            upD[0] * g + aLong * fwdD[0] + aLat * leftD[0] + ab[0] + vib * gauss(),
            upD[1] * g + aLong * fwdD[1] + aLat * leftD[1] + ab[1] + vib * gauss(),
            upD[2] * g + aLong * fwdD[2] + aLat * leftD[2] + ab[2] + vib * gauss()
        )

        // ---- 車速信号・車両ジャイロ（Android Auto）
        if (params.vehicleSpeedHz > 0 && t >= nextVehT) {
            nextVehT += (1e9 / params.vehicleSpeedHz).toLong()
            // 車輪速は 0.1km/h 単位程度に丸められ、低速ではわずかに揺れる
            val vv = Math.round(v * params.vehicleSpeedScale * 36.0) / 36.0
            sink.vehicleSpeed(t, vv)
        }
        if (params.vehicleGyroHz > 0 && t >= nextVehGyroT) {
            nextVehGyroT += (1e9 / params.vehicleGyroHz).toLong()
            sink.vehicleGyro(t, 0.001 * gauss(), 0.001 * gauss(), wCcw + 0.0015 + 0.002 * gauss())
        }

        // ---- コンパス（10Hz）
        if (t >= nextRotT) {
            nextRotT += 100_000_000L
            compassErr += -compassErr * 0.1 / 60.0 + params.compassErrorDeg * sqrt(2 * 0.1 / 60.0) * gauss()
            val inTun = inTunnel
            val psiMag = (hd - params.declinationDeg + compassErr + 2.0 * gauss()) * Geo.DEG
            val r = rotationMatrix(psiMag)
            sink.rotation(t, r, if (inTun) 62.0 else 46.0 + 0.5 * gauss(), 46.0)
        }

        // ---- GPS（1Hz）
        val inTun = inTunnel
        if (wasInTunnel && !inTun) tunnelExitT = t
        wasInTunnel = inTun
        if (t >= nextGpsT) {
            nextGpsT += 1_000_000_000L
            val tau = 20.0
            val a = exp(-1.0 / tau)
            val q = params.gpsNoise * sqrt(1 - a * a)
            gmE = a * gmE + q * gauss()
            gmN = a * gmN + q * gauss()
            val gpsOk = (params.gpsInTunnels || !inTun) && params.gpsOutages.none { s in it }
            val sinceExit = if (tunnelExitT == Long.MIN_VALUE) Double.MAX_VALUE else (t - tunnelExitT) * 1e-9
            if (gpsOk && sinceExit > 1.5) {
                val degraded = sinceExit < 5.0
                val k = if (degraded) 4.0 else 1.0
                val p = path.pointAt(s)
                val eN = (gmN * k + params.gpsWhite * k * gauss())
                val eE = (gmE * k + params.gpsWhite * k * gauss())
                val lat = p.lat + eN / Geo.metersPerDegLat(p.lat)
                val lon = p.lon + eE / Geo.metersPerDegLon(p.lat)
                val acc = (if (degraded) 18.0 else 4.0) + rnd.nextDouble() * 1.5
                val spd = max(0.0, v + 0.1 * gauss())
                val brg = if (v > 0.5) Geo.normalizeDeg(hd + (0.4 + atan2(0.15, v) * Geo.RAD) * gauss()) else null
                sink.gps(
                    GpsFix(
                        timeNanos = t, wallTimeMs = wallMs(t), lat = lat, lon = lon, accuracyM = acc,
                        speedMs = spd, speedAccMs = 0.3, bearingDeg = brg,
                        bearingAccDeg = if (brg != null) max(1.0, atan2(0.3, max(v, 0.1)) * Geo.RAD) else null
                    )
                )
            }
        }
    }

    private fun rotationMatrix(psi: Double): DoubleArray {
        // 世界座標（ENU）での車両の前・左・上
        val fw = doubleArrayOf(sin(psi), cos(psi), 0.0)
        val lw = doubleArrayOf(-cos(psi), sin(psi), 0.0)
        val uw = doubleArrayOf(0.0, 0.0, 1.0)
        val r = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) {
            r[i * 3 + j] = fw[i] * fwdD[j] + lw[i] * leftD[j] + uw[i] * upD[j]
        }
        return r
    }

    private fun gauss(): Double {
        // Box-Muller
        var u1 = rnd.nextDouble()
        if (u1 < 1e-12) u1 = 1e-12
        val u2 = rnd.nextDouble()
        return sqrt(-2.0 * kotlin.math.ln(u1)) * cos(2 * Math.PI * u2)
    }

    companion object {
        private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
            a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]
        )
        private fun norm(a: DoubleArray): DoubleArray {
            val n = sqrt(dot(a, a))
            return doubleArrayOf(a[0] / n, a[1] / n, a[2] / n)
        }

        /** 10m ごとのカーブ限界速度 [m/s]（横加速度 2.0m/s²） */
        fun computeCurveCaps(path: SimPath): DoubleArray {
            val n = max(1, (path.length / 10.0).toInt() + 1)
            val caps = DoubleArray(n) { 40.0 }
            for (i in 3 until n - 3) {
                val h1 = path.headingAt((i - 3) * 10.0)
                val h2 = path.headingAt((i + 3) * 10.0)
                val dTheta = abs(Geo.diffDeg(h2, h1)) * Geo.DEG
                if (dTheta < 1e-4) continue
                val r = 60.0 / dTheta
                caps[i] = max(sqrt(2.0 * r), 30.0 / 3.6)
            }
            return caps
        }
    }
}

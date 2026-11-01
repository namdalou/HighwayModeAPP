package com.example.highwaymode.core.engine

import com.example.highwaymode.core.eta.DriverProfile
import com.example.highwaymode.core.eta.EtaPredictor
import com.example.highwaymode.core.eta.EtaResult
import com.example.highwaymode.core.eta.EtaSmoother
import com.example.highwaymode.core.eta.RouteProfile
import com.example.highwaymode.core.eta.SpeedLimitModel
import com.example.highwaymode.core.eta.TrafficTracker
import com.example.highwaymode.core.facility.Amenity
import com.example.highwaymode.core.facility.FacilityType
import com.example.highwaymode.core.geo.Geo
import com.example.highwaymode.core.graph.RoadGraph
import com.example.highwaymode.core.heading.CompassMountCalibrator
import com.example.highwaymode.core.heading.HeadingFilter
import com.example.highwaymode.core.match.MapMatcher
import com.example.highwaymode.core.match.MatchStatus
import com.example.highwaymode.core.model.GpsFix
import com.example.highwaymode.core.model.HeadingObs
import com.example.highwaymode.core.osm.TileId
import com.example.highwaymode.core.route.RouteAhead
import com.example.highwaymode.core.route.RouteAheadBuilder
import com.example.highwaymode.core.route.RouteEnd
import com.example.highwaymode.core.speed.ForwardAxisEstimator
import com.example.highwaymode.core.speed.SpeedFilter
import com.example.highwaymode.core.traffic.EdgeTraffic
import com.example.highwaymode.core.traffic.ExtTraffic
import com.example.highwaymode.core.traffic.JamModel
import com.example.highwaymode.core.traffic.JamSpan
import com.example.highwaymode.core.traffic.JamTracker
import com.example.highwaymode.core.traffic.TrafficData
import com.example.highwaymode.core.traffic.TrafficKind
import com.example.highwaymode.core.traffic.TrafficMapper
import com.example.highwaymode.core.traffic.TrafficQuery
import com.example.highwaymode.core.vehicle.TollCardState
import com.example.highwaymode.core.vehicle.VehicleState
import com.example.highwaymode.core.vehicle.VehicleYawCalibrator
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class EngineConfig(
    val maxRouteLength: Double = 150_000.0,
    val maxFacilities: Int = 30,
    /** GPS が途切れてから推測航法に入るまで [s] */
    val drStartAfterSec: Double = 2.2,
    val outputIntervalSec: Double = 1.0,
    val routeRefreshSec: Double = 8.0
)

/**
 * ハイウェイモードの中核。センサー・GPS・地図を統合して画面表示用スナップショットを作る。
 * Android には依存しない（JVM 単体テスト・シミュレーションで検証可能）。
 * スレッド安全ではないので、すべての呼び出しを同じスレッドから行うこと。
 *
 * 時刻 t はすべて elapsedRealtimeNanos 基準。
 */
class HighwayEngine(val config: EngineConfig = EngineConfig()) {

    val heading = HeadingFilter()
    val compass = CompassMountCalibrator()
    val speedFilter = SpeedFilter()
    val forwardAxis = ForwardAxisEstimator()
    val driver = DriverProfile()
    val eta = EtaPredictor(driver)
    private val etaSmoother = EtaSmoother()
    private val traffic = TrafficTracker()
    private val routeBuilder = RouteAheadBuilder(config.maxRouteLength, 60)

    var graph: RoadGraph = RoadGraph.empty()
        private set
    val matcher = MapMatcher(graph)

    // ---- 取付姿勢
    private val up = DoubleArray(3)
    private val gravityRaw = DoubleArray(3)
    private var upValid = false
    private val upRef = DoubleArray(3)
    private var upRefValid = false
    private var upDeviateSince = Long.MIN_VALUE
    var mountAxis = CompassMountCalibrator.AXIS_UNKNOWN
        private set

    // ---- ジャイロ
    private var lastGyroT = Long.MIN_VALUE
    private var lastRateCorr = 0.0
    private var yawAbsEmaDeg = 0.0
    private var gyroIntervalSum = 0.0
    private var gyroIntervalDt = 0.0

    // ---- 停止検出
    private val still = StationaryDetector()
    var stationary = false
        private set
    private var stillRateSum = 0.0
    private var stillRateN = 0
    private var stillSince = Long.MIN_VALUE
    private var lastBiasUpdateT = Long.MIN_VALUE

    // ---- 加速度
    private var lastAccelT = Long.MIN_VALUE

    // ---- コンパス
    private var compassAxisHeading = Double.NaN
    private var compassT = Long.MIN_VALUE
    private var compassMagOk = false
    private var lastCompassUpdateT = Long.MIN_VALUE
    /** 磁気偏角 [deg]（東偏正。日本は約 -5〜-10°）。ホスト側で GeomagneticField から設定 */
    var declinationDeg = -7.5

    // ---- GPS
    var lastFix: GpsFix? = null
        private set
    private var lastGoodFixT = Long.MIN_VALUE
    private var lastFixSpeed = Double.NaN
    private var lastFixSpeedT = Long.MIN_VALUE
    private var lastDrT = Long.MIN_VALUE
    private var lastMapHeadingT = Long.MIN_VALUE
    private var lastLearnT = Long.MIN_VALUE

    // ---- 経路・ETA
    var route: RouteAhead? = null
        private set
    private var profile: RouteProfile? = null
    private var routeBuiltT = Long.MIN_VALUE
    private var lastOutputT = Long.MIN_VALUE
    private var lastTrafficT = Long.MIN_VALUE
    private var currentProgress = Double.NaN
    var lastEta: EtaResult? = null
        private set

    // ---- 外部の交通情報（HERE / TomTom / Google）
    private var trafficData: TrafficData? = null
    private var edgeTraffic: EdgeTraffic? = null
    /** 渋滞の最後尾の動き（取得のたびに前回と照合） */
    private val jamTracker = JamTracker()
    /** いまの経路上の渋滞（経路・交通情報が変わったときだけ作り直す） */
    private var jamSpans: List<JamSpan> = emptyList()
    private var jamSpansFor: TrafficMapper.RouteTraffic? = null
    private var routeTraffic: TrafficMapper.RouteTraffic? = null
    private var routeTrafficFor: RouteAhead? = null
    /** 交通情報の取得状況（ホストが設定。例:「渋滞情報: API キー未設定」） */
    var trafficMessage: String? = null

    // ---- 車両（Android Auto）
    val vehicle = VehicleState()
    private val vehicleYaw = VehicleYawCalibrator()
    /** 診断用 */
    val vehicleYawCalibrator: VehicleYawCalibrator get() = vehicleYaw
    private var vehicleYawWasActive = false
    /** 車両ジャイロ使用中のスマホのジャイロのバイアス（切替時の推定値で固定） */
    private var phoneBias = Double.NaN
    private var lastVehicleUpdateT = Long.MIN_VALUE
    private var lastScaleLearnT = Long.MIN_VALUE
    private var lastGpsBearing = Double.NaN
    private var lastGpsBearingT = Long.MIN_VALUE

    // ---- SA/PA 停車中の表示保持
    private var lastOnSnapshot: HighwaySnapshot? = null
    private var lastOnT = Long.MIN_VALUE

    // ---- 状態
    /** 取得中のタイル数（ホストが設定） */
    var loadingTiles = 0
    /** データ取得に関するメッセージ（ホストが設定） */
    var dataMessage: String? = null
    var generalRoadMode: Boolean = false
        set(value) {
            field = value
            matcher.suppressed = value
            if (value) {
                route = null
                profile = null
            }
        }
    var snapshot: HighwaySnapshot = HighwaySnapshot.initial(0)
        private set

    // ------------------------------------------------------------------ 入力

    fun setGraph(g: RoadGraph) {
        graph = g
        matcher.setGraph(g)
        route = null
        profile = null
        routeTraffic = null
        routeTrafficFor = null
        edgeTraffic = trafficData?.let { if (g.isEmpty) null else TrafficMapper.annotate(g, it) }
    }

    // ------------------------------------------------------------------ 交通情報

    /** 取得した交通情報を設定する（null で消去） */
    fun setTraffic(data: TrafficData?) {
        trafficData = data
        edgeTraffic = if (data == null || graph.isEmpty) null else TrafficMapper.annotate(graph, data)
        routeTraffic = null
        routeTrafficFor = null
        jamSpansFor = null
        if (data == null) {
            jamTracker.clear()
            return
        }
        // 前回の取得結果と照合して、渋滞の最後尾が伸びているか・縮んでいるかを求める
        val rt = route
        val pf = profile
        val et = edgeTraffic
        if (rt != null && pf != null && et != null && rt.graphGeneration == graph.generation) {
            try {
                val rtf = TrafficMapper.forRoute(rt, graph, et, pf.cell)
                jamTracker.update(rt, graph, JamModel.spans(rtf, rt, graph, null), data.fetchedWallMs)
            } catch (e: Exception) {
                // 照合できなくても予測は続ける（伸び縮みは不明として扱う）
            }
        }
    }

    val trafficFetchedWallMs: Long? get() = trafficData?.fetchedWallMs

    /**
     * 交通情報の問い合わせ範囲（前方経路を 200m ごとに、最長 maxLength）。高速道路走行中でなければ null
     */
    fun trafficQuery(maxLength: Double = 100_000.0): TrafficQuery? {
        val rt = route ?: return null
        if (rt.graphGeneration != graph.generation || snapshot.status != EngineStatus.ON_HIGHWAY) return null
        val prog = if (currentProgress.isNaN()) 0.0 else currentProgress
        val end = min(rt.length, prog + maxLength)
        if (end - prog < 2000.0) return null
        val la = ArrayList<Double>()
        val lo = ArrayList<Double>()
        val br = ArrayList<Double>()
        val ds = ArrayList<Double>()
        val p = DoubleArray(2)
        var d = prog
        while (d <= end + 1e-6) {
            val si = rt.segmentIndexAt(d)
            if (si < 0) break
            val seg = rt.segments[si]
            val e = graph.edges[seg.edge]
            val off = (seg.startOffset + (d - seg.startDist)).coerceIn(0.0, e.length)
            e.pointAt(off, p)
            la.add(p[0]); lo.add(p[1]); br.add(e.smoothBearingAt(off)); ds.add(d - prog)
            if (d >= end) break
            d = min(end, d + 200.0)
        }
        if (la.size < 2) return null
        return TrafficQuery(la.toDoubleArray(), lo.toDoubleArray(), br.toDoubleArray(), ds.toDoubleArray(), end - prog)
    }

    /** 交通情報の重み: 10 分までは 1、30 分で 0 */
    private fun trafficWeight(wallMs: Long): Double {
        val td = trafficData ?: return 0.0
        val age = (wallMs - td.fetchedWallMs) / 1000.0
        return (1.0 - (age - 600.0) / 1200.0).coerceIn(0.0, 1.0)
    }

    // ------------------------------------------------------------------ 車両（Android Auto）

    /** 車速信号 [m/s]（生の車速・メーター表示速度のどちらか一方でも可） */
    fun onVehicleSpeed(t: Long, rawMs: Double?, displayMs: Double?) {
        vehicle.connected = true
        vehicle.setSpeed(t, rawMs, displayMs)
        if (!vehicle.speedFresh(t)) return
        if (lastVehicleUpdateT != Long.MIN_VALUE && t - lastVehicleUpdateT < 90_000_000L) return
        lastVehicleUpdateT = t
        speedFilter.updateSpeed(vehicle.correctedSpeed, vehicle.speedSigma())
    }

    /** 車両のジャイロ [rad/s]（座標系は車種ごとに異なるので GPS の旋回と照合して軸を決める） */
    fun onVehicleGyro(t: Long, x: Double, y: Double, z: Double) {
        vehicle.connected = true
        val rate = vehicleYaw.onSample(t, x, y, z)
        if (vehicleYaw.active != vehicleYawWasActive) {
            vehicleYawWasActive = vehicleYaw.active
            phoneBias = if (vehicleYaw.active) heading.bias else Double.NaN
            heading.resetBias()
            heading.inflate(2.0 * Geo.DEG)
            stillRateSum = 0.0
            stillRateN = 0
        }
        if (rate == null) return
        if (stationary) {
            heading.holdStill(t)
            stillRateSum += rate
            stillRateN++
        } else {
            heading.predict(rate, t)
        }
    }

    /**
     * 電池の節約でセンサーを止めたとき。止まる直前の「停車中」の判定が残って
     * 速度が 0 に固定されないよう、判定材料を捨てる（再開後は GPS の方位から方位を取り直す）。
     */
    fun onSensorsPaused() {
        still.reset()
        stationary = false
        stillSince = Long.MIN_VALUE
        stillRateSum = 0.0
        stillRateN = 0
        lastAccelT = Long.MIN_VALUE
        upDeviateSince = Long.MIN_VALUE
    }

    /** 直近の GPS 測位の速度 [m/s]（無ければ NaN）。電池の節約の判定に使う */
    val lastGpsSpeedMs: Double get() = lastFixSpeed

    /** 車両の GPS（スマホの測位が止まっているときだけ使う） */
    fun onVehicleLocation(fix: GpsFix) {
        vehicle.connected = true
        val gpsStale = lastGoodFixT == Long.MIN_VALUE || fix.timeNanos - lastGoodFixT > 2_500_000_000L
        if (gpsStale) onGps(fix)
    }

    fun setVehicleConnected(connected: Boolean) {
        vehicle.connected = connected
        if (!connected) {
            vehicle.reset()
            vehicleYaw.reset()
            if (vehicleYawWasActive) {
                vehicleYawWasActive = false
                heading.resetBias()
            }
        }
    }

    /** 重力センサー（TYPE_GRAVITY）[m/s²] */
    fun onGravity(t: Long, x: Double, y: Double, z: Double) {
        val n = sqrt(x * x + y * y + z * z)
        if (n < 3.0) return
        gravityRaw[0] = x; gravityRaw[1] = y; gravityRaw[2] = z
        up[0] = x / n; up[1] = y / n; up[2] = z / n
        upValid = true
        if (!upRefValid) {
            setMount()
            return
        }
        val dot = up[0] * upRef[0] + up[1] * upRef[1] + up[2] * upRef[2]
        if (dot < COS_12) {
            if (upDeviateSince == Long.MIN_VALUE) upDeviateSince = t
            else if (t - upDeviateSince > 1_500_000_000L) {
                // スマホの取付が変わった → コンパス較正・前方軸を学習し直し、方位の信頼度を下げる
                setMount()
                heading.inflate(15.0 * Geo.DEG)
            }
        } else {
            upDeviateSince = Long.MIN_VALUE
        }
    }

    private fun setMount() {
        upRef[0] = up[0]; upRef[1] = up[1]; upRef[2] = up[2]
        upRefValid = true
        upDeviateSince = Long.MIN_VALUE
        val az = abs(up[2])
        mountAxis = if (az < 0.75) CompassMountCalibrator.AXIS_BACK else CompassMountCalibrator.AXIS_TOP
        compass.reset(mountAxis)
        val ref = if (mountAxis == CompassMountCalibrator.AXIS_BACK) doubleArrayOf(0.0, 0.0, -1.0) else doubleArrayOf(0.0, 1.0, 0.0)
        forwardAxis.setBasis(up.copyOf(), ref)
    }

    /** ジャイロ（TYPE_GYROSCOPE）[rad/s] */
    fun onGyro(t: Long, x: Double, y: Double, z: Double) {
        if (!upValid) return
        // 鉛直軸まわりの角速度（左回り正）→ 方位変化率（時計回り正）
        val raw = -(x * up[0] + y * up[1] + z * up[2])
        still.onGyro(t, sqrt(x * x + y * y + z * z))
        val dt = if (lastGyroT == Long.MIN_VALUE) 0.0 else (t - lastGyroT) * 1e-9
        lastGyroT = t
        // 車両のジャイロが使える間はそちらで方位を進める（スマホは置き方で揺れるため）
        val vehicleYawInUse = vehicleYaw.active && vehicleYaw.fresh(t)
        if (!vehicleYawInUse) {
            if (stationary) {
                heading.holdStill(t)
                stillRateSum += raw
                stillRateN++
            } else {
                heading.predict(raw, t)
            }
        }
        if (dt > 0 && dt < 0.2) {
            val b = if (vehicleYawInUse && !phoneBias.isNaN()) phoneBias else heading.bias
            val corr = if (stationary) 0.0 else raw - b
            lastRateCorr = corr
            gyroIntervalSum += corr * dt
            gyroIntervalDt += dt
            val a = min(1.0, dt / 2.0)
            yawAbsEmaDeg += a * (abs(corr) * Geo.RAD - yawAbsEmaDeg)
        }
    }

    /** 加速度センサー（TYPE_ACCELEROMETER）[m/s²] */
    fun onAccel(t: Long, x: Double, y: Double, z: Double) {
        if (!upValid) return
        val lx = x - gravityRaw[0]
        val ly = y - gravityRaw[1]
        val lz = z - gravityRaw[2]
        still.onAccel(t, sqrt(lx * lx + ly * ly + lz * lz))
        forwardAxis.addLinearAccel(lx, ly, lz)
        val aLong = forwardAxis.forwardAccel(lx, ly, lz, -speedFilter.v * lastRateCorr)
        if (stationary) speedFilter.predict(0.0, t) else speedFilter.predict(aLong, t)
        lastAccelT = t
    }

    /**
     * 回転ベクトルセンサー由来の回転行列（デバイス→世界 ENU、行優先 3x3）と磁場強度。
     * @param fieldUt 測定磁場の大きさ [μT]
     * @param expectedFieldUt その地点の地磁気の大きさ [μT]（GeomagneticField）
     */
    fun onRotationMatrix(t: Long, r: DoubleArray, fieldUt: Double, expectedFieldUt: Double) {
        if (mountAxis == CompassMountCalibrator.AXIS_UNKNOWN || r.size < 9) return
        val ax = if (mountAxis == CompassMountCalibrator.AXIS_BACK) doubleArrayOf(0.0, 0.0, -1.0) else doubleArrayOf(0.0, 1.0, 0.0)
        val east = r[0] * ax[0] + r[1] * ax[1] + r[2] * ax[2]
        val north = r[3] * ax[0] + r[4] * ax[1] + r[5] * ax[2]
        if (east * east + north * north < 0.04) return
        compassAxisHeading = Geo.normalizeDeg(atan2(east, north) * Geo.RAD + declinationDeg)
        compassT = t
        compassMagOk = expectedFieldUt > 0 && fieldUt > 0 && abs(fieldUt - expectedFieldUt) / expectedFieldUt < 0.2
        maybeCompassUpdate(t)
    }

    private fun maybeCompassUpdate(t: Long) {
        if (!compassMagOk || compassAxisHeading.isNaN()) return
        if (lastCompassUpdateT != Long.MIN_VALUE && t - lastCompassUpdateT < 1_000_000_000L) return
        val gpsFresh = lastGoodFixT != Long.MIN_VALUE && t - lastGoodFixT < 2_500_000_000L
        if (gpsFresh && speedFilter.v >= 3.0) return // GPS 方位の方が圧倒的に正確
        val ch = compass.carHeading(compassAxisHeading) ?: return
        // 車内の地磁気は車体の鉄・電装品で数度〜十数度ゆがむ。ジャイロ積分の方が正確な間は使わず、
        // 方位が未確定・大きく不確か（σ がコンパス精度に近い）なときだけ使う。
        if (heading.initialized && heading.sigmaDeg < ch.second * 0.7) return
        heading.updateAngle(ch.first * Geo.DEG, ch.second * Geo.DEG, t, HeadingFilter.Source.COMPASS, 3.0)
        lastCompassUpdateT = t
    }

    /** GPS 測位 */
    fun onGps(fix: GpsFix) {
        val t = fix.timeNanos
        val prev = lastFix
        lastFix = fix
        val good = fix.accuracyM <= 50.0

        // ---- 速度
        var vGps: Double? = fix.speedMs
        var vSigma = max(fix.speedAccMs ?: 0.5, 0.2)
        if (vGps == null && prev != null && good && prev.accuracyM <= 50.0) {
            val dt = (t - prev.timeNanos) * 1e-9
            if (dt in 0.5..5.0) {
                vGps = Geo.distance(prev.lat, prev.lon, fix.lat, fix.lon) / dt
                vSigma = 1.0 + 0.5 * (fix.accuracyM + prev.accuracyM) / dt
            }
        }
        if (vGps != null && good) speedFilter.updateSpeed(vGps, vSigma)
        // 車速信号の倍率を GPS 速度で学習（直進中のみ）
        if (vGps != null && good && vehicle.speedFresh(t, 0.6) && yawAbsEmaDeg < 1.0) {
            val dtl = if (lastScaleLearnT == Long.MIN_VALUE) 1.0 else ((t - lastScaleLearnT) * 1e-9).coerceIn(0.0, 2.0)
            vehicle.learnScale(vGps, vSigma, dtl)
            lastScaleLearnT = t
        }
        // 車両ジャイロの軸の判定: スマホのジャイロ（あれば）または GPS 方位の変化と照合
        val brg0 = fix.bearingDeg
        val dtB = if (lastGpsBearingT == Long.MIN_VALUE) -1.0 else (t - lastGpsBearingT) * 1e-9
        if (gyroIntervalDt > 0.3 && !stationary && (t - lastGyroT) < 300_000_000L) {
            vehicleYaw.onReferenceInterval(gyroIntervalSum, gyroIntervalDt)
        } else if (good && brg0 != null && vGps != null && vGps >= 5.0 && !lastGpsBearing.isNaN() && dtB in 0.5..2.5) {
            vehicleYaw.onReferenceInterval(Geo.diffDeg(brg0, lastGpsBearing) * Geo.DEG, dtB, minTurnDeg = 30.0)
        } else {
            vehicleYaw.clearInterval()
        }
        if (good && brg0 != null && vGps != null && vGps >= 5.0) {
            lastGpsBearing = brg0
            lastGpsBearingT = t
        } else {
            lastGpsBearing = Double.NaN
        }

        // ---- GPS 方位による方位フィルタ更新（遅延補償つき）
        var bearingSigma = Double.NaN
        val brg = fix.bearingDeg
        if (good && brg != null && vGps != null && vGps >= 2.5) {
            val computed = atan2(max(fix.speedAccMs ?: 0.3, 0.15), vGps) * Geo.RAD + 0.5
            val reported = fix.bearingAccDeg?.takeIf { it > 0 } ?: 0.0
            bearingSigma = max(computed, min(reported, 15.0)).coerceAtLeast(0.8)
            heading.updateAngle(brg * Geo.DEG, bearingSigma * Geo.DEG, t, HeadingFilter.Source.GPS)
            if (vGps >= 8.0 && bearingSigma <= 3.0 && yawAbsEmaDeg < 1.5 && compassMagOk &&
                !compassAxisHeading.isNaN() && abs(t - compassT) < 600_000_000L
            ) {
                compass.addSample(t, brg, compassAxisHeading)
            }
        }

        // ---- 前方軸推定（GPS 区間の加速度とセンサー加速度の対応付け）
        val lastV = lastFixSpeed
        val dtv = if (lastFixSpeedT == Long.MIN_VALUE) -1.0 else (t - lastFixSpeedT) * 1e-9
        if (vGps != null && good && !lastV.isNaN() && dtv in 0.5..2.5 && gyroIntervalDt > 0.3) {
            val aLong = (vGps - lastV) / dtv
            val meanRate = gyroIntervalSum / gyroIntervalDt
            val aLat = -0.5 * (vGps + lastV) * meanRate
            forwardAxis.onGpsInterval(t, aLong, aLat, dtv)
        } else {
            forwardAxis.discardInterval()
        }
        gyroIntervalSum = 0.0
        gyroIntervalDt = 0.0
        if (vGps != null && good) {
            lastFixSpeed = vGps
            lastFixSpeedT = t
        }

        // ---- マップマッチング
        if (good) {
            lastGoodFixT = t
            lastDrT = t
            matcher.onFix(fix, speedFilter.v, headingObs(fix, bearingSigma))
            learnDriver(t, vGps)
        }
    }

    /**
     * 周期処理（4Hz 程度で呼ぶ）。推測航法・停止検出・表示更新を行う。
     */
    fun onTick(t: Long, wallMs: Long): HighwaySnapshot {
        if (lastAccelT == Long.MIN_VALUE || t - lastAccelT > 500_000_000L) {
            speedFilter.predict(if (stationary) 0.0 else null, t)
        }
        updateStationary(t)
        deadReckonIfNeeded(t)
        trackTraffic(t)
        if (lastOutputT == Long.MIN_VALUE || t - lastOutputT >= (config.outputIntervalSec * 0.95e9).toLong()) {
            updateOutputs(t, wallMs)
        }
        return snapshot
    }

    /** 「再探索」: マッチング状態を捨てて取り直す */
    fun forceRematch() {
        matcher.reset()
        route = null
        profile = null
        traffic.clear()
    }

    /** 必要なタイル（現在地・前方・データ端） */
    fun wantedTiles(): List<TileId> {
        val fix = lastFix ?: return emptyList()
        val out = LinkedHashSet<TileId>()
        val lat = fix.lat
        val lon = fix.lon
        out.add(TileId.of(lat, lon))
        val rt = route
        if (rt != null && rt.end == RouteEnd.DATA_BOUNDARY) out.add(TileId.of(rt.endLat, rt.endLon))
        if (heading.initialized && speedFilter.v > 5.0) {
            val p = Geo.destination(lat, lon, heading.headingDeg, 12_000.0)
            out.add(TileId.of(p.lat, p.lon))
        }
        addWithMargin(lat, lon, 4000.0, out)
        // 前方経路沿い（40km 先まで）。経路のすぐ脇にある SA/PA・分岐の相手側を取りこぼさないよう、
        // タイル境界に近い地点では隣のタイルも加える
        if (rt != null && rt.graphGeneration == graph.generation && !graph.isEmpty) {
            val p = DoubleArray(2)
            var next = 0.0
            for (s in rt.segments) {
                if (s.startDist > PREFETCH_AHEAD) break
                val e = graph.edges[s.edge]
                while (next <= s.endDist && next <= PREFETCH_AHEAD) {
                    if (next >= s.startDist) {
                        e.pointAt(s.startOffset + (next - s.startDist), p)
                        addWithMargin(p[0], p[1], 1500.0, out)
                    }
                    next += 1000.0
                }
            }
        }
        return out.filter { it.inJapan }
    }

    private fun addWithMargin(lat: Double, lon: Double, margin: Double, out: MutableSet<TileId>) {
        val here = TileId.of(lat, lon)
        out.add(here)
        for (dy in -1..1) for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
            val n = TileId(here.iy + dy, here.ix + dx)
            val cLat = lat.coerceIn(n.south, n.north)
            val cLon = lon.coerceIn(n.west, n.east)
            if (Geo.distance(lat, lon, cLat, cLon) < margin) out.add(n)
        }
    }

    // ------------------------------------------------------------------ 内部処理

    private fun headingObs(fix: GpsFix?, bearingSigma: Double): HeadingObs? {
        if (heading.initialized) return HeadingObs(heading.headingDeg, max(heading.sigmaDeg, 0.5))
        val b = fix?.bearingDeg
        if (b != null && !bearingSigma.isNaN()) return HeadingObs(b, bearingSigma)
        return null
    }

    private fun updateStationary(t: Long) {
        val gpsFresh = lastGoodFixT != Long.MIN_VALUE && t - lastGoodFixT < 2_500_000_000L
        val quiet: Boolean
        val need: Long
        if (vehicle.speedFresh(t)) {
            // 車速信号があれば、それが 0 かどうかで判定（最も確実）
            quiet = vehicle.speedMs < 0.1
            need = 800_000_000L
        } else if (gpsFresh) {
            quiet = still.isQuiet(false) && !lastFixSpeed.isNaN() && lastFixSpeed < 0.4
            need = 1_500_000_000L
        } else {
            quiet = still.isQuiet(true)
            need = 4_000_000_000L
        }
        if (quiet) {
            if (stillSince == Long.MIN_VALUE) stillSince = t
            if (!stationary && t - stillSince >= need) {
                stationary = true
                stillRateSum = 0.0
                stillRateN = 0
            }
        } else {
            stillSince = Long.MIN_VALUE
            if (stationary) {
                stationary = false
                stillRateSum = 0.0
                stillRateN = 0
            }
        }
        if (stationary) {
            speedFilter.updateZero()
            if (stillRateN >= 20 && (lastBiasUpdateT == Long.MIN_VALUE || t - lastBiasUpdateT >= 1_000_000_000L)) {
                heading.updateBias(stillRateSum / stillRateN, 0.002)
                stillRateSum = 0.0
                stillRateN = 0
                lastBiasUpdateT = t
            }
        }
    }

    private fun deadReckonIfNeeded(t: Long) {
        if (matcher.candidateCount == 0 || lastGoodFixT == Long.MIN_VALUE) return
        val gpsAge = (t - lastGoodFixT) * 1e-9
        if (gpsAge <= config.drStartAfterSec) return
        val from = if (lastDrT == Long.MIN_VALUE) lastGoodFixT else lastDrT
        if (t <= from) return
        val dt = (t - from) * 1e-9
        val v = speedFilter.v
        val ds = v * dt
        // 前後方向の不確かさ: 加速度で速度を追えていない場合は「0.5m/s² で加減速しうる」とみなす
        val growth = if (vehicle.speedFresh(t)) 0.0 else if (forwardAxis.valid) 0.08 else 0.5
        val sigmaV = min(sqrt(speedFilter.sigma * speedFilter.sigma + (growth * gpsAge).let { it * it }), v + 3.0)
        val dsSigma = sigmaV * dt + 0.01 * ds
        val obs = if (heading.initialized) HeadingObs(heading.headingDeg, max(heading.sigmaDeg, 0.5)) else null
        matcher.deadReckon(t, dt, v, dsSigma, obs)
        lastDrT = t
        mapAidedHeading(t)
    }

    /** トンネル内: 直線区間で確信度が高ければ道路方位でジャイロのドリフトを抑える */
    private fun mapAidedHeading(t: Long) {
        val r = matcher.result
        // 分岐の可能性（別車線の仮説）が残っている間は地図方位を使わない（判定を歪めないため）
        if (!r.onRoad || !r.deadReckoning || r.confidence < 0.999) return
        if (lastMapHeadingT != Long.MIN_VALUE && t - lastMapHeadingT < 1_000_000_000L) return
        val e = graph.edges[r.edge]
        if (r.offset < 60.0 || e.length - r.offset < 60.0) return // 分岐・合流の近く
        if (graph.outEdges(e.from).size >= 2 && r.offset - r.alongSigma < 400.0) return // 分岐直後
        val b0 = e.smoothBearingAt(r.offset, 20.0)
        val bA = e.smoothBearingAt(r.offset - 40.0, 20.0)
        val bB = e.smoothBearingAt(r.offset + 40.0, 20.0)
        if (abs(Geo.diffDeg(bA, b0)) > 3.0 || abs(Geo.diffDeg(bB, b0)) > 3.0) return
        heading.updateAngle(b0 * Geo.DEG, 3.0 * Geo.DEG, t, HeadingFilter.Source.MAP, 4.0)
        lastMapHeadingT = t
    }

    private fun trackTraffic(t: Long) {
        if (lastTrafficT == Long.MIN_VALUE) {
            lastTrafficT = t
            return
        }
        val dt = (t - lastTrafficT) * 1e-9
        lastTrafficT = t
        val r = matcher.result
        if (!r.onRoad || dt <= 0 || dt > 5) return
        val e = graph.edges[r.edge]
        if (e.attr.isLink) return
        val vFree = currentFreeSpeed() ?: run {
            val lim = SpeedLimitModel.limitFor(e.attr).kmh
            lim / 3.6 * driver.ratio(lim)
        }
        traffic.add(t * 1e-9, dt, speedFilter.v * dt, vFree)
    }

    private fun currentFreeSpeed(): Double? {
        val p = profile ?: return null
        val prog = currentProgress
        if (prog.isNaN()) return null
        val k = min(p.cells - 1, max(0, (prog / p.cell).toInt()))
        return eta.freeSpeed(p, k)
    }

    private fun learnDriver(t: Long, vGps: Double?) {
        val prevT = lastLearnT
        lastLearnT = t
        if (vGps == null || prevT == Long.MIN_VALUE) return
        val dt = min((t - prevT) * 1e-9, 2.0)
        val r = matcher.result
        if (!r.onRoad || r.deadReckoning || r.confidence < 0.9 || stationary) return
        val e = graph.edges[r.edge]
        if (e.attr.isLink) return
        val lim = SpeedLimitModel.limitFor(e.attr).kmh
        val ratio = vGps * 3.6 / lim
        if (ratio < 0.75) return // 渋滞・合流中などは学習しない
        val p = profile
        val prog = currentProgress
        if (p != null && !prog.isNaN()) {
            val k = min(p.cells - 1, max(0, (prog / p.cell).toInt()))
            if (p.vCap[k] < lim / 3.6 * 1.1) return // カーブ・料金所付近
            val k2 = min(p.cells - 1, k + 20)
            for (i in k..k2) if (p.vCap[i] < 25.0 / 3.6) return
        }
        driver.learn(lim, ratio, dt)
    }

    private fun progressOn(rt: RouteAhead, edge: Int, offset: Double): Double? {
        for (s in rt.segments) {
            if (s.edge == edge && offset >= s.startOffset - 1.0 && offset <= s.endOffset + 1.0) {
                return s.startDist + (offset - s.startOffset)
            }
        }
        return null
    }

    private fun updateOutputs(t: Long, wallMs: Long) {
        lastOutputT = t
        val r = matcher.result
        val fix = lastFix
        val gpsAge = if (lastGoodFixT == Long.MIN_VALUE) null else (t - lastGoodFixT) * 1e-9
        val status = when {
            generalRoadMode -> EngineStatus.GENERAL_ROAD_MODE
            fix == null -> EngineStatus.WAITING_GPS
            graph.isEmpty || r.status == MatchStatus.NO_DATA -> EngineStatus.LOADING_MAP
            r.onRoad && (r.confidence >= 0.5 || r.stableCount >= 2) -> EngineStatus.ON_HIGHWAY
            else -> EngineStatus.OFF_HIGHWAY
        }
        val speedKmh = if (speedFilter.initialized) speedFilter.v * 3.6 else null
        val speedSource = when {
            vehicle.speedFresh(t) -> "車速信号"
            gpsAge != null && gpsAge < 2.5 -> "GPS"
            else -> "推定"
        }
        val hdg = if (heading.initialized) heading.headingDeg else null
        val hdgSigma = if (heading.initialized) heading.sigmaDeg else null
        val srcLabel = when {
            !heading.initialized -> "-"
            r.deadReckoning -> "ジャイロ"
            else -> when (heading.lastSource) {
                HeadingFilter.Source.GPS -> "GPS"
                HeadingFilter.Source.COMPASS -> "コンパス"
                HeadingFilter.Source.MAP -> "地図"
                else -> "ジャイロ"
            }
        }

        if (status == EngineStatus.OFF_HIGHWAY && fix != null) {
            val rs = restStopSnapshot(t, wallMs, fix)
            if (rs != null) {
                currentProgress = Double.NaN
                snapshot = rs
                return
            }
        }
        if (status != EngineStatus.ON_HIGHWAY) {
            currentProgress = Double.NaN
            snapshot = HighwaySnapshot(
                status, wallMs, null, null, false, null, null, speedKmh, null, false,
                hdg, hdgSigma, srcLabel, r.deadReckoning, fix?.accuracyM, gpsAge, r.confidence, emptyList(),
                compass.calibrated, if (compass.calibrated) compass.offsetDeg else null, forwardAxis.valid,
                heading.biasDegPerSec, null, null, fix?.lat, fix?.lon, dataMessage, 0.0, false,
                traffic = trafficView(null, null, wallMs, 0.0), vehicle = vehicleView(t, null, 0.0), speedSource = speedSource
            )
            return
        }

        // ---- 前方経路（同じ車線上を進んでいる間は作り直さず進捗だけ更新）
        var rt = route
        var prog: Double? = null
        if (rt != null && rt.graphGeneration == graph.generation) prog = progressOn(rt, r.edge, r.offset)
        if (rt == null || prog == null || rt.graphGeneration != graph.generation ||
            t - routeBuiltT > (config.routeRefreshSec * 1e9).toLong()
        ) {
            rt = routeBuilder.build(graph, r.edge, r.offset)
            route = rt
            profile = eta.buildProfile(rt, graph)
            routeBuiltT = t
            prog = 0.0
        }
        // マッチ結果は最後の GPS 測位時刻のもの → 現在時刻まで車速で外挿（最大 3 秒）
        val lagSec = ((t - r.timeNanos) * 1e-9).coerceIn(0.0, 3.0)
        val progress: Double = ((prog ?: 0.0) + speedFilter.v * lagSec).coerceAtMost(rt.length)
        currentProgress = progress
        val pf = profile!!
        // 外部の交通情報を前方経路に対応づける（経路を作り直したときだけ）
        val et = edgeTraffic
        if (et != null && et.graphGeneration == graph.generation && routeTrafficFor !== rt) {
            routeTraffic = TrafficMapper.forRoute(rt, graph, et, pf.cell)
            routeTrafficFor = rt
        } else if (et == null) {
            routeTraffic = null
            routeTrafficFor = null
        }
        val rtf = routeTraffic
        val tw = trafficWeight(wallMs)
        // 渋滞（量・位置・最後尾の動き）を含めた交通情報
        val ext: ExtTraffic? = if (rtf != null) {
            if (jamSpansFor !== rtf) {
                jamSpans = JamModel.spans(rtf, rt, graph, jamTracker)
                jamSpansFor = rtf
            }
            ExtTraffic(rtf.absPace, rtf.extraPace, jamSpans, tw, max(0.0, rtf.ageSec(wallMs)))
        } else null
        val res = eta.predict(pf, progress, speedFilter.v, traffic.state(), ext)
        lastEta = res
        val events = if (rtf != null && tw > 0.05) rtf.events.filter { it.end > progress } else emptyList()

        val views = ArrayList<FacilityView>()
        val cams = ArrayList<FacilityView>()
        val ids = HashSet<String>()
        var prevDist = progress
        for (uf in rt.facilities) {
            if (uf.distance <= progress + 2.0) continue
            if (!RouteAheadBuilder.isDisplayable(uf.facility)) continue
            val f = uf.facility
            val isCam = f.type == FacilityType.CAMERA
            if (isCam) {
                if (cams.size >= 5 || uf.distance - progress > 30_000.0) continue
            } else if (views.size >= config.maxFacilities) continue
            val travel = res.timeTo(uf.distance)
            val shown = etaSmoother.update(f.id, wallMs + travel * 1000.0)
            ids.add(f.id)
            val from = if (isCam) uf.distance else prevDist
            var jam = 0.0
            var note: String? = null
            var closed = false
            for (ev in events) {
                if (ev.start < uf.distance && ev.end > progress && ev.start <= uf.distance) {
                    if (ev.kind == TrafficKind.CLOSURE) closed = true
                }
                if (ev.end <= from || ev.start >= uf.distance) continue
                if (ev.kind == TrafficKind.JAM || ev.kind == TrafficKind.SLOW) {
                    jam += min(ev.end, uf.distance) - max(ev.start, from)
                } else if (note == null) note = ev.kind.label
            }
            val v = FacilityView(
                id = f.id, type = f.type, name = f.listName, displayName = f.displayName,
                distanceM = uf.distance - progress, etaEpochMs = shown, travelSec = travel,
                amenities = f.sortedAmenities(), etcOnly = f.etcOnly, etcReason = f.etcReason,
                pois = f.pois.toList(), lat = f.lat, lon = f.lon, exitRef = f.exitRef,
                destination = f.destination, directionNote = f.directionNote,
                typeInferred = f.typeInferred, flowBefore = res.flowRatio(prevDist, uf.distance),
                jamBeforeM = jam, trafficNote = note, closedBefore = closed,
                maxspeedKmh = f.maxspeedKmh, cameraType = f.cameraType, userDefined = f.userDefined,
                noExit = f.noExit
            )
            if (isCam) cams.add(v) else {
                views.add(v)
                prevDist = uf.distance
            }
        }
        etaSmoother.retain(ids)

        val e = graph.edges[r.edge]
        val lim = SpeedLimitModel.limitFor(e.attr)
        val roadBearing = e.smoothBearingAt(r.offset)
        val firstChange = rt.roadChanges.firstOrNull { it.distance > progress }
        val horizon = firstChange?.distance ?: rt.length
        val terminal = rt.facilities.lastOrNull {
            it.distance <= horizon + 50 && it.distance > progress &&
                (it.facility.type == FacilityType.JCT || it.facility.type.isExit)
        }
        val dirWord = Geo.compassJa(roadBearing) + "行き"
        val directionText = if (terminal != null) "$dirWord・${terminal.facility.displayName}方面" else dirWord
        val nextRoadText = firstChange?.let { ch ->
            val km = (ch.distance - progress) / 1000.0
            if (km > 80) null else {
                val jct = rt.facilities.firstOrNull { it.facility.type == FacilityType.JCT && abs(it.distance - ch.distance) < 2500 }
                val to = ch.toName ?: ch.toRef ?: "別路線"
                if (jct != null) "${fmtKm(km)}先 ${jct.facility.displayName}から $to" else "${fmtKm(km)}先 $to へ"
            }
        }
        val roadName = e.attr.displayName ?: if (e.attr.isLink) {
            firstChange?.toName?.let { "ランプ（$it へ）" } ?: "ランプ"
        } else null
        val tr = traffic.state()

        snapshot = HighwaySnapshot(
            status = status, wallTimeMs = wallMs,
            roadName = roadName, roadRef = e.attr.displayRef, onLink = e.attr.isLink,
            directionText = directionText, nextRoadText = nextRoadText,
            speedKmh = speedKmh, limitKmh = lim.kmh.toInt(), limitTagged = lim.tagged,
            headingDeg = hdg, headingSigmaDeg = hdgSigma, headingSource = srcLabel,
            deadReckoning = r.deadReckoning, gpsAccuracyM = fix?.accuracyM, gpsAgeSec = gpsAge,
            matchConfidence = r.confidence, facilities = views,
            compassCalibrated = compass.calibrated,
            compassOffsetDeg = if (compass.calibrated) compass.offsetDeg else null,
            forwardAxisValid = forwardAxis.valid, gyroBiasDegPerSec = heading.biasDegPerSec,
            driverRatio = driver.ratio(lim.kmh), trafficRatio = if (tr.valid) tr.ratio else null,
            lat = r.lat, lon = r.lon, dataMessage = dataMessage,
            routeLengthM = rt.length - progress, routeTruncated = rt.end == RouteEnd.DATA_BOUNDARY,
            cameras = cams, traffic = trafficView(rtf, events, wallMs, progress, res, ext),
            vehicle = vehicleView(t, rt, progress), speedSource = speedSource,
            generalRoad = e.attr.generalRoad
        )
        lastOnSnapshot = snapshot
        lastOnT = t
    }

    /**
     * 高速道路を外れた直後に SA/PA 区域内で停車している場合、直前の前方施設を
     * 「今出発したら」の到着予定で表示し続ける。
     */
    private fun restStopSnapshot(t: Long, wallMs: Long, fix: GpsFix): HighwaySnapshot? {
        val lo = lastOnSnapshot ?: return null
        if (lastOnT == Long.MIN_VALUE || t - lastOnT > REST_HOLD_NANOS) return null
        val area = graph.restAreaAt(fix.lat, fix.lon) ?: return null
        val views = lo.facilities.map { f ->
            FacilityView(
                f.id, f.type, f.name, f.displayName, f.distanceM,
                ((wallMs + f.travelSec * 1000.0) / 60_000.0).let { Math.round(it) * 60_000L },
                f.travelSec, f.amenities, f.etcOnly, f.etcReason, f.pois, f.lat, f.lon,
                f.exitRef, f.destination, f.directionNote, f.typeInferred, f.flowBefore,
                f.jamBeforeM, f.trafficNote, f.closedBefore, f.maxspeedKmh, f.cameraType, f.userDefined, f.noExit, f.jamEndM
            )
        }
        val hdg = if (heading.initialized) heading.headingDeg else null
        return HighwaySnapshot(
            status = EngineStatus.REST_STOP, wallTimeMs = wallMs,
            roadName = lo.roadName, roadRef = lo.roadRef, onLink = false,
            directionText = "${area.facility.displayName} に停車中", nextRoadText = lo.nextRoadText,
            speedKmh = if (speedFilter.initialized) speedFilter.v * 3.6 else null,
            limitKmh = lo.limitKmh, limitTagged = lo.limitTagged,
            headingDeg = hdg, headingSigmaDeg = if (heading.initialized) heading.sigmaDeg else null,
            headingSource = lo.headingSource, deadReckoning = false, gpsAccuracyM = fix.accuracyM,
            gpsAgeSec = (t - lastGoodFixT) * 1e-9, matchConfidence = 0.0, facilities = views,
            compassCalibrated = compass.calibrated, compassOffsetDeg = lo.compassOffsetDeg,
            forwardAxisValid = forwardAxis.valid, gyroBiasDegPerSec = heading.biasDegPerSec,
            driverRatio = lo.driverRatio, trafficRatio = null, lat = fix.lat, lon = fix.lon,
            dataMessage = dataMessage ?: "到着予定は「今から出発した場合」", routeLengthM = lo.routeLengthM,
            routeTruncated = lo.routeTruncated, cameras = emptyList(), traffic = lo.traffic,
            vehicle = vehicleView(t, route, if (currentProgress.isNaN()) 0.0 else currentProgress),
            speedSource = lo.speedSource, generalRoad = lo.generalRoad
        )
    }

    private fun trafficView(
        rtf: TrafficMapper.RouteTraffic?,
        events: List<TrafficMapper.RouteEvent>?,
        wallMs: Long,
        progress: Double,
        res: EtaResult? = null,
        ext: ExtTraffic? = null
    ): TrafficView? {
        val td = trafficData
        if (td == null && trafficMessage == null) return null
        val ev = events ?: emptyList()
        val jam = ev.firstOrNull { (it.kind == TrafficKind.JAM || it.kind == TrafficKind.SLOW) && it.end > progress + 50 }
        var jamDelay: Double? = null
        var jamLen: Double? = null
        var jamDist: Double? = jam?.let { max(0.0, it.start - progress) }
        var trend = 0
        // 渋滞の伸び縮みがわかれば、いまの最後尾の推定位置から。遅れは到着予測（伸び縮み込み）から
        val useSpans = ext != null && ext.weight > 0.05 && ext.jams.isNotEmpty()
        // 取得からいままでに解消したとみられる渋滞は除く
        val span = if (useSpans) ext!!.jams.filter { it.head > progress + 50 && it.tailAfter(ext.ageSec) < it.head - 100.0 }.minByOrNull { it.tail } else null
        if (useSpans) {
            jamDist = null
            if (span != null && res != null) {
                val tailNow = span.tailAfter(ext!!.ageSec)
                jamDist = max(0.0, tailNow - progress)
                jamLen = span.head - max(tailNow, progress)
                trend = span.trend
                // 1 つ手前の渋滞の先頭から、この渋滞の先頭までの遅れ（最後尾が伸びた分を含む）
                val prevHead = ext.jams.filter { it.head <= span.tail && it.head > progress }.maxOfOrNull { it.head } ?: progress
                jamDelay = res.delayBetween(max(progress, prevHead), span.head).takeIf { it >= 0.0 }
            }
        } else if (jam != null) {
            // 連続する渋滞・混雑を 1 つにまとめて長さと遅れを出す
            var end = jam.end
            var delay = 0.0
            var known = false
            for (x in ev) {
                if (x.kind != TrafficKind.JAM && x.kind != TrafficKind.SLOW) continue
                if (x.start > end + 300) break
                if (x.end > end) end = x.end
                x.delaySec?.let { delay += it; known = true }
                if (x.delaySec == null && x.ratio != null && x.ratio > 0.02) {
                    val l = x.end - max(x.start, progress)
                    delay += l / 22.2 * (1 / x.ratio - 1)
                    known = true
                }
            }
            jamLen = end - max(jam.start, progress)
            jamDelay = if (known) delay else null
        }
        val routeDelay = if (res != null && rtf != null && rtf.hasCoverage) {
            var d = 0.0
            for (k in res.vPred.indices) {
                val l = res.bound[k + 1] - res.bound[k]
                d += l / max(res.vPred[k], 0.5) - l / max(res.vFree[k], 0.5)
            }
            d.coerceAtLeast(0.0)
        } else null
        return TrafficView(
            provider = td?.provider, fetchedWallMs = td?.fetchedWallMs, message = trafficMessage,
            nextJamDistanceM = if (jamLen != null) jamDist else null, nextJamLengthM = jamLen, nextJamDelaySec = jamDelay,
            events = ev.filter { it.kind.isEvent || it.kind == TrafficKind.JAM }.take(8).map { Triple(max(0.0, it.start - progress), it.kind, it.description) },
            routeDelaySec = routeDelay,
            nextJamTrend = trend
        )
    }

    private fun vehicleView(t: Long, rt: RouteAhead?, progress: Double): VehicleView? {
        val v = vehicle
        if (!v.connected) return null
        var advice: String? = null
        var level = 0
        val range = v.rangeRemainingM
        if (range != null && rt != null && rt.graphGeneration == graph.generation) {
            val want = if (v.electric) Amenity.EV else Amenity.GAS
            val stops = rt.facilities.filter { it.distance > progress + 200 && it.facility.type.isRestArea && want in it.facility.amenities }
            val s1 = stops.getOrNull(0)
            val s2 = stops.getOrNull(1)
            val what = if (v.electric) "充電" else "給油"
            val kmR = range / 1000.0
            when {
                s1 == null && kmR < 60 -> {
                    advice = String.format(Locale.JAPAN, "航続%.0fkm：前方に%sできるSA/PAが見つかりません", kmR, what); level = 2
                }
                s1 != null && s1.distance - progress > range -> {
                    advice = String.format(Locale.JAPAN, "航続%.0fkm：次の%s（%s）まで%.0fkm", kmR, what, s1.facility.displayName, (s1.distance - progress) / 1000); level = 2
                }
                s1 != null && (s2 == null && kmR < 120 || s2 != null && s2.distance - progress > range) -> {
                    advice = String.format(Locale.JAPAN, "次の%sで%sを（航続%.0fkm）", s1.facility.displayName, what, kmR); level = 1
                }
                v.energyLow == true -> {
                    advice = "燃料残量が少なくなっています"; level = 1
                }
            }
        } else if (v.energyLow == true) {
            advice = "燃料残量が少なくなっています"
            level = 1
        }
        return VehicleView(
            connected = true, model = v.model, speedSignal = v.speedFresh(t), tollCard = v.tollCard,
            rangeRemainingM = range, fuelPercent = v.fuelPercent ?: v.batteryPercent, energyLow = v.energyLow,
            fuelAdvice = advice, fuelAdviceLevel = level, vehicleGyro = vehicleYaw.active && vehicleYaw.fresh(t)
        )
    }

    companion object {
        private val COS_12 = cos(12.0 * Geo.DEG)
        private const val PREFETCH_AHEAD = 40_000.0
        /** SA/PA 停車中に直前の表示を保持する最長時間（2 時間） */
        private const val REST_HOLD_NANOS = 2L * 3600L * 1_000_000_000L

        fun fmtKm(km: Double): String =
            if (km < 10) String.format(Locale.US, "%.1fkm", km) else String.format(Locale.US, "%.0fkm", km)
    }
}

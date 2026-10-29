package com.example.highwaymode.core

import com.example.highwaymode.core.engine.EngineStatus
import com.example.highwaymode.core.engine.HighwayEngine
import com.example.highwaymode.core.geo.Geo
import com.example.highwaymode.core.graph.RoadContinuity
import com.example.highwaymode.core.graph.RoadGraph
import com.example.highwaymode.core.model.GpsFix
import com.example.highwaymode.core.route.RouteAhead
import com.example.highwaymode.core.route.RouteAheadBuilder
import com.example.highwaymode.core.route.RouteEnd
import com.example.highwaymode.core.route.RouteSegment
import com.example.highwaymode.core.sim.JamZone
import com.example.highwaymode.core.sim.SimParams
import com.example.highwaymode.core.sim.SimPath
import com.example.highwaymode.core.sim.SimSink
import com.example.highwaymode.core.sim.TraceSimulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 合成道路網上で「実車相当のノイズ」を持つ GPS・ジャイロ・加速度・コンパスを生成し、
 * エンジン全体（方位フィルタ → HMM マップマッチング → 前方経路 → ETA）を評価する。
 */
class EngineSimulationTest {

    companion object {
        lateinit var net: TestNetwork
        lateinit var graph: RoadGraph

        @BeforeClass
        @JvmStatic
        fun setup() {
            net = TestNetwork().build()
            graph = net.graph()
        }
    }

    private class EngineSink(val e: HighwayEngine) : SimSink {
        override fun gyro(t: Long, x: Double, y: Double, z: Double) = e.onGyro(t, x, y, z)
        override fun accel(t: Long, x: Double, y: Double, z: Double) = e.onAccel(t, x, y, z)
        override fun gravity(t: Long, x: Double, y: Double, z: Double) = e.onGravity(t, x, y, z)
        override fun rotation(t: Long, r: DoubleArray, fieldUt: Double, expectedUt: Double) = e.onRotationMatrix(t, r, fieldUt, expectedUt)
        override fun gps(fix: GpsFix) = e.onGps(fix)
        override fun vehicleSpeed(t: Long, rawMs: Double) = e.onVehicleSpeed(t, rawMs, null)
        override fun vehicleGyro(t: Long, x: Double, y: Double, z: Double) = e.onVehicleGyro(t, x, y, z)
    }

    private fun southboundRoute(fromS: Double): RouteAhead {
        val p0 = net.sbPoint(fromS)
        val p = graph.nearby(p0.lat, p0.lon, 20.0) { it.wayId in net.sbWayIds }.first()
        return RouteAheadBuilder().build(graph, p.edge, p.offset)
    }

    private fun newEngine(): HighwayEngine {
        val e = HighwayEngine()
        e.setGraph(graph)
        return e
    }

    /** シナリオ評価結果 */
    private class DriveMetrics(
        val matchRate: Double,
        val headingRms: Double,
        val tunnelMaxHeadingErr: Double,
        val openPosRms: Double,
        val tunnelMaxPosErr: Double,
        val tunnelExitPosErr: Double,
        /** 距離帯ごとの MAE [s]: [本アプリ, 距離÷現在速度, 制限速度] */
        val etaMae: Map<String, DoubleArray>,
        val etaTotal: DoubleArray,
        val engine: HighwayEngine
    )

    private fun runSouthboundDrive(
        title: String,
        params: SimParams,
        seed: Int,
        setup: (HighwayEngine, TraceSimulator) -> Unit = { _, _ -> }
    ): DriveMetrics {
        val route = southboundRoute(500.0)
        val path = SimPath.fromRoute(route, graph)
        val sim = TraceSimulator(path, params, seed = seed)
        val engine = newEngine()
        setup(engine, sim)
        val sink = EngineSink(engine)

        // 実到着時刻（経路距離 = 施設距離）
        val facDist = route.facilities.associate { it.facility.id to it.distance }
        val arrival = HashMap<String, Double>()
        class Pred(val id: String, val tSec: Double, val dist: Double, val ours: Double, val naiveSpeed: Double, val naiveLimit: Double)
        val preds = ArrayList<Pred>()

        var ticks = 0
        var onRoadTicks = 0
        var wrongCarriageway = 0
        var headingSqSum = 0.0
        var headingN = 0
        var tunnelMaxHeadingErr = 0.0
        var tunnelMaxPosErr = 0.0
        var tunnelExitPosErr = Double.NaN
        var posSqSum = 0.0
        var posN = 0
        var lastTunnelT = -1e9
        var lastFixT = Long.MIN_VALUE
        val t0 = sim.t

        while (sim.advance(0.25, sink)) {
            val snap = engine.onTick(sim.t, sim.wallMs())
            ticks++
            val tSec = (sim.t - t0) * 1e-9
            val trueS = sim.s
            for ((id, d) in facDist) if (arrival[id] == null && trueS >= d) arrival[id] = tSec
            val tunnel = sim.inTunnel
            if (tunnel) lastTunnelT = tSec
            if (tSec < 20) continue
            val r = engine.matcher.result
            if (r.onRoad) {
                onRoadTicks++
                val e = graph.edges[r.edge]
                if (e.wayId !in net.sbWayIds) wrongCarriageway++
                val tp = sim.trueLatLon
                if (tunnel) {
                    // 推測航法で更新された位置（表示は測位時刻からの外挿も行うが、ここでは生の推定位置）
                    if (r.deadReckoning && sim.t - r.timeNanos < 300_000_000L) {
                        tunnelMaxPosErr = maxOf(tunnelMaxPosErr, Geo.distance(tp.lat, tp.lon, r.lat, r.lon))
                    }
                } else if (!r.deadReckoning && r.timeNanos != lastFixT && sim.t - r.timeNanos < 260_000_000L) {
                    // GPS 受信中: 測位直後の tick で、測位時刻の真位置と比較（測位〜tick の走行分を差し引く）
                    lastFixT = r.timeNanos
                    val lag = (sim.t - r.timeNanos) * 1e-9
                    val back = path.pointAt(maxOf(0.0, trueS - sim.v * lag))
                    val err = Geo.distance(back.lat, back.lon, r.lat, r.lon)
                    if (tSec - lastTunnelT > 10.0) { posSqSum += err * err; posN++ }
                    else if (tunnelExitPosErr.isNaN()) tunnelExitPosErr = err
                }
            }
            if (engine.heading.initialized && sim.v > 5) {
                val err = abs(Geo.diffDeg(engine.heading.headingDeg, sim.trueHeading))
                if (tunnel) tunnelMaxHeadingErr = maxOf(tunnelMaxHeadingErr, err)
                else {
                    headingSqSum += err * err
                    headingN++
                }
            }
            // ETA の記録（1 秒ごと）
            val spKmh = snap.speedKmh
            if (ticks % 4 == 0 && snap.status == EngineStatus.ON_HIGHWAY && spKmh != null) {
                for (f in snap.facilities) {
                    val d = f.distanceM
                    // 比較用: (A) 距離÷現在速度  (B) 制限速度ちょうどで走った場合
                    val vNow = maxOf(spKmh / 3.6, 1.0)
                    val fd = facDist[f.id] ?: continue
                    var tLimit = 0.0
                    var x = trueS
                    while (x < fd) {
                        val seg = path.segAt(x)
                        val stepD = minOf(50.0, fd - x)
                        tLimit += stepD / (path.limitKmh[seg] / 3.6)
                        x += stepD
                    }
                    preds.add(Pred(f.id, tSec, d, f.travelSec, d / vNow, tLimit))
                }
            }
        }

        val matchRate = 1.0 - wrongCarriageway.toDouble() / onRoadTicks
        val headingRms = sqrt(headingSqSum / headingN)
        val posRms = sqrt(posSqSum / maxOf(1, posN))
        println("=== $title ===")
        println("走行車線（上下線）の正解率: %.3f%%  (%d / %d tick)".format(matchRate * 100, onRoadTicks - wrongCarriageway, onRoadTicks))
        println("方位誤差 RMS（GPS 受信中）: %.2f° / 最大（トンネル 5km, GPS 無し）: %.2f°".format(headingRms, tunnelMaxHeadingErr))
        println("道路上位置の誤差 RMS（GPS 受信中）: %.1fm".format(posRms))
        println("位置誤差 最大（トンネル内 推測航法）: %.1fm / トンネル出口後の最初の測位: %.1fm".format(tunnelMaxPosErr, tunnelExitPosErr))
        println("ジャイロバイアス推定: %.4f°/s（真値は約 %.4f°/s） / 前方軸推定: %s".format(engine.heading.biasDegPerSec, trueYawBiasDeg(), engine.forwardAxis.valid))
        println("学習した運転傾向（制限100帯）: %.3f, （80帯）: %.3f（真値 %.2f）".format(engine.driver.ratio(100.0), engine.driver.ratio(80.0), params.driverRatio))

        val bands = listOf("1-5km" to (1000.0 to 5000.0), "5-15km" to (5000.0 to 15000.0), "15-40km" to (15000.0 to 40000.0))
        println("到着予測の平均絶対誤差 [秒]  本アプリ / 距離÷現在速度 / 制限速度で走行")
        val mae = LinkedHashMap<String, DoubleArray>()
        val total = DoubleArray(3)
        var nTotal = 0
        for ((name, range) in bands) {
            val acc = DoubleArray(3)
            var n = 0
            for (p in preds) {
                if (p.dist < range.first || p.dist >= range.second) continue
                val arr = arrival[p.id] ?: continue
                val actual = arr - p.tSec
                acc[0] += abs(p.ours - actual); acc[1] += abs(p.naiveSpeed - actual); acc[2] += abs(p.naiveLimit - actual)
                n++
            }
            if (n == 0) continue
            for (i in 0..2) total[i] += acc[i]
            nTotal += n
            val m = DoubleArray(3) { acc[it] / n }
            mae[name] = m
            println("  %-8s n=%5d : %6.1f / %6.1f / %6.1f".format(name, n, m[0], m[1], m[2]))
        }
        for (i in 0..2) total[i] /= maxOf(1, nTotal)
        println("  全体     n=%5d : %6.1f / %6.1f / %6.1f".format(nTotal, total[0], total[1], total[2]))
        return DriveMetrics(matchRate, headingRms, tunnelMaxHeadingErr, posRms, tunnelMaxPosErr, tunnelExitPosErr, mae, total, engine)
    }

    @Test
    fun freeFlowDrive_matchingHeadingDeadReckoningAndEta() {
        val m = runSouthboundDrive(
            "順調な流れ（下り 59km, トンネル 5km, 運転傾向 制限×1.08）",
            SimParams(driverRatio = 1.08, initialSpeedKmh = 95.0), seed = 7
        )
        assertTrue("上下線の判定率 > 99.5%", m.matchRate > 0.995)
        assertTrue("方位RMS < 1.0°", m.headingRms < 1.0)
        assertTrue("トンネル内方位誤差 < 2°", m.tunnelMaxHeadingErr < 2.0)
        assertTrue("道路上位置 RMS < 8m", m.openPosRms < 8.0)
        assertTrue("トンネル内位置誤差 < 120m", m.tunnelMaxPosErr < 120.0)
        // 順調時は「制限速度で走行」「距離÷現在速度」の半分以下の誤差
        assertTrue("ETA 誤差", m.etaTotal[0] < 0.5 * m.etaTotal[1] && m.etaTotal[0] < 0.5 * m.etaTotal[2])
    }

    @Test
    fun congestedDrive_etaAdaptsToJam() {
        val m = runSouthboundDrive(
            "渋滞あり（11.5〜14.5km を 20km/h, 予告なし）",
            SimParams(driverRatio = 1.08, initialSpeedKmh = 95.0, jams = listOf(JamZone(11_500.0, 14_500.0, 20.0))), seed = 7
        )
        assertTrue(m.matchRate > 0.995)
        assertTrue("ETA 誤差が単純法より小さい", m.etaTotal[0] < m.etaTotal[1] && m.etaTotal[0] < m.etaTotal[2])
    }

    @Test
    fun congestedDrive_withTrafficInfo_etaKnowsJamInAdvance() {
        val jam = JamZone(11_500.0, 14_500.0, 20.0)
        val params = SimParams(driverRatio = 1.08, initialSpeedKmh = 95.0, jams = listOf(jam))
        val plain = runSouthboundDrive("（比較）同じ渋滞・交通情報なし", params, seed = 7)
        val m = runSouthboundDrive("渋滞あり（11.5〜14.5km を 20km/h）・交通情報あり（出発時に取得）", params, seed = 7) { e, sim ->
            // 経路上 500m 地点から走るので、道路上の位置は +500m
            val pts = (0..60).map { net.sbPoint(500.0 + jam.start + (jam.end - jam.start) * it / 60.0) }
            val cover = (0..300).map { net.sbPoint(500.0 + 200.0 * it) }
            val item = com.example.highwaymode.core.traffic.TrafficItem(
                pts.map { it.lat }.toDoubleArray(), pts.map { it.lon }.toDoubleArray(),
                com.example.highwaymode.core.traffic.TrafficKind.JAM, 20.0 / (100.0 * 1.08), delaySec = 400.0, description = "渋滞"
            )
            e.setTraffic(
                com.example.highwaymode.core.traffic.TrafficData(
                    "TomTom", sim.wallMs(), listOf(item), true,
                    cover.map { it.lat }.toDoubleArray(), cover.map { it.lon }.toDoubleArray()
                )
            )
        }
        assertTrue(m.matchRate > 0.995)
        val far = m.etaMae["15-40km"]!![0]
        val farPlain = plain.etaMae["15-40km"]!![0]
        println("交通情報の効果（15-40km 先の到着予測誤差）: %.0f 秒 → %.0f 秒".format(farPlain, far))
        assertTrue("交通情報で遠方の到着予測が改善", far < 0.5 * farPlain)
        assertTrue(m.etaTotal[0] < 0.6 * plain.etaTotal[0])
    }

    @Test
    fun vehicleSpeedSignalImprovesTunnelDeadReckoning() {
        val params = SimParams(driverRatio = 1.08, initialSpeedKmh = 95.0, vehicleSpeedHz = 10.0, vehicleSpeedScale = 1.03, vehicleGyroHz = 20.0)
        val m = runSouthboundDrive("Android Auto 接続（車速信号 10Hz・メーター誤差 +3%・車両ジャイロ）", params, seed = 7)
        val v = m.engine.vehicle
        val cal = m.engine.vehicleYawCalibrator
        println("車速信号の倍率学習: %.4f（真値 %.4f）, 車両ジャイロ使用: %s（軸 %d, 符号 %.0f, 相関 %.4f）".format(
            v.scale, 1 / 1.03, m.engine.snapshot.vehicle?.vehicleGyro, cal.axis, cal.sign, cal.correlation))
        assertEquals(1 / 1.03, v.scale, 0.006)
        assertTrue("車速信号ありのトンネル内位置誤差 < 20m", m.tunnelMaxPosErr < 20.0)
        assertTrue(m.matchRate > 0.995)
        assertTrue("車両ジャイロを使用", m.engine.snapshot.vehicle?.vehicleGyro == true)
        assertTrue("トンネル内方位誤差 < 2°", m.tunnelMaxHeadingErr < 2.0)
    }

    private fun trueYawBiasDeg(): Double {
        // シミュレータのバイアス (0.003,-0.004,0.002) を上方向 (0,cos15°,sin15°) に射影（方位変化率は符号反転）
        val c = kotlin.math.cos(15.0 * Geo.DEG)
        val s = kotlin.math.sin(15.0 * Geo.DEG)
        return -(-0.004 * c + 0.002 * s) * Geo.RAD
    }

    /** 指定ウェイを優先して辿る経路（JCT でランプへ入る経路を作るため） */
    private fun forcedRoute(startS: Double, preferWays: Set<Long>, length: Double): RouteAhead {
        val p0 = net.sbPoint(startS)
        val p = graph.nearby(p0.lat, p0.lon, 20.0) { it.wayId in net.sbWayIds }.first()
        val segs = ArrayList<RouteSegment>()
        var cur = graph.edges[p.edge]
        var off = p.offset
        var dist = 0.0
        while (dist < length) {
            val take = minOf(cur.length - off, length - dist)
            segs.add(RouteSegment(cur.id, off, off + take, dist))
            dist += take
            val succ = RoadContinuity.rankedSuccessors(graph, cur)
            if (succ.isEmpty()) break
            val next = succ.firstOrNull { graph.edges[it.first].wayId in preferWays }?.first ?: succ[0].first
            cur = graph.edges[next]
            off = 0.0
        }
        return RouteAhead(graph.generation, p.edge, p.offset, segs, dist, emptyList(), RouteEnd.MAX_LENGTH, 0.0, 0.0, emptyList(), emptyList())
    }

    private fun runBranchCase(takeLink: Boolean): Pair<Long, Boolean> {
        val prefer = if (takeLink) net.otherRoadWayIds + net.jctLinkWayId else net.sbWayIds
        val route = forcedRoute(26_000.0, prefer, 7_000.0)
        val path = SimPath.fromRoute(route, graph)
        // JCT（経路上 4km 地点）の手前 600m から分岐後 1.6km まで GPS を遮断
        val outage = 3_400.0..5_600.0
        val sim = TraceSimulator(path, SimParams(initialSpeedKmh = 80.0, gpsOutages = listOf(outage)), seed = 11)
        val engine = newEngine()
        val sink = EngineSink(engine)
        var decidedWay = -1L
        var drSeen = false
        while (sim.advance(0.25, sink)) {
            engine.onTick(sim.t, sim.wallMs())
            if (sim.s in outage) drSeen = drSeen || engine.matcher.result.deadReckoning
            if (sim.s >= outage.endInclusive - 30.0 && decidedWay == -1L) {
                val r = engine.matcher.result
                if (r.onRoad) decidedWay = graph.edges[r.edge].wayId
            }
        }
        return decidedWay to drSeen
    }

    @Test
    fun junctionBranchIsResolvedByGyroWithoutGps() {
        val (wayLink, dr1) = runBranchCase(takeLink = true)
        val (wayMain, dr2) = runBranchCase(takeLink = false)
        println("=== GPS 遮断中の JCT 分岐判定 ===")
        println("ランプへ進んだ場合: 推定ウェイ=$wayLink（ランプ/別線なら正解） 推測航法=$dr1")
        println("本線を直進した場合: 推定ウェイ=$wayMain（本線なら正解） 推測航法=$dr2")
        assertTrue(dr1 && dr2)
        assertTrue("ランプ側に判定", wayLink == net.jctLinkWayId || wayLink in net.otherRoadWayIds)
        assertTrue("本線側に判定", wayMain in net.sbWayIds)
    }

    @Test
    fun startingWhileStoppedUsesCompassPriorAndLocksOnAfterMoving() {
        // 渋滞で停止中にアプリを起動 → 動き出してから正しい車線に乗る
        val route = southboundRoute(3_000.0)
        val path = SimPath.fromRoute(route, graph)
        val sim = TraceSimulator(
            path,
            SimParams(initialSpeedKmh = 0.0, jams = listOf(JamZone(0.0, 300.0, 15.0))),
            seed = 3
        )
        val engine = newEngine()
        val sink = EngineSink(engine)
        var lockedAt = -1.0
        val t0 = sim.t
        while (sim.advance(0.25, sink) && sim.s < 3000) {
            engine.onTick(sim.t, sim.wallMs())
            val r = engine.matcher.result
            if (lockedAt < 0 && r.onRoad && r.confidence > 0.9 && graph.edges[r.edge].wayId in net.sbWayIds) {
                lockedAt = (sim.t - t0) * 1e-9
            }
        }
        println("=== 停止状態からの起動 ===  正しい車線にロック: %.1f 秒後".format(lockedAt))
        assertTrue(lockedAt in 0.0..30.0)
        assertEquals(EngineStatus.ON_HIGHWAY, engine.snapshot.status)
    }

    @Test
    fun bidirectionalRoadDirectionFromHeadingAndNoisyGps() {
        // 対面通行（1 本のウェイ）の別線を東→西へ。上下で形状が同じなので方位だけが手掛かり。
        val other = graph.edges.filter { it.wayId in net.otherRoadWayIds }
        val westbound = other.filter { !it.forwardOnWay }
        val far = westbound.maxByOrNull { it.lon[0] }!!
        val route = RouteAheadBuilder().build(graph, far.id, 5.0)
        val path = SimPath.fromRoute(route, graph)
        val sim = TraceSimulator(path, SimParams(initialSpeedKmh = 70.0, gpsNoise = 6.0, gpsWhite = 3.0), seed = 5)
        val engine = newEngine()
        val sink = EngineSink(engine)
        var on = 0
        var wrongDir = 0
        val t0 = sim.t
        while (sim.advance(0.25, sink)) {
            engine.onTick(sim.t, sim.wallMs())
            if ((sim.t - t0) * 1e-9 < 15) continue
            val r = engine.matcher.result
            if (r.onRoad) {
                on++
                val e = graph.edges[r.edge]
                if (e.wayId in net.otherRoadWayIds && e.forwardOnWay) wrongDir++
            }
        }
        println("=== 対面通行区間（GPS 雑音 大） === 逆方向に判定: %d / %d tick".format(wrongDir, on))
        assertTrue(on > 100)
        assertTrue(wrongDir.toDouble() / on < 0.005)
    }

    @Test
    fun parallelGeneralRoadIsNotTakenAsHighway() {
        // 下り線の 70m 東を並走する一般道（高速道路データには無い道）
        val src = SimPath.fromRoute(southboundRoute(1000.0), graph)
        val n = src.lat.size
        val la = DoubleArray(n)
        val lo = DoubleArray(n)
        for (i in 0 until n) {
            val s = src.cum[i]
            val h = src.headingAt(s) * Geo.DEG
            // 進行方向左（東）へ 70m
            val east = -kotlin.math.cos(h) * 70.0
            val north = kotlin.math.sin(h) * 70.0
            la[i] = src.lat[i] + north / Geo.metersPerDegLat(src.lat[i])
            lo[i] = src.lon[i] + east / Geo.metersPerDegLon(src.lat[i])
        }
        val cum = DoubleArray(n)
        for (i in 1 until n) cum[i] = cum[i - 1] + Geo.distance(la[i - 1], lo[i - 1], la[i], lo[i])
        val path = SimPath(la, lo, cum, DoubleArray(n - 1) { 60.0 }, BooleanArray(n - 1), emptyList())
        val sim = TraceSimulator(path, SimParams(initialSpeedKmh = 60.0, driverRatio = 1.0), seed = 9)
        val engine = newEngine()
        val sink = EngineSink(engine)
        var ticks = 0
        var onHighway = 0
        while (sim.advance(0.25, sink) && sim.s < 25_000) {
            val snap = engine.onTick(sim.t, sim.wallMs())
            ticks++
            if (snap.status == EngineStatus.ON_HIGHWAY) onHighway++
        }
        println("=== 70m 離れた並行一般道 === 高速道路と誤判定: %d / %d tick".format(onHighway, ticks))
        assertTrue(onHighway.toDouble() / ticks < 0.05)
    }

    @Test
    fun restStopAtServiceAreaKeepsUpcomingFacilities() {
        // 下り 17km → テストSA の出口ランプ → 駐車場（道路データの無い場所）で停車
        val route = forcedRoute(17_000.0, setOf(net.saLinkWayId), 2_900.0)
        val base = SimPath.fromRoute(route, graph)
        val n = base.lat.size
        val last = base.pointAt(base.length)
        val hd = base.headingAt(base.length) * Geo.DEG
        // 進行方向の右（SA 区域の奥＝東側）へ 45m 入った駐車枠
        val east = kotlin.math.cos(hd) * 45.0
        val north = -kotlin.math.sin(hd) * 45.0
        val pLat = last.lat + north / Geo.metersPerDegLat(last.lat)
        val pLon = last.lon + east / Geo.metersPerDegLon(last.lat)
        val la = base.lat.copyOf(n + 1); la[n] = pLat
        val lo = base.lon.copyOf(n + 1); lo[n] = pLon
        val cum = base.cum.copyOf(n + 1); cum[n] = cum[n - 1] + Geo.distance(base.lat[n - 1], base.lon[n - 1], pLat, pLon)
        val lim = base.limitKmh.copyOf(n); lim[n - 1] = 20.0
        val tun = base.tunnel.copyOf(n)
        val path = SimPath(la, lo, cum, lim, tun, emptyList())
        val sim = TraceSimulator(path, SimParams(initialSpeedKmh = 90.0, jams = listOf(JamZone(cum[n] - 12.0, cum[n] + 100.0, 0.0))), seed = 13)
        val engine = newEngine()
        val sink = EngineSink(engine)
        var stoppedSince = -1.0
        var status = EngineStatus.WAITING_GPS
        var facilities = 0
        val t0 = sim.t
        while (sim.advance(0.25, sink)) {
            val snap = engine.onTick(sim.t, sim.wallMs())
            val ts = (sim.t - t0) * 1e-9
            if (sim.v < 0.1 && stoppedSince < 0) stoppedSince = ts
            if (stoppedSince > 0 && ts - stoppedSince > 90) {
                status = snap.status
                facilities = snap.facilities.size
                break
            }
            if (ts > 900) break
        }
        println("=== SA 駐車場で停車 === 状態=$status 表示施設数=$facilities")
        assertEquals(EngineStatus.REST_STOP, status)
        assertTrue(facilities >= 3)
    }
}

package com.example.highwaymode.core

import com.example.highwaymode.core.eta.DriverProfile
import com.example.highwaymode.core.eta.EtaPredictor
import com.example.highwaymode.core.eta.RouteProfile
import com.example.highwaymode.core.eta.TrafficState
import com.example.highwaymode.core.geo.Geo
import com.example.highwaymode.core.graph.GraphBuilder
import com.example.highwaymode.core.graph.RoadGraph
import com.example.highwaymode.core.hwdb.HwdbFile
import com.example.highwaymode.core.osm.OsmDataSet
import com.example.highwaymode.core.osm.OsmTileData
import com.example.highwaymode.core.osm.TileId
import com.example.highwaymode.core.route.RouteAheadBuilder
import com.example.highwaymode.core.traffic.ExtTraffic
import com.example.highwaymode.core.traffic.JamModel
import com.example.highwaymode.core.traffic.JamSpan
import com.example.highwaymode.core.traffic.JamTracker
import com.example.highwaymode.core.traffic.TrafficData
import com.example.highwaymode.core.traffic.TrafficItem
import com.example.highwaymode.core.traffic.TrafficKind
import com.example.highwaymode.core.traffic.TrafficMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * 渋滞時の到着予測。
 *  - 渋滞の最後尾が伸びている／縮んでいる／着く前に解消する場合
 *  - 交通情報の遅れ時間を保つ（自分の自由流の速さが取得元と違っても）
 *  - 渋滞の中では自分の実際の流れを優先する
 *  - 取得結果を前回と照合して最後尾の動きを求める（実際の地図データ上で）
 */
class JamEtaTest {

    private val cell = 50.0
    private val length = 60_000.0
    private val n = (length / cell).toInt()
    private val vFree = 100.0 / 3.6

    /** 制限 100km/h・カーブ無しの 60km の経路 */
    private fun profile() = RouteProfile(
        0L, length, cell,
        DoubleArray(n) { vFree }, BooleanArray(n) { true },
        DoubleArray(n) { 135.0 / 3.6 }, BooleanArray(n), BooleanArray(n)
    )

    private val noOwnInfo = TrafficState(ratio = 1.0, congestedDistance = 0.0, valid = false)

    /** [tail, head] が速さ jamKmh の渋滞（速さがわかる取得元）。他は順調 */
    private fun ext(tail: Double, head: Double, jamKmh: Double, tailSpeed: Double, ageSec: Double = 0.0): ExtTraffic {
        val abs = DoubleArray(n) { Double.NaN }
        val extra = DoubleArray(n) { 0.0 }
        for (k in 0 until n) {
            val mid = (k + 0.5) * cell
            if (mid >= tail && mid < head) {
                abs[k] = 1.0 / (jamKmh / 3.6)
                extra[k] = Double.NaN
            }
        }
        return ExtTraffic(abs, extra, listOf(JamSpan(tail, head, tailSpeed, 0.0, 0.0, 0.0, 0.0)), 1.0, ageSec)
    }

    /**
     * 実際の走行（加減速つき）: 自由流で走り、動いている最後尾に追いついたら渋滞の速さ、先頭を過ぎたら自由流。
     * @return from を時刻 0 に出発して to に着くまでの秒数
     */
    private fun truth(from: Double, to: Double, tail0: Double, head: Double, tailSpeed: Double, jamKmh: Double): Double {
        val vj = jamKmh / 3.6
        var x = from
        var v = vFree
        var t = 0.0
        val dt = 0.1
        while (x < to) {
            val tail = minOf(tail0 + tailSpeed * t, head)
            val target = if (x >= tail && x < head) vj else vFree
            // 渋滞の最後尾の手前では減速しておく（実際の運転と同じく、追いつく前に速度を落とす）
            val gap = tail - x
            val brake = if (gap > 0 && x < head && tail < head) kotlin.math.sqrt(vj * vj + 2 * 0.9 * gap) else Double.MAX_VALUE
            val want = minOf(target, brake)
            v = if (v < want) minOf(want, v + 0.8 * dt) else maxOf(want, v - 0.9 * dt)
            x += v * dt
            t += dt
        }
        return t
    }

    private fun fmt(v: Double) = String.format(Locale.US, "%.0f", v)

    @Test
    fun growingJam_tailComesToMeet() {
        // 300 秒前の取得で 26〜30km、いまの取得で 25〜30km（最後尾が 12km/h で手前に伸びている）。自分は 8.33km 地点
        val eta = EtaPredictor(DriverProfile())
        val pf = profile()
        val from = 8333.0
        val vt = -1000.0 / 300.0
        val dyn = eta.predict(pf, from, vFree, noOwnInfo, ext(25_000.0, 30_000.0, 15.0, vt)).timeTo(40_000.0)
        val static = eta.predict(pf, from, vFree, noOwnInfo, ext(25_000.0, 30_000.0, 15.0, Double.NaN)).timeTo(40_000.0)
        val actual = truth(from, 40_000.0, 25_000.0, 30_000.0, vt, 15.0)
        println("渋滞が伸びている: 実際 ${fmt(actual)} 秒 / 伸びを考慮 ${fmt(dyn)} 秒 / 考慮なし ${fmt(static)} 秒")
        assertTrue("伸びを考慮した予測は実際に近い（±60 秒）", abs(dyn - actual) < 60.0)
        assertTrue("考慮しないと大きく早すぎる", actual - static > 300.0)
    }

    @Test
    fun shrinkingJam_tailMovesAway() {
        val eta = EtaPredictor(DriverProfile())
        val pf = profile()
        val from = 8333.0
        val vt = 4.0
        val dyn = eta.predict(pf, from, vFree, noOwnInfo, ext(25_000.0, 30_000.0, 15.0, vt)).timeTo(40_000.0)
        val static = eta.predict(pf, from, vFree, noOwnInfo, ext(25_000.0, 30_000.0, 15.0, Double.NaN)).timeTo(40_000.0)
        val actual = truth(from, 40_000.0, 25_000.0, 30_000.0, vt, 15.0)
        println("渋滞が縮んでいる: 実際 ${fmt(actual)} 秒 / 縮みを考慮 ${fmt(dyn)} 秒 / 考慮なし ${fmt(static)} 秒")
        assertTrue(abs(dyn - actual) < 60.0)
        assertTrue(static - actual > 300.0)
    }

    @Test
    fun jamDissolvesBeforeArrival() {
        // 27〜30km の渋滞の最後尾が 8m/s で前へ（解消に向かう）。0km から 100km/h では着く前に解消する
        val eta = EtaPredictor(DriverProfile())
        val dyn = eta.predict(profile(), 0.0, vFree, noOwnInfo, ext(27_000.0, 30_000.0, 15.0, 8.0)).timeTo(40_000.0)
        println("着く前に解消: 予測 ${fmt(dyn)} 秒（順調なら ${fmt(40_000.0 / vFree)} 秒）")
        assertEquals(40_000.0 / vFree, dyn, 15.0)
    }

    @Test
    fun staleInfoIsExtrapolated() {
        // 6 分前の取得（25〜30km, 最後尾が 3.3m/s で伸びている）。いまは最後尾が 23.8km まで来ているはず
        val eta = EtaPredictor(DriverProfile())
        val vt = -1000.0 / 300.0
        val from = 0.0
        val dyn = eta.predict(profile(), from, vFree, noOwnInfo, ext(25_000.0, 30_000.0, 15.0, vt, ageSec = 360.0)).timeTo(40_000.0)
        val actual = truth(from, 40_000.0, 25_000.0 + vt * 360.0, 30_000.0, vt, 15.0)
        println("取得から 6 分: 実際 ${fmt(actual)} 秒 / 予測 ${fmt(dyn)} 秒")
        assertTrue(abs(dyn - actual) < 60.0)
    }

    @Test
    fun providerDelayGivesJamSpeed() {
        // 取得元（TomTom）: 約 3.3km の渋滞で遅れ 440 秒（自由流 80km/h 基準）。自分の自由流は 100km/h
        // → 渋滞の中は「80km/h での所要時間 + 遅れ」で進む（渋滞の中の速さは運転のくせに左右されない）
        val eta = EtaPredictor(DriverProfile())
        val item = TrafficItem(doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, 0.03), TrafficKind.JAM, 0.235, delaySec = 440.0)
        val (pa, _) = TrafficMapper.paces(item)
        val jamTime = item.length / 22.2 + 440.0
        assertEquals(jamTime / item.length, pa, 1e-9)
        val abs = DoubleArray(n) { Double.NaN }
        val extra = DoubleArray(n) { 0.0 }
        for (k in 0 until n) if ((k + 0.5) * cell in 10_000.0..10_000.0 + item.length) {
            abs[k] = pa
            extra[k] = Double.NaN
        }
        val e = ExtTraffic(abs, extra, emptyList(), 1.0, 0.0)
        val res = eta.predict(profile(), 0.0, vFree, noOwnInfo, e)
        val free = eta.predict(profile(), 0.0, vFree, noOwnInfo, null)
        val delay = res.timeTo(30_000.0) - free.timeTo(30_000.0)
        val expected = jamTime - item.length / vFree
        println("渋滞（遅れ 440 秒・80km/h 基準）→ 予測の遅れ ${fmt(delay)} 秒（期待 ${fmt(expected)} 秒）")
        assertEquals(expected, delay, 40.0)
        // 流れが少し悪い程度（速度比 0.8・遅れ 60 秒）は、自分の自由流に遅れを足す
        val light = TrafficItem(doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, 0.03), TrafficKind.SLOW, 0.8, delaySec = 60.0)
        val (la, le) = TrafficMapper.paces(light)
        assertTrue(la.isNaN())
        assertEquals(60.0 / light.length, le, 1e-9)
    }

    @Test
    fun insideJam_ownFlowFirstThenProvider() {
        // 渋滞の中（26km 地点）。取得元は 20km/h だが、自分は直近 2 分 10km/h でしか進んでいない
        val eta = EtaPredictor(DriverProfile())
        val own = TrafficState(ratio = 10.0 / 100.0, congestedDistance = 1500.0, valid = true, accelTrend = 0.0)
        val e = ext(24_000.0, 30_000.0, 20.0, Double.NaN)
        val res = eta.predict(profile(), 26_000.0, 10.0 / 3.6, own, e)
        val t1 = res.timeTo(27_000.0)
        val tHead = res.timeTo(30_000.0)
        println("渋滞の中: 次の 1km ${fmt(t1)} 秒（自分の流れ 360 秒 / 取得元 180 秒）、先頭まで ${fmt(tHead)} 秒")
        assertTrue("すぐ先は自分の流れに近い", t1 > 260.0 && t1 < 380.0)
        assertTrue("先頭まで（4km）は自分の流れと取得元の間", tHead > 4000.0 / (20.0 / 3.6) && tHead < 4000.0 / (10.0 / 3.6))
        // 先頭を過ぎたら順調（先頭から 10km は自由流 + 加速分）
        val after = res.timeTo(40_000.0) - tHead
        assertEquals(10_000.0 / vFree, after, 40.0)
    }

    @Test
    fun ownJamWithoutProviderInfoStillDecays() {
        // 交通情報が古く、いまの渋滞が載っていない（取得元は順調としている）: 自分の流れがしばらく続くとみなす
        val eta = EtaPredictor(DriverProfile())
        val own = TrafficState(ratio = 0.2, congestedDistance = 3000.0, valid = true)
        val e = ExtTraffic(DoubleArray(n) { Double.NaN }, DoubleArray(n) { 0.0 }, emptyList(), 1.0, 0.0)
        val withInfo = eta.predict(profile(), 10_000.0, 20.0 / 3.6, own, e).timeTo(20_000.0)
        val noInfo = eta.predict(profile(), 10_000.0, 20.0 / 3.6, own, null).timeTo(20_000.0)
        println("取得元に無い渋滞の中: 交通情報あり ${fmt(withInfo)} 秒 / なし ${fmt(noInfo)} 秒")
        assertEquals("「順調」の古い情報で、自分が渋滞中という事実を打ち消さない", noInfo, withInfo, 5.0)
    }

    // ------------------------------------------------------------------ 最後尾の動き（実際の地図データ上）

    private fun aichiGraph(): RoadGraph {
        val url = javaClass.getResource("/hwdb/aichi-route23.hwdb")
        assertNotNull(url)
        val db = HwdbFile.open(File(url!!.toURI()))
        val tiles = HashSet<TileId>()
        val list = ArrayList<OsmTileData>()
        for (iy in 73..75) for (ix in 67..69) {
            val t = TileId(iy, ix)
            db.read(t)?.let { list.add(it); tiles.add(t) }
        }
        return GraphBuilder.build(OsmDataSet.merge(list), tiles)
    }

    @Test
    fun trackerEstimatesTailMovementFromSuccessiveFetches() {
        val graph = aichiGraph()
        val p = graph.nearby(34.8160, 137.3300, 200.0) { !it.attr.isLink && it.attr.normRefs.contains("23") }
            .filter { abs(Geo.diffDeg(graph.edges[it.edge].bearingAt(it.offset), 290.0)) < 50 }
            .minByOrNull { it.dist }
        assertNotNull(p)
        val route = RouteAheadBuilder().build(graph, p!!.edge, p.offset)
        assertTrue(route.length > 30_000)
        val pt = DoubleArray(2)
        fun line(a: Double, b: Double): Pair<DoubleArray, DoubleArray> {
            val m = ((b - a) / 100.0).toInt() + 1
            val la = DoubleArray(m)
            val lo = DoubleArray(m)
            for (i in 0 until m) {
                route.pointAt(graph, a + (b - a) * i / (m - 1), pt)
                la[i] = pt[0]
                lo[i] = pt[1]
            }
            return la to lo
        }
        val (cla, clo) = line(0.0, 28_000.0)
        fun fetch(tail: Double, head: Double, wallMs: Long): TrafficData {
            val (la, lo) = line(tail, head)
            return TrafficData("HERE", wallMs, listOf(TrafficItem(la, lo, TrafficKind.JAM, 0.15, speedKmh = 15.0)), true, cla, clo)
        }
        val tracker = JamTracker()
        for ((wall, tail) in listOf(0L to 20_000.0, 300_000L to 19_000.0, 600_000L to 18_000.0)) {
            val data = fetch(tail, 25_000.0, wall)
            val et = TrafficMapper.annotate(graph, data)
            val rtf = TrafficMapper.forRoute(route, graph, et, 50.0)
            val spans = JamModel.spans(rtf, route, graph, null)
            assertEquals("渋滞は 1 つ", 1, spans.size)
            tracker.update(route, graph, spans, wall)
        }
        val vt = tracker.observed.single().tailSpeed
        println(String.format(Locale.US, "最後尾の動き: %.2f m/s（実際 -3.33 m/s = 12km/h で伸びている）", vt))
        assertEquals(-1000.0 / 300.0, vt, 0.6)
        // いまの経路上の渋滞に、求めた動きが付く
        val data = fetch(18_000.0, 25_000.0, 600_000L)
        val rtf = TrafficMapper.forRoute(route, graph, TrafficMapper.annotate(graph, data), 50.0)
        val span = JamModel.spans(rtf, route, graph, tracker).single()
        assertEquals(vt, span.tailSpeed, 1e-9)
        assertEquals(1, span.trend)
    }
}

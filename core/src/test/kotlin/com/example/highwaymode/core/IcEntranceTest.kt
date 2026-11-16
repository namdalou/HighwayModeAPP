package com.example.highwaymode.core

import com.example.highwaymode.core.engine.EngineStatus
import com.example.highwaymode.core.engine.HighwayEngine
import com.example.highwaymode.core.geo.Geo
import com.example.highwaymode.core.geo.LatLon
import com.example.highwaymode.core.graph.GraphBuilder
import com.example.highwaymode.core.graph.RoadGraph
import com.example.highwaymode.core.model.GpsFix
import com.example.highwaymode.core.osm.OsmDataSet
import com.example.highwaymode.core.osm.OsmNode
import com.example.highwaymode.core.osm.OsmTileData
import com.example.highwaymode.core.osm.OsmWay
import com.example.highwaymode.core.osm.TileId
import com.example.highwaymode.core.route.RouteAhead
import com.example.highwaymode.core.route.RouteAheadBuilder
import com.example.highwaymode.core.sim.SimParams
import com.example.highwaymode.core.sim.SimPath
import com.example.highwaymode.core.sim.SimSink
import com.example.highwaymode.core.sim.TraceSimulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * IC の入口側（v2.17）。利用者の報告「バイパスで、IC の出口を過ぎて表示が消えたあと、同じ IC の合流側（下道からの入口）を
 * もう一度 IC として表示した」の再現。
 *
 * 南北に真っすぐな上下線分離のバイパス（下り=南行き=東側、上り=北行き=西側）。
 *   5.0km 豊川IC: 下りは出口の分岐（5.0km）と入口の合流（5.6km）の両方に「豊川IC」の分岐ノード
 *   7.0km 見附IC: 下りは出口の分岐ノードが未登録（推定出口）、入口の合流（7.5km）にだけ「見附IC入口」
 *   9.0km 西IC: 下りは入口の合流（9.0km）だけ（ハーフ IC）。上りは出口の分岐（9.0km）に「西IC」
 *  10.9km 掛川IC: 下りの出口の分岐ノードが、実際の分岐（11.0km）より 100m 手前に登録されている（形では決められない）。
 *          入口の合流（11.5km）にも「掛川IC」
 */
class IcEntranceTest {

    class Bypass {
        var nextId = 1L
        val pos = HashMap<Long, LatLon>()
        val ways = ArrayList<OsmWay>()
        val junctions = ArrayList<OsmNode>()
        val sb = LongArray(301)
        val nb = LongArray(301)
        val sbWays = HashSet<Long>()
        val nbWays = HashSet<Long>()

        fun pt(s: Double, x: Double): LatLon =
            LatLon(35.0 - s / Geo.metersPerDegLat(35.0), 137.0 + x / Geo.metersPerDegLon(35.0))

        fun node(p: LatLon): Long {
            val id = nextId++
            pos[id] = p
            return id
        }

        fun way(ids: List<Long>, tags: Map<String, String>): Long {
            val id = 100_000 + nextId++
            ways.add(OsmWay(id, ids.toLongArray(), DoubleArray(ids.size) { pos[ids[it]]!!.lat }, DoubleArray(ids.size) { pos[ids[it]]!!.lon }, tags))
            return id
        }

        fun junction(id: Long, name: String) {
            junctions.add(OsmNode(id, pos[id]!!.lat, pos[id]!!.lon, mapOf("highway" to "motorway_junction", "name" to name)))
        }

        private val link = mapOf("highway" to "trunk_link", "oneway" to "yes")
        private val local = mapOf("highway" to "secondary", "name" to "県道1号")

        /** 下り（東側）の出口ランプ: 本線の i 番目の点から東へ。先は一般道へつながる */
        private fun sbExit(i: Int) {
            val s0 = i * 50.0
            val ramp = listOf(sb[i]) + (1..6).map { j -> node(pt(s0 + 40.0 * j, 12.5 + 6.0 * j * j)) }
            way(ramp, link)
            val end = ramp.last()
            way(listOf(end, node(pt(s0 + 260.0, 600.0))), local)
        }

        /** 下り（東側）の入口ランプ: 一般道から本線の i 番目の点へ合流 */
        private fun sbEntrance(i: Int) {
            val s1 = i * 50.0
            val start = node(pt(s1 - 260.0, 600.0))
            val ramp = listOf(start) + (6 downTo 1).map { j -> node(pt(s1 - 40.0 * j, 12.5 + 6.0 * j * j)) } + listOf(sb[i])
            way(ramp, link)
        }

        /** 上り（西側）の出口ランプ: 本線の i 番目の点から西へ */
        private fun nbExit(i: Int) {
            val s0 = i * 50.0
            val ramp = listOf(nb[i]) + (1..6).map { j -> node(pt(s0 - 40.0 * j, -12.5 - 6.0 * j * j)) }
            way(ramp, link)
            way(listOf(ramp.last(), node(pt(s0 - 260.0, -600.0))), local)
        }

        fun build(): Bypass {
            for (i in 0..300) {
                sb[i] = node(pt(i * 50.0, 12.5))
                nb[i] = node(pt(i * 50.0, -12.5))
            }
            val main = mapOf("highway" to "trunk", "name" to "テストバイパス", "ref" to "1", "oneway" to "yes", "motorroad" to "yes", "maxspeed" to "70")
            sbWays.add(way((0..300).map { sb[it] }, main))
            nbWays.add(way((300 downTo 0).map { nb[it] }, main))

            // ---- 豊川IC: 出口（5.0km）と入口（5.6km）の両方に分岐ノード
            sbExit(100)
            junction(sb[100], "豊川IC")
            sbEntrance(112)
            junction(sb[112], "豊川IC")

            // ---- 見附IC: 出口（7.0km）は分岐ノード未登録、入口（7.5km）に「見附IC入口」
            sbExit(140)
            sbEntrance(150)
            junction(sb[150], "見附IC入口")

            // ---- 西IC: 下りは入口（9.0km）だけ、上りは出口（9.0km）
            sbEntrance(180)
            junction(sb[180], "西IC")
            nbExit(180)
            junction(nb[180], "西IC")

            // ---- 掛川IC: 出口の分岐ノードが分岐の 100m 手前、入口にも同じ名前
            sbExit(220)
            junction(sb[218], "掛川IC")
            sbEntrance(230)
            junction(sb[230], "掛川IC")
            return this
        }

        fun graph(): RoadGraph {
            val data = OsmDataSet.merge(listOf(OsmTileData(ways, junctions, emptyList(), emptyList(), emptyList())))
            val tiles = HashSet<TileId>()
            for (w in ways) for (i in w.lat.indices) tiles.add(TileId.of(w.lat[i], w.lon[i]))
            return GraphBuilder.build(data, tiles)
        }
    }

    companion object {
        lateinit var net: Bypass
        lateinit var graph: RoadGraph

        @BeforeClass
        @JvmStatic
        fun setup() {
            net = Bypass().build()
            graph = net.graph()
        }

        fun route(s: Double, x: Double, ways: Set<Long>): RouteAhead {
            val p0 = net.pt(s, x)
            val p = graph.nearby(p0.lat, p0.lon, 20.0) { it.wayId in ways }.first()
            return RouteAheadBuilder().build(graph, p.edge, p.offset)
        }

        fun RouteAhead.describe() = facilities.joinToString {
            "${it.facility.displayName}${if (it.facility.noExit) "(出口なし)" else ""}@${"%.2f".format(it.distance / 1000)}km"
        }
    }

    @Test
    fun eachIcIsShownOnceAtItsExit() {
        val r = route(1000.0, 12.5, net.sbWays)
        println("下り: " + r.describe())
        val names = r.facilities.map { it.facility.displayName }
        assertEquals(listOf("豊川IC", "見附IC", "西IC", "掛川IC"), names)
        // 豊川・見附は出口（分岐）の位置、西は入口だけなので「出口なし」
        assertEquals(4000.0, r.facilities[0].distance, 30.0)
        assertFalse(r.facilities[0].facility.noExit)
        assertEquals("推定出口の位置", 6000.0, r.facilities[1].distance, 60.0)
        assertFalse(r.facilities[1].facility.noExit)
        assertTrue("入口だけの IC は出口なし", r.facilities[2].facility.noExit)
        assertEquals("出口の分岐ノードの位置", 9900.0, r.facilities[3].distance, 30.0)
        assertFalse(r.facilities[3].facility.noExit)
    }

    @Test
    fun icIsNotShownAgainAfterPassingItsExit() {
        // 豊川IC の出口（5.0km）を過ぎて、入口（5.6km）の手前
        val r = route(5_200.0, 12.5, net.sbWays)
        println("豊川IC の出口を過ぎたあと: " + r.describe())
        assertFalse("入口側の豊川IC は出さない", r.facilities.any { it.facility.displayName == "豊川IC" })
        assertEquals("見附IC", r.facilities.first().facility.displayName)
        // 見附IC の推定出口（7.0km）を過ぎて、入口（7.5km）の手前
        val r2 = route(7_200.0, 12.5, net.sbWays)
        println("見附IC の出口を過ぎたあと: " + r2.describe())
        assertFalse("入口側の見附IC は出さない", r2.facilities.any { it.facility.displayName == "見附IC" })
        // 掛川IC の出口（11.0km）を過ぎて、入口（11.5km）の手前
        val r3 = route(11_200.0, 12.5, net.sbWays)
        println("掛川IC の出口を過ぎたあと: " + r3.describe())
        assertFalse("入口側の掛川IC は出さない", r3.facilities.any { it.facility.displayName == "掛川IC" })
    }

    @Test
    fun oppositeDirectionKeepsItsExit() {
        val r = route(13_000.0, -12.5, net.nbWays)
        println("上り: " + r.describe())
        val west = r.facilities.firstOrNull { it.facility.displayName == "西IC" }
        assertTrue("上りの西IC（出口あり）", west != null && !west.facility.noExit)
        assertEquals(4000.0, west!!.distance, 30.0)
    }

    private class Sink(val e: HighwayEngine) : SimSink {
        override fun gyro(t: Long, x: Double, y: Double, z: Double) = e.onGyro(t, x, y, z)
        override fun accel(t: Long, x: Double, y: Double, z: Double) = e.onAccel(t, x, y, z)
        override fun gravity(t: Long, x: Double, y: Double, z: Double) = e.onGravity(t, x, y, z)
        override fun rotation(t: Long, r: DoubleArray, fieldUt: Double, expectedUt: Double) = e.onRotationMatrix(t, r, fieldUt, expectedUt)
        override fun gps(fix: GpsFix) = e.onGps(fix)
        override fun vehicleSpeed(t: Long, rawMs: Double) = e.onVehicleSpeed(t, rawMs, null)
        override fun vehicleGyro(t: Long, x: Double, y: Double, z: Double) = e.onVehicleGyro(t, x, y, z)
    }

    @Test
    fun engineShowsEachIcOnceWhileDriving() {
        // エンジンで走らせて、一覧の先頭に出た IC の順番を記録する（同じ IC が 2 回出ない・途中の IC を飛ばさない）
        val start = route(500.0, 12.5, net.sbWays)
        val sim = TraceSimulator(SimPath.fromRoute(start, graph), SimParams(driverRatio = 1.0, initialSpeedKmh = 70.0), seed = 3)
        val e = HighwayEngine()
        e.setGraph(graph)
        val sink = Sink(e)
        val firsts = ArrayList<String>()
        var onHighway = false
        while (sim.advance(0.25, sink) && sim.s < 12_500.0) {
            val snap = e.onTick(sim.t, sim.wallMs())
            if (snap.status == EngineStatus.ON_HIGHWAY) onHighway = true
            val first = snap.facilities.firstOrNull { it.type.isExit }?.displayName ?: continue
            if (firsts.lastOrNull() != first) firsts.add(first)
        }
        println("先頭に出た IC の順: $firsts")
        assertTrue(onHighway)
        assertEquals(listOf("豊川IC", "見附IC", "西IC", "掛川IC"), firsts)
    }
}

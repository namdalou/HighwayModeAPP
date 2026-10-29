package com.example.highwaymode.core

import com.example.highwaymode.core.engine.EngineStatus
import com.example.highwaymode.core.engine.HighwayEngine
import com.example.highwaymode.core.graph.GraphBuilder
import com.example.highwaymode.core.model.GpsFix
import com.example.highwaymode.core.osm.OsmDataSet
import com.example.highwaymode.core.osm.OsmTileData
import com.example.highwaymode.core.osm.TileId
import com.example.highwaymode.core.route.RouteAheadBuilder
import com.example.highwaymode.core.sim.SimParams
import com.example.highwaymode.core.sim.SimPath
import com.example.highwaymode.core.sim.SimSink
import com.example.highwaymode.core.sim.TraceSimulator
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 実機と同じく「タイル単位で少しずつ地図を読み込む」状況で走り切れるか。
 * Overpass の bbox 検索と同様に、タイルに一部でも掛かるウェイはそのタイルに含める。
 */
class TileLoadingTest {

    private fun tileOf(net: TestNetwork, tile: TileId): OsmTileData {
        val ways = net.ways.filter { w -> w.lat.indices.any { tile.contains(w.lat[it], w.lon[it]) } }
        val nodeIds = ways.flatMap { it.nodeIds.asList() }.toHashSet()
        return OsmTileData(
            ways,
            net.junctions.filter { it.id in nodeIds },
            net.tolls.filter { it.id in nodeIds },
            net.areas.filter { tile.contains(it.centerLat, it.centerLon) },
            net.pois.filter { tile.contains(it.lat, it.lon) }
        )
    }

    @Test
    fun driveWhileLoadingTilesOnDemand() {
        val net = TestNetwork().build()
        val full = net.graph()
        val p0 = net.sbPoint(500.0)
        val p = full.nearby(p0.lat, p0.lon, 20.0) { it.wayId in net.sbWayIds }.first()
        val path = SimPath.fromRoute(RouteAheadBuilder().build(full, p.edge, p.offset), full)

        val loaded = LinkedHashMap<TileId, OsmTileData>()
        val engine = HighwayEngine()
        fun load(t: TileId) {
            if (t in loaded) return
            loaded[t] = tileOf(net, t)
            engine.setGraph(GraphBuilder.build(OsmDataSet.merge(loaded.values), loaded.keys.toSet()))
        }
        load(TileId.of(p0.lat, p0.lon))

        val sim = TraceSimulator(path, SimParams(driverRatio = 1.05, initialSpeedKmh = 90.0), seed = 21)
        val sink = object : SimSink {
            override fun gyro(t: Long, x: Double, y: Double, z: Double) = engine.onGyro(t, x, y, z)
            override fun accel(t: Long, x: Double, y: Double, z: Double) = engine.onAccel(t, x, y, z)
            override fun gravity(t: Long, x: Double, y: Double, z: Double) = engine.onGravity(t, x, y, z)
            override fun rotation(t: Long, r: DoubleArray, fieldUt: Double, expectedUt: Double) = engine.onRotationMatrix(t, r, fieldUt, expectedUt)
            override fun gps(fix: GpsFix) = engine.onGps(fix)
        }
        var ticks = 0
        var onHw = 0
        var wrong = 0
        var minAhead = Double.MAX_VALUE
        val t0 = sim.t
        while (sim.advance(0.25, sink)) {
            val snap = engine.onTick(sim.t, sim.wallMs())
            ticks++
            // 2 秒ごとにエンジンの要求タイルを（即時に）読み込む
            if (ticks % 8 == 0) engine.wantedTiles().forEach { load(it) }
            if ((sim.t - t0) * 1e-9 < 20) continue
            if (snap.status == EngineStatus.ON_HIGHWAY) {
                onHw++
                val r = engine.matcher.result
                if (full.edges.isNotEmpty() && engine.graph.edges[r.edge].wayId !in net.sbWayIds) wrong++
                // 終点 8km 手前までは、常に 8km 以上先まで経路が見えていること
                if (sim.s < path.length - 8000) minAhead = minOf(minAhead, snap.routeLengthM)
            }
        }
        println("=== タイル逐次読込 === 読込タイル数=${loaded.size} 高速道路判定=${onHw}/${ticks - 80} 誤車線=$wrong 最小前方経路=%.1fkm".format(minAhead / 1000))
        assertTrue(onHw > (ticks - 80) * 0.97)
        assertTrue(wrong == 0)
        assertTrue("前方経路が途切れない", minAhead > 8000)
    }
}

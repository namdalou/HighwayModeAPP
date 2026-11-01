package com.example.highwaymode.core

import com.example.highwaymode.core.facility.FacilityType
import com.example.highwaymode.core.geo.Geo
import com.example.highwaymode.core.graph.GraphBuilder
import com.example.highwaymode.core.graph.RoadGraph
import com.example.highwaymode.core.hwdb.HwdbFile
import com.example.highwaymode.core.osm.OsmDataSet
import com.example.highwaymode.core.osm.OsmTileData
import com.example.highwaymode.core.osm.TileId
import com.example.highwaymode.core.route.RouteAheadBuilder
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * 実際の地図データ（播但連絡道路 福崎付近の 4 タイル。© OpenStreetMap contributors, ODbL）での回帰テスト。
 * 福崎付近は、通過する車線（有料・朝来/豊岡方面）と、福崎南・福崎・福崎北の出口がある車線（無料区間）に分かれ、
 * 料金所の先で合流する。前方経路が通過する側を選んでも、出口側の IC を飛ばさずに並べる。
 */
class RealDataParallelTest {

    companion object {
        lateinit var graph: RoadGraph

        @BeforeClass
        @JvmStatic
        fun load() {
            val url = RealDataParallelTest::class.java.getResource("/hwdb/hyogo-bantan.hwdb")
            assertNotNull("テスト用データ hwdb/hyogo-bantan.hwdb", url)
            val db = HwdbFile.open(File(url!!.toURI()))
            val tiles = HashSet<TileId>()
            val list = ArrayList<OsmTileData>()
            for (iy in 74..75) for (ix in 58..59) {
                val t = TileId(iy, ix)
                db.read(t)?.let {
                    list.add(it)
                    tiles.add(t)
                }
            }
            graph = GraphBuilder.build(OsmDataSet.merge(list), tiles)
        }
    }

    private fun names(lat: Double, lon: Double, heading: Double): List<String> {
        val p = graph.nearby(lat, lon, 200.0) { !it.attr.isLink && it.attr.normRefs.contains("E95") }
            .filter { abs(Geo.diffDeg(graph.edges[it.edge].bearingAt(it.offset), heading)) < 50 }
            .minByOrNull { it.dist }
        assertNotNull("播但連絡道路が見つかる", p)
        val r = RouteAheadBuilder().build(graph, p!!.edge, p.offset)
        return r.facilities.filter { it.facility.type != FacilityType.CAMERA && it.facility.type != FacilityType.TB }
            .map { it.facility.displayName + if (it.facility.noExit) "(出口なし)" else "" }
    }

    private fun assertInOrder(label: String, expected: List<String>, list: List<String>) {
        var i = 0
        for (x in list) if (i < expected.size && x == expected[i]) i++
        assertTrue("$label: ${expected.getOrNull(i)} が見つからない（順番どおりに）\n実際: $list", i == expected.size)
    }

    @Test
    fun northbound_exitsOnParallelCarriagewayAreListed() {
        // 豊富から北へ（朝来・豊岡方面）
        val n = names(34.87065, 134.73951, 0.0)
        println("北行き: $n")
        assertInOrder("北行き", listOf("船津IC", "福崎南IC", "福崎IC", "福崎北IC", "市川南IC", "市川SA", "市川北IC"), n)
    }

    @Test
    fun southbound_stillComplete() {
        // 福崎北から南へ（姫路方面）
        val s = names(34.95637, 134.77030, 200.0)
        println("南行き: $s")
        assertInOrder("南行き", listOf("福崎IC", "福崎南IC", "船津IC", "砥堀IC", "豊富PA"), s)
    }
}

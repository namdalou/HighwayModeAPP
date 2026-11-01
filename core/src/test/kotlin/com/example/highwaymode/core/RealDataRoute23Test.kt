package com.example.highwaymode.core

import com.example.highwaymode.core.facility.FacilityType
import com.example.highwaymode.core.geo.Geo
import com.example.highwaymode.core.graph.GraphBuilder
import com.example.highwaymode.core.graph.RoadGraph
import com.example.highwaymode.core.hwdb.HwdbFile
import com.example.highwaymode.core.osm.OsmDataSet
import com.example.highwaymode.core.osm.OsmTileData
import com.example.highwaymode.core.osm.TileId
import com.example.highwaymode.core.route.RouteAhead
import com.example.highwaymode.core.route.RouteAheadBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * 実際の地図データ（国道23号 名豊道路・知立バイパス周辺の 9 タイル。© OpenStreetMap contributors, ODbL）での回帰テスト。
 *  - 片方向にしか出口の無い IC（幸田須美・小島江原・西中など）が、反対方向でも「出口なし」として並ぶ
 *  - 種別の付かない名前の分岐（「豊川為当」「幸田芦谷」）は JCT ではなく IC
 *  - 本線の名前が変わる地点（国道23号 → 知立バイパス）でランプへ進まず、前方の IC が続けて並ぶ
 */
class RealDataRoute23Test {

    companion object {
        lateinit var graph: RoadGraph

        @BeforeClass
        @JvmStatic
        fun load() {
            val url = RealDataRoute23Test::class.java.getResource("/hwdb/aichi-route23.hwdb")
            assertNotNull("テスト用データ hwdb/aichi-route23.hwdb", url)
            val db = HwdbFile.open(File(url!!.toURI()))
            val tiles = HashSet<TileId>()
            val list = ArrayList<OsmTileData>()
            for (iy in 73..75) for (ix in 67..69) {
                val t = TileId(iy, ix)
                db.read(t)?.let {
                    list.add(it)
                    tiles.add(t)
                }
            }
            graph = GraphBuilder.build(OsmDataSet.merge(list), tiles)
        }
    }

    private fun routeFrom(lat: Double, lon: Double, heading: Double): RouteAhead {
        val p = graph.nearby(lat, lon, 200.0) { !it.attr.isLink && it.attr.normRefs.contains("23") }
            .filter { abs(Geo.diffDeg(graph.edges[it.edge].bearingAt(it.offset), heading)) < 50 }
            .minByOrNull { it.dist }
        assertNotNull("国道23号が見つかる", p)
        return RouteAheadBuilder().build(graph, p!!.edge, p.offset)
    }

    private fun RouteAhead.names(): List<String> = facilities
        .filter { it.facility.type != FacilityType.CAMERA && it.facility.type != FacilityType.TB }
        .map { it.facility.displayName + if (it.facility.noExit) "(出口なし)" else "" }

    /** expected が list の中にこの順で（間に他の施設があってもよい）並んでいるか */
    private fun assertInOrder(label: String, expected: List<String>, list: List<String>) {
        var i = 0
        for (x in list) if (i < expected.size && x == expected[i]) i++
        assertTrue("$label: ${expected.getOrNull(i)} が見つからない（順番どおりに）\n実際: $list", i == expected.size)
    }

    @Test
    fun westbound_halfInterchangesAppearAsNoExit() {
        // 豊川為当付近から西へ（名古屋方面）
        val r = routeFrom(34.8160, 137.3300, 290.0)
        val names = r.names()
        println("西行き: $names")
        assertInOrder(
            "西行き",
            listOf(
                "豊川為当IC", "御津金野IC", "蒲郡IC", "蒲郡西IC", "幸田芦谷IC", "幸田桐山IC",
                "幸田須美IC(出口なし)", "西尾東IC", "小島江原IC(出口なし)", "中原IC", "藤井IC"
            ),
            names
        )
        assertFalse("豊川為当は JCT ではなく IC", names.contains("豊川為当JCT"))
    }

    @Test
    fun eastbound_allInterchangesInOrder() {
        // 岡崎バイパス 藤井IC 付近から東へ（豊橋方面）
        val r = routeFrom(34.8964, 137.0735, 160.0)
        val names = r.names()
        println("東行き: $names")
        assertInOrder(
            "東行き",
            listOf("中原IC", "小島江原IC", "西尾東IC", "幸田須美IC", "幸田桐山IC", "幸田芦谷IC", "蒲郡西IC", "蒲郡IC", "御津金野IC", "豊川為当IC", "小坂井御津IC"),
            names
        )
        // 東行きは全 IC に出口がある（出口なしは付かない）
        assertTrue(r.facilities.take(10).none { it.facility.noExit })
        // 名前の無い分岐に「国道23号」のような道路名を IC 名として付けない
        assertTrue(names.none { it == "国道23号IC" })
    }

    @Test
    fun routeContinuesOntoChiryuBypassInsteadOfRamp() {
        // 名古屋南から東へ。豊明の先で本線の名前が「知立バイパス」に変わる地点で、
        // 旧名「国道23号」のランプへ進まずに本線を続ける
        val r = routeFrom(35.0520, 136.9420, 125.0)
        val names = r.names()
        println("名古屋南から東へ: ${"%.1f".format(r.length / 1000)}km $names")
        assertTrue("前方経路が 30km 以上続く（実際 ${"%.1f".format(r.length / 1000)}km）", r.length > 30_000)
        assertTrue("知立バイパスへ続く", r.roadChanges.any { it.toName == "知立バイパス" })
        assertInOrder("知立バイパス", listOf("今川IC", "一ツ木IC", "上重原IC", "西中IC", "野田IC", "高棚北IC"), names)
        assertEquals("ランプの先で終わらない", 0, r.segments.count { graph.edges[it.edge].attr.isLink })
    }
}

package com.example.highwaymode.core

import com.example.highwaymode.core.facility.FacilityType
import com.example.highwaymode.core.geo.Geo
import com.example.highwaymode.core.geo.LatLon
import com.example.highwaymode.core.graph.GraphBuilder
import com.example.highwaymode.core.graph.RoadGraph
import com.example.highwaymode.core.osm.OsmArea
import com.example.highwaymode.core.osm.OsmDataSet
import com.example.highwaymode.core.osm.OsmNode
import com.example.highwaymode.core.osm.OsmPoi
import com.example.highwaymode.core.osm.OsmTileData
import com.example.highwaymode.core.osm.OsmWay
import com.example.highwaymode.core.osm.TileId
import com.example.highwaymode.core.route.RouteAhead
import com.example.highwaymode.core.route.RouteAheadBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * 「デモ走行で表示されない IC / SA があった」ケースの再現と、オービスの方向判定。
 *
 * 南北に真っすぐな上下線分離道路（下り=南行き=東側、上り=北行き=西側）。
 *   5.0km  東IC: 下りだけ motorway_junction あり。上りのランプ（5.2km）は分岐ノード未登録
 *  10.0km  中央SA: 下り線の東側にある上下集約型。下りの分岐ノードは名称なし、
 *          上りはランプが本線をまたいで右側の区域へ入る（左側判定だけでは拾えない）
 *  14-16.5km オービス: 道路上ノード / 路側（左側）/ 利用者登録（方位つき）
 *  17.0km  下り本線がそのまま別の自動車道へ分かれる（ランプ無し・分岐ノード無し）
 */
class ExtractorCasesTest {

    class Mini {
        var nextId = 1L
        val pos = HashMap<Long, LatLon>()
        val ways = ArrayList<OsmWay>()
        val junctions = ArrayList<OsmNode>()
        val tolls = ArrayList<OsmNode>()
        val areas = ArrayList<OsmArea>()
        val pois = ArrayList<OsmPoi>()
        val cameras = ArrayList<OsmNode>()
        val sb = LongArray(401)
        val nb = LongArray(401)
        val sbWays = HashSet<Long>()
        val nbWays = HashSet<Long>()

        fun pt(s: Double, x: Double): LatLon =
            LatLon(36.0 - s / Geo.metersPerDegLat(36.0), 138.0 + x / Geo.metersPerDegLon(36.0))

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

        fun nodeAt(id: Long, tags: Map<String, String>) = OsmNode(id, pos[id]!!.lat, pos[id]!!.lon, tags)

        val link = mapOf("highway" to "motorway_link", "oneway" to "yes")

        fun build(): Mini {
            for (i in 0..400) {
                sb[i] = node(pt(i * 50.0, 12.5))
                nb[i] = node(pt(i * 50.0, -12.5))
            }
            val main = mapOf("highway" to "motorway", "name" to "ミニ自動車道", "ref" to "E96", "oneway" to "yes", "maxspeed" to "100")
            sbWays.add(way((0..400).map { sb[it] }, main))
            nbWays.add(way((400 downTo 0).map { nb[it] }, main))

            // ---- A: 東IC（下りのみ分岐ノードあり）
            way(listOf(sb[100]) + (1..10).map { j -> node(pt(5000.0 + 50 * j, 12.5 + j * j)) }, link)
            junctions.add(nodeAt(sb[100], mapOf("highway" to "motorway_junction", "name" to "東IC")))
            way(listOf(nb[104]) + (1..10).map { j -> node(pt(5200.0 - 50 * j, -12.5 - j * j)) }, link)

            // ---- B: 中央SA（集約型・下り東側）
            val ring = listOf(pt(9800.0, 60.0), pt(9800.0, 200.0), pt(10200.0, 200.0), pt(10200.0, 60.0), pt(9800.0, 60.0))
            val c = pt(10000.0, 130.0)
            areas.add(OsmArea("w1", 1, c.lat, c.lon, listOf(OsmArea.Ring(ring.map { it.lat }.toDoubleArray(), ring.map { it.lon }.toDoubleArray())),
                mapOf("highway" to "services", "name" to "中央SA")))
            val g = pt(10050.0, 150.0)
            pois.add(OsmPoi("n1", g.lat, g.lon, mapOf("amenity" to "fuel", "brand" to "テスト石油")))
            // 下り: 名称なしの分岐ノード → 区域 → 合流
            val sbSa = ArrayList<Long>()
            sbSa.add(sb[196])
            for (j in 1..7) sbSa.add(node(pt(9800.0 + 50 * j, 12.5 + minOf(j, 4) * 22.0)))
            sbSa.add(sb[204])
            way(sbSa, link)
            junctions.add(nodeAt(sb[196], mapOf("highway" to "motorway_junction")))
            // 上り: 10.3km で右へ分かれ、下り線をまたいで区域へ（分岐ノード未登録）
            way(listOf(nb[206]) + (1..6).map { j -> node(pt(10300.0 - 50 * j, -12.5 + j * 27.0)) }, link)
            way(listOf(node(pt(9800.0, 150.0))) + (1..3).map { j -> node(pt(9800.0 - 25 * j, 150.0 - j * 50.0)) } + listOf(nb[194]), link)

            // ---- C: オービス
            cameras.add(nodeAt(sb[280], mapOf("highway" to "speed_camera", "maxspeed" to "100")))
            run {
                val id = node(pt(14500.0, -27.5))
                cameras.add(nodeAt(id, mapOf("highway" to "speed_camera")))
            }
            run {
                val id = node(pt(16000.0, 22.5))
                cameras.add(nodeAt(id, mapOf("highway" to "speed_camera", "camera:type" to "loop")))
            }
            run {
                val p = pt(16500.0, 0.0)
                cameras.add(OsmNode(-5, p.lat, p.lon, mapOf("highway" to "speed_camera", "source" to "user", "user:bearing" to "0", "name" to "登録オービス")))
            }

            // ---- D: 下り本線がそのまま別路線へ分岐
            way(listOf(sb[340]) + (1..20).map { j -> node(pt(17000.0 + 50 * j, 12.5 + j * j * 2.0)) },
                mapOf("highway" to "motorway", "name" to "支線道路", "ref" to "E97", "oneway" to "yes"))
            return this
        }

        fun graph(): RoadGraph {
            val data = OsmDataSet.merge(listOf(OsmTileData(ways, junctions, tolls, areas, pois, cameras)))
            val tiles = HashSet<TileId>()
            for (w in ways) for (i in w.lat.indices) tiles.add(TileId.of(w.lat[i], w.lon[i]))
            return GraphBuilder.build(data, tiles)
        }
    }

    companion object {
        lateinit var net: Mini
        lateinit var graph: RoadGraph

        @BeforeClass
        @JvmStatic
        fun setup() {
            net = Mini().build()
            graph = net.graph()
        }

        fun route(s: Double, x: Double, ways: Set<Long>): RouteAhead {
            val p0 = net.pt(s, x)
            val p = graph.nearby(p0.lat, p0.lon, 20.0) { it.wayId in ways }.first()
            return RouteAheadBuilder().build(graph, p.edge, p.offset)
        }

        fun RouteAhead.describe() = facilities.joinToString { "${it.facility.displayName}@${"%.2f".format(it.distance / 1000)}km" }
    }

    @Test
    fun southboundFindsUnnamedSaAndInferredJct() {
        val r = route(1000.0, 12.5, net.sbWays)
        println("下り: " + r.describe())
        val list = r.facilities.map { it.facility.type to it.facility.displayName }
        assertEquals(
            listOf(
                FacilityType.IC to "東IC",
                FacilityType.SA to "中央SA",
                FacilityType.CAMERA to "速度取締機",
                FacilityType.CAMERA to "速度取締機",
                FacilityType.JCT to "支線道路JCT"
            ),
            list
        )
        assertEquals(8800.0, r.facilities[1].distance, 30.0)
        assertTrue("SA の設備が引き継がれる", r.facilities[1].facility.amenities.isNotEmpty())
        assertEquals(13000.0, r.facilities[2].distance, 5.0)
        assertEquals(100, r.facilities[2].facility.maxspeedKmh)
        assertEquals(15000.0, r.facilities[3].distance, 15.0)
        assertEquals("loop", r.facilities[3].facility.cameraType)
        assertEquals(16000.0, r.facilities[4].distance, 30.0)
        assertTrue(r.facilities[4].facility.typeInferred)
    }

    @Test
    fun northboundFindsInferredIcAndSharedSa() {
        val r = route(19_500.0, -12.5, net.nbWays)
        println("上り: " + r.describe())
        val list = r.facilities.map { it.facility.type to it.facility.displayName }
        assertEquals(
            listOf(
                FacilityType.CAMERA to "登録オービス",
                FacilityType.CAMERA to "速度取締機",
                FacilityType.SA to "中央SA",
                FacilityType.IC to "東IC"
            ),
            list
        )
        assertTrue(r.facilities[0].facility.userDefined)
        assertEquals(3000.0, r.facilities[0].distance, 30.0)
        assertEquals(5000.0, r.facilities[1].distance, 15.0)
        assertEquals("区域へ入るランプの分岐点", 9200.0, r.facilities[2].distance, 30.0)
        assertEquals(14_300.0, r.facilities[3].distance, 30.0)
        assertTrue(r.facilities[3].facility.typeInferred)
    }
}

package com.example.highwaymode.core

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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * テスト用の合成高速道路網（日本の左側通行に合わせた上下線分離道路）。
 *
 *  「テスト自動車道」(E99) 北→南 60km。中央分離帯をはさみ上下線の中心線が 25m 離れている。
 *  下り（南行き）は東側、上り（北行き）は西側。
 *   10km  テスト北IC（下り: 東へ出口 / 上り: 10.3km 地点で西へ出口）
 *   20km  テストSA（下り: 東側区域＋出口分岐「テストSA」, 上り: 西側区域のみ）
 *   30km  JCT（分岐名「中央」= 種別サフィックス無し）→ 別線自動車道 E98（対面通行）
 *   35-40km トンネル
 *   41-41.8km 半径約 760m のカーブ
 *   45km  テストスマートIC
 *   50km  テスト南IC（出口料金所が payment:cash=no）
 *   55km  本線料金所（上下線）
 *  制限速度: 0-25km 100, 25-45km 80, 45km- 100
 */
class TestNetwork {
    private var nextId = 1000L
    private val nodePos = HashMap<Long, LatLon>()
    val ways = ArrayList<OsmWay>()
    val junctions = ArrayList<OsmNode>()
    val tolls = ArrayList<OsmNode>()
    val areas = ArrayList<OsmArea>()
    val pois = ArrayList<OsmPoi>()

    val step = 25.0
    val total = 60_000.0
    val n = (total / step).toInt() + 1
    val center = ArrayList<LatLon>(n)
    val headings = DoubleArray(n)
    val sbIds = LongArray(n)
    val nbIds = LongArray(n)
    val sbWayIds = HashSet<Long>()
    val nbWayIds = HashSet<Long>()
    val otherRoadWayIds = HashSet<Long>()
    val linkWayIds = HashSet<Long>()
    var jctLinkWayId = 0L
    var saLinkWayId = 0L

    fun headingAt(s: Double): Double {
        val bend = 60.0 * ((s - 41_000.0) / 800.0).coerceIn(0.0, 1.0)
        return 180.0 + 25.0 * sin(2 * PI * s / 12_000.0) + bend
    }

    private fun node(p: LatLon): Long {
        val id = nextId++
        nodePos[id] = p
        return id
    }

    private fun way(ids: List<Long>, tags: Map<String, String>): Long {
        val id = nextId++
        val la = DoubleArray(ids.size) { nodePos[ids[it]]!!.lat }
        val lo = DoubleArray(ids.size) { nodePos[ids[it]]!!.lon }
        ways.add(OsmWay(id, ids.toLongArray(), la, lo, tags))
        return id
    }

    private fun left(i: Int): Pair<Double, Double> {
        val psi = headings[i] * Geo.DEG
        return Pair(-cos(psi), sin(psi)) // (east, north)
    }

    private fun offset(p: LatLon, east: Double, north: Double): LatLon =
        LatLon(p.lat + north / Geo.metersPerDegLat(p.lat), p.lon + east / Geo.metersPerDegLon(p.lat))

    /** 下り線上 s[m] の地点から左（東）へ lateral[m] ずらした点 */
    fun sbPoint(s: Double, lateral: Double = 0.0): LatLon {
        val i = (s / step).toInt().coerceIn(0, n - 1)
        val (le, ln) = left(i)
        return offset(center[i], le * (12.5 + lateral), ln * (12.5 + lateral))
    }

    fun nbPoint(s: Double, lateralWest: Double = 0.0): LatLon {
        val i = (s / step).toInt().coerceIn(0, n - 1)
        val (le, ln) = left(i)
        return offset(center[i], -le * (12.5 + lateralWest), -ln * (12.5 + lateralWest))
    }

    fun build(): TestNetwork {
        var p = LatLon(36.30, 138.00)
        for (i in 0 until n) {
            val s = i * step
            headings[i] = headingAt(s)
            center.add(p)
            val q = Geo.destination(p.lat, p.lon, headingAt(s + step / 2), step)
            p = q
        }
        for (i in 0 until n) {
            sbIds[i] = node(sbPoint(i * step))
            nbIds[i] = node(nbPoint(i * step))
        }

        // ---- 本線（区間ごとにタグの異なるウェイ）
        data class Sec(val from: Double, val to: Double, val tags: Map<String, String>)
        val base = mapOf("highway" to "motorway", "name" to "テスト自動車道", "ref" to "E99", "oneway" to "yes", "lanes" to "2")
        val secs = listOf(
            Sec(0.0, 25_000.0, base + ("maxspeed" to "100")),
            Sec(25_000.0, 35_000.0, base + ("maxspeed" to "80")),
            Sec(35_000.0, 40_000.0, base + mapOf("maxspeed" to "80", "tunnel" to "yes", "layer" to "-1")),
            Sec(40_000.0, 45_000.0, base + ("maxspeed" to "80")),
            Sec(45_000.0, total, base + ("maxspeed" to "100"))
        )
        for (sec in secs) {
            val a = (sec.from / step).toInt()
            val b = (sec.to / step).toInt().coerceAtMost(n - 1)
            sbWayIds.add(way((a..b).map { sbIds[it] }, sec.tags))
            nbWayIds.add(way((b downTo a).map { nbIds[it] }, sec.tags))
        }

        // ---- 下り: テスト北IC（10km）
        exitLink(sbIds[400], 10_000.0, true, 500.0, "テスト北IC", mapOf("ref" to "3"), destination = "テスト市;北町")
        // 上り: テスト北IC（10.3km 地点、北行きなので s が減る方向）
        run {
            val pts = ArrayList<Long>()
            pts.add(nbIds[412])
            for (k in 1..10) pts.add(node(nbPoint(10_300.0 - 50.0 * k, k * k * 1.2)))
            linkWayIds.add(way(pts, mapOf("highway" to "motorway_link", "oneway" to "yes")))
            junctions.add(OsmNode(nbIds[412], nodePos[nbIds[412]]!!.lat, nodePos[nbIds[412]]!!.lon,
                mapOf("highway" to "motorway_junction", "name" to "テスト北IC")))
        }

        // ---- 下り: テストSA（出口分岐 19.6km → 区域 → 20.4km で合流）
        run {
            val pts = ArrayList<Long>()
            pts.add(sbIds[784])
            for (k in 1..15) {
                val s = 19_600.0 + 50.0 * k
                val lat = if (k <= 3) 40.0 * k else if (k >= 13) 40.0 * (16 - k) else 110.0
                pts.add(node(sbPoint(s, lat)))
            }
            pts.add(sbIds[816])
            saLinkWayId = way(pts, mapOf("highway" to "motorway_link", "oneway" to "yes"))
            linkWayIds.add(saLinkWayId)
            junctions.add(OsmNode(sbIds[784], nodePos[sbIds[784]]!!.lat, nodePos[sbIds[784]]!!.lon,
                mapOf("highway" to "motorway_junction", "name" to "テストSA")))
            val ring = listOf(sbPoint(19_700.0, 50.0), sbPoint(19_700.0, 170.0), sbPoint(20_300.0, 170.0), sbPoint(20_300.0, 50.0), sbPoint(19_700.0, 50.0))
            val c = sbPoint(20_000.0, 110.0)
            areas.add(OsmArea("w9001", 9001, c.lat, c.lon, listOf(OsmArea.Ring(ring.map { it.lat }.toDoubleArray(), ring.map { it.lon }.toDoubleArray())),
                mapOf("highway" to "services", "name" to "テストSA（下り線）")))
            val g = sbPoint(19_900.0, 130.0)
            pois.add(OsmPoi("n9101", g.lat, g.lon, mapOf("amenity" to "fuel", "brand" to "ENEOS", "opening_hours" to "24/7")))
            val r = sbPoint(20_100.0, 140.0)
            pois.add(OsmPoi("n9102", r.lat, r.lon, mapOf("amenity" to "restaurant", "name" to "テスト食堂")))
        }
        // ---- 上り: テストSA 区域のみ（西側）
        run {
            val ring = listOf(nbPoint(19_700.0, 50.0), nbPoint(19_700.0, 170.0), nbPoint(20_300.0, 170.0), nbPoint(20_300.0, 50.0), nbPoint(19_700.0, 50.0))
            val c = nbPoint(20_000.0, 110.0)
            areas.add(OsmArea("w9002", 9002, c.lat, c.lon, listOf(OsmArea.Ring(ring.map { it.lat }.toDoubleArray(), ring.map { it.lon }.toDoubleArray())),
                mapOf("highway" to "services", "name" to "テストSA（上り線）")))
            val k = nbPoint(20_050.0, 120.0)
            pois.add(OsmPoi("n9201", k.lat, k.lon, mapOf("shop" to "convenience", "brand" to "テストマート")))
        }

        // ---- 30km JCT（名称「中央」）→ 別線自動車道（対面通行 E98）
        run {
            val pts = ArrayList<Long>()
            pts.add(sbIds[1200])
            var last = sbPoint(30_000.0)
            for (k in 1..16) {
                last = sbPoint(30_000.0 + 50.0 * k, 0.6 * k * k)
                pts.add(node(last))
            }
            val wid = way(pts, mapOf("highway" to "motorway_link", "oneway" to "yes"))
            linkWayIds.add(wid)
            jctLinkWayId = wid
            junctions.add(OsmNode(sbIds[1200], nodePos[sbIds[1200]]!!.lat, nodePos[sbIds[1200]]!!.lon,
                mapOf("highway" to "motorway_junction", "name" to "中央")))
            // 別線: 東へ 10km
            val other = ArrayList<Long>()
            other.add(pts.last())
            var q = last
            for (k in 1..200) {
                q = Geo.destination(q.lat, q.lon, 100.0, 50.0)
                other.add(node(q))
            }
            otherRoadWayIds.add(way(other, mapOf("highway" to "motorway", "name" to "別線自動車道", "ref" to "E98", "oneway" to "no", "maxspeed" to "70")))
        }

        // ---- 45km スマートIC
        exitLink(sbIds[1800], 45_000.0, true, 400.0, "テストスマートIC", emptyMap(), tollTags = mapOf("barrier" to "toll_booth"))
        // ---- 50km 南IC（出口料金所が現金不可）
        exitLink(sbIds[2000], 50_000.0, true, 500.0, "テスト南IC", emptyMap(),
            tollTags = mapOf("barrier" to "toll_booth", "name" to "テスト南料金所", "payment:cash" to "no", "payment:etc" to "yes"))
        // ---- 55km 本線料金所（上下線）
        tolls.add(OsmNode(sbIds[2200], nodePos[sbIds[2200]]!!.lat, nodePos[sbIds[2200]]!!.lon,
            mapOf("barrier" to "toll_booth", "name" to "テスト本線料金所")))
        tolls.add(OsmNode(nbIds[2200], nodePos[nbIds[2200]]!!.lat, nodePos[nbIds[2200]]!!.lon,
            mapOf("barrier" to "toll_booth", "name" to "テスト本線料金所")))
        return this
    }

    private fun exitLink(
        startNode: Long, s0: Double, sb: Boolean, length: Double, name: String,
        extraTags: Map<String, String>, destination: String? = null, tollTags: Map<String, String>? = null
    ) {
        val pts = ArrayList<Long>()
        pts.add(startNode)
        val k = (length / 50.0).toInt()
        for (j in 1..k) pts.add(node(sbPoint(s0 + 50.0 * j, 1.0 * j * j)))
        val tags = HashMap<String, String>()
        tags["highway"] = "motorway_link"
        tags["oneway"] = "yes"
        if (destination != null) tags["destination"] = destination
        linkWayIds.add(way(pts, tags))
        if (tollTags != null) {
            val tid = pts[pts.size - 2]
            tolls.add(OsmNode(tid, nodePos[tid]!!.lat, nodePos[tid]!!.lon, tollTags))
        }
        junctions.add(OsmNode(startNode, nodePos[startNode]!!.lat, nodePos[startNode]!!.lon,
            mapOf("highway" to "motorway_junction", "name" to name) + extraTags))
    }

    fun tileData(): OsmTileData = OsmTileData(ways, junctions, tolls, areas, pois)

    fun graph(): RoadGraph {
        val data = OsmDataSet.merge(listOf(tileData()))
        val tiles = HashSet<TileId>()
        for (w in ways) for (i in w.lat.indices) tiles.add(TileId.of(w.lat[i], w.lon[i]))
        return GraphBuilder.build(data, tiles)
    }
}

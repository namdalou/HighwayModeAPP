package com.example.highwaymode.core

import com.example.highwaymode.core.facility.FacilityType
import com.example.highwaymode.core.geo.Geo
import com.example.highwaymode.core.geo.LatLon
import com.example.highwaymode.core.graph.BypassClassifier
import com.example.highwaymode.core.graph.GraphBuilder
import com.example.highwaymode.core.osm.OsmDataSet
import com.example.highwaymode.core.osm.OsmNode
import com.example.highwaymode.core.osm.OsmTileData
import com.example.highwaymode.core.osm.OsmWay
import com.example.highwaymode.core.osm.TileId
import com.example.highwaymode.core.route.RouteAheadBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主要バイパス（IC のある一般国道）の判定。
 *  国道23号（名豊道路）: 0〜30km の上下線分離道路。5/8/11/14km に IC（下りのみ分岐ノード、上りは分岐ノードなし）。
 *  0〜3.5km と 15.5km〜 は IC の無い一般区間（交差点が多い想定）。
 *  国道1号: 別の場所にある、IC の無い分離 4 車線の一般国道 → 対象外。
 */
class BypassTest {

    private var nextId = 1L
    private val pos = HashMap<Long, LatLon>()
    private val ways = ArrayList<OsmWay>()
    private val junctions = ArrayList<OsmNode>()
    private val sbPieces = ArrayList<Pair<Long, Double>>() // (wayId, 始点 km)
    private val r1Ways = HashSet<Long>()

    private fun pt(s: Double, x: Double, lon0: Double = 137.4): LatLon =
        LatLon(34.9 - s / Geo.metersPerDegLat(34.9), lon0 + x / Geo.metersPerDegLon(34.9))

    private fun node(p: LatLon): Long {
        val id = nextId++
        pos[id] = p
        return id
    }

    private fun way(ids: List<Long>, tags: Map<String, String>): Long {
        val id = 500_000 + nextId++
        ways.add(OsmWay(id, ids.toLongArray(), DoubleArray(ids.size) { pos[ids[it]]!!.lat }, DoubleArray(ids.size) { pos[ids[it]]!!.lon }, tags))
        return id
    }

    private fun build(): OsmDataSet {
        val n = 601 // 50m 間隔 30km
        val sb = LongArray(n) { node(pt(it * 50.0, 12.0)) }
        val nb = LongArray(n) { node(pt(it * 50.0, -12.0)) }
        val tags = mapOf("highway" to "trunk", "ref" to "23", "name" to "名豊道路", "oneway" to "yes", "lanes" to "2")
        // 1km ごとのウェイに分割（OSM の実データに近い）
        for (k in 0 until 30) {
            val a = k * 20
            val b = a + 20
            sbPieces.add(way((a..b).map { sb[it] }, tags) to k.toDouble())
            way((b downTo a).map { nb[it] }, tags)
        }
        for (km in listOf(5, 8, 11, 14)) {
            val i = km * 20
            val name = "IC$km"
            way(listOf(sb[i]) + (1..8).map { j -> node(pt(km * 1000.0 + 50 * j, 12.0 + j * j * 1.5)) },
                mapOf("highway" to "trunk_link", "oneway" to "yes"))
            junctions.add(OsmNode(sb[i], pos[sb[i]]!!.lat, pos[sb[i]]!!.lon, mapOf("highway" to "motorway_junction", "name" to "${name}IC")))
            way(listOf(nb[i + 4]) + (1..8).map { j -> node(pt(km * 1000.0 + 200 - 50 * j, -12.0 - j * j * 1.5)) },
                mapOf("highway" to "trunk_link", "oneway" to "yes"))
        }
        // IC の無い分離 4 車線の一般国道（別の場所）
        val r1s = (0..200).map { node(pt(it * 50.0, 12.0, 137.6)) }
        val r1n = (0..200).map { node(pt(it * 50.0, -12.0, 137.6)) }
        val t1 = mapOf("highway" to "trunk", "ref" to "1", "oneway" to "yes")
        r1Ways.add(way(r1s, t1))
        r1Ways.add(way(r1n.reversed(), t1))
        // 交差点の左折レーン（trunk_link）
        r1Ways.add(way(listOf(r1s[100]) + (1..2).map { j -> node(pt(5000.0 + 30 * j, 12.0 + 6.0 * j, 137.6)) }, mapOf("highway" to "trunk_link", "oneway" to "yes")))
        return OsmDataSet.merge(listOf(OsmTileData(ways, junctions, emptyList(), emptyList(), emptyList())))
    }

    @Test
    fun selectsOnlyInterchangeSections() {
        val data = build()
        val keep = BypassClassifier.select(data)
        val keptKm = sbPieces.filter { it.first in keep }.map { it.second }
        println("採用した下り区間 [km]: ${keptKm.first()}〜${keptKm.last() + 1}")
        assertTrue("IC の間は連続して採用", (5..13).all { k -> k.toDouble() in keptKm })
        assertTrue("最初の IC の手前 1.5km まで", 3.0 in keptKm || 4.0 in keptKm)
        assertFalse("IC の無い区間（0〜2km）は対象外", 0.0 in keptKm || 1.0 in keptKm)
        assertFalse("最後の IC から先の一般区間（17km〜）は対象外", keptKm.any { it >= 17.0 })
        assertTrue("IC の無い一般国道は対象外", r1Ways.none { it in keep })
    }

    @Test
    fun routeOnBypassShowsInterchanges() {
        val data = build()
        val tiles = HashSet<TileId>()
        for (w in ways) for (i in w.lat.indices) tiles.add(TileId.of(w.lat[i], w.lon[i]))
        val g = GraphBuilder.build(data, tiles)
        assertTrue(g.edges.none { it.wayId in r1Ways })
        val p0 = pt(6000.0, 12.0)
        val p = g.nearby(p0.lat, p0.lon, 20.0) { !it.attr.isLink }.first()
        val e = g.edges[p.edge]
        assertEquals("国道23号", e.attr.displayRef)
        assertTrue(e.attr.generalRoad)
        val r = RouteAheadBuilder().build(g, p.edge, p.offset)
        println("名豊道路 下り 6km 地点から: " + r.facilities.joinToString { "${it.facility.displayName}@${"%.1f".format(it.distance / 1000)}km" })
        assertEquals(listOf("IC8IC", "IC11IC", "IC14IC"), r.facilities.map { it.facility.displayName })
        assertTrue(r.facilities.all { it.facility.type == FacilityType.IC })
        // 上り（分岐ノード未登録）でも推定出口として IC が出る
        val q0 = pt(13_500.0, -12.0)
        val q = g.nearby(q0.lat, q0.lon, 20.0) { !it.attr.isLink }.first()
        val rn = RouteAheadBuilder().build(g, q.edge, q.offset)
        println("名豊道路 上り 13.5km 地点から: " + rn.facilities.joinToString { "${it.facility.displayName}@${"%.1f".format(it.distance / 1000)}km" })
        assertEquals(listOf("IC11IC", "IC8IC", "IC5IC"), rn.facilities.map { it.facility.displayName })
    }
}

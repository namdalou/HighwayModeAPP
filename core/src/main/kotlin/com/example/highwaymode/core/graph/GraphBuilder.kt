package com.example.highwaymode.core.graph

import com.example.highwaymode.core.facility.FacilityExtractor
import com.example.highwaymode.core.geo.Geo
import com.example.highwaymode.core.osm.OsmDataSet
import com.example.highwaymode.core.osm.OsmWay
import com.example.highwaymode.core.osm.TileId

/**
 * OSM ウェイ群から有向道路グラフを構築する。
 *  - 複数ウェイが共有するノード・ウェイ端点を頂点とし、ウェイをそこで分割してエッジにする。
 *  - 一方通行（oneway タグ／motorway の暗黙一方通行）を解釈して有向エッジを作る。
 *  - 施設（IC/JCT/SA/PA/料金所）をエッジ上に位置づける。
 */
object GraphBuilder {

    fun build(input: OsmDataSet, loadedTiles: Set<TileId>): RoadGraph {
        // 一般国道のうち IC のある主要バイパス区間だけを残す
        val data = BypassClassifier.filter(input)
        val ways = data.roads.values.filter { it.nodeIds.size >= 2 }
        if (ways.isEmpty()) return RoadGraph(emptyArray(), emptyArray(), emptyMap(), SpatialIndex(), loadedTiles)

        // ノードの使用回数（端点は必ず頂点にする）
        val use = LongIntMap(ways.sumOf { it.nodeIds.size })
        for (w in ways) {
            val ids = w.nodeIds
            for (i in ids.indices) {
                val bonus = if (i == 0 || i == ids.size - 1) 2 else 1
                use.add(ids[i], bonus)
            }
        }

        val vertices = ArrayList<Vertex>()
        val vertexByNode = HashMap<Long, Int>()
        fun vertexOf(w: OsmWay, i: Int): Int {
            val id = w.nodeIds[i]
            return vertexByNode.getOrPut(id) {
                vertices.add(Vertex(id, w.lat[i], w.lon[i]))
                vertices.size - 1
            }
        }

        val edges = ArrayList<Edge>()
        for (w in ways) {
            val rc = RoadClass.of(w.tags["highway"])
            val mode = RoadAttr.onewayMode(w.tags, rc)
            val fwdAttr = RoadAttr.fromTags(w.tags, true, mode)
            val bwdAttr = RoadAttr.fromTags(w.tags, false, mode)
            val ids = w.nodeIds
            var start = 0
            for (i in 1 until ids.size) {
                val isVertex = use.get(ids[i]) >= 2 || i == ids.size - 1
                if (!isVertex) continue
                val from = start
                val to = i
                start = i
                if (to - from < 1) continue
                val vFrom = vertexOf(w, from)
                val vTo = vertexOf(w, to)
                val n = to - from + 1
                val pIds = LongArray(n) { ids[from + it] }
                val pLat = DoubleArray(n) { w.lat[from + it] }
                val pLon = DoubleArray(n) { w.lon[from + it] }
                if (polylineLength(pLat, pLon) < 0.05) continue
                when (mode) {
                    OnewayMode.FORWARD -> edges.add(makeEdge(edges.size, w.id, vFrom, vTo, pIds, pLat, pLon, fwdAttr, false, true))
                    OnewayMode.BACKWARD -> edges.add(
                        makeEdge(edges.size, w.id, vTo, vFrom, pIds.reversedArray(), pLat.reversedArray(), pLon.reversedArray(), bwdAttr, false, false)
                    )
                    OnewayMode.BOTH, OnewayMode.IMPLIED_FORWARD -> {
                        val a = makeEdge(edges.size, w.id, vFrom, vTo, pIds, pLat, pLon, fwdAttr, false, true)
                        edges.add(a)
                        val b = makeEdge(
                            edges.size, w.id, vTo, vFrom, pIds.reversedArray(), pLat.reversedArray(), pLon.reversedArray(),
                            bwdAttr, mode == OnewayMode.IMPLIED_FORWARD, false
                        )
                        edges.add(b)
                        a.twin = b.id
                        b.twin = a.id
                    }
                }
            }
        }

        // 隣接リスト
        val outCount = IntArray(vertices.size)
        val inCount = IntArray(vertices.size)
        for (e in edges) {
            outCount[e.from]++
            inCount[e.to]++
        }
        for (i in vertices.indices) {
            vertices[i].out = IntArray(outCount[i])
            vertices[i].inc = IntArray(inCount[i])
        }
        val oFill = IntArray(vertices.size)
        val iFill = IntArray(vertices.size)
        for (e in edges) {
            vertices[e.from].out[oFill[e.from]++] = e.id
            vertices[e.to].inc[iFill[e.to]++] = e.id
        }

        // 空間インデックス（最近傍エッジ検索用）
        val index = SpatialIndex()
        for (e in edges) {
            for (s in 0 until e.pointCount - 1) {
                index.insertSegment(e.id, s, e.lat[s], e.lon[s], e.lat[s + 1], e.lon[s + 1])
            }
        }

        val graph = RoadGraph(vertices.toTypedArray(), edges.toTypedArray(), vertexByNode, index, loadedTiles)
        FacilityExtractor.attach(graph, data)
        return graph
    }

    private fun polylineLength(lat: DoubleArray, lon: DoubleArray): Double {
        var s = 0.0
        for (i in 0 until lat.size - 1) s += Geo.distance(lat[i], lon[i], lat[i + 1], lon[i + 1])
        return s
    }

    private fun makeEdge(
        id: Int, wayId: Long, from: Int, to: Int,
        ids: LongArray, lat: DoubleArray, lon: DoubleArray,
        attr: RoadAttr, reverseOfImplied: Boolean, forwardOnWay: Boolean
    ): Edge {
        val n = lat.size
        val cum = DoubleArray(n)
        val brg = DoubleArray(n - 1)
        for (i in 0 until n - 1) {
            cum[i + 1] = cum[i] + Geo.distance(lat[i], lon[i], lat[i + 1], lon[i + 1])
            brg[i] = Geo.bearing(lat[i], lon[i], lat[i + 1], lon[i + 1])
        }
        // 長さ 0 の区間の方位は前後から補う
        for (i in 0 until n - 1) {
            if (cum[i + 1] - cum[i] < 0.01) {
                brg[i] = if (i > 0) brg[i - 1] else if (i + 1 < n - 1) brg[i + 1] else 0.0
            }
        }
        return Edge(id, wayId, from, to, ids, lat, lon, cum, brg, attr, reverseOfImplied, forwardOnWay)
    }
}

package com.example.highwaymode.core.graph

import com.example.highwaymode.core.facility.FacilityAnchor
import com.example.highwaymode.core.geo.Geo
import com.example.highwaymode.core.geo.LocalFrame
import com.example.highwaymode.core.geo.SegmentMath
import com.example.highwaymode.core.geo.SegmentProjection
import com.example.highwaymode.core.osm.TileId
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** グラフの頂点（OSM ノードのうち分岐・合流・端点になっているもの） */
class Vertex(val nodeId: Long, val lat: Double, val lon: Double) {
    var out: IntArray = IntArray(0)
    var inc: IntArray = IntArray(0)
}

/**
 * 有向エッジ。座標列は進行方向順に並んでいる。
 * offset はエッジ始点からの道のり [m]。
 */
class Edge(
    val id: Int,
    val wayId: Long,
    val from: Int,
    val to: Int,
    val nodeIds: LongArray,
    val lat: DoubleArray,
    val lon: DoubleArray,
    /** 累積距離 [m]（cum[0] = 0） */
    val cum: DoubleArray,
    /** 各区間の方位 [deg] */
    val segBearing: DoubleArray,
    val attr: RoadAttr,
    /** 一方通行が「暗黙」（タグ無しの motorway）のウェイを逆走方向に辿るエッジ */
    val reverseOfImplied: Boolean,
    /** ウェイの記述方向と同じ向きか */
    val forwardOnWay: Boolean
) {
    var twin: Int = -1
    val length: Double get() = cum[cum.size - 1]
    val pointCount: Int get() = lat.size

    /** offset を含む区間番号 [0, n-2] */
    fun segmentIndexAt(offset: Double): Int {
        val n = cum.size
        if (offset <= 0.0) return 0
        if (offset >= cum[n - 1]) return n - 2
        var lo = 0
        var hi = n - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (cum[mid] <= offset) lo = mid else hi = mid
        }
        return min(lo, n - 2)
    }

    /** offset 地点の緯度経度を out[0], out[1] に書き込む */
    fun pointAt(offset: Double, out: DoubleArray) {
        val i = segmentIndexAt(offset)
        val segLen = cum[i + 1] - cum[i]
        val t = if (segLen > 1e-9) ((offset - cum[i]) / segLen).coerceIn(0.0, 1.0) else 0.0
        out[0] = lat[i] + (lat[i + 1] - lat[i]) * t
        out[1] = lon[i] + (lon[i + 1] - lon[i]) * t
    }

    fun bearingAt(offset: Double): Double = segBearing[segmentIndexAt(offset)]

    /** 前後 span [m] の弦から求めた滑らかな方位（ノード間隔の粗いカーブ対策） */
    fun smoothBearingAt(offset: Double, span: Double = 15.0): Double {
        if (length < 2 * span) return bearingAt(offset)
        val a = DoubleArray(2)
        val b = DoubleArray(2)
        pointAt((offset - span).coerceIn(0.0, length), a)
        pointAt((offset + span).coerceIn(0.0, length), b)
        return Geo.bearing(a[0], a[1], b[0], b[1])
    }

    val startBearing: Double get() = segBearing[0]
    val endBearing: Double get() = segBearing[segBearing.size - 1]
}

class RestAreaRef(val lat: Double, val lon: Double, val radius: Double, val facility: FacilityRefName)

/** SA/PA の表示名（循環参照を避けるため名前だけ持つ） */
class FacilityRefName(val displayName: String)

class EdgeProjection(
    val edge: Int,
    val offset: Double,
    val dist: Double,
    /** 正 = 進行方向左側 */
    val lateral: Double,
    val lat: Double,
    val lon: Double
)

/**
 * 高速道路ネットワークの有向グラフ。
 */
class RoadGraph(
    val vertices: Array<Vertex>,
    val edges: Array<Edge>,
    val vertexByNode: Map<Long, Int>,
    private val index: SpatialIndex,
    val loadedTiles: Set<TileId>
) {
    /** エッジごとの施設アンカー（offset 昇順） */
    var anchors: Array<List<FacilityAnchor>> = Array(edges.size) { emptyList() }
        internal set

    /** SA/PA 区域（中心・半径・施設）。停車中の判定に使う */
    var restAreas: List<RestAreaRef> = emptyList()
        internal set

    /** 地点を含む（近い）SA/PA */
    fun restAreaAt(lat: Double, lon: Double): RestAreaRef? =
        restAreas.firstOrNull { Geo.distance(lat, lon, it.lat, it.lon) <= it.radius }

    /** グラフ生成ごとに変わる識別子（キャッシュ無効化用） */
    val generation: Long = nextGeneration()

    val isEmpty: Boolean get() = edges.isEmpty()

    fun edge(id: Int): Edge = edges[id]
    fun outEdges(vertex: Int): IntArray = vertices[vertex].out
    fun inEdges(vertex: Int): IntArray = vertices[vertex].inc

    fun isLoaded(lat: Double, lon: Double): Boolean = TileId.of(lat, lon) in loadedTiles

    /**
     * 半径 radius [m] 以内のエッジを、エッジごとに最近傍の射影 1 つずつ返す。
     */
    fun nearby(lat: Double, lon: Double, radius: Double, filter: ((Edge) -> Boolean)? = null): List<EdgeProjection> {
        if (edges.isEmpty()) return emptyList()
        val frame = LocalFrame(lat, lon)
        val best = HashMap<Int, EdgeProjection>()
        val proj = SegmentProjection()
        index.query(lat, lon, radius) { edgeId, seg ->
            val e = edges[edgeId]
            if (filter != null && !filter(e)) return@query
            val ax = frame.x(e.lon[seg]); val ay = frame.y(e.lat[seg])
            val bx = frame.x(e.lon[seg + 1]); val by = frame.y(e.lat[seg + 1])
            SegmentMath.project(0.0, 0.0, ax, ay, bx, by, proj)
            if (proj.dist > radius) return@query
            val prev = best[edgeId]
            if (prev == null || proj.dist < prev.dist) {
                val off = e.cum[seg] + proj.t * (e.cum[seg + 1] - e.cum[seg])
                val pLat = e.lat[seg] + (e.lat[seg + 1] - e.lat[seg]) * proj.t
                val pLon = e.lon[seg] + (e.lon[seg + 1] - e.lon[seg]) * proj.t
                best[edgeId] = EdgeProjection(edgeId, off, proj.dist, proj.lateral, pLat, pLon)
            }
        }
        return best.values.sortedBy { it.dist }
    }

    /**
     * いちばん近い道路（エッジ）までの距離 [m]。maxRadius 以内に無ければ null。
     * 近くから順に範囲を広げて調べる（電池の節約の判定で数秒ごとに呼ぶため、近くに道路があればすぐ終わる）。
     */
    fun nearestDistance(lat: Double, lon: Double, maxRadius: Double): Double? {
        if (edges.isEmpty()) return null
        val frame = LocalFrame(lat, lon)
        val proj = SegmentProjection()
        var r = minOf(400.0, maxRadius)
        while (true) {
            var best = Double.POSITIVE_INFINITY
            index.query(lat, lon, r) { edgeId, seg ->
                val e = edges[edgeId]
                val ax = frame.x(e.lon[seg]); val ay = frame.y(e.lat[seg])
                val bx = frame.x(e.lon[seg + 1]); val by = frame.y(e.lat[seg + 1])
                SegmentMath.project(0.0, 0.0, ax, ay, bx, by, proj)
                if (proj.dist < best) best = proj.dist
            }
            // 調べた範囲（半径 r の円）の中で見つかったものが最も近い
            if (best <= r) return best
            if (r >= maxRadius) return null
            r = minOf(r * 3.0, maxRadius)
        }
    }

    /** 指定エッジへの最近傍射影 */
    fun projectOnEdge(edgeId: Int, lat: Double, lon: Double): EdgeProjection {
        val e = edges[edgeId]
        val frame = LocalFrame(lat, lon)
        val proj = SegmentProjection()
        var best: EdgeProjection? = null
        for (seg in 0 until e.pointCount - 1) {
            val ax = frame.x(e.lon[seg]); val ay = frame.y(e.lat[seg])
            val bx = frame.x(e.lon[seg + 1]); val by = frame.y(e.lat[seg + 1])
            SegmentMath.project(0.0, 0.0, ax, ay, bx, by, proj)
            if (best == null || proj.dist < best.dist) {
                val off = e.cum[seg] + proj.t * (e.cum[seg + 1] - e.cum[seg])
                val pLat = e.lat[seg] + (e.lat[seg + 1] - e.lat[seg]) * proj.t
                val pLon = e.lon[seg] + (e.lon[seg + 1] - e.lon[seg]) * proj.t
                best = EdgeProjection(edgeId, off, proj.dist, proj.lateral, pLat, pLon)
            }
        }
        return best!!
    }

    companion object {
        private var gen = 0L
        @Synchronized
        private fun nextGeneration(): Long = ++gen

        fun empty(): RoadGraph = RoadGraph(emptyArray(), emptyArray(), emptyMap(), SpatialIndex(), emptySet())
    }
}

/**
 * 線分の格子インデックス（セル ≒ 220m 四方）。
 */
class SpatialIndex {
    private val cells = HashMap<Long, LongList>()

    private class LongList {
        var data = LongArray(8)
        var size = 0
        fun add(v: Long) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = v
        }
    }

    private fun key(iy: Int, ix: Int): Long = (iy.toLong() shl 32) or (ix.toLong() and 0xffffffffL)

    fun insertSegment(edgeId: Int, seg: Int, lat1: Double, lon1: Double, lat2: Double, lon2: Double) {
        val y0 = floor(min(lat1, lat2) / CELL_LAT).toInt()
        val y1 = floor(max(lat1, lat2) / CELL_LAT).toInt()
        val x0 = floor(min(lon1, lon2) / CELL_LON).toInt()
        val x1 = floor(max(lon1, lon2) / CELL_LON).toInt()
        val packed = (edgeId.toLong() shl 24) or seg.toLong()
        for (iy in y0..y1) for (ix in x0..x1) {
            cells.getOrPut(key(iy, ix)) { LongList() }.add(packed)
        }
    }

    inline fun query(lat: Double, lon: Double, radius: Double, crossinline visit: (edgeId: Int, seg: Int) -> Unit) {
        queryRaw(lat, lon, radius) { p -> visit((p ushr 24).toInt(), (p and 0xFFFFFFL).toInt()) }
    }

    fun queryRaw(lat: Double, lon: Double, radius: Double, visit: (Long) -> Unit) {
        val dLat = radius / Geo.metersPerDegLat(lat)
        val dLon = radius / Geo.metersPerDegLon(lat)
        val y0 = floor((lat - dLat) / CELL_LAT).toInt()
        val y1 = floor((lat + dLat) / CELL_LAT).toInt()
        val x0 = floor((lon - dLon) / CELL_LON).toInt()
        val x1 = floor((lon + dLon) / CELL_LON).toInt()
        for (iy in y0..y1) for (ix in x0..x1) {
            val list = cells[key(iy, ix)] ?: continue
            for (i in 0 until list.size) visit(list.data[i])
        }
    }

    companion object {
        const val CELL_LAT = 0.002
        const val CELL_LON = 0.0025
    }
}

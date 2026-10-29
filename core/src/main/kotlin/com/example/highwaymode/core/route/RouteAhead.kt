package com.example.highwaymode.core.route

import com.example.highwaymode.core.facility.Facility
import com.example.highwaymode.core.facility.FacilityExtractor
import com.example.highwaymode.core.facility.FacilityType
import com.example.highwaymode.core.graph.RoadContinuity
import com.example.highwaymode.core.graph.RoadGraph
import kotlin.math.abs
import kotlin.math.min

class RouteSegment(val edge: Int, val startOffset: Double, val endOffset: Double, val startDist: Double) {
    val length: Double get() = endOffset - startOffset
    val endDist: Double get() = startDist + length
}

class UpcomingFacility(
    val facility: Facility,
    /** 現在地からの道のり [m] */
    val distance: Double,
    val edge: Int,
    val offset: Double
)

class RoadChange(val distance: Double, val fromName: String?, val toName: String?, val toRef: String?)

enum class RouteEnd { MAX_LENGTH, DATA_BOUNDARY, ROAD_END, LOOP }

/** 現在地から「同じ路線を走り続けた場合」の前方経路 */
class RouteAhead(
    val graphGeneration: Long,
    val startEdge: Int,
    val startOffset: Double,
    val segments: List<RouteSegment>,
    val length: Double,
    val facilities: List<UpcomingFacility>,
    val end: RouteEnd,
    val endLat: Double,
    val endLon: Double,
    /** 継続方向の判断が際どい分岐までの距離 */
    val forks: List<Double>,
    val roadChanges: List<RoadChange>
) {
    /** dist [m] 地点を含む区間 */
    fun segmentIndexAt(dist: Double): Int {
        if (segments.isEmpty()) return -1
        var lo = 0
        var hi = segments.size - 1
        if (dist <= 0) return 0
        if (dist >= segments[hi].startDist) return hi
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (segments[mid].startDist <= dist) lo = mid else hi = mid
        }
        return lo
    }
}

/**
 * マッチ位置から前方へ道路グラフを辿り、通過予定の施設を道のり付きで列挙する。
 * 分岐では RoadContinuity（路線番号・名称の一致、本線優先、曲がり角）で継続先を選ぶ。
 * 例: 長野道を南下して岡谷JCTに達すると、同じ E19 の中央道（名古屋方面）へ継続し、辰野PA が並ぶ。
 */
class RouteAheadBuilder(
    val maxLength: Double = 150_000.0,
    val maxFacilities: Int = 40
) {
    fun build(graph: RoadGraph, edge: Int, offset: Double): RouteAhead {
        val segs = ArrayList<RouteSegment>()
        val facs = ArrayList<UpcomingFacility>()
        val seen = HashSet<String>()
        val forks = ArrayList<Double>()
        val changes = ArrayList<RoadChange>()
        val visited = HashSet<Int>()
        var cur = graph.edges[edge]
        var off = offset.coerceIn(0.0, cur.length)
        var dist = 0.0
        var end = RouteEnd.MAX_LENGTH
        var endLat = cur.lat[cur.pointCount - 1]
        var endLon = cur.lon[cur.pointCount - 1]
        var first = true

        while (true) {
            if (!visited.add(cur.id)) {
                end = RouteEnd.LOOP
                break
            }
            val take = min(cur.length - off, maxLength - dist).coerceAtLeast(0.0)
            segs.add(RouteSegment(cur.id, off, off + take, dist))
            if (facs.size < maxFacilities) {
                for (a in graph.anchors[cur.id]) {
                    if (a.offset < off - 1e-6 || a.offset > off + take) continue
                    if (first && a.offset < off + 1.0) continue // 通過済み
                    addFacility(facs, seen, a.facility, dist + (a.offset - off), cur.id, a.offset)
                }
            }
            dist += take
            first = false
            if (dist >= maxLength - 1e-6) {
                end = RouteEnd.MAX_LENGTH
                val p = DoubleArray(2)
                cur.pointAt(off + take, p)
                endLat = p[0]
                endLon = p[1]
                break
            }
            endLat = cur.lat[cur.pointCount - 1]
            endLon = cur.lon[cur.pointCount - 1]
            val succ = RoadContinuity.rankedSuccessors(graph, cur)
            if (succ.isEmpty()) {
                end = if (graph.isLoaded(endLat, endLon)) RouteEnd.ROAD_END else RouteEnd.DATA_BOUNDARY
                break
            }
            if (succ.size >= 2 && succ[0].second - succ[1].second < 2.0) forks.add(dist)
            val next = graph.edges[succ[0].first]
            if ((cur.attr.isLink && !next.attr.isLink) ||
                (!next.attr.isLink && FacilityExtractor.differentRoad(cur.attr, next.attr))
            ) {
                changes.add(RoadChange(dist, cur.attr.displayName, next.attr.displayName, next.attr.displayRef))
            }
            cur = next
            off = 0.0
        }
        return RouteAhead(
            graph.generation, edge, offset, segs, dist, facs, end, endLat, endLon, forks, changes
        )
    }

    private fun addFacility(
        out: MutableList<UpcomingFacility>,
        seen: MutableSet<String>,
        f: Facility,
        distance: Double,
        edge: Int,
        offset: Double
    ) {
        if (!seen.add(f.id)) return
        if (f.type == FacilityType.CAMERA) {
            // 同じ取締機が門型の両端など 2 ノードで登録されている場合だけ除く
            if (out.none { it.facility.type == FacilityType.CAMERA && abs(distance - it.distance) < 60.0 }) {
                out.add(UpcomingFacility(f, distance, edge, offset))
            }
            return
        }
        // 同名・同種別が 3km 以内に重複（出口分岐ノードと SA/PA 区域など）→ 先の方を残す
        val dup = out.any {
            it.facility.type == f.type && it.facility.baseName == f.baseName && distance - it.distance < 3000.0
        }
        if (dup) return
        // SA/PA 区域と、その出口分岐（種別推定が IC 等）の重複も同名なら除外
        if (f.type.isRestArea && out.any { it.facility.baseName == f.baseName && it.facility.typeInferred && distance - it.distance < 2000.0 }) return
        out.add(UpcomingFacility(f, distance, edge, offset))
    }

    companion object {
        fun isDisplayable(f: Facility): Boolean = f.type != FacilityType.TB || f.baseName.isNotEmpty() || f.etcOnly
    }
}

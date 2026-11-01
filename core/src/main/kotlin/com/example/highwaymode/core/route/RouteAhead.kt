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
    private val segmentByEdge: Map<Int, RouteSegment> by lazy { segments.associateBy { it.edge } }

    /** 道のり dist [m] の地点の座標（out = [lat, lon]） */
    fun pointAt(graph: RoadGraph, dist: Double, out: DoubleArray) {
        val i = segmentIndexAt(dist)
        if (i < 0) {
            out[0] = endLat
            out[1] = endLon
            return
        }
        val seg = segments[i]
        graph.edges[seg.edge].pointAt(seg.startOffset + (dist - seg.startDist).coerceIn(0.0, seg.length), out)
    }

    /** 座標を経路に投影した道のり [m]。経路から radius 以上離れていれば null */
    fun distanceOf(graph: RoadGraph, lat: Double, lon: Double, radius: Double = 150.0): Double? {
        var best: Double? = null
        var bestD = Double.MAX_VALUE
        for (p in graph.nearby(lat, lon, radius)) {
            val seg = segmentByEdge[p.edge] ?: continue
            if (p.offset < seg.startOffset - 1.0 || p.offset > seg.endOffset + 1.0) continue
            if (p.dist < bestD) {
                bestD = p.dist
                best = seg.startDist + (p.offset - seg.startOffset).coerceIn(0.0, seg.length)
            }
        }
        return best
    }

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
    /** 分岐点（本線上）: 道のり・分岐の手前のエッジ・選ばなかった後続 */
    private class Fork(val dist: Double, val edge: Int, val others: List<Int>)

    fun build(graph: RoadGraph, edge: Int, offset: Double): RouteAhead {
        val segs = ArrayList<RouteSegment>()
        val raw = ArrayList<UpcomingFacility>()
        val forkInfo = ArrayList<Fork>()
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
            for (a in graph.anchors[cur.id]) {
                if (a.offset < off - 1e-6 || a.offset > off + take) continue
                if (first && a.offset < off + 1.0) continue // 通過済み
                raw.add(UpcomingFacility(a.facility, dist + (a.offset - off), cur.id, a.offset))
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
            // 同じ路線（路線番号が共通・同名）の本線が続くなら、それを優先する。
            // 本線の名前が変わる地点（国道23号 → 知立バイパス）で、旧名と同じ名前のランプへ
            // 進んでしまい、その先で経路が終わる（施設が 1 つしか出ない）のを防ぐ
            val pick = if (cur.attr.isLink) succ[0].first
            else succ.firstOrNull { (id, _) ->
                val n = graph.edges[id]
                !n.attr.isLink && FacilityExtractor.sameRoute(cur.attr, n.attr)
            }?.first ?: succ[0].first
            if (!cur.attr.isLink && succ.size >= 2) forkInfo.add(Fork(dist, cur.id, succ.map { it.first }.filter { it != pick }))
            val next = graph.edges[pick]
            if ((cur.attr.isLink && !next.attr.isLink) ||
                (!next.attr.isLink && FacilityExtractor.differentRoad(cur.attr, next.attr))
            ) {
                changes.add(RoadChange(dist, cur.attr.displayName, next.attr.displayName, next.attr.displayRef))
            }
            cur = next
            off = 0.0
        }
        // 並行する別の車線（料金所の脇を通る連絡路・出口側の車線など）にある出口も、合流するなら前方の施設に含める
        raw.addAll(parallelBranchFacilities(graph, segs, forkInfo, dist))
        raw.sortBy { it.distance }
        val facs = ArrayList<UpcomingFacility>()
        val seen = HashSet<String>()
        for (u in raw) {
            if (facs.size >= maxFacilities) break
            addFacility(facs, seen, u.facility, u.distance, u.edge, u.offset)
        }
        return RouteAhead(
            graph.generation, edge, offset, segs, dist, facs, end, endLat, endLon, forks, changes
        )
    }

    /**
     * 分岐で選ばなかった側が、同じ路線の並行する車線（または短い連絡路を経た同じ路線の本線）で、
     * 先で前方経路に合流する場合、その側にある出口・SA/PA・JCT を返す。
     * 例: 播但連絡道路の福崎付近は、通過する車線（有料）と、福崎南・福崎・福崎北の出口がある車線（無料区間）に分かれている。
     * 通過する側を前方経路にすると、出口側の 3 つの IC が飛ばされてしまう。
     */
    private fun parallelBranchFacilities(graph: RoadGraph, segs: List<RouteSegment>, forkInfo: List<Fork>, routeLength: Double): List<UpcomingFacility> {
        if (forkInfo.isEmpty()) return emptyList()
        val onRoute = HashMap<Int, Double>(segs.size * 2)
        val routeEdges = HashSet<Int>(segs.size * 2)
        for (sg in segs) {
            onRoute[graph.edges[sg.edge].to] = sg.endDist
            routeEdges.add(sg.edge)
        }
        val out = ArrayList<UpcomingFacility>()
        for (fk in forkInfo) {
            val base = graph.edges[fk.edge]
            for (alt in fk.others.take(3)) {
                if (alt in routeEdges) continue
                var e = graph.edges[alt]
                var d = fk.dist
                var linkRun = 0.0
                var sawMain = false
                var rejoined = false
                val found = ArrayList<UpcomingFacility>()
                var guard = 0
                while (guard++ < 80 && d - fk.dist < PARALLEL_MAX_M) {
                    if (e.id in routeEdges) {
                        // 前方経路そのものに戻った
                        rejoined = true
                        break
                    }
                    if (e.attr.isLink) {
                        linkRun += e.length
                        if (linkRun > PARALLEL_MAX_LINK_M) break
                    } else {
                        linkRun = 0.0
                        // 同じ路線の本線だけ（一般道・別の路線へ出たら終わり）
                        if (!FacilityExtractor.sameRoute(base.attr, e.attr)) break
                        sawMain = true
                        for (a in graph.anchors[e.id]) {
                            val t = a.facility.type
                            if (t == FacilityType.CAMERA || t == FacilityType.TB) continue
                            found.add(UpcomingFacility(a.facility, d + a.offset, e.id, a.offset))
                        }
                    }
                    d += e.length
                    val r = onRoute[e.to]
                    if (r != null && r > fk.dist) {
                        rejoined = true
                        break
                    }
                    val succ = RoadContinuity.rankedSuccessors(graph, e)
                    val nx = (if (e.attr.isLink) succ.firstOrNull()
                    else succ.firstOrNull { (id, _) -> val n = graph.edges[id]; !n.attr.isLink && FacilityExtractor.sameRoute(base.attr, n.attr) }
                        ?: succ.firstOrNull())?.first ?: break
                    e = graph.edges[nx]
                }
                if (rejoined && sawMain) out.addAll(found.filter { it.distance in fk.dist..routeLength })
            }
        }
        return out
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
        // 同名・同種別が 3km 以内に重複（出口分岐ノードと SA/PA 区域など）→ 先の方を残す。
        // ただし先の方が「出口なし」で、こちらに出口がある（並行する車線の出口）なら、こちらに置き換える
        val dupIndex = out.indexOfFirst {
            it.facility.type == f.type && it.facility.baseName == f.baseName && distance - it.distance < 3000.0
        }
        if (dupIndex >= 0) {
            if (out[dupIndex].facility.noExit && !f.noExit) out[dupIndex] = UpcomingFacility(f, distance, edge, offset)
            return
        }
        // SA/PA 区域と、その出口分岐（種別推定が IC 等）の重複も同名なら除外
        if (f.type.isRestArea && out.any { it.facility.baseName == f.baseName && it.facility.typeInferred && distance - it.distance < 2000.0 }) return
        out.add(UpcomingFacility(f, distance, edge, offset))
    }

    companion object {
        fun isDisplayable(f: Facility): Boolean = f.type != FacilityType.TB || f.baseName.isNotEmpty() || f.etcOnly

        /** 並行する車線を辿る長さの上限 */
        private const val PARALLEL_MAX_M = 8000.0
        /** 並行する車線へ移る連絡路（料金所の脇など）の長さの上限 */
        private const val PARALLEL_MAX_LINK_M = 1200.0
    }
}

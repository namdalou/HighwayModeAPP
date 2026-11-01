package com.example.highwaymode.core.traffic

import com.example.highwaymode.core.geo.Geo
import com.example.highwaymode.core.graph.RoadGraph
import com.example.highwaymode.core.route.RouteAhead
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** 有向エッジ上の交通情報の区間 */
class EdgeSpan(val start: Double, val end: Double, val item: TrafficItem)

/**
 * 取得した交通情報（座標列）を道路グラフの有向エッジに対応づける。
 *  - 情報の座標列を 40m ごとに区切り、近く（maxDist 以内）で進行方位が合うエッジに載せる
 *  - 進行方向が逆（対向車線の情報）や、並行する一般道・高架下の道路の情報は載らない
 *  - 交通情報が逆向きの座標列で届く API（TomTom）は、分離された車線に限り逆向きでも照合する
 */
class EdgeTraffic(val data: TrafficData, val graphGeneration: Long, val spans: Map<Int, List<EdgeSpan>>, val coveredEdges: Set<Int>) {
    val isEmpty: Boolean get() = spans.isEmpty()
}

object TrafficMapper {

    private const val STEP = 40.0

    fun annotate(graph: RoadGraph, data: TrafficData, maxDist: Double = 18.0, tryReverse: Boolean = data.provider == "TomTom"): EdgeTraffic {
        val spans = HashMap<Int, MutableList<EdgeSpan>>()
        for (item in data.items) {
            if (item.lat.size == 1) {
                // 地点情報（事故など）: 近い車線のうち最も近い 1 本だけ
                val near = graph.nearby(item.lat[0], item.lon[0], maxDist * 1.5)
                val best = near.minByOrNull { it.dist } ?: continue
                spans.getOrPut(best.edge) { ArrayList() }.add(EdgeSpan(max(0.0, best.offset - 50), best.offset + 50, item))
                continue
            }
            var res = mapLine(graph, item.lat, item.lon, maxDist, allowBidirectional = true)
            if (tryReverse && res.matched < res.samples * 0.4) {
                val rl = item.lat.reversedArray()
                val ro = item.lon.reversedArray()
                val rev = mapLine(graph, rl, ro, maxDist, allowBidirectional = false)
                if (rev.matched > res.matched) res = rev
            }
            if (res.matched < max(1, res.samples / 4)) continue
            for ((edge, range) in res.ranges) spans.getOrPut(edge) { ArrayList() }.add(EdgeSpan(range[0], range[1], item))
        }
        // 経路上の「問い合わせ済み」エッジ（coverageAll の API で、情報が無い＝順調とみなす範囲）
        val covered = HashSet<Int>()
        if (data.coverageAll && data.coveredLat.size >= 2) {
            val res = mapLine(graph, data.coveredLat, data.coveredLon, 25.0, allowBidirectional = true)
            covered.addAll(res.ranges.keys)
        }
        return EdgeTraffic(data, graph.generation, spans, covered)
    }

    private class LineMatch(val samples: Int, val matched: Int, val ranges: Map<Int, DoubleArray>)

    private fun mapLine(graph: RoadGraph, lat: DoubleArray, lon: DoubleArray, maxDist: Double, allowBidirectional: Boolean): LineMatch {
        val ranges = LinkedHashMap<Int, DoubleArray>()
        var samples = 0
        var matched = 0
        var prevEdge = -1
        for (i in 0 until lat.size - 1) {
            val segLen = Geo.distance(lat[i], lon[i], lat[i + 1], lon[i + 1])
            if (segLen < 0.5) continue
            val brg = Geo.bearing(lat[i], lon[i], lat[i + 1], lon[i + 1])
            val n = max(1, ceil(segLen / STEP).toInt())
            for (k in 0 until n) {
                val f = (k + 0.5) / n
                val pla = lat[i] + (lat[i + 1] - lat[i]) * f
                val plo = lon[i] + (lon[i + 1] - lon[i]) * f
                samples++
                val cand = graph.nearby(pla, plo, maxDist) { e -> allowBidirectional || !e.attr.bidirectional }
                val best = cand.filter { abs(Geo.diffDeg(graph.edges[it.edge].bearingAt(it.offset), brg)) < 40.0 }.minByOrNull { it.dist }
                if (best == null) {
                    prevEdge = -1
                    continue
                }
                matched++
                val half = segLen / n / 2
                val r = ranges.getOrPut(best.edge) { doubleArrayOf(Double.MAX_VALUE, -Double.MAX_VALUE) }
                r[0] = min(r[0], max(0.0, best.offset - half))
                r[1] = max(r[1], min(graph.edges[best.edge].length, best.offset + half))
                // 隣り合うエッジへ移ったら境界まで埋める
                if (prevEdge >= 0 && prevEdge != best.edge) {
                    val pe = graph.edges[prevEdge]
                    if (pe.to == graph.edges[best.edge].from) {
                        ranges[prevEdge]?.let { it[1] = pe.length }
                        r[0] = 0.0
                    }
                }
                prevEdge = best.edge
            }
        }
        return LineMatch(samples, matched, ranges)
    }

    /**
     * 前方経路に沿った交通情報（ETA 用の 50m セル）。
     * ratio[k] = NaN は「情報なし」。
     */
    class RouteTraffic(
        val provider: String,
        val fetchedWallMs: Long,
        val cell: Double,
        val ratio: DoubleArray,
        val events: List<RouteEvent>,
        val totalDelaySec: Double?,
        /** 渋滞区間の実際の速さ（取得元が速さを出す場合）の逆数 [s/m]。NaN = なし */
        val absPace: DoubleArray = DoubleArray(ratio.size) { Double.NaN },
        /** 自由流からの遅れ [s/m]（取得元の遅れ時間をそのまま保つ）。NaN = 情報なし、0 = 順調 */
        val extraPace: DoubleArray = DoubleArray(ratio.size) { Double.NaN }
    ) {
        val hasCoverage: Boolean = ratio.any { !it.isNaN() }

        fun ageSec(nowWallMs: Long): Double = (nowWallMs - fetchedWallMs) / 1000.0
    }

    /** 経路上の出来事（渋滞・事故・通行止めなど）。距離はルート始点から */
    class RouteEvent(val start: Double, val end: Double, val kind: TrafficKind, val description: String?, val delaySec: Double?, val ratio: Double?)

    /**
     * 1 件の情報から、渋滞の「実際の速さの逆数」と「自由流からの遅れ」[s/m] を求める（無ければ NaN）。
     *  - 渋滞・混雑（速度比 0.6 未満）: 渋滞の中は誰でも同じように進むので、実際の速さを使う。
     *    速さがわかる取得元（HERE）はその速さ、遅れ時間がわかる取得元（TomTom）は「自由流 80km/h ＋ 遅れ」から、
     *    速度比だけ（Google）は 80km/h × 速度比。
     *  - 流れが少し悪い程度: 自由流からの遅れ（遅れ時間 ÷ 長さ、または速度比から）を、自分の自由流の速さに足す
     *    （運転のくせによる速さの違いを残す）。
     */
    fun paces(item: TrafficItem): Pair<Double, Double> {
        val r = item.ratio ?: return Double.NaN to Double.NaN
        if (r >= 0.98) return Double.NaN to 0.0
        val d = item.delaySec
        val len = item.length
        val hasDelay = d != null && d > 0 && len > 50
        if (r < 0.6) {
            val sp = item.speedKmh?.let { it / 3.6 }
            val v = when {
                sp != null && sp > 0.5 -> sp
                hasDelay -> len / (len / REF_FREE_MS + d!!)
                else -> r.coerceAtLeast(0.02) * REF_FREE_MS
            }.coerceAtLeast(0.3)
            return (1.0 / v) to max(0.0, 1.0 / v - 1.0 / REF_FREE_MS)
        }
        return Double.NaN to (if (hasDelay) d!! / len else (1.0 / r - 1.0) / REF_FREE_MS)
    }

    /** 取得元の遅れ・速度比の基準にする自由流の速さ（80km/h） */
    private const val REF_FREE_MS = 22.2

    fun forRoute(route: RouteAhead, graph: RoadGraph, et: EdgeTraffic, cell: Double): RouteTraffic {
        val n = max(1, ceil(route.length / cell).toInt())
        val ratio = DoubleArray(n) { Double.NaN }
        val absPace = DoubleArray(n) { Double.NaN }
        val extraPace = DoubleArray(n) { Double.NaN }
        val rawEvents = ArrayList<RouteEvent>()
        for (seg in route.segments) {
            val covered = seg.edge in et.coveredEdges
            if (covered) {
                val k0 = (seg.startDist / cell).toInt().coerceIn(0, n - 1)
                val k1 = (seg.endDist / cell).toInt().coerceIn(0, n - 1)
                for (k in k0..k1) {
                    if (ratio[k].isNaN()) ratio[k] = 1.0
                    if (extraPace[k].isNaN()) extraPace[k] = 0.0
                }
            }
            val list = et.spans[seg.edge] ?: continue
            for (sp in list) {
                val a = max(sp.start, seg.startOffset)
                val b = min(sp.end, seg.endOffset)
                if (b <= a) continue
                val d0 = seg.startDist + (a - seg.startOffset)
                val d1 = seg.startDist + (b - seg.startOffset)
                val r = sp.item.ratio
                if (r != null) {
                    val k0 = (d0 / cell).toInt().coerceIn(0, n - 1)
                    val k1 = ((d1 - 1e-6) / cell).toInt().coerceIn(0, n - 1)
                    val (pa, pe) = paces(sp.item)
                    for (k in k0..k1) {
                        // 同じセルに複数の情報があれば遅い方
                        ratio[k] = if (ratio[k].isNaN() || covered && ratio[k] == 1.0) r else min(ratio[k], r)
                        if (!pa.isNaN()) absPace[k] = if (absPace[k].isNaN()) pa else max(absPace[k], pa)
                        if (!pe.isNaN()) extraPace[k] = if (extraPace[k].isNaN()) pe else max(extraPace[k], pe)
                    }
                }
                if (sp.item.kind != TrafficKind.FLOW) rawEvents.add(RouteEvent(d0, d1, sp.item.kind, sp.item.description, sp.item.delaySec, r))
            }
        }
        // 同じ種類で隣接・重なる出来事はまとめる
        val events = ArrayList<RouteEvent>()
        for (e in rawEvents.sortedBy { it.start }) {
            val last = events.lastOrNull()
            if (last != null && last.kind == e.kind && e.start <= last.end + 150.0) {
                events[events.size - 1] = RouteEvent(last.start, max(last.end, e.end), last.kind, last.description ?: e.description,
                    if (last.delaySec != null || e.delaySec != null) max(last.delaySec ?: 0.0, e.delaySec ?: 0.0) else null,
                    if (last.ratio != null && e.ratio != null) min(last.ratio, e.ratio) else last.ratio ?: e.ratio)
            } else events.add(e)
        }
        return RouteTraffic(et.data.provider, et.data.fetchedWallMs, cell, ratio, events, et.data.totalDelaySec, absPace, extraPace)
    }
}

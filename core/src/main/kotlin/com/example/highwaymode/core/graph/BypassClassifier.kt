package com.example.highwaymode.core.graph

import com.example.highwaymode.core.geo.Geo
import com.example.highwaymode.core.osm.OsmDataSet
import com.example.highwaymode.core.osm.OsmWay
import java.util.PriorityQueue

/**
 * 一般国道のうち「IC のある主要バイパス」（名豊道路・名阪国道・静清バイパス・加古川バイパスなど）の区間を選び出す。
 *
 * 取得データには一般の国道（highway=trunk）も含まれるが、ハイウェイモードの対象にするのは
 *  - 有料（toll=yes）・自動車専用道路（motorroad=yes）
 *  - IC（highway=motorway_junction）や本線料金所が並ぶ区間:
 *      同じ路線（ref / 名称）でつながるウェイをたどり、IC を含むウェイと、
 *      前後の IC の間隔が maxGap 以内に収まるウェイ、IC の前後 tail 以内のウェイ
 *  - それらにつながるランプ（trunk_link）
 * だけにする。交差点の多い一般の国道は対象外（地図にも載せない）。
 *
 * データ作成時に判定済みのウェイには `hwm:class=bypass` が付いており、そのまま採用する。
 */
object BypassClassifier {

    const val TAG = "hwm:class"
    const val TAG_BYPASS = "bypass"

    class Params(
        /** 前後の IC の間隔（これより離れた区間は IC の無い一般区間とみなす） */
        val maxGap: Double = 15_000.0,
        /** 最初・最後の IC の手前／先にも含める長さ */
        val tail: Double = 1_500.0,
        /** ランプをたどる最大長 */
        val linkReach: Double = 3_000.0
    )

    /** 常に採用する道路か（高速道路・有料道路・自動車専用道路・判定済みバイパス） */
    fun isCoreRoad(tags: Map<String, String>): Boolean {
        val hw = tags["highway"] ?: return false
        if (hw == "motorway" || hw == "motorway_link") return true
        if (tags["toll"] == "yes") return true
        if (tags["motorroad"] == "yes") return true
        return tags[TAG] == TAG_BYPASS
    }

    private fun isCandidateMain(tags: Map<String, String>): Boolean {
        val hw = tags["highway"]
        return hw == "trunk" || hw == "primary"
    }

    private fun isCandidateLink(tags: Map<String, String>): Boolean {
        val hw = tags["highway"]
        return hw == "trunk_link" || hw == "primary_link"
    }

    /** 採用するウェイの ID */
    fun select(data: OsmDataSet, params: Params = Params()): Set<Long> {
        val keep = HashSet<Long>()
        val mains = ArrayList<OsmWay>()
        val links = ArrayList<OsmWay>()
        for (w in data.roads.values) {
            if (w.nodeIds.size < 2) continue
            when {
                isCoreRoad(w.tags) -> keep.add(w.id)
                isCandidateMain(w.tags) -> mains.add(w)
                isCandidateLink(w.tags) -> links.add(w)
            }
        }
        if (mains.isEmpty() && links.isEmpty()) return keep

        // ---- 本線候補: 同じ路線どうしの接続（端点の共有）
        val n = mains.size
        val idx = HashMap<Long, Int>(n * 2)
        for (i in 0 until n) idx[mains[i].id] = i
        val len = DoubleArray(n) { lengthOf(mains[it]) }
        val oneway = BooleanArray(n) { onewayOf(mains[it].tags) }
        val keyRef = Array(n) { RoadAttr.fromTags(mains[it].tags, true, OnewayMode.BOTH) }
        // ノード → そのノードを端点に持つウェイ
        val byStart = HashMap<Long, MutableList<Int>>()
        val byEnd = HashMap<Long, MutableList<Int>>()
        for (i in 0 until n) {
            val w = mains[i]
            val first = w.nodeIds.first()
            val last = w.nodeIds.last()
            // 一方通行の逆向き（oneway=-1）は向きを入れ替えて扱う
            val rev = w.tags["oneway"] == "-1"
            byStart.getOrPut(if (rev) last else first) { ArrayList(2) }.add(i)
            byEnd.getOrPut(if (rev) first else last) { ArrayList(2) }.add(i)
        }
        fun startOf(i: Int): Long = if (mains[i].tags["oneway"] == "-1") mains[i].nodeIds.last() else mains[i].nodeIds.first()
        fun endOf(i: Int): Long = if (mains[i].tags["oneway"] == "-1") mains[i].nodeIds.first() else mains[i].nodeIds.last()
        fun sameRoad(a: Int, b: Int): Boolean {
            val ra = keyRef[a]
            val rb = keyRef[b]
            if (ra.normRefs.isNotEmpty() && rb.normRefs.isNotEmpty()) return ra.normRefs.any { it in rb.normRefs }
            if (ra.normName != null && rb.normName != null) return ra.normName == rb.normName
            return true
        }
        // 下流側の隣（i の終端から始まるウェイ。対面通行のウェイは両端どちらでもつながる）
        fun nextOf(i: Int): List<Int> {
            val out = ArrayList<Int>(2)
            val ends = if (oneway[i]) listOf(endOf(i)) else listOf(endOf(i), startOf(i))
            for (node in ends) {
                byStart[node]?.forEach { j -> if (j != i && sameRoad(i, j)) out.add(j) }
                byEnd[node]?.forEach { j -> if (j != i && !oneway[j] && sameRoad(i, j)) out.add(j) }
            }
            return out
        }
        fun prevOf(i: Int): List<Int> {
            val out = ArrayList<Int>(2)
            val starts = if (oneway[i]) listOf(startOf(i)) else listOf(startOf(i), endOf(i))
            for (node in starts) {
                byEnd[node]?.forEach { j -> if (j != i && sameRoad(i, j)) out.add(j) }
                byStart[node]?.forEach { j -> if (j != i && !oneway[j] && sameRoad(i, j)) out.add(j) }
            }
            return out
        }

        // ---- IC の手掛かり: 出口分岐ノード・料金所・高速道路とのランプ接続
        val coreNodes = HashSet<Long>()
        for (w in data.roads.values) if (w.id in keep) {
            val hw = w.tags["highway"]
            if (hw == "motorway" || hw == "motorway_link") for (id in w.nodeIds) coreNodes.add(id)
        }
        val evidence = BooleanArray(n)
        for (i in 0 until n) {
            val w = mains[i]
            for (id in w.nodeIds) {
                if (data.junctions.containsKey(id) || data.tollBooths.containsKey(id)) {
                    evidence[i] = true
                    break
                }
            }
        }
        // 反対車線: IC の分岐ノードが片側にしか登録されていないことが多いので、
        // 同じ路線で IC から 80m 以内を通る別のウェイ（対向車線）も手掛かりにする
        run {
            val cell = 0.001
            fun key(lat: Double, lon: Double): Long =
                (Math.floor(lat / cell).toLong() shl 32) or (Math.floor(lon / cell).toLong() and 0xffffffffL)
            val grid = HashMap<Long, MutableList<Int>>()
            for (i in 0 until n) {
                val w = mains[i]
                var k = 0
                while (k < w.lat.size) {
                    val list = grid.getOrPut(key(w.lat[k], w.lon[k])) { ArrayList(2) }
                    if (list.isEmpty() || list[list.size - 1] != i) list.add(i)
                    k++
                }
            }
            val seeds = (0 until n).filter { evidence[it] }
            for (i in seeds) {
                val w = mains[i]
                for (k in w.nodeIds.indices) {
                    val id = w.nodeIds[k]
                    if (!data.junctions.containsKey(id) && !data.tollBooths.containsKey(id)) continue
                    val la = w.lat[k]
                    val lo = w.lon[k]
                    for (dy in -1..1) for (dx in -1..1) {
                        val list = grid[key(la + dy * cell, lo + dx * cell)] ?: continue
                        for (j in list) {
                            if (evidence[j] || !sameRoad(i, j)) continue
                            val o = mains[j]
                            for (m in o.lat.indices) {
                                if (Geo.distance(la, lo, o.lat[m], o.lon[m]) < 80.0) {
                                    evidence[j] = true
                                    break
                                }
                            }
                        }
                    }
                }
            }
        }
        // 高速道路へ直接つながる（JCT 的な接続）ランプが分かれる本線も手掛かりにする
        if (coreNodes.isNotEmpty()) {
            val nodeToMain = HashMap<Long, MutableList<Int>>()
            for (i in 0 until n) for (id in mains[i].nodeIds) nodeToMain.getOrPut(id) { ArrayList(1) }.add(i)
            for (l in links) {
                val touchesCore = l.nodeIds.first() in coreNodes || l.nodeIds.last() in coreNodes
                if (!touchesCore) continue
                for (end in longArrayOf(l.nodeIds.first(), l.nodeIds.last())) nodeToMain[end]?.forEach { evidence[it] = true }
            }
        }
        if (evidence.none { it } && links.isEmpty()) return keep

        // ---- 下流の次の IC まで（dNext）・上流の前の IC まで（dPrev）の距離
        val dNext = shortest(n, evidence, len) { i -> prevOf(i) }  // 逆向きにたどって「下流に IC がある」距離
        val dPrev = shortest(n, evidence, len) { i -> nextOf(i) }
        for (i in 0 until n) {
            val ok = evidence[i] ||
                (dPrev[i] + len[i] + dNext[i] <= params.maxGap) ||
                dPrev[i] <= params.tail || dNext[i] <= params.tail
            if (ok) keep.add(mains[i].id)
        }

        // ---- ランプ: 採用した道路につながるものを、ランプ伝いに linkReach までたどって採用
        val keptNodes = HashSet<Long>()
        for (w in data.roads.values) if (w.id in keep) for (id in w.nodeIds) keptNodes.add(id)
        val linkAt = HashMap<Long, MutableList<OsmWay>>()
        for (l in links) {
            linkAt.getOrPut(l.nodeIds.first()) { ArrayList(2) }.add(l)
            linkAt.getOrPut(l.nodeIds.last()) { ArrayList(2) }.add(l)
        }
        val reach = HashMap<Long, Double>()
        val pq = PriorityQueue<Pair<Double, OsmWay>>(compareBy { it.first })
        for (l in links) {
            if (l.nodeIds.first() in keptNodes || l.nodeIds.last() in keptNodes) pq.add(lengthOf(l) to l)
        }
        while (pq.isNotEmpty()) {
            val (d, l) = pq.poll()
            val prev = reach[l.id]
            if (prev != null && prev <= d) continue
            reach[l.id] = d
            if (d > params.linkReach) continue
            for (end in longArrayOf(l.nodeIds.first(), l.nodeIds.last())) {
                linkAt[end]?.forEach { m -> if (m.id != l.id && (reach[m.id] ?: Double.MAX_VALUE) > d) pq.add(d + lengthOf(m) to m) }
            }
        }
        for ((id, d) in reach) if (d <= params.linkReach) keep.add(id)
        return keep
    }

    /** 採用するウェイだけのデータセット（判定済みのバイパスには hwm:class=bypass を付ける） */
    fun filter(data: OsmDataSet, params: Params = Params(), markBypass: Boolean = false): OsmDataSet {
        val keep = select(data, params)
        if (keep.size == data.roads.size && !markBypass) return data
        val roads = LinkedHashMap<Long, OsmWay>()
        for ((id, w) in data.roads) {
            if (id !in keep) continue
            roads[id] = if (markBypass && !isCoreRoad(w.tags)) OsmWay(w.id, w.nodeIds, w.lat, w.lon, w.tags + (TAG to TAG_BYPASS)) else w
        }
        return OsmDataSet(roads, data.junctions, data.tollBooths, data.restAreas, data.pois, data.cameras)
    }

    private fun onewayOf(tags: Map<String, String>): Boolean {
        val o = tags["oneway"]?.lowercase()
        return o == "yes" || o == "true" || o == "1" || o == "-1" || tags["junction"] == "roundabout"
    }

    private fun lengthOf(w: OsmWay): Double {
        var s = 0.0
        for (i in 0 until w.lat.size - 1) s += Geo.distance(w.lat[i], w.lon[i], w.lat[i + 1], w.lon[i + 1])
        return s
    }

    /** 手掛かりのあるウェイ（距離 0）から neighbors 方向へ広げた、各ウェイまでの距離（ウェイ長の和） */
    private fun shortest(n: Int, src: BooleanArray, len: DoubleArray, neighbors: (Int) -> List<Int>): DoubleArray {
        val dist = DoubleArray(n) { Double.POSITIVE_INFINITY }
        val pq = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        for (i in 0 until n) if (src[i]) {
            dist[i] = 0.0
            pq.add(0.0 to i)
        }
        while (pq.isNotEmpty()) {
            val (d, i) = pq.poll()
            if (d > dist[i]) continue
            // i の隣 j: j から i へ進むと i に着く → j から IC までの距離 = d + (i が IC でなければ i の長さ)
            val step = if (src[i]) 0.0 else len[i]
            for (j in neighbors(i)) {
                val nd = d + step
                if (nd < dist[j] && !src[j]) {
                    dist[j] = nd
                    pq.add(nd to j)
                }
            }
        }
        return dist
    }
}

package com.example.highwaymode.core.graph

import com.example.highwaymode.core.geo.Geo
import com.example.highwaymode.core.osm.OsmDataSet
import com.example.highwaymode.core.osm.OsmWay
import java.util.PriorityQueue
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * 一般国道のうち「IC のある主要バイパス」（名豊道路・静清バイパス・衣浦豊田道路など）の区間を選び出す。
 *
 * 取得データには一般の国道（highway=trunk）も含まれるが、ハイウェイモードの対象にするのは
 *  - 有料（toll=yes）・自動車専用道路（motorroad=yes）・高速道路（いつも採用）
 *  - 信号の無い、IC（highway=motorway_junction）が並ぶ区間（下の決まり）
 *  - それらにつながるランプ（trunk_link）
 * だけにする。交差点の多い一般の国道は対象外（地図にも載せない）。
 *
 * **v2.15 で作り直した**。それまでは「同じ路線で、前後の IC の間隔が 15km 以内なら、間は全部バイパス」
 * 「IC・高速道路へのランプの前後 1.5km はバイパス」としていたので、信号のある一般道でも、立体交差や高速道路の IC が
 * 近くにあれば対象になっていた（利用者の報告: 国道23号の名四国道（大高〜豊明）が、5km 先の知立バイパスの IC の
 * 延長として対象になった。実データでは、ほかに IC の無い国道155号・302号・1号・42号・419号・153号の一部も入っていた）。
 *
 * いまの決まり:
 *  1. **平面で交わる場所で区切る**: 道路の上の信号・横断歩道・踏切のノード（[OsmDataSet.barrierNodes]）と、
 *     国道どうしが平面で交わるノードは「区切り」。区切りをまたいで「IC の間」「IC の前後」を広げない。
 *     上下線が分かれた道路では、片方の車線にしか信号が登録されていないことがあるので、反対車線にも同じ場所に区切りを置く。
 *     ウェイの途中に区切りがあるときは、そこでウェイを分ける（[prepare]）。
 *  2. **IC の間・前後**（これまでと同じ考え方）: IC を含むウェイ、前後の IC の間隔が [Params.maxGap] 以内のウェイ、
 *     IC の前後 [Params.tail] 以内のウェイを候補にする。
 *  3. **ひと続きの区間として確かめる**: 候補が区切り無しでつながったひと続きの区間が、
 *     **出口の IC（motorway_junction）が [Params.minIcSites] か所以上**（[Params.icSiteGap] 以内は 1 か所と数える）で、
 *     **長さ [Params.minLength] 以上**のときだけ採用する。高速道路の IC へのランプがあるだけの国道・
 *     立体交差が 1 つあるだけの国道は対象にしない。
 *     ただし、自動車専用・有料の区間（いつも採用）どうしの間にある区間と、そこから区切り無しで続く IC のある区間は採用する
 *     （自動車専用の印が一部のウェイにしか付いていない道路が、途中で切れないように）。
 *     「間」は、道のりが両端の間の距離とほぼ同じときだけ（有料トンネルの両側をつなぐ峠の旧道は「間」ではない）。
 *
 * データ作成時に判定済みのウェイには `hwm:class=bypass` が付いており、そのまま採用する。
 */
object BypassClassifier {

    const val TAG = "hwm:class"
    const val TAG_BYPASS = "bypass"

    /** 自動車専用・有料の区間とのつながりを数えるとき、これより近いものは 1 か所と数える */
    private const val ATTACH_GAP = 300.0

    /** 区切りで分けたウェイの ID の印（本物のウェイの ID と重ならない） */
    private const val SPLIT_BASE = 1L shl 40
    private const val SPLIT_MAX_PARTS = 64

    /** 区切りで分けたウェイの ID（元のウェイの ID と、何番目か） */
    fun splitId(wayId: Long, part: Int): Long = SPLIT_BASE or (wayId shl 6) or part.toLong()

    /** 分けたウェイの ID から元のウェイの ID（分けていない ID はそのまま） */
    fun originalId(id: Long): Long = if (id >= SPLIT_BASE) (id and (SPLIT_BASE - 1)) ushr 6 else id

    class Params(
        /** 前後の IC の間隔（これより離れた区間は IC の無い一般区間とみなす。v2.14 までは 15km） */
        val maxGap: Double = 10_000.0,
        /** 最初・最後の IC の手前／先にも含める長さ（区切りがあればそこまで） */
        val tail: Double = 1_500.0,
        /** ランプをたどる最大長 */
        val linkReach: Double = 3_000.0,
        /** 採用するひと続きの区間の、最短の長さ */
        val minLength: Double = 3_000.0,
        /** 採用するひと続きの区間の、最短の広がり（端から端までの直線距離の目安） */
        val minExtent: Double = 2_000.0,
        /** 採用するひと続きの区間に必要な、出口の IC の数 */
        val minIcSites: Int = 2,
        /** IC を数えるとき、これより近いものは 1 か所と数える（1 つの IC・JCT の出口が複数あるため） */
        val icSiteGap: Double = 1_000.0,
        /** IC の手掛かりを反対車線にも写す距離 */
        val mirrorDist: Double = 80.0,
        /** 区切り（信号など）を反対車線にも写す距離 */
        val barrierMirrorDist: Double = 45.0,
        /**
         * 自動車専用・有料の区間どうしの「間」とみなす道のりの上限: つながる 2 点の間の直線距離 × [betweenDetour] ＋ [betweenSlack]。
         * これより遠回りの道（有料トンネルの旧道など）は「間」ではない
         */
        val betweenDetour: Double = 1.3,
        val betweenSlack: Double = 300.0
    )

    /** ひと続きの区間の判定結果（確認用の一覧に使う） */
    class Component(
        val wayCount: Int,
        val lengthM: Double,
        val extentM: Double,
        /** 出口の IC の数（近いものは 1 か所） */
        val icSites: Int,
        val ref: String?,
        val name: String?,
        val lat: Double,
        val lon: Double,
        val kept: Boolean,
        val reason: String
    )

    class Result(
        /** 採用するウェイの ID */
        val keep: Set<Long>,
        /** 候補になったひと続きの区間（採用・不採用とも） */
        val components: List<Component>,
        /** 候補の本線（判定していない国道）の数と長さ */
        val candidateWays: Int,
        val candidateLengthM: Double,
        /** 候補の本線の上にあった区切り（信号・横断歩道・踏切・国道どうしの交差）の数 */
        val barrierCount: Int
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

    // ================================================================== 候補の本線のつながり

    /** 候補の本線（判定していない国道）と、そのつながりを調べる道具 */
    private class Net(val mains: List<OsmWay>) {
        val n = mains.size
        val len = DoubleArray(n) { lengthOf(mains[it]) }
        val oneway = BooleanArray(n) { onewayOf(mains[it].tags) }
        val keyRef = Array(n) { RoadAttr.fromTags(mains[it].tags, true, OnewayMode.BOTH) }
        /** 高さ（橋 = 1、トンネル = -1、地上 = 0）。地上の一般道と、その上の高架のバイパスを取り違えないため */
        val level = IntArray(n) { levelOf(mains[it].tags) }
        // ノード → そのノードを端点に持つウェイ
        val byStart = HashMap<Long, MutableList<Int>>()
        val byEnd = HashMap<Long, MutableList<Int>>()

        init {
            for (i in 0 until n) {
                byStart.getOrPut(startOf(i)) { ArrayList(2) }.add(i)
                byEnd.getOrPut(endOf(i)) { ArrayList(2) }.add(i)
            }
        }

        // 一方通行の逆向き（oneway=-1）は向きを入れ替えて扱う
        fun startOf(i: Int): Long = if (mains[i].tags["oneway"] == "-1") mains[i].nodeIds.last() else mains[i].nodeIds.first()
        fun endOf(i: Int): Long = if (mains[i].tags["oneway"] == "-1") mains[i].nodeIds.first() else mains[i].nodeIds.last()

        fun sameRoad(a: Int, b: Int): Boolean = sameRoute(keyRef[a], keyRef[b])

        /**
         * 国道どうしが平面で交わるノード（別の路線の本線が、同じノードを通り抜けている）。
         * 立体交差ならノードを共有しない。合流・分岐（片方がそこで終わる）は数えない
         */
        fun crossingNodes(): Set<Long> {
            // 腕の数: 途中を通るウェイは 2、端のウェイは 1
            val arms = LongIntMap(n * 4)
            for (w in mains) {
                val ids = w.nodeIds
                for (k in ids.indices) arms.add(ids[k], if (k == 0 || k == ids.size - 1) 1 else 2)
            }
            val at = HashMap<Long, MutableList<Int>>()
            for (i in 0 until n) for (id in mains[i].nodeIds) {
                if (arms.get(id) >= 4) at.getOrPut(id) { ArrayList(4) }.let { if (it.isEmpty() || it[it.size - 1] != i) it.add(i) }
            }
            val out = HashSet<Long>()
            for ((id, list) in at) {
                var differ = false
                loop@ for (a in list.indices) for (b in a + 1 until list.size) {
                    if (!sameRoad(list[a], list[b])) {
                        differ = true
                        break@loop
                    }
                }
                if (differ) out.add(id)
            }
            return out
        }
    }

    /** 同じ路線か（路線番号が両方にあれば番号、無ければ名前で比べる。どちらも分からなければ同じとみなす） */
    private fun sameRoute(ra: RoadAttr, rb: RoadAttr): Boolean {
        if (ra.normRefs.isNotEmpty() && rb.normRefs.isNotEmpty()) return ra.normRefs.any { it in rb.normRefs }
        if (ra.normName != null && rb.normName != null) return ra.normName == rb.normName
        return true
    }

    /** 近くのウェイを探すための格子（ウェイの折れ線を約 50m ごとに置く） */
    private class WayGrid(private val net: Net) {
        private val cell = 0.001
        private val grid = HashMap<Long, MutableList<Int>>()

        private fun key(lat: Double, lon: Double): Long =
            (floor(lat / cell).toLong() shl 32) or (floor(lon / cell).toLong() and 0xffffffffL)

        init {
            for (i in 0 until net.n) {
                val w = net.mains[i]
                for (k in w.lat.indices) {
                    put(w.lat[k], w.lon[k], i)
                    if (k > 0) {
                        val d = Geo.distance(w.lat[k - 1], w.lon[k - 1], w.lat[k], w.lon[k])
                        val steps = (d / 50.0).toInt()
                        for (s in 1..steps) {
                            val f = s.toDouble() / (steps + 1)
                            put(w.lat[k - 1] + (w.lat[k] - w.lat[k - 1]) * f, w.lon[k - 1] + (w.lon[k] - w.lon[k - 1]) * f, i)
                        }
                    }
                }
            }
        }

        private fun put(lat: Double, lon: Double, i: Int) {
            val list = grid.getOrPut(key(lat, lon)) { ArrayList(2) }
            if (list.isEmpty() || list[list.size - 1] != i) list.add(i)
        }

        /** (lat, lon) の近く（約 300m 以内の升目）を通るウェイ */
        fun near(lat: Double, lon: Double): Set<Int> {
            val out = LinkedHashSet<Int>()
            for (dy in -2..2) for (dx in -2..2) grid[key(lat + dy * cell, lon + dx * cell)]?.let { out.addAll(it) }
            return out
        }
    }

    /** 点からウェイの折れ線までの距離 [m] と、いちばん近いノードの番号 */
    private fun nearestOn(w: OsmWay, lat: Double, lon: Double, out: IntArray): Double {
        val mLat = Geo.metersPerDegLat(lat)
        val mLon = Geo.metersPerDegLon(lat)
        var best = Double.MAX_VALUE
        var bestNodeD = Double.MAX_VALUE
        var bestNode = 0
        for (k in w.lat.indices) {
            val x = (w.lon[k] - lon) * mLon
            val y = (w.lat[k] - lat) * mLat
            val dn = sqrt(x * x + y * y)
            if (dn < bestNodeD) {
                bestNodeD = dn
                bestNode = k
            }
            if (dn < best) best = dn
            if (k > 0) {
                val ax = (w.lon[k - 1] - lon) * mLon
                val ay = (w.lat[k - 1] - lat) * mLat
                val vx = x - ax
                val vy = y - ay
                val l2 = vx * vx + vy * vy
                if (l2 > 1e-6) {
                    val t = (-(ax * vx + ay * vy) / l2).coerceIn(0.0, 1.0)
                    val qx = ax + t * vx
                    val qy = ay + t * vy
                    val d = sqrt(qx * qx + qy * qy)
                    if (d < best) best = d
                }
            }
        }
        out[0] = bestNode
        return best
    }

    // ================================================================== 区切りでウェイを分ける

    /**
     * 判定の前の準備: 候補の本線（判定していない国道）の上の区切り（信号・横断歩道・踏切・国道どうしの平面交差）を
     * 反対車線にも写し、ウェイの途中に区切りがあるときはそこでウェイを分ける。
     * 区切りが無ければ、渡されたデータをそのまま返す。
     */
    fun prepare(data: OsmDataSet, params: Params = Params()): OsmDataSet {
        val mains = ArrayList<OsmWay>()
        for (w in data.roads.values) {
            if (w.nodeIds.size >= 2 && !isCoreRoad(w.tags) && isCandidateMain(w.tags)) mains.add(w)
        }
        if (mains.isEmpty()) return data
        val net = Net(mains)
        val cross = net.crossingNodes()
        if (data.barrierNodes.isEmpty() && cross.isEmpty()) return data

        // ---- 候補の本線の上にある区切り
        val barrier = HashSet<Long>(cross)
        /** (ウェイ, ノードの番号) の区切り: 反対車線へ写すために場所を覚える */
        val found = ArrayList<IntArray>()
        for (i in 0 until net.n) {
            val ids = mains[i].nodeIds
            for (k in ids.indices) {
                if (ids[k] in data.barrierNodes || ids[k] in cross) {
                    barrier.add(ids[k])
                    found.add(intArrayOf(i, k))
                }
            }
        }
        if (barrier.isEmpty()) return data

        // ---- 反対車線にも同じ場所に区切りを置く（上下線が分かれた道路で、片方にしか信号が登録されていないとき）
        val grid = WayGrid(net)
        val tmp = IntArray(1)
        for (f in found) {
            val i = f[0]
            if (!net.oneway[i]) continue
            val w = mains[i]
            val la = w.lat[f[1]]
            val lo = w.lon[f[1]]
            for (j in grid.near(la, lo)) {
                if (j == i || !net.oneway[j] || net.level[j] != net.level[i] || !net.sameRoad(i, j)) continue
                val o = mains[j]
                // 同じ車線の続き（区切りのノードを共有している）は写さなくてよい
                if (o.nodeIds.first() == w.nodeIds[f[1]] || o.nodeIds.last() == w.nodeIds[f[1]]) continue
                val d = nearestOn(o, la, lo, tmp)
                if (d > params.barrierMirrorDist) continue
                val m = tmp[0]
                // いちばん近いノードが遠すぎる（長い直線の途中）ときは写さない
                if (Geo.distance(la, lo, o.lat[m], o.lon[m]) > 300.0) continue
                barrier.add(o.nodeIds[m])
            }
        }

        // ---- ウェイの途中に区切りがあるものを分ける
        val roads = LinkedHashMap<Long, OsmWay>(data.roads.size * 2)
        val candidateIds = HashSet<Long>(mains.size * 2)
        for (w in mains) candidateIds.add(w.id)
        for ((id, w) in data.roads) {
            if (id !in candidateIds) {
                roads[id] = w
                continue
            }
            val ids = w.nodeIds
            val cuts = ArrayList<Int>()
            for (k in 1 until ids.size - 1) {
                if (ids[k] in barrier && cuts.size < SPLIT_MAX_PARTS - 1) cuts.add(k)
            }
            if (cuts.isEmpty()) {
                roads[id] = w
                continue
            }
            var from = 0
            var part = 0
            for (c in cuts + (ids.size - 1)) {
                val nn = c - from + 1
                if (nn >= 2) {
                    val pid = splitId(w.id, part++)
                    roads[pid] = OsmWay(pid, ids.copyOfRange(from, c + 1), w.lat.copyOfRange(from, c + 1), w.lon.copyOfRange(from, c + 1), w.tags)
                }
                from = c
            }
        }
        return OsmDataSet(roads, data.junctions, data.tollBooths, data.restAreas, data.pois, data.cameras, barrier)
    }

    // ================================================================== 判定

    /** 採用するウェイの ID（[prepare] 済みのデータを渡すと、区切りのところで正しく分かれる） */
    fun select(data: OsmDataSet, params: Params = Params()): Set<Long> = classify(data, params).keep

    /** 判定する（採用するウェイと、確認用のひと続きの区間の一覧） */
    fun classify(data: OsmDataSet, params: Params = Params()): Result {
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
        if (mains.isEmpty() && links.isEmpty()) return Result(keep, emptyList(), 0, 0.0, 0)

        val net = Net(mains)
        val n = net.n
        val len = net.len
        val oneway = net.oneway

        // ---- 区切り: 信号・横断歩道・踏切（渡されたもの）と、国道どうしの平面交差
        val barrier = HashSet<Long>()
        if (n > 0) barrier.addAll(net.crossingNodes())
        var barrierCount = 0
        /** 途中に区切りがあるウェイ（[prepare] で分けていないとき）は採用しない・通り抜けない */
        val blocked = BooleanArray(n)
        for (i in 0 until n) {
            val ids = mains[i].nodeIds
            for (k in ids.indices) {
                val b = ids[k] in data.barrierNodes || ids[k] in barrier
                if (!b) continue
                barrier.add(ids[k])
                barrierCount++
                if (k > 0 && k < ids.size - 1) blocked[i] = true
            }
        }
        fun open(node: Long): Boolean = node !in barrier

        // 下流側の隣（i の終端から始まるウェイ。対面通行のウェイは両端どちらでもつながる）。区切りはまたがない
        fun nextOf(i: Int): List<Int> {
            if (blocked[i]) return emptyList()
            val out = ArrayList<Int>(2)
            val ends = if (oneway[i]) listOf(net.endOf(i)) else listOf(net.endOf(i), net.startOf(i))
            for (node in ends) {
                if (!open(node)) continue
                net.byStart[node]?.forEach { j -> if (j != i && !blocked[j] && net.sameRoad(i, j)) out.add(j) }
                net.byEnd[node]?.forEach { j -> if (j != i && !blocked[j] && !oneway[j] && net.sameRoad(i, j)) out.add(j) }
            }
            return out
        }
        fun prevOf(i: Int): List<Int> {
            if (blocked[i]) return emptyList()
            val out = ArrayList<Int>(2)
            val starts = if (oneway[i]) listOf(net.startOf(i)) else listOf(net.startOf(i), net.endOf(i))
            for (node in starts) {
                if (!open(node)) continue
                net.byEnd[node]?.forEach { j -> if (j != i && !blocked[j] && net.sameRoad(i, j)) out.add(j) }
                net.byStart[node]?.forEach { j -> if (j != i && !blocked[j] && !oneway[j] && net.sameRoad(i, j)) out.add(j) }
            }
            return out
        }

        // ---- IC の手掛かり: 出口分岐ノード・料金所（出口の IC）、高速道路とのランプ接続（つながり）
        val coreNodes = HashSet<Long>()
        for (w in data.roads.values) if (w.id in keep) {
            val hw = w.tags["highway"]
            if (hw == "motorway" || hw == "motorway_link") for (id in w.nodeIds) coreNodes.add(id)
        }
        val evidence = BooleanArray(n)
        /** 出口の IC の場所（ウェイごと。緯度・経度の並び）。ひと続きの区間の IC の数を数えるのに使う */
        val exitPts = arrayOfNulls<ArrayList<Double>>(n)
        fun addExit(i: Int, lat: Double, lon: Double) {
            evidence[i] = true
            val l = exitPts[i] ?: ArrayList<Double>(4).also { exitPts[i] = it }
            l.add(lat)
            l.add(lon)
        }
        for (i in 0 until n) {
            if (blocked[i]) continue
            val w = mains[i]
            for (k in w.nodeIds.indices) {
                val id = w.nodeIds[k]
                if (data.junctions.containsKey(id) || data.tollBooths.containsKey(id)) addExit(i, w.lat[k], w.lon[k])
            }
        }
        // 反対車線: IC の分岐ノードが片側にしか登録されていないことが多いので、
        // 同じ路線・同じ高さで IC から mirrorDist 以内を通る別のウェイ（対向車線）も手掛かりにする
        if (n > 0) {
            val grid = WayGrid(net)
            val tmp = IntArray(1)
            val seeds = (0 until n).filter { exitPts[it] != null }
            for (i in seeds) {
                val pts = ArrayList(exitPts[i]!!)
                var p = 0
                while (p < pts.size) {
                    val la = pts[p]
                    val lo = pts[p + 1]
                    p += 2
                    for (j in grid.near(la, lo)) {
                        if (j == i || blocked[j] || net.level[j] != net.level[i] || !net.sameRoad(i, j)) continue
                        if (nearestOn(mains[j], la, lo, tmp) < params.mirrorDist) {
                            val had = exitPts[j]
                            // 同じ IC を二重に数えないよう、まだ無いときだけ足す
                            if (had == null || !hasNear(had, la, lo, 30.0)) addExit(j, la, lo)
                        }
                    }
                }
            }
        }
        // 高速道路へ直接つながる（JCT 的な接続）ランプが分かれる本線も手掛かりにする（IC の数には数えない）
        val nodeToMain = HashMap<Long, MutableList<Int>>()
        for (i in 0 until n) for (id in mains[i].nodeIds) nodeToMain.getOrPut(id) { ArrayList(1) }.let { if (it.isEmpty() || it[it.size - 1] != i) it.add(i) }
        if (coreNodes.isNotEmpty()) {
            for (l in links) {
                val touchesCore = l.nodeIds.first() in coreNodes || l.nodeIds.last() in coreNodes
                if (!touchesCore) continue
                for (end in longArrayOf(l.nodeIds.first(), l.nodeIds.last())) nodeToMain[end]?.forEach { if (!blocked[it]) evidence[it] = true }
            }
        }
        // 同じ路線の「いつも採用する本線」（自動車専用・有料の国道など）と、区切り無しでつながる場所。
        // 自動車専用の印が一部のウェイにしか付いていない道路で、間のウェイが抜けないようにする（手掛かりにし、つながりとして覚える）
        val attachPts = arrayOfNulls<ArrayList<Double>>(n)
        for (cw in data.roads.values) {
            if (cw.id !in keep) continue
            val hw = cw.tags["highway"]
            if (hw != "trunk" && hw != "primary" && hw != "secondary") continue
            var attr: RoadAttr? = null
            for (k in cw.nodeIds.indices) {
                val id = cw.nodeIds[k]
                val list = nodeToMain[id] ?: continue
                if (id in barrier) continue
                val a = attr ?: RoadAttr.fromTags(cw.tags, true, OnewayMode.BOTH).also { attr = it }
                for (i in list) {
                    if (blocked[i] || !sameRoute(net.keyRef[i], a)) continue
                    evidence[i] = true
                    val l = attachPts[i] ?: ArrayList<Double>(2).also { attachPts[i] = it }
                    l.add(cw.lat[k])
                    l.add(cw.lon[k])
                }
            }
        }

        // ---- 下流の次の IC まで（dNext）・上流の前の IC まで（dPrev）の距離（区切りはまたがない）
        val dNext = shortest(n, evidence, len) { i -> prevOf(i) }  // 逆向きにたどって「下流に IC がある」距離
        val dPrev = shortest(n, evidence, len) { i -> nextOf(i) }
        val prov = BooleanArray(n)
        for (i in 0 until n) {
            if (blocked[i]) continue
            prov[i] = evidence[i] ||
                (dPrev[i] + len[i] + dNext[i] <= params.maxGap) ||
                dPrev[i] <= params.tail || dNext[i] <= params.tail
        }

        // ---- ひと続きの区間（区切り無しでつながった候補）ごとに、IC の数と長さを確かめる
        val parent = IntArray(n) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != r) {
                val nx = parent[c]
                parent[c] = r
                c = nx
            }
            return r
        }
        // 区切りでないノードを共有する、同じ路線の候補どうしをまとめる（端どうしだけでなく、
        // ウェイの途中につながる短いウェイ（IC のまわりの分かれ道など）も同じ区間に入れる）
        for ((node, list) in nodeToMain) {
            if (list.size < 2 || node in barrier) continue
            for (a in list.indices) {
                val i = list[a]
                if (!prov[i]) continue
                for (b in a + 1 until list.size) {
                    val j = list[b]
                    if (!prov[j] || !net.sameRoad(i, j)) continue
                    val ra = find(i)
                    val rb = find(j)
                    if (ra != rb) parent[ra] = rb
                }
            }
        }
        val members = HashMap<Int, MutableList<Int>>()
        for (i in 0 until n) if (prov[i]) members.getOrPut(find(i)) { ArrayList() }.add(i)
        val components = ArrayList<Component>(members.size)
        for (list in members.values) {
            var total = 0.0
            var minLa = Double.MAX_VALUE
            var maxLa = -Double.MAX_VALUE
            var minLo = Double.MAX_VALUE
            var maxLo = -Double.MAX_VALUE
            val sites = ArrayList<Double>()
            val attach = ArrayList<Double>()
            val refs = HashMap<String, Double>()
            val names = HashMap<String, Double>()
            /** 道のりの目安（上下線が分かれた道路は、片側ぶんだけ数える） */
            var routeLen = 0.0
            for (i in list) {
                total += len[i]
                routeLen += if (oneway[i]) len[i] * 0.5 else len[i]
                val w = mains[i]
                for (k in w.lat.indices) {
                    if (w.lat[k] < minLa) minLa = w.lat[k]
                    if (w.lat[k] > maxLa) maxLa = w.lat[k]
                    if (w.lon[k] < minLo) minLo = w.lon[k]
                    if (w.lon[k] > maxLo) maxLo = w.lon[k]
                }
                exitPts[i]?.let { pts ->
                    var p = 0
                    while (p < pts.size) {
                        if (!hasNear(sites, pts[p], pts[p + 1], params.icSiteGap)) {
                            sites.add(pts[p])
                            sites.add(pts[p + 1])
                        }
                        p += 2
                    }
                }
                attachPts[i]?.let { pts ->
                    var p = 0
                    while (p < pts.size) {
                        if (!hasNear(attach, pts[p], pts[p + 1], ATTACH_GAP)) {
                            attach.add(pts[p])
                            attach.add(pts[p + 1])
                        }
                        p += 2
                    }
                }
                w.tags["ref"]?.let { refs[it] = (refs[it] ?: 0.0) + len[i] }
                (w.tags["name"] ?: w.tags["name:ja"])?.let { names[it] = (names[it] ?: 0.0) + len[i] }
            }
            val extent = Geo.distance(minLa, minLo, maxLa, maxLo)
            val ic = sites.size / 2
            val joins = attach.size / 2
            // 自動車専用・有料の区間とつながる場所どうしの、いちばん離れた 2 点の距離
            var attachSpan = 0.0
            var p1 = 0
            while (p1 < attach.size) {
                var p2 = p1 + 2
                while (p2 < attach.size) {
                    val dd = Geo.distance(attach[p1], attach[p1 + 1], attach[p2], attach[p2 + 1])
                    if (dd > attachSpan) attachSpan = dd
                    p2 += 2
                }
                p1 += 2
            }
            val reason = when {
                // 自動車専用・有料の区間どうしをつなぐ（印の付いていないウェイが間にあるだけ）。
                // 道のりが、つながる 2 点の間の距離とほぼ同じときだけ（有料トンネルの両側をつなぐ峠の旧道のような、
                // 遠回りの道は「間」ではない。全国のデータで、国道385号・263号の峠道が入っていた）
                joins >= 2 && routeLen <= attachSpan * params.betweenDetour + params.betweenSlack -> "採用（自動車専用・有料の区間の間）"
                // 自動車専用・有料の区間から、区切り無しで続く IC のある区間
                joins >= 1 && ic >= 1 -> "採用（自動車専用・有料の区間の続き）"
                ic < params.minIcSites -> "出口の IC が $ic か所"
                total < params.minLength || extent < params.minExtent -> "短い"
                else -> "採用"
            }
            val kept = reason.startsWith("採用")
            if (kept) for (i in list) keep.add(mains[i].id)
            components.add(
                Component(
                    list.size, total, extent, ic,
                    refs.maxByOrNull { it.value }?.key, names.maxByOrNull { it.value }?.key,
                    (minLa + maxLa) / 2, (minLo + maxLo) / 2, kept, reason
                )
            )
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
        return Result(keep, components, n, len.sum(), barrierCount)
    }

    /** 採用するウェイだけのデータセット（判定済みのバイパスには hwm:class=bypass を付ける） */
    fun filter(data: OsmDataSet, params: Params = Params(), markBypass: Boolean = false): OsmDataSet =
        filterWithResult(data, params, markBypass).first

    /** [filter] と、確認用の判定結果 */
    fun filterWithResult(data: OsmDataSet, params: Params = Params(), markBypass: Boolean = false): Pair<OsmDataSet, Result> {
        val prepared = prepare(data, params)
        val result = classify(prepared, params)
        val keep = result.keep
        if (prepared === data && keep.size == data.roads.size && !markBypass) return data to result
        val roads = LinkedHashMap<Long, OsmWay>()
        for ((id, w) in prepared.roads) {
            if (id !in keep) continue
            roads[id] = if (markBypass && !isCoreRoad(w.tags)) OsmWay(w.id, w.nodeIds, w.lat, w.lon, w.tags + (TAG to TAG_BYPASS)) else w
        }
        // 区切りは判定にだけ使う（判定済みのデータには持たせない）
        return OsmDataSet(roads, data.junctions, data.tollBooths, data.restAreas, data.pois, data.cameras) to result
    }

    /**
     * 確認用の一覧（データ作成の記録に出す）。採用した区間・不採用にした区間を、長い順に並べる。
     * @param maxLines 採用・不採用それぞれの最大の行数
     * @param area 詳しく見る範囲（南・西・北・東の緯度経度）。この中の区間は、短いものも西から順にすべて出す
     */
    fun report(r: Result, maxLines: Int = 400, area: DoubleArray? = null): List<String> {
        val out = ArrayList<String>()
        val kept = r.components.filter { it.kept }.sortedByDescending { it.lengthM }
        val dropped = r.components.filter { !it.kept }.sortedByDescending { it.lengthM }
        out.add(
            "主要バイパスの判定: 候補の国道 ${r.candidateWays} 本・%.0f km、区切り（信号・横断歩道・踏切・平面交差）${r.barrierCount} か所".format(r.candidateLengthM / 1000)
        )
        out.add("  採用 ${kept.size} 区間・%.0f km ／ 不採用 ${dropped.size} 区間・%.0f km（長さは上下線を別々に数えた合計）".format(kept.sumOf { it.lengthM } / 1000, dropped.sumOf { it.lengthM } / 1000))
        fun line(c: Component): String =
            "  %6.1f km  IC %2d  国道%-8s %-18s  (%.4f, %.4f)  %s".format(c.lengthM / 1000, c.icSites, c.ref ?: "?", c.name ?: "-", c.lat, c.lon, c.reason)
        out.add("---- 採用した区間（長い順）")
        kept.take(maxLines).forEach { out.add(line(it)) }
        if (kept.size > maxLines) out.add("  …ほか ${kept.size - maxLines} 区間")
        out.add("---- 不採用にした区間（長い順）")
        dropped.take(maxLines).forEach { out.add(line(it)) }
        if (dropped.size > maxLines) out.add("  …ほか ${dropped.size - maxLines} 区間・%.0f km".format(dropped.drop(maxLines).sumOf { it.lengthM } / 1000))
        if (area != null && area.size == 4) {
            val inside = r.components.filter { it.lat >= area[0] && it.lat <= area[2] && it.lon >= area[1] && it.lon <= area[3] }.sortedBy { it.lon }
            out.add("---- 範囲 (%.2f, %.2f)〜(%.2f, %.2f) の区間（西から。採用 ${inside.count { it.kept }}・不採用 ${inside.count { !it.kept }}）".format(area[0], area[1], area[2], area[3]))
            inside.forEach { out.add((if (it.kept) "  ○" else "  ×") + line(it)) }
        }
        return out
    }

    /**
     * 確認用のまとめ（データ作成の記録に出す）。同じ名前の区間をまとめ、1 行に数件ずつ詰める（記録を短くするため）。
     *
     * 件の書き方:
     *  - 採用: `名前〔国道の番号〕合計km/区間の数 IC最小-最大 @緯度,経度`（後ろの「間」「続」は、自動車専用・有料の区間の間／続き）
     *  - ぎりぎりの採用（IC が 2 か所だけ、または 5km 未満）: 区間ごとに `名前〔番号〕km IC数 @緯度,経度`
     *  - 不採用（いちばん長い区間が 2km 以上か、IC が 2 か所以上のもの）: `名前〔番号〕最長km(合計km/数) IC最大 理由 @緯度,経度`
     *
     * @param area 詳しく見る範囲（南・西・北・東）。この中の区間は、短いものも西から順にすべて出す
     * @param taggedBarriers 地図データにあった信号・横断歩道・踏切のノードの数（分からなければ負）
     */
    fun summary(r: Result, maxKept: Int = 500, maxDropped: Int = 150, area: DoubleArray? = null, taggedBarriers: Int = -1): List<String> {
        val out = ArrayList<String>()
        val kept = r.components.filter { it.kept }
        val dropped = r.components.filter { !it.kept }
        out.add(
            "主要バイパスの判定: 候補の国道 ${r.candidateWays} 本・%.0f km、区切り（信号・横断歩道・踏切・平面交差）${r.barrierCount} か所".format(r.candidateLengthM / 1000) +
                (if (taggedBarriers >= 0) "（地図データの信号・横断歩道・踏切のノード $taggedBarriers）" else "")
        )
        out.add("  採用 ${kept.size} 区間・%.0f km ／ 不採用 ${dropped.size} 区間・%.0f km（長さは上下線を別々に数えた合計）".format(kept.sumOf { it.lengthM } / 1000, dropped.sumOf { it.lengthM } / 1000))
        fun count(prefix: String) = kept.count { it.reason == prefix }
        out.add("  採用の内訳: IC が並ぶ区間 ${count("採用")}、自動車専用・有料の区間の間 ${kept.count { it.reason.contains("の間") }}、続き ${kept.count { it.reason.contains("の続き") }}")

        fun label(c: Component): String = (c.name ?: "-") + "〔" + (c.ref ?: "?") + "〕"
        fun pack(items: List<String>) {
            val sb = StringBuilder()
            for (it in items) {
                if (sb.isNotEmpty() && sb.length + it.length > 210) {
                    out.add(sb.toString())
                    sb.setLength(0)
                }
                if (sb.isNotEmpty()) sb.append(" ｜ ")
                sb.append(it)
            }
            if (sb.isNotEmpty()) out.add(sb.toString())
        }

        // ---- 採用（名前ごと）
        val keptGroups = kept.groupBy { label(it) }.entries.sortedByDescending { e -> e.value.sumOf { it.lengthM } }
        out.add("---- 採用（名前ごと ${keptGroups.size} 件。長い順）")
        pack(keptGroups.take(maxKept).map { (name, list) ->
            val big = list.maxByOrNull { it.lengthM }!!
            val lo = list.minOf { it.icSites }
            val hi = list.maxOf { it.icSites }
            val flags = (if (list.any { it.reason.contains("の間") }) "間" else "") + (if (list.any { it.reason.contains("の続き") }) "続" else "")
            "%s%.1f/%d IC%s @%.2f,%.2f%s".format(name, list.sumOf { it.lengthM } / 1000, list.size, if (lo == hi) "$lo" else "$lo-$hi", big.lat, big.lon, if (flags.isEmpty()) "" else " $flags")
        })
        if (keptGroups.size > maxKept) out.add("  …ほか ${keptGroups.size - maxKept} 件")

        // ---- ぎりぎりの採用（見直すときに、まずここを見る）
        val marginal = kept.filter { it.reason == "採用" && (it.icSites <= 2 || it.lengthM < 5_000.0) }.sortedBy { it.lengthM }
        out.add("---- 採用のうち、IC が 2 か所だけ・または 5km 未満の区間（${marginal.size} 区間。短い順）")
        pack(marginal.take(maxDropped * 2).map { "%s%.1f IC%d @%.3f,%.3f".format(label(it), it.lengthM / 1000, it.icSites, it.lat, it.lon) })
        val attached = kept.filter { it.reason != "採用" }.sortedByDescending { it.lengthM }
        out.add("---- 採用のうち、自動車専用・有料の区間の間・続き（${attached.size} 区間。長い順）")
        pack(attached.take(maxDropped).map { "%s%.1f IC%d%s @%.3f,%.3f".format(label(it), it.lengthM / 1000, it.icSites, if (it.reason.contains("の間")) "間" else "続", it.lat, it.lon) })

        // ---- 不採用（長いもの・IC が 2 か所以上あるもの）
        val droppedGroups = dropped.groupBy { label(it) }.entries
            .filter { e -> e.value.any { it.lengthM >= 2_000.0 || it.icSites >= 2 } }
            .sortedByDescending { e -> e.value.maxOf { it.lengthM } }
        out.add("---- 不採用（名前ごと。いちばん長い区間が 2km 以上か、IC が 2 か所以上のもの ${droppedGroups.size} 件。長い順）")
        pack(droppedGroups.take(maxDropped).map { (name, list) ->
            val big = list.maxByOrNull { it.lengthM }!!
            "%s%.1f(%.1f/%d) IC%d %s @%.2f,%.2f".format(name, big.lengthM / 1000, list.sumOf { it.lengthM } / 1000, list.size, list.maxOf { it.icSites }, if (big.reason == "短い") "短" else "IC不足", big.lat, big.lon)
        })
        if (droppedGroups.size > maxDropped) out.add("  …ほか ${droppedGroups.size - maxDropped} 件")

        if (area != null && area.size == 4) {
            val inside = r.components.filter { it.lat >= area[0] && it.lat <= area[2] && it.lon >= area[1] && it.lon <= area[3] }.sortedBy { it.lon }
            out.add("---- 範囲 (%.2f, %.2f)〜(%.2f, %.2f) の区間（西から。採用 ${inside.count { it.kept }}・不採用 ${inside.count { !it.kept }}）".format(area[0], area[1], area[2], area[3]))
            pack(inside.map { "%s%s%.1f IC%d @%.3f,%.3f".format(if (it.kept) "○" else "×", label(it), it.lengthM / 1000, it.icSites, it.lat, it.lon) })
        }
        return out
    }

    /** pts（緯度・経度の並び）の中に、(lat, lon) から dist 以内の点があるか */
    private fun hasNear(pts: List<Double>, lat: Double, lon: Double, dist: Double): Boolean {
        val mLat = Geo.metersPerDegLat(lat)
        val mLon = mLat * cos(Math.toRadians(lat))
        var p = 0
        while (p < pts.size) {
            val dy = (pts[p] - lat) * mLat
            val dx = (pts[p + 1] - lon) * mLon
            if (dx * dx + dy * dy <= dist * dist) return true
            p += 2
        }
        return false
    }

    private fun levelOf(tags: Map<String, String>): Int {
        val b = tags["bridge"]
        if (b != null && b != "no") return 1
        val t = tags["tunnel"]
        if (t != null && t != "no") return -1
        return 0
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

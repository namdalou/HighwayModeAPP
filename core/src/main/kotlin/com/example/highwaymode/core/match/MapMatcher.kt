package com.example.highwaymode.core.match

import com.example.highwaymode.core.geo.Geo
import com.example.highwaymode.core.graph.Edge
import com.example.highwaymode.core.graph.EdgeProjection
import com.example.highwaymode.core.graph.RoadContinuity
import com.example.highwaymode.core.graph.RoadGraph
import com.example.highwaymode.core.model.GpsFix
import com.example.highwaymode.core.model.HeadingObs
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

enum class MatchStatus { NO_DATA, OFF_ROAD, ON_ROAD }

class MatchResult(
    val status: MatchStatus,
    val edge: Int,
    val offset: Double,
    val lat: Double,
    val lon: Double,
    /** 最尤候補（同一車線の候補を合算）の事後確率 */
    val confidence: Double,
    /** GPS 位置から道路までの距離 [m] */
    val distToRoad: Double,
    val deadReckoning: Boolean,
    /** 同じ車線（ウェイ・方向）に連続してマッチした回数 */
    val stableCount: Int,
    val graphGeneration: Long,
    val timeNanos: Long,
    /** 推測航法で蓄積した前後方向の位置不確かさ [m] */
    val alongSigma: Double = 0.0
) {
    val onRoad: Boolean get() = status == MatchStatus.ON_ROAD && edge >= 0

    companion object {
        fun none(status: MatchStatus, gen: Long, t: Long) =
            MatchResult(status, -1, 0.0, Double.NaN, Double.NaN, 0.0, Double.NaN, false, 0, gen, t)
    }
}

class MatchParams(
    val minSearchRadius: Double = 30.0,
    val maxSearchRadius: Double = 90.0,
    /** 車線位置・地図の誤差 [m] */
    val roadSigma: Double = 3.5,
    /** 位置の外れ値（マルチパス）に対する下限尤度 */
    val posOutlierFloor: Double = 2e-3,
    /** 道路形状（ポリライン）方位の誤差 [deg] */
    val roadHeadingSigmaDeg: Double = 4.0,
    val kappaMax: Double = 120.0,
    val kappaMaxDr: Double = 60.0,
    /** 推測航法中に方位照合で探す前後位置の窓の上限 [m] */
    val drWindowMax: Double = 250.0,
    val maxHeadingPenalty: Double = 25.0,
    val minSpeedForHeading: Double = 1.5,
    val impliedReversePenalty: Double = -1.0,
    val betaBase: Double = 2.0,
    val betaPosFactor: Double = 0.8,
    val jumpPenalty: Double = -9.0,
    val pEnterLink: Double = 0.03,
    val pEnterMain: Double = 0.004,
    val pExitEnd: Double = 0.2,
    val pExitMain: Double = 0.003,
    val offSigmas: Double = 2.5,
    val offHeadingDeg: Double = 35.0,
    val maxCandidates: Int = 14,
    val pruneLog: Double = 22.0,
    val resetGapSec: Double = 45.0
)

/**
 * 隠れマルコフモデル（HMM）による逐次マップマッチング。
 *
 *  状態   : 有向エッジ上の位置（＝どの車線をどちら向きに走っているか）＋「高速道路外」
 *  観測尤度: GPS 位置と道路の距離（ガウス＋外れ値床）、融合方位と道路方位の差（von Mises）
 *  遷移   : 道路上の経路距離と「ドップラー速度×経過時間」の差（指数分布）
 *
 * 上下線が 20〜30m しか離れていない区間でも、方位項（逆向きは最大 -25 の対数尤度）と
 * 経路連続性で正しい進行方向の車線に乗る。GPS が途切れたトンネル内では、
 * 推定車速で候補を道路に沿って進め（分岐では路線継続性の事前確率で枝分かれ）、
 * ジャイロ方位との一致度で分岐先を判定する。
 */
class MapMatcher(graph: RoadGraph, val params: MatchParams = MatchParams()) {

    private class Cand(
        val edge: Int,
        val offset: Double,
        var logP: Double,
        val alongSigma: Double,
        val dist: Double,
        /** 推測航法で直前に通ったエッジ（方位照合の窓を遡るため） */
        val prevEdge: Int = -1
    )

    var graph: RoadGraph = graph
        private set
    private var cands = ArrayList<Cand>()
    private var logOff = 0.0
    private var initialized = false
    private var lastT = Long.MIN_VALUE
    private var lastSpeed = 0.0
    private var lastBestKey = Long.MIN_VALUE
    private var stable = 0

    /** true の間は「一般道走行中」として高速道路へのマッチを止める */
    var suppressed: Boolean = false
        set(value) {
            field = value
            if (value) {
                cands.clear()
                initialized = false
                result = MatchResult.none(MatchStatus.OFF_ROAD, graph.generation, lastT)
            }
        }

    var result: MatchResult = MatchResult.none(MatchStatus.NO_DATA, graph.generation, 0L)
        private set

    val candidateCount: Int get() = cands.size

    /** デバッグ用: 候補の (エッジ, offset, 確率) */
    fun candidates(): List<Triple<Int, Double, Double>> = cands.map { Triple(it.edge, it.offset, exp(it.logP)) }

    /** デバッグ用: 「高速道路外」の確率 */
    val offProbability: Double get() = exp(logOff)

    fun reset() {
        cands.clear()
        initialized = false
        stable = 0
        lastBestKey = Long.MIN_VALUE
        result = MatchResult.none(if (graph.isEmpty) MatchStatus.NO_DATA else MatchStatus.OFF_ROAD, graph.generation, lastT)
    }

    /** 道路グラフ差し替え（タイル追加時）。候補を新グラフへ写像する */
    fun setGraph(g: RoadGraph) {
        if (g === graph) return
        val old = graph
        graph = g
        if (cands.isEmpty() || g.isEmpty) {
            if (g.isEmpty) reset()
            return
        }
        val pt = DoubleArray(2)
        val mapped = ArrayList<Cand>()
        for (c in cands) {
            val oe = old.edges[c.edge]
            oe.pointAt(c.offset, pt)
            val b = oe.bearingAt(c.offset)
            val same = g.nearby(pt[0], pt[1], 10.0) { it.wayId == oe.wayId && it.forwardOnWay == oe.forwardOnWay }.firstOrNull()
            val p = same ?: g.nearby(pt[0], pt[1], 6.0).firstOrNull {
                abs(Geo.diffDeg(g.edges[it.edge].bearingAt(it.offset), b)) < 30.0
            }
            if (p != null) mapped.add(Cand(p.edge, p.offset, c.logP, c.alongSigma, c.dist))
        }
        cands = mergeDuplicates(mapped)
        if (cands.isEmpty()) {
            initialized = false
            result = MatchResult.none(MatchStatus.OFF_ROAD, g.generation, lastT)
        } else {
            buildResult(result.deadReckoning, lastT, result.distToRoad)
        }
    }

    /**
     * GPS 測位で更新する。
     * @param speed フィルタ済み車速 [m/s]
     * @param heading 融合方位（無ければ null）
     */
    fun onFix(fix: GpsFix, speed: Double, heading: HeadingObs?): MatchResult {
        val t = fix.timeNanos
        if (graph.isEmpty) {
            lastT = t
            result = MatchResult.none(MatchStatus.NO_DATA, graph.generation, t)
            return result
        }
        if (suppressed) {
            lastT = t
            result = MatchResult.none(MatchStatus.OFF_ROAD, graph.generation, t)
            return result
        }
        val dt = if (lastT == Long.MIN_VALUE) 0.0 else (t - lastT) * 1e-9
        // 推測航法で候補を「現在」まで進めた後に、それより少し前の時刻の測位が届くことがある（受信遅延）。
        // 3 秒以内なら負の経過時間として後戻りを許して処理する。
        if (dt < -3.0) return result
        if (dt > params.resetGapSec) {
            cands.clear()
            initialized = false
        }
        val acc = max(fix.accuracyM, 3.0)
        val sigmaPos = sqrt(acc * acc + params.roadSigma * params.roadSigma)
        val radius = (3 * sigmaPos + 10).coerceIn(params.minSearchRadius, params.maxSearchRadius)
        val near = graph.nearby(fix.lat, fix.lon, radius)
        val useHeading = heading != null && speed >= params.minSpeedForHeading
        val kappa = if (useHeading) kappaFor(heading!!.sigmaDeg, params.kappaMax) else 0.0

        val emis = DoubleArray(near.size)
        for (k in near.indices) {
            val p = near[k]
            emis[k] = emission(graph.edges[p.edge], p.offset, p.dist, sigmaPos, if (useHeading) heading else null, kappa)
        }
        var eOff = -0.5 * params.offSigmas * params.offSigmas
        if (useHeading) eOff += max(kappa * (cos(params.offHeadingDeg * Geo.DEG) - 1.0), -params.maxHeadingPenalty)

        val newC = ArrayList<Cand>(near.size)
        val newOff: Double
        if (!initialized) {
            for (k in near.indices) {
                val p = near[k]
                newC.add(Cand(p.edge, p.offset, ln(0.5) + emis[k], 0.0, p.dist))
            }
            newOff = ln(0.5) + eOff
            initialized = true
        } else {
            val expected = 0.5 * (lastSpeed + speed) * dt
            val byEdge = HashMap<Int, MutableList<Int>>()
            for (k in near.indices) byEdge.getOrPut(near[k].edge) { ArrayList(1) }.add(k)
            val best = DoubleArray(near.size) { Double.NEGATIVE_INFINITY }
            val pt = DoubleArray(2)
            for (c in cands) {
                val beta = params.betaBase + params.betaPosFactor * sigmaPos + c.alongSigma
                val maxDist = max(0.0, expected) + 4 * beta + 30.0
                val rd = routeDistances(c.edge, c.offset, near, byEdge, maxDist, 5.0 + sigmaPos + max(0.0, -expected))
                graph.edges[c.edge].pointAt(c.offset, pt)
                for (k in near.indices) {
                    val r = rd[k]
                    val lt = if (!r.isNaN()) {
                        -abs(r - expected) / beta
                    } else {
                        // 経路が見つからない（データ不連続・上下線の乗り換え）→ 大きなペナルティ付きの跳躍
                        val gc = Geo.distance(pt[0], pt[1], near[k].lat, near[k].lon)
                        params.jumpPenalty - abs(gc - expected) / beta
                    }
                    val v = c.logP + lt
                    if (v > best[k]) best[k] = v
                }
            }
            for (k in near.indices) {
                val p = near[k]
                val fromOff = logOff + ln(enterProb(graph.edges[p.edge], p.offset))
                val prior = max(best[k], fromOff)
                newC.add(Cand(p.edge, p.offset, prior + emis[k], 0.0, p.dist))
            }
            var toOff = logOff + ln(1.0 - params.pEnterMain)
            for (c in cands) {
                val v = c.logP + ln(exitProb(c.edge, c.offset))
                if (v > toOff) toOff = v
            }
            newOff = toOff + eOff
        }
        lastT = t
        lastSpeed = speed
        commit(newC, newOff)
        val nearest = near.firstOrNull()?.dist ?: Double.NaN
        buildResult(false, t, nearest)
        return result
    }

    /**
     * GPS が無い間の推測航法。
     * @param dtSec 前回からの経過時間 [s]
     * @param speed 推定車速 [m/s]
     * @param distSigma 走行距離の不確かさ [m]
     */
    fun deadReckon(tNanos: Long, dtSec: Double, speed: Double, distSigma: Double, heading: HeadingObs?): MatchResult {
        if (graph.isEmpty || cands.isEmpty() || suppressed || !initialized) {
            lastT = tNanos
            return result
        }
        var onMassBefore = 0.0
        for (c in cands) onMassBefore += exp(c.logP)
        val out = ArrayList<Cand>()
        val dt = max(0.0, dtSec)
        for (c in cands) {
            // ランプ上の候補は現実的な速度で進める（本線の速度のまま進めると分岐先を通り越してしまう）
            val e = graph.edges[c.edge]
            val v = if (e.attr.isLink) minOf(speed, linkSpeedCap(e)) else speed
            advance(c.edge, c.offset, v * dt, c.logP, c.alongSigma + distSigma, 0, c.prevEdge, out)
        }
        if (heading != null && speed * dt > 0.3) {
            val kappa = kappaFor(heading.sigmaDeg, params.kappaMaxDr)
            for (c in out) {
                val e = graph.edges[c.edge]
                // 推測航法の前後位置誤差（alongSigma）を考慮し、窓内で最も方位が合う地点の尤度を使う。
                // （速度の過大評価で候補が分岐の先へ進みすぎても、正しい分岐が不当に罰せられない）
                val w = minOf(c.alongSigma, params.drWindowMax)
                var best = Double.NEGATIVE_INFINITY
                for (k in -2..2) {
                    val off = c.offset + w * k / 2.0
                    val brg = if (off < 0 && c.prevEdge >= 0) {
                        val pe = graph.edges[c.prevEdge]
                        pe.smoothBearingAt((pe.length + off).coerceIn(0.0, pe.length))
                    } else {
                        e.smoothBearingAt(off.coerceIn(0.0, e.length))
                    }
                    val diff = Geo.diffDeg(heading.headingDeg, brg) * Geo.DEG
                    best = max(best, kappa * (cos(diff) - 1.0))
                }
                c.logP += max(best, -params.maxHeadingPenalty)
            }
        }
        val merged = mergeDuplicates(out)
        // 道路上の総確率は保つ（推測航法中に「高速道路外」へ流れないように）
        var m = Double.NEGATIVE_INFINITY
        for (c in merged) m = max(m, c.logP)
        var s = 0.0
        for (c in merged) s += exp(c.logP - m)
        val shift = ln(max(onMassBefore, 1e-300)) - (m + ln(s))
        for (c in merged) c.logP += shift
        lastT = tNanos
        commit(merged, logOff)
        buildResult(true, tNanos, result.distToRoad)
        return result
    }

    private fun linkSpeedCap(e: Edge): Double {
        val tagged = e.attr.maxspeedKmh
        val kmh = if (tagged != null) tagged * 1.3 else 52.0
        return maxOf(kmh, 45.0) / 3.6
    }

    // ------------------------------------------------------------------ internals

    private fun kappaFor(sigmaDeg: Double, kMax: Double): Double {
        val s = sigmaDeg * Geo.DEG
        val r = params.roadHeadingSigmaDeg * Geo.DEG
        return (1.0 / (s * s + r * r)).coerceIn(1.0, kMax)
    }

    private fun emission(e: Edge, offset: Double, dist: Double, sigmaPos: Double, heading: HeadingObs?, kappa: Double): Double {
        val z = dist / sigmaPos
        var le = ln(exp(-0.5 * z * z) + params.posOutlierFloor)
        if (heading != null) {
            val diff = Geo.diffDeg(heading.headingDeg, e.smoothBearingAt(offset)) * Geo.DEG
            le += max(kappa * (cos(diff) - 1.0), -params.maxHeadingPenalty)
        }
        if (e.reverseOfImplied) le += params.impliedReversePenalty
        return le
    }

    private fun enterProb(e: Edge, offset: Double): Double {
        if (e.attr.isLink) return params.pEnterLink
        if (graph.inEdges(e.from).isEmpty() && offset < 500.0) return params.pEnterLink
        return params.pEnterMain
    }

    private fun exitProb(edgeId: Int, offset: Double): Double {
        val e = graph.edges[edgeId]
        val remaining = e.length - offset
        if (graph.outEdges(e.to).isEmpty() && remaining < 300.0) return params.pExitEnd
        if (e.attr.isLink && remaining < 150.0) return params.pExitEnd * 0.5
        return params.pExitMain
    }

    private class VD(val v: Int, val d: Double)

    /** (edge, offset) から各ターゲット候補までの道のり（到達不能は NaN） */
    private fun routeDistances(
        fromEdge: Int,
        fromOffset: Double,
        targets: List<EdgeProjection>,
        byEdge: Map<Int, List<Int>>,
        maxDist: Double,
        backTol: Double
    ): DoubleArray {
        val res = DoubleArray(targets.size) { Double.NaN }
        byEdge[fromEdge]?.forEach { k ->
            val d = targets[k].offset - fromOffset
            if (d >= -backTol) res[k] = d
        }
        val e0 = graph.edges[fromEdge]
        val startD = e0.length - fromOffset
        if (startD > maxDist) return res
        val bestD = HashMap<Int, Double>()
        val pq = PriorityQueue<VD>(16) { a, b -> a.d.compareTo(b.d) }
        bestD[e0.to] = startD
        pq.add(VD(e0.to, startD))
        var first = true
        while (pq.isNotEmpty()) {
            val cur = pq.poll()
            if (cur.d > (bestD[cur.v] ?: Double.MAX_VALUE)) continue
            for (eid in graph.outEdges(cur.v)) {
                if (first && eid == e0.twin) continue
                byEdge[eid]?.forEach { k ->
                    val r = cur.d + targets[k].offset
                    if (r <= maxDist && (res[k].isNaN() || r < res[k])) res[k] = r
                }
                val e = graph.edges[eid]
                val nd = cur.d + e.length
                if (nd <= maxDist && nd < (bestD[e.to] ?: Double.MAX_VALUE)) {
                    bestD[e.to] = nd
                    pq.add(VD(e.to, nd))
                }
            }
            first = false
        }
        return res
    }

    private fun advance(
        edge: Int, offset: Double, remaining: Double, logP: Double, sigma: Double,
        depth: Int, prevEdge: Int, out: MutableList<Cand>
    ) {
        val e = graph.edges[edge]
        if (offset + remaining <= e.length || depth > 5) {
            out.add(Cand(edge, minOf(offset + remaining, e.length), logP, sigma, 0.0, prevEdge))
            return
        }
        val rest = remaining - (e.length - offset)
        val succ = RoadContinuity.rankedSuccessors(graph, e)
        if (succ.isEmpty()) {
            // データ端（未読込タイル等）: 端で待機
            out.add(Cand(edge, e.length, logP, sigma, 0.0, prevEdge))
            return
        }
        val priors = RoadContinuity.branchLogPriors(succ.map { it.second })
        for (i in succ.indices) advance(succ[i].first, 0.0, rest, logP + priors[i], sigma, depth + 1, edge, out)
    }

    private fun mergeDuplicates(list: List<Cand>): ArrayList<Cand> {
        val sorted = list.sortedByDescending { it.logP }
        val out = ArrayList<Cand>(sorted.size)
        for (c in sorted) {
            if (out.none { it.edge == c.edge && abs(it.offset - c.offset) < 2.0 }) out.add(c)
        }
        return out
    }

    private fun commit(newC: List<Cand>, newOff: Double) {
        var m = newOff
        for (c in newC) if (c.logP > m) m = c.logP
        var sum = exp(newOff - m)
        for (c in newC) sum += exp(c.logP - m)
        val lse = m + ln(sum)
        logOff = newOff - lse
        for (c in newC) c.logP -= lse
        cands = ArrayList(
            mergeDuplicates(newC.filter { it.logP > -params.pruneLog && !it.logP.isNaN() })
                .take(params.maxCandidates)
        )
    }

    private fun buildResult(dr: Boolean, t: Long, distToRoad: Double) {
        val best = cands.firstOrNull()
        val pOff = exp(logOff)
        if (best == null || pOff > 0.5) {
            stable = 0
            lastBestKey = Long.MIN_VALUE
            result = MatchResult(
                MatchStatus.OFF_ROAD, -1, 0.0, Double.NaN, Double.NaN, pOff, distToRoad, dr, 0, graph.generation, t
            )
            return
        }
        val be = graph.edges[best.edge]
        val pt = DoubleArray(2)
        be.pointAt(best.offset, pt)
        val bb = be.bearingAt(best.offset)
        // 同じ車線の候補（隣接エッジ・同一ウェイ同方向）を合算した信頼度
        var conf = 0.0
        val q = DoubleArray(2)
        for (c in cands) {
            val e = graph.edges[c.edge]
            val same = (e.wayId == be.wayId && e.forwardOnWay == be.forwardOnWay) || run {
                e.pointAt(c.offset, q)
                Geo.distance(q[0], q[1], pt[0], pt[1]) < 12.0 && abs(Geo.diffDeg(e.bearingAt(c.offset), bb)) < 25.0
            }
            if (same) conf += exp(c.logP)
        }
        val key = (be.wayId shl 1) or (if (be.forwardOnWay) 1L else 0L)
        stable = if (key == lastBestKey) stable + 1 else 0
        lastBestKey = key
        result = MatchResult(
            MatchStatus.ON_ROAD, best.edge, best.offset, pt[0], pt[1],
            conf.coerceIn(0.0, 1.0), distToRoad, dr, stable, graph.generation, t, best.alongSigma
        )
    }
}

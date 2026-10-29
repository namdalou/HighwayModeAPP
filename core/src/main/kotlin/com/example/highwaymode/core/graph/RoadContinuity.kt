package com.example.highwaymode.core.graph

import com.example.highwaymode.core.geo.Geo
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * 分岐点で「同じ路線を走り続ける」先を選ぶための評価。
 * 路線番号・名称の一致、本線/ランプの別、曲がり角度で採点する。
 */
object RoadContinuity {

    fun score(cur: Edge, next: Edge): Double {
        val c = cur.attr
        val n = next.attr
        var s = 0.0
        if (c.normRefs.isNotEmpty() && n.normRefs.any { it in c.normRefs }) s += 4.0
        if (c.normName != null && c.normName == n.normName) s += 4.0
        if (!c.isLink && n.isLink) s -= 3.0
        if (c.isLink && !n.isLink) s += 1.0
        if (n.roadClass.rank == c.roadClass.rank) s += 1.0
        if (n.toll == c.toll) s += 0.5
        val turn = abs(Geo.diffDeg(next.startBearing, cur.endBearing))
        s -= turn / 20.0
        return s
    }

    /** 後続エッジ（U ターン除外済み）を評価順に返す */
    fun rankedSuccessors(graph: RoadGraph, cur: Edge): List<Pair<Int, Double>> {
        val outs = graph.outEdges(cur.to)
        val list = ArrayList<Pair<Int, Double>>(outs.size)
        for (o in outs) {
            if (o == cur.twin) continue
            val e = graph.edges[o]
            // 同一ウェイを逆走する U ターンも除外
            if (e.wayId == cur.wayId && e.forwardOnWay != cur.forwardOnWay) continue
            list.add(o to score(cur, e))
        }
        list.sortByDescending { it.second }
        return list
    }

    /** 分岐の事前確率（対数）。温度 2 のソフトマックス */
    fun branchLogPriors(scores: List<Double>, temperature: Double = 2.0): DoubleArray {
        if (scores.isEmpty()) return DoubleArray(0)
        val m = scores.max()
        var z = 0.0
        for (s in scores) z += exp((s - m) / temperature)
        val lz = ln(z)
        return DoubleArray(scores.size) { (scores[it] - m) / temperature - lz }
    }
}

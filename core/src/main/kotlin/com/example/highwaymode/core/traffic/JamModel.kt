package com.example.highwaymode.core.traffic

import com.example.highwaymode.core.geo.Geo
import com.example.highwaymode.core.graph.RoadGraph
import com.example.highwaymode.core.route.RouteAhead
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * 前方経路上の 1 つの渋滞（距離はルート始点からの道のり。取得時点の位置を、いまの経路に対応づけたもの）。
 * @param tailSpeed 渋滞の最後尾の動き [m/s]。＋ は前へ（渋滞が縮んでいる）、− は手前へ（伸びている）。不明なら NaN
 */
class JamSpan(
    val tail: Double,
    val head: Double,
    val tailSpeed: Double,
    val tailLat: Double,
    val tailLon: Double,
    val headLat: Double,
    val headLon: Double
) {
    val length: Double get() = head - tail

    /** 取得から ageSec 秒後の最後尾の推定位置（先頭は動かないものとする） */
    fun tailAfter(ageSec: Double): Double {
        if (tailSpeed.isNaN()) return tail
        val t = ageSec.coerceIn(0.0, JamModel.MAX_EXTRAPOLATION_SEC)
        return min(tail + tailSpeed * t, head).coerceAtLeast(tail - JamModel.maxGrowth(length))
    }

    /** 伸びている (+1) / 縮んでいる (-1) / 変わらない・不明 (0) */
    val trend: Int
        get() = when {
            tailSpeed.isNaN() -> 0
            tailSpeed < -JamModel.TREND_SPEED -> 1
            tailSpeed > JamModel.TREND_SPEED -> -1
            else -> 0
        }
}

/** 到着予測に渡す外部の交通情報（前方経路のセルごと） */
class ExtTraffic(
    /** 渋滞区間の実際の速さの逆数 [s/m]（NaN = なし） */
    val absPace: DoubleArray,
    /** 自由流からの遅れ [s/m]（NaN = 情報なし、0 = 順調） */
    val extraPace: DoubleArray,
    /** 渋滞（伸び縮みの推定つき） */
    val jams: List<JamSpan>,
    /** 情報の信頼度（取得からの経過で 1 → 0） */
    val weight: Double,
    /** 取得からの経過 [s] */
    val ageSec: Double
)

/**
 * 渋滞の「量・距離・動き」のモデル。
 *  - 取得結果の渋滞・混雑（隣り合うものはまとめる）を経路上の区間にする
 *  - 前回の取得結果と照合して、最後尾が手前に伸びている／前へ縮んでいる速さを求める（JamTracker）
 *  - 到着予測では、自分が最後尾に追いつく時刻の位置（伸び縮みを含む）から渋滞が始まるとみなす
 */
object JamModel {
    /** 伸び縮みを先へ延ばす時間の上限（ピークが過ぎるなど、長く続くとは限らないため） */
    const val MAX_EXTRAPOLATION_SEC = 1800.0

    /** 「伸びている／縮んでいる」と表示する最後尾の速さ [m/s]（約 2km/h） */
    const val TREND_SPEED = 0.55

    /** 最後尾が手前へ伸びる量の上限 [m]（いまの長さの 1.5 倍 + 2km） */
    fun maxGrowth(length: Double): Double = 1.5 * max(0.0, length) + 2000.0

    /** 経路上の渋滞・混雑の区間（300m 以内で続くものは 1 つにまとめる） */
    fun spans(rtf: TrafficMapper.RouteTraffic, route: RouteAhead, graph: RoadGraph, tracker: JamTracker?): List<JamSpan> {
        val raw = rtf.events.filter { it.kind == TrafficKind.JAM || it.kind == TrafficKind.SLOW }.sortedBy { it.start }
        val merged = ArrayList<DoubleArray>()
        for (e in raw) {
            val last = merged.lastOrNull()
            if (last != null && e.start <= last[1] + 300.0) last[1] = max(last[1], e.end)
            else merged.add(doubleArrayOf(e.start, e.end))
        }
        val p = DoubleArray(2)
        val q = DoubleArray(2)
        return merged.filter { it[1] - it[0] >= 100.0 }.map { (a, b) ->
            route.pointAt(graph, a, p)
            route.pointAt(graph, b, q)
            val vt = tracker?.tailSpeedFor(p[0], p[1], q[0], q[1]) ?: Double.NaN
            JamSpan(a, b, vt, p[0], p[1], q[0], q[1])
        }
    }

    /** 経路上の交通情報と渋滞の動きから、到着予測に渡す形を作る */
    fun build(rtf: TrafficMapper.RouteTraffic, route: RouteAhead, graph: RoadGraph, tracker: JamTracker?, weight: Double, nowWallMs: Long): ExtTraffic =
        ExtTraffic(rtf.absPace, rtf.extraPace, spans(rtf, route, graph, tracker), weight, max(0.0, rtf.ageSec(nowWallMs)))
}

/**
 * 取得のたびに渋滞を前回の取得結果と照合し、最後尾の動き（伸びている・縮んでいる）を求める。
 * 先頭（ボトルネック: トンネル・合流・料金所など）はあまり動かないので、先頭の位置が近いものを同じ渋滞とみなす。
 */
class JamTracker {

    class Observed(
        val tailLat: Double, val tailLon: Double,
        val headLat: Double, val headLon: Double,
        val length: Double,
        val wallMs: Long,
        /** 最後尾の動き [m/s]（NaN = 不明） */
        val tailSpeed: Double
    )

    var observed: List<Observed> = emptyList()
        private set

    fun clear() {
        observed = emptyList()
    }

    /**
     * 新しく取得した渋滞（いまの経路に対応づけたもの）を記録する。
     * 前回の渋滞の最後尾・先頭を同じ経路に投影して位置の変化を求める。
     */
    fun update(route: RouteAhead, graph: RoadGraph, spans: List<JamSpan>, wallMs: Long) {
        val prev = observed
        val out = ArrayList<Observed>()
        for (j in spans) {
            var vt = Double.NaN
            val match = prev.firstOrNull { o -> matches(route, graph, o, j) }
            if (match != null) {
                val dt = (wallMs - match.wallMs) / 1000.0
                val prevTail = route.distanceOf(graph, match.tailLat, match.tailLon)
                if (dt in 60.0..1500.0 && prevTail != null) {
                    // 位置の対応づけ・情報の区切りの細かさによる揺れ（数百 m）は動きとみなさない
                    // （DEADBAND 以下は 0、その 2 倍以上はそのまま、間はなめらかにつなぐ）
                    val d = j.tail - prevTail
                    val ad = abs(d)
                    val moved = when {
                        ad <= DEADBAND_M -> 0.0
                        ad >= 2 * DEADBAND_M -> d
                        else -> sign(d) * 2.0 * (ad - DEADBAND_M)
                    }
                    val v = (moved / dt).coerceIn(-MAX_GROW_MS, MAX_SHRINK_MS)
                    vt = if (match.tailSpeed.isNaN()) v else 0.5 * v + 0.5 * match.tailSpeed
                } else if (dt < 60.0) {
                    vt = match.tailSpeed
                }
            }
            out.add(Observed(j.tailLat, j.tailLon, j.headLat, j.headLon, j.length, wallMs, vt))
        }
        observed = out
    }

    private fun matches(route: RouteAhead, graph: RoadGraph, o: Observed, j: JamSpan): Boolean {
        val h = route.distanceOf(graph, o.headLat, o.headLon)
        if (h != null && abs(h - j.head) < 2000.0) return true
        val t = route.distanceOf(graph, o.tailLat, o.tailLon) ?: return false
        val hh = h ?: (t + o.length)
        return t < j.head && hh > j.tail
    }

    /** いまの経路上の渋滞（最後尾・先頭の位置）に当たる記録の、最後尾の動き */
    fun tailSpeedFor(tailLat: Double, tailLon: Double, headLat: Double, headLon: Double): Double {
        var best: Observed? = null
        var bestD = Double.MAX_VALUE
        for (o in observed) {
            val dh = Geo.distance(o.headLat, o.headLon, headLat, headLon)
            val dt = Geo.distance(o.tailLat, o.tailLon, tailLat, tailLon)
            val d = min(dh, dt)
            if (d < 600.0 && d < bestD) {
                bestD = d
                best = o
            }
        }
        return best?.tailSpeed ?: Double.NaN
    }

    companion object {
        /** 最後尾が手前へ伸びる速さの上限 [m/s]（約 22km/h） */
        const val MAX_GROW_MS = 6.0
        /** 最後尾が前へ縮む速さの上限 [m/s]（約 29km/h） */
        const val MAX_SHRINK_MS = 8.0
        const val DEADBAND_M = 250.0
    }
}

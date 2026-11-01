package com.example.highwaymode.core.eta

import com.example.highwaymode.core.facility.FacilityType
import com.example.highwaymode.core.geo.Geo
import com.example.highwaymode.core.geo.LocalFrame
import com.example.highwaymode.core.graph.RoadGraph
import com.example.highwaymode.core.route.RouteAhead
import com.example.highwaymode.core.traffic.ExtTraffic
import com.example.highwaymode.core.traffic.JamModel
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 前方経路の「静的な」速度プロファイル（50m セルごと）。経路を作り直すまで再利用する。
 *  - 最高速度（標識タグ or 道路種別の既定値）
 *  - カーブ半径から求めた走行可能速度 v = √(a_lat·R)
 *  - 本線料金所（ETC レーン 20km/h）
 */
class RouteProfile(
    val graphGeneration: Long,
    val routeLength: Double,
    val cell: Double,
    /** セルの制限速度 [m/s] */
    val vLimit: DoubleArray,
    val limitTagged: BooleanArray,
    /** カーブ・料金所による上限 [m/s] */
    val vCap: DoubleArray,
    val isLink: BooleanArray,
    val tunnel: BooleanArray
) {
    val cells: Int get() = vLimit.size
    fun cellStart(k: Int): Double = k * cell
    fun cellEnd(k: Int): Double = min((k + 1) * cell, routeLength)
}

/** ETA 計算結果。時刻は「今からの秒数」 */
class EtaResult(
    val startDist: Double,
    /** 各セル境界の道のり [m] */
    val bound: DoubleArray,
    /** 各セル境界までの累積時間 [s] */
    val cumTime: DoubleArray,
    /** 各セルの予測平均速度 [m/s] */
    val vPred: DoubleArray,
    /** 各セルの自由流速度 [m/s] */
    val vFree: DoubleArray
) {
    /** ルート始点からの道のり dist [m] に到達するまでの秒数 */
    fun timeTo(dist: Double): Double {
        val n = bound.size
        if (n == 0) return 0.0
        if (dist <= bound[0]) return 0.0
        if (dist >= bound[n - 1]) {
            val last = vPred.lastOrNull() ?: 25.0
            return cumTime[n - 1] + (dist - bound[n - 1]) / max(last, 1.0)
        }
        var lo = 0
        var hi = n - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (bound[mid] <= dist) lo = mid else hi = mid
        }
        val f = (dist - bound[lo]) / (bound[hi] - bound[lo]).coerceAtLeast(1e-9)
        return cumTime[lo] + f * (cumTime[hi] - cumTime[lo])
    }

    /** 区間 [d0, d1] の、自由流に比べた遅れ [s] */
    fun delayBetween(d0: Double, d1: Double): Double {
        if (d1 <= d0 || bound.size < 2) return 0.0
        var d = 0.0
        for (k in 0 until vPred.size) {
            val a = max(bound[k], d0)
            val b = min(bound[k + 1], d1)
            if (b <= a) continue
            d += (b - a) / max(vPred[k], 0.5) - (b - a) / max(vFree[k], 0.5)
        }
        return d
    }

    /** 区間 [d0, d1] の「自由流所要時間 / 予測所要時間」（1=順調, 小さいほど渋滞） */
    fun flowRatio(d0: Double, d1: Double): Double {
        if (d1 <= d0 || bound.size < 2) return 1.0
        var tPred = 0.0
        var tFree = 0.0
        for (k in 0 until vPred.size) {
            val a = max(bound[k], d0)
            val b = min(bound[k + 1], d1)
            if (b <= a) continue
            tPred += (b - a) / max(vPred[k], 0.5)
            tFree += (b - a) / max(vFree[k], 0.5)
        }
        return if (tPred > 1e-9) (tFree / tPred).coerceIn(0.0, 1.5) else 1.0
    }
}

/**
 * 到着予測。
 *
 * 1) 自由流速度 v_free(s) = min(制限速度 × 運転傾向比, カーブ速度, 料金所)
 * 2) 現在の流れの持続:
 *      v(s) = v_free(s) × [ b(s) + (c_now − c_x)·e^(−s/L1) ],  b(s) = 1 + (c_x − 1)·e^(−s/L_x)
 *    c_now = 現在車速 / v_free(現在地)（短期, L1 = 1.5km で解消）
 *    c_x   = 直近 2 分の「自由流所要時間 / 実所要時間」
 *            渋滞中（c_now<0.85 かつ c_x<0.9）: L_x = 1.5km + 0.5×既に渋滞を走った距離（〜12km）
 *            順調時: 渋滞サンプルを除いた直近 5 分の速度比（0.9〜1.15）, L_x = 15km（走り方の持続）
 * 3) 外部の交通情報（渋滞の速さ・遅れ時間）は「1m あたりの所要時間」で扱い、取得元の遅れ時間を保つ。
 *    渋滞の最後尾が伸びている／縮んでいる場合は、自分が追いつく時刻の最後尾の位置から渋滞が始まるとみなす。
 *    渋滞の中では、近く（〜2km）は自分の実際の流れ、その先は交通情報の流れと渋滞の先頭の位置を使う。
 * 4) 加速度制約（加速 0.8, 減速 0.9 m/s²）で前進・後退パスをかけた速度曲線を台形積分。
 */
class EtaPredictor(private val driver: DriverProfile, val params: Params = Params()) {

    class Params(
        val cell: Double = 50.0,
        val accel: Double = 0.8,
        val decel: Double = 0.9,
        val latAccMain: Double = 2.0,
        val latAccLink: Double = 2.6,
        val minCurveKmhMain: Double = 30.0,
        val minCurveKmhLink: Double = 20.0,
        val curveRadiusIgnore: Double = 1500.0,
        val tollBoothKmh: Double = 20.0,
        val tunnelFactor: Double = 0.98,
        val shortTermLength: Double = 1500.0,
        /** 渋滞の残り長さの推定 = min + growth × 既に渋滞を走った距離（2〜12km） */
        val congestionMinLength: Double = 1500.0,
        val congestionGrowth: Double = 0.5,
        val congestionMaxLength: Double = 12000.0,
        val congestionThreshold: Double = 0.9,
        /** 現在の速度比がこれ未満なら「渋滞中」 */
        val jamNowThreshold: Double = 0.85,
        /** 順調時に直近の走り方が持続する距離 */
        val mediumTermLength: Double = 15000.0,
        /** 渋滞の中で、自分の実際の流れ（直近 2 分）を交通情報より優先する距離 */
        val ownJamBlendLength: Double = 2000.0,
        /** 渋滞の中をこの距離走ったら、自分の流れを十分信頼する */
        val ownJamTrustDistance: Double = 1000.0,
        /** 渋滞中に「現在の速さ」を効かせる距離（停止・発進の繰り返しで揺れるため短く） */
        val shortTermJamLength: Double = 300.0,
        val maxKmh: Double = 135.0,
        val minSpeed: Double = 0.8
    )

    fun buildProfile(route: RouteAhead, graph: RoadGraph): RouteProfile {
        val cell = params.cell
        val n = max(1, ceil(route.length / cell).toInt())
        val vLimit = DoubleArray(n)
        val tagged = BooleanArray(n)
        val vCap = DoubleArray(n) { params.maxKmh / 3.6 }
        val isLink = BooleanArray(n)
        val tunnel = BooleanArray(n)

        for (k in 0 until n) {
            val mid = min((k + 0.5) * cell, route.length)
            val si = route.segmentIndexAt(mid)
            if (si < 0) {
                vLimit[k] = 80 / 3.6
                continue
            }
            val seg = route.segments[si]
            val e = graph.edges[seg.edge]
            val lim = SpeedLimitModel.limitFor(e.attr)
            vLimit[k] = lim.kmh / 3.6
            tagged[k] = lim.tagged
            isLink[k] = e.attr.isLink
            tunnel[k] = e.attr.tunnel
        }

        // ---- カーブ半径（10m 間隔に再サンプリングし、±30m の方位変化から曲率）
        if (route.segments.isNotEmpty()) {
            val step = 10.0
            val m = max(2, floor(route.length / step).toInt() + 1)
            val xs = DoubleArray(m)
            val ys = DoubleArray(m)
            val first = graph.edges[route.segments[0].edge]
            val p = DoubleArray(2)
            first.pointAt(route.segments[0].startOffset, p)
            val frame = LocalFrame(p[0], p[1])
            var si = 0
            for (i in 0 until m) {
                val d = min(i * step, route.length)
                while (si < route.segments.size - 1 && route.segments[si].endDist < d) si++
                val seg = route.segments[si]
                val e = graph.edges[seg.edge]
                e.pointAt(seg.startOffset + (d - seg.startDist), p)
                // 長距離では局所座標の歪みを避けるため、フレームを区間ごとに再設定せず差分だけ使う
                xs[i] = frame.x(p[1])
                ys[i] = frame.y(p[0])
            }
            val half = 3 // ±30m
            val heads = DoubleArray(m)
            for (i in 0 until m) {
                val a = max(0, i - 1)
                val b = min(m - 1, i + 1)
                heads[i] = atan2(xs[b] - xs[a], ys[b] - ys[a])
            }
            for (i in half until m - half) {
                val dTheta = abs(Geo.wrapPi(heads[i + half] - heads[i - half]))
                val ds = 2 * half * step
                if (dTheta < 1e-6) continue
                val radius = ds / dTheta
                if (radius >= params.curveRadiusIgnore) continue
                val k = min(n - 1, ((i * step) / cell).toInt())
                val link = isLink[k]
                val aLat = if (link) params.latAccLink else params.latAccMain
                val floorKmh = if (link) params.minCurveKmhLink else params.minCurveKmhMain
                val v = max(sqrt(aLat * radius), floorKmh / 3.6)
                if (v < vCap[k]) vCap[k] = v
            }
        }

        // ---- 本線料金所（ETC レーンの 20km/h 区間を前後約 60m とする）
        for (f in route.facilities) {
            if (f.facility.type != FacilityType.TB) continue
            val k0 = max(0, ((f.distance - 60.0) / cell).toInt())
            val k1 = min(n - 1, ((f.distance + 60.0) / cell).toInt())
            for (k in k0..k1) vCap[k] = min(vCap[k], params.tollBoothKmh / 3.6)
        }
        return RouteProfile(route.graphGeneration, route.length, cell, vLimit, tagged, vCap, isLink, tunnel)
    }

    /** セル k の自由流速度 [m/s] */
    fun freeSpeed(profile: RouteProfile, k: Int): Double {
        val lim = profile.vLimit[k]
        val ratio = driver.ratio(lim * 3.6)
        var v = lim * ratio
        if (profile.tunnel[k]) v *= params.tunnelFactor
        return min(min(v, profile.vCap[k]), params.maxKmh / 3.6)
    }

    /**
     * @param fromDist 現在地のルート始点からの道のり [m]
     * @param vNow 現在の車速 [m/s]
     * @param ext 外部の交通情報（プロファイルと同じセル。渋滞の伸び縮みの推定つき）。null = 情報なし
     */
    fun predict(
        profile: RouteProfile,
        fromDist: Double,
        vNow: Double,
        traffic: TrafficState,
        ext: ExtTraffic? = null
    ): EtaResult {
        val cell = profile.cell
        val k0 = min(profile.cells - 1, max(0, floor(fromDist / cell).toInt()))
        val n = profile.cells - k0
        val bound = DoubleArray(n + 1)
        bound[0] = fromDist
        for (i in 1..n) bound[i] = max(fromDist, profile.cellEnd(k0 + i - 1))
        val vFree = DoubleArray(n) { freeSpeed(profile, k0 + it) }
        val mid = DoubleArray(n) { 0.5 * (bound[it] + bound[it + 1]) }

        val vFree0 = max(vFree[0], 1.0)
        val cNow = (vNow / vFree0).coerceIn(0.0, 1.6)
        // ---- 自車の流れ（直近の走行から）
        var jamMode = false
        var cJam = 1.0      // 渋滞中の速度比（直近 2 分）
        var lJam = 0.0      // 交通情報が無いとき、渋滞が続くとみなす長さ
        if (traffic.valid) {
            val cRec = traffic.ratio
            // 渋滞を抜けて加速中（速度比 0.4 以上で 0.3m/s² 超の加速が続く）なら渋滞は解消とみなす
            val recovering = cNow > 0.4 && traffic.accelTrend > 0.3
            if (cNow < params.jamNowThreshold && cRec < params.congestionThreshold && !recovering) {
                jamMode = true
                cJam = cRec.coerceIn(0.03, 1.0)
                lJam = (params.congestionMinLength + params.congestionGrowth * traffic.congestedDistance)
                    .coerceIn(params.congestionMinLength, params.congestionMaxLength)
            }
        }
        // 順調に走れる区間の速さ（運転のくせ・その日の流れのずれを、先へ行くほど薄めて反映）
        val cFree = if (traffic.valid && traffic.freeValid) traffic.freeRatio.coerceIn(0.9, 1.15) else 1.0
        val vBase = DoubleArray(n) { i -> vFree[i] * (1.0 + (cFree - 1.0) * exp(-(mid[i] - fromDist) / params.mediumTermLength)) }

        // ---- 外部の交通情報（取得からの経過で薄める）と、渋滞の伸び縮み
        val w = (ext?.weight ?: 0.0).coerceIn(0.0, 1.0)
        val useExt = ext != null && w > 0.05
        val absP = DoubleArray(n) { Double.NaN }
        val extraP = DoubleArray(n) { Double.NaN }
        // 自分がいま中にいる（またはすぐ先から始まる）交通情報の渋滞の先頭
        var currentJamHead = Double.NaN
        if (ext != null && useExt) {
            for (i in 0 until n) {
                absP[i] = ext.absPace.getOrElse(k0 + i) { Double.NaN }
                extraP[i] = ext.extraPace.getOrElse(k0 + i) { Double.NaN }
            }
            currentJamHead = applyJamDynamics(ext, fromDist, n, bound, mid, vBase, absP, extraP)
        }

        // ---- セルごとの速さ（1m あたりの所要時間で考える）
        val pOwn = 1.0 / max(cJam * vFree0, params.minSpeed)
        // 自分の流れ（直近 2 分の平均）は、渋滞に入った直後は渋滞前の走りを含むので、渋滞の中を 1km 走るまでは控えめに
        val ownWeight = if (jamMode) (traffic.congestedDistance / params.ownJamTrustDistance).coerceIn(0.0, 1.0) else 0.0
        val inProviderJam = jamMode && !currentJamHead.isNaN()
        val target = DoubleArray(n)
        for (i in 0 until n) {
            val s = mid[i] - fromDist
            val vb = vBase[i]
            val pProv = if (useExt) pace(vb, absP[i], extraP[i], w) else Double.NaN
            val p = when {
                !jamMode -> if (pProv.isNaN()) 1.0 / vb else pProv
                inProviderJam -> if (mid[i] < currentJamHead) {
                    // 渋滞の中: 近くは自分の実際の流れ、先は交通情報の流れ
                    val a = ownWeight * exp(-s / params.ownJamBlendLength)
                    a * pOwn + (1.0 - a) * (if (pProv.isNaN()) pOwn else pProv)
                } else {
                    if (pProv.isNaN()) 1.0 / vb else pProv
                }
                else -> {
                    // 交通情報にいまの渋滞が無い（情報が古い・範囲外）: 自分の流れが既に渋滞を走った距離に応じて薄れていく
                    // （いまは直近 2 分の流れ、先へ行くほど順調に走れる速さへ）
                    val e = exp(-s / lJam)
                    val own = 1.0 / max(vFree[i] * cJam * e + vb * (1.0 - e), params.minSpeed)
                    if (pProv.isNaN()) own else max(own, pProv)
                }
            }
            target[i] = 1.0 / p
        }
        // 現在の速さからのずれは、すぐ先だけに効かせる
        // （交通情報の渋滞の中では、直近 2 分の自分の流れを使っているので弱めに・短く）
        val shortL = if (inProviderJam) params.shortTermJamLength else params.shortTermLength
        val dv = (vNow - target[0]) * (if (inProviderJam) 0.5 else 1.0)
        for (i in 0 until n) {
            val s = mid[i] - fromDist
            target[i] = (target[i] + dv * exp(-s / shortL)).coerceIn(params.minSpeed, max(params.minSpeed, vFree[i] * 1.6))
        }

        // 加速度制約: 境界速度 vb[0..n]
        val vbd = DoubleArray(n + 1)
        vbd[0] = max(0.0, vNow)
        for (i in 0 until n) {
            val ds = bound[i + 1] - bound[i]
            vbd[i + 1] = min(target[i], sqrt(vbd[i] * vbd[i] + 2 * params.accel * ds))
        }
        for (i in n - 1 downTo 1) {
            val ds = bound[i + 1] - bound[i]
            vbd[i] = min(vbd[i], sqrt(vbd[i + 1] * vbd[i + 1] + 2 * params.decel * ds))
        }
        val cum = DoubleArray(n + 1)
        val vPred = DoubleArray(n)
        for (i in 0 until n) {
            val ds = bound[i + 1] - bound[i]
            val avg = max(params.minSpeed, min(0.5 * (vbd[i] + vbd[i + 1]), max(target[i], vbd[i])))
            vPred[i] = avg
            cum[i + 1] = cum[i] + if (ds > 0) ds / avg else 0.0
        }
        return EtaResult(fromDist, bound, cum, vPred, vFree)
    }

    /**
     * 1m あたりの所要時間 [s/m]。交通情報が無ければ NaN。
     * 渋滞の実際の速さがわかればそれを、遅れ時間がわかれば「自由流 + 遅れ」を使う（w = 情報の信頼度）。
     */
    private fun pace(vBase: Double, absPace: Double, extraPace: Double, w: Double): Double {
        val free = 1.0 / max(vBase, params.minSpeed)
        val prov = when {
            !absPace.isNaN() -> max(free, absPace)
            !extraPace.isNaN() -> free + max(0.0, extraPace)
            else -> return Double.NaN
        }
        return free + (prov - free) * w
    }

    /**
     * 渋滞の伸び縮みを反映する（absP / extraP を書き換える）。
     * 自分が最後尾に追いつく時刻を、渋滞が無いとした所要時間から求め、その時刻の最後尾の位置から渋滞が始まるとみなす。
     *  - 伸びている: 最後尾より手前の区間も、最後尾付近と同じ流れにする
     *  - 縮んでいる: 縮んだ区間は順調にする。着く前に解消するなら渋滞全体を順調にする
     * @return 自分がいま中にいる（または 2.5km 以内に始まる）渋滞の先頭。無ければ NaN
     */
    private fun applyJamDynamics(
        ext: ExtTraffic, fromDist: Double, n: Int, bound: DoubleArray, mid: DoubleArray,
        vBase: DoubleArray, absP: DoubleArray, extraP: DoubleArray
    ): Double {
        if (ext.jams.isEmpty() || n == 0) return Double.NaN
        val jams = ext.jams.sortedBy { it.tail }
        // まだ伸び縮みを反映していない渋滞のセル（追いつく時刻の計算では、その渋滞は無いものとする）
        val pending = BooleanArray(n)
        for (j in jams) for (i in 0 until n) if (mid[i] >= j.tail && mid[i] < j.head) pending[i] = true
        val t0 = DoubleArray(n + 1)
        fun rebuildTimes() {
            for (i in 0 until n) {
                val free = 1.0 / max(vBase[i], params.minSpeed)
                val p = if (pending[i]) free else pace(vBase[i], absP[i], extraP[i], 1.0).let { if (it.isNaN()) free else it }
                t0[i + 1] = t0[i] + (bound[i + 1] - bound[i]) * p
            }
        }
        rebuildTimes()
        fun timeAt(x: Double): Double {
            if (x <= bound[0]) return 0.0
            if (x >= bound[n]) return t0[n] + (x - bound[n]) / 25.0
            var lo = 0
            var hi = n
            while (hi - lo > 1) {
                val m = (lo + hi) ushr 1
                if (bound[m] <= x) lo = m else hi = m
            }
            val f = (x - bound[lo]) / (bound[hi] - bound[lo]).coerceAtLeast(1e-9)
            return t0[lo] + f * (t0[hi] - t0[lo])
        }
        val horizon = (JamModel.MAX_EXTRAPOLATION_SEC - ext.ageSec).coerceAtLeast(0.0)
        var current = Double.NaN
        for (j in jams) {
            // この渋滞を処理したら、以後の渋滞の「追いつく時刻」にはこの渋滞の遅れを含める
            val doneAfter = {
                for (i in 0 until n) if (mid[i] >= j.tail - JamModel.maxGrowth(j.length) && mid[i] < j.head) pending[i] = false
                rebuildTimes()
            }
            if (j.head <= fromDist) {
                doneAfter()
                continue
            }
            val tailNow = j.tailAfter(ext.ageSec)
            if (tailNow >= j.head - 100.0) {
                // 取得からいままでに解消した
                for (i in 0 until n) if (mid[i] >= j.tail && mid[i] < j.head) {
                    absP[i] = Double.NaN
                    extraP[i] = 0.0
                }
                doneAfter()
                continue
            }
            var meet = tailNow
            if (!j.tailSpeed.isNaN() && abs(j.tailSpeed) > 0.05 && tailNow > fromDist) {
                val minTail = j.tail - JamModel.maxGrowth(j.length)
                repeat(5) {
                    val t = min(timeAt(meet), horizon)
                    meet = (tailNow + j.tailSpeed * t).coerceIn(max(minTail, fromDist), j.head - 100.0)
                }
            }
            if (meet >= j.head - 100.0) {
                // 着く前に解消する
                for (i in 0 until n) if (mid[i] >= j.tail && mid[i] < j.head) {
                    absP[i] = Double.NaN
                    extraP[i] = 0.0
                }
                doneAfter()
                continue
            }
            if (meet < j.tail - 1.0) {
                // 伸びた分: 最後尾付近の流れを手前へ延ばす
                var a = Double.NaN
                var e = Double.NaN
                for (i in 0 until n) {
                    if (mid[i] < j.tail) continue
                    if (mid[i] >= j.head) break
                    if (!absP[i].isNaN() || (!extraP[i].isNaN() && extraP[i] > 0.0)) {
                        a = absP[i]
                        e = extraP[i]
                        break
                    }
                }
                if (!a.isNaN() || !e.isNaN()) {
                    for (i in 0 until n) {
                        if (mid[i] < meet) continue
                        if (mid[i] >= j.tail) break
                        if (!a.isNaN()) absP[i] = if (absP[i].isNaN()) a else max(absP[i], a)
                        if (!e.isNaN()) extraP[i] = if (extraP[i].isNaN()) e else max(extraP[i], e)
                    }
                }
            } else if (meet > j.tail + 1.0) {
                // 縮んだ分は順調
                for (i in 0 until n) {
                    if (mid[i] < j.tail) continue
                    if (mid[i] >= meet) break
                    absP[i] = Double.NaN
                    extraP[i] = 0.0
                }
            }
            if (current.isNaN() && meet <= fromDist + 2500.0) current = j.head
            doneAfter()
        }
        return current
    }
}

/**
 * 表示用 ETA の平滑化（1 秒ごとの微小な揺れで「分」がちらつかないようにする）。
 */
class EtaSmoother {
    private val smoothed = HashMap<String, Double>()
    private val shownMinute = HashMap<String, Long>()

    /** @return 表示する到着時刻 [epoch ms]（分単位に丸め済み） */
    fun update(id: String, rawEpochMs: Double): Long {
        val prev = smoothed[id]
        val v = if (prev == null || abs(rawEpochMs - prev) > 180_000.0) rawEpochMs else prev + 0.3 * (rawEpochMs - prev)
        smoothed[id] = v
        val minutes = v / 60_000.0
        val shown = shownMinute[id]
        val m = if (shown == null || abs(minutes - shown) > 0.75) Math.round(minutes) else shown
        shownMinute[id] = m
        return m * 60_000L
    }

    fun smoothedValue(id: String): Double? = smoothed[id]

    fun retain(ids: Set<String>) {
        smoothed.keys.retainAll(ids)
        shownMinute.keys.retainAll(ids)
    }
}

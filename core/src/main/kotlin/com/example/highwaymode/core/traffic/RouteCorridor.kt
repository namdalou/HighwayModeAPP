package com.example.highwaymode.core.traffic

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 問い合わせた前方経路のまわり（v2.23）。取得した交通情報のうち、経路の近くにあるものだけを残すのに使う
 * （首都圏など、問い合わせた範囲に数千件の情報があるとき、道路との照合を軽くする。確認用の件数にも使う）。
 * 経路を 50m ごとの点にして、約 300m の升目に入れておく。
 */
class RouteCorridor(q: TrafficQuery, maxDistM: Double = Double.MAX_VALUE) {
    private val lat: DoubleArray
    private val lon: DoubleArray
    /** 各点の道のり（現在地から）[m] */
    private val along: DoubleArray
    private val grid = HashMap<Long, IntArray>()
    private val mLat = 110_950.0
    private val mLon: Double

    init {
        val la = ArrayList<Double>()
        val lo = ArrayList<Double>()
        val ds = ArrayList<Double>()
        val n = q.lat.size
        if (n > 0) {
            la.add(q.lat[0]); lo.add(q.lon[0]); ds.add(q.dist.getOrElse(0) { 0.0 })
        }
        for (i in 0 until n - 1) {
            val d0 = q.dist.getOrElse(i) { 0.0 }
            if (d0 > maxDistM) break
            val d1 = q.dist.getOrElse(i + 1) { d0 }
            val seg = max(1.0, d1 - d0)
            val k = max(1, ceil(seg / STEP_M).toInt())
            for (j in 1..k) {
                val f = j.toDouble() / k
                la.add(q.lat[i] + (q.lat[i + 1] - q.lat[i]) * f)
                lo.add(q.lon[i] + (q.lon[i + 1] - q.lon[i]) * f)
                ds.add(d0 + (d1 - d0) * f)
            }
        }
        lat = la.toDoubleArray()
        lon = lo.toDoubleArray()
        along = ds.toDoubleArray()
        mLon = 111_320.0 * cos(Math.toRadians(if (lat.isEmpty()) 35.0 else lat[0]))
        val tmp = HashMap<Long, ArrayList<Int>>()
        for (i in lat.indices) tmp.getOrPut(key(cell(lat[i]), cell(lon[i]))) { ArrayList(4) }.add(i)
        for ((k, v) in tmp) grid[k] = v.toIntArray()
    }

    val isEmpty: Boolean get() = lat.isEmpty()

    private fun cell(v: Double): Int = floor(v / CELL_DEG).toInt()
    private fun key(a: Int, b: Int): Long = (a.toLong() shl 32) or (b.toLong() and 0xFFFFFFFFL)

    /** いちばん近い経路の点の番号（radius 以内に無ければ -1） */
    private fun nearestIndex(la: Double, lo: Double, radius: Double): Int {
        val ca = cell(la)
        val co = cell(lo)
        var best = -1
        var bestD2 = radius * radius
        for (da in -1..1) for (dn in -1..1) {
            val idx = grid[key(ca + da, co + dn)] ?: continue
            for (i in idx) {
                val dy = (lat[i] - la) * mLat
                val dx = (lon[i] - lo) * mLon
                val d2 = dx * dx + dy * dy
                if (d2 <= bestD2) {
                    bestD2 = d2
                    best = i
                }
            }
        }
        return best
    }

    /** 経路までの距離 [m]（radius（300m まで）以内に無ければ null）。点の間隔 50m の分だけ大きめに出ることがある */
    fun distanceTo(la: Double, lo: Double, radius: Double = MAX_RADIUS_M): Double? {
        val i = nearestIndex(la, lo, radius.coerceAtMost(MAX_RADIUS_M))
        if (i < 0) return null
        val dy = (lat[i] - la) * mLat
        val dx = (lon[i] - lo) * mLon
        return sqrt(dx * dx + dy * dy)
    }

    /** 近い経路の点の道のり [m]（無ければ null） */
    fun alongOf(la: Double, lo: Double, radius: Double = MAX_RADIUS_M): Double? {
        val i = nearestIndex(la, lo, radius.coerceAtMost(MAX_RADIUS_M))
        return if (i < 0) null else along[i]
    }

    /** 線（または点）のどこかが経路から radius 以内にあるか（頂点の間は 50m ごとに調べる） */
    fun lineNear(la: DoubleArray, lo: DoubleArray, radius: Double): Boolean = firstNearAlong(la, lo, radius) != null

    /** 線のうち経路の近くにある部分の、経路上の道のりの最小 [m]（近くに無ければ null） */
    fun firstNearAlong(la: DoubleArray, lo: DoubleArray, radius: Double): Double? {
        if (isEmpty || la.isEmpty()) return null
        val r = radius.coerceAtMost(MAX_RADIUS_M)
        var best: Double? = null
        fun test(a: Double, b: Double) {
            val i = nearestIndex(a, b, r)
            if (i >= 0 && (best == null || along[i] < best!!)) best = along[i]
        }
        test(la[0], lo[0])
        for (k in 0 until la.size - 1) {
            val dy = (la[k + 1] - la[k]) * mLat
            val dx = (lo[k + 1] - lo[k]) * mLon
            val len = sqrt(dx * dx + dy * dy)
            val steps = max(1, ceil(len / STEP_M).toInt())
            for (j in 1..steps) {
                val f = j.toDouble() / steps
                test(la[k] + (la[k + 1] - la[k]) * f, lo[k] + (lo[k + 1] - lo[k]) * f)
            }
        }
        return best
    }

    companion object {
        const val STEP_M = 50.0
        /** 升目の大きさ（緯度・経度）。約 330m × 270m（北緯 35 度） */
        private const val CELL_DEG = 0.003
        const val MAX_RADIUS_M = 250.0
    }
}

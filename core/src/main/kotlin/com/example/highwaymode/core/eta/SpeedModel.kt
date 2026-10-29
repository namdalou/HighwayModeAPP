package com.example.highwaymode.core.eta

import com.example.highwaymode.core.graph.RoadAttr
import com.example.highwaymode.core.graph.RoadClass
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.exp

/**
 * 道路属性から最高速度を決める（標識タグが無い場合は日本の道路種別ごとの一般値）。
 */
object SpeedLimitModel {
    class Limit(val kmh: Double, val tagged: Boolean)

    fun limitFor(attr: RoadAttr): Limit {
        attr.maxspeedKmh?.let { return Limit(it.toDouble(), true) }
        val kmh = when {
            attr.isLink -> 40.0
            attr.urban -> 60.0
            attr.roadClass == RoadClass.MOTORWAY ->
                if (attr.bidirectional || (attr.lanes != null && attr.lanes <= 1)) 70.0 else 100.0
            attr.roadClass == RoadClass.TRUNK && attr.motorroad -> 70.0
            attr.roadClass == RoadClass.TRUNK -> 60.0
            else -> 50.0
        }
        return Limit(kmh, false)
    }
}

/**
 * 運転傾向（制限速度に対する自由走行時の速度比）を制限速度帯ごとに学習する。
 * 事前分布は日本の高速道路の一般的な自由流速度。学習値は指数忘却（約 1 時間分）。
 */
class DriverProfile {
    private val bands = doubleArrayOf(55.0, 65.0, 75.0, 85.0, 105.0, Double.MAX_VALUE)
    private val prior = doubleArrayOf(1.10, 1.10, 1.07, 1.05, 1.00, 0.94)
    private val sumW = DoubleArray(bands.size)
    private val sumR = DoubleArray(bands.size)
    private val priorWeightSec = 300.0
    private val tauSec = 3600.0

    fun bandOf(limitKmh: Double): Int {
        for (i in bands.indices) if (limitKmh < bands[i]) return i
        return bands.size - 1
    }

    /** 制限速度に対する予測速度比 */
    fun ratio(limitKmh: Double): Double {
        val b = bandOf(limitKmh)
        return (prior[b] * priorWeightSec + sumR[b]) / (priorWeightSec + sumW[b])
    }

    /** 学習済みの重み（秒） */
    fun learnedSeconds(limitKmh: Double): Double = sumW[bandOf(limitKmh)]

    fun learn(limitKmh: Double, observedRatio: Double, dtSec: Double) {
        if (dtSec <= 0 || observedRatio.isNaN()) return
        val b = bandOf(limitKmh)
        val r = observedRatio.coerceIn(0.6, 1.5)
        val d = exp(-dtSec / tauSec)
        sumW[b] = sumW[b] * d + dtSec
        sumR[b] = sumR[b] * d + dtSec * r
    }

    fun reset() {
        sumW.fill(0.0)
        sumR.fill(0.0)
    }

    fun serialize(): String = sumW.indices.joinToString(";") { String.format(Locale.US, "%.3f,%.5f", sumW[it], sumR[it]) }

    fun restore(s: String?) {
        if (s.isNullOrBlank()) return
        val parts = s.split(';')
        if (parts.size != bands.size) return
        for (i in parts.indices) {
            val p = parts[i].split(',')
            if (p.size != 2) return
            val w = p[0].toDoubleOrNull() ?: return
            val r = p[1].toDoubleOrNull() ?: return
            if (w < 0 || r < 0) return
            sumW[i] = w
            sumR[i] = r
        }
    }
}

/** 現在の交通状況（自車の走りから推定） */
class TrafficState(
    /** 自由流に対する所要時間比（1 = 順調、0.3 = 渋滞） */
    val ratio: Double,
    /** 連続して流れが悪い区間を走った距離 [m] */
    val congestedDistance: Double,
    /** 推定に十分なデータがあるか */
    val valid: Boolean,
    /** 直近 6 秒の加速傾向 [m/s²]（渋滞の抜け出し検出用） */
    val accelTrend: Double = 0.0,
    /** 順調に流れていた区間だけで見た直近 5 分の速度比（運転のくせ・その日の流れ） */
    val freeRatio: Double = 1.0,
    val freeValid: Boolean = false
)

/**
 * 直近の走行から「自由流ならかかる時間 / 実際にかかった時間」を求める。
 *  - ratio      : 120 秒窓の全サンプル（停止と発進を繰り返す渋滞でも平均的な流れを捉える）
 *  - freeRatio  : 300 秒窓のうち順調に流れていたサンプルのみ（渋滞の影響を受けない「走り方」）
 */
class TrafficTracker(private val windowSec: Double = 120.0, private val freeWindowSec: Double = 300.0) {
    private class Sample(val t: Double, val dt: Double, val ds: Double, val freeT: Double, val v: Double)

    private val samples = ArrayDeque<Sample>()
    private var sumDt = 0.0
    private var sumFreeT = 0.0
    private val freeSamples = ArrayDeque<Sample>()
    private var fSumDt = 0.0
    private var fSumFreeT = 0.0
    var congestedDistance = 0.0
        private set

    fun add(tSec: Double, dtSec: Double, ds: Double, vFree: Double) {
        if (dtSec <= 0 || dtSec > 5 || vFree <= 0.5) return
        val freeT = ds / vFree
        val smp = Sample(tSec, dtSec, ds, freeT, ds / dtSec)
        samples.addLast(smp)
        sumDt += dtSec
        sumFreeT += freeT
        while (samples.isNotEmpty() && samples.first().t < tSec - windowSec) {
            val s = samples.removeFirst()
            sumDt -= s.dt
            sumFreeT -= s.freeT
        }
        if (smp.v >= 0.75 * vFree) {
            freeSamples.addLast(smp)
            fSumDt += dtSec
            fSumFreeT += freeT
        }
        while (freeSamples.isNotEmpty() && freeSamples.first().t < tSec - freeWindowSec) {
            val s = freeSamples.removeFirst()
            fSumDt -= s.dt
            fSumFreeT -= s.freeT
        }
        val r = ratioRaw()
        if (r < 0.75) congestedDistance += ds else if (r > 0.88) congestedDistance = 0.0
    }

    private fun ratioRaw(): Double = if (sumDt > 1e-6) (sumFreeT / sumDt) else 1.0

    fun state(): TrafficState = TrafficState(
        ratio = ratioRaw().coerceIn(0.0, 1.5),
        congestedDistance = congestedDistance,
        valid = sumDt >= 30.0,
        accelTrend = accelTrend(),
        freeRatio = if (fSumDt > 1e-6) (fSumFreeT / fSumDt).coerceIn(0.5, 1.5) else 1.0,
        freeValid = fSumDt >= 30.0
    )

    /** 直近 6 秒の速度変化率 [m/s²]（前半 2 秒と後半 2 秒の平均速度の差） */
    private fun accelTrend(): Double {
        if (samples.isEmpty()) return 0.0
        val tEnd = samples.last().t
        var vA = 0.0; var nA = 0
        var vB = 0.0; var nB = 0
        val it = samples.descendingIterator()
        while (it.hasNext()) {
            val smp = it.next()
            val age = tEnd - smp.t
            if (age > 6.0) break
            if (age <= 2.0) { vB += smp.v; nB++ } else if (age >= 4.0) { vA += smp.v; nA++ }
        }
        if (nA == 0 || nB == 0) return 0.0
        return (vB / nB - vA / nA) / 4.0
    }

    fun clear() {
        samples.clear()
        freeSamples.clear()
        sumDt = 0.0
        sumFreeT = 0.0
        fSumDt = 0.0
        fSumFreeT = 0.0
        congestedDistance = 0.0
    }
}

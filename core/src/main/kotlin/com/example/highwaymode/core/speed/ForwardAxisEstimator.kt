package com.example.highwaymode.core.speed

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * スマホ座標系における「車両の前方向」を推定する。
 *
 * 加速度センサの水平成分 p（スマホ座標の水平基底 e1,e2 で表した複素数）は
 *   p = (a_long + i·a_lat) · e^{iθ}
 * と書ける（θ: e1 から前方軸までの角度）。a_long は GPS 速度の差分、
 * a_lat = v·(左回り角速度) はジャイロから得られるので、
 *   e^{iθ} ∝ Σ p·conj(q)   （q = a_long + i·a_lat）
 * という閉形式の最小二乗で θ が求まる。
 * GPS 速度差分のノイズを抑えるため、連続する 2 区間（約 2 秒）をまとめて対応付ける。
 * 推定後は GPS が無いトンネル内でも前後加速度 a_long を取り出せる。
 */
class ForwardAxisEstimator(private val tauSec: Double = 900.0) {

    // 水平基底（スマホ座標）
    private val e1 = DoubleArray(3)
    private val e2 = DoubleArray(3)
    private var basisValid = false

    // 現在の GPS 区間内の加速度和
    private var accP1 = 0.0
    private var accP2 = 0.0
    private var accN = 0
    // 1 つ前の区間
    private var prevP1 = 0.0
    private var prevP2 = 0.0
    private var prevN = 0
    private var prevLong = Double.NaN
    private var prevLat = 0.0
    private var prevDt = 0.0

    // 相関の指数忘却和
    private var wRe = 0.0
    private var wIm = 0.0
    private var sumPP = 0.0
    private var sumQQ = 0.0
    private var lastT = Long.MIN_VALUE

    var thetaRad = 0.0
        private set
    var correlation = 0.0
        private set

    /** 励起量（忘却付き Σ|q|²） */
    val excitation: Double get() = sumQQ

    // 水平面内の加速度センサーバイアス（基底 e1, e2 成分）
    private var biasP1 = 0.0
    private var biasP2 = 0.0
    private var biasWeight = 0.0

    /** 前後方向に射影したバイアス推定 [m/s²] */
    val forwardBias: Double get() = biasP1 * cos(thetaRad) + biasP2 * sin(thetaRad)

    /** 推定が使える状態か（十分な加減速・旋回の励起と高い相関） */
    val valid: Boolean get() = basisValid && sumQQ > 3.0 && correlation > 0.6

    /**
     * 水平基底を設定（重力方向 up と参照軸 ref から）。取付が変わったら呼ぶ。
     */
    fun setBasis(up: DoubleArray, ref: DoubleArray) {
        val d = up[0] * ref[0] + up[1] * ref[1] + up[2] * ref[2]
        var x = ref[0] - d * up[0]
        var y = ref[1] - d * up[1]
        var z = ref[2] - d * up[2]
        val n = sqrt(x * x + y * y + z * z)
        reset()
        if (n < 0.2) return
        x /= n; y /= n; z /= n
        e1[0] = x; e1[1] = y; e1[2] = z
        // e2 = up × e1
        e2[0] = up[1] * z - up[2] * y
        e2[1] = up[2] * x - up[0] * z
        e2[2] = up[0] * y - up[1] * x
        basisValid = true
    }

    /** 線形加速度（重力除去済み、スマホ座標）を蓄積 */
    fun addLinearAccel(ax: Double, ay: Double, az: Double) {
        if (!basisValid) return
        accP1 += ax * e1[0] + ay * e1[1] + az * e1[2]
        accP2 += ax * e2[0] + ay * e2[1] + az * e2[2]
        accN++
    }

    /**
     * GPS 測位ごとに、その区間の GPS 由来加速度と対応付ける。
     * @param aLong 前後加速度 [m/s²]（GPS 速度差分）
     * @param aLat 横加速度 [m/s²]（左向き正 = v × 左回り角速度）
     * @param dt 区間長 [s]
     */
    fun onGpsInterval(tNanos: Long, aLong: Double, aLat: Double, dt: Double) {
        if (!basisValid || accN < 5) {
            discardInterval()
            return
        }
        if (prevN > 0 && !prevLong.isNaN()) {
            val n = prevN + accN
            val rawP1 = (prevP1 + accP1) / n
            val rawP2 = (prevP2 + accP2) / n
            val tt = dt + prevDt
            val qL = (aLong * dt + prevLong * prevDt) / tt
            val qT = (aLat * dt + prevLat * prevDt) / tt
            // 水平面内のセンサーバイアス: 長期的に車の加速度の平均は 0 に近いので、
            // 「センサー − GPS 加速度（前方軸が定まっていれば回転して比較）」の平均をバイアスとする
            val c0 = cos(thetaRad)
            val s0 = sin(thetaRad)
            val r1 = if (valid) rawP1 - (qL * c0 - qT * s0) else rawP1
            val r2 = if (valid) rawP2 - (qL * s0 + qT * c0) else rawP2
            val a = 1.0 / (1.0 + minOf(biasWeight, 150.0))
            biasP1 += a * (r1.coerceIn(-1.0, 1.0) - biasP1)
            biasP2 += a * (r2.coerceIn(-1.0, 1.0) - biasP2)
            biasWeight += 1.0
            val p1 = rawP1 - biasP1
            val p2 = rawP2 - biasP2
            if (lastT != Long.MIN_VALUE && tNanos > lastT) {
                val decay = exp(-(tNanos - lastT) * 1e-9 / tauSec)
                wRe *= decay; wIm *= decay; sumPP *= decay; sumQQ *= decay
            }
            lastT = tNanos
            // p·conj(q)
            wRe += p1 * qL + p2 * qT
            wIm += p2 * qL - p1 * qT
            sumPP += p1 * p1 + p2 * p2
            sumQQ += qL * qL + qT * qT
            thetaRad = atan2(wIm, wRe)
            val denom = sqrt(sumPP * sumQQ)
            correlation = if (denom > 1e-9) sqrt(wRe * wRe + wIm * wIm) / denom else 0.0
        }
        prevP1 = accP1; prevP2 = accP2; prevN = accN
        prevLong = aLong; prevLat = aLat; prevDt = dt
        accP1 = 0.0; accP2 = 0.0; accN = 0
    }

    /** GPS 区間が使えない場合に蓄積を捨てる（区間のずれを防ぐ） */
    fun discardInterval() {
        accP1 = 0.0; accP2 = 0.0; accN = 0
        prevN = 0
        prevLong = Double.NaN
    }

    /**
     * 線形加速度から前後成分を取り出す（推定が無効、または横方向の整合が取れない場合は null）。
     * @param expectedLat ジャイロから求めた横加速度 [m/s²]（左向き正）。整合チェックに使う。
     */
    fun forwardAccel(ax: Double, ay: Double, az: Double, expectedLat: Double = Double.NaN): Double? {
        if (!valid) return null
        val p1 = ax * e1[0] + ay * e1[1] + az * e1[2] - biasP1
        val p2 = ax * e2[0] + ay * e2[1] + az * e2[2] - biasP2
        val c = cos(thetaRad)
        val s = sin(thetaRad)
        // Re / Im (p · e^{-iθ})
        val along = p1 * c + p2 * s
        val lat = -p1 * s + p2 * c
        if (!expectedLat.isNaN() && abs(lat - expectedLat) > 2.5) return null // 振動・段差の瞬間値は除外
        return along
    }

    fun reset() {
        basisValid = false
        wRe = 0.0; wIm = 0.0; sumPP = 0.0; sumQQ = 0.0
        accP1 = 0.0; accP2 = 0.0; accN = 0
        prevN = 0; prevLong = Double.NaN
        correlation = 0.0
        lastT = Long.MIN_VALUE
        biasP1 = 0.0
        biasP2 = 0.0
        biasWeight = 0.0
    }
}

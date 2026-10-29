package com.example.highwaymode.core.vehicle

import com.example.highwaymode.core.geo.Geo
import kotlin.math.abs
import kotlin.math.sqrt

/** ETC カードの状態（Android Auto の TollCard） */
enum class TollCardState { UNKNOWN, VALID, INVALID, NOT_INSERTED }

/**
 * 車両から得られる情報（Android Auto 接続時）。
 * 車速は車輪の回転から求めた値で、トンネル内でも途切れず、GPS より応答が速い。
 */
class VehicleState {
    /** Android Auto で車につながっている */
    var connected = false
    var model: String? = null
    var electric = false

    // ---- 車速
    var speedT = Long.MIN_VALUE
        private set
    var speedMs = Double.NaN
        private set
    var speedIsRaw = false
        private set
    /** 車速信号の倍率（GPS ドップラー速度との比で学習。メーター表示速度は 2〜5% 高めのことが多い） */
    var scale = 1.0
        private set
    /** 倍率の学習量 [s] */
    var scaleLearnedSec = 0.0
        private set

    // ---- ETC・燃料
    var tollCard = TollCardState.UNKNOWN
    var tollCardKnown = false
    var rangeRemainingM: Double? = null
    var fuelPercent: Double? = null
    var batteryPercent: Double? = null
    var energyLow: Boolean? = null

    fun setSpeed(t: Long, rawMs: Double?, displayMs: Double?) {
        val raw = rawMs?.takeIf { !it.isNaN() && it >= 0 && it < 90 }
        val disp = displayMs?.takeIf { !it.isNaN() && it >= 0 && it < 90 }
        val v = raw ?: disp ?: return
        val isRaw = raw != null
        if (isRaw != speedIsRaw && speedT != Long.MIN_VALUE) {
            // 生の車速とメーター表示の切り替わり: 倍率を学習し直す
            scale = if (isRaw) 1.0 else 0.97
            scaleLearnedSec = 0.0
        } else if (speedT == Long.MIN_VALUE && !isRaw) {
            scale = 0.97
        }
        speedIsRaw = isRaw
        speedMs = v
        speedT = t
    }

    fun speedFresh(t: Long, maxAgeSec: Double = 1.5): Boolean =
        speedT != Long.MIN_VALUE && (t - speedT) * 1e-9 <= maxAgeSec && !speedMs.isNaN()

    /** 補正後の車速 [m/s] */
    val correctedSpeed: Double get() = speedMs * scale

    /** 車速の 1σ [m/s] */
    fun speedSigma(): Double {
        val v = correctedSpeed
        return if (scaleLearnedSec >= 60.0) 0.12 + 0.006 * v else 0.25 + 0.03 * v
    }

    /** GPS ドップラー速度との比較で倍率を学習（直進・等速に近い区間で呼ぶ） */
    fun learnScale(gpsMs: Double, gpsSigma: Double, dtSec: Double) {
        if (speedMs.isNaN() || speedMs < 8.0 || gpsMs < 8.0 || gpsSigma > 0.6) return
        val r = gpsMs / speedMs
        if (r < 0.85 || r > 1.15) return
        val w = (dtSec / 120.0).coerceIn(0.0, 0.05)
        scale += (r - scale) * if (scaleLearnedSec < 30) 0.2 else w
        scaleLearnedSec += dtSec
    }

    fun reset() {
        speedT = Long.MIN_VALUE
        speedMs = Double.NaN
        tollCard = TollCardState.UNKNOWN
        tollCardKnown = false
        rangeRemainingM = null
        fuelPercent = null
        batteryPercent = null
        energyLow = null
    }
}

/**
 * 車両のジャイロ（Android Auto の CarSensors）の軸と符号を、GPS 方位の変化と照合して決める。
 * 車両の座標系はメーカーによって異なるので、x/y/z と ± のうち GPS の旋回と最もよく一致するものを使う。
 * スマホと違って車体に固定されているため、確定すればスマホの置き方に左右されない。
 */
class VehicleYawCalibrator {
    // 各軸の積分値 x と基準の方位変化 y の統計（定数バイアスは切片で吸収するため平均を引いて相関をとる）
    private var n = 0.0
    private val sx = DoubleArray(3)
    private val sxx = DoubleArray(3)
    private val sxy = DoubleArray(3)
    private var sy = 0.0
    private var syy = 0.0
    private val acc = DoubleArray(3)
    private var accDt = 0.0
    private var lastT = Long.MIN_VALUE
    var lastSampleT = Long.MIN_VALUE
        private set
    private val last = DoubleArray(3)

    /** 確定した軸（0..2）と符号。未確定なら -1 */
    var axis = -1
        private set
    var sign = 1.0
        private set
    var correlation = 0.0
        private set

    val active: Boolean get() = axis >= 0

    fun fresh(t: Long): Boolean = lastSampleT != Long.MIN_VALUE && (t - lastSampleT) * 1e-9 < 0.5

    /** @return 確定していれば方位変化率（時計回り正）[rad/s] */
    fun onSample(t: Long, x: Double, y: Double, z: Double): Double? {
        val dt = if (lastT == Long.MIN_VALUE) 0.0 else (t - lastT) * 1e-9
        lastT = t
        lastSampleT = t
        last[0] = x; last[1] = y; last[2] = z
        if (dt > 0 && dt < 0.5) {
            if (!x.isNaN()) acc[0] += x * dt
            if (!y.isNaN()) acc[1] += y * dt
            if (!z.isNaN()) acc[2] += z * dt
            accDt += dt
        }
        if (axis < 0) return null
        val v = last[axis]
        return if (v.isNaN()) null else sign * v
    }

    /**
     * 基準の方位変化 [rad]（時計回り正）と照合する。
     * 基準はスマホのジャイロ（鉛直軸まわりに射影・バイアス補正済み）の積分が最良。スマホにジャイロが無ければ GPS 方位の変化。
     * @param minTurnDeg 判定に必要な累積旋回量（二乗和の平方根）
     */
    fun onReferenceInterval(dPsi: Double, dtSec: Double, minTurnDeg: Double = 8.0) {
        if (accDt < dtSec * 0.6 || accDt > dtSec * 1.6) {
            clearInterval()
            return
        }
        // 区間長の違いで切片（バイアス×時間）が揺れないよう 1 秒あたりに直す
        val y = dPsi / dtSec
        n += 1.0
        sy += y
        syy += y * y
        for (a in 0 until 3) {
            val x = acc[a] / accDt
            sx[a] += x
            sxx[a] += x * x
            sxy[a] += x * y
        }
        clearInterval()
        val varY = syy / n - (sy / n) * (sy / n)
        // 旋回量（標準偏差 × √n）が minTurnDeg 相当に達したら判定
        if (n < 20 || varY * n < (minTurnDeg * Geo.DEG) * (minTurnDeg * Geo.DEG)) return
        var best = -1
        var bestR = 0.0
        var bestSlope = 0.0
        for (k in 0 until 3) {
            val mx = sx[k] / n
            val varX = sxx[k] / n - mx * mx
            if (varX <= 1e-12) continue
            val cov = sxy[k] / n - mx * (sy / n)
            val r = cov / sqrt(varX * varY)
            if (abs(r) > abs(bestR)) {
                bestR = r
                best = k
                bestSlope = cov / varX
            }
        }
        if (best < 0) return
        correlation = abs(bestR)
        if (abs(bestR) > 0.97 && abs(bestSlope) in 0.85..1.18) {
            axis = best
            sign = if (bestR > 0) 1.0 else -1.0
        } else if (axis >= 0 && abs(bestR) < 0.8) {
            axis = -1 // 一致しなくなった（取り付け・座標系の変化）
        }
    }

    fun clearInterval() {
        acc.fill(0.0)
        accDt = 0.0
    }

    fun reset() {
        n = 0.0
        sx.fill(0.0)
        sxx.fill(0.0)
        sxy.fill(0.0)
        sy = 0.0
        syy = 0.0
        clearInterval()
        axis = -1
        lastT = Long.MIN_VALUE
        lastSampleT = Long.MIN_VALUE
    }
}

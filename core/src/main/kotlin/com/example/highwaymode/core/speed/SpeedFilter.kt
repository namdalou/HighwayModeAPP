package com.example.highwaymode.core.speed

import kotlin.math.max
import kotlin.math.sqrt

/**
 * 車速のカルマンフィルタ（状態: 速度 v, 前後加速度センサのバイアス ba）。
 * GPS ドップラー速度で更新し、GPS が途切れるトンネル内では
 * 加速度センサの前後成分（取付向き推定済みの場合）で速度変化を追従する。
 */
class SpeedFilter {
    var v = 0.0
        private set
    var accelBias = 0.0
        private set
    var initialized = false
        private set

    private var p00 = 100.0
    private var p01 = 0.0
    private var p11 = 0.05 * 0.05
    private var lastT = Long.MIN_VALUE

    val sigma: Double get() = sqrt(p00.coerceAtLeast(0.0))
    val biasSigma: Double get() = sqrt(p11.coerceAtLeast(0.0))

    /**
     * @param aLong 前後加速度 [m/s²]（前向き正）。取付向きが不明なら null。
     */
    fun predict(aLong: Double?, tNanos: Long) {
        if (lastT == Long.MIN_VALUE || tNanos <= lastT) {
            lastT = tNanos
            return
        }
        val dt = (tNanos - lastT) * 1e-9
        lastT = tNanos
        if (dt > 1.0 || !initialized) return
        if (aLong != null) {
            v = max(0.0, v + (aLong - accelBias) * dt)
            val q00 = 0.25 * 0.25 * dt
            val q11 = 0.002 * 0.002 * dt
            val n00 = p00 - 2 * dt * p01 + dt * dt * p11 + q00
            val n01 = p01 - dt * p11
            p00 = n00
            p01 = n01
            p11 += q11
        } else {
            // 加速度情報なし: 等速仮定で不確かさだけ増やす
            p00 += 0.6 * 0.6 * dt
            p11 += 0.002 * 0.002 * dt
        }
    }

    fun updateSpeed(z: Double, sigmaZ: Double) {
        if (!initialized) {
            v = max(0.0, z)
            p00 = sigmaZ * sigmaZ
            p01 = 0.0
            initialized = true
            return
        }
        val r = sigmaZ * sigmaZ
        val s = p00 + r
        val y = z - v
        val k0 = p00 / s
        val k1 = p01 / s
        v = max(0.0, v + k0 * y)
        accelBias = (accelBias + k1 * y).coerceIn(-0.5, 0.5)
        val n00 = (1 - k0) * p00
        val n01 = (1 - k0) * p01
        val n11 = p11 - k1 * p01
        p00 = n00
        p01 = n01
        p11 = n11.coerceAtLeast(1e-10)
    }

    /** 停車検出時のゼロ速度更新 */
    fun updateZero() = updateSpeed(0.0, 0.05)

    fun reset() {
        initialized = false
        v = 0.0
        p00 = 100.0
        p01 = 0.0
    }
}

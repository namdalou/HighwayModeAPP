package com.example.highwaymode.core.engine

import kotlin.math.sqrt

/**
 * 1 秒窓のジャイロ RMS と加速度の標準偏差から「車が止まっているか」を判定する材料を作る。
 * （停止中はジャイロのバイアス推定と速度ゼロ更新に使う）
 */
class StationaryDetector {
    private var winStart = Long.MIN_VALUE
    private var gyroSq = 0.0
    private var gyroN = 0
    private var accSum = 0.0
    private var accSq = 0.0
    private var accN = 0

    var gyroRms = 1.0
        private set
    var accStd = 1.0
        private set
    var hasWindow = false
        private set

    fun onGyro(t: Long, magnitude: Double) {
        gyroSq += magnitude * magnitude
        gyroN++
        roll(t)
    }

    fun onAccel(t: Long, linearMagnitude: Double) {
        accSum += linearMagnitude
        accSq += linearMagnitude * linearMagnitude
        accN++
        roll(t)
    }

    private fun roll(t: Long) {
        if (winStart == Long.MIN_VALUE) {
            winStart = t
            return
        }
        if (t - winStart < 1_000_000_000L) return
        if (gyroN > 5) gyroRms = sqrt(gyroSq / gyroN)
        if (accN > 5) {
            val mean = accSum / accN
            accStd = sqrt((accSq / accN - mean * mean).coerceAtLeast(0.0))
        }
        hasWindow = gyroN > 5 || accN > 5
        gyroSq = 0.0; gyroN = 0
        accSum = 0.0; accSq = 0.0; accN = 0
        winStart = t
    }

    /** strict = GPS が無い状況用の厳しい閾値 */
    fun isQuiet(strict: Boolean): Boolean {
        if (!hasWindow) return false
        return if (strict) gyroRms < 0.012 && accStd < 0.05 else gyroRms < 0.03 && accStd < 0.12
    }
}

package com.example.highwaymode.core.heading

import com.example.highwaymode.core.geo.Geo
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 車両方位のカルマンフィルタ（状態: 方位 ψ, ジャイロ鉛直軸バイアス b）。
 *
 *  予測: ジャイロ角速度を重力方向へ射影した「鉛直軸まわりの回転率」で積分
 *        （スマホの取り付け向きに依存しない）。
 *  更新: GPS ドップラー方位（低速ほど誤差大、遅延補償付き）、
 *        取付オフセット補正済みコンパス、地図の道路方位（トンネル内）、
 *        停車中のゼロ角速度（バイアス推定）。
 *
 * 単位: 角度はラジアン、方位は真北基準・時計回り。
 */
class HeadingFilter(private val params: Params = Params()) {

    class Params(
        /** ジャイロの角度ランダムウォーク [rad/√s]（車載振動込み） */
        val arw: Double = 0.0015,
        /** スケールファクタ誤差・取付傾き誤差（回転量に比例） */
        val scaleErr: Double = 0.02,
        /** バイアスのランダムウォーク [rad/s/√s] */
        val biasRw: Double = 0.00004,
        /** 初期バイアス不確かさ [rad/s] */
        val initBiasSigma: Double = 0.006,
        val maxBiasAbs: Double = 0.05,
        /** 連続棄却がこの回数に達したら測定値で再初期化 */
        val maxConsecutiveRejects: Int = 4
    )

    enum class Source { NONE, GPS, COMPASS, MAP, GYRO }

    var initialized = false
        private set
    /** 方位 [rad, 0..2π) */
    var psi = 0.0
        private set
    /** ジャイロバイアス（方位変化率換算）[rad/s] */
    var bias = 0.0
        private set
    var lastSource = Source.NONE
        private set
    /** 最後に GPS / コンパス / 地図で補正された時刻 */
    var lastAbsoluteUpdateNanos = Long.MIN_VALUE
        private set

    private var p00 = 0.0
    private var p01 = 0.0
    private var p11 = params.initBiasSigma * params.initBiasSigma
    private var lastT = Long.MIN_VALUE
    private var unwrapped = 0.0
    private var rejects = 0

    // 遅延補償用の履歴（時刻・累積回転量）
    private val histT = LongArray(HIST)
    private val histU = DoubleArray(HIST)
    private var histHead = 0
    private var histCount = 0

    val headingDeg: Double get() = Geo.normalizeDeg(psi * Geo.RAD)
    val sigmaDeg: Double get() = sqrt(p00.coerceAtLeast(0.0)) * Geo.RAD
    val biasDegPerSec: Double get() = bias * Geo.RAD
    val biasSigmaDegPerSec: Double get() = sqrt(p11.coerceAtLeast(0.0)) * Geo.RAD

    fun reset() {
        initialized = false
        psi = 0.0
        p00 = 0.0
        p01 = 0.0
        // バイアス推定は取付が変わらない限り有効なので残す
        p11 = maxOf(p11, (params.initBiasSigma * 0.5).let { it * it })
        rejects = 0
        lastSource = Source.NONE
    }

    fun initialize(headingRad: Double, sigmaRad: Double, tNanos: Long, source: Source) {
        psi = Geo.wrap2Pi(headingRad)
        p00 = sigmaRad * sigmaRad
        p01 = 0.0
        initialized = true
        rejects = 0
        lastSource = source
        lastAbsoluteUpdateNanos = tNanos
        if (lastT == Long.MIN_VALUE) lastT = tNanos
    }

    /**
     * ジャイロによる予測。
     * @param rateRad 方位変化率の生測定値 [rad/s]（時計回り正、バイアス未補正）
     */
    fun predict(rateRad: Double, tNanos: Long) {
        if (lastT == Long.MIN_VALUE || tNanos <= lastT) {
            lastT = tNanos
            return
        }
        val dt = (tNanos - lastT) * 1e-9
        lastT = tNanos
        if (dt > 0.5) return // センサー欠落時は積分しない
        val w = rateRad - bias
        unwrapped += w * dt
        recordHistory(tNanos)
        if (!initialized) return
        psi = Geo.wrap2Pi(psi + w * dt)
        val sf = params.scaleErr * abs(w) * dt
        val q00 = params.arw * params.arw * dt + sf * sf
        val q11 = params.biasRw * params.biasRw * dt
        val n00 = p00 - 2 * dt * p01 + dt * dt * p11 + q00
        val n01 = p01 - dt * p11
        p00 = n00
        p01 = n01
        p11 += q11
    }

    /** 停車中など回転していない時刻の積分（バイアスのみで予測、角度は固定） */
    fun holdStill(tNanos: Long) {
        if (lastT != Long.MIN_VALUE && tNanos > lastT) {
            val dt = (tNanos - lastT) * 1e-9
            if (dt <= 0.5) p11 += params.biasRw * params.biasRw * dt
        }
        lastT = tNanos
        recordHistory(tNanos)
    }

    /**
     * 絶対方位の観測で更新する。
     * @param measTimeNanos 観測時刻（GPS は測位時刻）。現在より過去なら回転量を差し引いて比較する。
     * @param gateSigma ゲート（σ単位）。0 以下で無効。
     * @return 採用されたら true
     */
    fun updateAngle(zRad: Double, sigmaRad: Double, measTimeNanos: Long, source: Source, gateSigma: Double = 3.5): Boolean {
        if (!initialized) {
            initialize(zRad, sigmaRad, measTimeNanos, source)
            return true
        }
        val rotSince = unwrapped - unwrappedAt(measTimeNanos)
        val psiAtMeas = psi - rotSince
        val y = Geo.wrapPi(zRad - psiAtMeas)
        val r = sigmaRad * sigmaRad
        val s = p00 + r
        if (gateSigma > 0 && y * y > gateSigma * gateSigma * s) {
            rejects++
            if (source == Source.GPS && rejects >= params.maxConsecutiveRejects) {
                // 方位が大きくずれた（取付変更・長時間の推測航法）→ GPS で再初期化
                initialize(zRad + rotSince, sigmaRad, measTimeNanos, source)
                return true
            }
            return false
        }
        rejects = 0
        val k0 = p00 / s
        val k1 = p01 / s
        psi = Geo.wrap2Pi(psi + k0 * y)
        bias = (bias + k1 * y).coerceIn(-params.maxBiasAbs, params.maxBiasAbs)
        val n00 = (1 - k0) * p00
        val n01 = (1 - k0) * p01
        val n11 = p11 - k1 * p01
        p00 = n00
        p01 = n01
        p11 = n11.coerceAtLeast(1e-12)
        lastSource = source
        lastAbsoluteUpdateNanos = measTimeNanos
        return true
    }

    /** 停車中: 平均の生角速度 = バイアス として観測更新 */
    fun updateBias(meanRawRateRad: Double, sigmaRad: Double) {
        val r = sigmaRad * sigmaRad
        val s = p11 + r
        val y = meanRawRateRad - bias
        val k0 = p01 / s
        val k1 = p11 / s
        if (initialized) psi = Geo.wrap2Pi(psi + k0 * y)
        bias = (bias + k1 * y).coerceIn(-params.maxBiasAbs, params.maxBiasAbs)
        val n00 = p00 - k0 * p01
        val n01 = p01 - k0 * p11
        val n11 = (1 - k1) * p11
        p00 = n00.coerceAtLeast(0.0)
        p01 = n01
        p11 = n11.coerceAtLeast(1e-12)
    }

    /** スマホを動かした等で方位の信頼度を下げる */
    fun inflate(extraSigmaRad: Double) {
        p00 += extraSigmaRad * extraSigmaRad
    }

    /** 角速度の入力元（スマホ⇔車両のジャイロ）を切り替えたときにバイアス推定をやり直す */
    fun resetBias() {
        bias = 0.0
        p01 = 0.0
        p11 = params.initBiasSigma * params.initBiasSigma
    }

    /** t 時点から現在までのジャイロ回転量 [rad]（補正後の値） */
    fun rotationSince(tNanos: Long): Double = unwrapped - unwrappedAt(tNanos)

    private fun recordHistory(t: Long) {
        histT[histHead] = t
        histU[histHead] = unwrapped
        histHead = (histHead + 1) % HIST
        if (histCount < HIST) histCount++
    }

    private fun unwrappedAt(t: Long): Double {
        if (histCount == 0) return unwrapped
        // 新しい順に探索
        var idx = (histHead - 1 + HIST) % HIST
        var newerT = Long.MAX_VALUE
        var newerU = unwrapped
        for (k in 0 until histCount) {
            val ht = histT[idx]
            val hu = histU[idx]
            if (ht <= t) {
                if (newerT == Long.MAX_VALUE || newerT == ht) return hu
                val f = (t - ht).toDouble() / (newerT - ht).toDouble()
                return hu + (newerU - hu) * f
            }
            newerT = ht
            newerU = hu
            idx = (idx - 1 + HIST) % HIST
        }
        return newerU // 履歴より古い → 最古の値
    }

    private companion object {
        const val HIST = 1024
    }
}

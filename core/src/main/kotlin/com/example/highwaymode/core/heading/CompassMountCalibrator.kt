package com.example.highwaymode.core.heading

import com.example.highwaymode.core.geo.Geo
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * コンパス（地磁気）方位 → 車両方位の変換。
 *
 * スマホのコンパスが示すのは「スマホのある軸」の方位であり、車両の進行方向とは
 * 取り付け角度ぶんずれる。GPS 方位が信頼できる直進走行中に
 *   offset = GPS方位 − 軸のコンパス方位
 * を円周平均で学習し（指数忘却）、トンネル内・低速時の方位推定に使う。
 * 学習前は一般的な取り付け（画面が運転者向き＝背面が前方）を弱い事前分布として使う。
 */
class CompassMountCalibrator(private val tauSec: Double = 300.0) {

    companion object {
        /** スマホ背面（-Z 軸）。ダッシュボード等に立てて設置した場合 */
        const val AXIS_BACK = 0
        /** スマホ上端（+Y 軸）。平置きの場合 */
        const val AXIS_TOP = 1
        const val AXIS_UNKNOWN = -1
    }

    var axisId = AXIS_UNKNOWN
        private set
    private var sumC = 0.0
    private var sumS = 0.0
    private var sumW = 0.0
    private var lastT = Long.MIN_VALUE

    fun reset(newAxisId: Int) {
        axisId = newAxisId
        sumC = 0.0
        sumS = 0.0
        sumW = 0.0
        lastT = Long.MIN_VALUE
    }

    fun addSample(tNanos: Long, gpsCourseDeg: Double, axisHeadingDeg: Double, weight: Double = 1.0) {
        if (lastT != Long.MIN_VALUE && tNanos > lastT) {
            val decay = exp(-(tNanos - lastT) * 1e-9 / tauSec)
            sumC *= decay
            sumS *= decay
            sumW *= decay
        }
        lastT = tNanos
        val d = Geo.diffDeg(gpsCourseDeg, axisHeadingDeg) * Geo.DEG
        sumC += weight * cos(d)
        sumS += weight * sin(d)
        sumW += weight
    }

    val sampleWeight: Double get() = sumW

    /** 学習済みオフセット [deg] */
    val offsetDeg: Double get() = atan2(sumS, sumC) * Geo.RAD

    /** 円周標準偏差 [deg] */
    val circularStdDeg: Double
        get() {
            if (sumW <= 0) return 180.0
            val r = (sqrt(sumC * sumC + sumS * sumS) / sumW).coerceIn(1e-9, 1.0)
            return sqrt(-2.0 * ln(r)) * Geo.RAD
        }

    val calibrated: Boolean get() = sumW >= 20.0 && circularStdDeg <= 10.0

    /**
     * 軸のコンパス方位から車両方位とその不確かさ [deg] を返す。使えない場合 null。
     */
    fun carHeading(axisHeadingDeg: Double): Pair<Double, Double>? {
        if (calibrated) {
            val sigma = max(circularStdDeg, 5.0) + 4.0
            return Geo.normalizeDeg(axisHeadingDeg + offsetDeg) to sigma
        }
        return when (axisId) {
            AXIS_BACK -> Geo.normalizeDeg(axisHeadingDeg) to 35.0
            AXIS_TOP -> Geo.normalizeDeg(axisHeadingDeg) to 45.0
            else -> null
        }
    }
}

package com.example.highwaymode.core.audio

import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 帯ごとの強さ（[BandAnalyzer]）を、スペアナのバーの高さ（0..1）にする（v2.15。考え方は v2.10〜v2.13 と同じ）。
 *
 * 高さの付け方:
 *  - 帯の強さ [dB] を隣の帯・時間で軽くならす
 *  - 「帯の最近の平均（上がるときは速く、下がるときはゆっくり追う）」と「全体の基準（最近いちばん強い帯）」の差を縮めて（周波数ごとの形）、
 *    「いまの値と平均の差」を広げて（拍・音の出だし）足す。ずっと鳴っている音は中ほど、拍で上へ、合間で下へ
 *  - 基準の [SHOW_BELOW_DB] 下〜[SHOW_ABOVE_DB] 上を 0〜[TOP_LEVEL] に写し、それより上のとても強い拍だけ天井まで
 *  - 曲の強弱（音量が測れる端末だけ）を掛ける。音の無い帯は消す
 *
 * v2.15 で変えたところ（入力が、にじみの無い・くずれない解析になったので）:
 *  - **基準よりずっと弱い帯は出さない**（[SHAPE_KNEE_DB] より下は急に下げる）。これまでは基準の 90 dB 下まで少しずつ見えていたので、
 *    本来その音域に無い小さな成分（Android の正規化が作る成分・倍音のすそ）まで、中ほどの高さで動いていた
 *  - **全体がそろって下がったときは戻す**（下の [commonDip]）。Android の Visualizer は、音声の処理 1 回分（約 10〜40ms）ごとに
 *    最大値をそろえる（正規化）。大きな音（バスドラム）が鳴ると、その間だけ、ほかの全部の帯が同じ量だけ小さく読める。
 *    にじみが無くなったぶん、そのままだと「ドラムが鳴るたびに、歌や和音の帯が沈む」のが目立つ
 *  - 高音の持ち上げは、帯の幅が音の高さに比例して広がるぶん（1 オクターブで 3 dB）を見込んで小さくした
 *
 * 数字の合わせ方: 楽器ごとに分けた試験用の音（バスドラム・ベース・歌・ハイハット・スネア・和音）と、それを混ぜた曲、
 * v2.13 の調整に使った録画の音を、v2.13 の計算と新しい計算の両方に通して、平均の高さ・一番上の段にいる時間・
 * 上がる／下がる速さ・動きの量がほぼ同じになるようにした（作業用フォルダ。リポジトリには入れていない）。
 */
class SpectrumMapper(private val an: BandAnalyzer) {

    companion object {
        // ---- 無音の判定
        /** 波形の最大（8bit）がこれ未満なら無音 */
        const val PEAK_GATE = 3
        /** 曲の音量の基準からこれ以上小さい音は無音とみなす [dB] */
        const val SILENCE_BELOW_REF_DB = 60f
        /** これより小さい音 [dBFS] は完全な無音（音声データが 0） */
        const val DIGITAL_SILENCE_DB = -95f

        // ---- 周波数ごとの補正
        /** 高音の持ち上げ [dB/オクターブ]（[TILT_FROM_HZ] より上。音楽は高音ほど小さい） */
        const val TILT_DB_PER_OCT = 2f
        const val TILT_FROM_HZ = 1000f
        /** 持ち上げの上限 [dB] */
        const val TILT_MAX_DB = 6f

        // ---- 音の無い帯を消す
        /** 8bit の波形の細かさで決まる雑音（帯の中）より、これだけ上 [dB] から出し始める */
        const val GATE_MARGIN_DB = 8f
        /** そこからこれだけ上 [dB] で元の高さ */
        const val GATE_DB = 6f
        /**
         * 全体の基準（最近いちばん強い帯）よりこれ以上弱い帯 [dB] は消す。ほかの音にかき消されて聞こえない大きさ。
         * 基準の [CAP_FROM_DB] 下からここまでは、上がれる高さに上限を付ける（[CAP_FROM_DB] 下で 1、ここで 0）。
         * ふだん音の無い帯は、急に鳴り始めた音の一瞬の広がり（パチッという成分）だけで「平均からの差」が大きくなり、
         * 上限が無いと、聞こえないほど小さいのに高く上がってしまう
         */
        const val GATE_BELOW_TOP_DB = 46f
        const val CAP_FROM_DB = 25f

        // ---- ならし（読み取り 25ms あたりの割合。読み取りの間隔が変わっても同じ速さになるよう換算する）
        const val SMOOTH_ATTACK = 0.9f
        const val SMOOTH_RELEASE = 0.6f
        /** 隣の帯とならす割合（両隣それぞれ） */
        const val SMOOTH_NEIGHBOR = 0.15f
        /** 帯ごとの「最近の平均」の時定数 [秒]（下がるとき・上がるとき） */
        const val BAND_AVG_SEC = 0.6f
        const val BAND_AVG_UP_SEC = 0.25f
        /** 全体の基準（最近いちばん強い帯）が下がる速さ [dB/秒] */
        const val TOP_RELEASE_DB_PER_SEC = 10f

        // ---- 高さ
        /** 周波数ごとの形（平均と基準の差）を縮める割合 */
        const val SPEC_K = 0.35f
        /** 基準よりこれ以上弱い帯 [dB] は、縮めずに [SHAPE_STEEP_K] 倍で下げる（基準の約 40 dB 下で見えなくなる） */
        const val SHAPE_KNEE_DB = 20f
        const val SHAPE_STEEP_K = 1.25f
        /** 平均からの差（拍・音の出だし）を広げる割合。v2.13 は 3.0。にじみ・くずれによる細かな揺れが無くなったぶん大きくした */
        const val DYN_K = 3.4f
        /** 基準より何 dB 下までを表すか（0）と、基準より何 dB 上で [TOP_LEVEL] になるか */
        const val SHOW_BELOW_DB = 32f
        const val SHOW_ABOVE_DB = 10f
        /** 写した位置が 1 のときの高さ。それを超えた分（とても強い拍）だけで、残りの上の部分を [TOP_EXT_DB] かけて使う */
        const val TOP_LEVEL = 0.88f
        const val TOP_EXT_DB = 16f

        // ---- 全体がそろって下がったとき（Android の正規化）に戻す
        /** 短い窓の帯のうち、鳴っている帯がこの数以上あるときだけ判定する */
        const val DIP_MIN_BANDS = 10
        /** そろって下がったとみなす下がり [dB]（鳴っている帯の 6 割がこれ以上下がっている） */
        const val DIP_MIN_DB = 1.5f
        /** 「そろって」の幅 [dB]（下から 2 割目と 6 割目の差がこれ未満） */
        const val DIP_TIGHT_DB = 3f
        /** 戻す量の上限 [dB] */
        const val DIP_MAX_DB = 8f
        /** 長い窓の帯は下がりが遅れて浅く出るので、戻す量もならす（時定数 [秒]。中の窓・長い窓） */
        const val DIP_MID_TAU = 0.020f
        const val DIP_LOW_TAU = 0.040f

        // ---- 曲の中の強弱（静かな部分・盛り上がる部分）
        /** 曲の音量の基準（直近の大きい音）から何 dB 下までを強弱として表すか */
        const val LOUD_RANGE_DB = 18f
        /** 基準が下がる速さ [dB/秒] */
        const val LOUD_RELEASE_DB_PER_SEC = 1.0f
        /** いちばん静かな部分でのバーの高さの割合 */
        const val LOUD_MIN = 0.35f
        /** 強弱をならす割合（25ms あたり。上がる・下がる） */
        const val LOUD_ATTACK = 0.6f
        const val LOUD_RELEASE = 0.25f
    }

    private val n = an.bands
    private val tilt = FloatArray(n) {
        (TILT_DB_PER_OCT * (ln(an.centers[it] / TILT_FROM_HZ) / ln(2f))).coerceIn(0f, TILT_MAX_DB)
    }
    /** 8bit の波形の細かさで決まる雑音の、帯の中の強さ [dB]（1/12 を、帯の幅の割合だけ） */
    private val quantDb = FloatArray(n) {
        10f * log10((an.edges[it + 1] - an.edges[it]) / (an.sampleRate / 2f) / 12f)
    }
    private val db = FloatArray(n)
    private val dbF = FloatArray(n)
    private val dbS = FloatArray(n)
    private val dbAvg = FloatArray(n)
    private val gate = FloatArray(n)
    private val dev = FloatArray(n)
    private val sortBuf = FloatArray(n)
    private var smoothed = false
    private var topRef = -100f
    private var loudS = -1f
    private var loudRef = -100f
    private var dipMid = 0f
    private var dipLow = 0f

    /** 結果（0..1）。呼ぶたびに同じ配列を書き換える */
    val out = FloatArray(n)

    /** いまの曲の相対的な音量（0..1。測れない端末は -1） */
    var loudness = 0f
        private set

    /** 最後の [map] が無音だったか */
    var silent = true
        private set

    /** 最後の [map] で、そろって下がったぶんとして戻した量 [dB]（0 以下。確認用） */
    var lastDip = 0f
        private set

    /** 強弱の基準を捨てて、次に届く音を新しい基準にする（音量を変えたとき） */
    fun resetLoudness() {
        loudRef = -100f
    }

    /**
     * @param power 帯ごとの強さ（[BandAnalyzer.compute]）
     * @param rmsDb 再生中の音の大きさ [dBFS]（測れない端末は null）
     * @param peak 最新の波形の最大（8bit の絶対値）
     * @param dt 前回からの時間 [秒]
     * @return バーの高さ（0..1。[out]）
     */
    fun map(power: FloatArray, rmsDb: Float?, peak: Int, dt: Float): FloatArray {
        silent = peak < PEAK_GATE || (rmsDb != null && (rmsDb < DIGITAL_SILENCE_DB || rmsDb < loudRef - SILENCE_BELOW_REF_DB))
        if (silent) {
            out.fill(0f)
            loudness = 0f
            // ならした値は捨てる（次に鳴り始めたら、その音から始める）
            smoothed = false
            topRef = -100f
            loudS = -1f
            dipMid = 0f
            dipLow = 0f
            lastDip = 0f
            return out
        }
        for (b in 0 until n) db[b] = 10f * log10(power[b] + 1e-12f) + tilt[b]
        // ---- ならす（隣の帯・時間）
        val k = (dt / 0.025f).coerceIn(0.2f, 4f)
        val att = 1f - (1f - SMOOTH_ATTACK).pow(k)
        val rel = 1f - (1f - SMOOTH_RELEASE).pow(k)
        for (b in 0 until n) {
            val l = db[if (b > 0) b - 1 else b]
            val r = db[if (b < n - 1) b + 1 else b]
            dbF[b] = db[b] * (1f - 2f * SMOOTH_NEIGHBOR) + (l + r) * SMOOTH_NEIGHBOR
        }
        val avgA = min(1f, dt / BAND_AVG_SEC)
        val avgUpA = min(1f, dt / BAND_AVG_UP_SEC)
        var frameMax = -100f
        for (b in 0 until n) {
            dbS[b] = when {
                !smoothed -> dbF[b]
                dbF[b] > dbS[b] -> dbS[b] + (dbF[b] - dbS[b]) * att
                else -> dbS[b] + (dbF[b] - dbS[b]) * rel
            }
            dbAvg[b] = if (!smoothed) dbS[b] else dbAvg[b] + (dbS[b] - dbAvg[b]) * (if (dbS[b] > dbAvg[b]) avgUpA else avgA)
            if (dbS[b] > frameMax) frameMax = dbS[b]
            // 音の無い帯（8bit の細かさの雑音しか無い）は消す
            gate[b] = ((dbS[b] - tilt[b] - quantDb[b] - GATE_MARGIN_DB) / GATE_DB).coerceIn(0f, 1f)
            dev[b] = dbS[b] - dbAvg[b]
        }
        smoothed = true
        topRef = if (frameMax > topRef) frameMax else max(frameMax, topRef - TOP_RELEASE_DB_PER_SEC * dt)

        // ---- 全体がそろって下がったぶん（Android の正規化）を戻す
        val dip = commonDip()
        lastDip = dip
        dipMid += (dip - dipMid) * min(1f, dt / DIP_MID_TAU)
        dipLow += (dip - dipLow) * min(1f, dt / DIP_LOW_TAU)

        // ---- 曲の強弱（音量が測れる端末だけ）: 静かな部分は全体を低く
        val loudFactor = if (rmsDb != null) {
            // 曲の音量の基準: 大きい音には即追従、静かになってもゆっくりしか下げない
            loudRef = if (rmsDb > loudRef) rmsDb else loudRef - LOUD_RELEASE_DB_PER_SEC * dt
            val loudNow = ((rmsDb - (loudRef - LOUD_RANGE_DB)) / LOUD_RANGE_DB).coerceIn(0f, 1f)
            val la = 1f - (1f - LOUD_ATTACK).pow(k)
            val lr = 1f - (1f - LOUD_RELEASE).pow(k)
            loudS = if (loudS < 0f) loudNow else loudS + (loudNow - loudS) * (if (loudNow > loudS) la else lr)
            loudness = loudS
            LOUD_MIN + (1f - LOUD_MIN) * loudS
        } else {
            loudness = -1f
            1f
        }

        // ---- 高さ
        for (b in 0 until n) {
            val below = dbAvg[b] - topRef                    // 0 以下
            val shape = if (below > -SHAPE_KNEE_DB) below * SPEC_K
            else -SHAPE_KNEE_DB * SPEC_K + (below + SHAPE_KNEE_DB) * SHAPE_STEEP_K
            val d = dev[b] - when (an.resolution[b]) {
                BandAnalyzer.RES_HIGH -> dip
                BandAnalyzer.RES_MID -> dipMid
                else -> dipLow
            }
            val p = shape + d * DYN_K
            val x = ((p + SHOW_BELOW_DB) / (SHOW_BELOW_DB + SHOW_ABOVE_DB)).coerceIn(0f, 1f)
            val over = ((p - SHOW_ABOVE_DB) / TOP_EXT_DB).coerceIn(0f, 1f)
            // 基準よりずっと弱い帯は、高く上がれない
            val cap = ((dbS[b] - (topRef - GATE_BELOW_TOP_DB)) / (GATE_BELOW_TOP_DB - CAP_FROM_DB)).coerceIn(0f, 1f)
            out[b] = min((x * TOP_LEVEL + over * (1f - TOP_LEVEL)) * gate[b], cap) * loudFactor
        }
        return out
    }

    /**
     * 全体がそろって下がった量 [dB]（0 以下。そろっていなければ 0）。
     *
     * 短い窓の帯のうち鳴っている帯について、「いまの値 − 最近の平均」を小さい順に並べる。
     * 下から 6 割目まで [DIP_MIN_DB] 以上下がっていて、下から 2 割目との差が [DIP_TIGHT_DB] 未満（＝大半の帯が同じ量だけ下がった）なら、
     * Android の正規化で全体が小さく読めただけとみなして、その量（下から 4 割目）を返す。
     * 楽器が止まって帯が消えていくとき（帯ごとに下がり方が違う）・広い音域の音が鳴ったとき（上がる）は、そろわないので 0。
     */
    private fun commonDip(): Float {
        var m = 0
        for (b in 0 until n) {
            if (an.resolution[b] == BandAnalyzer.RES_HIGH && gate[b] > 0.5f) sortBuf[m++] = dev[b]
        }
        if (m < DIP_MIN_BANDS) return 0f
        // 数が少ないので、その場で並べ替える（毎回の確保をしない）
        for (i in 1 until m) {
            val v = sortBuf[i]
            var j = i - 1
            while (j >= 0 && sortBuf[j] > v) {
                sortBuf[j + 1] = sortBuf[j]
                j--
            }
            sortBuf[j + 1] = v
        }
        val q20 = sortBuf[(0.2f * (m - 1)).toInt()]
        val q60 = sortBuf[(0.6f * (m - 1)).toInt()]
        if (q60 >= -DIP_MIN_DB || q60 - q20 >= DIP_TIGHT_DB) return 0f
        return max(sortBuf[(0.4f * (m - 1)).toInt()], -DIP_MAX_DB)
    }
}

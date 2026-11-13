package com.example.highwaymode.core.audio

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 帯ごとの強さ（[BandAnalyzer]）を、スペアナのバーの高さ（0..1）にする（v2.15。高さの付け方は v2.16 で作り直した）。
 *
 * 高さの付け方:
 *  - 帯の強さ [dB] を隣の帯・時間で軽くならす
 *  - 「帯の最近の平均（上がるときは速く、下がるときはゆっくり追う）」と「全体の基準（最近いちばん強い帯）」の差を縮めて（周波数ごとの形）、
 *    「いまの値と平均の差」を広げて（拍・音の出だし）足す。ずっと鳴っている音は中ほど、拍で上へ、合間で下へ
 *  - 基準の [SHOW_BELOW_DB] 下〜[SHOW_ABOVE_DB] 上を 0〜[TOP_LEVEL] に写し、それより上のとても強い拍だけ天井まで
 *  - 曲の音量（音量が測れる端末だけ）を掛ける。音の無い帯は消す
 *
 * v2.15 で変えたところ（入力が、にじみの無い・くずれない解析になったので）:
 *  - **基準よりずっと弱い帯は出さない**（[SHAPE_KNEE_DB] より下は急に下げる）
 *  - **全体がそろって下がったときは戻す**（下の [commonDip]）。Android の Visualizer は、音声の処理 1 回分（約 10〜40ms）ごとに
 *    最大値をそろえる（正規化）。大きな音（バスドラム）が鳴ると、その間だけ、ほかの全部の帯が同じ量だけ小さく読める
 *  - 高音の持ち上げは、帯の幅が音の高さに比例して広がるぶん（1 オクターブで 3 dB）を見込んで小さくした
 *
 * v2.16 で変えたところ。利用者の報告「それほど大きい音でないのに上に張り付く」「曲の静かな部分と盛り上がる部分で高さが変わらない」。
 * 利用者の画面録画（横画面の LED 49 本 × 18 段）を 1 コマずつ測ると、v2.15 は平均の高さ 0.56〜0.61（v2.13 は 0.455）、
 * 上の 2 段にいる時間 8〜12%（左端の低音の 6 本は 14〜22%。バスドラムのたびに一緒に天井まで跳ね上がる）、
 * 前奏 0.44・A メロ 0.56・サビ 0.61 だった。実際の曲（スマホの中の音）は、v2.15 の調整に使った音（スピーカーをマイクで拾った音・
 * 合成した音）より、拍の跳ね上がりがずっと大きい。端末・曲で入力の性質が変わっても同じ見え方になるよう、次の形にした:
 *  - **全体の高さを自動でそろえる**（[AUTO_TARGET_DB]）: 鳴っている帯の高さの平均が決まった位置に来るよう、全体をゆっくり上下させる。
 *    上の 2 段に届いた帯が多いときは、その分だけ全体を下げる（[HIT_PULL_DB_PER_SEC]）
 *  - **上向きの振れを頭打ちにする**（[DYN_UP_MAX_DB]）: 拍で跳ね上がる量は、どんなに強くてもここまで。
 *    ふつうの拍は 8〜9 割の高さ、天井はとても強い拍だけ
 *  - **周波数ごとの形をはっきり出す**（[SPEC_K] 0.35 → 0.6）: 強い音域は高く、弱い音域は低く（聞こえる音と合うように）
 *  - **曲の音量を、絶対的な大きさで反映する**（下の「曲の音量」）: v2.15 までは「最近いちばん大きかった音」との差（18 dB 幅）で、
 *    静かな部分が続くとすぐ基準が下がって元の高さに戻っていた。いまは「音の最大値（音量つまみの位置の目安）からの実効値の差」
 *    （＝音の詰まり具合。音量つまみによらない）を、ゆっくりしか動かない基準と比べる。サビは元の高さ、A メロは 7 割前後、静かな前奏は 5 割以下
 *
 * 数字の合わせ方: 利用者の録画の音から、実機の表示の統計（平均・上の 2 段にいる時間・左端・上下の広がり）に合う入力を作り
 * （上向きの振れ 1.4〜2.2 倍・低音に拍ごとの山）、それと、楽器ごとに分けた試験用の音を混ぜた曲とで、
 * 平均の高さ 0.39〜0.48・上の 2 段にいる時間 1% 以下になるようにした（作業用フォルダ。リポジトリには入れていない）。
 */
class SpectrumMapper(private val an: BandAnalyzer) {

    companion object {
        // ---- 無音の判定
        /** 波形の最大（8bit）がこれ未満なら無音 */
        const val PEAK_GATE = 3
        /** 音の最大値の記憶（[PEAK_RELEASE_DB_PER_SEC]）からこれ以上小さい音は無音とみなす [dB] */
        const val SILENCE_BELOW_REF_DB = 70f
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
        /** 周波数ごとの形（平均と基準の差）を縮める割合。v2.15 は 0.35（強い音域と弱い音域の差が小さく、どの帯も同じ高さで動いて見えた） */
        const val SPEC_K = 0.6f
        /** 基準よりこれ以上弱い帯 [dB] は、縮めずに [SHAPE_STEEP_K] 倍で下げる（基準の約 40 dB 下で見えなくなる） */
        const val SHAPE_KNEE_DB = 20f
        const val SHAPE_STEEP_K = 1.25f
        /** 平均からの差（拍・音の出だし）を広げる割合 */
        const val DYN_K = 3.0f
        /**
         * 上向きの振れ（広げたあと）の上限 [dB]。なめらかに頭打ちにする（x → 上限 ×（1 − e^(−x/上限)））。
         * 実際の曲のバスドラムは帯の平均より 15 dB 以上跳ね上がり、上限が無いと拍のたびに天井に届く（v2.15）
         */
        const val DYN_UP_MAX_DB = 18f
        /** 基準より何 dB 下までを表すか（0）と、基準より何 dB 上で [TOP_LEVEL] になるか */
        const val SHOW_BELOW_DB = 32f
        const val SHOW_ABOVE_DB = 10f
        /** 写した位置が 1 のときの高さ。それを超えた分（とても強い拍）だけで、残りの上の部分を [TOP_EXT_DB] かけて使う */
        const val TOP_LEVEL = 0.88f
        const val TOP_EXT_DB = 16f

        // ---- 全体の高さを自動でそろえる（v2.16）
        /** 鳴っている帯の位置（基準からの dB。[SHOW_BELOW_DB] 下〜[SHOW_ABOVE_DB] 上で切った値）の平均を、ここに合わせる */
        const val AUTO_TARGET_DB = -10f
        /** 合わせる速さ（時定数 [秒]）。曲の中の強弱（拍・小節）より十分ゆっくり */
        const val AUTO_TAU_SEC = 4f
        /** 全体を上下させる量の範囲 [dB] と、最初の値 */
        const val AUTO_MIN_DB = -24f
        const val AUTO_MAX_DB = 10f
        const val AUTO_START_DB = -6f
        /** 鳴っている帯がこの数以上あるときだけ合わせる */
        const val AUTO_MIN_BANDS = 4
        /** 上の 2 段あたり（[TOP_LEVEL] より上の部分の半分）に届いた帯の割合 × これ [dB/秒] だけ、全体を下げる */
        const val HIT_AT = 0.5f
        const val HIT_PULL_DB_PER_SEC = 25f

        // ---- 全体がそろって下がったとき（Android の正規化）に戻す
        /** 短い窓の帯のうち、鳴っている帯がこの数以上あるときだけ判定する */
        const val DIP_MIN_BANDS = 10
        /** そろって下がったとみなす下がり [dB]（鳴っている帯の 6 割がこれ以上下がっている） */
        const val DIP_MIN_DB = 1.5f
        /** 「そろって」の幅 [dB]（下から 2 割目と 6 割目の差がこれ未満） */
        const val DIP_TIGHT_DB = 3f
        /** 戻す量の上限 [dB]（v2.15 は 8。戻しすぎると、その帯が跳ね上がって見える） */
        const val DIP_MAX_DB = 5f
        /** 長い窓の帯は下がりが遅れて浅く出るので、戻す量もならす（時定数 [秒]。中の窓・長い窓） */
        const val DIP_MID_TAU = 0.020f
        const val DIP_LOW_TAU = 0.040f

        // ---- 曲の音量（静かな部分・盛り上がる部分。v2.16 で作り直し）
        /**
         * 音の大きさの目安 = 実効値 [dBFS] − 音の最大値の記憶 [dBFS]（0 以下。音の詰まり具合）。音量つまみの位置によらない。
         * いまどきの曲のサビで −8〜−10、A メロで −12〜−14、静かな前奏で −18 以下。
         * 音の最大値の記憶は、大きい音にはすぐ追従し、下がるのはこの速さ [dB/秒]（曲ごとの音量の差に 1 分ほどで慣れる）
         */
        const val PEAK_RELEASE_DB_PER_SEC = 0.1f
        /** 「元の高さ」にする基準の最初の値 [dB]（いまどきの曲のサビ）。基準は、これより詰まった音が続けば上がる（[LOUD_SLOW_SEC] の平均で） */
        const val LOUD_ANCHOR_DB = -9f
        const val LOUD_SLOW_SEC = 1.5f
        /** 基準の範囲 [dB] */
        const val LOUD_REF_MAX_DB = -7f
        const val LOUD_REF_MIN_DB = -18f
        /** 基準が下がる速さ [dB/秒]。ゆっくり（A メロの間に基準が下がって、サビと同じ高さに戻らないように。30 秒で 0.75 dB） */
        const val LOUD_REF_RELEASE_DB_PER_SEC = 0.025f
        /**
         * 最近いちばん大きかった音（下がる速さ [LOUD_RECENT_RELEASE_DB_PER_SEC]）が、基準より [LOUD_FAR_DB] 以上小さいまま
         * [LOUD_FAR_HOLD_SEC] 続いたら、基準を速く下げる（静かな録音の曲・話し声が、ずっと低いままにならないように）
         */
        const val LOUD_REF_RELEASE_FAR_DB_PER_SEC = 0.12f
        const val LOUD_FAR_DB = 5f
        const val LOUD_FAR_HOLD_SEC = 20f
        const val LOUD_RECENT_RELEASE_DB_PER_SEC = 0.3f
        /** 基準から何 dB 下で、いちばん低く（[LOUD_MIN] 倍）するか。基準の [LOUD_FULL_DB] 下までは元の高さ */
        const val LOUD_RANGE_DB = 8f
        const val LOUD_FULL_DB = 1f
        /** いちばん静かな部分でのバーの高さの割合 */
        const val LOUD_MIN = 0.25f
        /** 強弱をならす割合（25ms あたり。上がる・下がる） */
        const val LOUD_ATTACK = 0.5f
        const val LOUD_RELEASE = 0.3f
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
    private val pos = FloatArray(n)
    private var smoothed = false
    private var topRef = -100f
    private var loudS = -1f
    private var dipMid = 0f
    private var dipLow = 0f
    private var loudSlow = 0f
    private var loudSlowValid = false
    private var loudRecent = -100f
    private var farSec = 0f

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

    /** 全体を上下させている量 [dB]（確認用） */
    var autoDb = AUTO_START_DB
        private set

    /** 音の最大値の記憶 [dBFS]（まだ無ければ -100。確認用） */
    var peakRefDb = -100f
        private set

    /** 「元の高さ」にする基準 [dB]（確認用） */
    var loudRefDb = LOUD_ANCHOR_DB
        private set

    /** いまの音の大きさの目安（実効値 − 音の最大値の記憶）[dB]（測れないときは 0。確認用） */
    var loudNowDb = 0f
        private set

    /** 音の最大値の記憶を捨てて、次に届く音から覚え直す（音量を変えたとき。「元の高さ」の基準は音量によらないので残す） */
    fun resetLoudness() {
        peakRefDb = -100f
    }

    /**
     * @param power 帯ごとの強さ（[BandAnalyzer.compute]）
     * @param rmsDb 再生中の音の実効値 [dBFS]（測れない端末は null）
     * @param peakDb 再生中の音の最大値 [dBFS]（測れない端末は null）
     * @param peak 最新の波形の最大（8bit の絶対値）
     * @param dt 前回からの時間 [秒]
     * @return バーの高さ（0..1。[out]）
     */
    fun map(power: FloatArray, rmsDb: Float?, peakDb: Float?, peak: Int, dt: Float): FloatArray {
        silent = peak < PEAK_GATE ||
            (rmsDb != null && (rmsDb < DIGITAL_SILENCE_DB || (peakRefDb > -90f && rmsDb < peakRefDb - SILENCE_BELOW_REF_DB)))
        if (silent) {
            out.fill(0f)
            loudness = 0f
            // ならした値は捨てる（次に鳴り始めたら、その音から始める）。全体の高さ・音量の基準は持ち続ける
            smoothed = false
            topRef = -100f
            loudS = -1f
            loudSlowValid = false
            farSec = 0f
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

        // ---- 帯ごとの位置（基準からの dB）: 周波数ごとの形 ＋ 拍（上向きは頭打ち）
        var active = 0
        for (b in 0 until n) {
            val below = dbAvg[b] - topRef                    // 0 以下
            val shape = if (below > -SHAPE_KNEE_DB) below * SPEC_K
            else -SHAPE_KNEE_DB * SPEC_K + (below + SHAPE_KNEE_DB) * SHAPE_STEEP_K
            val d = dev[b] - when (an.resolution[b]) {
                BandAnalyzer.RES_HIGH -> dip
                BandAnalyzer.RES_MID -> dipMid
                else -> dipLow
            }
            var e = d * DYN_K
            if (e > 0f) e = DYN_UP_MAX_DB * (1f - exp(-e / DYN_UP_MAX_DB))
            pos[b] = shape + e
            if (gate[b] > 0.5f) active++
        }

        // ---- 全体の高さを自動でそろえる: 鳴っている帯の位置の平均を目標へ。上の 2 段に届いた帯が多ければ下げる
        if (active >= AUTO_MIN_BANDS) {
            var sum = 0f
            for (b in 0 until n) {
                if (gate[b] > 0.5f) sum += (pos[b] + autoDb).coerceIn(-SHOW_BELOW_DB, SHOW_ABOVE_DB)
            }
            autoDb += (AUTO_TARGET_DB - sum / active) * min(1f, dt / AUTO_TAU_SEC)
            var hits = 0
            for (b in 0 until n) {
                if (gate[b] > 0.5f && (pos[b] + autoDb - SHOW_ABOVE_DB) / TOP_EXT_DB >= HIT_AT) hits++
            }
            autoDb -= hits.toFloat() / active * HIT_PULL_DB_PER_SEC * dt
            autoDb = autoDb.coerceIn(AUTO_MIN_DB, AUTO_MAX_DB)
        }

        // ---- 曲の音量（音量が測れる端末だけ）: 静かな部分は全体を低く
        val loudFactor = loudnessFactor(rmsDb, peakDb, k, dt)

        // ---- 高さ
        for (b in 0 until n) {
            val p = pos[b] + autoDb
            val x = ((p + SHOW_BELOW_DB) / (SHOW_BELOW_DB + SHOW_ABOVE_DB)).coerceIn(0f, 1f)
            val over = ((p - SHOW_ABOVE_DB) / TOP_EXT_DB).coerceIn(0f, 1f)
            // 基準よりずっと弱い帯は、高く上がれない
            val cap = ((dbS[b] - (topRef - GATE_BELOW_TOP_DB)) / (GATE_BELOW_TOP_DB - CAP_FROM_DB)).coerceIn(0f, 1f)
            out[b] = min((x * TOP_LEVEL + over * (1f - TOP_LEVEL)) * gate[b], cap) * loudFactor
        }
        return out
    }

    /**
     * 曲の音量から、バーの高さに掛ける割合（[LOUD_MIN]〜1）を決める。
     *
     * Android の Visualizer の波形は、音声の処理 1 回分ごとに最大値をそろえてあり（正規化）、曲の音量は波形からは分からない。
     * 別に測れる「実効値」と「最大値」（Visualizer の MeasurementPeakRms。約 0.5 秒の窓）を使う。
     * 実効値そのもの（dBFS）は音量つまみの位置で変わる端末があるので、最大値の記憶との差（音の詰まり具合）を使う。
     */
    private fun loudnessFactor(rmsDb: Float?, peakDb: Float?, k: Float, dt: Float): Float {
        if (rmsDb == null || peakDb == null) {
            loudness = -1f
            loudNowDb = 0f
            return 1f
        }
        // 音の最大値の記憶: 大きい音にはすぐ追従、下がるのはゆっくり
        if (peakDb > -90f) {
            peakRefDb = if (peakDb > peakRefDb) peakDb else max(peakDb, peakRefDb - PEAK_RELEASE_DB_PER_SEC * dt)
        }
        if (peakRefDb <= -90f || rmsDb <= -90f) {
            loudness = -1f
            loudNowDb = 0f
            return 1f
        }
        val now = min(0f, rmsDb - peakRefDb)
        loudNowDb = now
        loudSlow = if (!loudSlowValid) now else loudSlow + (now - loudSlow) * min(1f, dt / LOUD_SLOW_SEC)
        loudSlowValid = true
        loudRecent = if (loudSlow > loudRecent) loudSlow else loudRecent - LOUD_RECENT_RELEASE_DB_PER_SEC * dt
        farSec = if (loudRecent < loudRefDb - LOUD_FAR_DB) farSec + dt else 0f
        loudRefDb = if (loudSlow > loudRefDb) min(loudSlow, LOUD_REF_MAX_DB)
        else max(loudRefDb - (if (farSec > LOUD_FAR_HOLD_SEC) LOUD_REF_RELEASE_FAR_DB_PER_SEC else LOUD_REF_RELEASE_DB_PER_SEC) * dt, LOUD_REF_MIN_DB)
        val loudNow = ((now - (loudRefDb - LOUD_RANGE_DB)) / (LOUD_RANGE_DB - LOUD_FULL_DB)).coerceIn(0f, 1f)
        val la = 1f - (1f - LOUD_ATTACK).pow(k)
        val lr = 1f - (1f - LOUD_RELEASE).pow(k)
        loudS = if (loudS < 0f) loudNow else loudS + (loudNow - loudS) * (if (loudNow > loudS) la else lr)
        loudness = loudS
        return LOUD_MIN + (1f - LOUD_MIN) * loudS
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

package com.example.highwaymode.core.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 波形の履歴（[WaveStitcher]）から、スペアナの帯ごとの強さを出す（v2.15）。
 *
 * v2.14 までは Android の Visualizer の FFT（1024 サンプル・窓なし・結果は 1 バイト）をそのまま使っていたので、
 *  - 周波数の細かさが約 47Hz で、低い帯は 1 帯 = FFT の 1 本にするしかなく、画面の左 6 割が 47Hz〜1.4kHz を等間隔に並べた形になっていた
 *    （低音は左端の数本だけ、歌や楽器が画面の大半）
 *  - 窓をかけない FFT は、強い音が離れた帯まで広くにじむ。大きい値は 1 バイトに収めるために半分にされ続けて、形がくずれる
 *    （ベースだけの音でも、画面の左 7 割が光っていた）
 * ため、聞こえている音と、光る場所が合っていなかった（利用者の報告）。
 *
 * ここでは波形から自前で FFT する:
 *  - **周波数の目盛り**: 35Hz〜16kHz を、ほぼ「音の高さ（対数）」で等分する。画面の左 1/4 が低音（〜約 220Hz）、次の 1/4 が中低音（〜約 900Hz）、
 *    次が中高音（〜約 3.8kHz）、右 1/4 が高音。いちばん低いところだけは、帯の幅を解析の細かさ（約 12Hz）より狭くしない
 *  - **周波数に応じた長さの窓**: 低音は長い窓（4096 サンプル = 約 85ms。細かさ約 12Hz）、中低音は 2048、それより上は 1024（約 21ms。速い音に追従）。
 *    長い窓は、新しい側を速く落とす左右非対称の形にして、音の出だしへの反応を速くする
 *  - **窓をかける**（Hann）ので、強い音が離れた帯ににじまない。計算は小数（float）なので、大きい値で形がくずれない
 *
 * 帯の値は「その帯に入る成分の二乗平均」（8bit の目盛りの 2 乗。窓の長さによらない）。Android に依存しない。
 */
class BandAnalyzer(val sampleRate: Float, val bands: Int = 48) {

    companion object {
        /** いちばん低い帯の下端 [Hz] */
        const val F_MIN = 35f
        /** いちばん高い帯の上端 [Hz]（サンプリング周波数の半分の 0.9 倍まで） */
        const val F_MAX = 16000f
        const val N_LOW = 4096
        const val N_MID = 2048
        const val N_HIGH = 1024
        /** 長い窓の、新しい側を落とす長さ（サンプル） */
        const val LOW_FALL = 1024
        /** 短い窓は、少しずらした 2 回の大きい方を使う（窓の端に来た短い音を取りこぼさない）。ずらす量 [秒] */
        const val STAGGER_SEC = 0.00625f

        const val RES_LOW = 0
        const val RES_MID = 1
        const val RES_HIGH = 2

        /**
         * 帯の境目 [Hz]。幅は「下端の周波数 × 一定の割合」（対数で等分）だが、[minBw] より狭くしない。
         * 割合は、[bands] 個でちょうど [fMax] に届くように決める
         */
        fun bandEdges(bands: Int, fMin: Float, fMax: Float, minBw: Float): FloatArray {
            fun build(c: Double): DoubleArray {
                val e = DoubleArray(bands + 1)
                e[0] = fMin.toDouble()
                for (i in 1..bands) e[i] = e[i - 1] + max(minBw.toDouble(), c * e[i - 1])
                return e
            }
            var lo = 0.001
            var hi = 2.0
            repeat(80) {
                val c = (lo + hi) / 2
                if (build(c)[bands] < fMax) lo = c else hi = c
            }
            val e = build((lo + hi) / 2)
            return FloatArray(bands + 1) { e[it].toFloat() }
        }

        /** Hann 窓（長さ m。最後が最新） */
        fun hann(m: Int): FloatArray = FloatArray(m) {
            val s = sin(PI * (it + 0.5) / m)
            (s * s).toFloat()
        }

        /** 左右非対称の窓: 古い側 m - fall サンプルでゆっくり上がり、新しい側 fall サンプルで速く下がる */
        fun asymmetric(m: Int, fall: Int): FloatArray {
            val rise = m - fall
            return FloatArray(m) {
                if (it < rise) {
                    val s = sin(0.5 * PI * (it + 0.5) / rise)
                    (s * s).toFloat()
                } else {
                    val c = cos(0.5 * PI * (it - rise + 0.5) / fall)
                    (c * c).toFloat()
                }
            }
        }
    }

    /** 帯の境目 [Hz]（bands + 1 個） */
    val edges: FloatArray = bandEdges(bands, F_MIN, min(F_MAX, sampleRate * 0.45f), sampleRate / N_LOW)

    /** 帯の中心（境目の相乗平均）[Hz] */
    val centers = FloatArray(bands) { sqrt(edges[it] * edges[it + 1]) }

    /** 帯ごとの窓の種類（[RES_LOW] / [RES_MID] / [RES_HIGH]） */
    val resolution = IntArray(bands) {
        val bw = edges[it + 1] - edges[it]
        when {
            bw < sampleRate / N_MID -> RES_LOW
            bw < sampleRate / N_HIGH -> RES_MID
            else -> RES_HIGH
        }
    }

    // 長い窓は、窓をかけたあと 4 つ（2 つ）ずつ足して 1024 点にしてから FFT する（使うのは低い周波数の本だけなので、結果はほぼ同じ。
    // 4096 点・2048 点の FFT をそのまま計算するより、ずっと軽い）
    private val low = Res(asymmetric(N_LOW, LOW_FALL), N_HIGH, N_LOW / N_HIGH)
    private val mid = Res(hann(N_MID), N_HIGH, N_MID / N_HIGH)
    private val high = Res(hann(N_HIGH), N_HIGH, 1)
    private val highB = FloatArray(N_HIGH / 2 + 1)
    private val stagger = (STAGGER_SEC * sampleRate).toInt()

    // 帯 → FFT の本と重み（本 k は (k-0.5)df〜(k+0.5)df を受け持つ。帯にかかる割合が重み）
    private val wStart = IntArray(bands + 1)
    private val wBin: IntArray
    private val wFrac: FloatArray

    init {
        val bins = ArrayList<Int>()
        val fracs = ArrayList<Float>()
        for (b in 0 until bands) {
            wStart[b] = bins.size
            val r = res(resolution[b])
            val df = sampleRate / r.length
            val fl = edges[b]
            val fh = edges[b + 1]
            val k0 = max(1, floor(fl / df + 0.5f).toInt())
            // まとめて足した窓では、使える本は全体の 1/4 まで（それより上は、折り返しが混ざる）
            val k1 = min(if (r.decim > 1) r.n / 4 else r.n / 2, floor(fh / df + 0.5f).toInt())
            for (k in k0..k1) {
                val a = max(fl, (k - 0.5f) * df)
                val z = min(fh, (k + 0.5f) * df)
                if (z > a) {
                    bins.add(k)
                    fracs.add((z - a) / df)
                }
            }
        }
        wStart[bands] = bins.size
        wBin = bins.toIntArray()
        wFrac = fracs.toFloatArray()
    }

    private fun res(kind: Int): Res = when (kind) {
        RES_LOW -> low
        RES_MID -> mid
        else -> high
    }

    /**
     * 帯ごとの強さ（二乗平均）を out に入れる。
     * @return 解析できたか（履歴が短い窓 1 つぶんに満たなければ false）
     */
    fun compute(st: WaveStitcher, out: FloatArray): Boolean {
        if (st.length < N_HIGH) return false
        low.power(st, 0)
        mid.power(st, 0)
        high.power(st, 0)
        if (st.length >= N_HIGH + stagger && stagger > 0) {
            // 少し前の位置でもう一度。短い音が窓の端に来ても、どちらかの窓の中ほどに入る
            System.arraycopy(high.p, 0, highB, 0, highB.size)
            high.power(st, stagger)
            val p = high.p
            for (k in highB.indices) if (highB[k] > p[k]) p[k] = highB[k]
        }
        for (b in 0 until bands) {
            val p = res(resolution[b]).p
            var s = 0f
            for (i in wStart[b] until wStart[b + 1]) s += p[wBin[i]] * wFrac[i]
            out[b] = s
        }
        return true
    }

    /**
     * 1 つの分解能: 窓（長さ n × decim。最後が最新）と、計算用の置き場。
     * decim > 1 のときは、窓をかけた波形を decim 個ずつ足して n 点にしてから FFT する（周波数の刻みは、窓の長さぶんの細かさのまま）
     */
    private class Res(val w: FloatArray, val n: Int, val decim: Int) {
        /** 窓の長さ（サンプル） */
        val length = n * decim
        private val fft = Fft(n)
        private val re = FloatArray(n)
        private val im = FloatArray(n)
        private val x = FloatArray(length)
        /** 片側の強さ（本ごと。帯の中を足すと、その帯の成分の二乗平均） */
        val p = FloatArray(n / 2 + 1)
        /** 新しい側 i サンプルぶんの、窓の 2 乗の和（履歴が短いときの大きさ合わせ） */
        private val tail = FloatArray(length + 1).also { t ->
            var s = 0.0
            for (i in 1..length) {
                s += w[length - i].toDouble() * w[length - i]
                t[i] = s.toFloat()
            }
        }

        fun power(st: WaveStitcher, back: Int) {
            val valid = st.read(x, length, back)
            if (decim == 1) {
                for (i in 0 until n) {
                    re[i] = x[i] * w[i]
                    im[i] = 0f
                }
            } else {
                var j = 0
                for (i in 0 until n) {
                    var s = 0f
                    for (d in 0 until decim) {
                        s += x[j] * w[j]
                        j++
                    }
                    re[i] = s
                    im[i] = 0f
                }
            }
            fft.transform(re, im)
            // 履歴が窓より短いときは、入っている部分の窓の大きさで割る（強さが小さく出ないように）
            val energy = max(tail[valid], 1e-9f)
            val norm = 2f / (length * energy)
            for (k in p.indices) p[k] = (re[k] * re[k] + im[k] * im[k]) * norm
        }
    }
}

/** 2 のべき乗の大きさの FFT（その場で計算。表は最初に作る） */
class Fft(val n: Int) {
    private val cosT = FloatArray(n / 2)
    private val sinT = FloatArray(n / 2)
    private val rev = IntArray(n)

    init {
        require(n >= 2 && n and (n - 1) == 0) { "FFT の大きさは 2 のべき乗" }
        for (i in 0 until n / 2) {
            cosT[i] = cos(2.0 * PI * i / n).toFloat()
            sinT[i] = sin(2.0 * PI * i / n).toFloat()
        }
        var bits = 0
        while (1 shl bits < n) bits++
        for (i in 0 until n) {
            var r = 0
            var v = i
            for (b in 0 until bits) {
                r = (r shl 1) or (v and 1)
                v = v shr 1
            }
            rev[i] = r
        }
    }

    /** re・im をフーリエ変換で置き換える（e^{-j...} の向き） */
    fun transform(re: FloatArray, im: FloatArray) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var start = 0
            while (start < n) {
                var t = 0
                for (k in start until start + half) {
                    val c = cosT[t]
                    val s = sinT[t]
                    val l = k + half
                    val xr = re[l] * c + im[l] * s
                    val xi = im[l] * c - re[l] * s
                    re[l] = re[k] - xr
                    im[l] = im[k] - xi
                    re[k] += xr
                    im[k] += xi
                    t += step
                }
                start += size
            }
            size = size shl 1
        }
    }
}

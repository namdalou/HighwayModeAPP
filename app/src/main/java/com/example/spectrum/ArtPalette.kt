package com.example.spectrum

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * ジャケットから、スペアナのバーの元になる色を選ぶ（別スレッドで呼ぶ）。
 *
 * これまでは「鮮やかで面積の多い色」だけを探していたため、暗いジャケット・淡いジャケット・白黒のジャケットの多くで
 * 色が見つからず、標準の青になっていた。ここでは、
 *  - 色相を 15° ごとに分けて、鮮やかさと明るさで重み付けした量を数え、いちばん多い色の周り（両隣を含む）の
 *    平均の色相・鮮やかさ・明るさをそのまま使う（淡いジャケットは淡い色、深い色のジャケットは深い色になる）
 *  - 暗いジャケットに少しだけある鮮やかな色（ネオンの文字など）も拾う（鮮やかな色は少なくても重く数える）
 *  - 色がほとんど無い（白黒・灰色）ジャケットは、わずかな色味（セピア・青み）を残した銀色にする
 * 返す色は [SpectrumStyle.gradientFor] でバーのグラデーションにする。
 */
object ArtPalette {

    private const val SIZE = 40
    private const val BUCKETS = 24

    /** ジャケットの代表色（ARGB）。読めない画像なら null */
    fun dominant(src0: Bitmap): Int? = try {
        dominantInner(src0)
    } catch (_: Throwable) {
        null
    }

    private fun dominantInner(src0: Bitmap): Int? {
        if (src0.isRecycled || src0.width <= 0 || src0.height <= 0) return null
        // ハードウェア画像は画素を直接読めないので、読める形式に写してから縮小する
        val src = if (src0.config == Bitmap.Config.HARDWARE) src0.copy(Bitmap.Config.ARGB_8888, false) ?: return null else src0
        val small = Bitmap.createScaledBitmap(src, SIZE, SIZE, true)
        val px = IntArray(SIZE * SIZE)
        small.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        if (small !== src) small.recycle()
        if (src !== src0) src.recycle()

        val w = FloatArray(BUCKETS)
        val sumS = FloatArray(BUCKETS)
        val sumV = FloatArray(BUCKETS)
        val sumSin = FloatArray(BUCKETS)
        val sumCos = FloatArray(BUCKETS)
        val hsv = FloatArray(3)
        var total = 0
        var colored = 0
        var vivid = 0
        // 色のほとんど無い画素の、わずかな色味（白黒ジャケットのセピア・青みを残すため）
        var tintSin = 0f
        var tintCos = 0f
        var tintW = 0f
        var neutralV = 0f
        var neutral = 0
        for (c in px) {
            if (Color.alpha(c) < 128) continue
            total++
            Color.colorToHSV(c, hsv)
            val h = hsv[0]
            val s = hsv[1]
            val v = hsv[2]
            if (v < 0.10f) continue                       // ほぼ黒は色として数えない
            if (s >= 0.16f && v >= 0.16f) {
                colored++
                // 鮮やかで明るい色ほど重く。鮮やかな色は面積が少なくても拾う
                var weight = s.pow(1.3f) * (0.35f + v)
                if (s >= 0.5f && v >= 0.35f) {
                    weight *= 1.8f
                    vivid++
                }
                val b = ((h / 360f) * BUCKETS).toInt().coerceIn(0, BUCKETS - 1)
                w[b] += weight
                sumS[b] += s * weight
                sumV[b] += v * weight
                val rad = Math.toRadians(h.toDouble())
                sumSin[b] += (sin(rad) * weight).toFloat()
                sumCos[b] += (cos(rad) * weight).toFloat()
            } else {
                neutral++
                neutralV += v
                if (s >= 0.04f) {
                    val rad = Math.toRadians(h.toDouble())
                    tintSin += (sin(rad) * s).toFloat()
                    tintCos += (cos(rad) * s).toFloat()
                    tintW += s
                }
            }
        }
        if (total == 0) return null

        var best = 0
        for (i in 1 until BUCKETS) if (w[i] > w[best]) best = i
        val enoughColor = colored >= total * 0.02f || vivid >= total * 0.006f
        if (!enoughColor || w[best] <= 0f) {
            // 白黒・灰色のジャケット: 銀色（わずかな色味があればそれを少し）
            val tintHue = if (tintW > 0f) hueOf(tintSin, tintCos) else 210f
            val tintS = if (neutral > 0 && tintW > 0f) (tintW / neutral).coerceIn(0f, 0.1f) else 0f
            val v = if (neutral > 0) (neutralV / neutral).coerceIn(0.5f, 0.95f) else 0.85f
            return Color.HSVToColor(floatArrayOf(tintHue, tintS, v))
        }
        // いちばん多い色と両隣をまとめて、平均の色相・鮮やかさ・明るさ
        var cw = 0f
        var cs = 0f
        var cv = 0f
        var csin = 0f
        var ccos = 0f
        for (k in -1..1) {
            val b = (best + k + BUCKETS) % BUCKETS
            // 両隣は、いちばん多い色の半分以上あるときだけ混ぜる（別の色を混ぜて濁らせない）
            if (k != 0 && w[b] < w[best] * 0.5f) continue
            cw += w[b]
            cs += sumS[b]
            cv += sumV[b]
            csin += sumSin[b]
            ccos += sumCos[b]
        }
        val hue = hueOf(csin, ccos)
        val sat = (cs / cw).coerceIn(0f, 1f)
        val value = (cv / cw).coerceIn(0f, 1f)
        return Color.HSVToColor(floatArrayOf(hue, sat, value))
    }

    private fun hueOf(sn: Float, cs: Float): Float {
        if (sqrt(sn * sn + cs * cs) < 1e-6f) return 0f
        var d = Math.toDegrees(atan2(sn.toDouble(), cs.toDouble())).toFloat()
        if (d < 0f) d += 360f
        return d
    }
}

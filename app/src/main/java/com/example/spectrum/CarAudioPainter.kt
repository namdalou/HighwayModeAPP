package com.example.spectrum

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 昔のカーオーディオのスペアナを、利用者が示した動画（1990〜2000 年代の 2DIN デッキ・イコライザーの表示）に合わせて描く（v2.7）。
 * v2.8 から、表示窓（枠・照らされた画面）・消えている段・飾り（目盛り・矢印・周波数の文字）は描かず、
 * LED（標準）と同じように、音楽の画面の背景の上に点灯している段（ブロック）だけを描く。
 *
 *  - [STYLE_BLUE] ブルー: 奥へすぼまる 20 本 × 14 段の青いバー。白いピークはバーから離れると、ふわっと上へ浮かんで消える
 *  - [STYLE_STEREO] ステレオ: 9 つの帯を左右 2 本ずつ（18 本 × 12 段）。奥へすぼまる形に、下が白っぽい水色・中ほどが水色・上が青の段。
 *    いちばん下の段はいつも点灯。青いピークは少し止まってから落ちる
 *  - [STYLE_EQ] イコライザー: 蛍光表示管の 15 本の幅の広いバー。下半分が白、上が水色。細い線の集まりで、とても速く動く
 *
 * バーの動きは本物の表示に合わせて、ここで計算する（上がるのは速く、落ちるのは一定の速さ。ピークの動きも表示ごと）。
 * 奥へすぼまる形は、平らに描いた絵を射影変換（Matrix.setPolyToPoly）で台形に写して描く（1 本 1 回で描くのはそのまま）。
 * 色の設定が標準の青のときは本物の色で、ほかの色を選んだときは点灯している段をその色にする。
 */
class CarAudioPainter(private val bands: Int) {

    companion object {
        const val STYLE_BLUE = 0
        const val STYLE_STEREO = 1
        const val STYLE_EQ = 2

        private const val MAX_COLS = 20

        private val STD_BOTTOM = Color.rgb(0, 90, 200)
        private val STD_TOP = Color.rgb(40, 190, 245)
    }

    // ================================================================== 色

    /** 本物の色で描く（設定の色が標準の青） */
    private var authentic = true
    private var tintBottom = STD_BOTTOM
    private var tintTop = STD_TOP
    private var colorsVersion = 0

    fun setColors(bottoms: IntArray, tops: IntArray, uniform: Boolean) {
        val auth = uniform && bottoms[0] == STD_BOTTOM && tops[0] == STD_TOP
        val b = if (uniform) bottoms[0] else bottoms[bottoms.size / 2]
        val t = if (uniform) tops[0] else tops[tops.size / 2]
        if (auth == authentic && b == tintBottom && t == tintTop) return
        authentic = auth
        tintBottom = b
        tintTop = t
        colorsVersion++
    }

    /** 点灯している段の色（行 k / 行数）。本物の色か、設定の色のグラデーション。暗い背景の上でよく見えるよう、少し明るめ */
    private fun litColor(style: Int, k: Int, rows: Int): Int {
        val f = if (rows > 1) k.toFloat() / (rows - 1) else 0f
        if (!authentic) return mix(tintBottom, tintTop, f)
        return when (style) {
            STYLE_BLUE -> mix(Color.rgb(58, 112, 246), Color.rgb(168, 214, 255), f * f)
            STYLE_STEREO -> when {
                k == 0 -> Color.rgb(74, 124, 255)                  // いちばん下（いつも点灯）
                f < 0.25f -> Color.rgb(200, 248, 255)              // 白っぽい水色
                f < 0.66f -> Color.rgb(76, 218, 246)               // 水色
                else -> Color.rgb(74, 136, 255)                    // 青
            }
            else -> if (f < 0.45f) Color.rgb(236, 246, 234) else Color.rgb(36, 224, 244)
        }
    }

    // ================================================================== 状態（バーの動き）

    private val target = FloatArray(MAX_COLS)
    private val disp = FloatArray(MAX_COLS)
    private val peakPos = FloatArray(MAX_COLS) { -1f }
    private val peakHold = FloatArray(MAX_COLS)
    private var lastStyle = -1

    var animating = false
        private set

    /**
     * 24 の帯を m 本にまとめる（重なる帯の最大。拍がはっきり見えるように）。
     * gain: 本物の表示はよく振れて上の方まで届くので、少し大きめに
     */
    private fun resample(vis: FloatArray, m: Int, gain: Float) {
        for (j in 0 until m) {
            val a = j * bands.toFloat() / m
            val b = (j + 1) * bands.toFloat() / m
            var v = 0f
            var i = floor(a).toInt()
            while (i < b && i < bands) {
                if (i >= 0) v = max(v, vis[i])
                i++
            }
            target[j] = (v * gain).coerceIn(0f, 1f)
        }
    }

    /**
     * 本物の表示に近い動き: 上がるのは速く（rise [高さ/秒]）、落ちるのは一定の速さ（fall）。
     * dt = 0（止めた見本）なら、すぐ目標の高さにする
     */
    private fun ballistics(m: Int, dt: Float, rise: Float, fall: Float) {
        for (j in 0 until m) {
            val t = target[j]
            val d = disp[j]
            disp[j] = when {
                dt <= 0f -> t
                t > d -> min(t, d + rise * dt)
                else -> max(t, d - fall * dt)
            }
            if (disp[j] != t) animating = true
        }
    }

    fun draw(c: Canvas, r: RectF, style: Int, vis: FloatArray, scale: Float, dtSec: Float) {
        if (r.width() < 8f || r.height() < 8f || scale <= 0f) return
        animating = false
        if (style != lastStyle) {
            lastStyle = style
            for (j in 0 until MAX_COLS) {
                peakPos[j] = -1f
                peakHold[j] = 0f
            }
        }
        val dt = dtSec.coerceIn(0f, 0.1f)
        when (style) {
            STYLE_STEREO -> drawStereo(c, r, vis, scale, dt)
            STYLE_EQ -> drawEq(c, r, vis, scale, dt)
            else -> drawBlue(c, r, vis, scale, dt)
        }
    }

    // ================================================================== 共通の部品

    private val plain = Paint()
    private val area = RectF()
    private val matrix = Matrix()
    private val persp = Matrix()
    private val src = FloatArray(8)
    private val dst = FloatArray(8)

    /** 段の模様（幅 1 画素。点灯している段の色と隙間） */
    private class Pattern {
        var shader: BitmapShader? = null
        var hPx = 0
        var style = -1
        var rows = -1
        var unit = -1f
        var seg = -1f
        var scale = -1f
        var ver = -1
        var auth = false
        var geom = -1
    }

    private val litPattern = Pattern()
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val litPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /**
     * 行の模様を作る（区画・色が変わったときだけ。判断は数値の比較で、毎フレームの割り当てはしない）。画像の下端 = 行 0 の下。
     * @param uniformGeom 0 以上なら台形用（行の位置は rowTop / rowBot。平らな座標の上から）
     */
    private fun pattern(style: Int, rows: Int, unit: Float, segH: Float, s: Float, uniformGeom: Int = -1): BitmapShader? {
        val p = litPattern
        if (p.shader != null && p.style == style && p.rows == rows && p.unit == unit && p.seg == segH && p.scale == s &&
            p.ver == colorsVersion && p.auth == authentic && p.geom == uniformGeom
        ) return p.shader
        val hPx = ceil(rows * unit * s).toInt().coerceIn(1, 4096)
        val px = IntArray(hPx)
        val total = rows * unit
        for (y in 0 until hPx) {
            if (uniformGeom >= 0) {
                // 台形: 行の位置は rowTop / rowBot（平らな座標の上からの位置。画面の上では等間隔）
                val v = (y + 0.5f) / s
                var c = 0
                for (k in 0 until rows) {
                    if (v >= rowTop[k] && v < rowBot[k]) {
                        c = litColor(style, k, rows)
                        break
                    }
                }
                px[y] = c
                continue
            }
            val fromBottom = total - (y + 0.5f) / s
            val rr = floor(fromBottom / unit).toInt()
            val within = fromBottom - rr * unit
            px[y] = if (rr in 0 until rows && within < segH) litColor(style, rr, rows) else 0
        }
        val bmp = try {
            Bitmap.createBitmap(1, hPx, Bitmap.Config.ARGB_8888)
        } catch (_: Throwable) {
            return null
        }
        bmp.setPixels(px, 0, 1, 0, 0, 1, hPx)
        p.shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.CLAMP)
        p.hPx = hPx
        p.style = style
        p.rows = rows
        p.unit = unit
        p.seg = segH
        p.scale = s
        p.ver = colorsVersion
        p.auth = authentic
        p.geom = uniformGeom
        return p.shader
    }

    /** 台形用の模様の位置: 模様の 1 画素 = 1/s 単位、模様の上端 = v 0 */
    private fun placePatternTop(s: Float) {
        val sh = litPattern.shader ?: return
        matrix.setScale(1f / s, 1f / s)
        sh.setLocalMatrix(matrix)
    }

    /** 模様の位置: 模様の 1 画素 = 1/s 単位、模様の下端 = y = base */
    private fun placePattern(s: Float, base: Float) {
        val sh = litPattern.shader ?: return
        matrix.setScale(1f / s, 1f / s)
        matrix.postTranslate(0f, base - litPattern.hPx / s)
        sh.setLocalMatrix(matrix)
    }

    /**
     * 平らな区画 (0,0)-(w,h) を、下の幅いっぱい・上を ratio 倍にすぼめた台形（area の中、左右中央）へ写す射影変換を設定する
     */
    private fun perspective(a: RectF, w: Float, h: Float, ratio: Float) {
        val cx = a.centerX()
        val bw = a.width()
        val tw = bw * ratio
        src[0] = 0f; src[1] = 0f
        src[2] = w; src[3] = 0f
        src[4] = w; src[5] = h
        src[6] = 0f; src[7] = h
        dst[0] = cx - tw / 2f; dst[1] = a.top
        dst[2] = cx + tw / 2f; dst[3] = a.top
        dst[4] = a.right; dst[5] = a.bottom
        dst[6] = a.left; dst[7] = a.bottom
        persp.setPolyToPoly(src, 0, dst, 0, 4)
    }

    // ---- 台形の上で、行を画面の上では等間隔に並べる（射影変換のままだと、奥の段ほど詰まって見えるため。本物の表示は等間隔）
    private val rowTop = FloatArray(32)
    private val rowBot = FloatArray(32)
    private val mapPt = FloatArray(2)
    private var rgW = -1f
    private var rgH = -1f
    private var rgRatio = -1f
    private var rgRows = -1
    private var rgSeg = -1f
    /** 行の位置を計算し直した回数（模様の作り直しの判断） */
    private var rgVersion = 0

    private fun screenY(v: Float, fw: Float): Float {
        mapPt[0] = fw / 2f
        mapPt[1] = v
        persp.mapPoints(mapPt)
        return mapPt[1]
    }

    /** 画面の y になる、平らな座標の v（上が 0）。射影変換の y は v について単調なので二分法で求める */
    private fun localV(ys: Float, fw: Float, fh: Float): Float {
        var lo = 0f
        var hi = fh
        repeat(22) {
            val mid = (lo + hi) / 2f
            if (screenY(mid, fw) < ys) lo = mid else hi = mid
        }
        return (lo + hi) / 2f
    }

    /** rows 段を画面の上で等間隔に（1 段のうち segFrac が点灯部分）。区画が変わったときだけ計算する */
    private fun uniformRows(rows: Int, segFrac: Float, fw: Float, fh: Float, ratio: Float): Int {
        if (fw == rgW && fh == rgH && ratio == rgRatio && rows == rgRows && segFrac == rgSeg) return rgVersion
        rgW = fw
        rgH = fh
        rgRatio = ratio
        rgRows = rows
        rgSeg = segFrac
        val yt = screenY(0f, fw)
        val yb = screenY(fh, fw)
        val pitch = (yb - yt) / rows
        for (k in 0 until rows) {
            val bot = yb - k * pitch
            rowBot[k] = localV(bot, fw, fh)
            rowTop[k] = localV(bot - pitch * segFrac, fw, fh)
        }
        rgVersion++
        return rgVersion
    }

    // ================================================================== ブルー（奥へすぼまる 20 本・浮かぶピーク）

    private fun drawBlue(c: Canvas, r: RectF, vis: FloatArray, scale: Float, dt: Float) {
        val m = 20
        resample(vis, m, 1.1f)
        ballistics(m, dt, rise = 6f, fall = 2.2f)
        area.set(r)
        val rows = 14
        val fw = area.width()
        val fh = area.height()
        val unit = fh / rows
        val segH = unit * 0.56f
        val pitch = fw / m
        val slatW = pitch * 0.74f
        val off = (pitch - slatW) / 2f
        // 浮かぶピーク: バーに押し上げられ、離れるとゆっくり上へ浮かんで、上まで行くと消える
        for (j in 0 until m) {
            val top = disp[j]
            when {
                dt <= 0f -> peakPos[j] = (top + 0.18f).coerceAtMost(0.98f)
                peakPos[j] < 0f || top >= peakPos[j] -> peakPos[j] = if (top > 0.06f) top else -1f
                else -> {
                    peakPos[j] += 0.35f * dt
                    if (peakPos[j] > 1.0f) peakPos[j] = -1f
                    animating = true
                }
            }
        }
        perspective(area, fw, fh, 0.82f)
        val geom = uniformRows(rows, 0.56f, fw, fh, 0.82f)
        val lit = pattern(STYLE_BLUE, rows, unit, segH, scale, uniformGeom = geom)
        c.save()
        c.concat(persp)
        placePatternTop(scale)
        litPaint.shader = lit
        peakPaint.color = Color.rgb(236, 244, 255)
        for (j in 0 until m) {
            val x = j * pitch + off
            val litRows = (disp[j] * rows + 0.001f).toInt().coerceIn(0, rows)
            if (litRows > 0 && lit != null) {
                val top = rowTop[litRows - 1] - unit * 0.12f
                c.drawRect(x, top, x + slatW, fh, litPaint)
            }
            val p = peakPos[j]
            if (p > 0f) {
                val pr = (p * rows).toInt().coerceIn(0, rows - 1)
                if (pr >= litRows) c.drawRect(x, rowTop[pr], x + slatW, rowBot[pr], peakPaint)
            }
        }
        c.restore()
    }

    // ================================================================== ステレオ（9 帯 × 左右・奥へすぼまる形）

    private fun drawStereo(c: Canvas, r: RectF, vis: FloatArray, scale: Float, dt: Float) {
        val m = 18
        resample(vis, m, 1.1f)
        ballistics(m, dt, rise = 6f, fall = 2.3f)
        area.set(r)
        val rows = 12            // 行 0 = いつも点灯している床
        val fw = area.width()
        val fh = area.height()
        val unit = fh / rows
        val segH = unit * 0.48f
        // 9 組 × 左右 2 本: 組の中の隙間は小さく、組の間は広く
        val pairPitch = fw / 9f
        val colW = pairPitch * 0.38f
        val innerGap = pairPitch * 0.06f
        val pairOff = (pairPitch - colW * 2f - innerGap) / 2f
        // ピーク: 少し止まってから落ちる
        for (j in 0 until m) {
            val top = disp[j]
            when {
                dt <= 0f -> peakPos[j] = (top + 0.12f).coerceAtMost(1f)
                top >= peakPos[j] -> {
                    peakPos[j] = top
                    peakHold[j] = 0.5f
                }
                peakHold[j] > 0f -> {
                    peakHold[j] -= dt
                    animating = true
                }
                else -> {
                    peakPos[j] = max(top, peakPos[j] - 1.6f * dt)
                    if (peakPos[j] > top) animating = true
                }
            }
        }
        perspective(area, fw, fh, 0.78f)
        val geom = uniformRows(rows, 0.48f, fw, fh, 0.78f)
        val lit = pattern(STYLE_STEREO, rows, unit, segH, scale, uniformGeom = geom)
        c.save()
        c.concat(persp)
        placePatternTop(scale)
        litPaint.shader = lit
        peakPaint.color = if (authentic) Color.rgb(84, 140, 255) else mix(tintTop, Color.WHITE, 0.35f)
        for (j in 0 until m) {
            val pair = j / 2
            val x = pair * pairPitch + pairOff + (j % 2) * (colW + innerGap)
            // 行 0（床）はいつも点灯
            val litRows = (1 + (disp[j] * (rows - 1) + 0.001f).toInt()).coerceIn(1, rows)
            if (lit != null) {
                val top = rowTop[litRows - 1] - unit * 0.15f
                c.drawRect(x, top, x + colW, fh, litPaint)
            }
            val p = peakPos[j]
            if (p > 0.02f) {
                val pr = (1 + (p * (rows - 1)).toInt()).coerceIn(1, rows - 1)
                if (pr >= litRows) c.drawRect(x, rowTop[pr], x + colW, rowBot[pr], peakPaint)
            }
        }
        c.restore()
    }

    // ================================================================== イコライザー（蛍光表示管の 15 本）

    private fun drawEq(c: Canvas, r: RectF, vis: FloatArray, scale: Float, dt: Float) {
        val m = 15
        resample(vis, m, 1.2f)
        ballistics(m, dt, rise = 20f, fall = 4.5f)
        area.set(r)
        // 細い線の集まり（蛍光表示管の 1 本 1 本の線）
        val unit = (area.height() / 40f).coerceAtLeast(1.0f)
        val rows = floor(area.height() / unit).toInt()
        if (rows < 4) return
        val segH = unit * 0.84f
        val base = area.bottom
        val pitch = area.width() / m
        val barW = pitch * 0.8f
        val off = (pitch - barW) / 2f
        val lit = pattern(STYLE_EQ, rows, unit, segH, scale) ?: return
        placePattern(scale, base)
        litPaint.shader = lit
        for (j in 0 until m) {
            val x = area.left + j * pitch + off
            val litRows = (disp[j] * rows + 0.001f).toInt().coerceIn(0, rows)
            if (litRows > 0) {
                c.drawRect(x, base - litRows * unit, x + barW, base, litPaint)
            }
        }
    }

    private fun mix(a: Int, b: Int, t: Float): Int = SpectrumPainter.mix(a, b, t)
}

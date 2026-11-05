package com.example.spectrum

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 昔のカーオーディオのスペアナを、利用者が示した動画（1990〜2000 年代の 2DIN デッキ・イコライザーの表示）に合わせて描く（v2.7）。
 *
 *  - [STYLE_BLUE] ブルー: 紫の画面に、奥へすぼまる 20 本 × 14 段の青いバー。白いピークはバーから離れると、ふわっと上へ浮かんで消える。
 *    消えている段は赤紫に見える。下に周波数、右上に赤い矢印の線、左右に小さなレベルの目盛り
 *  - [STYLE_STEREO] ステレオ: 9 つの帯を左右 2 本ずつ（18 本 × 11 段）。奥へすぼまる格子に、下が白っぽい水色・中ほどが水色・上が青の段。
 *    いちばん下の段はいつも点灯。青いピークは少し止まってから落ちる。上に赤い点線、下に緑の周波数
 *  - [STYLE_EQ] イコライザー: 蛍光表示管の 15 本の幅の広いバー。下半分が白、上が水色。細い線の集まりで、とても速く動く
 *
 * バーの動きは本物の表示に合わせて、ここで計算する（上がるのは速く、落ちるのは一定の速さ。ピークの動きも表示ごと）。
 * 奥へすぼまる形は、平らに描いた絵を射影変換（Matrix.setPolyToPoly）で台形に写して描く（1 本 1 回で描くのはそのまま）。
 * 色の設定が標準の青のときは本物の色で、ほかの色を選んだときは点灯している段をその色にする（消えている段・ピークは本物の色）。
 */
class CarAudioPainter(private val bands: Int) {

    companion object {
        const val STYLE_BLUE = 0
        const val STYLE_STEREO = 1
        const val STYLE_EQ = 2

        private const val MAX_COLS = 20

        /** 解析の帯の範囲（SpectrumEngine と同じ 40Hz〜16kHz） */
        private const val F_MIN = 40f
        private const val F_RATIO = 400f

        private val FREQ_LABELS = floatArrayOf(60f, 120f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
        private val FREQ_TEXT = arrayOf("60", "120", "250", "500", "1k", "2k", "4k", "8k", "16k")

        private val STD_BOTTOM = Color.rgb(0, 90, 200)
        private val STD_TOP = Color.rgb(40, 190, 245)

        private val EQ_TEXT = arrayOf("60", "150", "400", "1k", "2.4k", "6k", "15k")
        private val EQ_FREQ = floatArrayOf(60f, 150f, 400f, 1000f, 2400f, 6000f, 15000f)
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

    /** 点灯している段の色（行 k / 行数）。本物の色か、設定の色のグラデーション */
    private fun litColor(style: Int, k: Int, rows: Int): Int {
        val f = if (rows > 1) k.toFloat() / (rows - 1) else 0f
        if (!authentic) return mix(tintBottom, tintTop, f)
        return when (style) {
            STYLE_BLUE -> mix(Color.rgb(46, 92, 228), Color.rgb(150, 200, 255), f * f)
            STYLE_STEREO -> when {
                k == 0 -> Color.rgb(64, 112, 255)                  // いちばん下（いつも点灯）
                f < 0.25f -> Color.rgb(196, 248, 255)              // 白っぽい水色
                f < 0.66f -> Color.rgb(70, 214, 242)               // 水色
                else -> Color.rgb(64, 124, 255)                    // 青
            }
            else -> if (f < 0.45f) Color.rgb(234, 245, 230) else Color.rgb(28, 222, 242)
        }
    }

    /** 消えている段の色（行 k / 行数）。蛍光表示管・液晶の、光っていない部分がうっすら見える色 */
    private fun ghostColor(style: Int, k: Int, rows: Int): Int = when (style) {
        STYLE_BLUE -> Color.argb(150, 120, 38, 112)
        STYLE_STEREO -> Color.argb(165, 116, 44, 78)
        else -> if (k < (rows * 0.45f).toInt()) Color.argb(110, 132, 118, 70) else Color.argb(96, 40, 120, 140)
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

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val plain = Paint()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val tmp = RectF()
    private val inner = RectF()
    private val area = RectF()
    private val matrix = Matrix()
    private val persp = Matrix()
    private val src = FloatArray(8)
    private val dst = FloatArray(8)

    /** 段の模様（幅 1 画素。行の色と隙間）。行の色は litColor（点灯）か ghostColor（消えている段） */
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
    private val ghostPattern = Pattern()
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val litPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = 64 }
    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /**
     * 行の模様を作る（区画・色が変わったときだけ。判断は数値の比較で、毎フレームの割り当てはしない）。画像の下端 = 行 0 の下。
     * @param ghost 消えている段の模様（ghostColor）。false なら点灯の色（litColor）
     */
    private fun pattern(p: Pattern, style: Int, rows: Int, unit: Float, segH: Float, s: Float, ghost: Boolean, uniformGeom: Int = -1): BitmapShader? {
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
                        c = if (ghost) ghostColor(style, k, rows) else litColor(style, k, rows)
                        break
                    }
                }
                px[y] = c
                continue
            }
            val fromBottom = total - (y + 0.5f) / s
            val rr = floor(fromBottom / unit).toInt()
            val within = fromBottom - rr * unit
            px[y] = if (rr in 0 until rows && within < segH) {
                if (ghost) ghostColor(style, rr, rows) else litColor(style, rr, rows)
            } else 0
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
    private fun placePatternTop(p: Pattern, s: Float) {
        val sh = p.shader ?: return
        matrix.setScale(1f / s, 1f / s)
        sh.setLocalMatrix(matrix)
    }

    /** 模様の位置: 模様の 1 画素 = 1/s 単位、模様の下端 = y = base */
    private fun placePattern(p: Pattern, s: Float, base: Float) {
        val sh = p.shader ?: return
        matrix.setScale(1f / s, 1f / s)
        matrix.postTranslate(0f, base - p.hPx / s)
        sh.setLocalMatrix(matrix)
    }

    // ---- 表示窓（本体の黒い枠と、照らされた画面）
    private val winKey = RectF()
    private var winStyle = -1
    private var winBg: LinearGradient? = null
    private var winGlowA: RadialGradient? = null
    private var winGlowB: RadialGradient? = null
    private var winGloss: LinearGradient? = null

    private fun drawWindow(c: Canvas, r: RectF, style: Int) {
        if (r != winKey || style != winStyle || winBg == null) {
            winKey.set(r)
            winStyle = style
            val big = max(r.width(), r.height())
            when (style) {
                STYLE_BLUE -> {
                    // 紫の画面（下ほど明るく、ふちが赤紫に光る）
                    winBg = LinearGradient(0f, r.top, 0f, r.bottom, intArrayOf(Color.rgb(34, 10, 48), Color.rgb(42, 13, 58), Color.rgb(64, 18, 84)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
                    winGlowA = RadialGradient(r.centerX(), r.bottom + r.height() * 0.15f, big * 0.62f, intArrayOf(Color.argb(120, 150, 40, 200), Color.argb(0, 150, 40, 200)), null, Shader.TileMode.CLAMP)
                    winGlowB = RadialGradient(r.left, r.top + r.height() * 0.4f, big * 0.35f, intArrayOf(Color.argb(70, 200, 40, 150), Color.argb(0, 200, 40, 150)), null, Shader.TileMode.CLAMP)
                }
                STYLE_STEREO -> {
                    winBg = LinearGradient(0f, r.top, 0f, r.bottom, intArrayOf(Color.rgb(28, 13, 34), Color.rgb(36, 17, 42), Color.rgb(48, 20, 56)), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
                    winGlowA = RadialGradient(r.centerX(), r.bottom, big * 0.6f, intArrayOf(Color.argb(80, 110, 40, 150), Color.argb(0, 110, 40, 150)), null, Shader.TileMode.CLAMP)
                    winGlowB = RadialGradient(r.right, r.top, big * 0.3f, intArrayOf(Color.argb(40, 60, 90, 200), Color.argb(0, 60, 90, 200)), null, Shader.TileMode.CLAMP)
                }
                else -> {
                    // 蛍光表示管: ほぼ黒（少し青み）
                    winBg = LinearGradient(0f, r.top, 0f, r.bottom, intArrayOf(Color.rgb(10, 14, 22), Color.rgb(5, 7, 12)), null, Shader.TileMode.CLAMP)
                    winGlowA = RadialGradient(r.centerX(), r.bottom, big * 0.6f, intArrayOf(Color.argb(40, 40, 160, 180), Color.argb(0, 40, 160, 180)), null, Shader.TileMode.CLAMP)
                    winGlowB = null
                }
            }
            winGloss = LinearGradient(0f, r.top, 0f, r.top + r.height() * 0.4f, Color.argb(22, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
        }
        val rad = (min(r.width(), r.height()) * 0.06f).coerceIn(3f, 7f)
        fill.shader = winBg
        c.drawRoundRect(r, rad, rad, fill)
        winGlowA?.let { fill.shader = it; c.drawRoundRect(r, rad, rad, fill) }
        winGlowB?.let { fill.shader = it; c.drawRoundRect(r, rad, rad, fill) }
        fill.shader = null
        stroke.shader = null
        stroke.strokeWidth = 1.6f
        stroke.color = Color.rgb(4, 3, 6)
        c.drawRoundRect(r, rad, rad, stroke)
        tmp.set(r.left + 1.2f, r.top + 1.2f, r.right - 1.2f, r.bottom - 1.2f)
        stroke.strokeWidth = 0.6f
        stroke.color = if (style == STYLE_EQ) Color.argb(90, 120, 150, 170) else Color.argb(100, 140, 100, 180)
        c.drawRoundRect(tmp, rad * 0.8f, rad * 0.8f, stroke)
    }

    private fun drawGloss(c: Canvas, r: RectF) {
        val rad = (min(r.width(), r.height()) * 0.06f).coerceIn(3f, 7f)
        fill.shader = winGloss
        tmp.set(r.left + 1.2f, r.top + 1.2f, r.right - 1.2f, r.top + r.height() * 0.4f)
        c.drawRoundRect(tmp, rad * 0.8f, rad * 0.8f, fill)
        fill.shader = null
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

    /** 周波数 f の位置（0〜1。帯の範囲 40Hz〜16kHz の対数） */
    private fun freqPos(f: Float): Float = (ln(f / F_MIN) / ln(F_RATIO)).coerceIn(0f, 1f)

    private fun level(from: Int, to: Int): Float {
        var s = 0f
        var cnt = 0
        for (j in from until to) {
            s += disp[j] * disp[j]
            cnt++
        }
        return if (cnt == 0) 0f else sqrt(s / cnt)
    }

    // ================================================================== ブルー（斜めにすぼまる 20 本・浮かぶピーク）

    private fun drawBlue(c: Canvas, r: RectF, vis: FloatArray, scale: Float, dt: Float) {
        val m = 20
        resample(vis, m, 1.1f)
        ballistics(m, dt, rise = 6f, fall = 2.2f)
        drawWindow(c, r, STYLE_BLUE)
        val pad = (min(r.width(), r.height()) * 0.05f).coerceIn(2.5f, 6f)
        inner.set(r.left + pad, r.top + pad, r.right - pad, r.bottom - pad)
        val labelsH = if (inner.height() >= 56f) 7f else 0f
        val decoH = if (inner.height() >= 70f) 7f else 2f
        val ladderW = if (inner.width() >= 150f) 4.5f else 0f
        area.set(inner.left + ladderW + (if (ladderW > 0f) 4f else 0f), inner.top + decoH, inner.right - ladderW - (if (ladderW > 0f) 4f else 0f), inner.bottom - labelsH)
        if (area.height() < 12f) return
        val rows = 14
        val fw = area.width()
        val fh = area.height()
        val unit = fh / rows
        val segH = unit * 0.52f
        val pitch = fw / m
        val slatW = pitch * 0.72f
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
        val geom = uniformRows(rows, 0.52f, fw, fh, 0.82f)
        val ghost = pattern(ghostPattern, STYLE_BLUE, rows, unit, segH, scale, ghost = true, uniformGeom = geom)
        val lit = pattern(litPattern, STYLE_BLUE, rows, unit, segH, scale, ghost = false, uniformGeom = geom)
        c.save()
        c.concat(persp)
        placePatternTop(ghostPattern, scale)
        placePatternTop(litPattern, scale)
        ghostPaint.shader = ghost
        litPaint.shader = lit
        glowPaint.shader = lit
        peakPaint.color = Color.rgb(232, 240, 255)
        for (j in 0 until m) {
            val x = j * pitch + off
            if (ghost != null) c.drawRect(x, 0f, x + slatW, fh, ghostPaint)
            val litRows = (disp[j] * rows + 0.001f).toInt().coerceIn(0, rows)
            if (litRows > 0 && lit != null) {
                val top = rowTop[litRows - 1] - unit * 0.12f
                c.drawRect(x - pitch * 0.08f, top - unit * 0.1f, x + slatW + pitch * 0.08f, fh, glowPaint)
                c.drawRect(x, top, x + slatW, fh, litPaint)
            }
            val p = peakPos[j]
            if (p > 0f) {
                val pr = (p * rows).toInt().coerceIn(0, rows - 1)
                if (pr >= litRows) {
                    val pt = rowTop[pr]
                    val pb = rowBot[pr]
                    val gl = (pb - pt) * 0.35f
                    peakPaint.alpha = 90
                    c.drawRect(x - pitch * 0.1f, pt - gl, x + slatW + pitch * 0.1f, pb + gl, peakPaint)
                    peakPaint.alpha = 255
                    c.drawRect(x, pt, x + slatW, pb, peakPaint)
                }
            }
        }
        // 床の線
        plain.shader = null
        plain.color = Color.argb(200, 70, 120, 255)
        c.drawRect(0f, fh + unit * 0.15f, fw, fh + unit * 0.15f + max(0.5f, unit * 0.12f), plain)
        c.restore()

        // 周波数（床の下）
        if (labelsH > 0f) {
            text.textSize = labelsH * 0.82f
            text.color = Color.rgb(110, 160, 255)
            for (k in FREQ_LABELS.indices) {
                val x = area.left + freqPos(FREQ_LABELS[k]) * area.width()
                c.drawText(FREQ_TEXT[k], x, inner.bottom - labelsH * 0.12f, text)
            }
        }
        // 右上の赤い矢印の線（飾り）
        if (decoH >= 6f) {
            val y = inner.top + decoH * 0.45f
            val x0 = area.left + area.width() * 0.56f
            val x1 = area.right - decoH * 1.4f
            val seg = (x1 - x0) / 4f
            fill.shader = null
            fill.color = Color.argb(230, 255, 60, 60)
            for (k in 0 until 4) {
                val sx = x0 + k * seg
                c.drawRect(sx, y - 0.6f, sx + seg * 0.7f, y + 0.6f, fill)
                // ▶
                val tx = sx + seg * 0.78f
                c.drawLine(tx, y - 1.4f, tx + 1.8f, y, stroke.apply { color = Color.argb(230, 255, 60, 60); strokeWidth = 0.8f })
                c.drawLine(tx, y + 1.4f, tx + 1.8f, y, stroke)
            }
            stroke.color = Color.argb(230, 255, 60, 60)
            stroke.strokeWidth = 0.9f
            tmp.set(x1 + 1f, y - decoH * 0.32f, x1 + decoH * 1.3f, y + decoH * 0.32f)
            c.drawRoundRect(tmp, decoH * 0.32f, decoH * 0.32f, stroke)
        }
        // 左右の小さなレベルの目盛り（青・緑・赤）
        if (ladderW > 0f) {
            drawLadder(c, inner.left, area.top + area.height() * 0.35f, ladderW, area.bottom, level(0, m / 2))
            drawLadder(c, inner.right - ladderW, area.top + area.height() * 0.35f, ladderW, area.bottom, level(m / 2, m))
        }
        drawGloss(c, r)
    }

    private fun drawLadder(c: Canvas, x: Float, top: Float, w: Float, bottom: Float, lv: Float) {
        val n = 8
        val step = (bottom - top) / n
        val lit = (lv * 1.2f * n).toInt().coerceIn(0, n)
        for (k in 0 until n) {
            val y = bottom - k * step
            val col = when {
                k >= 6 -> Color.rgb(255, 60, 70)
                k >= 4 -> Color.rgb(60, 230, 120)
                else -> Color.rgb(70, 120, 255)
            }
            plain.shader = null
            plain.color = if (k < lit) col else withAlpha(col, 46)
            c.drawRect(x, y - step * 0.5f, x + w, y, plain)
        }
    }

    // ================================================================== ステレオ（9 帯 × 左右・奥へすぼまる格子）

    private fun drawStereo(c: Canvas, r: RectF, vis: FloatArray, scale: Float, dt: Float) {
        val m = 18
        resample(vis, m, 1.1f)
        ballistics(m, dt, rise = 6f, fall = 2.3f)
        drawWindow(c, r, STYLE_STEREO)
        val pad = (min(r.width(), r.height()) * 0.05f).coerceIn(2.5f, 6f)
        inner.set(r.left + pad, r.top + pad, r.right - pad, r.bottom - pad)
        val labelsH = if (inner.height() >= 56f) 7f else 0f
        val decoH = if (inner.height() >= 60f) 5f else 2f
        val sideW = if (inner.width() >= 150f) inner.width() * 0.06f else 0f
        area.set(inner.left + sideW, inner.top + decoH, inner.right - sideW, inner.bottom - labelsH)
        if (area.height() < 12f) return
        val rows = 12            // 行 0 = いつも点灯している床
        val fw = area.width()
        val fh = area.height()
        val unit = fh / rows
        val segH = unit * 0.42f
        // 9 組 × 左右 2 本: 組の中の隙間は小さく、組の間は広く
        val pairPitch = fw / 9f
        val colW = pairPitch * 0.36f
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
        val geom = uniformRows(rows, 0.42f, fw, fh, 0.78f)
        val ghost = pattern(ghostPattern, STYLE_STEREO, rows, unit, segH, scale, ghost = true, uniformGeom = geom)
        val lit = pattern(litPattern, STYLE_STEREO, rows, unit, segH, scale, ghost = false, uniformGeom = geom)
        c.save()
        c.concat(persp)
        placePatternTop(ghostPattern, scale)
        placePatternTop(litPattern, scale)
        ghostPaint.shader = ghost
        litPaint.shader = lit
        glowPaint.shader = lit
        peakPaint.color = if (authentic) Color.rgb(72, 126, 255) else mix(tintTop, Color.WHITE, 0.3f)
        for (j in 0 until m) {
            val pair = j / 2
            val x = pair * pairPitch + pairOff + (j % 2) * (colW + innerGap)
            if (ghost != null) c.drawRect(x, 0f, x + colW, fh, ghostPaint)
            // 行 0（床）はいつも点灯
            val litRows = (1 + (disp[j] * (rows - 1) + 0.001f).toInt()).coerceIn(1, rows)
            if (lit != null) {
                val top = rowTop[litRows - 1] - unit * 0.15f
                if (litRows > 1) c.drawRect(x - colW * 0.1f, top - unit * 0.1f, x + colW * 1.1f, fh, glowPaint)
                c.drawRect(x, top, x + colW, fh, litPaint)
            }
            val p = peakPos[j]
            if (p > 0.02f) {
                val pr = (1 + (p * (rows - 1)).toInt()).coerceIn(1, rows - 1)
                if (pr >= litRows) c.drawRect(x, rowTop[pr], x + colW, rowBot[pr], peakPaint)
            }
        }
        c.restore()
        // 上の赤い点線（台形の上の辺に沿って）
        if (decoH >= 4f) {
            val y = inner.top + decoH * 0.4f
            val tw = area.width() * 0.78f
            val x0 = area.centerX() - tw / 2f
            val dots = 44
            fill.shader = null
            fill.color = Color.argb(220, 255, 64, 64)
            for (k in 0 until dots) {
                if (k % 6 == 5) continue
                val x = x0 + tw * (k + 0.5f) / dots
                c.drawRect(x - 0.45f, y - 0.45f, x + 0.45f, y + 0.45f, fill)
            }
        }
        // 下の緑の周波数（9 つの組の下）
        if (labelsH > 0f) {
            text.textSize = labelsH * 0.82f
            text.color = Color.rgb(90, 230, 120)
            for (k in 0 until 9) {
                val x = area.left + (k + 0.5f) * pairPitch
                c.drawText(FREQ_TEXT[k], x, inner.bottom - labelsH * 0.12f, text)
            }
        }
        // 左右の斜めの飾りの目盛り（緑と青。音の大きさで明るくなる）
        if (sideW > 0f) {
            val lv = level(0, m)
            drawFan(c, inner.left, area, sideW, left = true, lv)
            drawFan(c, inner.right, area, sideW, left = false, lv)
        }
        drawGloss(c, r)
    }

    /** 台形の外側の斜めの目盛り（台形の辺に沿って並ぶ短い線） */
    private fun drawFan(c: Canvas, edgeX: Float, a: RectF, w: Float, left: Boolean, lv: Float) {
        val n = 9
        val lit = (lv * 1.3f * n).toInt().coerceIn(0, n)
        stroke.shader = null
        stroke.strokeWidth = max(0.6f, a.height() / n * 0.22f)
        for (k in 0 until n) {
            val f = k.toFloat() / (n - 1)
            val y = a.bottom - f * a.height() * 0.92f
            // 上ほど台形の内側へ寄る
            val inset = (a.width() * 0.11f) * f
            val x1 = if (left) a.left + inset - 1.5f else a.right - inset + 1.5f
            val x0 = if (left) x1 - w * (0.5f + 0.5f * (1f - f)) else x1 + w * (0.5f + 0.5f * (1f - f))
            val col = if (k % 2 == 0) Color.rgb(70, 220, 120) else Color.rgb(70, 130, 255)
            stroke.color = if (k < lit) col else withAlpha(col, 50)
            c.drawLine(x0, y + 1.2f, x1, y - 1.2f, stroke)
        }
    }

    // ================================================================== イコライザー（蛍光表示管の 15 本）

    private fun drawEq(c: Canvas, r: RectF, vis: FloatArray, scale: Float, dt: Float) {
        val m = 15
        resample(vis, m, 1.2f)
        ballistics(m, dt, rise = 20f, fall = 4.5f)
        drawWindow(c, r, STYLE_EQ)
        val pad = (min(r.width(), r.height()) * 0.06f).coerceIn(3f, 7f)
        inner.set(r.left + pad, r.top + pad, r.right - pad, r.bottom - pad)
        val labelsH = if (inner.height() >= 50f) 6f else 0f
        area.set(inner.left, inner.top + 1f, inner.right, inner.bottom - labelsH)
        if (area.height() < 10f) return
        // 細い線の集まり（蛍光表示管の 1 本 1 本の線）
        val unit = (area.height() / 40f).coerceAtLeast(1.0f)
        val rows = floor(area.height() / unit).toInt()
        if (rows < 4) return
        val segH = unit * 0.84f
        val fh = rows * unit
        val base = area.bottom
        val pitch = area.width() / m
        val barW = pitch * 0.8f
        val off = (pitch - barW) / 2f
        // 消えている部分（下は黄土色、上は暗い青緑。模様の行ごとの色）
        val ghost = pattern(ghostPattern, STYLE_EQ, rows, unit, segH, scale, ghost = true)
        val lit = pattern(litPattern, STYLE_EQ, rows, unit, segH, scale, ghost = false)
        placePattern(ghostPattern, scale, base)
        placePattern(litPattern, scale, base)
        ghostPaint.shader = ghost
        litPaint.shader = lit
        glowPaint.shader = lit
        for (j in 0 until m) {
            val x = area.left + j * pitch + off
            if (ghost != null) c.drawRect(x, base - fh, x + barW, base, ghostPaint)
            val litRows = (disp[j] * rows + 0.001f).toInt().coerceIn(0, rows)
            if (litRows > 0 && lit != null) {
                c.drawRect(x - pitch * 0.06f, base - litRows * unit - unit * 0.4f, x + barW + pitch * 0.06f, base, glowPaint)
                c.drawRect(x, base - litRows * unit, x + barW, base, litPaint)
            }
        }
        // 下の小さな周波数（水色）
        if (labelsH > 0f) {
            text.textSize = labelsH * 0.78f
            text.color = Color.rgb(80, 200, 220)
            for (k in EQ_FREQ.indices) {
                val col = (freqPos(EQ_FREQ[k]) * m).toInt().coerceIn(0, m - 1)
                val x = area.left + (col + 0.5f) * pitch
                c.drawText(EQ_TEXT[k], x, inner.bottom - labelsH * 0.1f, text)
            }
        }
        drawGloss(c, r)
    }

    private fun mix(a: Int, b: Int, t: Float): Int = SpectrumPainter.mix(a, b, t)
    private fun withAlpha(c: Int, a: Int): Int = SpectrumPainter.withAlpha(c, a)
}

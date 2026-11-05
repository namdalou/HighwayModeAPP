package com.example.spectrum

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * スペアナの表示の種類（[SpectrumStyle.VISUAL_LED] など）を描く。音楽の画面（スマホ・車）と、設定画面の見本で共用する。
 *
 *  - LED（標準）… 段に分かれたバー。段の色と隙間を描いた細い画像を模様にして、1 本 1 回で描く（v2.4 までと同じ）
 *  - カーオーディオ（ブルー・ステレオ・イコライザー。v2.7）… 利用者が示した動画の、昔のカーオーディオの表示を再現したもの。
 *    本数・色・動き・ピークの動きは [CarAudioPainter] が持つ（ブルーの保存する番号は v2.5 の「レトロ」・v2.6 の「カーオーディオ」と同じ）
 *  - なめらかなバー・ミラー・ライン・ドット … 同じ表示窓の中に、光るバー・線・LED マトリクスで描く（ピークは同じピンク系）
 *  - アナログメーター（アンバー照明・ブルー照明）… 照明付きのカーオーディオのメーター。目盛り板は画像として 1 回だけ描き、
 *    針はばね・ダンパーで俊敏に動かす（約 0.07 秒で振れる）
 *
 * 座標は呼び出し側の座標（PlayerRenderer の仮想座標）。scale は 1 単位あたりの画素数で、模様の画像を画面の画素に合わせるのに使う。
 * 毎フレームの割り当てはしない（模様・グラデーション・目盛り板は、区画・色・種類が変わったときだけ作り直す）。
 */
class SpectrumPainter(private val n: Int) {

    // ================================================================== 色

    private val colBottom = IntArray(n) { Color.rgb(0, 90, 200) }
    private val colTop = IntArray(n) { Color.rgb(40, 190, 245) }
    private var uniform = true
    /** LED（標準）のピーク（上の色を白に寄せた色） */
    private var peakColor = Color.rgb(215, 225, 235)
    /** カーオーディオ系のピーク・針の色（バーの色の反対側の色合い。標準の青ならピンク） */
    private var accent = Color.rgb(255, 70, 200)
    /** 色が変わるたびに増える（模様の作り直しの判断） */
    private var colorsVersion = 0

    /** 帯ごとの色（下・上）。前と同じなら何もしない */
    fun setColors(bottoms: IntArray, tops: IntArray) {
        var same = true
        for (i in 0 until n) {
            if (colBottom[i] != bottoms[i] || colTop[i] != tops[i]) {
                same = false
                break
            }
        }
        if (same) return
        System.arraycopy(bottoms, 0, colBottom, 0, n)
        System.arraycopy(tops, 0, colTop, 0, n)
        uniform = (1 until n).all { colBottom[it] == colBottom[0] && colTop[it] == colTop[0] }
        // ピークは上の色を白に寄せた色（どの色でも、バーより少し明るく見える）
        peakColor = mix(colTop[n / 2], Color.WHITE, 0.55f)
        accent = accentFor(colTop[n / 2], uniform)
        carAudio.setColors(colBottom, colTop, uniform)
        colorsVersion++
    }

    // ================================================================== 描画

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val plain = Paint()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val tmp = RectF()
    private val inner = RectF()
    private val matrix = Matrix()

    /** アナログメーターの針がまだ動いている（描き直しの間隔を決めるのに使う） */
    var animating = false
        private set

    /**
     * @param style 表示の種類（SpectrumStyle.VISUAL_*）
     * @param vis 帯ごとの高さ（0〜1）
     * @param peak 帯ごとのピーク（0〜1）
     * @param scale 1 単位あたりの画素数
     * @param segUnit LED の 1 段の高さ（隙間込み）
     * @param panel 表示窓（暗い板）を描いてよい（ほかの絵に重ねていない）
     * @param dtSec 前のフレームからの経過（メーターの針の動き）
     */
    fun draw(
        c: Canvas, r: RectF, style: Int, vis: FloatArray, peak: FloatArray,
        scale: Float, segUnit: Float, panel: Boolean, dtSec: Float
    ) {
        if (r.width() <= 2f || r.height() <= 2f || scale <= 0f) return
        animating = false
        if (style == SpectrumStyle.VISUAL_LED) {
            drawLed(c, r, vis, peak, scale, segUnit)
            return
        }
        // 昔のカーオーディオの再現（本数・色・動き・ピークの動きは CarAudioPainter が持つ）
        if (SpectrumStyle.isCarAudio(style)) {
            val st = when (style) {
                SpectrumStyle.VISUAL_CA_STEREO -> CarAudioPainter.STYLE_STEREO
                SpectrumStyle.VISUAL_CA_EQ -> CarAudioPainter.STYLE_EQ
                else -> CarAudioPainter.STYLE_BLUE
            }
            carAudio.draw(c, r, st, vis, scale, dtSec)
            animating = carAudio.animating
            return
        }
        // カーオーディオ系: 表示窓の中に描く（メーターは、表示窓をメーターの大きさに合わせる）
        val pad = (min(r.width(), r.height()) * 0.06f).coerceIn(2.5f, 7f)
        inner.set(r.left + pad, r.top + pad, r.right - pad, r.bottom - pad)
        val meter = style == SpectrumStyle.VISUAL_METER || style == SpectrumStyle.VISUAL_METER_DARK
        val win = if (meter) meterWindow(inner, pad, r) else r
        if (panel) drawPanel(c, win)
        when (style) {
            SpectrumStyle.VISUAL_BARS -> drawBars(c, inner, vis, peak, mirror = false)
            SpectrumStyle.VISUAL_MIRROR -> drawBars(c, inner, vis, peak, mirror = true)
            SpectrumStyle.VISUAL_LINE -> drawLine(c, inner, vis, peak)
            SpectrumStyle.VISUAL_DOTS -> drawDots(c, inner, vis, peak, scale)
            SpectrumStyle.VISUAL_METER -> drawMeters(c, inner, vis, scale, blue = false, dtSec = dtSec)
            else -> drawMeters(c, inner, vis, scale, blue = true, dtSec = dtSec)
        }
        if (panel) drawGloss(c, win)
    }

    // ================================================================== 表示窓（カーオーディオの液晶・蛍光表示管の窓）

    private val panelKey = RectF()
    private var panelBg: LinearGradient? = null
    private var panelGlowA: RadialGradient? = null
    private var panelGlowB: RadialGradient? = null
    private var panelGloss: LinearGradient? = null

    private fun panelRadius(r: RectF) = (min(r.width(), r.height()) * 0.07f).coerceIn(3f, 8f)

    private fun checkPanel(r: RectF) {
        if (r == panelKey && panelBg != null) return
        panelKey.set(r)
        val big = max(r.width(), r.height())
        // 奥が深い紫がかった黒（下ほど紫）
        panelBg = LinearGradient(
            0f, r.top, 0f, r.bottom,
            intArrayOf(Color.rgb(16, 12, 34), Color.rgb(11, 8, 25), Color.rgb(30, 12, 48)), floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        // 左下からにじむ紫の光（写真の照明のもれ）・右上のかすかな青
        panelGlowA = RadialGradient(
            r.left + r.width() * 0.1f, r.bottom, big * 0.72f,
            intArrayOf(Color.argb(110, 176, 44, 206), Color.argb(34, 124, 32, 176), Color.argb(0, 124, 32, 176)), floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        panelGlowB = RadialGradient(
            r.right - r.width() * 0.08f, r.top, big * 0.5f,
            Color.argb(34, 50, 110, 255), Color.argb(0, 50, 110, 255), Shader.TileMode.CLAMP
        )
        // ガラスの映り込み（上の方を少しだけ明るく）
        panelGloss = LinearGradient(
            0f, r.top, 0f, r.top + r.height() * 0.42f,
            Color.argb(20, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP
        )
    }

    private fun drawPanel(c: Canvas, r: RectF) {
        checkPanel(r)
        val rad = panelRadius(r)
        fill.shader = panelBg
        c.drawRoundRect(r, rad, rad, fill)
        fill.shader = panelGlowA
        c.drawRoundRect(r, rad, rad, fill)
        fill.shader = panelGlowB
        c.drawRoundRect(r, rad, rad, fill)
        fill.shader = null
        // 縁（外は黒、内側に細い光）
        stroke.shader = null
        stroke.strokeWidth = 1.4f
        stroke.color = Color.rgb(3, 2, 8)
        c.drawRoundRect(r, rad, rad, stroke)
        tmp.set(r.left + 1.1f, r.top + 1.1f, r.right - 1.1f, r.bottom - 1.1f)
        stroke.strokeWidth = 0.6f
        stroke.color = Color.argb(110, 110, 96, 170)
        c.drawRoundRect(tmp, rad * 0.8f, rad * 0.8f, stroke)
    }

    private fun drawGloss(c: Canvas, r: RectF) {
        checkPanel(r)
        val rad = panelRadius(r)
        fill.shader = panelGloss
        tmp.set(r.left + 1.2f, r.top + 1.2f, r.right - 1.2f, r.top + r.height() * 0.42f)
        c.drawRoundRect(tmp, rad * 0.8f, rad * 0.8f, fill)
        fill.shader = null
    }

    // ================================================================== 段の模様（LED・カーオーディオ共通）

    /**
     * 段の模様: 幅 1 画素・高さ = 全段分の画像に、段ごとの色（下の濃い色 → 上の明るい色）と段の隙間（透明）を描く。
     * 画面の画素と 1 対 1 で重なるよう、描く倍率に合わせて作る（区画・色が変わったときだけ）。
     * fade > 0 なら映り込み用（上＝根元に近いほど濃く、下へ行くほど薄く。並びも上下を逆に）
     */
    private class SegCache(n: Int, val bright: Boolean) {
        val shaders = arrayOfNulls<BitmapShader>(n)
        val heights = IntArray(n)
        var rows = -1
        var unit = -1f
        var seg = -1f
        var scale = -1f
        var ver = -1
        var uni = false
    }

    private val ledCache = SegCache(n, bright = false)

    /** 区画・色が変わっていたら、段の模様を捨てる（1 フレームに 1 回） */
    private fun SegCache.check(rows: Int, unit: Float, segH: Float, scale: Float) {
        if (rows != this.rows || unit != this.unit || segH != seg || scale != this.scale || colorsVersion != ver || uniform != uni) {
            for (k in shaders.indices) shaders[k] = null
            this.rows = rows
            this.unit = unit
            seg = segH
            this.scale = scale
            ver = colorsVersion
            uni = uniform
        }
    }

    private fun SegCache.shader(i: Int, reflection: Boolean = false): BitmapShader? {
        val idx = if (uniform) 0 else i
        shaders[idx]?.let { return it }
        // カーオーディオは下の色も明るめに（発光している表示らしく）
        val cb = if (bright) mix(colBottom[idx], colTop[idx], 0.3f) else colBottom[idx]
        val ct = if (bright) mix(colTop[idx], Color.WHITE, 0.12f) else colTop[idx]
        val hPx = ceil(rows * unit * scale).toInt().coerceIn(1, 4096)
        val px = IntArray(hPx)
        val total = rows * unit
        for (y in 0 until hPx) {
            if (reflection) {
                // 映り込み: 画像の上端 = 根元。下へ行くほど上の段（明るい色）で、薄くなる
                val fromBase = (y + 0.5f) / scale
                val rr = floor(fromBase / unit).toInt()
                val within = fromBase - rr * unit
                px[y] = if (rr in 0 until rows && within < seg) {
                    val k = if (rows > 1) rr.toFloat() / (rows - 1) else 0f
                    val a = (0.34f * (1f - k).pow(1.6f) * 255f).toInt()
                    withAlpha(mix(cb, ct, k), a)
                } else 0
            } else {
                // 画像の上端からの位置 → 下端（バーの根元）からの位置
                val fromBottom = total - (y + 0.5f) / scale
                val rr = floor(fromBottom / unit).toInt()
                val within = fromBottom - rr * unit
                px[y] = if (rr in 0 until rows && within < seg) {
                    mix(cb, ct, if (rows > 1) rr.toFloat() / (rows - 1) else 0f)
                } else 0
            }
        }
        val bmp = try {
            Bitmap.createBitmap(1, hPx, Bitmap.Config.ARGB_8888)
        } catch (_: Throwable) {
            return null
        }
        bmp.setPixels(px, 0, 1, 0, 0, 1, hPx)
        val s = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.CLAMP)
        shaders[idx] = s
        heights[idx] = hPx
        return s
    }

    // ================================================================== LED（標準）

    private val ledPaint = Paint()

    private fun drawLed(c: Canvas, r: RectF, vis: FloatArray, peak: FloatArray, scale: Float, segUnit: Float) {
        val x0 = r.left
        val y1 = r.bottom
        val gap = 2f
        val barW = (r.width() - gap * (n - 1)) / n
        val unit = segUnit
        val segH = unit * 0.72f
        val rows = ((r.height() + unit - segH) / unit).toInt()
        if (rows <= 0) return
        ledCache.check(rows, unit, segH, scale)
        for (i in 0 until n) {
            val sh = ledCache.shader(i)
            if (sh != null && (i == 0 || !uniform)) {
                // 模様の 1 画素 = 画面の 1 画素、模様の下端 = バーの根元
                matrix.setScale(1f / scale, 1f / scale)
                matrix.postTranslate(0f, y1 - ledCache.heights[if (uniform) 0 else i] / scale)
                sh.setLocalMatrix(matrix)
            }
            val bx = x0 + i * (barW + gap)
            val lit = (vis[i] * rows).toInt()       // 切り捨て: 届いた段だけ点灯
            if (lit > 0) {
                if (sh != null) {
                    ledPaint.shader = sh
                    c.drawRect(bx, y1 - lit * unit, bx + barW, y1, ledPaint)
                } else {
                    for (rr in 0 until lit) {
                        plain.color = mix(colBottom[i], colTop[i], rr.toFloat() / max(1, rows - 1))
                        val b = y1 - rr * unit
                        c.drawRect(bx, b - segH, bx + barW, b, plain)
                    }
                }
            }
            if (peak[i] > 0.04f) {
                val row = min(max((peak[i] * rows).toInt(), lit), rows - 1)
                val b = y1 - row * unit
                plain.color = peakColor
                c.drawRect(bx, b - segH * 0.6f, bx + barW, b, plain)
            }
        }
    }

    // ================================================================== カーオーディオ（昔の表示の再現）

    /** 昔のカーオーディオの 3 種類（ブルー・ステレオ・イコライザー）を描く部品（v2.7） */
    private val carAudio = CarAudioPainter(n)

    // ================================================================== なめらかなバー・ミラー

    private val barShaders = arrayOfNulls<LinearGradient>(n)
    private var bkTop = Float.NaN
    private var bkBottom = Float.NaN
    private var bkMirror = false
    private var bkVer = -1
    private var bkUni = false
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 区画・色が変わっていたら、グラデーションを捨てる（1 フレームに 1 回） */
    private fun checkBarCache(top: Float, bottom: Float, mirror: Boolean) {
        if (top != bkTop || bottom != bkBottom || mirror != bkMirror || colorsVersion != bkVer || uniform != bkUni) {
            for (k in 0 until n) barShaders[k] = null
            bkTop = top
            bkBottom = bottom
            bkMirror = mirror
            bkVer = colorsVersion
            bkUni = uniform
        }
    }

    private fun barShader(i: Int, top: Float, bottom: Float, mirror: Boolean): LinearGradient {
        val idx = if (uniform) 0 else i
        barShaders[idx]?.let { return it }
        val g = if (mirror) {
            LinearGradient(0f, top, 0f, bottom, intArrayOf(colTop[idx], colBottom[idx], colTop[idx]), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        } else {
            LinearGradient(0f, bottom, 0f, top, colBottom[idx], colTop[idx], Shader.TileMode.CLAMP)
        }
        barShaders[idx] = g
        return g
    }

    private fun drawBars(c: Canvas, r: RectF, vis: FloatArray, peak: FloatArray, mirror: Boolean) {
        val gap = max(1.5f, r.width() / n * 0.24f)
        val barW = (r.width() - gap * (n - 1)) / n
        val rad = min(barW * 0.4f, 3f)
        val cy = r.centerY()
        // なめらかなバーは下に映り込み（全体の 2 割弱）
        val reflH = if (!mirror && r.height() >= 46f) r.height() * 0.17f else 0f
        val base = r.bottom - reflH - (if (reflH > 0f) 1.5f else 0f)
        val h = base - r.top
        capPaint.color = accent
        checkBarCache(r.top, if (mirror) r.bottom else base, mirror)
        if (mirror) {
            plain.shader = null
            plain.color = withAlpha(colTop[0], 90)
            c.drawRect(r.left, cy - 0.35f, r.right, cy + 0.35f, plain)
        }
        for (i in 0 until n) {
            val sh = barShader(i, r.top, if (mirror) r.bottom else base, mirror)
            barPaint.shader = sh
            val bx = r.left + i * (barW + gap)
            if (mirror) {
                val hh = max(0.7f, vis[i] * (r.height() / 2f - 1.5f))
                // にじみ
                barPaint.alpha = 60
                tmp.set(bx - 0.8f, cy - hh - 0.8f, bx + barW + 0.8f, cy + hh + 0.8f)
                c.drawRoundRect(tmp, rad + 0.8f, rad + 0.8f, barPaint)
                barPaint.alpha = 255
                tmp.set(bx, cy - hh, bx + barW, cy + hh)
                c.drawRoundRect(tmp, rad, rad, barPaint)
                if (peak[i] > 0.03f) {
                    val ph = peak[i] * (r.height() / 2f - 1.5f) + 1f
                    val capH = max(1.2f, barW * 0.22f)
                    tmp.set(bx, cy - ph - capH, bx + barW, cy - ph)
                    c.drawRoundRect(tmp, capH / 2f, capH / 2f, capPaint)
                    tmp.set(bx, cy + ph, bx + barW, cy + ph + capH)
                    c.drawRoundRect(tmp, capH / 2f, capH / 2f, capPaint)
                }
            } else {
                val bh = vis[i] * (h - 3f)
                if (bh > 0.6f) {
                    barPaint.alpha = 60
                    tmp.set(bx - 0.9f, base - bh - 0.9f, bx + barW + 0.9f, base)
                    c.drawRoundRect(tmp, rad + 0.9f, rad + 0.9f, barPaint)
                    barPaint.alpha = 255
                    tmp.set(bx, base - bh, bx + barW, base)
                    c.drawRoundRect(tmp, rad, rad, barPaint)
                    if (reflH > 0f) {
                        // 映り込み（縮めて薄く）
                        barPaint.alpha = 46
                        val rh = bh * (reflH / h)
                        tmp.set(bx, base + 1.5f, bx + barW, base + 1.5f + rh)
                        c.drawRoundRect(tmp, rad, rad, barPaint)
                        barPaint.alpha = 255
                    }
                }
                if (peak[i] > 0.03f) {
                    val capH = max(1.4f, barW * 0.24f)
                    val py = base - peak[i] * (h - 3f) - 1.4f
                    tmp.set(bx, py - capH, bx + barW, py)
                    c.drawRoundRect(tmp, capH / 2f, capH / 2f, capPaint)
                }
            }
        }
        barPaint.shader = null
        if (reflH > 0f) {
            plain.shader = null
            plain.color = withAlpha(colTop[0], 60)
            c.drawRect(r.left, base + 0.4f, r.right, base + 0.9f, plain)
        }
    }

    // ================================================================== ライン

    private val curve = Path()
    private val area = Path()
    private val peakCurve = Path()
    private var lineFill: LinearGradient? = null
    private var lineStrokeShader: LinearGradient? = null
    private val lineKeyRect = RectF()
    private var lnVer = -1
    private var lnUni = false
    private val px = FloatArray(n)
    private val py = FloatArray(n)

    private fun drawLine(c: Canvas, r: RectF, vis: FloatArray, peak: FloatArray) {
        if (r != lineKeyRect || colorsVersion != lnVer || uniform != lnUni || lineFill == null) {
            lineKeyRect.set(r)
            lnVer = colorsVersion
            lnUni = uniform
            if (uniform) {
                lineFill = LinearGradient(0f, r.top, 0f, r.bottom, withAlpha(colTop[0], 170), withAlpha(colBottom[0], 20), Shader.TileMode.CLAMP)
                lineStrokeShader = null
            } else {
                val cols = IntArray(n) { withAlpha(colTop[it], 110) }
                lineFill = LinearGradient(r.left, 0f, r.right, 0f, cols, null, Shader.TileMode.CLAMP)
                lineStrokeShader = LinearGradient(r.left, 0f, r.right, 0f, colTop, null, Shader.TileMode.CLAMP)
            }
        }
        // 目盛りの線（オシロスコープのような方眼）
        plain.shader = null
        plain.color = withAlpha(colTop[0], 26)
        val rowsG = 5
        for (k in 1 until rowsG) {
            val y = r.top + r.height() * k / rowsG
            c.drawRect(r.left, y - 0.25f, r.right, y + 0.25f, plain)
        }
        val colsG = 8
        for (k in 1 until colsG) {
            val x = r.left + r.width() * k / colsG
            c.drawRect(x - 0.25f, r.top, x + 0.25f, r.bottom, plain)
        }
        val h = r.height() - 2.5f
        val step = r.width() / n
        for (i in 0 until n) {
            px[i] = r.left + (i + 0.5f) * step
            py[i] = r.bottom - vis[i] * h
        }
        buildSmooth(curve, r.left, r.right, false, r.bottom)
        buildSmooth(area, r.left, r.right, true, r.bottom)
        fill.shader = lineFill
        c.drawPath(area, fill)
        fill.shader = null
        // 光る線（太く淡い線の上に細い線）
        stroke.shader = lineStrokeShader
        stroke.color = colTop[0]
        stroke.alpha = 70
        stroke.strokeWidth = 4.2f
        c.drawPath(curve, stroke)
        stroke.alpha = 255
        stroke.strokeWidth = 1.7f
        c.drawPath(curve, stroke)
        stroke.shader = null
        // ピークの線（ピンク）
        for (i in 0 until n) py[i] = r.bottom - peak[i] * h - 1.2f
        buildSmooth(peakCurve, r.left, r.right, false, r.bottom)
        stroke.color = accent
        stroke.alpha = 160
        stroke.strokeWidth = 1.0f
        c.drawPath(peakCurve, stroke)
        stroke.alpha = 255
    }

    /** px/py の点をなめらかな曲線でつなぐ（closed なら下までふさいで塗る形にする） */
    private fun buildSmooth(p: Path, left: Float, right: Float, closed: Boolean, bottom: Float) {
        p.reset()
        if (closed) {
            p.moveTo(left, bottom)
            p.lineTo(left, py[0])
        } else {
            p.moveTo(left, py[0])
        }
        p.lineTo(px[0], py[0])
        for (i in 1 until n) {
            val mx = (px[i - 1] + px[i]) / 2f
            val my = (py[i - 1] + py[i]) / 2f
            p.quadTo(px[i - 1], py[i - 1], mx, my)
        }
        p.lineTo(px[n - 1], py[n - 1])
        p.lineTo(right, py[n - 1])
        if (closed) {
            p.lineTo(right, bottom)
            p.close()
        }
    }

    // ================================================================== ドット（LED マトリクス）

    private val dotShaders = arrayOfNulls<BitmapShader>(n)
    private var dkPitch = -1
    private var dkRows = -1
    private var dkRad = -1f
    private var dkScale = -1f
    private var dkVer = -1
    private var dkUni = false
    private val dotPaint = Paint()
    private val dotGhostPaint = Paint().apply { alpha = 34 }

    /** 区画・色が変わっていたら、LED の列の模様を捨てる（1 フレームに 1 回） */
    private fun checkDotCache(pitchPx: Int, rows: Int, rad: Float, scale: Float) {
        if (pitchPx != dkPitch || rows != dkRows || rad != dkRad || scale != dkScale || colorsVersion != dkVer || uniform != dkUni) {
            for (k in 0 until n) dotShaders[k] = null
            dkPitch = pitchPx
            dkRows = rows
            dkRad = rad
            dkScale = scale
            dkVer = colorsVersion
            dkUni = uniform
        }
    }

    private fun dotShader(i: Int, pitchPx: Int, rows: Int, rad: Float, scale: Float): BitmapShader? {
        val idx = if (uniform) 0 else i
        dotShaders[idx]?.let { return it }
        val hPx = (pitchPx * rows).coerceIn(1, 4096)
        val bmp = try {
            Bitmap.createBitmap(pitchPx, hPx, Bitmap.Config.ARGB_8888)
        } catch (_: Throwable) {
            return null
        }
        val bc = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (k in 0 until rows) {
            p.color = mix(colBottom[idx], colTop[idx], if (rows > 1) k.toFloat() / (rows - 1) else 0f)
            val cy = hPx - (k + 0.5f) * pitchPx
            bc.drawCircle(pitchPx / 2f, cy, rad * scale, p)
        }
        val s = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.CLAMP)
        dotShaders[idx] = s
        return s
    }

    private fun drawDots(c: Canvas, r: RectF, vis: FloatArray, peak: FloatArray, scale: Float) {
        // 1 列の幅を画面の画素の整数にして、模様の繰り返しと列の位置をぴったり合わせる
        val pitchPx = floor(r.width() * scale / n).toInt()
        if (pitchPx < 3) {
            drawBars(c, r, vis, peak, mirror = false)
            return
        }
        val pitch = pitchPx / scale
        val rows = floor(r.height() / pitch).toInt()
        if (rows < 3) {
            drawBars(c, r, vis, peak, mirror = false)
            return
        }
        val rad = pitch * 0.36f
        val gx0 = r.left + (r.width() - pitch * n) / 2f
        val yb = r.bottom
        val top = yb - rows * pitch
        checkDotCache(pitchPx, rows, rad, scale)
        if (uniform) {
            val sh = dotShader(0, pitchPx, rows, rad, scale) ?: return
            matrix.setScale(1f / scale, 1f / scale)
            matrix.postTranslate(gx0, top)
            sh.setLocalMatrix(matrix)
            // 消えている LED（全部の列を 1 回で）
            dotGhostPaint.shader = sh
            c.drawRect(gx0, top, gx0 + pitch * n, yb, dotGhostPaint)
            dotPaint.shader = sh
            for (i in 0 until n) {
                val lit = (vis[i] * rows).toInt()
                if (lit > 0) c.drawRect(gx0 + i * pitch, yb - lit * pitch, gx0 + (i + 1) * pitch, yb, dotPaint)
            }
        } else {
            for (i in 0 until n) {
                val sh = dotShader(i, pitchPx, rows, rad, scale) ?: continue
                matrix.setScale(1f / scale, 1f / scale)
                matrix.postTranslate(gx0 + i * pitch, top)
                sh.setLocalMatrix(matrix)
                dotGhostPaint.shader = sh
                c.drawRect(gx0 + i * pitch, top, gx0 + (i + 1) * pitch, yb, dotGhostPaint)
                val lit = (vis[i] * rows).toInt()
                if (lit > 0) {
                    dotPaint.shader = sh
                    c.drawRect(gx0 + i * pitch, yb - lit * pitch, gx0 + (i + 1) * pitch, yb, dotPaint)
                }
            }
        }
        fill.shader = null
        for (i in 0 until n) {
            if (peak[i] <= 0.04f) continue
            val row = min((peak[i] * rows).toInt(), rows - 1)
            val cx = gx0 + (i + 0.5f) * pitch
            val cyy = yb - (row + 0.5f) * pitch
            fill.color = withAlpha(accent, 80)
            c.drawCircle(cx, cyy, rad * 1.35f, fill)
            fill.color = accent
            c.drawCircle(cx, cyy, rad * 0.92f, fill)
        }
    }

    // ================================================================== アナログメーター（照明付き）

    private class Needle {
        var pos = 0f
        var vel = 0f
        var hold = 0f
    }

    private val needles = arrayOf(Needle(), Needle())
    private val faceRect = arrayOf(RectF(), RectF())
    private var faceBmp: Bitmap? = null
    private var fkW = -1
    private var fkH = -1
    private var fkBlue = false
    private var fkCount = -1
    private var fkVer = -1
    private var glassShader: LinearGradient? = null
    private var gkTop = Float.NaN
    private var gkBottom = Float.NaN
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** VU メーターの目盛り（dB）と位置（+3 を 1 とした電圧比） */
    private val vuMarks = floatArrayOf(-20f, -10f, -7f, -5f, -3f, -2f, -1f, 0f, 1f, 2f, 3f)
    private fun vuPos(db: Float): Float = 10f.pow((db - 3f) / 20f)

    /** 針の振れ（-44°〜+44°、少しだけ振り切れる） */
    private fun needleAngle(pos: Float): Float = -44f + 88f * pos.coerceIn(-0.02f, 1.06f)

    private val meterWin = RectF()
    private var meterCount = 1
    private var meterW = 0f
    private var meterH = 0f

    /** メーターの並び（2 つ並べるか・大きさ）を決めて、表示窓（メーターの外側に余白）を返す */
    private fun meterWindow(r: RectF, pad: Float, outer: RectF): RectF {
        val two = r.width() >= r.height() * 1.5f
        val count = if (two) 2 else 1
        // メーターの大きさ（横:縦 = 約 1.72:1。両端の目盛りの数字が板に収まるように）
        val gap = if (two) r.width() * 0.035f else 0f
        val cellW = (r.width() - gap * (count - 1)) / count
        val mh = min(r.height(), cellW / 1.72f)
        val mw = mh * 1.72f
        val totalW = mw * count + gap * (count - 1)
        val left = r.left + (r.width() - totalW) / 2f
        val topY = r.top + (r.height() - mh) / 2f
        for (k in 0 until count) faceRect[k].set(left + k * (mw + gap), topY, left + k * (mw + gap) + mw, topY + mh)
        meterCount = count
        meterW = mw
        meterH = mh
        meterWin.set(
            max(outer.left, left - pad), max(outer.top, topY - pad),
            min(outer.right, left + totalW + pad), min(outer.bottom, topY + mh + pad)
        )
        return meterWin
    }

    private fun drawMeters(c: Canvas, r: RectF, vis: FloatArray, scale: Float, blue: Boolean, dtSec: Float) {
        val count = meterCount
        val two = count == 2
        val mw = meterW
        val mh = meterH
        if (mw <= 1f || mh <= 1f) return

        // 入力: 低音（左）・高音（右）、1 つのときは全体。最大値と実効値を混ぜて、拍に合わせてよく振れるように
        val targets = meterTargets
        if (two) {
            targets[0] = level(vis, 0, 7)
            targets[1] = level(vis, n / 2, n)
        } else {
            targets[0] = level(vis, 0, n)
        }
        for (k in 0 until count) {
            val tgt = (targets[k] * 1.15f).coerceIn(0f, 1.06f)
            stepNeedle(needles[k], tgt, dtSec)
            if (kotlin.math.abs(needles[k].vel) > 0.01f || kotlin.math.abs(needles[k].pos - tgt) > 0.02f) animating = true
        }

        // 目盛り板（画像として 1 回だけ描く）
        val fw = ceil(mw * scale).toInt().coerceIn(8, 2048)
        val fh = ceil(mh * scale).toInt().coerceIn(8, 2048)
        if (fw != fkW || fh != fkH || blue != fkBlue || count != fkCount || colorsVersion != fkVer || faceBmp == null) {
            // 前の目盛り板は捨てるだけ（描いたばかりの画面がまだ使っていることがあるので recycle しない）
            faceBmp = try {
                Bitmap.createBitmap(fw, fh * count, Bitmap.Config.ARGB_8888).also { b ->
                    val bc = Canvas(b)
                    for (k in 0 until count) {
                        bc.save()
                        bc.translate(0f, (fh * k).toFloat())
                        bc.scale(fw / mw, fh / mh)
                        drawFace(bc, mw, mh, blue, if (count == 2) (if (k == 0) "BASS" else "TREBLE") else null)
                        bc.restore()
                    }
                }
            } catch (_: Throwable) {
                null
            }
            fkW = fw
            fkH = fh
            fkBlue = blue
            fkCount = count
            fkVer = colorsVersion
        }
        val face = faceBmp
        for (k in 0 until count) {
            val fr = faceRect[k]
            if (face != null) {
                srcRect.set(0, fh * k, fw, fh * (k + 1))
                c.drawBitmap(face, srcRect, fr, bmpPaint)
            } else {
                fill.shader = null
                fill.color = if (blue) Color.rgb(12, 26, 64) else Color.rgb(236, 170, 70)
                c.drawRoundRect(fr, mh * 0.06f, mh * 0.06f, fill)
            }
            drawNeedle(c, fr, mw, mh, needles[k], blue)
        }
    }

    private val meterTargets = FloatArray(2)
    private val srcRect = android.graphics.Rect()

    /** 帯の範囲の強さ（最大値と実効値を混ぜる） */
    private fun level(v: FloatArray, from: Int, to: Int): Float {
        var s = 0f
        var mx = 0f
        var cnt = 0
        for (i in from until min(to, n)) {
            s += v[i] * v[i]
            if (v[i] > mx) mx = v[i]
            cnt++
        }
        if (cnt == 0) return 0f
        return 0.55f * mx + 0.45f * sqrt(s / cnt)
    }

    /**
     * 針の動き（ばね・ダンパー）。v2.5 は VU メーターらしく約 0.3 秒かけて振れていたが、
     * v2.6 から俊敏に（約 0.07 秒で目標に届き、ほんの少しだけ行き過ぎる）
     */
    private fun stepNeedle(nd: Needle, target: Float, dt: Float) {
        var t = dt.coerceIn(0f, 0.1f)
        while (t > 0f) {
            val h = min(t, 0.004f)
            val acc = NEEDLE_K * (target - nd.pos) - NEEDLE_D * nd.vel
            nd.vel += acc * h
            nd.pos += nd.vel * h
            t -= h
        }
        nd.pos = nd.pos.coerceIn(-0.02f, 1.08f)
        nd.hold = if (nd.pos >= vuPos(1f)) 0.35f else max(0f, nd.hold - dt)
    }

    /** 目盛り板（大きさ w×h の座標で描く）。blue = ブルー照明、そうでなければアンバー照明 */
    private fun drawFace(c: Canvas, w: Float, h: Float, blue: Boolean, label: String?) {
        val rr = RectF(0f, 0f, w, h)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val rad = h * 0.07f
        // 板と照明（下の中央から照らす）
        p.shader = if (blue) {
            RadialGradient(
                w / 2f, h * 0.96f, w * 0.78f,
                intArrayOf(Color.rgb(46, 104, 210), Color.rgb(18, 44, 116), Color.rgb(6, 10, 32)), floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        } else {
            RadialGradient(
                w / 2f, h * 0.96f, w * 0.78f,
                intArrayOf(Color.rgb(255, 222, 140), Color.rgb(246, 164, 54), Color.rgb(132, 62, 10)), floatArrayOf(0f, 0.52f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        c.drawRoundRect(rr, rad, rad, p)
        p.shader = null
        // 下のカバー（針の根元を隠す板）
        val coverTop = h * 0.84f
        p.color = if (blue) Color.rgb(6, 6, 14) else Color.rgb(28, 18, 10)
        c.drawRect(h * 0.05f, coverTop, w - h * 0.05f, h - h * 0.05f, p)
        p.color = if (blue) Color.argb(90, 120, 160, 255) else Color.argb(90, 255, 210, 140)
        c.drawRect(h * 0.05f, coverTop, w - h * 0.05f, coverTop + h * 0.008f, p)
        // 枠
        val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = h * 0.03f
            color = if (blue) Color.rgb(24, 30, 52) else Color.rgb(70, 40, 16)
        }
        c.drawRoundRect(RectF(h * 0.015f, h * 0.015f, w - h * 0.015f, h - h * 0.015f), rad, rad, sp)

        // 目盛りの弧（中心は板の下の外側）
        val cx = w / 2f
        val cy = h * 1.18f
        val arcR = h * 0.95f
        val scaleColor = if (blue) Color.rgb(150, 226, 255) else Color.rgb(58, 28, 6)
        val redColor = if (blue) accent else Color.rgb(190, 24, 16)
        val arc = RectF(cx - arcR, cy - arcR, cx + arcR, cy + arcR)
        val a0 = needleAngle(vuPos(-20f))
        val aZero = needleAngle(vuPos(0f))
        val a3 = needleAngle(vuPos(3f))
        sp.strokeCap = Paint.Cap.BUTT
        if (blue) {
            // 光る目盛り（太く淡い線の上に細い線）
            sp.color = Color.argb(70, 120, 200, 255)
            sp.strokeWidth = h * 0.055f
            c.drawArc(arc, -90f + a0, aZero - a0, false, sp)
        }
        sp.color = scaleColor
        sp.strokeWidth = h * 0.018f
        c.drawArc(arc, -90f + a0, aZero - a0, false, sp)
        if (blue) {
            sp.color = withAlpha(redColor, 90)
            sp.strokeWidth = h * 0.1f
            c.drawArc(RectF(cx - arcR - h * 0.03f, cy - arcR - h * 0.03f, cx + arcR + h * 0.03f, cy + arcR + h * 0.03f), -90f + aZero, a3 - aZero, false, sp)
        }
        sp.color = redColor
        sp.strokeWidth = h * 0.06f
        c.drawArc(RectF(cx - arcR - h * 0.03f, cy - arcR - h * 0.03f, cx + arcR + h * 0.03f, cy + arcR + h * 0.03f), -90f + aZero, a3 - aZero, false, sp)
        // 目盛りの線と数字
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = h * 0.085f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        for (db in vuMarks) {
            val a = Math.toRadians(needleAngle(vuPos(db)).toDouble())
            val major = db == -20f || db == -10f || db == -5f || db == 0f || db == 3f
            val len = if (major) h * 0.09f else h * 0.055f
            val sx = cx + (sin(a) * arcR).toFloat()
            val sy = cy - (cos(a) * arcR).toFloat()
            val ex = cx + (sin(a) * (arcR + len)).toFloat()
            val ey = cy - (cos(a) * (arcR + len)).toFloat()
            sp.strokeWidth = h * (if (major) 0.022f else 0.016f)
            sp.color = if (db > 0f) redColor else scaleColor
            c.drawLine(sx, sy, ex, ey, sp)
            val tx = cx + (sin(a) * (arcR + h * 0.14f)).toFloat()
            val ty = cy - (cos(a) * (arcR + h * 0.14f)).toFloat() + tp.textSize * 0.35f
            tp.color = if (db > 0f) redColor else scaleColor
            val s = if (db > 0f) "+${db.toInt()}" else if (db == 0f) "0" else "${db.toInt()}"
            c.drawText(s, tx, ty, tp)
        }
        // 「VU」と、低音・高音の名前
        tp.textSize = h * 0.17f
        tp.color = if (blue) Color.rgb(170, 232, 255) else Color.rgb(64, 32, 8)
        c.drawText("VU", cx, h * 0.70f, tp)
        if (label != null) {
            tp.textSize = h * 0.075f
            tp.typeface = Typeface.DEFAULT_BOLD
            tp.textAlign = Paint.Align.LEFT
            tp.color = if (blue) Color.argb(210, 150, 210, 255) else Color.rgb(96, 52, 14)
            c.drawText(label, h * 0.12f, h * 0.78f, tp)
        }
        // ピークのランプの台（「VU」の右）
        p.color = if (blue) Color.rgb(40, 10, 30) else Color.rgb(90, 30, 10)
        c.drawCircle(w / 2f + h * 0.36f, h * 0.645f, h * 0.045f, p)
    }

    private fun drawNeedle(c: Canvas, fr: RectF, w: Float, h: Float, nd: Needle, blue: Boolean) {
        val cx = fr.left + w / 2f
        val cy = fr.top + h * 1.18f
        val a = Math.toRadians(needleAngle(nd.pos).toDouble())
        val len = h * 1.0f
        val tx = cx + (sin(a) * len).toFloat()
        val ty = cy - (cos(a) * len).toFloat()
        c.save()
        c.clipRect(fr.left + h * 0.04f, fr.top + h * 0.04f, fr.right - h * 0.04f, fr.top + h * 0.84f)
        stroke.shader = null
        stroke.strokeCap = Paint.Cap.ROUND
        if (blue) {
            // 光る針（ピンク）
            stroke.color = withAlpha(accent, 90)
            stroke.strokeWidth = h * 0.05f
            c.drawLine(cx, cy, tx, ty, stroke)
            stroke.color = mix(accent, Color.WHITE, 0.35f)
            stroke.strokeWidth = h * 0.018f
            c.drawLine(cx, cy, tx, ty, stroke)
        } else {
            // 影と黒い針
            stroke.color = Color.argb(70, 60, 20, 0)
            stroke.strokeWidth = h * 0.022f
            c.drawLine(cx + h * 0.014f, cy + h * 0.014f, tx + h * 0.014f, ty + h * 0.014f, stroke)
            stroke.color = Color.rgb(24, 14, 8)
            stroke.strokeWidth = h * 0.017f
            c.drawLine(cx, cy, tx, ty, stroke)
        }
        c.restore()
        // ピークのランプ
        if (nd.hold > 0f) {
            fill.shader = null
            val lx = fr.left + w / 2f + h * 0.36f
            val ly = fr.top + h * 0.645f
            fill.color = Color.argb(110, 255, 40, 40)
            c.drawCircle(lx, ly, h * 0.075f, fill)
            fill.color = Color.rgb(255, 70, 50)
            c.drawCircle(lx, ly, h * 0.04f, fill)
        }
        // ガラスの映り込み
        if (fr.top != gkTop || fr.bottom != gkBottom || glassShader == null) {
            glassShader = LinearGradient(0f, fr.top, 0f, fr.top + h * 0.5f, Color.argb(54, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
            gkTop = fr.top
            gkBottom = fr.bottom
        }
        fill.shader = glassShader
        tmp.set(fr.left + h * 0.03f, fr.top + h * 0.03f, fr.right - h * 0.03f, fr.top + h * 0.5f)
        c.drawRoundRect(tmp, h * 0.06f, h * 0.06f, fill)
        fill.shader = null
    }

    // ================================================================== 見本（設定画面）

    private val demoVis = FloatArray(n)
    private val demoPeak = FloatArray(n)

    /**
     * 見本の表示（設定画面）: 音楽が流れているような動きを作って描く。
     * @param t 秒（時間とともに動く）。still なら動きを止めた 1 枚
     */
    fun drawDemo(c: Canvas, r: RectF, style: Int, scale: Float, t: Float, dtSec: Float, still: Boolean = false) {
        for (i in 0 until n) {
            val x = i.toFloat() / (n - 1)
            // 低音が強く高音ほど弱い、よくある曲の形に、帯ごとにずれた揺れを足す
            val base = 0.30f + 0.62f * exp(-x * 1.7f) + 0.10f * exp(-((x - 0.55f) * 6f).pow(2))
            val wobble = 0.62f + 0.38f * sin(t * 2.3f + i * 0.61f) * cos(t * 1.1f - i * 0.27f)
            val v = (base * wobble).coerceIn(0f, 1f)
            demoVis[i] = v
            demoPeak[i] = if (still) (v + 0.12f).coerceAtMost(1f) else max(v, demoPeak[i] - dtSec * 0.6f)
        }
        if (still) {
            // 止めた見本では、メーターの針をその位置に置く
            needles[0].pos = (level(demoVis, 0, 7) * 1.15f).coerceAtMost(1.06f)
            needles[1].pos = (level(demoVis, n / 2, n) * 1.15f).coerceAtMost(1.06f)
            needles[0].vel = 0f
            needles[1].vel = 0f
        }
        draw(c, r, style, demoVis, demoPeak, scale, max(3.5f, r.height() / 22f), true, if (still) 0f else dtSec)
    }

    companion object {
        /** 針のばねの強さと減衰（約 30 rad/s・減衰比 0.7: 約 0.07 秒で振れ、行き過ぎは 5% ほど） */
        private const val NEEDLE_K = 900f
        private const val NEEDLE_D = 42f

        fun mix(a: Int, b: Int, t: Float): Int {
            val k = t.coerceIn(0f, 1f)
            return Color.rgb(
                (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
                (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
                (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt()
            )
        }

        fun withAlpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

        /**
         * カーオーディオ系のピーク・針の色: バーの色の反対側の色合い（標準の青 → ピンク、緑 → 青紫など）。
         * 虹色（帯ごとに色が違う）・白っぽい色はピンク
         */
        fun accentFor(top: Int, uniform: Boolean): Int {
            val hsv = FloatArray(3)
            Color.colorToHSV(top, hsv)
            if (!uniform || hsv[1] < 0.15f) return Color.rgb(255, 70, 200)
            hsv[0] = (hsv[0] + 125f) % 360f
            hsv[1] = 0.72f
            hsv[2] = 1f
            return Color.HSVToColor(hsv)
        }

        /** 表示の種類ごとの、音楽の画面の背景（上・下）。カーオーディオ系は、表示窓が引き立つ紫がかった暗い灰色 */
        fun screenBackground(style: Int, out: IntArray) {
            if (style == SpectrumStyle.VISUAL_LED) {
                out[0] = Color.rgb(14, 42, 58)
                out[1] = Color.rgb(4, 12, 20)
            } else {
                out[0] = Color.rgb(30, 27, 44)
                out[1] = Color.rgb(8, 7, 13)
            }
        }
    }
}

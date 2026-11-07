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
import kotlin.math.log10
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
 *  - なめらかなバー・ミラー・ライン・ドット … 光るバー・線・LED マトリクス（ピークはバーの色を白に寄せた色）
 *  - アナログメーター（アンバー照明・ブルー照明）… 照明付きのカーオーディオのメーター。目盛り板は画像として 1 回だけ描き、
 *    針はばね・ダンパーで俊敏に動かす（約 0.05 秒で振れる）。入力は「最近 2 秒の平均からどれだけ大きいか」で、端に張り付かない（v2.8）
 *
 * v2.8 から、どの表示も表示窓（枠・紫の板・ガラスの映り込み）や消えている段を描かず、LED（標準）と同じように
 * 音楽の画面の背景の上にバー（ブロック）だけを描く。
 *
 * 座標は呼び出し側の座標（PlayerRenderer の仮想座標）。scale は 1 単位あたりの画素数で、模様の画像を画面の画素に合わせるのに使う。
 * 毎フレームの割り当てはしない（模様・グラデーション・目盛り板は、区画・色・種類が変わったときだけ作り直す）。
 */
class SpectrumPainter(private val n: Int) {

    // ================================================================== 色

    private val colBottom = IntArray(n) { Color.rgb(0, 90, 200) }
    private val colTop = IntArray(n) { Color.rgb(40, 190, 245) }
    private var uniform = true
    /** ピーク（上の色を白に寄せた色。どの色でも、バーより少し明るく見える） */
    private var peakColor = Color.rgb(215, 225, 235)
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
        carAudio.setColors(colBottom, colTop, uniform)
        coarse?.let { co ->
            for (j in 0 until COARSE_BANDS) {
                val i = j * n / COARSE_BANDS
                cBottom[j] = colBottom[i]
                cTop[j] = colTop[i]
            }
            co.setColors(cBottom, cTop)
        }
        colorsVersion++
    }

    // ================================================================== 24 本で描く表示（v2.13）

    /**
     * なめらかなバー・ミラー・ライン・ドット・メーターは、これまでどおり 24 本で描く。
     * 解析の帯が 48 のときは、2 本ずつまとめて（高さは平均・ピークは大きい方）24 本用の描き手に渡す
     */
    private val coarse: SpectrumPainter? = if (n > COARSE_BANDS) SpectrumPainter(COARSE_BANDS) else null
    private val cVis = FloatArray(COARSE_BANDS)
    private val cPeak = FloatArray(COARSE_BANDS)
    private val cRaw = FloatArray(COARSE_BANDS)
    private val cBottom = IntArray(COARSE_BANDS)
    private val cTop = IntArray(COARSE_BANDS)

    private fun toCoarse(src: FloatArray, dst: FloatArray, useMax: Boolean) {
        for (j in 0 until COARSE_BANDS) {
            val a = j * n / COARSE_BANDS
            val b = max(a + 1, (j + 1) * n / COARSE_BANDS)
            var sum = 0f
            var mx = 0f
            for (i in a until min(b, n)) {
                sum += src[i]
                if (src[i] > mx) mx = src[i]
            }
            dst[j] = if (useMax) mx else sum / (b - a)
        }
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
     * @param dtSec 前のフレームからの経過（メーターの針の動き）
     * @param raw 帯ごとの強さ（高さに伸ばして 1 で切る前の値）。メーターの入力に使う（無ければ vis）
     */
    fun draw(
        c: Canvas, r: RectF, style: Int, vis: FloatArray, peak: FloatArray,
        scale: Float, segUnit: Float, dtSec: Float, raw: FloatArray? = null
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
        // そのほか（24 本で描く表示）: 帯が 48 なら 2 本ずつまとめて 24 本用の描き手へ
        val co = coarse
        if (co != null) {
            toCoarse(vis, cVis, useMax = false)
            toCoarse(peak, cPeak, useMax = true)
            val cr = if (raw != null) cRaw.also { toCoarse(raw, it, useMax = false) } else null
            co.oneMeter = oneMeter
            co.draw(c, r, style, cVis, cPeak, scale, segUnit, dtSec, cr)
            animating = co.animating
            return
        }
        // 区画いっぱいに、バー・線・メーターだけを描く（表示窓は描かない）。
        // 音が無い（再生していない）ときは、バー・線は何も描かない（v2.9。メーターは針を左に置いて描く）
        val meter = style == SpectrumStyle.VISUAL_METER || style == SpectrumStyle.VISUAL_METER_DARK
        if (!meter && silent(vis, peak)) return
        when (style) {
            SpectrumStyle.VISUAL_BARS -> drawBars(c, r, vis, peak, mirror = false)
            SpectrumStyle.VISUAL_MIRROR -> drawBars(c, r, vis, peak, mirror = true)
            SpectrumStyle.VISUAL_LINE -> drawLine(c, r, vis, peak)
            SpectrumStyle.VISUAL_DOTS -> drawDots(c, r, vis, peak, scale)
            SpectrumStyle.VISUAL_METER -> drawMeters(c, r, raw ?: vis, scale, blue = false, dtSec = dtSec)
            else -> drawMeters(c, r, raw ?: vis, scale, blue = true, dtSec = dtSec)
        }
    }

    /** 帯の高さもピークも、ほぼ 0（音が出ていない） */
    private fun silent(vis: FloatArray, peak: FloatArray): Boolean {
        for (i in 0 until n) if (vis[i] > 0.004f || peak[i] > 0.004f) return false
        return true
    }

    // ================================================================== 段の模様（LED）

    /**
     * 段の模様: 幅 1 画素・高さ = 全段分の画像に、段ごとの色（下の濃い色 → 上の明るい色）と段の隙間（透明）を描く。
     * 画面の画素と 1 対 1 で重なるよう、描く倍率に合わせて作る（区画・色が変わったときだけ）。
     */
    private class SegCache(n: Int) {
        val shaders = arrayOfNulls<BitmapShader>(n)
        val heights = IntArray(n)
        var rows = -1
        var unit = -1f
        var seg = -1f
        var scale = -1f
        var ver = -1
        var uni = false
    }

    private val ledCache = SegCache(n)

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

    private fun SegCache.shader(i: Int): BitmapShader? {
        val idx = if (uniform) 0 else i
        shaders[idx]?.let { return it }
        val cb = colBottom[idx]
        val ct = colTop[idx]
        val hPx = ceil(rows * unit * scale).toInt().coerceIn(1, 4096)
        val px = IntArray(hPx)
        val total = rows * unit
        for (y in 0 until hPx) {
            // 画像の上端からの位置 → 下端（バーの根元）からの位置
            val fromBottom = total - (y + 0.5f) / scale
            val rr = floor(fromBottom / unit).toInt()
            val within = fromBottom - rr * unit
            px[y] = if (rr in 0 until rows && within < seg) {
                mix(cb, ct, if (rows > 1) rr.toFloat() / (rows - 1) else 0f)
            } else 0
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

    /**
     * LED（標準）。ブロックの大きさと並びはどの画面でもスマホの縦画面と同じ（v2.12）:
     * 段の間隔 = segUnit（点灯は 72%）、列の間隔 = 段の間隔 ÷ [LED_ROW_PER_COL]（列の隙間はその 22%）。
     * 横に広い区画では、ブロックを大きくせず本数を増やす（24〜[LED_MAX_COLS] 本。帯の数（48）と本数が違うときは、
     * 帯の間をなめらかにつなぐ／2 本をまとめる）。
     * v2.11 はバーの幅から段の高さを決めていたので、横画面ではブロックが縦画面の 2 倍近い大きさになっていた
     */
    private fun drawLed(c: Canvas, r: RectF, vis: FloatArray, peak: FloatArray, scale: Float, segUnit: Float) {
        val x0 = r.left
        val y1 = r.bottom
        val unit = segUnit
        val segH = unit * 0.72f
        val rows = ((r.height() + unit - segH) / unit).toInt()
        if (rows <= 0) return
        val colPitch = unit / LED_ROW_PER_COL
        val m = (r.width() / colPitch).toInt().coerceIn(LED_MIN_COLS, LED_MAX_COLS)
        val gap = max(1.5f, r.width() / m * 0.22f)
        val barW = (r.width() - gap * (m - 1)) / m
        ledCache.check(rows, unit, segH, scale)
        var placed: BitmapShader? = null
        for (j in 0 until m) {
            // 帯の位置（本数が帯の数と同じなら j そのもの。多いときは帯の間をつなぐ）
            val u = if (m == n) j.toFloat() else ((j + 0.5f) * n / m - 0.5f).coerceIn(0f, (n - 1).toFloat())
            val i0 = u.toInt().coerceIn(0, n - 1)
            val i1 = min(i0 + 1, n - 1)
            val f = u - i0
            val v = vis[i0] + (vis[i1] - vis[i0]) * f
            val pk = peak[i0] + (peak[i1] - peak[i0]) * f
            val ci = if (uniform) 0 else (u + 0.5f).toInt().coerceIn(0, n - 1)
            val sh = ledCache.shader(ci)
            if (sh != null && sh !== placed) {
                // 模様の 1 画素 = 画面の 1 画素、模様の下端 = バーの根元
                matrix.setScale(1f / scale, 1f / scale)
                matrix.postTranslate(0f, y1 - ledCache.heights[if (uniform) 0 else ci] / scale)
                sh.setLocalMatrix(matrix)
                placed = sh
            }
            val bx = x0 + j * (barW + gap)
            val lit = (v * rows).toInt()       // 切り捨て: 届いた段だけ点灯
            if (lit > 0) {
                if (sh != null) {
                    ledPaint.shader = sh
                    c.drawRect(bx, y1 - lit * unit, bx + barW, y1, ledPaint)
                } else {
                    for (rr in 0 until lit) {
                        plain.color = mix(colBottom[ci], colTop[ci], rr.toFloat() / max(1, rows - 1))
                        val bb = y1 - rr * unit
                        c.drawRect(bx, bb - segH, bx + barW, bb, plain)
                    }
                }
            }
            if (pk > 0.04f) {
                val row = min(max((pk * rows).toInt(), lit), rows - 1)
                val bb = y1 - row * unit
                plain.color = peakColor
                c.drawRect(bx, bb - segH * 0.6f, bx + barW, bb, plain)
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
        capPaint.color = peakColor
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
                val hh = vis[i] * (r.height() / 2f - 1.5f)
                if (hh < 0.4f) {
                    if (peak[i] > 0.03f) drawMirrorPeak(c, bx, barW, cy, r, peak[i])
                    continue
                }
                // にじみ
                barPaint.alpha = 60
                tmp.set(bx - 0.8f, cy - hh - 0.8f, bx + barW + 0.8f, cy + hh + 0.8f)
                c.drawRoundRect(tmp, rad + 0.8f, rad + 0.8f, barPaint)
                barPaint.alpha = 255
                tmp.set(bx, cy - hh, bx + barW, cy + hh)
                c.drawRoundRect(tmp, rad, rad, barPaint)
                if (peak[i] > 0.03f) drawMirrorPeak(c, bx, barW, cy, r, peak[i])
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

    private fun drawMirrorPeak(c: Canvas, bx: Float, barW: Float, cy: Float, r: RectF, pk: Float) {
        val ph = pk * (r.height() / 2f - 1.5f) + 1f
        val capH = max(1.2f, barW * 0.22f)
        tmp.set(bx, cy - ph - capH, bx + barW, cy - ph)
        c.drawRoundRect(tmp, capH / 2f, capH / 2f, capPaint)
        tmp.set(bx, cy + ph, bx + barW, cy + ph + capH)
        c.drawRoundRect(tmp, capH / 2f, capH / 2f, capPaint)
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
        // ピークの線（バーの色を白に寄せた色）
        for (i in 0 until n) py[i] = r.bottom - peak[i] * h - 1.2f
        buildSmooth(peakCurve, r.left, r.right, false, r.bottom)
        stroke.color = peakColor
        stroke.alpha = 150
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
            fill.color = peakColor
            c.drawCircle(cx, cyy, rad * 0.92f, fill)
        }
    }

    // ================================================================== アナログメーター（v2.14 で見た目を作り直し）

    private class Needle {
        var pos = 0f
        var vel = 0f
        var hold = 0f
        /** 入力の最近の平均（dB）と、ばらつき（dB の 2 乗）。NaN = 無音のあと（次の音を基準にする） */
        var mean = Float.NaN
        var varDb = 4f
        /** 音が鳴り始めてからの秒数（鳴り始めは平均を早く合わせる） */
        var age = 0f
    }

    /**
     * メーターの色（v2.14）。照明の色（[SpectrumStyle.meterLight]）と文字盤（明るい・暗い）から決める。
     * 目盛り板を作るときと、針・ランプを描くときに使う
     */
    private class MeterLook {
        var dark = false
        var faceCenter = 0
        var faceMid = 0
        var faceEdge = 0
        var ink = 0
        var red = 0
        var needle = 0
        var needleUnder = 0
        var lamp = 0
    }

    private val needles = arrayOf(Needle(), Needle())
    private val faceRect = arrayOf(RectF(), RectF())
    private val look = MeterLook()
    private var faceBmp: Bitmap? = null
    private var fkW = -1
    private var fkH = -1
    private var fkDark = false
    private var fkCount = -1
    private var fkVer = -1
    private var fkLight = -1
    private var fkPeak = false
    private var fkSingle = false
    private var glassShader: LinearGradient? = null
    private var gkTop = Float.NaN
    private var gkH = Float.NaN
    private val bridgePath = Path()
    private val bridgeEdge = Path()
    private var bridgeShader: LinearGradient? = null
    private var bkW = Float.NaN
    private var bkH = Float.NaN
    private var bkDark = false
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** VU メーターの目盛り（dB）と位置（+3 を 1 とした電圧比） */
    private val vuMarks = floatArrayOf(-20f, -10f, -7f, -5f, -3f, -2f, -1f, 0f, 1f, 2f, 3f)
    private fun vuPos(db: Float): Float = 10f.pow((db - 3f) / 20f)

    /** 針の振れ（-46°〜+46°、少しだけ振り切れる。v2.14 で ±44° → ±46°：目盛りの数字が詰まらないように） */
    private fun needleAngle(pos: Float): Float = -46f + 92f * pos.coerceIn(-0.02f, 1.06f)

    private var meterCount = 1
    private var meterW = 0f
    private var meterH = 0f

    /** 見本の小さな絵で、メーターを 1 つにする（車の画面の一覧の絵。v2.14） */
    var oneMeter = false

    /**
     * メーターの並びと大きさを決める（区画の中、左右・上下の中央。横:縦 = 約 1.72:1）。
     * 2 つ（左・上 = 低音、右・下 = 高音）のときは、横に並べるか縦に積むかを、メーターが大きくなる方に（v2.9）。
     * 設定で 1 つ（全体を大きく）にもできる（v2.14）
     */
    private fun meterLayout(r: RectF) {
        val w = r.width()
        val h = r.height()
        if (SpectrumStyle.meterSingle || oneMeter) {
            val mh = min(h, w / 1.72f)
            val mw = mh * 1.72f
            val x = r.left + (w - mw) / 2f
            val y = r.top + (h - mh) / 2f
            faceRect[0].set(x, y, x + mw, y + mh)
            meterCount = 1
            meterW = mw
            meterH = mh
            return
        }
        val gapX = w * 0.035f
        val gapY = h * 0.05f
        val side = min(h, (w - gapX) / 2f / 1.72f)
        val stack = min((h - gapY) / 2f, w / 1.72f)
        val stacked = stack > side
        val mh = if (stacked) stack else side
        val mw = mh * 1.72f
        val totalW = if (stacked) mw else mw * 2 + gapX
        val totalH = if (stacked) mh * 2 + gapY else mh
        val left = r.left + (w - totalW) / 2f
        val topY = r.top + (h - totalH) / 2f
        for (k in 0 until 2) {
            val x = if (stacked) left else left + k * (mw + gapX)
            val y = if (stacked) topY + k * (mh + gapY) else topY
            faceRect[k].set(x, y, x + mw, y + mh)
        }
        meterCount = 2
        meterW = mw
        meterH = mh
    }

    /** 照明の色と文字盤から、メーターの色を決める */
    private fun updateLook(dark: Boolean) {
        val lamp = when (SpectrumStyle.meterLight) {
            SpectrumStyle.LIGHT_WARM -> LAMP_WARM
            SpectrumStyle.LIGHT_WHITE -> LAMP_WHITE
            SpectrumStyle.LIGHT_BLUE -> LAMP_BLUE
            SpectrumStyle.LIGHT_GREEN -> LAMP_GREEN
            SpectrumStyle.LIGHT_THEME -> colTop[n / 2]
            else -> if (dark) LAMP_BLUE else LAMP_WARM
        }
        val l = look
        l.dark = dark
        l.lamp = lamp
        val hsv = FloatArray(3)
        Color.colorToHSV(lamp, hsv)
        if (dark) {
            // 暗い文字盤: 黒い板の下の方がうっすら照明の色に光り、目盛り・針が光る
            l.faceCenter = mix(Color.BLACK, lamp, 0.24f)
            l.faceMid = mix(Color.BLACK, lamp, 0.08f)
            l.faceEdge = Color.rgb(4, 5, 7)
            l.ink = mix(lamp, Color.WHITE, 0.5f)
            l.red = Color.rgb(255, 100, 76)
            l.needle = mix(Color.WHITE, lamp, 0.12f)
            l.needleUnder = withAlpha(lamp, 75)
        } else {
            // 明るい文字盤: 紙の文字盤を裏から照らす（中ほどが明るく、縁は暗く）。目盛りと針は照明の色の暗い色
            val paper = Color.rgb(255, 252, 240)
            l.faceCenter = mix(paper, lamp, 0.26f)
            l.faceMid = mix(paper, lamp, 0.66f)
            l.faceEdge = mix(lamp, Color.BLACK, 0.55f)
            l.ink = Color.HSVToColor(floatArrayOf(hsv[0], (hsv[1] * 1.1f).coerceAtMost(1f), 0.2f))
            l.red = Color.rgb(198, 30, 22)
            l.needle = mix(l.ink, Color.BLACK, 0.4f)
            l.needleUnder = Color.argb(44, 0, 0, 0)
        }
    }

    private fun drawMeters(c: Canvas, r: RectF, vis: FloatArray, scale: Float, blue: Boolean, dtSec: Float) {
        meterLayout(r)
        val count = meterCount
        val two = count == 2
        val mw = meterW
        val mh = meterH
        if (mw <= 1f || mh <= 1f) return
        val dark = blue

        // 入力: 低音（左）・高音（右）、1 つのときは全体（v の値は 1 で切る前の強さ。切ると強弱が消えるため）
        for (k in 0 until count) {
            val s = if (!two) meterInput(vis, 0, n) else if (k == 0) meterInput(vis, 0, 7) else meterInput(vis, n / 2, n)
            val nd = needles[k]
            val tgt = needleTarget(nd, s, dtSec)
            stepNeedle(nd, tgt, dtSec)
            if (kotlin.math.abs(nd.vel) > 0.01f || kotlin.math.abs(nd.pos - tgt) > 0.01f || s > 0.004f) animating = true
        }

        // 目盛り板（画像として 1 回だけ描く。区画・色・設定が変わったときだけ作り直す）
        val fw = ceil(mw * scale).toInt().coerceIn(8, 2048)
        val fh = ceil(mh * scale).toInt().coerceIn(8, 2048)
        val light = SpectrumStyle.meterLight
        val peakOn = SpectrumStyle.meterPeak
        val single = SpectrumStyle.meterSingle || oneMeter
        if (fw != fkW || fh != fkH || dark != fkDark || count != fkCount || colorsVersion != fkVer || light != fkLight ||
            peakOn != fkPeak || single != fkSingle || faceBmp == null
        ) {
            updateLook(dark)
            // 前の目盛り板は捨てるだけ（描いたばかりの画面がまだ使っていることがあるので recycle しない）
            faceBmp = try {
                Bitmap.createBitmap(fw, fh * count, Bitmap.Config.ARGB_8888).also { b ->
                    val bc = Canvas(b)
                    for (k in 0 until count) {
                        bc.save()
                        bc.translate(0f, (fh * k).toFloat())
                        bc.scale(fw / mw, fh / mh)
                        drawFace(bc, mw, mh, fh / mh, if (count == 2) (if (k == 0) "BASS" else "TREBLE") else null, peakOn)
                        bc.restore()
                    }
                }
            } catch (_: Throwable) {
                null
            }
            fkW = fw
            fkH = fh
            fkDark = dark
            fkCount = count
            fkVer = colorsVersion
            fkLight = light
            fkPeak = peakOn
            fkSingle = single
        }
        val face = faceBmp
        for (k in 0 until count) {
            val fr = faceRect[k]
            if (face != null) {
                srcRect.set(0, fh * k, fw, fh * (k + 1))
                c.drawBitmap(face, srcRect, fr, bmpPaint)
            } else {
                fill.shader = null
                fill.color = look.faceMid
                c.drawRoundRect(fr, mh * 0.07f, mh * 0.07f, fill)
            }
            drawNeedle(c, fr, mw, mh, needles[k], peakOn)
        }
    }

    private val srcRect = android.graphics.Rect()

    /** 帯の範囲の強さ（最大値 3 割・実効値 7 割） */
    private fun meterInput(v: FloatArray, from: Int, to: Int): Float {
        var s = 0f
        var mx = 0f
        var cnt = 0
        for (i in from until min(to, n)) {
            s += v[i] * v[i]
            if (v[i] > mx) mx = v[i]
            cnt++
        }
        if (cnt == 0) return 0f
        return 0.3f * mx + 0.7f * sqrt(s / cnt)
    }

    /**
     * 針の目標の位置（v2.8）。バーの高さはいつも上の方にあるので、そのまま針にすると右の端に張り付く（v2.7 まで）。
     * そこで、いまの強さ（dB）が最近 [AVG_TAU] 秒の平均よりどれだけ大きいかを、最近のばらつきで割って VU の目盛りに写す。
     * 平均の音で [CENTER_VU] dB（目盛りの中ほど）、拍で右へ、拍の合間で左へ大きく振れる。曲の音量・設定の高さによらない。
     * 無音では左の端へ戻る
     */
    private fun needleTarget(nd: Needle, s: Float, dt: Float): Float {
        if (s < 0.004f) {
            nd.mean = Float.NaN
            return 0f
        }
        val d = 20f * log10(s)
        if (nd.mean.isNaN()) {
            nd.mean = d
            nd.varDb = 4f
            nd.age = 0f
        }
        if (dt > 0f) {
            // 鳴り始めの 1 秒は平均を早く合わせる（音が立ち上がる間、針が右へ振り切れたままにならないように）
            nd.age += dt
            val a = min(1f, dt / (if (nd.age < 1f) 0.25f else AVG_TAU))
            nd.mean += (d - nd.mean) * a
            val dev = d - nd.mean
            nd.varDb += (dev * dev - nd.varDb) * a
        }
        val sd = max(sqrt(nd.varDb), SD_MIN)
        return vuPos((CENTER_VU + EXPAND * (d - nd.mean) / sd).coerceIn(-26f, 3.4f))
    }

    /** 針の動き（ばね・ダンパー）。約 0.05 秒で目標に届き、少しだけ行き過ぎる（v2.8 で v2.6 より速く） */
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
        nd.hold = if (nd.pos >= vuPos(1f)) 0.3f else max(0f, nd.hold - dt)
    }

    /**
     * 目盛り板（大きさ w×h の座標で描く。v2.14 で作り直し）。
     * 金属の枠の中に、照明で光る文字盤（明るい・暗い）、VU の目盛り（-20〜+3。+1〜+3 は赤）、内側にパーセントの目盛り、
     * 「VU」、低音・高音の名前、右下にピークのランプ。針の根元を隠す台は、針の上に毎回描く（[drawNeedle]）。
     * @param pxPerUnit 1 単位あたりの画素（小さいときは細かい文字を省く）
     */
    private fun drawFace(c: Canvas, w: Float, h: Float, pxPerUnit: Float, label: String?, peakOn: Boolean) {
        val l = look
        val dark = l.dark
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val b = h * M_BEZEL
        val px = h * pxPerUnit            // 文字盤の高さ（画素）
        // 枠（上が明るく下が暗い金属）
        p.shader = LinearGradient(
            0f, 0f, 0f, h,
            if (dark) Color.rgb(54, 56, 62) else Color.rgb(64, 60, 56), if (dark) Color.rgb(10, 10, 12) else Color.rgb(16, 14, 12),
            Shader.TileMode.CLAMP
        )
        c.drawRoundRect(RectF(0f, 0f, w, h), h * 0.085f, h * 0.085f, p)
        // 文字盤（下の中央から照らす）
        val face = RectF(b, b, w - b, h - b)
        p.shader = RadialGradient(
            w / 2f, h * 0.98f, w * 0.78f,
            intArrayOf(l.faceCenter, l.faceMid, l.faceEdge), floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawRoundRect(face, h * 0.05f, h * 0.05f, p)
        // 文字盤の上の縁の影（枠の内側に沈んで見えるように）
        p.shader = LinearGradient(0f, b, 0f, b + h * 0.11f, Color.argb(if (dark) 120 else 80, 0, 0, 0), Color.argb(0, 0, 0, 0), Shader.TileMode.CLAMP)
        c.drawRoundRect(face, h * 0.05f, h * 0.05f, p)
        p.shader = null

        val cx = w / 2f
        val cy = h * M_PIVOT_Y
        val arcR = h * M_ARC_R
        val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.BUTT
        }
        fun arcRect(r: Float) = RectF(cx - r, cy - r, cx + r, cy + r)
        val a20 = needleAngle(vuPos(-20f))
        val a0 = needleAngle(vuPos(0f))
        val a3 = needleAngle(vuPos(3f))

        // パーセントの目盛り（内側。0〜100% = 0 VU）。小さいメーターでは省く
        if (px >= 100f) {
            val pr = arcR - h * 0.085f
            sp.color = withAlpha(l.ink, 150)
            sp.strokeWidth = h * 0.007f
            c.drawArc(arcRect(pr), -90f + needleAngle(0f), a0 - needleAngle(0f), false, sp)
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = Paint.Align.CENTER
                textSize = h * 0.048f
                color = withAlpha(l.ink, 170)
            }
            for (k in 0..5) {
                val a = Math.toRadians(needleAngle(vuPos(0f) * k / 5f).toDouble())
                val s0 = pr
                val s1 = pr - h * 0.032f
                c.drawLine(
                    cx + (sin(a) * s0).toFloat(), cy - (cos(a) * s0).toFloat(),
                    cx + (sin(a) * s1).toFloat(), cy - (cos(a) * s1).toFloat(), sp
                )
                if (px >= 150f && (k == 0 || k == 3 || k == 5)) {
                    // 0・60・100（%）
                    val tr = pr - h * 0.075f
                    val text = when (k) { 0 -> "0"; 3 -> "60"; else -> "100%" }
                    c.drawText(text, cx + (sin(a) * tr).toFloat(), cy - (cos(a) * tr).toFloat() + tp.textSize * 0.36f, tp)
                }
            }
        }

        // 目盛りの弧: -20〜0 は細い線、0〜+3 は太い赤（暗い文字盤は下に淡い光）
        if (dark) {
            sp.color = withAlpha(l.lamp, 60)
            sp.strokeWidth = h * 0.04f
            c.drawArc(arcRect(arcR), -90f + a20, a0 - a20, false, sp)
            sp.color = withAlpha(l.red, 70)
            sp.strokeWidth = h * 0.07f
            c.drawArc(arcRect(arcR + h * 0.012f), -90f + a0, a3 - a0, false, sp)
        }
        sp.color = l.ink
        sp.strokeWidth = h * 0.012f
        c.drawArc(arcRect(arcR), -90f + a20, a0 - a20, false, sp)
        sp.color = l.red
        sp.strokeWidth = h * 0.034f
        c.drawArc(arcRect(arcR + h * 0.011f), -90f + a0, a3 - a0, false, sp)

        // 目盛りの線と数字（数字は細めの字で、詰まらないように）
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = h * 0.074f
        }
        val numR = arcR + h * 0.135f
        for (db in vuMarks) {
            val a = Math.toRadians(needleAngle(vuPos(db)).toDouble())
            val major = db == -20f || db == -10f || db == -5f || db == 0f || db == 3f
            val len = if (major) h * 0.072f else h * 0.048f
            val r0 = arcR - h * 0.006f
            sp.strokeWidth = h * (if (major) 0.016f else 0.011f)
            sp.color = if (db > 0f) l.red else l.ink
            c.drawLine(
                cx + (sin(a) * r0).toFloat(), cy - (cos(a) * r0).toFloat(),
                cx + (sin(a) * (r0 + len)).toFloat(), cy - (cos(a) * (r0 + len)).toFloat(), sp
            )
            tp.color = if (db > 0f) l.red else l.ink
            tp.typeface = if (db == 0f) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            val text = if (db > 0f) "+${db.toInt()}" else if (db == 0f) "0" else "${db.toInt()}"
            c.drawText(text, cx + (sin(a) * numR).toFloat(), cy - (cos(a) * numR).toFloat() + tp.textSize * 0.36f, tp)
        }
        // -20〜-10 の間の小さな目盛り（-15）
        run {
            val a = Math.toRadians(needleAngle(vuPos(-15f)).toDouble())
            val r0 = arcR - h * 0.006f
            sp.strokeWidth = h * 0.009f
            sp.color = l.ink
            c.drawLine(
                cx + (sin(a) * r0).toFloat(), cy - (cos(a) * r0).toFloat(),
                cx + (sin(a) * (r0 + h * 0.034f)).toFloat(), cy - (cos(a) * (r0 + h * 0.034f)).toFloat(), sp
            )
        }

        // 「VU」
        tp.typeface = Typeface.DEFAULT_BOLD
        tp.textSize = h * 0.16f
        tp.color = l.ink
        c.drawText("VU", cx, h * 0.665f, tp)
        // 低音・高音の名前（左下）
        if (label != null) {
            tp.textAlign = Paint.Align.LEFT
            tp.textSize = h * 0.064f
            tp.color = withAlpha(l.ink, 215)
            c.drawText(label, b + h * 0.07f, h * 0.875f, tp)
        }
        // ピークのランプ（右下。消えているとき）
        if (peakOn) {
            val lx = w - b - h * 0.13f
            val ly = h * 0.79f
            p.color = if (dark) Color.rgb(26, 26, 30) else Color.rgb(40, 34, 28)
            c.drawCircle(lx, ly, h * 0.047f, p)
            p.color = if (dark) Color.rgb(72, 16, 14) else Color.rgb(104, 26, 18)
            c.drawCircle(lx, ly, h * 0.033f, p)
            p.color = Color.argb(70, 255, 255, 255)
            c.drawCircle(lx - h * 0.01f, ly - h * 0.011f, h * 0.009f, p)
            if (px >= 110f) {
                tp.textAlign = Paint.Align.CENTER
                tp.textSize = h * 0.046f
                tp.color = withAlpha(l.ink, 200)
                c.drawText("PEAK", lx, ly + h * 0.105f, tp)
            }
        }
    }

    private fun drawNeedle(c: Canvas, fr: RectF, w: Float, h: Float, nd: Needle, peakOn: Boolean) {
        val l = look
        val b = h * M_BEZEL
        val cx = fr.left + w / 2f
        val cy = fr.top + h * M_PIVOT_Y
        val a = Math.toRadians(needleAngle(nd.pos).toDouble())
        val len = h * (M_ARC_R + 0.1f)
        val tx = cx + (sin(a) * len).toFloat()
        val ty = cy - (cos(a) * len).toFloat()
        c.save()
        c.clipRect(fr.left + b, fr.top + b, fr.right - b, fr.bottom - b)
        stroke.shader = null
        stroke.strokeCap = Paint.Cap.ROUND
        if (l.dark) {
            // 光る針（下に照明の色の光）
            stroke.color = l.needleUnder
            stroke.strokeWidth = h * 0.03f
            c.drawLine(cx, cy, tx, ty, stroke)
            stroke.color = l.needle
            stroke.strokeWidth = h * 0.014f
            c.drawLine(cx, cy, tx, ty, stroke)
        } else {
            // 文字盤に落ちる薄い影と、細い針
            val o = h * 0.01f
            stroke.color = l.needleUnder
            stroke.strokeWidth = h * 0.014f
            c.drawLine(cx + o, cy + o * 1.3f, tx + o, ty + o * 1.3f, stroke)
            stroke.color = l.needle
            stroke.strokeWidth = h * 0.012f
            c.drawLine(cx, cy, tx, ty, stroke)
        }
        // 針の根元を隠す台（下の中央の、なだらかな丸い山）
        val fw = w
        if (fw != bkW || h != bkH || l.dark != bkDark || bridgeShader == null) {
            val yt = h * BRIDGE_TOP
            val yb = h - b
            val bw = h * 0.36f
            val x0 = fw / 2f
            bridgePath.reset()
            bridgePath.moveTo(x0 - bw, yb)
            bridgePath.cubicTo(x0 - bw * 0.62f, yt, x0 - bw * 0.3f, yt, x0, yt)
            bridgePath.cubicTo(x0 + bw * 0.3f, yt, x0 + bw * 0.62f, yt, x0 + bw, yb)
            bridgePath.close()
            bridgeEdge.reset()
            bridgeEdge.moveTo(x0 - bw, yb)
            bridgeEdge.cubicTo(x0 - bw * 0.62f, yt, x0 - bw * 0.3f, yt, x0, yt)
            bridgeEdge.cubicTo(x0 + bw * 0.3f, yt, x0 + bw * 0.62f, yt, x0 + bw, yb)
            bridgeShader = LinearGradient(
                0f, yt, 0f, yb,
                if (l.dark) Color.rgb(46, 48, 54) else Color.rgb(74, 68, 62), if (l.dark) Color.rgb(10, 10, 12) else Color.rgb(20, 18, 16),
                Shader.TileMode.CLAMP
            )
            bkW = fw
            bkH = h
            bkDark = l.dark
        }
        c.translate(fr.left, fr.top)
        fill.shader = bridgeShader
        c.drawPath(bridgePath, fill)
        fill.shader = null
        // 台の上の縁の光
        stroke.style = Paint.Style.STROKE
        stroke.color = Color.argb(if (l.dark) 60 else 80, 255, 255, 255)
        stroke.strokeWidth = h * 0.007f
        c.drawPath(bridgeEdge, stroke)
        c.translate(-fr.left, -fr.top)
        c.restore()
        // ピークのランプ（+1 VU を超えたら 0.3 秒光る）
        if (peakOn && nd.hold > 0f) {
            val lx = fr.right - b - h * 0.13f
            val ly = fr.top + h * 0.79f
            fill.shader = null
            fill.color = Color.argb(90, 255, 60, 40)
            c.drawCircle(lx, ly, h * 0.085f, fill)
            fill.color = Color.rgb(255, 74, 52)
            c.drawCircle(lx, ly, h * 0.033f, fill)
            fill.color = Color.argb(200, 255, 220, 200)
            c.drawCircle(lx - h * 0.01f, ly - h * 0.011f, h * 0.01f, fill)
        }
        // ガラスの映り込み（上の方がうっすら明るい）
        if (fr.top != gkTop || h != gkH || glassShader == null) {
            glassShader = LinearGradient(0f, fr.top + b, 0f, fr.top + h * 0.48f, Color.argb(46, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
            gkTop = fr.top
            gkH = h
        }
        fill.shader = glassShader
        tmp.set(fr.left + b, fr.top + b, fr.right - b, fr.top + h * 0.48f)
        c.drawRoundRect(tmp, h * 0.05f, h * 0.05f, fill)
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
        // 24 本で描く表示は、24 本用の描き手の見本（これまでと同じ動き）
        val co = coarse
        if (co != null && style != SpectrumStyle.VISUAL_LED && !SpectrumStyle.isCarAudio(style)) {
            co.oneMeter = oneMeter
            co.drawDemo(c, r, style, scale, t, dtSec, still)
            animating = co.animating
            return
        }
        for (i in 0 until n) {
            val x = i.toFloat() / (n - 1)
            // 帯の位置を 24 本のときの番号に直す（帯が 48 でも見本の動きは同じ）
            val k = x * (COARSE_BANDS - 1)
            // 低音が強く高音ほど弱い、よくある曲の形に、帯ごとにずれた揺れを足す
            val base = 0.30f + 0.62f * exp(-x * 1.7f) + 0.10f * exp(-((x - 0.55f) * 6f).pow(2))
            val wobble = 0.62f + 0.38f * sin(t * 2.3f + k * 0.61f) * cos(t * 1.1f - k * 0.27f)
            val v = (base * wobble).coerceIn(0f, 1f)
            demoVis[i] = v
            demoPeak[i] = if (still) (v + 0.12f).coerceAtMost(1f) else max(v, demoPeak[i] - dtSec * 0.6f)
        }
        if (still) {
            // 止めた見本では、メーターの針を目盛りの中ほど（低音は 0、高音は -4）に置く
            needles[0].pos = vuPos(-0.5f)
            needles[1].pos = vuPos(-4f)
            needles[0].vel = 0f
            needles[1].vel = 0f
        }
        draw(c, r, style, demoVis, demoPeak, scale, max(3.5f, r.height() / 22f), if (still) 0f else dtSec)
    }

    companion object {
        /** LED のブロック: 段の間隔 ÷ 列の間隔（スマホの縦画面: 5.5 ÷ 9.2）と、本数の下限・上限 */
        private const val LED_ROW_PER_COL = 0.6f
        private const val LED_MIN_COLS = 24
        private const val LED_MAX_COLS = 64
        /** なめらかなバー・ミラー・ライン・ドット・メーターの本数（帯が多いときはまとめる） */
        private const val COARSE_BANDS = 24

        /** メーターの形（文字盤の高さに対する割合）: 枠の太さ・針の回る中心（文字盤の下の外側）・目盛りの弧の半径 */
        private const val M_BEZEL = 0.05f
        private const val M_PIVOT_Y = 1.1f
        private const val M_ARC_R = 0.83f
        /** 針の根元を隠す台の上端（文字盤の高さに対する割合） */
        private const val BRIDGE_TOP = 0.79f
        /** メーターの照明の色（v2.14）: 電球色・白・青・緑 */
        private val LAMP_WARM = Color.rgb(255, 172, 64)
        private val LAMP_WHITE = Color.rgb(236, 238, 232)
        private val LAMP_BLUE = Color.rgb(84, 160, 255)
        private val LAMP_GREEN = Color.rgb(96, 222, 120)

        /** 針のばねの強さと減衰（約 38 rad/s・減衰比 0.6: 約 0.05 秒で振れ、行き過ぎは 9% ほど） */
        private const val NEEDLE_K = 1444f
        private const val NEEDLE_D = 46f

        /** 平均の音での針の位置（VU の dB。+3 が右の端、0 が赤い目盛りの始まり） */
        private const val CENTER_VU = -1.5f
        /** 平均との差（ばらつきで割った値）を VU の dB にする倍率。大きいほど拍に合わせて大きく振れる */
        private const val EXPAND = 3.0f
        /** 平均・ばらつきを取る長さ（時定数・秒） */
        private const val AVG_TAU = 2.0f
        /** ばらつきの下限（dB）。ほとんど変わらない音で、細かい揺れを広げすぎないように */
        private const val SD_MIN = 1.2f

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
         * 表示の種類ごとの、音楽の画面の背景（上・下）。v2.8 から、どの表示も LED（標準）と同じ背景
         * （v2.6〜v2.7 のカーオーディオ系の紫がかった灰色は、見にくいのでやめた）
         */
        @Suppress("UNUSED_PARAMETER")
        fun screenBackground(style: Int, out: IntArray) {
            out[0] = Color.rgb(14, 42, 58)
            out[1] = Color.rgb(4, 12, 20)
        }
    }
}

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
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * スペアナの表示の種類（[SpectrumStyle.VISUAL_LED] など）を描く。音楽の画面（スマホ・車）と、設定画面の見本で共用する。
 *
 *  - LED（標準）… 段に分かれたバー。段の色と隙間を描いた細い画像を模様にして、1 本 1 回で描く
 *  - なめらかなバー・ミラー … 縦のグラデーションの角丸のバー（1 本 1 回）
 *  - ライン … 帯の高さをなめらかな曲線でつなぎ、下を淡く塗る
 *  - ドット … 丸い LED の列を模様にして 1 列 1 回で描く（消えている LED もうっすら）
 *  - レトロ（蛍光表示管）… 消えている段もうっすら見える LED、にじみ、下に周波数の目盛り
 *  - アナログメーター … 目盛り板（画像として 1 回だけ描いておく）と、ばねのように動く針（VU メーターの動き）
 *
 * 座標は呼び出し側の座標（PlayerRenderer の仮想座標）。scale は 1 単位あたりの画素数で、模様の画像を画面の画素に合わせるのに使う。
 * 毎フレームの割り当てはしない（模様・グラデーション・目盛り板は、区画・色・種類が変わったときだけ作り直す）。
 */
class SpectrumPainter(private val n: Int) {

    // ================================================================== 色

    private val colBottom = IntArray(n) { Color.rgb(0, 90, 200) }
    private val colTop = IntArray(n) { Color.rgb(40, 190, 245) }
    private var uniform = true
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
     * @param panel 背景の板（ガラス・目盛り板）を描いてよい（ジャケットに重ねていない）
     * @param dtSec 前のフレームからの経過（メーターの針の動き）
     */
    fun draw(
        c: Canvas, r: RectF, style: Int, vis: FloatArray, peak: FloatArray,
        scale: Float, segUnit: Float, panel: Boolean, dtSec: Float
    ) {
        if (r.width() <= 2f || r.height() <= 2f || scale <= 0f) return
        animating = false
        when (style) {
            SpectrumStyle.VISUAL_BARS -> drawBars(c, r, vis, peak, mirror = false)
            SpectrumStyle.VISUAL_MIRROR -> drawBars(c, r, vis, peak, mirror = true)
            SpectrumStyle.VISUAL_LINE -> drawLine(c, r, vis, peak)
            SpectrumStyle.VISUAL_DOTS -> drawDots(c, r, vis, peak, scale)
            SpectrumStyle.VISUAL_RETRO -> drawLed(c, r, vis, peak, scale, segUnit, retro = true, panel = panel)
            SpectrumStyle.VISUAL_METER -> drawMeters(c, r, vis, scale, dark = false, dtSec = dtSec)
            SpectrumStyle.VISUAL_METER_DARK -> drawMeters(c, r, vis, scale, dark = true, dtSec = dtSec)
            else -> drawLed(c, r, vis, peak, scale, segUnit, retro = false, panel = panel)
        }
    }

    // ================================================================== LED・レトロ

    private val ledShaders = arrayOfNulls<BitmapShader>(n)
    private val ledHeights = IntArray(n)
    private var lkRows = -1
    private var lkUnit = -1f
    private var lkSeg = -1f
    private var lkScale = -1f
    private var lkVer = -1
    private var lkUni = false
    private val ledPaint = Paint()
    private val ghostPaint = Paint().apply { alpha = 34 }
    private val glowPaint = Paint().apply { alpha = 64 }

    /**
     * 段の模様: 幅 1 画素・高さ = 全段分の画像に、段ごとの色（下の濃い色 → 上の明るい色）と段の隙間（透明）を描く。
     * 画面の画素と 1 対 1 で重なるよう、描く倍率に合わせて作る（区画・色が変わったときだけ）。
     */
    /** 区画・色が変わっていたら、段の模様を捨てる（1 フレームに 1 回） */
    private fun checkLedCache(rows: Int, unit: Float, segH: Float, scale: Float) {
        if (rows != lkRows || unit != lkUnit || segH != lkSeg || scale != lkScale || colorsVersion != lkVer || uniform != lkUni) {
            for (k in 0 until n) ledShaders[k] = null
            lkRows = rows
            lkUnit = unit
            lkSeg = segH
            lkScale = scale
            lkVer = colorsVersion
            lkUni = uniform
        }
    }

    private fun ledShader(i: Int, rows: Int, unit: Float, segH: Float, scale: Float): BitmapShader? {
        val idx = if (uniform) 0 else i
        ledShaders[idx]?.let { return it }
        val hPx = ceil(rows * unit * scale).toInt().coerceIn(1, 4096)
        val px = IntArray(hPx)
        val total = rows * unit
        for (y in 0 until hPx) {
            // 画像の上端からの位置 → 下端（バーの根元）からの位置
            val fromBottom = total - (y + 0.5f) / scale
            val rr = floor(fromBottom / unit).toInt()
            val within = fromBottom - rr * unit
            px[y] = if (rr in 0 until rows && within < segH) {
                mix(colBottom[idx], colTop[idx], if (rows > 1) rr.toFloat() / (rows - 1) else 0f)
            } else 0
        }
        val bmp = try {
            Bitmap.createBitmap(1, hPx, Bitmap.Config.ARGB_8888)
        } catch (_: Throwable) {
            return null
        }
        bmp.setPixels(px, 0, 1, 0, 0, 1, hPx)
        val s = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.CLAMP)
        ledShaders[idx] = s
        ledHeights[idx] = hPx
        return s
    }

    /** レトロの周波数の目盛り（帯の位置） */
    private val retroFreqs = floatArrayOf(63f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    private val retroLabels = arrayOf("63", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
    private var panelShader: LinearGradient? = null
    private var pkTop = Float.NaN
    private var pkBottom = Float.NaN

    private fun drawLed(c: Canvas, r0: RectF, vis: FloatArray, peak: FloatArray, scale: Float, segUnit: Float, retro: Boolean, panel: Boolean) {
        var r = r0
        if (retro && panel) {
            // ガラス板（暗い板と細い枠）
            tmp.set(r0.left - 3f, r0.top - 3f, r0.right + 3f, r0.bottom + 2f)
            if (tmp.top != pkTop || tmp.bottom != pkBottom || panelShader == null) {
                panelShader = LinearGradient(0f, tmp.top, 0f, tmp.bottom, Color.rgb(12, 22, 26), Color.rgb(4, 8, 10), Shader.TileMode.CLAMP)
                pkTop = tmp.top
                pkBottom = tmp.bottom
            }
            fill.shader = panelShader
            c.drawRoundRect(tmp, 4f, 4f, fill)
            fill.shader = null
            stroke.shader = null
            stroke.color = Color.rgb(30, 46, 52)
            stroke.strokeWidth = 0.8f
            c.drawRoundRect(tmp, 4f, 4f, stroke)
        }
        // レトロ: 下に周波数の目盛りを入れる
        val labelH = if (retro && panel && r0.height() >= 56f) 8.5f else 0f
        if (labelH > 0f) {
            tmp.set(r0.left, r0.top, r0.right, r0.bottom - labelH)
            r = tmp
        }
        val x0 = r.left
        val y1 = r.bottom
        val gap = if (retro) max(2f, r.width() / n * 0.24f) else 2f
        val barW = (r.width() - gap * (n - 1)) / n
        val unit = if (retro) segUnit * 1.12f else segUnit
        val segH = unit * (if (retro) 0.6f else 0.72f)
        val rows = ((r.height() + unit - segH) / unit).toInt()
        if (rows <= 0) return
        val top = y1 - rows * unit
        checkLedCache(rows, unit, segH, scale)
        for (i in 0 until n) {
            val sh = ledShader(i, rows, unit, segH, scale)
            if (sh != null && (i == 0 || !uniform)) {
                // 模様の 1 画素 = 画面の 1 画素、模様の下端 = バーの根元
                matrix.setScale(1f / scale, 1f / scale)
                matrix.postTranslate(0f, y1 - ledHeights[if (uniform) 0 else i] / scale)
                sh.setLocalMatrix(matrix)
            }
            val bx = x0 + i * (barW + gap)
            val lit = (vis[i] * rows).toInt()       // 切り捨て: 届いた段だけ点灯
            if (sh != null) {
                if (retro) {
                    // 消えている段もうっすら見せる
                    ghostPaint.shader = sh
                    c.drawRect(bx, top, bx + barW, y1, ghostPaint)
                }
                if (lit > 0) {
                    if (retro) {
                        // 蛍光表示管のにじみ（少し広く淡く重ねる）
                        glowPaint.shader = sh
                        c.drawRect(bx - 0.7f, y1 - lit * unit - 0.5f, bx + barW + 0.7f, y1, glowPaint)
                    }
                    ledPaint.shader = sh
                    c.drawRect(bx, y1 - lit * unit, bx + barW, y1, ledPaint)
                }
            } else if (lit > 0) {
                for (rr in 0 until lit) {
                    plain.color = mix(colBottom[i], colTop[i], rr.toFloat() / max(1, rows - 1))
                    val b = y1 - rr * unit
                    c.drawRect(bx, b - segH, bx + barW, b, plain)
                }
            }
            if (peak[i] > 0.04f) {
                val row = min(max((peak[i] * rows).toInt(), lit), rows - 1)
                val b = y1 - row * unit
                plain.color = peakColor
                c.drawRect(bx, b - segH * (if (retro) 0.9f else 0.6f), bx + barW, b, plain)
            }
        }
        if (labelH > 0f) {
            text.textSize = 6.4f
            text.typeface = Typeface.DEFAULT
            text.color = mix(colTop[n / 2], Color.BLACK, 0.35f)
            text.alpha = 200
            val fMin = 40f
            val ratio = 400f   // 40Hz〜16kHz（解析の帯の範囲）
            for (k in retroFreqs.indices) {
                val b = (ln(retroFreqs[k] / fMin) / ln(ratio)) * n - 0.5f
                if (b < -0.5f || b > n - 0.5f) continue
                val x = x0 + b * (barW + gap) + barW / 2f
                c.drawText(retroLabels[k], x, r0.bottom - 1.2f, text)
            }
            text.alpha = 255
        }
    }

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
    private fun checkBarCache(r: RectF, mirror: Boolean) {
        if (r.top != bkTop || r.bottom != bkBottom || mirror != bkMirror || colorsVersion != bkVer || uniform != bkUni) {
            for (k in 0 until n) barShaders[k] = null
            bkTop = r.top
            bkBottom = r.bottom
            bkMirror = mirror
            bkVer = colorsVersion
            bkUni = uniform
        }
    }

    private fun barShader(i: Int, r: RectF, mirror: Boolean): LinearGradient {
        val idx = if (uniform) 0 else i
        barShaders[idx]?.let { return it }
        val g = if (mirror) {
            LinearGradient(0f, r.top, 0f, r.bottom, intArrayOf(colTop[idx], colBottom[idx], colTop[idx]), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        } else {
            LinearGradient(0f, r.bottom, 0f, r.top, colBottom[idx], colTop[idx], Shader.TileMode.CLAMP)
        }
        barShaders[idx] = g
        return g
    }

    private fun drawBars(c: Canvas, r: RectF, vis: FloatArray, peak: FloatArray, mirror: Boolean) {
        val gap = max(1.5f, r.width() / n * 0.2f)
        val barW = (r.width() - gap * (n - 1)) / n
        val rad = min(barW * 0.45f, 3.5f)
        val h = r.height()
        val cy = r.centerY()
        capPaint.color = peakColor
        checkBarCache(r, mirror)
        if (mirror) {
            plain.color = colTop[n / 2]
            plain.alpha = 70
            c.drawRect(r.left, cy - 0.35f, r.right, cy + 0.35f, plain)
            plain.alpha = 255
        }
        for (i in 0 until n) {
            barPaint.shader = barShader(i, r, mirror)
            val bx = r.left + i * (barW + gap)
            if (mirror) {
                val hh = max(0.7f, vis[i] * (h / 2f - 1.5f))
                tmp.set(bx, cy - hh, bx + barW, cy + hh)
                c.drawRoundRect(tmp, rad, rad, barPaint)
                if (peak[i] > 0.03f) {
                    val ph = peak[i] * (h / 2f - 1.5f) + 1f
                    val capH = max(1.2f, barW * 0.22f)
                    tmp.set(bx, cy - ph - capH, bx + barW, cy - ph)
                    c.drawRoundRect(tmp, capH / 2f, capH / 2f, capPaint)
                    tmp.set(bx, cy + ph, bx + barW, cy + ph + capH)
                    c.drawRoundRect(tmp, capH / 2f, capH / 2f, capPaint)
                }
            } else {
                val bh = vis[i] * (h - 3f)
                if (bh > 0.6f) {
                    tmp.set(bx, r.bottom - bh, bx + barW, r.bottom)
                    c.drawRoundRect(tmp, rad, rad, barPaint)
                }
                if (peak[i] > 0.03f) {
                    val capH = max(1.6f, barW * 0.26f)
                    val py = r.bottom - peak[i] * (h - 3f) - 1.4f
                    tmp.set(bx, py - capH, bx + barW, py)
                    c.drawRoundRect(tmp, capH / 2f, capH / 2f, capPaint)
                }
            }
        }
        barPaint.shader = null
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
                lineFill = LinearGradient(0f, r.top, 0f, r.bottom, withAlpha(colTop[0], 190), withAlpha(colBottom[0], 26), Shader.TileMode.CLAMP)
                lineStrokeShader = null
            } else {
                val cols = IntArray(n) { withAlpha(colTop[it], 120) }
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
        // ピークの線
        for (i in 0 until n) py[i] = r.bottom - peak[i] * h - 1.2f
        buildSmooth(peakCurve, r.left, r.right, false, r.bottom)
        stroke.color = peakColor
        stroke.alpha = 120
        stroke.strokeWidth = 0.9f
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

    // ================================================================== ドット

    private val dotShaders = arrayOfNulls<BitmapShader>(n)
    private var dkPitch = -1
    private var dkRows = -1
    private var dkRad = -1f
    private var dkScale = -1f
    private var dkVer = -1
    private var dkUni = false
    private val dotPaint = Paint()
    private val dotGhostPaint = Paint().apply { alpha = 30 }

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
        fill.color = peakColor
        for (i in 0 until n) {
            if (peak[i] <= 0.04f) continue
            val row = min((peak[i] * rows).toInt(), rows - 1)
            c.drawCircle(gx0 + (i + 0.5f) * pitch, yb - (row + 0.5f) * pitch, rad * 0.92f, fill)
        }
    }

    // ================================================================== アナログメーター

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
    private var fkDark = false
    private var fkCount = -1
    private var fkVer = -1
    private var glassShader: LinearGradient? = null
    private var gkTop = Float.NaN
    private var gkBottom = Float.NaN
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** VU メーターの目盛り（dB）と位置（+3 を 1 とした電圧比） */
    private val vuMarks = floatArrayOf(-20f, -10f, -7f, -5f, -3f, -2f, -1f, 0f, 1f, 2f, 3f)
    private fun vuPos(db: Float): Float = 10f.pow((db - 3f) / 20f)

    /** 針の振れ（-48°〜+48°、少しだけ振り切れる） */
    private fun needleAngle(pos: Float): Float = -48f + 96f * pos.coerceIn(-0.02f, 1.06f)

    private fun drawMeters(c: Canvas, r: RectF, vis: FloatArray, scale: Float, dark: Boolean, dtSec: Float) {
        val two = r.width() >= r.height() * 1.45f
        val count = if (two) 2 else 1
        // メーターの大きさ（横:縦 = 約 1.6:1）
        val gap = if (two) r.width() * 0.04f else 0f
        val cellW = (r.width() - gap * (count - 1)) / count
        val mh = min(r.height(), cellW / 1.55f)
        val mw = mh * 1.55f
        val totalW = mw * count + gap * (count - 1)
        val left = r.left + (r.width() - totalW) / 2f
        val topY = r.top + (r.height() - mh) / 2f
        for (k in 0 until count) faceRect[k].set(left + k * (mw + gap), topY, left + k * (mw + gap) + mw, topY + mh)

        // 入力: 低音（左）・高音（右）、1 つのときは全体
        val targets = meterTargets
        if (two) {
            targets[0] = rms(vis, 0, 7)
            targets[1] = rms(vis, n / 2, n)
        } else {
            targets[0] = rms(vis, 0, n)
        }
        for (k in 0 until count) {
            val tgt = (targets[k] * 1.22f).coerceIn(0f, 1.05f)
            stepNeedle(needles[k], tgt, dtSec)
            if (kotlin.math.abs(needles[k].vel) > 0.01f || kotlin.math.abs(needles[k].pos - tgt) > 0.02f) animating = true
        }

        // 目盛り板（画像として 1 回だけ描く）
        val fw = ceil(mw * scale).toInt().coerceIn(8, 2048)
        val fh = ceil(mh * scale).toInt().coerceIn(8, 2048)
        if (fw != fkW || fh != fkH || dark != fkDark || count != fkCount || colorsVersion != fkVer || faceBmp == null) {
            // 前の目盛り板は捨てるだけ（描いたばかりの画面がまだ使っていることがあるので recycle しない）
            faceBmp = try {
                Bitmap.createBitmap(fw, fh * count, Bitmap.Config.ARGB_8888).also { b ->
                    val bc = Canvas(b)
                    for (k in 0 until count) {
                        bc.save()
                        bc.translate(0f, (fh * k).toFloat())
                        bc.scale(fw / mw, fh / mh)
                        drawFace(bc, mw, mh, dark, if (count == 2) (if (k == 0) "BASS" else "TREBLE") else null)
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
        }
        val face = faceBmp
        for (k in 0 until count) {
            val fr = faceRect[k]
            if (face != null) {
                srcRect.set(0, fh * k, fw, fh * (k + 1))
                c.drawBitmap(face, srcRect, fr, bmpPaint)
            } else {
                fill.color = if (dark) Color.rgb(10, 14, 18) else Color.rgb(236, 222, 182)
                c.drawRoundRect(fr, mh * 0.06f, mh * 0.06f, fill)
            }
            drawNeedle(c, fr, mw, mh, needles[k], dark)
        }
    }

    private val meterTargets = FloatArray(2)
    private val srcRect = android.graphics.Rect()

    private fun rms(v: FloatArray, from: Int, to: Int): Float {
        var s = 0f
        var cnt = 0
        for (i in from until min(to, n)) {
            s += v[i] * v[i]
            cnt++
        }
        return if (cnt == 0) 0f else sqrt(s / cnt)
    }

    /** VU メーターの針の動き（ばね・ダンパー。約 0.3 秒で目標に届き、少しだけ行き過ぎる） */
    private fun stepNeedle(nd: Needle, target: Float, dt: Float) {
        var t = dt.coerceIn(0f, 0.1f)
        while (t > 0f) {
            val h = min(t, 0.008f)
            val acc = 110f * (target - nd.pos) - 17f * nd.vel
            nd.vel += acc * h
            nd.pos += nd.vel * h
            t -= h
        }
        nd.pos = nd.pos.coerceIn(-0.02f, 1.08f)
        nd.hold = if (nd.pos >= vuPos(1f)) 0.35f else max(0f, nd.hold - dt)
    }

    /** 目盛り板（大きさ w×h の座標で描く） */
    private fun drawFace(c: Canvas, w: Float, h: Float, dark: Boolean, label: String?) {
        val accent = colTop[n / 2]
        val rr = RectF(0f, 0f, w, h)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        if (dark) {
            p.shader = RadialGradient(w / 2f, h * 0.75f, w * 0.7f, Color.rgb(18, 26, 32), Color.rgb(4, 7, 10), Shader.TileMode.CLAMP)
        } else {
            p.shader = LinearGradient(0f, 0f, 0f, h, Color.rgb(247, 236, 204), Color.rgb(226, 206, 152), Shader.TileMode.CLAMP)
        }
        c.drawRoundRect(rr, h * 0.07f, h * 0.07f, p)
        p.shader = null
        // 下のカバー（針の根元を隠す板）
        val coverTop = h * 0.84f
        p.color = if (dark) Color.rgb(14, 18, 22) else Color.rgb(48, 38, 28)
        c.drawRect(h * 0.05f, coverTop, w - h * 0.05f, h - h * 0.05f, p)
        // 枠
        val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = h * 0.03f
            color = if (dark) Color.rgb(40, 52, 60) else Color.rgb(92, 72, 46)
        }
        c.drawRoundRect(RectF(h * 0.015f, h * 0.015f, w - h * 0.015f, h - h * 0.015f), h * 0.07f, h * 0.07f, sp)

        // 目盛りの弧（中心は板の下の外側）
        val cx = w / 2f
        val cy = h * 1.18f
        val rad = h * 0.95f
        val scaleColor = if (dark) accent else Color.rgb(30, 26, 22)
        val redColor = if (dark) Color.rgb(255, 92, 64) else Color.rgb(196, 30, 26)
        val arc = RectF(cx - rad, cy - rad, cx + rad, cy + rad)
        val a0 = needleAngle(vuPos(-20f))
        val aZero = needleAngle(vuPos(0f))
        val a3 = needleAngle(vuPos(3f))
        sp.strokeCap = Paint.Cap.BUTT
        if (dark) {
            // 光る目盛り（太く淡い線の上に細い線）
            sp.color = withAlpha(accent, 60)
            sp.strokeWidth = h * 0.05f
            c.drawArc(arc, -90f + a0, aZero - a0, false, sp)
        }
        sp.color = scaleColor
        sp.strokeWidth = h * 0.018f
        c.drawArc(arc, -90f + a0, aZero - a0, false, sp)
        sp.color = redColor
        sp.strokeWidth = h * 0.06f
        c.drawArc(RectF(cx - rad - h * 0.03f, cy - rad - h * 0.03f, cx + rad + h * 0.03f, cy + rad + h * 0.03f), -90f + aZero, a3 - aZero, false, sp)
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
            val sx = cx + (sin(a) * rad).toFloat()
            val sy = cy - (cos(a) * rad).toFloat()
            val ex = cx + (sin(a) * (rad + len)).toFloat()
            val ey = cy - (cos(a) * (rad + len)).toFloat()
            sp.strokeWidth = h * (if (major) 0.022f else 0.016f)
            sp.color = if (db > 0f) redColor else scaleColor
            c.drawLine(sx, sy, ex, ey, sp)
            val tx = cx + (sin(a) * (rad + h * 0.155f)).toFloat()
            val ty = cy - (cos(a) * (rad + h * 0.155f)).toFloat() + tp.textSize * 0.35f
            tp.color = if (db > 0f) redColor else scaleColor
            val s = if (db > 0f) "+${db.toInt()}" else if (db == 0f) "0" else "${db.toInt()}"
            c.drawText(s, tx, ty, tp)
        }
        // 「VU」と、低音・高音の名前
        tp.textSize = h * 0.17f
        tp.color = if (dark) accent else Color.rgb(36, 30, 24)
        c.drawText("VU", cx, h * 0.70f, tp)
        if (label != null) {
            tp.textSize = h * 0.075f
            tp.typeface = Typeface.DEFAULT_BOLD
            tp.textAlign = Paint.Align.LEFT
            tp.color = if (dark) withAlpha(accent, 200) else Color.rgb(90, 74, 52)
            c.drawText(label, h * 0.12f, h * 0.78f, tp)
        }
        // ピークのランプの台
        p.color = if (dark) Color.rgb(40, 14, 12) else Color.rgb(96, 28, 22)
        c.drawCircle(w - h * 0.16f, h * 0.2f, h * 0.045f, p)
    }

    private fun drawNeedle(c: Canvas, fr: RectF, w: Float, h: Float, nd: Needle, dark: Boolean) {
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
        if (dark) {
            stroke.color = withAlpha(colTop[n / 2], 80)
            stroke.strokeWidth = h * 0.05f
            c.drawLine(cx, cy, tx, ty, stroke)
            stroke.color = Color.rgb(242, 246, 248)
            stroke.strokeWidth = h * 0.016f
            c.drawLine(cx, cy, tx, ty, stroke)
        } else {
            // 影
            stroke.color = Color.argb(60, 0, 0, 0)
            stroke.strokeWidth = h * 0.02f
            c.drawLine(cx + h * 0.012f, cy + h * 0.012f, tx + h * 0.012f, ty + h * 0.012f, stroke)
            stroke.color = Color.rgb(28, 24, 20)
            stroke.strokeWidth = h * 0.016f
            c.drawLine(cx, cy, tx, ty, stroke)
        }
        c.restore()
        // ピークのランプ
        if (nd.hold > 0f) {
            fill.color = Color.rgb(255, 64, 48)
            c.drawCircle(fr.right - h * 0.16f, fr.top + h * 0.2f, h * 0.04f, fill)
        }
        // ガラスの映り込み（クラシックだけ）
        if (!dark) {
            if (fr.top != gkTop || fr.bottom != gkBottom || glassShader == null) {
                glassShader = LinearGradient(0f, fr.top, 0f, fr.top + h * 0.5f, Color.argb(70, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
                gkTop = fr.top
                gkBottom = fr.bottom
            }
            fill.shader = glassShader
            tmp.set(fr.left + h * 0.03f, fr.top + h * 0.03f, fr.right - h * 0.03f, fr.top + h * 0.5f)
            c.drawRoundRect(tmp, h * 0.06f, h * 0.06f, fill)
            fill.shader = null
        }
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
            demoPeak[i] = if (still) (v + 0.08f).coerceAtMost(1f) else max(v, demoPeak[i] - dtSec * 0.6f)
        }
        if (still) {
            // 止めた見本では、メーターの針をその位置に置く
            needles[0].pos = rms(demoVis, 0, 7) * 1.2f
            needles[1].pos = rms(demoVis, n / 2, n) * 1.2f
            needles[0].vel = 0f
            needles[1].vel = 0f
        }
        draw(c, r, style, demoVis, demoPeak, scale, max(3.5f, r.height() / 22f), true, if (still) 0f else dtSec)
    }

    companion object {
        fun mix(a: Int, b: Int, t: Float): Int {
            val k = t.coerceIn(0f, 1f)
            return Color.rgb(
                (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
                (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
                (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt()
            )
        }

        fun withAlpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    }
}

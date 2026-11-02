package com.example.spectrum

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * 画面全体(ジャケット/スペアナ/曲情報/操作/シーク)を描く。
 * 横長なら横レイアウト、縦長なら縦レイアウトに自動で切り替える。
 * スマホの View と Android Auto の Surface の両方から使う。
 *
 * 軽く・なめらかに描くための工夫:
 *  - スペアナの段(LED)は、段の色と隙間を描いた細い画像を模様として使い、1 本のバーを 1 回で描く
 *    (従来は段ごとに描いていたので 1 フレームで数百回)
 *  - バーの動きは経過時間で計算する(描く間隔が多少ぶれても、動く速さが変わらない)
 *  - ジャケットの縮小と代表色の計算は別スレッドで行う(曲が変わった瞬間に描画が止まらない)
 *  - 毎フレーム作り直していた背景のグラデーション・時刻の文字などは使い回す
 */
class PlayerRenderer {
    companion object {
        private val ICON = Color.rgb(200, 225, 240)
        private val SUB_TEXT = Color.rgb(215, 230, 240)
        private val BOLD: Typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

        /** スペアナのグラデーション: 一番下の色 / 一番上の色 */
        private val BAR_BOTTOM = Color.rgb(0, 90, 200)     // 深い青
        private val BAR_TOP = Color.rgb(40, 190, 245)      // 明るい水色寄りの青

        /** 長い曲名などの流し表示: 先頭で止める時間 / 末尾で止める時間 / 流す速さ(仮想座標/秒) */
        private const val MARQUEE_HOLD_START_MS = 3000L
        private const val MARQUEE_HOLD_END_MS = 1500L
        private const val MARQUEE_SPEED = 30f

        /** 再生バー(Android Auto 風の波): 残り部分の色 / 波の高さ / 波の長さ / 流れる速さ(周/秒) */
        private val TRACK = Color.rgb(95, 105, 125)
        private const val WAVE_AMP = 2.4f
        private const val WAVE_LENGTH = 14f
        private const val WAVE_SPEED = 0.8f

        /**
         * 再生ボタンと再生バーの色(アクセント色)。アルバムアートから自動で決める。
         * ジャケットが白黒など色を取り出せないときはこの色を使う。
         */
        private val DEFAULT_ACCENT = Color.rgb(214, 208, 250)
        /** アクセント色の明るさ・鮮やかさ(Android Auto の淡いボタン色に合わせる) */
        private const val ACCENT_SATURATION = 0.35f
        private const val ACCENT_VALUE = 0.97f
        /** 曲が変わったときの色の切り替わりの速さ(1 フレームあたり。大きいほど速い) */
        private const val ACCENT_FADE = 0.08f

        /** ジャケットの縮小・代表色の計算用(描画のスレッドを止めないため) */
        private val artWorker = Executors.newSingleThreadExecutor { r ->
            Thread(r, "spectrum-art-prep").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        }

        /** 表示用のジャケットの大きさの上限(画素) */
        private const val ART_MAX_PX = 512

        /** ジャケットを正方形に切り抜いて、表示に十分な大きさまで縮小する(ハードウェア画像も可) */
        fun prepareArt(src0: Bitmap): Bitmap? = try {
            val src = if (src0.config == Bitmap.Config.HARDWARE) {
                src0.copy(Bitmap.Config.ARGB_8888, false) ?: src0
            } else {
                src0
            }
            val side = min(src.width, src.height)
            if (side <= 0) {
                null
            } else {
                val target = min(side, ART_MAX_PX)
                val m = Matrix().apply { setScale(target.toFloat() / side, target.toFloat() / side) }
                val out = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side, m, true)
                if (src !== src0 && src !== out) src.recycle()
                out
            }
        } catch (_: Throwable) {
            null
        }

        /** アルバムアートの代表色(鮮やかで面積の多い色相)を、淡いアクセント色にして返す */
        fun extractAccent(src: Bitmap): Int = try {
            extractAccentInner(src)
        } catch (_: Exception) {
            DEFAULT_ACCENT
        }

        private fun extractAccentInner(src0: Bitmap): Int {
            // ハードウェア画像は画素を直接読めないので、読める形式に写してから縮小する
            val src = if (src0.config == Bitmap.Config.HARDWARE) {
                src0.copy(Bitmap.Config.ARGB_8888, false) ?: return DEFAULT_ACCENT
            } else {
                src0
            }
            val small = Bitmap.createScaledBitmap(src, 24, 24, true)
            val hsv = FloatArray(3)
            val score = FloatArray(36)
            val hueSum = FloatArray(36)
            var total = 0
            for (y in 0 until small.height) for (x in 0 until small.width) {
                total++
                Color.colorToHSV(small.getPixel(x, y), hsv)
                val s = hsv[1]; val v = hsv[2]
                if (v < 0.25f || s < 0.2f) continue          // 暗すぎ・灰色に近い色は除く
                val b = (hsv[0] / 10f).toInt().coerceIn(0, 35)
                val w = s * s * v                              // 鮮やかな色ほど重視
                score[b] += w
                hueSum[b] += hsv[0] * w
            }
            if (small !== src) small.recycle()
            if (src !== src0) src.recycle()
            var best = 0
            for (i in 1 until 36) if (score[i] > score[best]) best = i
            // 色のある部分がほとんど無いジャケットは既定色
            if (score[best] < total * 0.01f) return DEFAULT_ACCENT
            val hue = hueSum[best] / score[best]
            return Color.HSVToColor(floatArrayOf(hue, ACCENT_SATURATION, ACCENT_VALUE))
        }

        /** アクセント色のボタン上に置くアイコン色(同じ色相の濃い色) */
        fun darkOf(c: Int): Int {
            val hsv = FloatArray(3)
            Color.colorToHSV(c, hsv)
            return Color.HSVToColor(floatArrayOf(hsv[0], 0.55f, 0.25f))
        }

        private fun lerpColor(a: Int, b: Int, t: Float): Int = Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
        )
    }

    /** 仮想座標でのレイアウト定義 */
    private class Layout(
        val vw: Float, val vh: Float,
        val art: RectF,
        val spec: RectF,          // 曲情報ありのときのスペアナ
        val specOnly: RectF,      // 曲情報なしのときのスペアナ
        val textX: Float, val textRight: Float, val iconX: Float,
        val titleY: Float, val albumY: Float, val artistY: Float,
        val titleSize: Float, val subSize: Float,
        val prev: RectF, val play: RectF, val next: RectF,
        val seekX0: Float, val seekX1: Float, val seekY: Float,
        val timeY: Float, val timeSize: Float,
        val seekTouch: RectF,
        val hintY: Float,
        // ---- スペアナの見え方 ----
        val segUnit: Float,       // 1 段の高さ(隙間込み)。小さいほど段が細かく、動きが滑らか
        val gain: Float,          // 表示上の伸び。大きいほどバーが高く上がる
        val noiseFloor: Float,    // これ以下の小さな値は 0 とみなす(下の段のちらつき防止)
        val boldTitle: Boolean = false
    )

    /**
     * 横画面(Android Auto 全画面・横向きスマホ・ディスプレイオーディオ) 400x214。
     * 左上ジャケット、右にスペアナ(高め)、左下に曲情報、右下に大きい操作ボタン、最下部にシーク。
     */
    private val landscape = Layout(
        vw = 400f, vh = 214f,
        art = RectF(12f, 8f, 128f, 124f),       // 下端をスペアナの下端に揃える
        spec = RectF(148f, 8f, 380f, 124f),     // 左右の余白(ジャケットとの間・右端)をどちらも 20 に
        specOnly = RectF(12f, 8f, 388f, 126f),
        textX = 32f, textRight = 228f, iconX = 12f,
        titleY = 140f, albumY = 158f, artistY = 176f,
        titleSize = 14f, subSize = 12f,
        prev = RectF(240f, 139f, 274f, 173f),
        play = RectF(280f, 138f, 340f, 174f),
        next = RectF(346f, 139f, 380f, 173f),
        seekX0 = 72f, seekX1 = 322f, seekY = 198f,
        timeY = 202f, timeSize = 11f,
        seekTouch = RectF(66f, 186f, 328f, 214f),
        hintY = 202f,
        segUnit = 4.2f, gain = 1.25f, noiseFloor = 0.06f
    )

    /**
     * 縦画面: 幅 240 固定、高さは実際の画面比率に合わせる(縦長スマホでも余白が出ないように)。
     * 上に曲情報、中央にジャケット、下から中央の少し上までスペアナ、最下部に操作とシーク。
     */
    private fun portraitLayout(vh: Float): Layout {
        val artSize = 160f
        val artCy = vh * 0.47f
        return Layout(
            vw = 240f, vh = vh,
            art = RectF(120f - artSize / 2, artCy - artSize / 2, 120f + artSize / 2, artCy + artSize / 2),
            spec = RectF(10f, vh * 0.45f, 230f, vh - 74f),
            specOnly = RectF(10f, 40f, 230f, vh - 74f),
            textX = 34f, textRight = 228f, iconX = 12f,
            titleY = 34f, albumY = 60f, artistY = 86f,
            titleSize = 17f, subSize = 15f,
            prev = RectF(52f, vh - 61f, 80f, vh - 33f),
            play = RectF(95f, vh - 62f, 145f, vh - 32f),
            next = RectF(160f, vh - 61f, 188f, vh - 33f),
            seekX0 = 50f, seekX1 = 190f, seekY = vh - 16f,
            timeY = vh - 12f, timeSize = 9f,
            seekTouch = RectF(44f, vh - 28f, 196f, vh),
            hintY = vh - 12f,
            segUnit = 5.5f, gain = 1.0f, noiseFloor = 0.03f
        )
    }
    private var portraitCache: Layout? = null

    /**
     * 2 画面（車の画面・スマホ横画面の左側。Spectrum 4 : ハイウェイ 5）用: 幅 400 固定、高さは区画の比率に合わせる。
     * 上に曲名・アルバム名・アーティスト名（アイコン付き。運転中でも読めるよう大きく）、
     * 中段にジャケットとスペアナ、下に操作ボタンとシーク。
     * 中段に高さがあるとき（縦長の区画）は、従来の縦画面と同じくジャケットの下半分にスペアナを重ねる。
     */
    private fun splitLayout(vh: Float): Layout {
        val roomy = vh >= 290f
        val titleSize = if (roomy) 27f else 24f
        val subSize = if (roomy) 20f else 18f
        val titleY = if (roomy) 33f else 30f
        val albumY = titleY + subSize + (if (roomy) 9f else 8f)
        val artistY = albumY + subSize + (if (roomy) 8f else 7f)
        val textBottom = artistY + subSize * 0.5f

        val seekY = vh - 12f
        val playH = if (roomy) 50f else 44f
        val ctrlCy = seekY - 17f - playH / 2
        val side = playH * 0.92f
        val midTop = textBottom + 6f
        val midBottom = ctrlCy - playH / 2 - 9f
        val midH = midBottom - midTop

        val art: RectF
        val spec: RectF
        val stacked = midH >= 230f
        if (stacked) {
            // 縦長: ジャケットを中央に、下半分にスペアナを重ねる（従来の縦画面と同じ見え方）
            val a = min(midH - 60f, 210f)
            art = RectF(200f - a / 2, midTop, 200f + a / 2, midTop + a)
            spec = RectF(12f, midTop + a * 0.5f, 388f, midBottom)
        } else {
            // 横長: 左にジャケット、右にスペアナ
            val a = min(midH, 172f).coerceAtLeast(40f)
            val at = midTop + (midH - a) / 2
            art = RectF(12f, at, 12f + a, at + a)
            spec = RectF(12f + a + 16f, midTop, 388f, midBottom)
        }
        return Layout(
            vw = 400f, vh = vh,
            art = art,
            spec = spec,
            specOnly = RectF(12f, 40f, 388f, midBottom),
            textX = 46f, textRight = 388f, iconX = 16f,
            titleY = titleY, albumY = albumY, artistY = artistY,
            titleSize = titleSize, subSize = subSize,
            prev = RectF(130f - side * 1.55f, ctrlCy - side / 2, 130f - side * 0.55f, ctrlCy + side / 2),
            play = RectF(200f - playH, ctrlCy - playH / 2, 200f + playH, ctrlCy + playH / 2),
            next = RectF(270f + side * 0.55f, ctrlCy - side / 2, 270f + side * 1.55f, ctrlCy + side / 2),
            seekX0 = 66f, seekX1 = 334f, seekY = seekY,
            timeY = seekY + 4f, timeSize = 11f,
            seekTouch = RectF(60f, seekY - 13f, 340f, vh),
            hintY = seekY + 4f,
            segUnit = if (stacked) 5.5f else 4.4f,
            gain = if (stacked) 1.0f else 1.2f,
            noiseFloor = if (stacked) 0.03f else 0.05f,
            boldTitle = true
        )
    }
    private var splitCache: Layout? = null

    /** 2 画面（左右に並べたとき）の表示にする */
    var large = false

    private var lay = landscape

    /** ジャケット(アルバムアート)がタップされたときの処理 */
    var onArtTap: (() -> Unit)? = null

    private val n = SpectrumEngine.BANDS
    private val target = FloatArray(n)
    private val level = FloatArray(n)
    private val peak = FloatArray(n)

    private var scale = 1f
    private var ox = 0f
    private var oy = 0f

    private val bg = Paint()
    private val shape = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    /** 背景のグラデーション(区画が変わったときだけ作り直す) */
    private var bgTop = Float.NaN
    private var bgLeft = Float.NaN
    private var bgHeight = -1

    /** スペアナの段(LED)の模様: 段の色と隙間を描いた幅 1 画素の画像 */
    private val ledPaint = Paint()
    private val ledMatrix = Matrix()
    private var ledShader: BitmapShader? = null
    private var ledRows = -1
    private var ledUnit = -1f
    private var ledScale = -1f
    private var ledHeightPx = 0

    /** バーの動きの計算に使う、前回描いた時刻 */
    private var lastSpecMs = 0L
    private var lastFrameMs = 0L
    /** 前のフレームからの経過(60fps の 1 フレームを 1 とする) */
    private var frameDt = 1f

    private val iconRect = RectF()
    private val artSrc = Rect()
    private val peakColor = Color.rgb(215, 225, 235)

    /** 曲名などの幅(文字と大きさが同じ間は測り直さない) */
    private val widthCache = arrayOf(WidthEntry(), WidthEntry(), WidthEntry())

    private class WidthEntry {
        var text = ""
        var size = 0f
        var bold = false
        var width = 0f
    }

    /** 再生位置の文字(1 秒ごとに作る) */
    private var posSec = -1L
    private var remSec = Long.MIN_VALUE
    private var posText = ""
    private var remText = ""

    /** 曲名・アルバム名・アーティスト名それぞれの流し表示の状態 */
    private class Marquee {
        var text = ""
        var start = 0L
    }
    private val marquees = Array(3) { Marquee() }

    // ---- アクセント色(アルバムアート連動) ----
    private var artRef: Bitmap? = null
    private var accentTarget = DEFAULT_ACCENT
    private var accR = Color.red(DEFAULT_ACCENT).toFloat()
    private var accG = Color.green(DEFAULT_ACCENT).toFloat()
    private var accB = Color.blue(DEFAULT_ACCENT).toFloat()
    private var accent = DEFAULT_ACCENT
    private var accentDark = darkOf(DEFAULT_ACCENT)

    /** 表示用に縮小したジャケット(別スレッドで作る)と、その元の画像 */
    @Volatile private var artShown: Bitmap? = null
    @Volatile private var artShownFor: Bitmap? = null
    @Volatile private var artRequested: Bitmap? = null
    @Volatile private var accentReady = DEFAULT_ACCENT

    /**
     * ジャケットが変わったら、縮小した画像と代表色を別スレッドで作り、できたら差し替える。
     * 代表色へはなめらかに近づける。
     */
    private fun updateAccent(dtFrames: Float) {
        val bmp = NowPlaying.art
        if (bmp !== artRef) {
            artRef = bmp
            artRequested = bmp
            if (bmp == null || bmp.isRecycled) {
                accentReady = DEFAULT_ACCENT
                artShown = null
                artShownFor = bmp
            } else {
                artWorker.execute {
                    val small = prepareArt(bmp)
                    val acc = extractAccent(small ?: bmp)
                    if (artRequested === bmp) {
                        artShown = small
                        artShownFor = bmp
                        accentReady = acc
                    }
                }
            }
        }
        accentTarget = accentReady
        val k = 1f - (1f - ACCENT_FADE).pow(dtFrames)
        accR += (Color.red(accentTarget) - accR) * k
        accG += (Color.green(accentTarget) - accG) * k
        accB += (Color.blue(accentTarget) - accB) * k
        val c = Color.rgb(accR.roundToIntSafe(), accG.roundToIntSafe(), accB.roundToIntSafe())
        if (c != accent) {
            accent = c
            accentDark = darkOf(c)
        }
    }

    private fun Float.roundToIntSafe(): Int = (this + 0.5f).toInt().coerceIn(0, 255)

    private val wavePath = Path()
    /** 波の高さ(再生中は WAVE_AMP、停止中は 0 へなめらかに変化) */
    private var waveAmp = 0f

    fun setSpectrum(v: FloatArray) {
        System.arraycopy(v, 0, target, 0, min(v.size, n))
    }

    /** バー・ピーク・再生バーの波がまだ動いている（描き直す間隔を決めるのに使う） */
    val animating: Boolean
        get() {
            if (waveAmp > 0.05f) return true
            for (i in 0 until n) if (target[i] > 0.01f || level[i] > 0.01f || peak[i] > 0.01f) return true
            return false
        }

    fun draw(c: Canvas, left: Int, top: Int, w: Int, h: Int) {
        val l = left.toFloat()
        val t = top.toFloat()
        if (t != bgTop || l != bgLeft || h != bgHeight) {
            bgTop = t
            bgLeft = l
            bgHeight = h
            bg.shader = LinearGradient(
                l, t, l, t + h, Color.rgb(14, 42, 58), Color.rgb(4, 12, 20), Shader.TileMode.CLAMP
            )
        }
        c.drawRect(l, t, l + w, t + h, bg)
        // 前のフレームからの経過(60fps のときを 1 とする)。描く間隔がぶれても動きの速さを保つ
        val nowMs = SystemClock.uptimeMillis()
        val dtFrames = if (lastFrameMs == 0L) 1f else ((nowMs - lastFrameMs) / (1000f / 60f)).coerceIn(0f, 6f)
        lastFrameMs = nowMs
        frameDt = dtFrames
        updateAccent(dtFrames)

        val aspect = if (h > 0) w.toFloat() / h else 1f
        lay = if (large && aspect in 0.62f..1.75f) {
            // 2 画面: 区画の比率に合わせた表示（曲名・アルバム名・アーティスト名を大きく）
            val vh = (400f / aspect).coerceIn(250f, 600f)
            val sc = splitCache
            if (sc != null && sc.vh == vh) sc else splitLayout(vh).also { splitCache = it }
        } else if (h > w) {
            val vh = (240f * h / w).coerceIn(380f, 560f)
            val pc = portraitCache
            if (pc != null && pc.vh == vh) pc else portraitLayout(vh).also { portraitCache = it }
        } else {
            landscape
        }
        scale = min(w / lay.vw, h / lay.vh)
        ox = l + (w - lay.vw * scale) / 2f
        oy = t + (h - lay.vh * scale) / 2f

        c.save()
        c.translate(ox, oy)
        c.scale(scale, scale)
        if (NowPlaying.hasInfo) {
            drawArt(c)                         // スペアナより先に描く(縦画面では重なる)
            drawSpectrum(c, lay.spec)
            drawTexts(c)
            drawControls(c)
            if (NowPlaying.durationMs > 0 || NowPlaying.state != null) drawSeek(c)
        } else {
            // 曲情報が取れないとき: スペアナを大きく表示
            drawSpectrum(c, lay.specOnly)
            drawControls(c)
            drawHint(c)
        }
        c.restore()
    }

    fun onTap(x: Float, y: Float): Boolean {
        val vx = (x - ox) / scale
        val vy = (y - oy) / scale
        when {
            lay.prev.contains(vx, vy) -> NowPlaying.prev()
            lay.play.contains(vx, vy) -> NowPlaying.playPause()
            lay.next.contains(vx, vy) -> NowPlaying.next()
            lay.seekTouch.contains(vx, vy) -> NowPlaying.seekToFraction(
                ((vx - lay.seekX0) / (lay.seekX1 - lay.seekX0)).coerceIn(0f, 1f)
            )
            NowPlaying.hasInfo && lay.art.contains(vx, vy) -> onArtTap?.invoke()
            else -> return false
        }
        return true
    }

    // ---------------------------------------------------------------- parts

    private fun drawArt(c: Canvas) {
        val dst = lay.art
        val original = NowPlaying.art
        // 縮小済みの画像があればそれを描く。無ければ(作っている間)、そのまま描いても重くない画像だけ描く
        val prepared = artShown?.takeIf { artShownFor === original && !it.isRecycled }
        val bmp = prepared ?: original?.takeIf {
            !it.isRecycled && (it.config == Bitmap.Config.HARDWARE || max(it.width, it.height) <= 1200)
        }
        if (bmp != null) {
            val side = min(bmp.width, bmp.height)
            artSrc.set(
                (bmp.width - side) / 2, (bmp.height - side) / 2,
                (bmp.width + side) / 2, (bmp.height + side) / 2
            )
            c.drawBitmap(bmp, artSrc, dst, bmpPaint)
        } else {
            shape.style = Paint.Style.FILL
            shape.color = Color.rgb(20, 40, 55)
            c.drawRect(dst, shape)
            text.color = Color.rgb(90, 120, 140)
            text.textSize = dst.height() * 0.5f
            text.textAlign = Paint.Align.CENTER
            c.drawText("♪", dst.centerX(), dst.centerY() + dst.height() * 0.18f, text)
            text.textAlign = Paint.Align.LEFT
        }
    }

    /**
     * 段(LED)の模様を用意する: 幅 1 画素・高さ = 全段分の画像に、段ごとの色(下の濃い青 → 上の薄い青)と
     * 段の隙間(透明)を描く。画面の画素と 1 対 1 で重なるよう、描く倍率に合わせて作る(区画が変わったときだけ)。
     */
    private fun ledShaderFor(rows: Int, unit: Float, segH: Float): BitmapShader? {
        if (rows <= 0 || scale <= 0f) return null
        if (rows != ledRows || unit != ledUnit || scale != ledScale || ledShader == null) {
            val hPx = ceil(rows * unit * scale).toInt().coerceIn(1, 4096)
            val px = IntArray(hPx)
            val total = rows * unit
            for (y in 0 until hPx) {
                // 画像の上端からの位置 → 下端(バーの根元)からの位置(仮想座標)
                val fromBottom = total - (y + 0.5f) / scale
                val rr = floor(fromBottom / unit).toInt()
                val within = fromBottom - rr * unit
                px[y] = if (rr in 0 until rows && within < segH) {
                    lerpColor(BAR_BOTTOM, BAR_TOP, if (rows > 1) rr.toFloat() / (rows - 1) else 0f)
                } else 0
            }
            val bmp = try {
                Bitmap.createBitmap(1, hPx, Bitmap.Config.ARGB_8888)
            } catch (_: Throwable) {
                return null
            }
            bmp.setPixels(px, 0, 1, 0, 0, 1, hPx)
            ledShader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.CLAMP)
            ledRows = rows
            ledUnit = unit
            ledScale = scale
            ledHeightPx = hPx
        }
        return ledShader
    }

    private fun drawSpectrum(c: Canvas, r: RectF) {
        val x0 = r.left; val y1 = r.bottom
        val gap = 2f
        val barW = (r.width() - gap * (n - 1)) / n
        val unit = lay.segUnit
        val segH = unit * 0.72f               // 段の高さ
        val rows = ((r.height() + unit - segH) / unit).toInt()

        shape.style = Paint.Style.FILL
        val st = SpectrumEngine.status
        if (st != SpectrumEngine.Status.RUNNING) target.fill(0f)

        // 経過時間に合わせた動き(60fps で 1 フレームあたり 上がる 0.6・下がる 0.18・ピーク 0.01 と同じ速さ)
        val nowMs = SystemClock.uptimeMillis()
        val f60 = if (lastSpecMs == 0L) 1f else ((nowMs - lastSpecMs) / (1000f / 60f)).coerceIn(0f, 6f)
        lastSpecMs = nowMs
        val riseK = 1f - 0.4f.pow(f60)
        val fallK = 1f - 0.82f.pow(f60)
        val peakFall = 0.01f * f60

        // 段の模様の位置: 画像の 1 画素 = 画面の 1 画素、画像の下端 = バーの根元
        val shader = ledShaderFor(rows, unit, segH)
        if (shader != null) {
            ledMatrix.setScale(1f / scale, 1f / scale)
            ledMatrix.postTranslate(0f, y1 - ledHeightPx / scale)
            shader.setLocalMatrix(ledMatrix)
            ledPaint.shader = shader
        }

        val floor = lay.noiseFloor
        for (i in 0 until n) {
            level[i] += (target[i] - level[i]) * (if (target[i] > level[i]) riseK else fallK)

            // 小さな値は切り捨てて(下の段のちらつき防止)、残りを伸ばして高さを使い切る
            val vis = (((level[i] - floor) / (1f - floor)) * lay.gain).coerceIn(0f, 1f)
            peak[i] = if (vis > peak[i]) vis else max(0f, peak[i] - peakFall)

            val bx = x0 + i * (barW + gap)
            val lit = (vis * rows).toInt()          // 切り捨て: 届いた段だけ点灯
            if (lit > 0) {
                if (shader != null) {
                    // 届いた段までを 1 回で描く(色と隙間は模様が受け持つ)
                    c.drawRect(bx, y1 - lit * unit, bx + barW, y1, ledPaint)
                } else {
                    for (rr in 0 until lit) {
                        shape.color = lerpColor(BAR_BOTTOM, BAR_TOP, rr.toFloat() / (rows - 1))
                        val bottom = y1 - rr * unit
                        c.drawRect(bx, bottom - segH, bx + barW, bottom, shape)
                    }
                }
            }
            if (peak[i] > 0.04f) {
                val row = min(max((peak[i] * rows).toInt(), lit), rows - 1)
                val bottom = y1 - row * unit
                shape.color = peakColor
                c.drawRect(bx, bottom - segH * 0.6f, bx + barW, bottom, shape)
            }
        }

        val msg = when (st) {
            SpectrumEngine.Status.NO_PERMISSION -> "マイク権限を許可してください"
            SpectrumEngine.Status.UNSUPPORTED -> "この端末では音声解析を開始できません"
            else -> null
        }
        if (msg != null) {
            text.color = Color.rgb(140, 165, 180)
            text.textSize = 11f
            text.textAlign = Paint.Align.CENTER
            c.drawText(msg, r.centerX(), r.centerY(), text)
            text.textAlign = Paint.Align.LEFT
        }
    }

    private fun drawHint(c: Canvas) {
        val msg = if (!NowPlaying.listenerConnected) "通知アクセスを許可すると曲名を表示できます"
                  else "曲情報を取得できません"
        text.color = Color.rgb(140, 165, 180)
        text.textSize = 10f
        text.textAlign = Paint.Align.CENTER
        val cut = TextUtils.ellipsize(msg, text, lay.vw - 16f, TextUtils.TruncateAt.END)
        c.drawText(cut, 0, cut.length, lay.vw / 2f, lay.hintY, text)
        text.textAlign = Paint.Align.LEFT
    }

    private fun drawTexts(c: Canvas) {
        val ix = lay.iconX
        if (ix >= 0f) {
            val cx = ix + 7f
            val k = lay.subSize / 13f        // アイコンの大きさを文字に合わせる

            // ♪ / ディスク / 人 のアイコン
            text.color = ICON
            text.textSize = lay.titleSize + 1f
            c.drawText("♪", ix, lay.titleY, text)

            shape.color = ICON
            shape.style = Paint.Style.STROKE
            shape.strokeWidth = 1.6f
            val dy = lay.albumY - 5f * k
            c.drawCircle(cx, dy, 7f * k, shape)
            shape.style = Paint.Style.FILL
            c.drawCircle(cx, dy, 2f * k, shape)
            c.drawCircle(cx, lay.artistY - 10f * k, 3.2f * k, shape)
            iconRect.set(cx - 6.5f * k, lay.artistY - 6f * k, cx + 6.5f * k, lay.artistY + 8f * k)
            c.drawArc(iconRect, 180f, 180f, true, shape)
        }

        text.typeface = if (lay.boldTitle) BOLD else null
        marqueeText(c, 0, NowPlaying.title, lay.titleY, lay.titleSize, Color.WHITE)
        text.typeface = null
        if (lay.albumY >= 0f) marqueeText(c, 1, NowPlaying.album, lay.albumY, lay.subSize, SUB_TEXT)
        marqueeText(c, 2, NowPlaying.artist, lay.artistY, lay.subSize, SUB_TEXT)
    }

    /**
     * 1 行の文字を描く。表示幅に収まらないときは
     * 「先頭で 3 秒止める → 右から左へ流して末尾まで表示 → 末尾で少し止める → 先頭に戻る」を繰り返す。
     */
    private fun marqueeText(c: Canvas, index: Int, s: String, y: Float, size: Float, color: Int) {
        val m = marquees[index]
        val now = SystemClock.uptimeMillis()
        if (m.text != s) {             // 曲が変わったら先頭からやり直す
            m.text = s
            m.start = now
        }
        if (s.isEmpty()) return
        text.color = color
        text.textSize = size

        val x = lay.textX
        val avail = lay.textRight - x
        // 文字の幅は、文字・大きさ・太さが変わったときだけ測る
        val wc = widthCache[index]
        val bold = text.typeface === BOLD
        if (wc.text != s || wc.size != size || wc.bold != bold) {
            wc.text = s
            wc.size = size
            wc.bold = bold
            wc.width = text.measureText(s)
        }
        val overflow = wc.width - avail
        if (overflow <= 0f) {
            c.drawText(s, x, y, text)
            return
        }

        val scrollMs = (overflow / MARQUEE_SPEED * 1000f).toLong().coerceAtLeast(1L)
        val cycle = MARQUEE_HOLD_START_MS + scrollMs + MARQUEE_HOLD_END_MS
        val t = (now - m.start) % cycle
        val offset = when {
            t < MARQUEE_HOLD_START_MS -> 0f
            t < MARQUEE_HOLD_START_MS + scrollMs -> overflow * (t - MARQUEE_HOLD_START_MS) / scrollMs
            else -> overflow
        }

        // 表示枠の外(アイコン側・右側)にはみ出さないよう切り抜いて描く
        c.save()
        c.clipRect(x, y - size * 1.2f, lay.textRight, y + size * 0.4f)
        c.drawText(s, x - offset, y, text)
        c.restore()
    }

    /** Android Auto 風の大きいボタン: 再生/一時停止は丸いボタン、前/次は大きいアイコン */
    private fun drawControls(c: Canvas) {
        shape.style = Paint.Style.FILL

        // 再生/一時停止
        val p = lay.play
        shape.color = accent
        c.drawRoundRect(p, p.height() / 2f, p.height() / 2f, shape)
        shape.color = accentDark
        val pcx = p.centerX(); val pcy = p.centerY()
        val s = p.height() * 0.24f
        if (NowPlaying.isPlaying) {
            val bw = s * 0.55f
            c.drawRoundRect(pcx - s * 0.9f, pcy - s, pcx - s * 0.9f + bw, pcy + s, bw * 0.3f, bw * 0.3f, shape)
            c.drawRoundRect(pcx + s * 0.9f - bw, pcy - s, pcx + s * 0.9f, pcy + s, bw * 0.3f, bw * 0.3f, shape)
        } else {
            tri(c, pcx - s * 0.7f, pcy - s, pcx - s * 0.7f, pcy + s, pcx + s, pcy)
        }

        // 前の曲 / 次の曲 (|◀  ▶|)
        shape.color = Color.WHITE
        val q = lay.prev.height() * 0.26f
        var cx = lay.prev.centerX(); var cy = lay.prev.centerY()
        c.drawRect(cx - q, cy - q, cx - q + q * 0.3f, cy + q, shape)
        tri(c, cx + q, cy - q, cx + q, cy + q, cx - q * 0.55f, cy)
        cx = lay.next.centerX(); cy = lay.next.centerY()
        c.drawRect(cx + q - q * 0.3f, cy - q, cx + q, cy + q, shape)
        tri(c, cx - q, cy - q, cx - q, cy + q, cx + q * 0.55f, cy)
    }

    private fun tri(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        path.reset()
        path.moveTo(x1, y1); path.lineTo(x2, y2); path.lineTo(x3, y3); path.close()
        c.drawPath(path, shape)
    }

    private fun drawSeek(c: Canvas) {
        val dur = NowPlaying.durationMs
        val pos = NowPlaying.positionMs()

        // 時刻の文字は秒が変わったときだけ作る
        val ps = pos / 1000
        val rs = if (dur > 0) (dur - pos) / 1000 else -1L
        if (ps != posSec) {
            posSec = ps
            posText = fmt(pos)
        }
        if (rs != remSec) {
            remSec = rs
            remText = if (dur > 0) "-" + fmt(dur - pos) else "-:--:--"
        }
        text.color = Color.WHITE
        text.textSize = lay.timeSize
        text.textAlign = Paint.Align.LEFT
        c.drawText(posText, 8f, lay.timeY, text)
        text.textAlign = Paint.Align.RIGHT
        c.drawText(remText, lay.vw - 8f, lay.timeY, text)
        text.textAlign = Paint.Align.LEFT

        val x0 = lay.seekX0
        val x1 = lay.seekX1
        val y = lay.seekY
        val frac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
        val xp = x0 + (x1 - x0) * frac

        // 再生中は波打ち、停止中は平らな線になる(なめらかに切り替え)
        val targetAmp = if (NowPlaying.isPlaying) WAVE_AMP else 0f
        waveAmp += (targetAmp - waveAmp) * (1f - 0.92f.pow(frameDt))
        val phase = (SystemClock.uptimeMillis() % 100000L) / 1000f * WAVE_SPEED * 2f * PI.toFloat()

        // 残り部分: 細い線 + 終端の小さな点
        shape.style = Paint.Style.STROKE
        shape.strokeCap = Paint.Cap.ROUND
        shape.strokeWidth = 2.5f
        shape.color = TRACK
        if (xp + 6f < x1) c.drawLine(xp + 6f, y, x1, y, shape)
        shape.style = Paint.Style.FILL
        c.drawCircle(x1, y, 2f, shape)

        // 再生済み部分: 波
        if (xp > x0 + 1f) {
            wavePath.reset()
            var x = x0
            wavePath.moveTo(x, y + waveAmp * sin(-phase))
            while (x < xp) {
                x = min(x + 1.5f, xp)
                wavePath.lineTo(x, y + waveAmp * sin((x - x0) / WAVE_LENGTH * 2f * PI.toFloat() - phase))
            }
            shape.style = Paint.Style.STROKE
            shape.strokeWidth = 3f
            shape.color = accent
            c.drawPath(wavePath, shape)
        }

        // 現在位置: 縦の短いバー
        shape.style = Paint.Style.FILL
        shape.color = accent
        c.drawRoundRect(xp - 1.6f, y - 7f, xp + 1.6f, y + 7f, 1.6f, 1.6f, shape)
        shape.strokeCap = Paint.Cap.BUTT
    }

    private fun fmt(ms: Long): String {
        val s = ms.coerceAtLeast(0L) / 1000
        return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }
}

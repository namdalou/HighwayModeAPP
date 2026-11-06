package com.example.spectrum

import android.graphics.Bitmap
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

        /** スペアナのグラデーション（標準の青）: 一番下の色 / 一番上の色。色は設定（SpectrumStyle）で変えられる */
        private val BAR_BOTTOM = Color.rgb(0, 90, 200)     // 深い青
        private val BAR_TOP = Color.rgb(40, 190, 245)      // 明るい水色寄りの青

        /**
         * バーの動き（v2.10、v2.12 で速く）: 上がるときは時定数 [RISE_TAU] 秒ですぐ目標へ、下がるときは一定の速さ [FALL_PER_SEC]
         * （解析の値/秒。画面の高さでは毎秒約 4 本分）で落ちる。拍でパッと上がって、上がるよりは少しゆっくり落ちる
         */
        private const val RISE_TAU = 0.012f
        private const val FALL_PER_SEC = 4.0f

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
        val boldTitle: Boolean = false,
        val timeMargin: Float = 8f // 再生時間・残り時間の文字の、画面の端からの位置
    )

    /**
     * 横画面(Android Auto 全画面・横向きスマホ・ディスプレイオーディオ): 高さ 214、幅は画面の比率に合わせる(400〜640)。
     * 左上ジャケット、右にスペアナ(高め)、左下に曲情報、右下に大きい操作ボタン、最下部にシーク。
     * v2.9 までは幅 400 に固定して画面に収めていたので、横に長い画面(車の 1920x720 など)では左右に大きな余白が出ていた。
     * v2.10 から、横に長い分はスペアナ・曲情報・シークを横に広げる(ジャケット・文字・ボタンの大きさと形はそのまま)。
     * v2.11: v2.10 は端まで使いすぎて窮屈だったので、左右に幅の約 4.5%（18〜28）、上に 12 の余白を取る
     * （テレビ・車の画面の「見やすい範囲」の目安（端から 3.5〜5%）と、スマホの縦画面の余白（幅の約 4%）に合わせた）。
     */
    private fun landscapeLayout(vw: Float): Layout {
        val m = (vw * 0.045f).coerceIn(18f, 28f)   // 左右の余白
        val top = 12f
        val artSize = 110f
        val right = vw - m
        return Layout(
            vw = vw, vh = 214f,
            art = RectF(m, top, m + artSize, top + artSize),        // 下端をスペアナの下端に揃える
            spec = RectF(m + artSize + 18f, top, right, top + artSize),
            specOnly = RectF(m, top, right, top + artSize + 4f),
            textX = m + 20f, textRight = right - 160f, iconX = m,
            titleY = 142f, albumY = 160f, artistY = 178f,
            titleSize = 14f, subSize = 12f,
            prev = RectF(right - 140f, 140f, right - 106f, 174f),
            play = RectF(right - 100f, 139f, right - 40f, 175f),
            next = RectF(right - 34f, 140f, right, 174f),
            seekX0 = m + 62f, seekX1 = right - 70f, seekY = 198f,
            timeY = 202f, timeSize = 11f,
            seekTouch = RectF(m + 56f, 186f, right - 64f, 214f),
            hintY = 202f,
            // LED のブロックはスマホの縦画面と同じ大きさ（段の間隔 5.5）、伸びも縦画面と同じ 1.0（v2.12。1.25 だと上に張り付いて見えた）
            segUnit = 5.5f, gain = 1.0f, noiseFloor = 0.03f,
            timeMargin = m
        )
    }
    private val landscape = landscapeLayout(400f)
    private var landscapeCache: Layout? = null

    /**
     * 縦画面: 幅 240 固定、高さは実際の画面比率に合わせる(縦長スマホでも余白が出ないように)。
     * 上に曲情報、その下にジャケット、ジャケットの下にスペアナ（重ねない）、最下部に操作とシーク。
     * v2.5 まではジャケットの下半分にスペアナを重ねていたが、消えている段・表示窓を描く表示ではジャケットに被るので、
     * v2.6 から重ねずに上下に並べる（ジャケットの大きさは画面の高さに合わせる）。
     */
    private fun portraitLayout(vh: Float): Layout {
        val midTop = 100f
        val midBottom = vh - 74f
        val gap = 10f
        val artSize = ((midBottom - midTop - gap) * 0.56f).coerceIn(96f, 168f)
        val specTop = midTop + artSize + gap
        return Layout(
            vw = 240f, vh = vh,
            art = RectF(120f - artSize / 2, midTop, 120f + artSize / 2, midTop + artSize),
            spec = RectF(10f, specTop, 230f, midBottom),
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
     * 中段に高さがあるとき（縦長の区画）は、ジャケットを上に、その下にスペアナを置く（v2.6 から重ねない）。
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
            // 縦長: ジャケットを上の中央に、その下にスペアナ（重ねない）
            val a = ((midH - 10f) * 0.56f).coerceIn(90f, 210f)
            art = RectF(200f - a / 2, midTop, 200f + a / 2, midTop + a)
            spec = RectF(12f, midTop + a + 10f, 388f, midBottom)
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
            segUnit = 5.5f,
            gain = 1.0f,
            noiseFloor = 0.03f,
            boldTitle = true
        )
    }
    private var splitCache: Layout? = null

    /** 2 画面（左右に並べたとき）の表示にする */
    var large = false

    private var lay = landscape

    /** ジャケット(アルバムアート)がタップされたときの処理 */
    var onArtTap: (() -> Unit)? = null

    /** カーナビの案内の帯がタップされたときの処理（スマホではナビのアプリを開く。車の画面では何もしない） */
    var onGuideTap: (() -> Unit)? = null

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
    private var bgStyle = -1
    private val bgColors = IntArray(2)

    /** スペアナの表示（LED・バー・ライン・ドット・レトロ・メーターなど）を描く部品 */
    private val painter = SpectrumPainter(n)
    /** 帯ごとの見える高さ（0〜1）と、ピークを止めておく時刻 */
    private val visArr = FloatArray(n)
    private val peakHoldUntil = LongArray(n)
    /** スペアナを描く区画（メーターはジャケットの下に） */
    private val specRect = RectF()

    /** いまのバーの色（帯ごと）。色の設定・ジャケットが変わったときだけ作り直す */
    private val barBottoms = IntArray(n) { BAR_BOTTOM }
    private val barTops = IntArray(n) { BAR_TOP }
    private var colorsForTheme = -1
    private var colorsForVersion = -1
    private var colorsForArt = Int.MIN_VALUE

    /** バーの動きの計算に使う、前回描いた時刻 */
    private var lastSpecMs = 0L
    private var lastFrameMs = 0L
    /** 前のフレームからの経過(60fps の 1 フレームを 1 とする) */
    private var frameDt = 1f

    private val iconRect = RectF()
    private val artSrc = Rect()

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
    /** ジャケットから選んだバーの色（[ArtPalette]。0 = ジャケットなし） */
    @Volatile private var artBarReady = 0

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
                artBarReady = 0
                artShown = null
                artShownFor = bmp
            } else {
                artWorker.execute {
                    val small = prepareArt(bmp)
                    val acc = extractAccent(small ?: bmp)
                    val barBase = ArtPalette.dominant(small ?: bmp) ?: 0
                    if (artRequested === bmp) {
                        artShown = small
                        artShownFor = bmp
                        accentReady = acc
                        artBarReady = barBase
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

    /**
     * 設定のバーの色を反映する（毎フレーム呼ぶが、色の設定・ジャケットが変わったときだけ作り直す）。
     *  - 決まった色: 下を濃く・上を明るくしたグラデーション
     *  - ジャケットの色: ジャケットから選んだ色（[ArtPalette]）を同じようなグラデーションに。ジャケットが無いときは標準の青
     *  - カラーコード: 指定の色を中ほどにしたグラデーション
     *  - 虹色: 低音の赤から高音の紫まで、帯ごとに色を変える
     */
    private fun updateBarColors() {
        val t = SpectrumStyle.theme
        val ver = SpectrumStyle.version
        val art = if (t == SpectrumStyle.THEME_ART) artBarReady else 0
        if (t == colorsForTheme && ver == colorsForVersion && art == colorsForArt) return
        colorsForTheme = t
        colorsForVersion = ver
        colorsForArt = art
        SpectrumStyle.colorsFor(t, art, barBottoms, barTops)
        painter.setColors(barBottoms, barTops)
    }

    private val wavePath = Path()
    /** 波の高さ(再生中は WAVE_AMP、停止中は 0 へなめらかに変化) */
    private var waveAmp = 0f

    fun setSpectrum(v: FloatArray) {
        System.arraycopy(v, 0, target, 0, min(v.size, n))
    }

    /** バー・ピーク・再生バーの波がまだ動いている（描き直す間隔を決めるのに使う） */
    val animating: Boolean
        get() {
            if (waveAmp > 0.05f || painter.animating) return true
            for (i in 0 until n) if (target[i] > 0.01f || level[i] > 0.01f || peak[i] > 0.01f) return true
            return false
        }

    fun draw(c: Canvas, left: Int, top: Int, w: Int, h: Int) {
        val l = left.toFloat()
        val t = top.toFloat()
        // 背景（v2.8 から、どの表示の種類も同じ）
        val style = SpectrumStyle.visual
        if (t != bgTop || l != bgLeft || h != bgHeight || style != bgStyle) {
            bgTop = t
            bgLeft = l
            bgHeight = h
            bgStyle = style
            SpectrumPainter.screenBackground(style, bgColors)
            bg.shader = LinearGradient(
                l, t, l, t + h, bgColors[0], bgColors[1], Shader.TileMode.CLAMP
            )
        }
        c.drawRect(l, t, l + w, t + h, bg)
        // 前のフレームからの経過(60fps のときを 1 とする)。描く間隔がぶれても動きの速さを保つ
        val nowMs = SystemClock.uptimeMillis()
        val dtFrames = if (lastFrameMs == 0L) 1f else ((nowMs - lastFrameMs) / (1000f / 60f)).coerceIn(0f, 6f)
        lastFrameMs = nowMs
        frameDt = dtFrames
        updateAccent(dtFrames)
        updateBarColors()

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
            // 横画面: 幅を画面の比率に合わせる（横に長い画面でも左右に余白を出さない）
            val vw = (214f * aspect).coerceIn(400f, 640f)
            val lc = landscapeCache
            if (lc != null && lc.vw == vw) lc else landscapeLayout(vw).also { landscapeCache = it }
        }
        scale = min(w / lay.vw, h / lay.vh)
        ox = l + (w - lay.vw * scale) / 2f
        oy = t + (h - lay.vh * scale) / 2f

        c.save()
        c.translate(ox, oy)
        c.scale(scale, scale)
        // カーナビの案内（ナビ中）: スペアナの区画の上の部分に帯を出す（一般道でも）
        guideShown = Guidance.active
        if (NowPlaying.hasInfo) {
            drawArt(c)
            drawSpectrum(c, guideSplit(lay.spec))
            drawTexts(c)
            drawControls(c)
            if (NowPlaying.durationMs > 0 || NowPlaying.state != null) drawSeek(c)
        } else {
            // 曲情報が取れないとき: スペアナを大きく表示
            drawSpectrum(c, guideSplit(lay.specOnly))
            drawControls(c)
            drawHint(c)
        }
        if (guideShown) drawGuidance(c, guideRect)
        c.restore()
    }

    // ---------------------------------------------------------------- カーナビの案内の帯

    private var guideShown = false
    private val guideRect = RectF()
    private val guideSpec = RectF()
    private val guidePath = Path()
    private var gkVersion = -1
    private var gkAvail = -1f
    private var gkLine = ""
    private var gkDist = ""
    private var gkTwo = false
    private var gkSize = -1f

    /** 案内を出すときは、スペアナの区画の上を帯に使い、残りをスペアナにする */
    private fun guideSplit(spec: RectF): RectF {
        if (!guideShown) return spec
        val bh = (spec.height() * 0.3f).coerceIn(28f, 40f)
        guideRect.set(spec.left, spec.top, spec.right, spec.top + bh)
        guideSpec.set(spec.left, spec.top + bh + 5f, spec.right, spec.bottom)
        return guideSpec
    }

    private fun drawGuidance(c: Canvas, r: RectF) {
        // 地の色: 濃い緑（Google マップの案内の色）と、上側の細い光
        shape.style = Paint.Style.FILL
        shape.color = Color.rgb(14, 102, 56)
        c.drawRoundRect(r, 6f, 6f, shape)
        shape.color = Color.argb(40, 255, 255, 255)
        c.drawRect(r.left + 5f, r.top + 0.8f, r.right - 5f, r.top + 1.5f, shape)
        // 矢印（ナビアプリの画像。無ければ案内の文から描く）
        val ih = r.height() - 8f
        iconRect.set(r.left + 5f, r.top + 4f, r.left + 5f + ih, r.bottom - 4f)
        val icon = Guidance.icon
        if (icon != null && !icon.isRecycled) {
            artSrc.set(0, 0, icon.width, icon.height)
            c.drawBitmap(icon, artSrc, iconRect, bmpPaint)
        } else {
            drawArrow(c, iconRect, Guidance.arrow)
        }
        val x = iconRect.right + 7f
        val avail = r.right - 6f - x
        // 2 段（距離の下に案内の文）にするか: 区画が横に長くなければ 2 段
        val two = r.width() / r.height() < 7.5f
        val big = r.height() * (if (two) 0.40f else 0.5f)
        val small = r.height() * (if (two) 0.30f else 0.36f)
        if (Guidance.version != gkVersion || avail != gkAvail || two != gkTwo || big != gkSize) {
            gkVersion = Guidance.version
            gkAvail = avail
            gkTwo = two
            gkSize = big
            gkDist = Guidance.distanceM?.let { Guidance.distanceLabel(it) } ?: ""
            text.typeface = BOLD
            text.textSize = big
            val distW = if (gkDist.isEmpty()) 0f else text.measureText(gkDist) + 6f
            text.typeface = Typeface.DEFAULT
            text.textSize = small
            val lineAvail = if (two) avail else avail - distW
            gkLine = TextUtils.ellipsize(Guidance.text.orEmpty(), text, lineAvail.coerceAtLeast(10f), TextUtils.TruncateAt.END).toString()
        }
        text.textAlign = Paint.Align.LEFT
        text.color = Color.WHITE
        if (two) {
            text.typeface = BOLD
            text.textSize = big
            val y1 = r.top + r.height() * 0.46f
            c.drawText(if (gkDist.isEmpty()) Guidance.source else gkDist, x, y1, text)
            text.typeface = Typeface.DEFAULT
            text.textSize = small
            text.color = Color.rgb(222, 245, 230)
            c.drawText(gkLine, x, r.top + r.height() * 0.84f, text)
        } else {
            val base = r.centerY() + big * 0.36f
            var xx = x
            if (gkDist.isNotEmpty()) {
                text.typeface = BOLD
                text.textSize = big
                c.drawText(gkDist, xx, base, text)
                xx += text.measureText(gkDist) + 6f
            }
            text.typeface = Typeface.DEFAULT
            text.textSize = small
            text.color = Color.rgb(222, 245, 230)
            c.drawText(gkLine, xx, base, text)
        }
        text.typeface = Typeface.DEFAULT
    }

    /** 白い矢印（Guidance.ARROW_*）を box の中に描く */
    private fun drawArrow(c: Canvas, box: RectF, kind: Int) {
        val s = min(box.width(), box.height())
        val l = box.centerX() - s / 2f
        val t = box.centerY() - s / 2f
        fun X(f: Float) = l + s * f
        fun Y(f: Float) = t + s * f
        shape.color = Color.WHITE
        shape.style = Paint.Style.STROKE
        shape.strokeWidth = s * 0.15f
        shape.strokeCap = Paint.Cap.ROUND
        shape.strokeJoin = Paint.Join.ROUND
        val p = guidePath
        p.reset()
        // 矢印の先（三角）は塗りで描く
        var hx = 0f; var hy = 0f; var dx = 0f; var dy = -1f
        when (kind) {
            Guidance.ARROW_RIGHT, Guidance.ARROW_LEFT -> {
                val sg = if (kind == Guidance.ARROW_RIGHT) 1f else -1f
                p.moveTo(X(0.5f - 0.18f * sg), Y(0.92f))
                p.lineTo(X(0.5f - 0.18f * sg), Y(0.42f))
                p.lineTo(X(0.5f + 0.2f * sg), Y(0.42f))
                hx = X(0.5f + 0.38f * sg); hy = Y(0.42f); dx = sg; dy = 0f
            }
            Guidance.ARROW_SLIGHT_RIGHT, Guidance.ARROW_SLIGHT_LEFT -> {
                val sg = if (kind == Guidance.ARROW_SLIGHT_RIGHT) 1f else -1f
                p.moveTo(X(0.5f - 0.12f * sg), Y(0.92f))
                p.lineTo(X(0.5f - 0.12f * sg), Y(0.56f))
                p.lineTo(X(0.5f + 0.16f * sg), Y(0.28f))
                hx = X(0.5f + 0.3f * sg); hy = Y(0.14f); dx = 0.707f * sg; dy = -0.707f
            }
            Guidance.ARROW_UTURN -> {
                // 日本の U ターン（右回り）: 左を上がって右へ回り、右を下がる
                p.moveTo(X(0.3f), Y(0.92f))
                p.lineTo(X(0.3f), Y(0.4f))
                p.quadTo(X(0.3f), Y(0.12f), X(0.52f), Y(0.12f))
                p.quadTo(X(0.74f), Y(0.12f), X(0.74f), Y(0.4f))
                p.lineTo(X(0.74f), Y(0.52f))
                hx = X(0.74f); hy = Y(0.72f); dx = 0f; dy = 1f
            }
            Guidance.ARROW_DEST -> {
                // 目的地: 旗
                p.moveTo(X(0.32f), Y(0.92f))
                p.lineTo(X(0.32f), Y(0.1f))
                c.drawPath(p, shape)
                shape.style = Paint.Style.FILL
                p.reset()
                p.moveTo(X(0.32f), Y(0.1f))
                p.lineTo(X(0.82f), Y(0.26f))
                p.lineTo(X(0.32f), Y(0.44f))
                p.close()
                c.drawPath(p, shape)
                shape.style = Paint.Style.FILL
                return
            }
            else -> {
                // 直進（方向が分からないときも）
                p.moveTo(X(0.5f), Y(0.92f))
                p.lineTo(X(0.5f), Y(0.36f))
                hx = X(0.5f); hy = Y(0.1f); dx = 0f; dy = -1f
            }
        }
        c.drawPath(p, shape)
        // 先の三角
        shape.style = Paint.Style.FILL
        val hl = s * 0.3f
        val hw = s * 0.24f
        val bx = hx - dx * hl
        val by = hy - dy * hl
        p.reset()
        p.moveTo(hx, hy)
        p.lineTo(bx - dy * hw, by + dx * hw)
        p.lineTo(bx + dy * hw, by - dx * hw)
        p.close()
        c.drawPath(p, shape)
    }

    fun onTap(x: Float, y: Float): Boolean {
        val vx = (x - ox) / scale
        val vy = (y - oy) / scale
        when {
            guideShown && guideRect.contains(vx, vy) -> onGuideTap?.invoke()
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
     * バーの高さを目標（解析の値）へ動かす。上がるのは速く（[RISE_TAU]）、下がるのは一定の速さ（[FALL_PER_SEC]）。
     * v2.9 はばねでなめらかにしたが、遅くてほとんど動いて見えなかったので、v2.10 で上がり・下がりを別にした
     */
    private fun stepLevel(i: Int, dt: Float) {
        if (dt <= 0f) return
        val tgt = target[i]
        val d = dt.coerceAtMost(0.1f)
        level[i] = if (tgt > level[i]) {
            level[i] + (tgt - level[i]) * (1f - kotlin.math.exp(-d / RISE_TAU))
        } else {
            max(tgt, level[i] - FALL_PER_SEC * d)
        }
    }

    /**
     * スペアナ: 帯ごとの高さ・ピークの動き（経過時間で計算）を求めて、設定の表示の種類で描く（[SpectrumPainter]）。
     */
    private fun drawSpectrum(c: Canvas, r: RectF) {
        val st = SpectrumEngine.status
        if (st != SpectrumEngine.Status.RUNNING) target.fill(0f)

        // 経過時間に合わせた動き（ピークは 60fps で 1 フレームあたり 0.01 と同じ速さ）
        val nowMs = SystemClock.uptimeMillis()
        val f60 = if (lastSpecMs == 0L) 1f else ((nowMs - lastSpecMs) / (1000f / 60f)).coerceIn(0f, 6f)
        lastSpecMs = nowMs
        val dtSec = f60 / 60f
        val peakFall = 0.01f * f60
        val style = SpectrumStyle.visual
        // ピークを止めておく時間（レトロは長め。昔の表示管のように少し止まってから落ちる）
        val holdMs = if (style == SpectrumStyle.VISUAL_RETRO) 650L else 220L

        val floor = lay.noiseFloor
        // バーの高さ（設定の「スペアナの高さ」で伸びを変える）
        val gain = lay.gain * SpectrumStyle.gainScale
        for (i in 0 until n) {
            stepLevel(i, dtSec)
            // 小さな値は切り捨てて(下の段のちらつき防止)、残りを伸ばして高さを使い切る
            val vis = (((level[i] - floor) / (1f - floor)) * gain).coerceIn(0f, 1f)
            visArr[i] = vis
            if (vis > peak[i]) {
                peak[i] = vis
                peakHoldUntil[i] = nowMs + holdMs
            } else if (nowMs >= peakHoldUntil[i]) {
                peak[i] = max(0f, peak[i] - peakFall)
            }
        }

        // 描く区画（v2.6 から、どの画面の配置でもジャケットとは重ならない）
        specRect.set(r)
        // メーターは、高さに伸ばして 1 で切る前の強さ（level）で振る（切ると強弱が消えて、針が端に張り付くため）
        painter.draw(c, specRect, style, visArr, peak, scale, lay.segUnit, f60 / 60f, raw = level)

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

    /** 画面の座標 (x, y) がスペアナの区画の中か（長押しで表示の種類を変えるため） */
    fun isSpectrumAt(x: Float, y: Float): Boolean {
        if (scale <= 0f) return false
        val vx = (x - ox) / scale
        val vy = (y - oy) / scale
        val r = if (NowPlaying.hasInfo) lay.spec else lay.specOnly
        if (!r.contains(vx, vy)) return false
        if (guideShown && guideRect.contains(vx, vy)) return false
        // ジャケットのタップ（音楽アプリを開く）と重なる場所は除く
        return !(NowPlaying.hasInfo && lay.art.contains(vx, vy))
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
        c.drawText(posText, lay.timeMargin, lay.timeY, text)
        text.textAlign = Paint.Align.RIGHT
        c.drawText(remText, lay.vw - lay.timeMargin, lay.timeY, text)
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

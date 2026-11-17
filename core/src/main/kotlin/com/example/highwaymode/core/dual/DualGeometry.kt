package com.example.highwaymode.core.dual

/** 画面の区画（ピクセル） */
class Box(val l: Float, val t: Float, val r: Float, val b: Float) {
    val w: Float get() = r - l
    val h: Float get() = b - t
    val isEmpty: Boolean get() = r <= l || b <= t
    fun contains(x: Float, y: Float): Boolean = x >= l && x < r && y >= t && y < b
    override fun toString(): String = "[%.0f,%.0f – %.0f,%.0f]".format(l, t, r, b)
}

/** 画面の構成（label は切替ボタンの表示名） */
enum class DualLayout(val label: String) {
    /** Spectrum（音楽）だけ */
    MUSIC("音楽"),
    /** 左に Spectrum、右にハイウェイモード（スマホ縦画面では上下） */
    SPLIT("2画面"),
    /** ハイウェイモードだけ */
    HIGHWAY("ハイウェイ")
}

/**
 * 1 フレーム分の配置。表示しない部分は null。
 * @param stacked スマホ縦画面の 2 画面（上にハイウェイモード、下に音楽）
 */
class DualFrame(
    val layout: DualLayout,
    val music: Box?,
    val highway: Box?,
    val bar: Box?,
    val divider: Box?,
    val stacked: Boolean = false
)

/**
 * 車の画面・スマホ画面の区画割り（描画とタップ判定の両方で使う）。
 *
 * 2 画面（左右）は Spectrum : ハイウェイモード = 4 : 5。どちらも細長くならないよう、
 * Spectrum 側は区画の比率に合わせた 2 画面用の表示（曲名・アルバム名・アーティスト名を大きく）にする。
 *
 * v2.18: 2 画面（左右）の下部バー（音楽・2画面・ハイウェイ）は、ハイウェイモード側（右）の下だけに置き、
 * Spectrum 側は表示範囲の下端まで使う（利用者の指示。バーが Spectrum 側の下まで横いっぱいに伸びていて、音楽の表示が狭かった）。
 * 上下の 2 画面（縦長の画面）と、音楽だけ・ハイウェイだけの表示は、今までどおり下に横いっぱい。
 */
object DualGeometry {

    /** Spectrum の縦レイアウトが余白なく収まる「幅 ÷ 高さ」（PlayerRenderer の縦画面 240×380） */
    const val PORTRAIT_W_PER_H = 240f / 380f

    /** Spectrum の横レイアウトの「高さ ÷ 幅」（PlayerRenderer の横画面 400×214） */
    const val LANDSCAPE_H_PER_W = 214f / 400f

    /** 2 画面の左側（Spectrum）の幅の割合（Spectrum 4 : ハイウェイ 5） */
    const val SPLIT_MUSIC_FRACTION = 4f / 9f

    /** 2 画面の左側の幅の範囲（画面幅に対する割合） */
    const val SPLIT_MIN = 0.36f
    const val SPLIT_MAX = 0.50f

    /** 車の画面の下部バーの高さ（画面の 12.5%、56〜84dp、画面の 22% まで） */
    fun carBarHeight(height: Float, density: Float): Float =
        (height * 0.125f).coerceIn(56f * density, 84f * density).coerceAtMost(height * 0.22f)

    /** スマホの下部バーの高さ */
    fun phoneBarHeight(height: Float, density: Float): Float = (60f * density).coerceAtMost(height * 0.15f)

    /** 区切り線の太さ */
    fun dividerWidth(density: Float): Float = maxOf(1f, 2f * density)

    /**
     * 車の画面。
     * @param left 表示範囲の左端（Android Auto の音楽カードなどが横に並ぶと狭くなる）
     * @param right 表示範囲の右端。範囲がおかしい（幅の 30% 未満・画面外）ときは画面全体を使う
     * @param highwayTop ハイウェイモード側の上端（右上のボタンと重ならないよう、表示範囲の上端を渡す）
     * @param barVisible 下部バーを出す（v2.17 から車の画面ではいつも出す。右上のボタンが出ない車でも操作できるように）
     * @param bottom 表示範囲の下端（Android Auto が画面の下に何かを重ねる車。高さの 60% 未満・画面外なら画面の下端）
     *
     * 縦長の画面（縦型のディスプレイ。幅が高さの 0.9 倍未満）の 2 画面は、スマホの縦画面と同じく上下に分ける（v2.17）。
     * 左右の 2 画面の下部バーは、ハイウェイモード側の下だけ（Spectrum 側は [bottom] まで。v2.18）。
     */
    fun car(
        width: Float, height: Float,
        left: Float, right: Float, highwayTop: Float,
        layout: DualLayout, barVisible: Boolean, density: Float,
        bottom: Float = height
    ): DualFrame {
        val valid = left >= 0f && right <= width && right - left >= width * 0.3f
        val l = if (valid) left else 0f
        val r = if (valid) right else width
        val bot = if (bottom >= height * 0.6f && bottom <= height) bottom else height
        val barH = if (barVisible) carBarHeight(bot, density) else 0f
        val areaB = bot - barH
        if (layout == DualLayout.SPLIT && r - l < areaB * 0.9f) {
            val bar = if (barVisible) Box(l, areaB, r, bot) else null
            // 縦長: 上にハイウェイモード、下に Spectrum（横の表示）
            val w = r - l
            val musicH = (w * LANDSCAPE_H_PER_W).coerceIn(areaB * 0.30f, areaB * 0.45f)
            val div = dividerWidth(density)
            val my = areaB - musicH
            return DualFrame(
                layout,
                music = Box(l, my, r, areaB),
                highway = Box(l, highwayTop.coerceIn(0f, my * 0.25f), r, my - div),
                bar = bar,
                divider = Box(l, my - div, r, my),
                stacked = true
            )
        }
        return arrange(layout, l, r, bot, barH, highwayTop.coerceIn(0f, areaB * 0.25f), density)
    }

    /**
     * スマホの画面（下部バーは常に表示）。
     * 横画面は車と同じ左右の 2 画面（下部バーはハイウェイモード側の下だけ。v2.18）。
     * 縦画面の 2 画面は上にハイウェイモード、下に Spectrum（横レイアウト）、いちばん下に横いっぱいのバー。
     */
    fun phone(width: Float, height: Float, layout: DualLayout, density: Float): DualFrame {
        val barH = phoneBarHeight(height, density)
        val areaB = height - barH
        if (width >= height || layout != DualLayout.SPLIT) return arrange(layout, 0f, width, height, barH, 0f, density)
        val bar = Box(0f, areaB, width, height)
        val musicH = (width * LANDSCAPE_H_PER_W).coerceIn(areaB * 0.26f, areaB * 0.42f)
        val div = dividerWidth(density)
        val my = areaB - musicH
        return DualFrame(
            layout,
            music = Box(0f, my, width, areaB),
            highway = Box(0f, 0f, width, my - div),
            bar = bar,
            divider = Box(0f, my - div, width, my),
            stacked = true
        )
    }

    /**
     * 左右の配置（車・スマホ横画面）。
     * @param bottom 表示範囲の下端（下部バーを含む）
     * @param barH 下部バーの高さ（0 ならバーなし）
     */
    private fun arrange(layout: DualLayout, l: Float, r: Float, bottom: Float, barH: Float, hwTop: Float, density: Float): DualFrame {
        val areaB = bottom - barH
        return when (layout) {
            DualLayout.MUSIC -> DualFrame(layout, Box(l, 0f, r, areaB), null, if (barH > 0f) Box(l, areaB, r, bottom) else null, null)
            DualLayout.HIGHWAY -> DualFrame(layout, null, Box(l, hwTop, r, areaB), if (barH > 0f) Box(l, areaB, r, bottom) else null, null)
            DualLayout.SPLIT -> {
                val w = r - l
                val musicW = (w * SPLIT_MUSIC_FRACTION).coerceIn(w * SPLIT_MIN, w * SPLIT_MAX)
                val div = dividerWidth(density)
                val mx = l + musicW
                // 下部バーはハイウェイモード側の下だけ。Spectrum 側（と区切り線）は下端まで（v2.18）
                DualFrame(
                    layout,
                    music = Box(l, 0f, mx, bottom),
                    highway = Box(mx + div, hwTop, r, areaB),
                    bar = if (barH > 0f) Box(mx + div, areaB, r, bottom) else null,
                    divider = Box(mx, 0f, mx + div, bottom)
                )
            }
        }
    }
}

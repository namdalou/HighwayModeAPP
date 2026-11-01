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
 * 2 画面の左側の幅は、Spectrum の 2 画面用の縦レイアウト（文字・ボタンを大きくしたもの。幅:高さ = 0.70）が
 * 余白なく収まる幅にする（画面幅の 22〜42% の範囲）。横長の画面ほど右側のハイウェイモードが広くなる。
 */
object DualGeometry {

    /** Spectrum の縦レイアウトが余白なく収まる「幅 ÷ 高さ」（PlayerRenderer の縦画面 240×380） */
    const val PORTRAIT_W_PER_H = 240f / 380f

    /** Spectrum の横レイアウトの「高さ ÷ 幅」（PlayerRenderer の横画面 400×214） */
    const val LANDSCAPE_H_PER_W = 214f / 400f

    /** 2 画面の左側（Spectrum）の「幅 ÷ 高さ」（運転中に読みやすい大きい表示） */
    const val SPLIT_MUSIC_W_PER_H = 0.70f

    /** 2 画面の左側の幅の範囲（画面幅に対する割合） */
    const val SPLIT_MIN = 0.22f
    const val SPLIT_MAX = 0.42f

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
     * @param barVisible 下部バーを出す（対象エリア内、または手動で音楽以外を選んだとき）
     */
    fun car(
        width: Float, height: Float,
        left: Float, right: Float, highwayTop: Float,
        layout: DualLayout, barVisible: Boolean, density: Float
    ): DualFrame {
        val valid = left >= 0f && right <= width && right - left >= width * 0.3f
        val l = if (valid) left else 0f
        val r = if (valid) right else width
        val barH = if (barVisible) carBarHeight(height, density) else 0f
        val areaB = height - barH
        val bar = if (barVisible) Box(l, areaB, r, height) else null
        return arrange(layout, l, r, areaB, highwayTop.coerceIn(0f, areaB * 0.25f), bar, density)
    }

    /**
     * スマホの画面（下部バーは常に表示）。
     * 横画面は車と同じ左右の 2 画面。縦画面の 2 画面は上にハイウェイモード、下に Spectrum（横レイアウト）。
     */
    fun phone(width: Float, height: Float, layout: DualLayout, density: Float): DualFrame {
        val barH = phoneBarHeight(height, density)
        val areaB = height - barH
        val bar = Box(0f, areaB, width, height)
        if (width >= height || layout != DualLayout.SPLIT) return arrange(layout, 0f, width, areaB, 0f, bar, density)
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

    /** 左右の配置（車・スマホ横画面） */
    private fun arrange(layout: DualLayout, l: Float, r: Float, areaB: Float, hwTop: Float, bar: Box?, density: Float): DualFrame =
        when (layout) {
            DualLayout.MUSIC -> DualFrame(layout, Box(l, 0f, r, areaB), null, bar, null)
            DualLayout.HIGHWAY -> DualFrame(layout, null, Box(l, hwTop, r, areaB), bar, null)
            DualLayout.SPLIT -> {
                val w = r - l
                val musicW = (SPLIT_MUSIC_W_PER_H * areaB).coerceIn(w * SPLIT_MIN, w * SPLIT_MAX)
                val div = dividerWidth(density)
                val mx = l + musicW
                DualFrame(
                    layout,
                    music = Box(l, 0f, mx, areaB),
                    highway = Box(mx + div, hwTop, r, areaB),
                    bar = bar,
                    divider = Box(mx, 0f, mx + div, areaB)
                )
            }
        }
}

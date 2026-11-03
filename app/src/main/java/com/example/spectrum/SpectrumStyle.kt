package com.example.spectrum

import android.content.Context
import android.graphics.Color

/**
 * スペアナの見た目の設定（バーの色・高さ）。設定画面で変えると、スマホと車の画面の両方にすぐ反映される
 * （描画のたびに設定ファイルを読まないよう、ここに写しておく）。保存先は Spectrum の設定（"spectrum"）。
 */
object SpectrumStyle {

    const val THEME_BLUE = 0
    const val THEME_ART = 1
    const val THEME_GREEN = 2
    const val THEME_ORANGE = 3
    const val THEME_PINK = 4
    const val THEME_WHITE = 5

    /** 色の選択肢（THEME_* の順） */
    val THEME_LABELS = arrayOf("青（標準）", "ジャケットの色に合わせる", "緑", "オレンジ", "ピンク", "白")

    /** 高さ（感度）の選択肢と倍率 */
    val GAIN_LABELS = arrayOf("控えめ", "標準", "高め")
    private val GAINS = floatArrayOf(0.8f, 1.0f, 1.25f)

    private const val PREFS = "spectrum"
    private const val KEY_THEME = "bar_theme"
    private const val KEY_GAIN = "bar_gain"

    /** バーの色（THEME_*） */
    @Volatile
    var theme = THEME_BLUE
        private set

    /** 高さの選択（GAIN_LABELS の番号） */
    @Volatile
    var gainIndex = 1
        private set

    /** バーの高さの倍率 */
    val gainScale: Float get() = GAINS[gainIndex.coerceIn(0, GAINS.size - 1)]

    fun load(ctx: Context) {
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        theme = p.getInt(KEY_THEME, THEME_BLUE).coerceIn(0, THEME_LABELS.size - 1)
        gainIndex = p.getInt(KEY_GAIN, 1).coerceIn(0, GAINS.size - 1)
    }

    fun setTheme(ctx: Context, t: Int) {
        theme = t.coerceIn(0, THEME_LABELS.size - 1)
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_THEME, theme).apply()
    }

    fun setGain(ctx: Context, i: Int) {
        gainIndex = i.coerceIn(0, GAINS.size - 1)
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_GAIN, gainIndex).apply()
    }

    /** 決まった色のテーマの、いちばん下の段の色 */
    fun bottomColor(t: Int): Int = when (t) {
        THEME_GREEN -> Color.rgb(0, 120, 64)
        THEME_ORANGE -> Color.rgb(196, 70, 0)
        THEME_PINK -> Color.rgb(150, 30, 130)
        THEME_WHITE -> Color.rgb(110, 122, 134)
        else -> Color.rgb(0, 90, 200)       // 青（従来の色）
    }

    /** 決まった色のテーマの、いちばん上の段の色 */
    fun topColor(t: Int): Int = when (t) {
        THEME_GREEN -> Color.rgb(90, 232, 128)
        THEME_ORANGE -> Color.rgb(255, 196, 64)
        THEME_PINK -> Color.rgb(255, 128, 204)
        THEME_WHITE -> Color.rgb(240, 244, 248)
        else -> Color.rgb(40, 190, 245)
    }
}

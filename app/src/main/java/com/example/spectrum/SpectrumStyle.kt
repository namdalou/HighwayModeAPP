package com.example.spectrum

import android.content.Context
import android.graphics.Color

/**
 * スペアナの見た目の設定（表示の種類・バーの色・高さ）。設定画面で変えると、スマホと車の画面の両方にすぐ反映される
 * （描画のたびに設定ファイルを読まないよう、ここに写しておく）。保存先は Spectrum の設定（"spectrum"）。
 *
 * 色は「下の色 → 上の色」のグラデーション。決まった色も、ジャケットの色・カラーコードで指定した色も、
 * 標準の青と同じように、下を少し濃く・上を少し明るく（色合いも少しずらして）描く（[gradientFor]）。
 */
object SpectrumStyle {

    // ---- 色（保存する番号。v2.4 の 0〜5 は変えない）
    const val THEME_BLUE = 0
    const val THEME_ART = 1
    const val THEME_GREEN = 2
    const val THEME_ORANGE = 3
    const val THEME_PINK = 4
    const val THEME_WHITE = 5
    const val THEME_CYAN = 6
    const val THEME_LIME = 7
    const val THEME_YELLOW = 8
    const val THEME_RED = 9
    const val THEME_PURPLE = 10
    const val THEME_TEAL = 11
    const val THEME_GOLD = 12
    const val THEME_RAINBOW = 13
    const val THEME_CUSTOM = 14

    private const val THEME_COUNT = 15

    /** 色の名前（番号順。v2.14 で「カラーコード」→「好きな色（カラーコード）」「虹色」→「虹色（低音→高音）」） */
    val THEME_LABELS = arrayOf(
        "青（標準）", "ジャケットの色", "緑", "オレンジ", "ピンク", "白", "水色", "黄緑", "黄色", "赤", "紫",
        "青緑", "ゴールド", "虹色（低音→高音）", "好きな色（カラーコード）"
    )

    /** 選ぶ画面に並べる順 */
    val PICKER_ORDER = intArrayOf(
        THEME_ART, THEME_BLUE, THEME_CYAN, THEME_TEAL, THEME_GREEN, THEME_LIME, THEME_YELLOW, THEME_GOLD,
        THEME_ORANGE, THEME_RED, THEME_PINK, THEME_PURPLE, THEME_WHITE, THEME_RAINBOW, THEME_CUSTOM
    )

    // ---- 表示の種類（保存する番号）
    const val VISUAL_LED = 0
    const val VISUAL_BARS = 1
    const val VISUAL_MIRROR = 2
    const val VISUAL_LINE = 3
    const val VISUAL_DOTS = 4
    const val VISUAL_RETRO = 5
    const val VISUAL_METER = 6
    const val VISUAL_METER_DARK = 7
    /** v2.7: 昔のカーオーディオ（9 帯 × 左右・奥へすぼまる格子） */
    const val VISUAL_CA_STEREO = 8
    /** v2.7: 昔のカーオーディオのグラフィックイコライザー（蛍光表示管の 15 本） */
    const val VISUAL_CA_EQ = 9

    /** カーオーディオ（本物の表示の再現。[CarAudioPainter] で描く） */
    fun isCarAudio(v: Int): Boolean = v == VISUAL_RETRO || v == VISUAL_CA_STEREO || v == VISUAL_CA_EQ

    /**
     * 表示の種類の名前（番号順）。v2.14 で、見た目がそのまま伝わる名前に見直した
     * （「ミラー」→「上下対称バー」、「ライン（波形）」→「曲線」、カーオーディオは色・本数を、メーターは文字盤を名前に。
     * ステレオは v2.9 で左右をまとめたので「ワイド」に）
     */
    val VISUAL_LABELS = arrayOf(
        "LED バー（標準）", "なめらかなバー", "上下対称バー", "曲線（ライン）", "丸い LED（ドット）",
        "カーオーディオ・ブルー（細い 20 本）", "アナログメーター（明るい文字盤）", "アナログメーター（暗い文字盤）",
        "カーオーディオ・ワイド（太い 9 本）", "カーオーディオ・イコライザー（15 本）"
    )

    /** 選ぶ画面・長押しで切り替える順（カーオーディオを LED の次にまとめる） */
    val VISUAL_ORDER = intArrayOf(
        VISUAL_LED, VISUAL_RETRO, VISUAL_CA_STEREO, VISUAL_CA_EQ, VISUAL_BARS, VISUAL_MIRROR, VISUAL_LINE, VISUAL_DOTS,
        VISUAL_METER, VISUAL_METER_DARK
    )

    /** 長押しで次の表示の種類へ */
    fun nextVisual(v: Int): Int {
        val i = VISUAL_ORDER.indexOf(v)
        return VISUAL_ORDER[(if (i < 0) 0 else i + 1) % VISUAL_ORDER.size]
    }

    /** 表示の種類の説明（番号順） */
    val VISUAL_NOTES = arrayOf(
        "段に分かれた LED のバー。いちばん見やすい、これまでの表示",
        "光沢のあるなめらかなバー。白いピークと、下にうっすら映り込み",
        "中央から上と下へ同時に伸びるバー",
        "低音から高音までの山を、光る 1 本の線で描く",
        "丸い LED を並べた表示",
        "昔の 2DIN デッキの表示。奥へすぼまる 20 本の青いバー（細い段）。白いピークは少し浮いてから落ちる",
        "照明で光る文字盤に黒い針の VU メーター（低音・高音）。照明の色・メーターの数を選べます",
        "黒い文字盤に光る目盛りと針の VU メーター。夜の車内向け。照明の色・メーターの数を選べます",
        "昔の 2DIN デッキの表示。奥へすぼまる太い 9 本のバーに、二重線の水色の段。青いピークは止まってから落ちる",
        "昔のグラフィックイコライザーの蛍光管。太い 15 本（下が白・上が水色）が素早く動く"
    )

    /** 表示の種類の短い名前（車の画面の一覧など、狭いところ用） */
    val VISUAL_SHORT = arrayOf(
        "LED バー（標準）", "なめらかなバー", "上下対称バー", "曲線", "丸い LED",
        "ブルー（細い 20 本）", "明るい文字盤", "暗い文字盤", "ワイド（太い 9 本）", "イコライザー（15 本）"
    )

    /** アナログメーターか */
    fun isMeter(v: Int): Boolean = v == VISUAL_METER || v == VISUAL_METER_DARK

    // ---- アナログメーターの設定（v2.14）
    const val LIGHT_STANDARD = 0
    const val LIGHT_WARM = 1
    const val LIGHT_WHITE = 2
    const val LIGHT_BLUE = 3
    const val LIGHT_GREEN = 4
    const val LIGHT_THEME = 5

    /** 照明の色（番号順）。標準 = 明るい文字盤は電球色、暗い文字盤は青 */
    val METER_LIGHT_LABELS = arrayOf("標準", "電球色", "白", "青", "緑", "スペアナの色に合わせる")
    /** 照明の色の選択肢（設定画面の一覧用の説明つき） */
    val METER_LIGHT_CHOICES = arrayOf(
        "標準（明るい文字盤は電球色・暗い文字盤は青）", "電球色（暖かい色）", "白", "青", "緑", "スペアナの色に合わせる"
    )
    /** メーターの数 */
    val METER_COUNT_LABELS = arrayOf("2 つ（低音・高音）", "1 つ（全体を大きく）")

    /** 高さ（感度）の選択肢と倍率 */
    val GAIN_LABELS = arrayOf("控えめ", "標準", "高め")
    private val GAINS = floatArrayOf(0.8f, 1.0f, 1.25f)

    private const val PREFS = "spectrum"
    private const val KEY_THEME = "bar_theme"
    private const val KEY_GAIN = "bar_gain"
    private const val KEY_CUSTOM = "bar_custom_color"
    private const val KEY_VISUAL = "bar_visual"
    private const val KEY_METER_LIGHT = "meter_light"
    private const val KEY_METER_SINGLE = "meter_single"
    private const val KEY_METER_PEAK = "meter_peak"

    /** バーの色（THEME_*） */
    @Volatile
    var theme = THEME_BLUE
        private set

    /** カラーコードで指定した色（THEME_CUSTOM のとき使う） */
    @Volatile
    var customColor = Color.rgb(0, 160, 255)
        private set

    /** 表示の種類（VISUAL_*） */
    @Volatile
    var visual = VISUAL_LED
        private set

    /** 高さの選択（GAIN_LABELS の番号） */
    @Volatile
    var gainIndex = 1
        private set

    /** アナログメーターの照明の色（LIGHT_*） */
    @Volatile
    var meterLight = LIGHT_STANDARD
        private set

    /** アナログメーターを 1 つだけ（全体を大きく）にする */
    @Volatile
    var meterSingle = false
        private set

    /** アナログメーターのピークのランプ（強い音で赤く光る） */
    @Volatile
    var meterPeak = true
        private set

    /** 設定が変わるたびに増える（描画側が色・模様を作り直すかを判断する） */
    @Volatile
    var version = 0
        private set

    /** バーの高さの倍率 */
    val gainScale: Float get() = GAINS[gainIndex.coerceIn(0, GAINS.size - 1)]

    fun load(ctx: Context) {
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        theme = p.getInt(KEY_THEME, THEME_BLUE).coerceIn(0, THEME_COUNT - 1)
        gainIndex = p.getInt(KEY_GAIN, 1).coerceIn(0, GAINS.size - 1)
        customColor = p.getInt(KEY_CUSTOM, Color.rgb(0, 160, 255)) or 0xFF000000.toInt()
        visual = p.getInt(KEY_VISUAL, VISUAL_LED).coerceIn(0, VISUAL_LABELS.size - 1)
        meterLight = p.getInt(KEY_METER_LIGHT, LIGHT_STANDARD).coerceIn(0, METER_LIGHT_LABELS.size - 1)
        meterSingle = p.getBoolean(KEY_METER_SINGLE, false)
        meterPeak = p.getBoolean(KEY_METER_PEAK, true)
        version++
    }

    fun setMeterLight(ctx: Context, v: Int) {
        meterLight = v.coerceIn(0, METER_LIGHT_LABELS.size - 1)
        save(ctx) { putInt(KEY_METER_LIGHT, meterLight) }
    }

    fun setMeterSingle(ctx: Context, single: Boolean) {
        meterSingle = single
        save(ctx) { putBoolean(KEY_METER_SINGLE, single) }
    }

    fun setMeterPeak(ctx: Context, on: Boolean) {
        meterPeak = on
        save(ctx) { putBoolean(KEY_METER_PEAK, on) }
    }

    fun setTheme(ctx: Context, t: Int) {
        theme = t.coerceIn(0, THEME_COUNT - 1)
        save(ctx) { putInt(KEY_THEME, theme) }
    }

    /** カラーコードで指定した色にする */
    fun setCustom(ctx: Context, color: Int) {
        customColor = color or 0xFF000000.toInt()
        theme = THEME_CUSTOM
        save(ctx) {
            putInt(KEY_CUSTOM, customColor)
            putInt(KEY_THEME, theme)
        }
    }

    fun setVisual(ctx: Context, v: Int) {
        visual = v.coerceIn(0, VISUAL_LABELS.size - 1)
        save(ctx) { putInt(KEY_VISUAL, visual) }
    }

    fun setGain(ctx: Context, i: Int) {
        gainIndex = i.coerceIn(0, GAINS.size - 1)
        save(ctx) { putInt(KEY_GAIN, gainIndex) }
    }

    private inline fun save(ctx: Context, block: android.content.SharedPreferences.Editor.() -> Unit) {
        val e = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        e.block()
        e.apply()
        version++
    }

    /** 色の名前（カラーコードのときはコードも） */
    fun themeLabel(t: Int = theme): String =
        if (t == THEME_CUSTOM) "好きな色（${colorCode(customColor)}）" else THEME_LABELS.getOrElse(t) { THEME_LABELS[0] }

    // ================================================================== 色

    /**
     * 決まった色のグラデーション（out[0] = いちばん下, out[1] = いちばん上）。
     * どの色も、標準の青（深い青 → 明るい水色寄りの青）と同じく、下を濃く・上を明るく、色合いを少しずらしてある。
     * ジャケットの色・虹色・カラーコードはここでは決まらない（false を返す）。
     */
    fun fixedColors(t: Int, out: IntArray): Boolean {
        val pair = when (t) {
            THEME_BLUE -> intArrayOf(Color.rgb(0, 90, 200), Color.rgb(40, 190, 245))
            THEME_GREEN -> intArrayOf(Color.rgb(0, 120, 64), Color.rgb(90, 232, 128))
            THEME_ORANGE -> intArrayOf(Color.rgb(196, 70, 0), Color.rgb(255, 196, 64))
            THEME_PINK -> intArrayOf(Color.rgb(150, 30, 130), Color.rgb(255, 128, 204))
            THEME_WHITE -> intArrayOf(Color.rgb(110, 122, 134), Color.rgb(240, 244, 248))
            THEME_CYAN -> intArrayOf(Color.rgb(0, 128, 186), Color.rgb(112, 236, 255))
            THEME_LIME -> intArrayOf(Color.rgb(62, 138, 0), Color.rgb(200, 255, 92))
            THEME_YELLOW -> intArrayOf(Color.rgb(176, 124, 0), Color.rgb(255, 240, 112))
            THEME_RED -> intArrayOf(Color.rgb(166, 0, 22), Color.rgb(255, 106, 82))
            THEME_PURPLE -> intArrayOf(Color.rgb(76, 26, 170), Color.rgb(196, 142, 255))
            THEME_TEAL -> intArrayOf(Color.rgb(0, 120, 112), Color.rgb(92, 244, 214))
            THEME_GOLD -> intArrayOf(Color.rgb(140, 96, 12), Color.rgb(255, 216, 122))
            else -> return false
        }
        out[0] = pair[0]
        out[1] = pair[1]
        return true
    }

    /**
     * 1 つの色から、バーのグラデーションを作る（ジャケットの色・カラーコード用。out[0] = 下, out[1] = 上）。
     * 指定の色が中ほどに来るように、下は少し濃く鮮やかに、上は明るく（色合いを明るい黄色の側へ少しずらす）。
     * 暗い色・淡い色も、暗い背景で見えるように明るさを補う。白・灰色・黒は銀色のグラデーション（わずかな色味は残す）。
     */
    fun gradientFor(color: Int, out: IntArray) {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        val h = hsv[0]
        val s = hsv[1]
        val v = hsv[2]
        if (s < 0.12f) {
            // 無彩色: 銀色（少しだけ元の色味）
            val tint = s * 0.8f
            out[0] = Color.HSVToColor(floatArrayOf(h, (tint * 1.2f).coerceAtMost(0.16f), (0.44f + 0.12f * v).coerceIn(0.44f, 0.56f)))
            out[1] = Color.HSVToColor(floatArrayOf(h, (tint * 0.6f).coerceAtMost(0.08f), 0.97f))
            return
        }
        // 明るい黄色（60°）の側へ、上の色は最大 16°、下の色は反対へ最大 6° ずらす（標準の青と同じ向き）
        var d = 60f - h
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        val shift = (d * 0.25f).coerceIn(-16f, 16f)
        val hTop = norm(h + shift)
        val hBottom = norm(h - shift * 0.35f)
        val pastel = s < 0.42f
        val sBottom = if (pastel) (s * 1.35f).coerceIn(0.30f, 0.62f) else (s * 1.08f).coerceIn(0.62f, 1.0f)
        val sTop = if (pastel) (s * 0.80f).coerceIn(0.16f, 0.42f) else (s * 0.78f).coerceIn(0.36f, 0.86f)
        // 明るさ: 下は指定の色より少し暗く（ただし暗すぎない）、上は明るく
        val vBottom = (v * 0.74f).coerceIn(0.46f, 0.80f)
        val vTop = (v * 1.25f).coerceIn(0.90f, 1.0f)
        out[0] = Color.HSVToColor(floatArrayOf(hBottom, sBottom, vBottom))
        out[1] = Color.HSVToColor(floatArrayOf(hTop, sTop, vTop))
    }

    /**
     * 色の設定から、帯ごとの色（下・上）を作る（PlayerRenderer と設定画面の見本で共用）。
     * @param artColor ジャケットから選んだ色（[ArtPalette]。0 = ジャケットなし → 標準の青）
     */
    fun colorsFor(t: Int, artColor: Int, bottoms: IntArray, tops: IntArray) {
        val n = bottoms.size
        val pair = IntArray(2)
        if (t == THEME_RAINBOW) {
            for (i in 0 until n) {
                rainbowFor(i, n, pair)
                bottoms[i] = pair[0]
                tops[i] = pair[1]
            }
            return
        }
        when {
            t == THEME_ART && artColor != 0 -> gradientFor(artColor, pair)
            t == THEME_CUSTOM -> gradientFor(customColor, pair)
            fixedColors(t, pair) -> {}
            else -> fixedColors(THEME_BLUE, pair)
        }
        bottoms.fill(pair[0])
        tops.fill(pair[1])
    }

    /** 虹色の i 番目（低音の赤 → 高音の紫）のグラデーション */
    fun rainbowFor(i: Int, n: Int, out: IntArray) {
        val hue = if (n <= 1) 0f else 285f * i / (n - 1)
        gradientFor(Color.HSVToColor(floatArrayOf(hue, 0.95f, 0.95f)), out)
    }

    private fun norm(h: Float): Float {
        var x = h % 360f
        if (x < 0f) x += 360f
        return x
    }

    /** "#RRGGBB" */
    fun colorCode(c: Int): String = String.format("#%06X", c and 0xFFFFFF)

    /**
     * カラーコードを読む（"#FF8800" "FF8800" "#F80" "0xFF8800" など）。読めなければ null
     */
    fun parseColor(s: String): Int? {
        var t = s.trim().replace("＃", "#").uppercase()
        t = t.removePrefix("#").removePrefix("0X")
        if (t.length == 3) t = t.map { "$it$it" }.joinToString("")
        if (t.length == 8) t = t.substring(2) // AARRGGBB → RRGGBB
        if (t.length != 6 || t.any { it !in "0123456789ABCDEF" }) return null
        return (t.toInt(16) or 0xFF000000.toInt())
    }
}

package com.example.highwaymode.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.spectrum.ArtPalette
import com.example.spectrum.NowPlaying
import com.example.spectrum.SpectrumEngine
import com.example.spectrum.SpectrumPainter
import com.example.spectrum.SpectrumStyle
import kotlin.concurrent.thread

/**
 * スペアナの見本（設定画面）。いまの表示の種類と色で、音楽が流れているような動きを描く。
 * 見えている間だけ動かす（約 30fps）。「ジャケットの色」は、再生中の曲のジャケットから色を選ぶ（無ければ見本の色）。
 */
class SpectrumPreviewView(context: Context) : View(context) {

    private val painter = SpectrumPainter(SpectrumEngine.BANDS)
    private val bottoms = IntArray(SpectrumEngine.BANDS)
    private val tops = IntArray(SpectrumEngine.BANDS)
    private val rect = RectF()
    private val density = resources.displayMetrics.density
    private val started = SystemClock.uptimeMillis()
    private var last = 0L
    private var shownVersion = -1
    private var shownArt = 0
    private var shownVisual = -1
    private val bgColors = IntArray(2)
    private val bg = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.rgb(14, 42, 58), Color.rgb(4, 12, 20))).apply {
        cornerRadius = 10 * density
    }
    private val tick = Runnable { invalidate() }
    private var artColor = SAMPLE_ART

    init {
        background = bg
        refreshArt()
    }

    /** 色・表示の種類が変わった（設定画面から呼ぶ） */
    fun refresh() {
        shownVersion = -1
        refreshArt()
        invalidate()
    }

    private fun refreshArt() {
        if (SpectrumStyle.theme != SpectrumStyle.THEME_ART) return
        val art = NowPlaying.art
        if (art == null) {
            artColor = SAMPLE_ART
            shownVersion = -1
            return
        }
        thread(name = "preview-art") {
            val c = ArtPalette.dominant(art) ?: SAMPLE_ART
            post {
                artColor = c
                shownVersion = -1
                invalidate()
            }
        }
    }


    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (shownVersion != SpectrumStyle.version || shownArt != artColor) {
            SpectrumStyle.colorsFor(SpectrumStyle.theme, artColor, bottoms, tops)
            painter.setColors(bottoms, tops)
            shownVersion = SpectrumStyle.version
            shownArt = artColor
        }
        // 背景も音楽の画面と同じ色に（カーオーディオ系は紫がかった暗い灰色）
        if (shownVisual != SpectrumStyle.visual) {
            shownVisual = SpectrumStyle.visual
            SpectrumPainter.screenBackground(shownVisual, bgColors)
            bg.colors = intArrayOf(bgColors[0], bgColors[1])
        }
        val now = SystemClock.uptimeMillis()
        val dt = if (last == 0L) 1f / 30f else ((now - last) / 1000f).coerceIn(0f, 0.1f)
        last = now
        val pad = 8f
        // 仮想座標 = dp
        canvas.save()
        canvas.scale(density, density)
        rect.set(pad, pad, width / density - pad, height / density - pad)
        painter.drawDemo(canvas, rect, SpectrumStyle.visual, density, (now - started) / 1000f, dt)
        canvas.restore()
        removeCallbacks(tick)
        if (isShown) postOnAnimationDelayed(tick, 33L)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    companion object {
        /** 曲が無いときの「ジャケットの色」の見本 */
        val SAMPLE_ART = Color.rgb(236, 112, 86)
    }
}

/** スペアナの表示の種類・色を選ぶ画面（設定画面から開く） */
object SpectrumPickers {

    private fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    // ================================================================== 表示の種類

    /** 表示の種類を、見本の絵を見ながら選ぶ */
    fun chooseVisual(a: Activity, onDone: () -> Unit) {
        val list = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dp(8f), a.dp(4f), a.dp(8f), a.dp(4f))
        }
        val bottoms = IntArray(SpectrumEngine.BANDS)
        val tops = IntArray(SpectrumEngine.BANDS)
        SpectrumStyle.colorsFor(SpectrumStyle.theme, PREVIEW_ART, bottoms, tops)
        lateinit var dialog: AlertDialog
        for (v in SpectrumStyle.VISUAL_LABELS.indices) {
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(a.dp(8f), a.dp(8f), a.dp(8f), a.dp(8f))
                isClickable = true
                isFocusable = true
                background = GradientDrawable().apply {
                    cornerRadius = a.dp(10f).toFloat()
                    setColor(if (v == SpectrumStyle.visual) 0xFF1C2C3A.toInt() else Color.TRANSPARENT)
                    if (v == SpectrumStyle.visual) setStroke(a.dp(1.5f), SettingsUi.C_ACCENT)
                }
            }
            val img = ImageView(a).apply {
                setImageBitmap(renderSample(a, v, bottoms, tops, 132f, 56f))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            row.addView(img, LinearLayout.LayoutParams(a.dp(132f), a.dp(56f)))
            val texts = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(a.dp(12f), 0, 0, 0)
            }
            texts.addView(TextView(a).apply {
                text = SpectrumStyle.VISUAL_LABELS[v] + if (v == SpectrumStyle.visual) "　✓" else ""
                textSize = 15f
                setTextColor(SettingsUi.C_TITLE)
                typeface = Typeface.DEFAULT_BOLD
            })
            texts.addView(TextView(a).apply {
                text = SpectrumStyle.VISUAL_NOTES[v]
                textSize = 12f
                setTextColor(SettingsUi.C_SUB)
                setPadding(0, a.dp(2f), 0, 0)
            })
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.setOnClickListener {
                SpectrumStyle.setVisual(a, v)
                dialog.dismiss()
                onDone()
            }
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = a.dp(4f)
            })
        }
        dialog = AlertDialog.Builder(a, SettingsUi.DIALOG_THEME)
            .setTitle("スペアナの表示の種類")
            .setView(ScrollView(a).apply { addView(list) })
            .setNegativeButton("閉じる", null)
            .create()
        dialog.show()
    }

    /** 見本の絵（w×h dp） */
    private fun renderSample(ctx: Context, visual: Int, bottoms: IntArray, tops: IntArray, wDp: Float, hDp: Float): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val bmp = Bitmap.createBitmap((wDp * d).toInt().coerceAtLeast(1), (hDp * d).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cols = IntArray(2)
        SpectrumPainter.screenBackground(visual, cols)
        val bg = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(cols[0], cols[1])).apply {
            cornerRadius = 8 * d
            setBounds(0, 0, bmp.width, bmp.height)
        }
        bg.draw(c)
        val p = SpectrumPainter(SpectrumEngine.BANDS)
        p.setColors(bottoms, tops)
        c.scale(d, d)
        p.drawDemo(c, RectF(5f, 5f, wDp - 5f, hDp - 4f), visual, d, 1.35f, 0f, still = true)
        return bmp
    }

    // ================================================================== 色

    /** 色を、グラデーションの見本を見ながら選ぶ（最後に「カラーコード」） */
    fun chooseColor(a: Activity, onDone: () -> Unit) {
        val columns = if (a.resources.configuration.screenWidthDp >= 600) 5 else 4
        val grid = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dp(10f), a.dp(6f), a.dp(10f), a.dp(6f))
        }
        lateinit var dialog: AlertDialog
        var row: LinearLayout? = null
        val order = SpectrumStyle.PICKER_ORDER
        for ((idx, t) in order.withIndex()) {
            if (idx % columns == 0) {
                row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
                grid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            val tile = swatchTile(a, t, selected = t == SpectrumStyle.theme)
            tile.setOnClickListener {
                if (t == SpectrumStyle.THEME_CUSTOM) {
                    dialog.dismiss()
                    enterColorCode(a, onDone)
                } else {
                    SpectrumStyle.setTheme(a, t)
                    dialog.dismiss()
                    onDone()
                }
            }
            row?.addView(tile, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        // 最後の段の空きを埋める（タイルの幅をそろえる）
        val rest = (columns - order.size % columns) % columns
        repeat(rest) { row?.addView(View(a), LinearLayout.LayoutParams(0, 1, 1f)) }
        grid.addView(TextView(a).apply {
            text = "どの色も、下を少し濃く・上を少し明るくしたグラデーションで表示します。「ジャケットの色」は曲ごとにジャケットの色に変わります。"
            textSize = 12f
            setTextColor(SettingsUi.C_SUB)
            setPadding(a.dp(4f), a.dp(10f), a.dp(4f), 0)
        })
        dialog = AlertDialog.Builder(a, SettingsUi.DIALOG_THEME)
            .setTitle("スペアナの色")
            .setView(ScrollView(a).apply { addView(grid) })
            .setNegativeButton("閉じる", null)
            .create()
        dialog.show()
    }

    /** 色の見本のタイル（グラデーション＋名前） */
    private fun swatchTile(a: Activity, t: Int, selected: Boolean): View {
        val tile = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(a.dp(4f), a.dp(6f), a.dp(4f), a.dp(6f))
            isClickable = true
            isFocusable = true
        }
        val sw = View(a)
        sw.background = swatchDrawable(a, t, selected)
        tile.addView(sw, LinearLayout.LayoutParams(a.dp(52f), a.dp(52f)))
        tile.addView(TextView(a).apply {
            text = when (t) {
                SpectrumStyle.THEME_CUSTOM -> "カラー\nコード"
                SpectrumStyle.THEME_ART -> "ジャケット\nの色"
                else -> SpectrumStyle.THEME_LABELS[t].replace("（標準）", "\n（標準）")
            }
            textSize = 11.5f
            gravity = Gravity.CENTER
            setTextColor(if (selected) SettingsUi.C_ACCENT else SettingsUi.C_TITLE)
            setPadding(0, a.dp(4f), 0, 0)
        })
        return tile
    }

    /** グラデーションの見本の絵（下 → 上） */
    fun swatchDrawable(ctx: Context, t: Int, selected: Boolean, custom: Int = SpectrumStyle.customColor): GradientDrawable {
        val bottoms = IntArray(SpectrumEngine.BANDS)
        val tops = IntArray(SpectrumEngine.BANDS)
        val g = when (t) {
            SpectrumStyle.THEME_RAINBOW -> {
                val cols = IntArray(7) { Color.HSVToColor(floatArrayOf(285f * it / 6f, 0.85f, 1f)) }
                GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, cols)
            }
            SpectrumStyle.THEME_ART -> {
                // ジャケットの色は、いくつかの色を斜めに並べた見本
                GradientDrawable(
                    GradientDrawable.Orientation.BL_TR,
                    intArrayOf(Color.rgb(236, 112, 86), Color.rgb(250, 200, 80), Color.rgb(90, 200, 160), Color.rgb(90, 140, 240))
                )
            }
            SpectrumStyle.THEME_CUSTOM -> {
                val pair = IntArray(2)
                SpectrumStyle.gradientFor(custom, pair)
                GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(pair[0], custom, pair[1]))
            }
            else -> {
                SpectrumStyle.colorsFor(t, 0, bottoms, tops)
                GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(bottoms[0], tops[0]))
            }
        }
        g.cornerRadius = ctx.dp(10f).toFloat()
        if (selected) g.setStroke(ctx.dp(3f), Color.WHITE) else g.setStroke(ctx.dp(1f), 0x40FFFFFF)
        return g
    }

    /** カラーコードを入れて色を決める（入れながら見本が変わる） */
    fun enterColorCode(a: Activity, onDone: () -> Unit) {
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dp(20f), a.dp(8f), a.dp(20f), a.dp(4f))
        }
        val preview = View(a)
        preview.background = swatchDrawable(a, SpectrumStyle.THEME_CUSTOM, false)
        val previewBox = FrameLayout(a).apply { addView(preview, FrameLayout.LayoutParams(a.dp(220f), a.dp(56f), Gravity.CENTER_HORIZONTAL)) }
        col.addView(previewBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val edit = EditText(a).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(SpectrumStyle.colorCode(SpectrumStyle.customColor))
            hint = "#00A0FF"
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF7D8590.toInt())
            textSize = 18f
            gravity = Gravity.CENTER
            isSingleLine = true
        }
        col.addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = a.dp(10f)
        })
        col.addView(TextView(a).apply {
            text = "#RRGGBB の形で入れてください（例: #FF6600 オレンジ、#00C8FF 水色、#B060FF 紫）。入れた色を中ほどに、下を少し濃く・上を少し明るくしたグラデーションで表示します。"
            textSize = 12f
            setTextColor(SettingsUi.C_SUB)
            setPadding(0, a.dp(8f), 0, 0)
        })
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val c = SpectrumStyle.parseColor(s?.toString().orEmpty()) ?: return
                preview.background = swatchDrawable(a, SpectrumStyle.THEME_CUSTOM, false, custom = c)
            }
        })
        val dialog = AlertDialog.Builder(a, SettingsUi.DIALOG_THEME)
            .setTitle("カラーコードで色を指定")
            .setView(col)
            .setPositiveButton("この色にする", null)
            .setNegativeButton("キャンセル", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val c = SpectrumStyle.parseColor(edit.text.toString())
                if (c == null) {
                    edit.error = "#RRGGBB の形で入れてください"
                } else {
                    SpectrumStyle.setCustom(a, c)
                    dialog.dismiss()
                    onDone()
                }
            }
        }
        dialog.show()
    }

    /** 見本の絵の「ジャケットの色」に使う色 */
    private val PREVIEW_ART = SpectrumPreviewView.SAMPLE_ART
}

package com.example.highwaymode.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import kotlin.math.max

/**
 * 設定画面の部品（上の帯・見出し・まとまり（カード）・行）。スマホの設定画面と開発者向け設定で共用する。
 *
 *  - 項目は内容ごとのまとまり（カード）に分け、まとまりの上に見出し、下に短い説明を置く
 *  - 行は高さ 58dp 以上。題名の下に「いまの値」（水色）と説明（灰色）を出す。右側はスイッチか「›」
 *  - 選ぶ項目は、押すと選択肢を出し、選んだらすぐ反映する（保存ボタンは無い）
 *  - 横に長い画面では、中身の幅を 720dp までにして中央に置く
 */
class SettingsUi(private val a: Activity) {

    companion object {
        val C_BG = 0xFF0A0F14.toInt()
        val C_BAR = 0xFF0F161D.toInt()
        val C_CARD = 0xFF131B23.toInt()
        val C_CARD_EDGE = 0xFF1F2B36.toInt()
        val C_DIVIDER = 0xFF1C2630.toInt()
        val C_TITLE = 0xFFE9EFF5.toInt()
        val C_SUB = 0xFF8C98A5.toInt()
        val C_ACCENT = 0xFF28BEF5.toInt()
        val C_OK = 0xFF4CD07D.toInt()
        val C_WARN = 0xFFFFB300.toInt()
        val C_DANGER = 0xFFFF7A70.toInt()
        const val DIALOG_THEME = android.R.style.Theme_DeviceDefault_Dialog_Alert
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private val density = a.resources.displayMetrics.density

    fun dp(v: Float): Int = (v * density + 0.5f).toInt()

    fun dp(v: Int): Int = dp(v.toFloat())

    /** 1 行。題名・いまの値・説明を、あとから書き換えられる */
    class Row internal constructor(
        /** 区切り線を含む行全体（隠すときに使う） */
        val container: View,
        /** 押せる部分 */
        val body: View,
        private val titleView: TextView,
        private val valueView: TextView,
        private val summaryView: TextView,
        val switch: Switch?
    ) {
        fun title(t: CharSequence) {
            titleView.text = t
        }

        /** いまの値（題名の下に水色で） */
        fun value(v: CharSequence?, color: Int = C_ACCENT) {
            valueView.text = v ?: ""
            valueView.setTextColor(color)
            valueView.visibility = if (v.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

        /** 説明（灰色） */
        fun summary(s: CharSequence?) {
            summaryView.text = s ?: ""
            summaryView.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

        fun enabled(e: Boolean) {
            body.isEnabled = e
            switch?.isEnabled = e
            body.alpha = if (e) 1f else 0.4f
        }

        fun shown(v: Boolean) {
            container.visibility = if (v) View.VISIBLE else View.GONE
        }
    }

    /** よく使う操作のボタン（横に並べる大きなボタン） */
    class Tile(val title: String, val sub: String, val onClick: () -> Unit)

    /** 並べたボタンの文字（あとで書き換える用） */
    class TileView(val title: TextView, val sub: TextView)

    // ================================================================== 画面全体

    /**
     * 画面全体を作る: 上の帯（戻る・題名）と、縦に並べる中身（スクロール）。
     * @return 中身を入れる列
     */
    fun page(title: String, subtitle: String?, onBack: () -> Unit): LinearLayout {
        // 上の帯と同じ色にして、画面の端まで描く端末ではステータスバーの下も帯の色に
        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C_BAR)
        }
        val bar = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(16), dp(6))
            setBackgroundColor(C_BAR)
        }
        bar.addView(TextView(a).apply {
            text = "←"
            textSize = 24f
            setTextColor(C_TITLE)
            gravity = Gravity.CENTER
            contentDescription = "戻る"
            background = selectable(borderless = true)
            isClickable = true
            isFocusable = true
            setOnClickListener { onBack() }
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
        val titles = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), 0, 0, 0)
        }
        titles.addView(TextView(a).apply {
            text = title
            textSize = 20f
            setTextColor(C_TITLE)
            typeface = Typeface.DEFAULT_BOLD
        })
        if (subtitle != null) {
            titles.addView(TextView(a).apply {
                text = subtitle
                textSize = 12f
                setTextColor(C_SUB)
            })
        }
        bar.addView(titles, LinearLayout.LayoutParams(0, WRAP, 1f))
        root.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))

        val content = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(40))
        }
        val holder = MaxWidthFrame(a, dp(720))
        holder.addView(content, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER_HORIZONTAL))
        val scroll = ScrollView(a).apply {
            isFillViewport = true
            setBackgroundColor(C_BG)
        }
        scroll.addView(holder, FrameLayout.LayoutParams(MATCH, WRAP))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        // 画面の端（システムバー・切り欠き）と重ならないように（Android 15 以降は画面の端まで描くため）
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(b.left, b.top, b.right, b.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        a.setContentView(root)
        return content
    }

    /** 中身の幅を [maxW] までにして中央に置く（横に長い画面で行が間延びしないように） */
    private class MaxWidthFrame(ctx: Context, private val maxW: Int) : FrameLayout(ctx) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED && w > maxW) {
                super.onMeasure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.EXACTLY), heightMeasureSpec)
                setMeasuredDimension(w, measuredHeight)
            } else {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }
    }

    // ================================================================== まとまり・行

    /**
     * まとまり（角の丸い枠）を作る。
     * @param title 見出し（null なら見出しなし）
     * @param note まとまりの下に出す短い説明
     * @return 行を入れる枠
     */
    fun section(parent: LinearLayout, title: String?, note: String? = null): LinearLayout {
        if (title != null) {
            parent.addView(TextView(a).apply {
                text = title
                textSize = 14f
                setTextColor(C_ACCENT)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(8), dp(22), dp(8), dp(8))
            })
        } else {
            parent.addView(View(a), LinearLayout.LayoutParams(MATCH, dp(14)))
        }
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(C_CARD)
                cornerRadius = dp(14).toFloat()
                setStroke(max(1, dp(1)), C_CARD_EDGE)
            }
            // 押したときの色が角からはみ出さないように
            clipToOutline = true
        }
        parent.addView(card, LinearLayout.LayoutParams(MATCH, WRAP))
        if (note != null) note(parent, note)
        return card
    }

    /** まとまりの下の短い説明 */
    fun note(parent: LinearLayout, text: String): TextView = TextView(a).apply {
        this.text = text
        textSize = 12f
        setTextColor(C_SUB)
        setLineSpacing(0f, 1.15f)
        setPadding(dp(10), dp(6), dp(10), 0)
        parent.addView(this)
    }

    /** オン・オフの行（行のどこを押しても切り替わる） */
    fun switchRow(card: LinearLayout, title: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit): Row {
        val sw = Switch(a).apply {
            isChecked = checked
            // 行全体で切り替える（押せる範囲を広く）
            isClickable = false
            isFocusable = false
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(C_ACCENT, 0xFFB7C0C9.toInt()))
            trackTintList = ColorStateList(states, intArrayOf(0x8028BEF5.toInt(), 0xFF3A4652.toInt()))
        }
        val row = makeRow(card, title, summary, sw, chevron = false)
        row.body.setOnClickListener {
            sw.isChecked = !sw.isChecked
            onChange(sw.isChecked)
        }
        // 読み上げ（TalkBack）で「オン／オフ」が分かるように
        row.body.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.isCheckable = true
                // Android 16 で int 版が加わり非推奨になったが、古い版も含めてこちらで動く
                @Suppress("DEPRECATION")
                info.isChecked = sw.isChecked
                info.className = Switch::class.java.name
            }
        }
        return row
    }

    /**
     * いくつかの中から 1 つを選ぶ行。いまの選択を題名の下に出し、押すと選択肢を出す。
     * 選んだらすぐ [onSelect] を呼ぶ（保存ボタンは無い）
     */
    fun choiceRow(card: LinearLayout, title: String, labels: Array<String>, current: Int, summary: String? = null, onSelect: (Int) -> Unit): Row {
        val row = makeRow(card, title, summary, null, chevron = true)
        var cur = current.coerceIn(0, labels.size - 1)
        row.value(labels[cur])
        row.body.setOnClickListener {
            choose(title, labels, cur) { which ->
                cur = which
                row.value(labels[which])
                onSelect(which)
            }
        }
        return row
    }

    /** 押すと何かをする行（画面・確認を開く）。right を渡すと「›」の左に小さな見本などを置く */
    fun actionRow(card: LinearLayout, title: String, summary: String?, danger: Boolean = false, right: View? = null, onClick: () -> Unit): Row {
        val row = makeRow(card, title, summary, right, chevron = !danger)
        if (danger) titleColor(row, C_DANGER)
        row.body.setOnClickListener { onClick() }
        return row
    }

    /** 任意の表示（見本など）を 1 行として置く。onClick があれば押せる */
    fun viewRow(card: LinearLayout, view: View, heightDp: Float, onClick: (() -> Unit)? = null): View {
        val wrapper = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        if (card.childCount > 0) {
            wrapper.addView(View(a).apply { setBackgroundColor(C_DIVIDER) }, LinearLayout.LayoutParams(MATCH, max(1, dp(1))))
        }
        wrapper.addView(view, LinearLayout.LayoutParams(MATCH, dp(heightDp)))
        if (onClick != null) {
            wrapper.isClickable = true
            wrapper.isFocusable = true
            wrapper.background = selectable(borderless = false)
            wrapper.setOnClickListener { onClick() }
        }
        card.addView(wrapper, LinearLayout.LayoutParams(MATCH, WRAP))
        return wrapper
    }

    /** 表示だけの行（押せない） */
    fun infoRow(card: LinearLayout, title: String, value: String?): Row {
        val row = makeRow(card, title, null, null, chevron = false)
        row.value(value)
        row.body.isClickable = false
        row.body.isFocusable = false
        row.body.background = null
        return row
    }

    private fun titleColor(row: Row, color: Int) {
        val body = row.body as? LinearLayout ?: return
        val texts = body.getChildAt(0) as? LinearLayout ?: return
        (texts.getChildAt(0) as? TextView)?.setTextColor(color)
    }

    /** よく使う操作: 大きなボタンを横に並べる */
    fun tiles(card: LinearLayout, items: List<Tile>): List<TileView> {
        val rowView = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        val out = ArrayList<TileView>()
        for ((i, t) in items.withIndex()) {
            if (i > 0) rowView.addView(View(a).apply { setBackgroundColor(C_DIVIDER) }, LinearLayout.LayoutParams(max(1, dp(1)), MATCH))
            val cell = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                minimumHeight = dp(76)
                setPadding(dp(6), dp(10), dp(6), dp(10))
                background = selectable(borderless = false)
                isClickable = true
                isFocusable = true
                setOnClickListener { t.onClick() }
            }
            val title = TextView(a).apply {
                text = t.title
                textSize = 15f
                setTextColor(C_TITLE)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }
            val sub = TextView(a).apply {
                text = t.sub
                textSize = 12f
                setTextColor(C_SUB)
                gravity = Gravity.CENTER
                setPadding(0, dp(3), 0, 0)
            }
            cell.addView(title, LinearLayout.LayoutParams(MATCH, WRAP))
            cell.addView(sub, LinearLayout.LayoutParams(MATCH, WRAP))
            rowView.addView(cell, LinearLayout.LayoutParams(0, MATCH, 1f))
            out.add(TileView(title, sub))
        }
        card.addView(rowView, LinearLayout.LayoutParams(MATCH, WRAP))
        return out
    }

    private fun makeRow(card: LinearLayout, title: String, summary: String?, right: View?, chevron: Boolean): Row {
        val wrapper = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        if (card.childCount > 0) {
            wrapper.addView(View(a).apply { setBackgroundColor(C_DIVIDER) }, LinearLayout.LayoutParams(MATCH, max(1, dp(1))).apply {
                marginStart = dp(16)
            })
        }
        val body = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(58)
            setPadding(dp(16), dp(10), dp(12), dp(10))
            background = selectable(borderless = false)
            isClickable = true
            isFocusable = true
        }
        val texts = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val tv = TextView(a).apply {
            text = title
            textSize = 16f
            setTextColor(C_TITLE)
        }
        val vv = TextView(a).apply {
            textSize = 14f
            setTextColor(C_ACCENT)
            setPadding(0, dp(2), 0, 0)
            visibility = View.GONE
        }
        val sv = TextView(a).apply {
            textSize = 12.5f
            setTextColor(C_SUB)
            setLineSpacing(0f, 1.1f)
            setPadding(0, dp(2), 0, 0)
            visibility = View.GONE
        }
        texts.addView(tv)
        texts.addView(vv)
        texts.addView(sv)
        body.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (right != null) {
            body.addView(right, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(12) })
        }
        if (chevron) {
            body.addView(TextView(a).apply {
                text = "›"
                textSize = 24f
                setTextColor(C_SUB)
                setPadding(dp(10), 0, dp(4), 0)
            })
        }
        wrapper.addView(body, LinearLayout.LayoutParams(MATCH, WRAP))
        card.addView(wrapper, LinearLayout.LayoutParams(MATCH, WRAP))
        return Row(wrapper, body, tv, vv, sv, right as? Switch).also { it.summary(summary) }
    }

    /** 押したときの色（端末の標準の波紋） */
    private fun selectable(borderless: Boolean): Drawable? {
        val tv = TypedValue()
        val attr = if (borderless) android.R.attr.selectableItemBackgroundBorderless else android.R.attr.selectableItemBackground
        return if (a.theme.resolveAttribute(attr, tv, true) && tv.resourceId != 0) a.getDrawable(tv.resourceId) else null
    }

    // ================================================================== ダイアログ

    /** 選択肢の一覧（選んだらすぐ閉じる） */
    fun choose(title: String, labels: Array<String>, current: Int, onSelect: (Int) -> Unit) {
        AlertDialog.Builder(a, DIALOG_THEME)
            .setTitle(title)
            .setSingleChoiceItems(labels, current) { d, which ->
                d.dismiss()
                onSelect(which)
            }
            .setNegativeButton("閉じる", null)
            .show()
    }

    /** 確認（はい/キャンセル） */
    fun confirm(title: String, message: String, ok: String, onOk: () -> Unit) {
        AlertDialog.Builder(a, DIALOG_THEME)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(ok) { _, _ -> onOk() }
            .setNegativeButton("キャンセル", null)
            .show()
    }
}

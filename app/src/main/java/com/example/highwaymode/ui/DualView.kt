package com.example.highwaymode.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.example.highwaymode.core.dual.DualFrame
import com.example.highwaymode.core.dual.DualGeometry
import com.example.highwaymode.core.dual.DualLayout
import com.example.highwaymode.core.engine.HighwaySnapshot
import com.example.highwaymode.dual.ModeBarRenderer
import kotlin.math.abs

/**
 * スマホの画面: Spectrum（音楽）・ハイウェイモード・下部の切替バーを、画面構成に合わせて並べる。
 *  - 音楽 / ハイウェイ … どちらか一方を全面に
 *  - 2画面 … 横画面は左右（左に Spectrum）、縦画面は上下（上にハイウェイモード）
 * Spectrum が見えていないときは音楽の View を外す（音声の解析を止める）。
 */
class DualView(
    context: Context,
    private val music: View,
    private val panel: View,
    private val bar: ModeBarView
) : ViewGroup(context) {

    private val density = resources.displayMetrics.density
    private var frame: DualFrame? = null
    private val divider = Paint().apply { color = 0xFF18263A.toInt() }

    var layoutMode: DualLayout = DualLayout.MUSIC
        private set

    init {
        setBackgroundColor(0xFF000000.toInt())
        setWillNotDraw(false)
        addView(panel)
        addView(bar)
        addView(music)
        bar.layoutMode = layoutMode
        panel.visibility = View.GONE
    }

    /** 画面構成を変える（新しく見える側は短くフェードイン） */
    fun setLayout(l: DualLayout, animate: Boolean) {
        if (l == layoutMode) return
        val before = layoutMode
        layoutMode = l
        bar.layoutMode = l
        val musicShown = l != DualLayout.HIGHWAY
        if (musicShown && music.parent == null) addView(music)
        if (!musicShown && music.parent != null) removeView(music)
        panel.visibility = if (l == DualLayout.MUSIC) View.GONE else View.VISIBLE
        requestLayout()
        invalidate()
        if (animate) {
            val fadeIn = ArrayList<View>()
            if (musicShown && before == DualLayout.HIGHWAY) fadeIn.add(music)
            if (l != DualLayout.MUSIC && before == DualLayout.MUSIC) fadeIn.add(panel)
            if (before == DualLayout.SPLIT || l == DualLayout.SPLIT) {
                // 大きさが変わる側も、いったん薄くしてから戻す（並び替えが目で追いやすい）
                if (musicShown && !fadeIn.contains(music)) fadeIn.add(music)
                if (l != DualLayout.MUSIC && !fadeIn.contains(panel)) fadeIn.add(panel)
            }
            for (v in fadeIn) {
                v.animate().cancel()
                v.alpha = 0.25f
                v.animate().alpha(1f).setDuration(240L).start()
            }
        }
    }

    fun setSnapshot(s: HighwaySnapshot) {
        bar.setSnapshot(s)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val f = DualGeometry.phone(w.toFloat(), h.toFloat(), layoutMode, density)
        frame = f
        if (music.parent != null) measureChild(music, f.music)
        measureChild(panel, f.highway)
        measureChild(bar, f.bar)
    }

    private fun measureChild(v: View, b: com.example.highwaymode.core.dual.Box?) {
        if (b == null) {
            v.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY))
            return
        }
        v.measure(
            MeasureSpec.makeMeasureSpec(b.w.toInt().coerceAtLeast(0), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(b.h.toInt().coerceAtLeast(0), MeasureSpec.EXACTLY)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val f = frame ?: return
        if (music.parent != null) place(music, f.music)
        place(panel, f.highway)
        place(bar, f.bar)
    }

    private fun place(v: View, b: com.example.highwaymode.core.dual.Box?) {
        if (b == null) {
            v.layout(0, 0, 0, 0)
            return
        }
        v.layout(b.l.toInt(), b.t.toInt(), b.r.toInt(), b.b.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        frame?.divider?.let { d -> canvas.drawRect(d.l, d.t, d.r, d.b, divider) }
    }
}

/** 下部の切替バー（スマホ） */
class ModeBarView(context: Context) : View(context) {

    interface Callbacks {
        fun onSelectLayout(l: DualLayout)
        fun onMenu()
        fun onPlayPause()
    }

    var callbacks: Callbacks? = null
    private val renderer = ModeBarRenderer(resources.displayMetrics.density).apply {
        showMenuButton = true
        idleHint = "高速道路に入ると自動で 2 画面になります"
    }
    private var snap: HighwaySnapshot? = null
    private var downX = 0f
    private var downY = 0f
    private val density = resources.displayMetrics.density

    var layoutMode: DualLayout = DualLayout.MUSIC
        set(v) {
            field = v
            invalidate()
        }

    fun setSnapshot(s: HighwaySnapshot) {
        snap = s
        invalidate()
    }

    /** 2 画面のときの案内（横画面でバーに余裕があるときに表示） */
    fun setSplitHint(hint: String?) {
        if (renderer.splitHint == hint) return
        renderer.splitHint = hint
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        renderer.draw(canvas, 0f, 0f, width.toFloat(), height.toFloat(), layoutMode, snap)
        // 曲名・再生状態を追いかける（ハイウェイだけの表示中）
        if (layoutMode == DualLayout.HIGHWAY) postInvalidateDelayed(1000L)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
            }
            MotionEvent.ACTION_UP -> {
                if (abs(e.x - downX) > 24 * density || abs(e.y - downY) > 24 * density) return true
                performClick()
                when (renderer.hitAt(e.x, e.y)) {
                    ModeBarRenderer.Target.MUSIC -> callbacks?.onSelectLayout(DualLayout.MUSIC)
                    ModeBarRenderer.Target.SPLIT, ModeBarRenderer.Target.OPEN_SPLIT -> callbacks?.onSelectLayout(DualLayout.SPLIT)
                    ModeBarRenderer.Target.HIGHWAY -> callbacks?.onSelectLayout(DualLayout.HIGHWAY)
                    ModeBarRenderer.Target.MENU -> callbacks?.onMenu()
                    ModeBarRenderer.Target.PLAY_PAUSE -> {
                        callbacks?.onPlayPause()
                        postInvalidateDelayed(300L)
                    }
                    null -> {}
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

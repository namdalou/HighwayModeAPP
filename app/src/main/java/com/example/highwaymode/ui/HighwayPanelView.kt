package com.example.highwaymode.ui

import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import com.example.highwaymode.core.engine.FacilityView
import com.example.highwaymode.core.engine.HighwaySnapshot
import kotlin.math.abs

/**
 * スマホ画面のハイウェイモード表示（描画は PanelRenderer と共用）。
 */
class HighwayPanelView(context: Context) : View(context) {

    interface Callbacks {
        fun onFacilityTap(f: FacilityView)
        fun onToggleDisplay()
        fun onReroute()
        fun onMenu()
        fun onOverlayTap()
    }

    var callbacks: Callbacks? = null

    private val renderer = PanelRenderer(resources.displayMetrics.density)

    /** 0=大きく 3 件, 1=一覧, 2=SA/PA のみ */
    var displayMode: Int
        get() = renderer.displayMode
        set(v) {
            renderer.displayMode = v
            invalidate()
        }

    var showCamerasInList: Boolean
        get() = renderer.showCamerasInList
        set(v) {
            renderer.showCamerasInList = v
            invalidate()
        }

    private var snap: HighwaySnapshot? = null
    private var insetL = 0
    private var insetT = 0
    private var insetR = 0
    private var insetB = 0
    private var downX = 0f
    private var downY = 0f
    private val density = resources.displayMetrics.density

    fun setSnapshot(s: HighwaySnapshot) {
        snap = s
        invalidate()
    }

    fun setInsets(l: Int, t: Int, r: Int, b: Int) {
        insetL = l; insetT = t; insetR = r; insetB = b
        invalidate()
    }

    /** 権限エラーなどの全面メッセージ（null で消す） */
    fun showMessage(m: String?) {
        renderer.overlayMessage = m
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        renderer.draw(c, insetL.toFloat(), insetT.toFloat(), (width - insetR).toFloat(), (height - insetB).toFloat(), snap, System.currentTimeMillis())
        // 先のページを表示中は、操作が無ければ現在地に戻すため描き直しを続ける
        if (renderer.page > 0) postInvalidateDelayed(1000L)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (abs(e.x - downX) > 24 * density || abs(e.y - downY) > 24 * density) return true
                if (renderer.overlayMessage != null) {
                    performClick()
                    callbacks?.onOverlayTap()
                    return true
                }
                val h = renderer.hitAt(e.x, e.y) ?: return true
                performClick()
                val f = h.facility
                if (f != null) {
                    callbacks?.onFacilityTap(f)
                } else {
                    when (h.button) {
                        PanelRenderer.BUTTON_DISPLAY -> callbacks?.onToggleDisplay()
                        PanelRenderer.BUTTON_REROUTE -> callbacks?.onReroute()
                        PanelRenderer.BUTTON_MENU -> callbacks?.onMenu()
                        else -> renderer.pageButton(h.button)
                    }
                    invalidate()
                }
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

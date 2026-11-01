package com.example.spectrum

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.Toast

/**
 * スマホの音楽画面。
 * 電池の節約のため、画面に見えている間だけ音声を解析し（アプリを裏にした・画面を消したら止める）、
 * 描き直しは再生中で約 60fps（高リフレッシュレートの画面でも 60fps まで）、一時停止中は約 15fps にする。
 */
class PlayerView(context: Context) : View(context) {

    companion object {
        /** 診断画面を開くのに必要な連続タップ回数 */
        private const val DIAG_TAP_COUNT = 20
        /** 連続タップとみなす間隔(ms)。これより間が空くと数え直し */
        private const val DIAG_TAP_INTERVAL_MS = 600L
        /** 再生中の描き直しの間隔（60fps。120Hz の画面でも 1 フレームおき） */
        private const val PLAYING_FRAME_MS = 15L
        /** 一時停止中の描き直しの間隔（曲名の流し表示用） */
        private const val PAUSED_FRAME_MS = 66L
    }

    private val renderer = PlayerRenderer().apply {
        onArtTap = {
            if (!NowPlaying.openSourceApp(context)) {
                Toast.makeText(context, "再生中のアプリを開けませんでした", Toast.LENGTH_SHORT).show()
            }
        }
    }
    private val specListener: (FloatArray) -> Unit = { renderer.setSpectrum(it) }

    /** 2 画面（横画面の左側）用の表示 */
    var large: Boolean
        get() = renderer.large
        set(v) {
            if (renderer.large != v) {
                renderer.large = v
                invalidate()
            }
        }

    /** 音声の解析を使っている（見えている間だけ） */
    private var acquired = false
    private val redraw = Runnable { invalidate() }

    /** 診断画面用の連続タップの数 */
    private var tapCount = 0
    private var lastTapTime = 0L

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        setSpectrumInUse(isVisible && isAttachedToWindow)
        if (isVisible) invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isShown) setSpectrumInUse(true)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(redraw)
        setSpectrumInUse(false)
        super.onDetachedFromWindow()
    }

    private fun setSpectrumInUse(use: Boolean) {
        if (use == acquired) return
        acquired = use
        if (use) {
            SpectrumEngine.listeners.add(specListener)
            SpectrumEngine.acquire(context.applicationContext)
        } else {
            SpectrumEngine.listeners.remove(specListener)
            SpectrumEngine.release()
        }
    }

    private var lastHeal = 0L

    override fun onDraw(canvas: Canvas) {
        renderer.draw(canvas, 0, 0, width, height)
        val now = SystemClock.uptimeMillis()
        if (now - lastHeal > 2000L) {      // 曲情報が空のままなら取り直す
            lastHeal = now
            MediaTracker.heal(context)
        }
        // 次の描き直し（画面の更新に合わせて）
        removeCallbacks(redraw)
        val delay = if (NowPlaying.isPlaying || renderer.animating) PLAYING_FRAME_MS else PAUSED_FRAME_MS
        postOnAnimationDelayed(redraw, delay)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            val handled = renderer.onTap(e.x, e.y)
            // ボタンやジャケット以外の場所の連続タップだけを数える(誤って再生操作しないように)
            if (!handled) countDiagnosticsTap() else tapCount = 0
            performClick()
            invalidate()
        }
        return true
    }

    /** 何もない場所を DIAG_TAP_COUNT 回続けてタップしたら診断画面を開く */
    private fun countDiagnosticsTap() {
        val now = SystemClock.uptimeMillis()
        tapCount = if (now - lastTapTime <= DIAG_TAP_INTERVAL_MS) tapCount + 1 else 1
        lastTapTime = now
        if (tapCount >= DIAG_TAP_COUNT) {
            tapCount = 0
            context.startActivity(Intent(context, DiagnosticsActivity::class.java))
        }
    }

    override fun performClick(): Boolean = super.performClick()
}

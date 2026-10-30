package com.example.spectrum

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.Toast

class PlayerView(context: Context) : View(context) {

    companion object {
        /** 診断画面を開くのに必要な連続タップ回数 */
        private const val DIAG_TAP_COUNT = 20
        /** 連続タップとみなす間隔(ms)。これより間が空くと数え直し */
        private const val DIAG_TAP_INTERVAL_MS = 600L
    }

    private val renderer = PlayerRenderer().apply {
        onArtTap = {
            if (!NowPlaying.openSourceApp(context)) {
                Toast.makeText(context, "再生中のアプリを開けませんでした", Toast.LENGTH_SHORT).show()
            }
        }
    }
    private val specListener: (FloatArray) -> Unit = { renderer.setSpectrum(it) }

    /** 診断画面用の連続タップの数 */
    private var tapCount = 0
    private var lastTapTime = 0L

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        SpectrumEngine.listeners.add(specListener)
        SpectrumEngine.acquire(context.applicationContext)
    }

    override fun onDetachedFromWindow() {
        SpectrumEngine.listeners.remove(specListener)
        SpectrumEngine.release()
        super.onDetachedFromWindow()
    }

    private var lastHeal = 0L

    override fun onDraw(canvas: Canvas) {
        renderer.draw(canvas, 0, 0, width, height)
        val now = SystemClock.uptimeMillis()
        if (now - lastHeal > 2000L) {      // 曲情報が空のままなら取り直す
            lastHeal = now
            MediaTracker.heal(context)
        }
        postInvalidateOnAnimation()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            val handled = renderer.onTap(e.x, e.y)
            // ボタンやジャケット以外の場所の連続タップだけを数える(誤って再生操作しないように)
            if (!handled) countDiagnosticsTap() else tapCount = 0
            performClick()
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

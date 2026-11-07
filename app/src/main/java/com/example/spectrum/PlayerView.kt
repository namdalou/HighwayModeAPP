package com.example.spectrum

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Toast
import kotlin.math.abs

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

    /**
     * 何もない場所を [DIAG_TAP_COUNT] 回続けてタップしたときの処理。
     * 未設定なら Spectrum の診断画面を開く（統合したアプリでは、パスワードを聞いて開発者向け設定を開く）
     */
    var onSecretTap: (() -> Unit)? = null

    /** スペアナの場所を長押ししたとき（統合したアプリでは表示の種類を順に切り替える。未設定なら何もしない） */
    var onSpectrumLongPress: (() -> Unit)? = null

    /** カーナビの案内の帯をタップしたとき（統合したアプリではナビのアプリを開く） */
    var onGuideTap: (() -> Unit)?
        get() = renderer.onGuideTap
        set(v) {
            renderer.onGuideTap = v
        }
    private var downX = 0f
    private var downY = 0f
    private var longPressed = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPress = Runnable {
        longPressed = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onSpectrumLongPress?.invoke()
        invalidate()
    }

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
        removeCallbacks(longPress)
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

    /** 再生位置のバーを指で動かしている（v2.14。離したときにその位置へ移る） */
    private var scrubbing = false

    override fun onTouchEvent(e: MotionEvent): Boolean {
        // 再生位置のバー: 触ったところから指に合わせて動かし、離したらその位置へ移る
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (renderer.isSeekAt(e.x, e.y)) {
                scrubbing = true
                tapCount = 0
                parent?.requestDisallowInterceptTouchEvent(true)
                renderer.scrubTo(e.x)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> if (scrubbing) {
                renderer.scrubTo(e.x)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (scrubbing) {
                scrubbing = false
                val commit = e.actionMasked == MotionEvent.ACTION_UP
                if (commit) renderer.scrubTo(e.x)
                renderer.endScrub(commit)
                if (commit) performClick()
                invalidate()
                return true
            }
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                longPressed = false
                if (onSpectrumLongPress != null && renderer.isSpectrumAt(e.x, e.y)) {
                    postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(e.x - downX) > touchSlop || abs(e.y - downY) > touchSlop) removeCallbacks(longPress)
            }
            MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
        }
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            removeCallbacks(longPress)
            if (longPressed) {
                // 長押しで表示を切り替えた: タップとしては数えない
                longPressed = false
                tapCount = 0
                return true
            }
            val handled = renderer.onTap(e.x, e.y)
            // ボタンやジャケット以外の場所の連続タップだけを数える(誤って再生操作しないように)
            if (!handled) countDiagnosticsTap() else tapCount = 0
            performClick()
            invalidate()
        }
        return true
    }

    /** 何もない場所を DIAG_TAP_COUNT 回続けてタップしたら診断画面（または onSecretTap）を開く */
    private fun countDiagnosticsTap() {
        val now = SystemClock.uptimeMillis()
        tapCount = if (now - lastTapTime <= DIAG_TAP_INTERVAL_MS) tapCount + 1 else 1
        lastTapTime = now
        if (tapCount >= DIAG_TAP_COUNT) {
            tapCount = 0
            val hook = onSecretTap
            if (hook != null) hook() else context.startActivity(Intent(context, DiagnosticsActivity::class.java))
        }
    }

    override fun performClick(): Boolean = super.performClick()
}

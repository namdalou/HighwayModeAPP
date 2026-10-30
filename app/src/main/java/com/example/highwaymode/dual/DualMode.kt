package com.example.highwaymode.dual

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.example.highwaymode.core.dual.DualLayout
import com.example.highwaymode.core.dual.HighwayPresence
import com.example.highwaymode.core.engine.HighwaySnapshot
import com.example.highwaymode.data.Prefs
import com.example.highwaymode.engine.EngineHost
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Spectrum（音楽）とハイウェイモードの画面構成を、スマホと車の画面で共有する。
 *
 *  - 高速道路の対象エリアに入る（[HighwayPresence]）→ 設定の画面（既定は 2 画面）に自動で切替
 *  - 対象エリアを出る → Spectrum 全画面に戻す
 *  - 対象エリア内で利用者が選んだ画面は、エリアを出るまでそのまま
 * 状態の変更はメインスレッドで行う（別スレッドから呼ばれたらメインスレッドへ回す）。
 */
object DualMode {

    fun interface Listener {
        fun onDualChanged()
    }

    /** いまの画面構成 */
    @Volatile
    var layout: DualLayout = DualLayout.MUSIC
        private set

    /** 高速道路の対象エリアにいる */
    @Volatile
    var highwayActive: Boolean = false
        private set

    /** 最後に画面構成が変わった時刻（SystemClock.uptimeMillis。切替の演出に使う） */
    @Volatile
    var changedAtMs: Long = 0L
        private set

    /** 下部の切替バーを出すか（対象エリア内、または手動で音楽以外にしているとき） */
    val barVisible: Boolean get() = highwayActive || layout != DualLayout.MUSIC

    private val presence = HighwayPresence()
    private val listeners = CopyOnWriteArraySet<Listener>()
    private var prefs: Prefs? = null
    private var users = 0
    /** 最後にエンジンの状態を受け取った時刻（SystemClock.elapsedRealtime） */
    private var lastSnapshotAt = 0L
    private val engineListener = EngineHost.SnapshotListener { s -> onSnapshot(s) }
    private val main = Handler(Looper.getMainLooper())

    /** 画面（スマホ・車）が表示を始めるときに呼ぶ */
    fun attach(ctx: Context) {
        if (!onMain { attach(ctx) }) return
        if (prefs == null) prefs = Prefs(ctx.applicationContext)
        users++
        if (users == 1) {
            // しばらく使っていなかった（前回の走行の状態が残っている）なら、音楽の画面から始める
            if (SystemClock.elapsedRealtime() - lastSnapshotAt > STALE_MS) {
                presence.reset()
                highwayActive = false
                setLayout(DualLayout.MUSIC)
            }
            EngineHost.addListener(engineListener)
            EngineHost.latest?.let { onSnapshot(it) }
        }
    }

    private const val STALE_MS = 30_000L

    fun detach() {
        if (!onMain { detach() }) return
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) EngineHost.removeListener(engineListener)
    }

    fun addListener(l: Listener) {
        listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    /** 対象エリアに入ったときの画面（設定） */
    fun highwayDefault(): DualLayout = try {
        DualLayout.valueOf(prefs?.highwayLayout ?: "SPLIT")
    } catch (e: IllegalArgumentException) {
        DualLayout.SPLIT
    }

    /** 利用者が画面を選んだ */
    fun select(l: DualLayout) {
        if (!onMain { select(l) }) return
        if (setLayout(l)) notifyListeners()
    }

    /** 「画面切替」ボタン: 2画面 → ハイウェイ → 音楽 → 2画面 … */
    fun cycle() {
        select(
            when (layout) {
                DualLayout.SPLIT -> DualLayout.HIGHWAY
                DualLayout.HIGHWAY -> DualLayout.MUSIC
                DualLayout.MUSIC -> DualLayout.SPLIT
            }
        )
    }

    /** 自動切替の設定を変えたとき（オンにしたら、いまの状況に合わせる） */
    fun settingsChanged() {
        if (!onMain { settingsChanged() }) return
        val p = prefs ?: return
        if (p.autoSwitch) {
            val target = if (highwayActive) highwayDefault() else DualLayout.MUSIC
            setLayout(target)
        }
        notifyListeners()
    }

    private fun onSnapshot(s: HighwaySnapshot) {
        lastSnapshotAt = SystemClock.elapsedRealtime()
        val changed = presence.update(s.status, lastSnapshotAt / 1000.0)
        if (!changed) return
        highwayActive = presence.active
        if (prefs?.autoSwitch != false) {
            setLayout(if (highwayActive) highwayDefault() else DualLayout.MUSIC)
        }
        notifyListeners()
    }

    private fun setLayout(l: DualLayout): Boolean {
        if (layout == l) return false
        layout = l
        changedAtMs = SystemClock.uptimeMillis()
        return true
    }

    private fun notifyListeners() {
        for (l in listeners) l.onDualChanged()
    }

    /** メインスレッドなら true（そのまま続ける）。別スレッドなら処理をメインスレッドへ回して false */
    private inline fun onMain(crossinline again: () -> Unit): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) return true
        main.post { again() }
        return false
    }

    /** 画面構成の表示名 */
    fun label(l: DualLayout): String = l.label
}

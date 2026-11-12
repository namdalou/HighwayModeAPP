package com.example.highwaymode

import android.app.Activity
import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.example.highwaymode.core.engine.DisconnectPolicy
import com.example.highwaymode.data.Prefs
import com.example.highwaymode.engine.EngineHost

/**
 * スマホの画面でこのアプリを開いているか（設定画面などを含む）。
 * アプリのどの入口（スマホの画面・常駐サービス・車の画面）からでも [install] を呼んでおく。
 */
object AppPresence {

    @Volatile
    private var started = 0
    private var installed = false

    /** スマホの画面でこのアプリが見えている */
    val phoneVisible: Boolean get() = started > 0

    @Synchronized
    fun install(context: Context) {
        if (installed) return
        val app = context.applicationContext as? Application ?: return
        installed = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                started++
                // スマホで開いた → 止める予定は取り消す（スマホで使っている）
                CarAutoStop.cancel("スマホの画面で開いた")
            }

            override fun onActivityStopped(activity: Activity) {
                if (started > 0) started--
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}

/**
 * Android Auto との接続が切れたあと、スマホ側の常駐（位置情報の取得・通知）を止める（v2.15）。
 *
 * 利用者の報告: スマホを操作せずに Android Auto だけで使っていると、切断したあともずっと常駐が続いていた。
 * 切断から判定時間（設定。標準 3 分）たっても、車の画面がつながり直さず、スマホの画面でも開いていなければ、
 * 常駐サービスとエンジン（位置情報・通信）を止める。判定は [DisconnectPolicy]（高速道路を走っている最中は止めない）。
 *
 * 画面を消したスマホは眠るので、時間は 3 通りで確かめる（どれも同じ [evaluate] を呼ぶ。何度呼んでもよい）:
 *  - メインスレッドのタイマー（スマホが起きている間だけ進む）
 *  - エンジンの状態が届くたび（位置情報が届くとスマホが起きる。[onSnapshot]）
 *  - アラーム（眠っていても届く。少し遅れることがある。常駐サービスが受け取る）
 */
object CarAutoStop {

    private const val TAG = "CarAutoStop"

    /** 判定時間を過ぎても止められない（高速道路を走行中）とき、次に確かめるまで */
    private const val RECHECK_MS = 30_000L

    /** 常駐サービスが受け取るアラームの合図 */
    const val ACTION_CHECK = "com.example.highwaymode.AUTO_STOP_CHECK"

    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null

    /** 接続が切れた時刻（SystemClock.elapsedRealtime。眠っている間も進む） */
    private var disconnectedAt = 0L
    private var graceMs = 0L

    /** 止める予定がある（判定時間の途中・高速道路を走行中で待っている） */
    @Volatile
    var pending = false
        private set

    /** 確認用（動作状態の画面）: 最後に何をしたか */
    @Volatile
    var lastEvent: String = ""
        private set

    private val check = Runnable { evaluate() }

    /** 止めるまでの残り時間 [ms]（予定が無ければ負） */
    fun remainingMs(): Long = if (pending) (disconnectedAt + graceMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L) else -1L

    /** 利用者が車のメニューで「終了」を押した（このあとの切断では、判定時間を置かずに止める） */
    @Volatile
    var exitRequested = false

    /** 車の画面がつながった */
    fun onCarConnected() {
        exitRequested = false
        cancel("車の画面がつながった")
    }

    /** 車の画面との接続が切れた（車のメニューの「終了」を含む） */
    fun onCarDisconnected(context: Context) {
        val ctx = context.applicationContext
        runOnMain {
            appContext = ctx
            val exit = exitRequested
            exitRequested = false
            if (AppPresence.phoneVisible) {
                lastEvent = "切断（スマホの画面で開いているので続ける）"
                return@runOnMain
            }
            if (!TrackingService.running && !EngineHost.running) return@runOnMain
            if (exit) {
                // 車の画面で「終了」を押した → 待たずに止める
                stopNow(ctx, "車の画面で終了したので常駐を止めた")
                return@runOnMain
            }
            val setting = Prefs(ctx).carStopGraceMs
            if (setting < 0L) {
                lastEvent = "切断（設定: 終了しない）"
                return@runOnMain
            }
            graceMs = setting
            disconnectedAt = SystemClock.elapsedRealtime()
            pending = true
            lastEvent = "切断（${graceMs / 60_000} 分後に止める予定）"
            Log.i(TAG, lastEvent)
            TrackingService.showPendingStop(graceMs)
            main.removeCallbacks(check)
            main.postDelayed(check, graceMs + 500L)
            setAlarm(ctx, disconnectedAt + graceMs + 1_000L)
        }
    }

    private fun stopNow(ctx: Context, event: String) {
        pending = false
        main.removeCallbacks(check)
        cancelAlarm(ctx)
        lastEvent = event
        Log.i(TAG, event)
        TrackingService.stop(ctx)
        // 常駐サービスが動いていなかった場合（起動できなかった など）も、エンジンは止める
        if (!TrackingService.running && !EngineHost.carConnected) EngineHost.stop()
    }

    /** 止める予定を取り消す */
    fun cancel(reason: String) {
        if (!pending) return
        runOnMain {
            if (!pending) return@runOnMain
            pending = false
            lastEvent = "止める予定を取り消し（$reason）"
            Log.i(TAG, lastEvent)
            main.removeCallbacks(check)
            appContext?.let { cancelAlarm(it) }
            TrackingService.clearPendingStop()
        }
    }

    /** エンジンの状態が届いた（メインスレッド）。時間が来ていれば確かめる */
    fun onSnapshot() {
        if (pending && SystemClock.elapsedRealtime() - disconnectedAt >= graceMs) evaluate()
    }

    /** 止めるかどうかを確かめる（何度呼んでもよい） */
    fun evaluate() {
        runOnMain {
            if (!pending) return@runOnMain
            val ctx = appContext ?: return@runOnMain
            val s = EngineHost.latest
            val since = SystemClock.elapsedRealtime() - disconnectedAt
            when (DisconnectPolicy.decide(EngineHost.carConnected, AppPresence.phoneVisible, since, graceMs, s?.status, s?.speedKmh, s?.gpsAgeSec)) {
                DisconnectPolicy.Action.KEEP -> cancel(if (EngineHost.carConnected) "車の画面がつながった" else "スマホの画面で開いた")
                DisconnectPolicy.Action.WAIT -> {
                    // 判定時間の途中、または高速道路を走行中（降りる・止まるまで続ける）
                    val left = graceMs - since
                    main.removeCallbacks(check)
                    main.postDelayed(check, if (left > 0L) left + 500L else RECHECK_MS)
                    if (left <= 0L) {
                        lastEvent = "高速道路を走行中のため続けています"
                        setAlarm(ctx, SystemClock.elapsedRealtime() + RECHECK_MS * 4)
                    }
                }
                DisconnectPolicy.Action.STOP -> stopNow(ctx, "接続が切れたので常駐を止めた")
            }
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    // ---- アラーム（スマホが眠っていても届く。正確な時刻でなくてよいので、許可の要らない種類を使う）

    private fun alarmIntent(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, 3,
            Intent(ACTION_CHECK).setPackage(ctx.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun setAlarm(ctx: Context, atElapsedMs: Long) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, atElapsedMs, alarmIntent(ctx))
        } catch (e: Exception) {
            Log.w(TAG, "alarm failed: ${e.message}")
        }
    }

    private fun cancelAlarm(ctx: Context) {
        try {
            (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(alarmIntent(ctx))
        } catch (e: Exception) {
            // 予約が無い
        }
    }
}

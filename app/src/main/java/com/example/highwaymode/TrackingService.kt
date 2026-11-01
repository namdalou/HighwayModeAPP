package com.example.highwaymode

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.example.highwaymode.core.engine.EngineStatus
import com.example.highwaymode.core.engine.HighwaySnapshot
import com.example.highwaymode.engine.EngineHost
import com.example.highwaymode.ui.Formats

/**
 * 画面を消しても・他のアプリに切り替えても・Android Auto だけで使っていても、
 * 測位・地図・渋滞情報・曲情報の取得を続けるための常駐サービス。通知には次の施設を表示する。
 *
 * 位置情報の種類（location）で前景にするのが基本。Android 14 以降は、位置情報の許可が
 * 「アプリの使用中のみ」のまま裏から起動すると location の種類にできないため、そのときは
 * データ同期の種類（dataSync）で前景にして、通信とアプリの動作を保つ（位置は車の GPS・車速と推測航法で補う）。
 * 以後 30 秒ごとと、車の画面が表示・操作されたときに location への切り替えを試みる。
 */
class TrackingService : Service() {

    /** 前景サービスの状態 */
    enum class Mode { NONE, LOCATION, DATA_ONLY }

    private val main = Handler(Looper.getMainLooper())
    private var lastNotifyMs = 0L
    private var lastText: String? = null
    private var current: Notification? = null
    private var engineStarted = false

    private val listener = EngineHost.SnapshotListener { s -> updateNotification(s) }

    /** 位置情報の種類で前景にできていないとき、定期的に切り替えを試みる */
    private val upgrade = object : Runnable {
        override fun run() {
            if (mode != Mode.DATA_ONLY) return
            tryUpgrade()
            if (mode == Mode.DATA_ONLY) main.postDelayed(this, UPGRADE_RETRY_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        running = true
        createChannel()
        promote(buildNotification("ハイウェイモード", "起動中…"))
        if (mode != Mode.NONE) startEngine()
    }

    private fun startEngine() {
        if (engineStarted) return
        engineStarted = true
        EngineHost.start(this)
        EngineHost.addListener(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // startForegroundService のたびに前景化が必要。location にできていなければ、ここでも試す
        promote(current ?: buildNotification("ハイウェイモード", "起動中…"))
        if (mode == Mode.NONE) {
            // 前景にできない（裏からの起動が制限された など）。動き続けると止められるので終える
            stopSelf(startId)
            return START_NOT_STICKY
        }
        startEngine()
        EngineHost.restartLocation()
        return START_NOT_STICKY
    }

    /** 前景サービスにする（location → だめなら dataSync） */
    private fun promote(n: Notification) {
        current = n
        if (Build.VERSION.SDK_INT < 29) {
            try {
                startForeground(NOTIFICATION_ID, n)
                setMode(Mode.LOCATION, null)
            } catch (e: Exception) {
                setMode(Mode.NONE, describe(e))
            }
            return
        }
        if (mode == Mode.LOCATION) {
            // すでに location で前景。通知の差し替えだけ（失敗しても location のまま動き続けるので種類は変えない）
            try {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } catch (e: Exception) {
                Log.w(TAG, "re-promote failed: ${e.message}")
            }
            return
        }
        if (tryLocation(n)) return
        val whyNotLocation = lastError
        try {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            setMode(Mode.DATA_ONLY, whyNotLocation)
            main.removeCallbacks(upgrade)
            main.postDelayed(upgrade, UPGRADE_RETRY_MS)
        } catch (e: Exception) {
            setMode(Mode.NONE, describe(e))
        }
    }

    private fun tryLocation(n: Notification): Boolean {
        if (!hasLocationPermission(this)) {
            lastError = "位置情報の権限がありません"
            return false
        }
        return try {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            setMode(Mode.LOCATION, null)
            true
        } catch (e: Exception) {
            lastError = describe(e)
            Log.i(TAG, "location type not allowed now: ${e.message}")
            false
        }
    }

    /** dataSync で動作中に、location への切り替えを試みる（車の画面の表示・操作のたびにも呼ばれる） */
    private fun tryUpgrade() {
        if (mode != Mode.DATA_ONLY || Build.VERSION.SDK_INT < 29) return
        val n = current ?: return
        if (tryLocation(n)) {
            Log.i(TAG, "upgraded to location foreground service")
            EngineHost.reregisterLocation()
        }
    }

    private fun setMode(m: Mode, error: String?) {
        mode = m
        lastError = error
        modeChangedAt = SystemClock.elapsedRealtime()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // スマホでアプリを最近の一覧から消したら停止（電池節約）。ただし車の画面で使っている間は続ける
        if (!EngineHost.carConnected) stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    /** Android 15 以降: dataSync の種類は 1 日 6 時間まで。超えたら前景をやめる */
    override fun onTimeout(startId: Int, fgsType: Int) {
        if (fgsType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC != 0) {
            setMode(Mode.NONE, "前景サービスの時間の上限（1 日 6 時間）に達しました")
            stopSelf()
        }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        running = false
        instance = null
        setMode(Mode.NONE, lastError)
        if (engineStarted) {
            EngineHost.removeListener(listener)
            // Android Auto の画面が出ている間はエンジンを止めない（車載画面側の終了時に止める）
            if (!EngineHost.carConnected) EngineHost.stop()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL_ID, "ハイウェイモード動作中", NotificationManager.IMPORTANCE_LOW)
        ch.description = "走行中の次の施設を表示します"
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_highway)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "停止", stop).build())
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return b.build()
    }

    private fun updateNotification(s: HighwaySnapshot) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotifyMs < 5000) return
        val (title, text) = when (s.status) {
            EngineStatus.ON_HIGHWAY -> {
                val next = s.facilities.firstOrNull { !it.noExit } ?: s.facilities.firstOrNull()
                val cam = s.cameras.firstOrNull()?.takeIf { it.distanceM < 2000 }
                (if (cam != null) "オービス ${Formats.distance(cam.distanceM)}先" else s.roadName ?: "高速道路") to (next?.let {
                    "次: ${it.displayName}  ${Formats.distance(it.distanceM)}  ${Formats.clock(it.etaEpochMs)}"
                } ?: "前方の施設を検索中")
            }
            EngineStatus.REST_STOP -> (s.roadName ?: "高速道路") to (s.directionText ?: "SA/PA 停車中")
            EngineStatus.WAITING_GPS -> "ハイウェイモード" to "GPS 測位中…"
            EngineStatus.LOADING_MAP -> "ハイウェイモード" to "高速道路データを取得中…"
            EngineStatus.OFF_HIGHWAY -> "ハイウェイモード" to "高速道路外"
            EngineStatus.GENERAL_ROAD_MODE -> "ハイウェイモード" to "一般道モード"
        }
        val key = title + text
        if (key == lastText) return
        lastText = key
        lastNotifyMs = now
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n = buildNotification(title, text)
        current = n
        nm.notify(NOTIFICATION_ID, n)
    }

    companion object {
        private const val TAG = "TrackingService"
        private const val UPGRADE_RETRY_MS = 30_000L

        /** 常駐サービスが動いている */
        @Volatile
        var running = false
            private set

        /** 前景サービスの種類（location なら裏でも GPS を受信できる） */
        @Volatile
        var mode: Mode = Mode.NONE
            private set

        /** location の種類にできなかった理由など */
        @Volatile
        var lastError: String? = null
            private set

        /** 起動そのものに失敗した理由（裏からの起動の制限など） */
        @Volatile
        var lastStartError: String? = null
            private set

        @Volatile
        var modeChangedAt = 0L
            private set

        @Volatile
        private var instance: TrackingService? = null

        const val CHANNEL_ID = "highway_tracking"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.example.highwaymode.STOP"

        /** @return 起動の要求が受け付けられた */
        fun start(context: Context): Boolean = try {
            context.startForegroundService(Intent(context, TrackingService::class.java))
            lastStartError = null
            true
        } catch (e: Exception) {
            // Android 12 以降、裏からの前景サービスの起動は制限される（電池の最適化を解除すると可能）
            lastStartError = describe(e)
            Log.w(TAG, "start failed: ${e.message}")
            false
        }

        /**
         * 車の画面の表示・操作時などに呼ぶ。止まっていれば起動し、dataSync なら location への切り替えを試みる。
         * @return location の種類で動作中
         */
        fun ensure(context: Context): Boolean {
            if (!running) {
                start(context)
                return false
            }
            if (mode == Mode.DATA_ONLY) {
                val svc = instance
                if (svc != null) {
                    if (Looper.myLooper() == Looper.getMainLooper()) svc.tryUpgrade() else svc.main.post { svc.tryUpgrade() }
                }
            }
            return mode == Mode.LOCATION
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TrackingService::class.java))
        }

        fun hasLocationPermission(ctx: Context): Boolean =
            ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        private fun describe(e: Exception): String = when {
            e.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException" ->
                "スマホで開いていない間の起動が Android に制限されました"
            e is SecurityException -> "位置情報の許可が「アプリの使用中のみ」のため、裏からは位置情報の常駐ができません"
            else -> "${e.javaClass.simpleName}: ${e.message?.take(60) ?: ""}"
        }
    }
}

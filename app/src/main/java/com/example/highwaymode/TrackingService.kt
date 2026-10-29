package com.example.highwaymode

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import com.example.highwaymode.core.engine.EngineStatus
import com.example.highwaymode.core.engine.HighwaySnapshot
import com.example.highwaymode.engine.EngineHost
import com.example.highwaymode.ui.Formats

/**
 * 画面を消しても・他のアプリに切り替えても測位と推測航法を続けるための常駐サービス
 * （foregroundServiceType="location"）。通知には次の施設を表示する。
 */
class TrackingService : Service() {

    private var lastNotifyMs = 0L
    private var lastText: String? = null
    private var current: Notification? = null

    private val listener = EngineHost.SnapshotListener { s -> updateNotification(s) }

    override fun onCreate() {
        super.onCreate()
        running = true
        createChannel()
        promote(buildNotification("ハイウェイモード", "起動中…"))
        EngineHost.start(this)
        EngineHost.addListener(listener)
    }

    /** 前景サービス化（startForegroundService のたびに呼ぶ必要がある） */
    private fun promote(n: Notification) {
        current = n
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        promote(current ?: buildNotification("ハイウェイモード", "起動中…"))
        EngineHost.restartLocation()
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // アプリを最近の一覧から消したら停止（電池節約）
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        EngineHost.removeListener(listener)
        // Android Auto の画面が出ている間はエンジンを止めない（車載画面側の終了時に止める）
        if (!EngineHost.carConnected) EngineHost.stop()
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
                val next = s.facilities.firstOrNull()
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
        /** 常駐サービスが動いている */
        @Volatile
        var running = false
            private set

        const val CHANNEL_ID = "highway_tracking"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.example.highwaymode.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, TrackingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TrackingService::class.java))
        }
    }
}

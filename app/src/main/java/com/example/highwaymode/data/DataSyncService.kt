package com.example.highwaymode.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.example.highwaymode.MainActivity
import com.example.highwaymode.R
import com.example.highwaymode.core.hwdb.HwdbDownloader
import com.example.highwaymode.core.hwdb.OverpassDatasetBuilder
import com.example.highwaymode.core.hwdb.Prefectures
import com.example.highwaymode.core.net.CancelToken
import com.example.highwaymode.core.net.CancelledException
import com.example.highwaymode.core.net.OverpassHttp
import java.io.File
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/** 地図データの更新・作成の進み具合（画面表示用） */
object DataSyncState {
    @Volatile
    var running = false
        private set
    @Volatile
    var title: String = ""
        private set
    /** 0〜100、不明なら -1 */
    @Volatile
    var percent: Int = -1
        private set
    @Volatile
    var message: String? = null
        private set
    @Volatile
    var lastResult: String? = null
        private set

    fun interface Listener {
        fun onChanged()
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()

    fun addListener(l: Listener) {
        listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    internal fun set(running: Boolean, title: String, percent: Int, message: String?, result: String? = lastResult) {
        this.running = running
        this.title = title
        this.percent = percent
        this.message = message
        this.lastResult = result
        main.post { for (l in listeners) l.onChanged() }
    }
}

/**
 * 地図データ（HWDB）のダウンロード・地方単位の作成を行う前景サービス（foregroundServiceType="dataSync"）。
 * 画面を閉じても続き、通知に進み具合と「中止」ボタンを出す。
 */
class DataSyncService : Service() {

    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "hwdb-sync") }
    @Volatile
    private var cancel: CancelToken? = null
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancel?.cancel()
                if (!DataSyncState.running) stopSelf()
                return START_NOT_STICKY
            }
            ACTION_DOWNLOAD, ACTION_BUILD -> {
                if (DataSyncState.running) return START_NOT_STICKY
                createChannel()
                val title = if (intent.action == ACTION_DOWNLOAD) "地図データを更新中" else "地図データを作成中"
                promote(build(title, "準備中…", -1))
                val token = CancelToken()
                cancel = token
                DataSyncState.set(true, title, -1, "準備中…")
                val spec = intent.getStringExtra(EXTRA_REGIONS) ?: "all"
                exec.execute {
                    val result = try {
                        if (intent.action == ACTION_DOWNLOAD) doDownload(token) else doBuild(spec, token)
                    } catch (e: CancelledException) {
                        "中止しました"
                    } catch (e: Exception) {
                        Log.w(TAG, "sync failed", e)
                        "失敗しました: ${e.message?.take(120)}"
                    }
                    DataSyncState.set(false, title, -1, null, result)
                    notifyDone(title, result)
                    cancel = null
                    stopForegroundCompat()
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun doDownload(token: CancelToken): String {
        val prefs = Prefs(this)
        val url = prefs.manifestUrl
        if (url.isBlank()) throw IllegalStateException("配布元（目録 URL）が設定されていません")
        val dl = HwdbDownloader(HwdbManager.downloadDir(this))
        progress("地図データを更新中", "更新情報を確認中…", -1)
        val m = dl.fetchManifest(url, token)
        prefs.lastUpdateCheckMs = System.currentTimeMillis()
        prefs.availableVersion = m.dataVersion
        val files = dl.download(m, token) { p ->
            val pct = if (p.totalBytes > 0) (p.doneBytes * 100 / p.totalBytes).toInt() else -1
            progress("地図データを更新中", String.format(Locale.US, "%s %.1f / %.1f MB", p.message, p.doneBytes / 1e6, p.totalBytes / 1e6), pct)
        }
        // 目録に無くなった古いダウンロードは消す
        val keep = files.map { it.name }.toSet()
        HwdbManager.downloadDir(this).listFiles()?.forEach { if (it.name.endsWith(".hwdb") && it.name !in keep) it.delete() }
        HwdbManager.notifyChanged()
        return "更新しました（版 ${m.dataVersion}, ${String.format(Locale.US, "%.1f", m.totalBytes / 1e6)} MB）"
    }

    private fun doBuild(spec: String, token: CancelToken): String {
        val prefs = Prefs(this)
        val prefsList = Prefectures.resolve(spec)
        val http = OverpassHttp({ prefs.endpoints }, hedgeAfterMs = 60_000, maxParallel = 2, readTimeoutMs = 600_000)
        val name = "region-" + spec.replace(Regex("[^A-Za-z0-9-]"), "_").take(40) + ".hwdb"
        val out = File(HwdbManager.regionDir(this), name)
        try {
            val res = OverpassDatasetBuilder(http, retries = 3, retryWaitMs = 30_000).build(prefsList, out, token) { p ->
                val pct = if (p.total > 0) p.done * 100 / p.total else -1
                progress("地図データを作成中", "[${p.done}/${p.total}] ${p.message}", pct)
            }
            HwdbManager.notifyChanged()
            val failed = if (res.failed.isEmpty()) "" else "（取得できなかった県: ${res.failed.joinToString("・") { it.first.name }}）"
            return "作成しました: ${res.succeeded.size} 都道府県, ${out.length() / 1024} KB$failed"
        } finally {
            http.shutdown()
        }
    }

    private fun progress(title: String, msg: String, pct: Int) {
        DataSyncState.set(true, title, pct, msg)
        val now = System.currentTimeMillis()
        if (now - lastNotify < 700 && pct in 1..99) return
        lastNotify = now
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, build(title, msg, pct))
    }

    private fun promote(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIFICATION_ID, n)
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL_ID, "地図データの更新", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }

    private fun build(title: String, text: String, pct: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 10, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 11, Intent(this, DataSyncService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_highway)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "中止", stop).build())
            .setProgress(100, pct.coerceAtLeast(0), pct < 0)
        return b.build()
    }

    private fun notifyDone(title: String, result: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_highway)
            .setContentTitle(title.replace("中", "").ifEmpty { "地図データ" })
            .setContentText(result)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID + 1, n)
    }

    override fun onDestroy() {
        cancel?.cancel()
        exec.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DataSyncService"
        private const val CHANNEL_ID = "hwdb_sync"
        private const val NOTIFICATION_ID = 2001
        const val ACTION_DOWNLOAD = "com.example.highwaymode.HWDB_DOWNLOAD"
        const val ACTION_BUILD = "com.example.highwaymode.HWDB_BUILD"
        const val ACTION_CANCEL = "com.example.highwaymode.HWDB_CANCEL"
        const val EXTRA_REGIONS = "regions"

        fun download(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, DataSyncService::class.java).setAction(ACTION_DOWNLOAD))
        }

        fun build(ctx: Context, regions: String) {
            ctx.startForegroundService(Intent(ctx, DataSyncService::class.java).setAction(ACTION_BUILD).putExtra(EXTRA_REGIONS, regions))
        }

        fun cancel(ctx: Context) {
            ctx.startService(Intent(ctx, DataSyncService::class.java).setAction(ACTION_CANCEL))
        }
    }
}

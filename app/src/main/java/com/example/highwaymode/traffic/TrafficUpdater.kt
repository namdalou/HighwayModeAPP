package com.example.highwaymode.traffic

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.example.highwaymode.core.engine.EngineStatus
import com.example.highwaymode.core.net.HttpStatusException
import com.example.highwaymode.core.traffic.GoogleRoutesTrafficProvider
import com.example.highwaymode.core.traffic.HereTrafficProvider
import com.example.highwaymode.core.traffic.TomTomTrafficProvider
import com.example.highwaymode.core.traffic.TrafficProvider
import com.example.highwaymode.data.Prefs
import com.example.highwaymode.engine.EngineHost
import java.io.IOException

/**
 * 渋滞情報の定期取得（3 分 / 5 分 / 自動）。
 *  - 高速道路を走行中だけ取得する（一般道・停車中は取得しない）
 *  - 前方経路（最長 100km）に沿って問い合わせ、結果はエンジンの到着予測に反映する
 *  - 「自動」: 前方 40km 以内に渋滞があれば 3 分、無ければ 5 分。JCT で別路線に入った等で
 *    前回の問い合わせ範囲を外れたら、間隔を待たずに取り直す（最短 60 秒）
 */
class TrafficUpdater(context: Context) {
    private val ctx = context.applicationContext
    private val prefs = Prefs(ctx)
    private val thread = HandlerThread("traffic").apply { start() }
    private val handler = Handler(thread.looper)
    private var lastFetchMs = 0L
    private var lastAttemptMs = 0L
    private var queriedLength = 0.0
    private var traveledSinceFetch = 0.0
    private var lastCheckMs = 0L
    private var failures = 0
    @Volatile
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                check()
            } catch (e: Exception) {
                Log.w(TAG, "traffic check failed", e)
            }
            handler.postDelayed(this, 15_000L)
        }
    }

    fun start() {
        if (running) return
        running = true
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        EngineHost.post {
            it.setTraffic(null)
            it.trafficMessage = null
        }
    }

    fun shutdown() {
        stop()
        thread.quitSafely()
    }

    /** 設定画面の「今すぐ取得」 */
    fun refreshNow() {
        handler.post {
            lastFetchMs = 0L
            lastAttemptMs = 0L
            check(force = true)
        }
    }

    private fun provider(): TrafficProvider? {
        val p = prefs.trafficProvider
        val key = prefs.trafficKey(p)
        if (key.isBlank()) return null
        return when (p) {
            "here" -> HereTrafficProvider(key)
            "tomtom" -> TomTomTrafficProvider(key)
            "google" -> GoogleRoutesTrafficProvider(key)
            else -> null
        }
    }

    private fun check(force: Boolean = false) {
        val p = prefs.trafficProvider
        val interval = prefs.trafficIntervalMin
        if (p == "none" || interval == 0) {
            if (lastFetchMs != 0L) EngineHost.post { it.setTraffic(null) }
            lastFetchMs = 0L
            EngineHost.post { it.trafficMessage = null }
            return
        }
        val prov = provider()
        if (prov == null) {
            EngineHost.post { it.trafficMessage = "渋滞情報: API キー未設定" }
            return
        }
        val snap = EngineHost.latest ?: return
        if (snap.status != EngineStatus.ON_HIGHWAY && !force) return
        val now = SystemClock.elapsedRealtime()
        // 前回取得時からの走行距離（車速 × 時間）
        if (lastCheckMs != 0L) {
            val dt = ((now - lastCheckMs) / 1000.0).coerceIn(0.0, 60.0)
            traveledSinceFetch += (snap.speedKmh ?: 0.0) / 3.6 * dt
        }
        lastCheckMs = now
        val jamNear = snap.traffic?.nextJamDistanceM?.let { it < 40_000 } == true
        val minutes = when (interval) {
            -1 -> if (jamNear) 3 else 5
            else -> interval
        }
        val due = force || lastFetchMs == 0L || now - lastFetchMs >= minutes * 60_000L
        val leftCoverage = lastFetchMs != 0L && queriedLength > 0 && traveledSinceFetch > queriedLength * 0.6 && now - lastFetchMs > 60_000L
        // 失敗が続いたら 1 → 2 → 4 分と間隔をあける
        val backoffOk = failures == 0 || now - lastAttemptMs >= (60_000L shl minOf(failures - 1, 2))
        if (!(due || leftCoverage) || !backoffOk) return
        if (!online()) {
            EngineHost.post { it.trafficMessage = "渋滞情報: 通信できません" }
            return
        }
        lastAttemptMs = now
        val maxLen = if (prefs.trafficShortRange) 60_000.0 else 100_000.0
        EngineHost.trafficQuery(maxLen) { q ->
            handler.post {
                if (q == null || q.isEmpty) return@post
                try {
                    val data = prov.fetch(q)
                    lastFetchMs = SystemClock.elapsedRealtime()
                    queriedLength = q.lengthM
                    traveledSinceFetch = 0.0
                    failures = 0
                    EngineHost.post {
                        it.setTraffic(data)
                        it.trafficMessage = null
                    }
                    Log.i(TAG, "${prov.name}: ${data.items.size} items, ${q.lengthM / 1000} km")
                } catch (e: Exception) {
                    failures++
                    val msg = when {
                        e is HttpStatusException && (e.code == 401 || e.code == 403) -> "渋滞情報: API キーが無効か、この API が有効になっていません"
                        e is HttpStatusException && e.code == 429 -> "渋滞情報: 取得回数の上限に達しました"
                        e is IOException -> "渋滞情報: 取得できません（${e.message?.take(40)}）"
                        else -> "渋滞情報: 解析できません（${e.message?.take(40)}）"
                    }
                    Log.w(TAG, msg, e)
                    EngineHost.post { it.trafficMessage = msg }
                }
            }
        }
    }

    private fun online(): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(n) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    companion object {
        private const val TAG = "TrafficUpdater"
    }
}

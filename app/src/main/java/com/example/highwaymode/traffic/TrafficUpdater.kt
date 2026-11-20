package com.example.highwaymode.traffic

import android.content.Context
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
import com.example.highwaymode.engine.RunDiagnostics
import java.io.IOException

/**
 * 渋滞情報の定期取得（3 分 / 5 分 / 自動）。
 *  - 高速道路を走行中だけ取得する（一般道・停車中は取得しない）
 *  - 前方経路（最長 100km）に沿って問い合わせ、結果はエンジンの到着予測に反映する
 *  - 「自動」: 前方 40km 以内に渋滞があれば 3 分、無ければ 5 分
 *  - JCT で別路線に入った・分岐で予想と違う方へ進んだなど、いまの前方経路が前回の問い合わせ範囲を外れたら、
 *    間隔を待たずに取り直す（最短 60 秒。v2.22 までは走った距離だけで判断していて、路線が変わっても
 *    次の取得まで（最長 5 分）新しい路線の渋滞が出なかった）
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
    /** 前回問い合わせた経路（外れたかどうかの判定用） */
    private var lastQuery: com.example.highwaymode.core.traffic.TrafficQuery? = null
    private var lastRouteCheckMs = 0L
    /** 取得元（同じ取得元・キー・範囲なら使い回す。TomTom は前回の一覧を覚えておくため） */
    private var cachedProvider: TrafficProvider? = null
    private var cachedProviderKey = ""
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
        val short = prefs.trafficShortRange
        val id = "$p|${key.hashCode()}|$short"
        cachedProvider?.let { if (cachedProviderKey == id) return it }
        val prov = when (p) {
            "here" -> HereTrafficProvider(key)
            // 道路の流れ（タイル）は前方 40km まで（通信量を抑える設定では 25km）
            "tomtom" -> TomTomTrafficProvider(key, if (short) 25_000.0 else TomTomTrafficProvider.DEFAULT_FLOW_RANGE_M)
            "google" -> GoogleRoutesTrafficProvider(key)
            else -> null
        }
        cachedProvider = prov
        cachedProviderKey = id
        return prov
    }

    /**
     * いまの前方経路の先 [ROUTE_CHECK_M] までが、前回問い合わせた経路から [ROUTE_OFF_M] 以上離れているか
     * （JCT で別の路線に入った・予想と違う分岐に進んだ）
     */
    private fun leftQueriedRoute(old: com.example.highwaymode.core.traffic.TrafficQuery, now: com.example.highwaymode.core.traffic.TrafficQuery): Boolean {
        val corridor = com.example.highwaymode.core.traffic.RouteCorridor(old)
        var checked = 0
        for (i in now.lat.indices) {
            if (now.dist[i] > ROUTE_CHECK_M) break
            checked++
            if (corridor.distanceTo(now.lat[i], now.lon[i], ROUTE_OFF_M) == null) return true
        }
        return checked == 0
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
        if (!backoffOk) return
        if (!(due || leftCoverage)) {
            // 路線が変わったか（30 秒ごと。前回の取得から 60 秒たっていれば取り直す）
            val old = lastQuery
            if (old != null && lastFetchMs != 0L && now - lastFetchMs > 60_000L && now - lastRouteCheckMs >= 30_000L) {
                lastRouteCheckMs = now
                EngineHost.trafficQuery(ROUTE_CHECK_M + 1000.0) { cur ->
                    handler.post {
                        if (running && cur != null && !cur.isEmpty && lastQuery === old && leftQueriedRoute(old, cur)) {
                            Log.i(TAG, "route left the queried range: refetch")
                            fetch(prov, now)
                        }
                    }
                }
            }
            return
        }
        val netProblem = RunDiagnostics.networkProblem(ctx)
        if (netProblem != null) {
            // 裏での通信が止められているときは、その旨を出す（メニュー →「動作状態」で確認・解除）
            EngineHost.post { it.trafficMessage = "渋滞情報: $netProblem" }
            return
        }
        fetch(prov, now)
    }

    private fun fetch(prov: TrafficProvider, now: Long) {
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
                    lastQuery = q
                    EngineHost.post {
                        it.setTraffic(data)
                        // 一部（事故・規制の一覧、または道路の流れ）だけ取得できなかったときは、その旨を出す
                        it.trafficMessage = data.warning?.let { w -> "渋滞情報: $w" }
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

    companion object {
        private const val TAG = "TrafficUpdater"
        /** 路線が変わったかを調べる長さ（前方）[m] */
        private const val ROUTE_CHECK_M = 5_000.0
        /** 前回の問い合わせ範囲からこれ以上離れたら「外れた」 [m] */
        private const val ROUTE_OFF_M = 150.0
    }
}

package com.example.highwaymode.data

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.highwaymode.core.graph.GraphBuilder
import com.example.highwaymode.core.graph.RoadGraph
import com.example.highwaymode.core.hwdb.HwdbStore
import com.example.highwaymode.core.hwdb.TileCodec
import com.example.highwaymode.core.net.OverpassHttp
import com.example.highwaymode.core.osm.OsmDataSet
import com.example.highwaymode.core.osm.OsmNode
import com.example.highwaymode.core.osm.OsmTileData
import com.example.highwaymode.core.osm.OverpassParser
import com.example.highwaymode.core.osm.OverpassQuery
import com.example.highwaymode.core.osm.TileId
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.abs

/**
 * 高速道路データのタイル読み込みと道路グラフの構築。
 *
 * 読み込み順:
 *  1. 端末内の地図データ（HWDB: アプリ同梱・ダウンロード・地方単位で作成）… 通信なし・数ミリ秒
 *  2. 以前オンラインで取得したタイルのキャッシュ（files/tiles/v2, 既定 60 日）
 *  3. Overpass API（設定で許可した場合）… 2 本並列・応答の遅いサーバーは別サーバーへヘッジ
 *  4. 通信できない場合は期限切れのキャッシュ
 * 利用者が登録したオービスもここでグラフに加える。
 */
class TileRepository(
    context: Context,
    private val prefs: Prefs,
    private val listener: Listener
) {
    interface Listener {
        /** 新しいグラフ（作業スレッドから呼ばれる） */
        fun onGraph(graph: RoadGraph)

        /** 取得状況（作業スレッドから呼ばれる） */
        fun onStatus(pending: Int, message: String?)
    }

    private val ctx = context.applicationContext
    private val cacheDir = File(ctx.filesDir, "tiles/v2")
    private val http = OverpassHttp({ prefs.endpoints }, hedgeAfterMs = 12_000, maxParallel = 2, readTimeoutMs = 180_000)
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "highway-tiles").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val netPool: ExecutorService = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "highway-overpass").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    private val lock = Any()
    private val loaded = LinkedHashMap<TileId, OsmTileData>()
    private val fromStore = HashSet<TileId>()
    private val inFlight = HashSet<TileId>()
    private val queue = LinkedHashSet<TileId>()
    private val retryAt = HashMap<TileId, Long>()
    private val failures = HashMap<TileId, Int>()
    private val missing = HashSet<TileId>()
    private var working = false
    private var lastWanted: List<TileId> = emptyList()
    private var message: String? = null
    private var lastBuildMs = 0L
    private var dirty = false
    private var extraCameras: List<OsmNode> = emptyList()

    /** 作業スレッドだけが触る */
    private var store: HwdbStore? = null
    private var storeLoaded = false

    private val dataListener = HwdbManager.Listener { reloadStore() }

    init {
        HwdbManager.addListener(dataListener)
    }

    /** エンジンからの要求（どのスレッドからでも可） */
    fun request(wanted: List<TileId>) {
        if (wanted.isEmpty()) return
        var start = false
        synchronized(lock) {
            lastWanted = wanted
            val now = SystemClock.elapsedRealtime()
            for (t in wanted) {
                if (t in loaded || t in queue || t in inFlight) continue
                if ((retryAt[t] ?: 0L) > now) continue
                queue.add(t)
            }
            // 要求順（現在地 → 前方 → 周辺）に並べ替え
            val ordered = wanted.filter { it in queue } + queue.filter { it !in wanted }
            queue.clear()
            queue.addAll(ordered)
            if (queue.isNotEmpty() && !working) {
                working = true
                start = true
            }
        }
        if (start) worker.execute { workLoop() }
        publishStatus()
    }

    /** 地図データ（HWDB）が更新されたので開き直す */
    fun reloadStore() {
        worker.execute {
            try {
                store?.close()
            } catch (_: Exception) {
            }
            store = null
            storeLoaded = false
            synchronized(lock) {
                // 地図データから読んだタイル・オンラインで取ったタイルとも読み直す（新しいデータを優先するため）
                loaded.clear()
                fromStore.clear()
                missing.clear()
                retryAt.clear()
                failures.clear()
                dirty = true
            }
            ensureStore()
            rebuild()
            publishStatus()
        }
    }

    /** 利用者が登録したオービス */
    fun setUserCameras(list: List<OsmNode>) {
        worker.execute {
            synchronized(lock) {
                extraCameras = list
                dirty = true
            }
            if (synchronized(lock) { loaded.isNotEmpty() }) rebuild()
        }
    }

    private fun ensureStore(): HwdbStore? {
        if (!storeLoaded) {
            storeLoaded = true
            store = try {
                HwdbManager.openStore(ctx).also { Log.i(TAG, "HWDB: ${it.files.size} files") }
            } catch (e: Exception) {
                Log.w(TAG, "HWDB open failed: ${e.message}")
                null
            }
        }
        return store
    }

    private fun workLoop() {
        while (true) {
            val t = synchronized(lock) {
                val x = queue.firstOrNull()
                if (x == null) working = false else queue.remove(x)
                x
            } ?: break
            // 1) 端末内の地図データ
            val st = ensureStore()
            val local: OsmTileData? = try {
                if (st != null && st.has(t)) st.read(t) else null
            } catch (e: Exception) {
                Log.w(TAG, "HWDB tile ${t.key()} failed: ${e.message}")
                null
            }
            if (local != null) {
                put(t, local, true)
                continue
            }
            // 2) キャッシュ
            val f = cacheFile(t)
            val maxAgeMs = prefs.cacheDays * 24L * 3600_000L
            if (f.exists() && System.currentTimeMillis() - f.lastModified() < maxAgeMs) {
                val d = readCache(f)
                if (d != null) {
                    put(t, d, false)
                    continue
                }
            }
            // 3) オンライン取得
            if (!prefs.onlineTiles) {
                val stale = if (f.exists()) readCache(f) else null
                if (stale != null) put(t, stale, false) else synchronized(lock) {
                    missing.add(t)
                    message = "この付近の地図データがありません（メニュー →「地図データ」で更新）"
                }
                continue
            }
            synchronized(lock) { inFlight.add(t) }
            netPool.execute { fetchOnline(t) }
            maybeRebuild()
        }
        maybeRebuild(force = true)
        publishStatus()
    }

    private fun fetchOnline(t: TileId) {
        try {
            val data = http.query(OverpassQuery.forTile(t)) { reader -> OverpassParser.parse(reader) }
            writeCache(cacheFile(t), data)
            worker.execute {
                synchronized(lock) {
                    inFlight.remove(t)
                    failures.remove(t)
                    message = null
                }
                put(t, data, false)
                maybeRebuild()
                publishStatus()
            }
        } catch (e: Exception) {
            Log.w(TAG, "tile ${t.key()} failed: ${e.message}")
            worker.execute {
                val stale = cacheFile(t).takeIf { it.exists() }?.let { readCache(it) }
                synchronized(lock) {
                    inFlight.remove(t)
                    val n = (failures[t] ?: 0) + 1
                    failures[t] = n
                    // 15s, 30s, 60s, ... 最大 5 分
                    val backoff = (15_000L shl minOf(n - 1, 4)).coerceAtMost(300_000L)
                    retryAt[t] = SystemClock.elapsedRealtime() + backoff
                    message = if (stale != null) "地図データ: 通信できないため保存済みのデータを使用中"
                    else "地図データを取得できません（通信状態を確認）: ${e.message?.take(50)}"
                }
                if (stale != null) put(t, stale, false)
                maybeRebuild()
                publishStatus()
            }
        }
    }

    private fun put(t: TileId, d: OsmTileData, local: Boolean) {
        synchronized(lock) {
            loaded[t] = d
            if (local) fromStore.add(t) else fromStore.remove(t)
            missing.remove(t)
            evictIfNeeded()
            dirty = true
        }
    }

    private fun maybeRebuild(force: Boolean = false) {
        val (pending, isDirty) = synchronized(lock) { (queue.size + inFlight.size) to dirty }
        if (!isDirty) return
        val now = SystemClock.elapsedRealtime()
        // 最初のグラフは早く渡す。以後は 4 秒ごと・待ちが無くなったら作り直す
        if (force || pending == 0 || lastBuildMs == 0L || now - lastBuildMs > 4000) rebuild()
    }

    private fun rebuild() {
        val (tiles, cams) = synchronized(lock) {
            dirty = false
            LinkedHashMap(loaded) to extraCameras
        }
        if (tiles.isEmpty()) return
        val t0 = SystemClock.elapsedRealtime()
        val graph = try {
            GraphBuilder.build(OsmDataSet.merge(tiles.values, cams), tiles.keys.toSet())
        } catch (e: Exception) {
            Log.e(TAG, "graph build failed", e)
            return
        }
        lastBuildMs = SystemClock.elapsedRealtime()
        Log.i(TAG, "graph built: ${tiles.size} tiles, ${graph.edges.size} edges in ${lastBuildMs - t0} ms")
        listener.onGraph(graph)
    }

    /** 最近要求されたタイル群から遠いものをメモリから外す（ディスクには残る） */
    private fun evictIfNeeded() {
        if (loaded.size <= MAX_TILES) return
        val center = lastWanted.firstOrNull() ?: return
        val victims = loaded.keys
            .filter { it !in lastWanted }
            .sortedByDescending { abs(it.iy - center.iy) + abs(it.ix - center.ix) }
        for (v in victims) {
            if (loaded.size <= MAX_TILES) break
            loaded.remove(v)
            fromStore.remove(v)
        }
    }

    private fun publishStatus() {
        val (pending, msg) = synchronized(lock) {
            val p = queue.size + inFlight.size
            p to (message ?: if (inFlight.isNotEmpty()) "地図データをオンラインで取得中（残り $p）" else null)
        }
        listener.onStatus(pending, msg)
    }

    fun clearCache() {
        worker.execute {
            cacheDir.listFiles()?.forEach { it.delete() }
            File(ctx.filesDir, "tiles/v1").deleteRecursively()
            synchronized(lock) {
                for (t in loaded.keys.filter { it !in fromStore }) loaded.remove(t)
                retryAt.clear()
                failures.clear()
                dirty = true
            }
        }
    }

    fun cacheSizeBytes(): Long = cacheDir.listFiles()?.sumOf { it.length() } ?: 0L

    /** 表示用: 読み込み済みタイル数と、そのうち地図データ（HWDB）から読んだ数 */
    fun loadedStats(): Pair<Int, Int> = synchronized(lock) { loaded.size to fromStore.size }

    /** このタイルは地図データが無い（端末の地図データに無く、オンライン取得もしない）と分かっている */
    fun hasNoData(t: TileId): Boolean = synchronized(lock) { t in missing }

    fun shutdown() {
        HwdbManager.removeListener(dataListener)
        worker.execute {
            try {
                store?.close()
            } catch (_: Exception) {
            }
        }
        worker.shutdown()
        netPool.shutdownNow()
        http.shutdown()
    }

    private fun cacheFile(t: TileId) = File(cacheDir, "${t.key()}.hwt")

    private fun readCache(f: File): OsmTileData? = try {
        GZIPInputStream(FileInputStream(f), 64 * 1024).use { TileCodec.decode(it.readBytes()) }
    } catch (e: Exception) {
        Log.w(TAG, "cache broken ${f.name}: ${e.message}")
        f.delete()
        null
    }

    private fun writeCache(f: File, d: OsmTileData) {
        try {
            cacheDir.mkdirs()
            val tmp = File(cacheDir, f.name + ".tmp")
            GZIPOutputStream(FileOutputStream(tmp), 64 * 1024).use { it.write(TileCodec.encode(d)) }
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        } catch (e: Exception) {
            Log.w(TAG, "cache write failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "TileRepository"
        private const val MAX_TILES = 40
    }
}

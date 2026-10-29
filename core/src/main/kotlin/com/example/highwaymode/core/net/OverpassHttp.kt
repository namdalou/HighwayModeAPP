package com.example.highwaymode.core.net

import java.io.IOException
import java.io.InputStreamReader
import java.io.Reader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Overpass API クライアント（ヘッジ要求つき）。
 *
 * 公開 Overpass サーバーは混雑すると応答開始まで数十秒〜数分待たされたり、429/504 を返したりする。
 *  1) まず優先サーバーに要求する
 *  2) hedgeAfterMs 経っても応答が始まらなければ、同じ要求を次のサーバーにも出す（同時に最大 maxParallel）
 *  3) 最初に応答が始まったサーバーを採用し、ほかの要求は切断する
 *  4) 429/5xx/通信エラーは即座に次のサーバーへ
 * 採用したサーバーは次回の優先サーバーになる。応答本文は文字列にせず逐次解析できる。
 */
class OverpassHttp(
    private val endpoints: () -> List<String>,
    val hedgeAfterMs: Long = 12_000,
    val maxParallel: Int = 2,
    val connectTimeoutMs: Int = 15_000,
    val readTimeoutMs: Int = 200_000
) {
    @Volatile
    private var preferred = 0

    private val executor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "overpass-" + COUNTER.incrementAndGet()).apply { isDaemon = true }
    }

    private sealed class Ev {
        class Failed(val idx: Int, val e: Exception) : Ev()
        class Claimed(val idx: Int) : Ev()
        class Done(val value: Any?) : Ev()
        class WinnerFailed(val e: Exception) : Ev()
    }

    /**
     * @param parse 応答本文の解析（例外を投げると要求全体の失敗）
     * @throws IOException すべてのサーバーで失敗した場合
     */
    fun <T> query(q: String, cancel: CancelToken? = null, onServer: ((String) -> Unit)? = null, parse: (Reader) -> T): T {
        val eps = endpoints()
        if (eps.isEmpty()) throw IOException("Overpass サーバーが設定されていません")
        val order = eps.indices.map { (preferred + it) % eps.size }
        val body = ("data=" + URLEncoder.encode(q, "UTF-8")).toByteArray(Charsets.UTF_8)
        val events = LinkedBlockingQueue<Ev>()
        val winner = AtomicInteger(-1)
        val conns = arrayOfNulls<HttpURLConnection>(eps.size)
        val lastError = AtomicReference<Exception?>(null)
        var launched = 0
        var active = 0
        var lastLaunchMs = 0L

        fun disconnectOthers(keep: Int) {
            for (i in conns.indices) if (i != keep) {
                try {
                    conns[i]?.disconnect()
                } catch (_: Exception) {
                }
            }
        }

        fun launch() {
            val idx = order[launched++]
            active++
            lastLaunchMs = System.currentTimeMillis()
            val url = eps[idx]
            executor.execute {
                var conn: HttpURLConnection? = null
                try {
                    conn = URI.create(url).toURL().openConnection() as HttpURLConnection
                    conns[idx] = conn
                    cancel?.register(conn)
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.connectTimeout = connectTimeoutMs
                    conn.readTimeout = readTimeoutMs
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    conn.setRequestProperty("User-Agent", Http.userAgent)
                    conn.setRequestProperty("Accept-Encoding", "gzip")
                    conn.setFixedLengthStreamingMode(body.size)
                    conn.outputStream.use { it.write(body) }
                    val code = conn.responseCode
                    if (code != 200) {
                        val err = try {
                            conn.errorStream?.let { Http.decode(conn, it).use { s -> Http.readText(s, 1500) } } ?: ""
                        } catch (_: IOException) {
                            ""
                        }
                        throw HttpStatusException(code, "HTTP $code ${Http.hostOf(url)} ${stripHtml(err).take(200)}")
                    }
                    if (!winner.compareAndSet(-1, idx)) {
                        conn.disconnect()
                        return@execute
                    }
                    events.add(Ev.Claimed(idx))
                    disconnectOthers(idx)
                    preferred = idx
                    onServer?.invoke(Http.hostOf(url))
                    try {
                        val value = InputStreamReader(Http.decode(conn, conn.inputStream), Charsets.UTF_8).use { parse(it) }
                        events.add(Ev.Done(value))
                    } catch (e: Exception) {
                        events.add(Ev.WinnerFailed(e))
                    }
                } catch (e: Exception) {
                    if (winner.get() == idx) events.add(Ev.WinnerFailed(e)) else events.add(Ev.Failed(idx, e))
                } finally {
                    conn?.let { cancel?.unregister(it) }
                    try {
                        conn?.disconnect()
                    } catch (_: Exception) {
                    }
                }
            }
        }

        launch()
        while (true) {
            if (cancel?.cancelled == true) {
                disconnectOthers(-1)
                throw CancelledException()
            }
            val ev = events.poll(500, TimeUnit.MILLISECONDS)
            if (ev == null) {
                val claimed = winner.get() >= 0
                if (!claimed && launched < order.size && active < maxParallel &&
                    System.currentTimeMillis() - lastLaunchMs >= hedgeAfterMs
                ) launch()
                continue
            }
            when (ev) {
                is Ev.Failed -> {
                    active--
                    lastError.set(ev.e)
                    if (winner.get() < 0) {
                        if (launched < order.size) launch()
                        else if (active == 0) throw asIo(lastError.get())
                    }
                }
                is Ev.Claimed -> Unit
                is Ev.Done -> {
                    @Suppress("UNCHECKED_CAST")
                    return ev.value as T
                }
                is Ev.WinnerFailed -> {
                    if (cancel?.cancelled == true) throw CancelledException()
                    throw asIo(ev.e)
                }
            }
        }
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    private fun asIo(e: Exception?): IOException = when (e) {
        null -> IOException("Overpass の取得に失敗しました")
        is IOException -> e
        else -> IOException(e.message ?: e.javaClass.simpleName, e)
    }

    companion object {
        private val COUNTER = AtomicInteger()

        val DEFAULT_ENDPOINTS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter"
        )

        private fun stripHtml(s: String): String = s.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()
    }
}

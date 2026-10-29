package com.example.highwaymode.core.net

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.CopyOnWriteArraySet
import java.util.zip.GZIPInputStream

/** 中断要求（ダウンロード・一括取得の「中止」ボタン用） */
class CancelToken {
    @Volatile
    var cancelled = false
        private set
    private val conns = CopyOnWriteArraySet<HttpURLConnection>()

    fun cancel() {
        cancelled = true
        for (c in conns) {
            try {
                c.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    fun register(c: HttpURLConnection) {
        conns.add(c)
        if (cancelled) c.disconnect()
    }

    fun unregister(c: HttpURLConnection) {
        conns.remove(c)
    }

    fun check() {
        if (cancelled) throw CancelledException()
    }
}

class CancelledException : IOException("中止しました")

class HttpStatusException(val code: Int, message: String) : IOException(message)

/**
 * HttpURLConnection の薄いラッパー（Android と JVM の両方で動く）。
 * gzip は自前で展開する（Accept-Encoding を明示すると Android でも自動展開されないため、挙動をそろえられる）。
 */
object Http {
    var userAgent: String = "HighwayMode/1.1 (+https://github.com/; personal navigation display)"

    class Request(
        val url: String,
        val method: String = "GET",
        val body: ByteArray? = null,
        val headers: Map<String, String> = emptyMap(),
        val connectTimeoutMs: Int = 15_000,
        val readTimeoutMs: Int = 60_000
    )

    /** レスポンス本文を処理する。2xx 以外は HttpStatusException */
    fun <T> execute(req: Request, cancel: CancelToken? = null, handle: (code: Int, conn: HttpURLConnection, body: InputStream) -> T): T {
        cancel?.check()
        val conn = URI.create(req.url).toURL().openConnection() as HttpURLConnection
        cancel?.register(conn)
        try {
            conn.requestMethod = req.method
            conn.connectTimeout = req.connectTimeoutMs
            conn.readTimeout = req.readTimeoutMs
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Accept-Encoding", "gzip")
            for ((k, v) in req.headers) conn.setRequestProperty(k, v)
            val b = req.body
            if (b != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(b.size)
                conn.outputStream.use { it.write(b) }
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = try {
                    conn.errorStream?.let { decode(conn, it).use { s -> readText(s, 2000) } } ?: ""
                } catch (_: IOException) {
                    ""
                }
                throw HttpStatusException(code, "HTTP $code ${hostOf(req.url)} ${err.replace(Regex("\\s+"), " ").take(300)}")
            }
            return decode(conn, conn.inputStream).use { handle(code, conn, it) }
        } catch (e: IOException) {
            if (cancel?.cancelled == true) throw CancelledException()
            throw e
        } finally {
            cancel?.unregister(conn)
            conn.disconnect()
        }
    }

    fun getText(url: String, headers: Map<String, String> = emptyMap(), cancel: CancelToken? = null, timeoutMs: Int = 30_000): String =
        execute(Request(url, headers = headers, readTimeoutMs = timeoutMs), cancel) { _, _, body -> readText(body, Int.MAX_VALUE) }

    fun postText(url: String, body: String, contentType: String, headers: Map<String, String> = emptyMap(), cancel: CancelToken? = null, timeoutMs: Int = 30_000): String =
        execute(
            Request(url, "POST", body.toByteArray(Charsets.UTF_8), headers + ("Content-Type" to contentType), readTimeoutMs = timeoutMs),
            cancel
        ) { _, _, b -> readText(b, Int.MAX_VALUE) }

    fun decode(conn: HttpURLConnection, s: InputStream): InputStream =
        if (conn.contentEncoding.equals("gzip", ignoreCase = true)) GZIPInputStream(s, 64 * 1024) else s

    fun readText(input: InputStream, max: Int): String {
        val out = ByteArrayOutputStream(64 * 1024)
        val buf = ByteArray(64 * 1024)
        while (out.size() < max) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toString("UTF-8")
    }

    fun hostOf(url: String): String = url.substringAfter("//").substringBefore('/')
}

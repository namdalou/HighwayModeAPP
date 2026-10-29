package com.example.highwaymode.core.hwdb

import com.example.highwaymode.core.json.JsonReader
import com.example.highwaymode.core.net.CancelToken
import com.example.highwaymode.core.net.CancelledException
import com.example.highwaymode.core.net.Http
import com.example.highwaymode.core.net.HttpStatusException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.security.MessageDigest

/** 配布用の目録（hwdb-manifest.json） */
class HwdbManifest(
    val format: Int,
    val dataVersion: String,
    val createdAt: String?,
    val description: String?,
    val files: List<FileEntry>
) {
    class FileEntry(
        val name: String,
        val url: String,
        val bytes: Long,
        val sha256: String,
        val coverage: String?,
        val prefectures: String?
    )

    val totalBytes: Long get() = files.sumOf { it.bytes }

    fun toJson(): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"format\": ").append(format).append(",\n")
        sb.append("  \"dataVersion\": ").append(q(dataVersion)).append(",\n")
        if (createdAt != null) sb.append("  \"createdAt\": ").append(q(createdAt)).append(",\n")
        if (description != null) sb.append("  \"description\": ").append(q(description)).append(",\n")
        sb.append("  \"license\": ").append(q("© OpenStreetMap contributors, ODbL 1.0")).append(",\n")
        sb.append("  \"files\": [\n")
        files.forEachIndexed { i, f ->
            sb.append("    {\"name\": ").append(q(f.name))
                .append(", \"url\": ").append(q(f.url))
                .append(", \"bytes\": ").append(f.bytes)
                .append(", \"sha256\": ").append(q(f.sha256))
            if (f.coverage != null) sb.append(", \"coverage\": ").append(q(f.coverage))
            if (f.prefectures != null) sb.append(", \"prefectures\": ").append(q(f.prefectures))
            sb.append("}").append(if (i < files.size - 1) ",\n" else "\n")
        }
        sb.append("  ]\n}\n")
        return sb.toString()
    }

    companion object {
        const val FORMAT = 1

        private fun q(s: String): String {
            val sb = StringBuilder("\"")
            for (c in s) {
                when {
                    c == '"' -> sb.append("\\\"")
                    c == '\\' -> sb.append("\\\\")
                    c == '\n' -> sb.append("\\n")
                    c.code < 0x20 -> sb.append(String.format("\\u%04x", c.code))
                    else -> sb.append(c)
                }
            }
            return sb.append('"').toString()
        }

        /** @param baseUrl 相対 URL の基準（目録の URL） */
        fun parse(json: String, baseUrl: String? = null): HwdbManifest {
            val r = JsonReader(json)
            var format = 0
            var version = ""
            var created: String? = null
            var desc: String? = null
            val files = ArrayList<FileEntry>()
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "format" -> format = r.nextLong().toInt()
                    "dataVersion" -> version = r.nextString()
                    "createdAt" -> created = r.nextString()
                    "description" -> desc = r.nextString()
                    "files" -> {
                        r.beginArray()
                        while (r.hasNext()) {
                            var name = ""
                            var url = ""
                            var bytes = -1L
                            var sha = ""
                            var cov: String? = null
                            var prefs: String? = null
                            r.beginObject()
                            while (r.hasNext()) {
                                when (r.nextName()) {
                                    "name" -> name = r.nextString()
                                    "url" -> url = r.nextString()
                                    "bytes" -> bytes = r.nextLong()
                                    "sha256" -> sha = r.nextString().lowercase()
                                    "coverage" -> cov = r.nextString()
                                    "prefectures" -> prefs = r.nextString()
                                    else -> r.skipValue()
                                }
                            }
                            r.endObject()
                            if (name.isEmpty() || !name.matches(Regex("[A-Za-z0-9._-]+\\.hwdb"))) throw IOException("目録のファイル名が不正です: $name")
                            if (sha.length != 64) throw IOException("目録に SHA-256 がありません: $name")
                            val abs = if (baseUrl != null && !url.startsWith("http")) URI(baseUrl).resolve(url.ifEmpty { name }).toString() else url
                            files.add(FileEntry(name, abs, bytes, sha, cov, prefs))
                        }
                        r.endArray()
                    }
                    else -> r.skipValue()
                }
            }
            r.endObject()
            if (format != FORMAT) throw IOException("未対応の目録形式 $format（アプリを更新してください）")
            if (files.isEmpty()) throw IOException("目録にファイルがありません")
            return HwdbManifest(format, version, created, desc, files)
        }

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * HWDB の更新ダウンロード。
 *  - 目録（数百バイト）だけ取得して版を比較し、新しいときだけ本体を取得する
 *  - 途中で切れても .part から再開（HTTP Range）
 *  - SHA-256 と全タイルの CRC を確認してから差し替える
 */
class HwdbDownloader(private val destDir: File) {

    class Progress(val fileName: String, val doneBytes: Long, val totalBytes: Long, val message: String)

    fun fetchManifest(manifestUrl: String, cancel: CancelToken? = null): HwdbManifest {
        val text = Http.getText(manifestUrl, headers = mapOf("Cache-Control" to "no-cache"), cancel = cancel)
        return HwdbManifest.parse(text, manifestUrl)
    }

    /**
     * @return 書き込んだ（または既に最新だった）ファイル
     */
    fun download(manifest: HwdbManifest, cancel: CancelToken? = null, progress: (Progress) -> Unit = {}): List<File> {
        destDir.mkdirs()
        val out = ArrayList<File>()
        val total = manifest.totalBytes
        var doneBefore = 0L
        for (f in manifest.files) {
            val dest = File(destDir, f.name)
            if (dest.exists() && (f.bytes < 0 || dest.length() == f.bytes) && HwdbManifest.sha256(dest) == f.sha256) {
                out.add(dest)
                doneBefore += f.bytes.coerceAtLeast(0)
                continue
            }
            val part = File(destDir, f.name + ".part")
            var attempt = 0
            while (true) {
                cancel?.check()
                try {
                    fetchResumable(f, part, cancel) { done ->
                        progress(Progress(f.name, doneBefore + done, total, "ダウンロード中"))
                    }
                    break
                } catch (e: CancelledException) {
                    throw e
                } catch (e: IOException) {
                    attempt++
                    if (attempt >= 4) throw e
                    progress(Progress(f.name, doneBefore + part.length(), total, "再接続中（${attempt}回目）: ${e.message?.take(60)}"))
                    Thread.sleep(2000L * attempt)
                }
            }
            progress(Progress(f.name, doneBefore + part.length(), total, "検証中"))
            val sha = HwdbManifest.sha256(part)
            if (sha != f.sha256) {
                part.delete()
                throw IOException("${f.name} の SHA-256 が一致しません（配布ファイルが更新された可能性。再度お試しください）")
            }
            HwdbFile.open(part).use { it.verifyAll() }
            if (dest.exists() && !dest.delete()) throw IOException("${dest.name} を置き換えられません")
            if (!part.renameTo(dest)) throw IOException("${dest.name} を保存できません")
            doneBefore += f.bytes.coerceAtLeast(part.length())
            out.add(dest)
        }
        return out
    }

    private fun fetchResumable(f: HwdbManifest.FileEntry, part: File, cancel: CancelToken?, onProgress: (Long) -> Unit) {
        var have = if (part.exists()) part.length() else 0L
        if (f.bytes in 1..have) {
            if (have == f.bytes) return
            part.delete()
            have = 0
        }
        val headers = if (have > 0) mapOf("Range" to "bytes=$have-") else emptyMap()
        Http.execute(Http.Request(f.url, headers = headers, readTimeoutMs = 60_000), cancel) { code, _, body ->
            val append = have > 0 && code == 206
            if (!append) have = 0
            FileOutputStream(part, append).use { fos ->
                val buf = ByteArray(128 * 1024)
                var done = have
                var lastReport = 0L
                while (true) {
                    if (cancel?.cancelled == true) throw CancelledException()
                    val n = body.read(buf)
                    if (n < 0) break
                    fos.write(buf, 0, n)
                    done += n
                    if (done - lastReport > 256 * 1024) {
                        lastReport = done
                        onProgress(done)
                    }
                }
                fos.fd.sync()
                onProgress(done)
            }
        }
        if (f.bytes > 0 && part.length() != f.bytes) {
            if (part.length() > f.bytes) part.delete()
            throw IOException("${f.name}: 受信サイズが一致しません（${part.length()} / ${f.bytes}）")
        }
    }

    companion object {
        fun isNotFound(e: Exception): Boolean = e is HttpStatusException && e.code == 404
    }
}

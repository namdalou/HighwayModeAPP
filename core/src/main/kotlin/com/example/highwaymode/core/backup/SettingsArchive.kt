package com.example.highwaymode.core.backup

import com.example.highwaymode.core.json.JsonReader

/**
 * 設定の書き出し・読み込み（v2.19）のファイル（JSON）。
 *
 * 機種変更・アプリの入れ直し（Google Play 版と、Google Play 以外から入れた版を切り替えるときなど。署名が違うので
 * アンインストールが必要で、端末内の設定は消える）のときに、設定・スペアナの見た目・渋滞情報の API キー・
 * 登録したオービスを、利用者が選んだファイル経由で引き継ぐ。
 *
 * ```
 * {
 *   "app": "spectrum-highway",
 *   "format": 1,
 *   "appVersion": "2.19.0",
 *   "exportedAt": "2026-11-17T12:00:00Z",
 *   "secrets": true,
 *   "prefs": {
 *     "highway_mode": { "display_mode": { "type": "int", "value": 1 }, ... },
 *     "spectrum": { "bar_visual": { "type": "int", "value": 5 }, ... }
 *   },
 *   "cameras": "35.1,137.2,180,100,登録地点\n..."
 * }
 * ```
 *  - 値は型つき（文字列・整数・長整数・小数・真偽・文字列の集まり）。小数は文字列で持つ（読み書きで値が変わらないように）
 *  - API キー（[isSecretKey]）は、書き出すときに含めないこともできる（[secrets] = false）
 *  - 知らない項目は読み飛ばす（新しい版で項目が増えても、古い版で読める）。[FORMAT] より新しい形は読まない
 */
class SettingsArchive(
    val appVersion: String,
    val exportedAt: String,
    /** API キーを含むか */
    val secrets: Boolean,
    /** 設定のファイルの名前 → (項目の名前 → 値) */
    val prefs: Map<String, Map<String, PrefValue>>,
    /** 登録したオービス（UserCameras の CSV）。無ければ null */
    val camerasCsv: String?
) {

    /** 設定の 1 項目の値 */
    sealed class PrefValue {
        data class Str(val v: String) : PrefValue()
        data class Int32(val v: Int) : PrefValue()
        data class Int64(val v: Long) : PrefValue()
        data class Real(val v: Float) : PrefValue()
        data class Bool(val v: Boolean) : PrefValue()
        data class StrSet(val v: Set<String>) : PrefValue()

        companion object {
            /** SharedPreferences.getAll() の値から作る（扱えない型は null） */
            fun of(any: Any?): PrefValue? = when (any) {
                is String -> Str(any)
                is Int -> Int32(any)
                is Long -> Int64(any)
                is Float -> Real(any)
                is Boolean -> Bool(any)
                is Set<*> -> StrSet(any.filterIsInstance<String>().toSet())
                else -> null
            }
        }
    }

    /** 読み込めないファイル（利用者向けの説明つき） */
    class ArchiveException(message: String) : Exception(message)

    /** API キーを除いた写し */
    fun withoutSecrets(): SettingsArchive = SettingsArchive(
        appVersion, exportedAt, false,
        prefs.mapValues { (file, m) -> m.filterKeys { !isSecretKey(file, it) } },
        camerasCsv
    )

    /** 設定の項目の数（API キーを除く） */
    fun settingCount(): Int = prefs.entries.sumOf { (file, m) -> m.keys.count { !isSecretKey(file, it) } }

    /** API キーの数（空のものを除く） */
    fun secretCount(): Int = prefs.entries.sumOf { (file, m) ->
        m.entries.count { (k, v) -> isSecretKey(file, k) && v is PrefValue.Str && v.v.isNotBlank() }
    }

    fun toJson(): String {
        val sb = StringBuilder(4096)
        sb.append("{\n")
        sb.append("  \"app\": ").append(quote(APP)).append(",\n")
        sb.append("  \"format\": ").append(FORMAT).append(",\n")
        sb.append("  \"appVersion\": ").append(quote(appVersion)).append(",\n")
        sb.append("  \"exportedAt\": ").append(quote(exportedAt)).append(",\n")
        sb.append("  \"secrets\": ").append(secrets).append(",\n")
        sb.append("  \"prefs\": {")
        var firstFile = true
        for ((file, values) in prefs.toSortedMap()) {
            sb.append(if (firstFile) "\n" else ",\n")
            firstFile = false
            sb.append("    ").append(quote(file)).append(": {")
            var first = true
            for ((key, v) in values.toSortedMap()) {
                sb.append(if (first) "\n" else ",\n")
                first = false
                sb.append("      ").append(quote(key)).append(": ")
                writeValue(sb, v)
            }
            sb.append(if (first) "}" else "\n    }")
        }
        sb.append(if (firstFile) "}" else "\n  }")
        if (camerasCsv != null) sb.append(",\n  \"cameras\": ").append(quote(camerasCsv))
        sb.append("\n}\n")
        return sb.toString()
    }

    private fun writeValue(sb: StringBuilder, v: PrefValue) {
        when (v) {
            is PrefValue.Str -> sb.append("{ \"type\": \"string\", \"value\": ").append(quote(v.v)).append(" }")
            is PrefValue.Int32 -> sb.append("{ \"type\": \"int\", \"value\": ").append(v.v).append(" }")
            is PrefValue.Int64 -> sb.append("{ \"type\": \"long\", \"value\": ").append(v.v).append(" }")
            // 小数は文字列で（Float の文字列はその値を正確に表すので、読み戻すと同じ値になる）
            is PrefValue.Real -> sb.append("{ \"type\": \"float\", \"value\": ").append(quote(v.v.toString())).append(" }")
            is PrefValue.Bool -> sb.append("{ \"type\": \"boolean\", \"value\": ").append(v.v).append(" }")
            is PrefValue.StrSet -> {
                sb.append("{ \"type\": \"stringSet\", \"value\": [")
                v.v.sorted().forEachIndexed { i, s ->
                    if (i > 0) sb.append(", ")
                    sb.append(quote(s))
                }
                sb.append("] }")
            }
        }
    }

    companion object {
        /** ファイルの目印 */
        const val APP = "spectrum-highway"

        /** ファイルの形の版（項目の意味を変えるときに上げる） */
        const val FORMAT = 1

        /** 書き出す設定のファイル（SharedPreferences の名前）。読み込みもこれだけを受け付ける */
        val PREF_FILES = listOf("highway_mode", "spectrum")

        /** API キー（渋滞情報の取得元ごと）の項目か */
        fun isSecretKey(file: String, key: String): Boolean = file == "highway_mode" && key.startsWith("traffic_key_")

        /**
         * ファイルを読む。このアプリのファイルでない・新しすぎる・壊れているときは [ArchiveException]。
         * [PREF_FILES] に無い設定のファイルは読み飛ばす。
         */
        fun parse(json: String): SettingsArchive {
            val r = JsonReader(json.removePrefix("﻿"))
            var app: String? = null
            var format = -1
            var appVersion = ""
            var exportedAt = ""
            var secrets = false
            val prefs = LinkedHashMap<String, Map<String, PrefValue>>()
            var cameras: String? = null
            try {
                if (r.peek() != JsonReader.Token.BEGIN_OBJECT) throw ArchiveException(NOT_OURS)
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "app" -> app = r.nextString()
                        "format" -> format = r.nextLong().toInt()
                        "appVersion" -> appVersion = r.nextString()
                        "exportedAt" -> exportedAt = r.nextString()
                        "secrets" -> secrets = r.nextBoolean()
                        "prefs" -> {
                            r.beginObject()
                            while (r.hasNext()) {
                                val file = r.nextName()
                                val values = readValues(r)
                                if (file in PREF_FILES) prefs[file] = values
                            }
                            r.endObject()
                        }
                        "cameras" -> cameras = if (r.peek() == JsonReader.Token.NULL) {
                            r.nextNull()
                            null
                        } else r.nextString()
                        else -> r.skipValue()
                    }
                }
                r.endObject()
            } catch (e: ArchiveException) {
                throw e
            } catch (e: Exception) {
                // 目印が読めていれば「壊れている」、読めていなければ「このアプリのファイルではない」
                throw ArchiveException(if (app == APP) BROKEN else NOT_OURS)
            }
            if (app != APP) throw ArchiveException(NOT_OURS)
            if (format > FORMAT) throw ArchiveException("新しい版のアプリで書き出したファイルです。アプリを更新してから読み込んでください。")
            if (format < 1) throw ArchiveException(BROKEN)
            return SettingsArchive(appVersion, exportedAt, secrets, prefs, cameras)
        }

        private const val NOT_OURS = "このアプリで書き出した設定のファイルではありません。"
        private const val BROKEN = "設定のファイルが壊れているため読み込めません。"

        /** { "項目": { "type": ..., "value": ... }, ... } を読む（知らない型の項目は読み飛ばす） */
        private fun readValues(r: JsonReader): Map<String, PrefValue> {
            val out = LinkedHashMap<String, PrefValue>()
            r.beginObject()
            while (r.hasNext()) {
                val key = r.nextName()
                var type: String? = null
                // "type" の後に "value" が来る前提にしない（どちらの順でも読む）
                var rawText: String? = null
                var rawBool: Boolean? = null
                var rawSet: Set<String>? = null
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "type" -> type = r.nextString()
                        "value" -> when (r.peek()) {
                            JsonReader.Token.BOOLEAN -> rawBool = r.nextBoolean()
                            JsonReader.Token.BEGIN_ARRAY -> {
                                val s = LinkedHashSet<String>()
                                r.beginArray()
                                while (r.hasNext()) s.add(r.nextString())
                                r.endArray()
                                rawSet = s
                            }
                            JsonReader.Token.NULL -> r.nextNull()
                            else -> rawText = r.nextString()
                        }
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                val value = when (type) {
                    "string" -> rawText?.let { PrefValue.Str(it) }
                    "int" -> rawText?.toLongOrNull()?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) PrefValue.Int32(it.toInt()) else null }
                    "long" -> rawText?.toLongOrNull()?.let { PrefValue.Int64(it) }
                    "float" -> rawText?.toFloatOrNull()?.let { PrefValue.Real(it) }
                    "boolean" -> rawBool?.let { PrefValue.Bool(it) }
                    "stringSet" -> rawSet?.let { PrefValue.StrSet(it) }
                    else -> null
                }
                if (value != null) out[key] = value
            }
            r.endObject()
            return out
        }

        /** JSON の文字列にする（制御文字・行区切りは \\u で表す） */
        fun quote(s: String): String {
            val sb = StringBuilder(s.length + 2)
            sb.append('"')
            for (c in s) {
                when {
                    c == '"' -> sb.append("\\\"")
                    c == '\\' -> sb.append("\\\\")
                    c == '\n' -> sb.append("\\n")
                    c == '\r' -> sb.append("\\r")
                    c == '\t' -> sb.append("\\t")
                    c < ' ' || c == '\u2028' || c == '\u2029' -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                    else -> sb.append(c)
                }
            }
            sb.append('"')
            return sb.toString()
        }
    }
}

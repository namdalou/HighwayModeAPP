package com.example.spectrum

import android.content.Context
import android.content.pm.PackageManager
import android.os.Debug
import android.os.ParcelFileDescriptor

/**
 * 音声の番号(オーディオセッション)を自分から知らせないアプリ(YouTube・らじるらじる など)用。
 * Android の内部情報(dumpsys audio)から、いま再生中のアプリと音声の番号の組を読み取る。
 *
 * 必要な権限 DUMP は普通のアプリには与えられないため、パソコンから 1 回だけ許可する:
 *   adb shell pm grant <アプリのID> android.permission.DUMP
 * (イコライザーアプリの RootlessJamesDSP などと同じ方法)
 */
object SessionScanner {

    private const val PERMISSION = "android.permission.DUMP"

    /** 直近に読み取った結果(パッケージ名 → 音声の番号)。診断画面用 */
    @Volatile var lastResult: Map<String, Int> = emptyMap()
        private set

    /** 直近の読み取りで起きたこと(診断画面用) */
    @Volatile var lastStatus = "未実行"
        private set

    private val sessionRe = Regex("sessionId:(\\d+)")
    private val uidRe = Regex("u/pid:(\\d+)/")

    fun hasPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** 再生中(started)の音声を探して「パッケージ名 → 音声の番号」を返す */
    fun scan(ctx: Context): Map<String, Int> {
        if (!hasPermission(ctx) && !ShizukuHelper.isRunning()) {
            lastStatus = "DUMP 権限がありません"
            lastResult = emptyMap()
            return lastResult
        }
        // 読み取り方法を順に試す(機種によって通る方法が違うため)
        val errors = mutableListOf<String>()
        var method = ""
        var text: String? = null
        for ((name, reader) in listOf<Pair<String, () -> String?>>(
            "dumpsys" to { dumpViaProcess(errors) },
            "パイプ" to { dumpViaPipe(errors) },
            "Shizuku" to { dumpViaShizuku(errors) }
        )) {
            val t = reader() ?: continue
            if (!looksValid(t)) {
                errors += "$name: 内容なし(${t.lineSequence().firstOrNull()?.take(60) ?: ""})"
                continue
            }
            text = t
            method = name
            break
        }
        if (text == null) {
            lastStatus = "読み取り失敗: " + errors.joinToString(" / ")
            return lastResult
        }

        val result = LinkedHashMap<String, Int>()
        val pm = ctx.packageManager
        // 再生中の音声 1 件ごとに "AudioPlaybackConfiguration piid:..." で始まる
        for (chunk in text.split("AudioPlaybackConfiguration piid:").drop(1)) {
            val entry = chunk.take(800)
            if (!entry.contains("state:started")) continue
            // 通知音・ナビ音声・通話などは除く
            if (entry.contains("USAGE_NOTIFICATION") || entry.contains("USAGE_ASSISTANCE") ||
                entry.contains("USAGE_VOICE_COMMUNICATION") || entry.contains("USAGE_ALARM")
            ) continue
            val uid = uidRe.find(entry)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val sid = sessionRe.find(entry)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (sid <= 0) continue
            val pkgs = try { pm.getPackagesForUid(uid) } catch (_: Exception) { null } ?: continue
            for (p in pkgs) if (p != ctx.packageName) result.putIfAbsent(p, sid)
        }
        lastResult = result
        lastStatus = if (result.isEmpty()) "($method) 再生中の音声は見つかりませんでした" else "OK ($method)"
        return result
    }

    /** 再生情報が含まれているか(権限エラーの文言だけ返ってくることがある) */
    private fun looksValid(t: String): Boolean =
        t.contains("AudioPlaybackConfiguration") || t.contains("playback activity", ignoreCase = true)

    /** 1. dumpsys コマンドを実行する(パソコンの adb shell dumpsys audio と同じ) */
    private fun dumpViaProcess(errors: MutableList<String>): String? = try {
        val p = ProcessBuilder("dumpsys", "audio").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out
    } catch (e: Exception) {
        errors += "dumpsys: ${e.javaClass.simpleName}"
        null
    }

    /** 2. パイプでシステムから直接受け取る */
    private fun dumpViaPipe(errors: MutableList<String>): String? {
        return try {
            val pipe = ParcelFileDescriptor.createPipe()
            val readSide = pipe[0]
            val writeSide = pipe[1]
            var out = ""
            val reader = Thread {
                out = try {
                    ParcelFileDescriptor.AutoCloseInputStream(readSide).bufferedReader().readText()
                } catch (_: Exception) {
                    ""
                }
            }
            reader.start()
            try {
                Debug.dumpService("audio", writeSide.fileDescriptor, arrayOf())
            } finally {
                writeSide.close()
            }
            reader.join(3000)
            out
        } catch (e: Exception) {
            errors += "パイプ: ${e.javaClass.simpleName}${e.message?.let { "(" + it.take(60) + ")" } ?: ""}"
            null
        }
    }

    /** 3. Shizuku が起動していれば、Shizuku の権限で dumpsys を実行する */
    private fun dumpViaShizuku(errors: MutableList<String>): String? {
        if (!ShizukuHelper.isRunning()) {
            errors += "Shizuku: 停止中"
            return null
        }
        return ShizukuHelper.execOutput(arrayOf("dumpsys", "audio")) ?: run {
            errors += "Shizuku: 実行失敗"
            null
        }
    }
}

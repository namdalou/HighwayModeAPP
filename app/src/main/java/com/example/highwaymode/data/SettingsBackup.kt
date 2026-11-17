package com.example.highwaymode.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.example.highwaymode.BuildConfig
import com.example.highwaymode.core.backup.SettingsArchive
import com.example.highwaymode.core.backup.SettingsArchive.PrefValue
import com.example.highwaymode.dual.DualMode
import com.example.highwaymode.engine.EngineHost
import com.example.highwaymode.nav.NavNotifications
import com.example.spectrum.ShizukuHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 設定の書き出し・読み込み（v2.19。設定画面の「設定の引き継ぎ」）。
 *
 * アプリの更新では、設定・API キー・登録したオービスはそのまま残る（同じアプリ ID・同じ署名のため）。
 * Android の自動バックアップ（Google アカウント）でも、入れ直し・機種変更のときに戻る（AndroidManifest の backup の設定）。
 * ここは、それでも戻らないとき（Google Play 版と Google Play 以外から入れた版の切り替えは、署名が違うので
 * アンインストールが必要で、自動バックアップも戻らない）のために、利用者が選んだファイルへ書き出し・読み込みする。
 *
 * 対象: 設定（highway_mode）・スペアナの見た目と音声の設定（spectrum）・渋滞情報の API キー（含めないこともできる）・
 * 登録したオービス。端末ごとの一時的な情報（[LOCAL_ONLY]）は書き出さない。
 * Shizuku による「音声の読み取り」の許可そのものは移せないので、読み込んだあと [ShizukuHelper] が取り直しを案内する。
 */
object SettingsBackup {

    /** 書き出さない・読み込まない項目（端末ごとの一時的な情報） */
    private val LOCAL_ONLY = setOf(
        "hwdb_last_check", "hwdb_available", "auto_hint_shown_for",
        ShizukuHelper.KEY_DUMP_NOTICE
    )

    /** 読み込むファイルの大きさの上限（壊れた・別のファイルを選んだときに、メモリを使い切らないように） */
    private const val MAX_BYTES = 8L * 1024 * 1024

    class Result(val settings: Int, val secrets: Int, val camerasAdded: Int, val camerasInFile: Int)

    /** 書き出すファイルの名前（例: spectrum-highway-settings-20261117.json） */
    fun fileName(): String = "spectrum-highway-settings-" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) + ".json"

    /** いまの設定を、書き出す形にする */
    fun collect(ctx: Context, includeSecrets: Boolean): SettingsArchive {
        val c = ctx.applicationContext
        val prefs = LinkedHashMap<String, Map<String, PrefValue>>()
        for (name in SettingsArchive.PREF_FILES) {
            val m = LinkedHashMap<String, PrefValue>()
            for ((k, v) in c.getSharedPreferences(name, Context.MODE_PRIVATE).all) {
                if (k in LOCAL_ONLY) continue
                PrefValue.of(v)?.let { m[k] = it }
            }
            prefs[name] = m
        }
        val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val a = SettingsArchive(BuildConfig.VERSION_NAME, now, true, prefs, UserCameraStore(c).csvText())
        return if (includeSecrets) a else a.withoutSecrets()
    }

    /** ファイルに書き出す（呼ぶのは別スレッドで） */
    fun write(ctx: Context, uri: Uri, a: SettingsArchive) {
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(a.toJson().toByteArray(Charsets.UTF_8)) }
            ?: throw IllegalStateException("ファイルに書き込めません")
    }

    /** ファイルを読む（呼ぶのは別スレッドで）。このアプリの設定のファイルでなければ [SettingsArchive.ArchiveException] */
    fun read(ctx: Context, uri: Uri): SettingsArchive {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_BYTES) throw SettingsArchive.ArchiveException("このアプリで書き出した設定のファイルではありません。")
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } ?: throw IllegalStateException("ファイルを開けません")
        return SettingsArchive.parse(String(bytes, Charsets.UTF_8))
    }

    /**
     * 読み込んだ設定を当てはめる（呼ぶのはメインスレッドで。表示中の画面・常駐中のエンジンにもすぐ反映する）。
     * 設定はファイルの内容に置き換える（ファイルに API キーが無いときは、いまの API キーを残す）。
     * 登録したオービスは、いまの登録に足す（同じ地点は足さない）。
     */
    fun apply(ctx: Context, a: SettingsArchive): Result {
        val c = ctx.applicationContext
        var settings = 0
        for (name in SettingsArchive.PREF_FILES) {
            val values = a.prefs[name] ?: continue
            val sp = c.getSharedPreferences(name, Context.MODE_PRIVATE)
            val now = sp.all
            val e = sp.edit().clear()
            // 端末ごとの情報と、ファイルに入っていない API キーは、いまの値を残す
            for ((k, v) in now) {
                if (k in LOCAL_ONLY || (!a.secrets && SettingsArchive.isSecretKey(name, k))) putRaw(e, k, v)
            }
            for ((k, v) in values) {
                if (k in LOCAL_ONLY) continue
                put(e, k, v)
                if (!SettingsArchive.isSecretKey(name, k)) settings++
            }
            e.commit()
        }
        var added = 0
        var inFile = 0
        a.camerasCsv?.let { csv ->
            inFile = com.example.highwaymode.core.camera.UserCameras.parse(csv).entries.size
            added = UserCameraStore(c).mergeCsv(csv)
        }
        // 表示・常駐中の処理に反映する
        AppSettings.changed(c)
        DualMode.settingsChanged()
        NavNotifications.settingsChanged(c)
        EngineHost.reloadUserCameras()
        EngineHost.refreshTraffic()
        return Result(settings, a.secretCount(), added, inFile)
    }

    private fun put(e: SharedPreferences.Editor, k: String, v: PrefValue) {
        when (v) {
            is PrefValue.Str -> e.putString(k, v.v)
            is PrefValue.Int32 -> e.putInt(k, v.v)
            is PrefValue.Int64 -> e.putLong(k, v.v)
            is PrefValue.Real -> e.putFloat(k, v.v)
            is PrefValue.Bool -> e.putBoolean(k, v.v)
            is PrefValue.StrSet -> e.putStringSet(k, v.v)
        }
    }

    private fun putRaw(e: SharedPreferences.Editor, k: String, v: Any?) {
        PrefValue.of(v)?.let { put(e, k, it) }
    }
}

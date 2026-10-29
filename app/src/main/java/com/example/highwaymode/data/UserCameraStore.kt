package com.example.highwaymode.data

import android.content.Context
import android.net.Uri
import com.example.highwaymode.core.camera.UserCameras
import java.io.File

/**
 * 利用者が登録・取り込みしたオービス（files/user_cameras.csv）。
 * 形式は UserCameras（緯度,経度,進行方位,制限速度,名称）。
 */
class UserCameraStore(context: Context) {
    private val ctx = context.applicationContext
    private val file = File(ctx.filesDir, "user_cameras.csv")

    fun load(): List<UserCameras.Entry> =
        if (file.exists()) UserCameras.parse(file.readText(Charsets.UTF_8)).entries else emptyList()

    private fun save(list: List<UserCameras.Entry>) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(UserCameras.toCsv(list), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    /** 現在地に登録（進行方位つき） */
    fun add(lat: Double, lon: Double, bearing: Double?, name: String? = null): Int {
        val list = load().toMutableList()
        val e = UserCameras.Entry(lat, lon, bearing, null, name ?: "登録地点")
        if (list.none { it.id == e.id }) list.add(e)
        save(list)
        return list.size
    }

    /** CSV を取り込んで追加する。@return (追加件数, 読めなかった行数) */
    fun import(uri: Uri): Pair<Int, Int> {
        val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IllegalStateException("ファイルを開けません")
        val parsed = UserCameras.parse(text)
        val list = load().toMutableList()
        val ids = list.map { it.id }.toHashSet()
        var added = 0
        for (e in parsed.entries) if (ids.add(e.id)) {
            list.add(e)
            added++
        }
        save(list)
        return added to parsed.skipped
    }

    fun export(uri: Uri) {
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(UserCameras.toCsv(load()).toByteArray(Charsets.UTF_8)) }
            ?: throw IllegalStateException("ファイルに書き込めません")
    }

    /** 指定地点から radius [m] 以内の登録を消す。@return 消した件数 */
    fun removeNear(lat: Double, lon: Double, radius: Double = 150.0): Int {
        val list = load()
        val keep = list.filter { com.example.highwaymode.core.geo.Geo.distance(lat, lon, it.lat, it.lon) > radius }
        save(keep)
        return list.size - keep.size
    }

    fun clear() {
        file.delete()
    }

    fun count(): Int = load().size
}

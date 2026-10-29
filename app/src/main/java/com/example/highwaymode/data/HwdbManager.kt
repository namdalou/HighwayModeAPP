package com.example.highwaymode.data

import android.content.Context
import android.util.Log
import com.example.highwaymode.core.hwdb.HwdbFile
import com.example.highwaymode.core.hwdb.HwdbStore
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 端末内の地図データ（HWDB）の管理。
 *
 *  1. アプリ同梱データ: assets/hwdb の .hwdb ファイル（無圧縮で格納しているので APK から直接読む。コピー不要）
 *  2. ダウンロードしたデータ: files/hwdb/download/（「地図データを更新」で取得）
 *  3. 端末内で作成したデータ: files/hwdb/region/（Overpass から地方単位で作成）
 * 同じタイルを複数のファイルが持っている場合は、新しい版のファイルを優先する。
 */
object HwdbManager {
    private const val TAG = "HwdbManager"

    enum class Kind(val label: String) { BUNDLED("アプリ同梱"), DOWNLOADED("ダウンロード"), REGION("端末内で作成") }

    class Info(
        val kind: Kind,
        val name: String,
        val version: String,
        val createdAt: String?,
        val bytes: Long,
        val coverage: String?,
        val prefectures: String?,
        val tiles: Int,
        val file: File?
    )

    fun interface Listener {
        fun onDataChanged()
    }

    private val listeners = CopyOnWriteArraySet<Listener>()

    fun addListener(l: Listener) {
        listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    fun notifyChanged() {
        for (l in listeners) l.onDataChanged()
    }

    fun root(ctx: Context): File = File(ctx.filesDir, "hwdb")
    fun downloadDir(ctx: Context): File = File(root(ctx), "download").also { it.mkdirs() }
    fun regionDir(ctx: Context): File = File(root(ctx), "region").also { it.mkdirs() }

    /** 開ける HWDB をすべて開く（新しい版が先） */
    fun openAll(ctx: Context): List<Pair<Kind, HwdbFile>> {
        val out = ArrayList<Pair<Kind, HwdbFile>>()
        // ---- 同梱データ
        val names = try {
            ctx.assets.list("hwdb")?.filter { it.endsWith(".hwdb") } ?: emptyList()
        } catch (e: IOException) {
            emptyList()
        }
        for (n in names) {
            val path = "hwdb/$n"
            try {
                val afd = ctx.assets.openFd(path)
                val apk = File(ctx.applicationInfo.sourceDir)
                val f = HwdbFile.open(apk, afd.startOffset, afd.length, n)
                afd.close()
                out.add(Kind.BUNDLED to f)
            } catch (e: FileNotFoundException) {
                // 圧縮されて格納されている（noCompress 設定漏れ）→ 一度だけコピーして使う
                try {
                    val copy = File(root(ctx), "bundled-$n")
                    val marker = File(root(ctx), "bundled-$n.ver")
                    val ver = appVersionMarker(ctx)
                    if (!copy.exists() || !marker.exists() || marker.readText() != ver) {
                        root(ctx).mkdirs()
                        val tmp = File(copy.path + ".tmp")
                        ctx.assets.open(path).use { input -> FileOutputStream(tmp).use { input.copyTo(it, 256 * 1024) } }
                        copy.delete()
                        tmp.renameTo(copy)
                        marker.writeText(ver)
                    }
                    out.add(Kind.BUNDLED to HwdbFile.open(copy, name = n))
                } catch (e2: Exception) {
                    Log.w(TAG, "bundled $n unusable: ${e2.message}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "bundled $n unusable: ${e.message}")
            }
        }
        // ---- ダウンロード・端末内作成
        for ((kind, dir) in listOf(Kind.DOWNLOADED to downloadDir(ctx), Kind.REGION to regionDir(ctx))) {
            for (f in dir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".hwdb") }) {
                try {
                    out.add(kind to HwdbFile.open(f))
                } catch (e: Exception) {
                    Log.w(TAG, "broken ${f.name}: ${e.message}")
                }
            }
        }
        // 新しい版を優先（同じ版なら全国版を先に）
        return out.sortedWith(compareByDescending<Pair<Kind, HwdbFile>> { it.second.dataVersion }.thenByDescending { it.second.coversAll })
    }

    fun openStore(ctx: Context): HwdbStore = HwdbStore(openAll(ctx).map { it.second })

    fun list(ctx: Context): List<Info> {
        val all = openAll(ctx)
        val out = all.map { (k, f) ->
            Info(k, f.name, f.dataVersion, f.createdAt, f.byteLength, f.meta[HwdbFile.META_COVERAGE], f.meta[HwdbFile.META_PREFECTURES], f.tileCount,
                if (k == Kind.BUNDLED) null else f.file)
        }
        for ((_, f) in all) f.close()
        return out
    }

    /** 最も新しい全国データの版（無ければ null） */
    fun newestNationalVersion(ctx: Context): String? = list(ctx).filter { it.coverage == "all" }.maxOfOrNull { it.version }

    fun delete(info: Info): Boolean {
        val f = info.file ?: return false
        val ok = f.delete()
        notifyChanged()
        return ok
    }

    fun totalBytes(ctx: Context): Long = list(ctx).filter { it.kind != Kind.BUNDLED }.sumOf { it.bytes }

    private fun appVersionMarker(ctx: Context): String {
        return try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            "${pi.lastUpdateTime}"
        } catch (e: Exception) {
            "0"
        }
    }
}

package com.example.highwaymode.datatool

import com.example.highwaymode.core.graph.BypassClassifier
import com.example.highwaymode.core.hwdb.DatasetBuilder
import com.example.highwaymode.core.hwdb.HwdbFile
import com.example.highwaymode.core.hwdb.HwdbManifest
import com.example.highwaymode.core.hwdb.OverpassDatasetBuilder
import com.example.highwaymode.core.hwdb.Prefectures
import com.example.highwaymode.core.net.Http
import com.example.highwaymode.core.net.OverpassHttp
import com.example.highwaymode.core.osm.OsmDataSet
import com.example.highwaymode.core.osm.OsmTileData
import java.io.File
import kotlin.system.exitProcess

/**
 * 地図データ（HWDB）作成ツール。
 *
 *  osm      --in located.osm.gz --out japan-highways.hwdb
 *           osmium で絞り込んだ OSM 抽出（Geofabrik の japan-latest.osm.pbf 等）から全国データを作る（推奨・最速）
 *  overpass --regions all|kanto,chubu|JP-23,... --out japan-highways.hwdb [--endpoint URL]...
 *           Overpass API から都道府県単位で取得して作る（osmium 不要。全国で 30〜90 分程度）
 *  manifest --base-url URL --out hwdb-manifest.json file.hwdb...
 *           アプリの「地図データを更新」が読む目録を作る
 *  info     file.hwdb...
 *           内容と検証結果を表示
 */
fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] in setOf("help", "-h", "--help")) {
        usage()
        return
    }
    val cmd = args[0]
    val opts = Opts(args.drop(1))
    try {
        when (cmd) {
            "osm" -> buildFromOsm(opts)
            "overpass" -> buildFromOverpass(opts)
            "manifest" -> writeManifest(opts)
            "info" -> info(opts)
            else -> {
                usage()
                exitProcess(2)
            }
        }
    } catch (e: Exception) {
        System.err.println("エラー: ${e.message}")
        e.printStackTrace()
        exitProcess(1)
    }
}

private fun usage() {
    println(
        """
        HighwayMode 地図データ作成ツール

          osm      --in located.osm.gz --out japan-highways.hwdb
          overpass --regions all --out japan-highways.hwdb [--endpoint URL]...
                   地域: all / hokkaido tohoku kanto chubu kinki chugoku shikoku kyushu / JP-23 など（カンマ区切り）
          manifest --base-url https://github.com/USER/REPO/releases/download/TAG/ --out hwdb-manifest.json japan-highways.hwdb
          info     japan-highways.hwdb
        """.trimIndent()
    )
}

private class Opts(args: List<String>) {
    val values = HashMap<String, MutableList<String>>()
    val positional = ArrayList<String>()

    init {
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (a.startsWith("--")) {
                val v = args.getOrNull(i + 1) ?: throw IllegalArgumentException("$a の値がありません")
                values.getOrPut(a.removePrefix("--")) { ArrayList() }.add(v)
                i += 2
            } else {
                positional.add(a)
                i++
            }
        }
    }

    fun get(k: String): String? = values[k]?.lastOrNull()
    fun req(k: String): String = get(k) ?: throw IllegalArgumentException("--$k を指定してください")
    fun all(k: String): List<String> = values[k] ?: emptyList()
}

private fun buildFromOsm(o: Opts) {
    val input = File(o.req("in"))
    val out = File(o.req("out"))
    out.absoluteFile.parentFile?.mkdirs()
    val t0 = System.currentTimeMillis()
    val raw = OsmXmlLoader(input).load()
    val ds = BypassClassifier.filter(OsmDataSet.merge(listOf(raw)), markBypass = true)
    val bypass = ds.roads.values.count { it.tags[BypassClassifier.TAG] == BypassClassifier.TAG_BYPASS }
    println("主要バイパス判定: 道路 ${raw.roads.size} → ${ds.roads.size}（うち一般国道のバイパス区間 $bypass）")
    val b = DatasetBuilder()
    b.add(OsmTileData(ds.roads.values.toList(), ds.junctions.values.toList(), ds.tollBooths.values.toList(), ds.restAreas.values.toList(), ds.pois.values.toList(), ds.cameras.values.toList()))
    val regions = Prefectures.resolve(o.get("regions") ?: "all")
    val meta = b.write(out, regions, mapOf("builder" to "osm", "input" to input.name))
    println("作成: ${out.path} (${out.length() / 1024} KB, ${(System.currentTimeMillis() - t0) / 1000} 秒)")
    meta.forEach { (k, v) -> println("  $k = ${v.take(120)}") }
    verify(out)
}

private fun buildFromOverpass(o: Opts) {
    val out = File(o.req("out"))
    out.absoluteFile.parentFile?.mkdirs()
    val regions = Prefectures.resolve(o.get("regions") ?: "all")
    val eps = o.all("endpoint").ifEmpty { OverpassHttp.DEFAULT_ENDPOINTS }
    Http.userAgent = "HighwayMode-datatool/1.1 (OSM data builder)"
    val http = OverpassHttp({ eps }, hedgeAfterMs = 90_000, maxParallel = 2, readTimeoutMs = 900_000)
    val t0 = System.currentTimeMillis()
    val res = OverpassDatasetBuilder(http, retries = 4, retryWaitMs = 60_000).build(regions, out) { p ->
        println("[${p.done}/${p.total}] ${p.message}")
    }
    http.shutdown()
    println("作成: ${out.path} (${out.length() / 1024} KB, ${(System.currentTimeMillis() - t0) / 60000} 分)")
    if (res.failed.isNotEmpty()) {
        println("取得できなかった都道府県（アプリではその範囲だけオンライン取得になります）:")
        res.failed.forEach { println("  ${it.first.name}: ${it.second.take(150)}") }
    }
    verify(out)
}

private fun writeManifest(o: Opts) {
    val base = o.req("base-url").let { if (it.endsWith("/")) it else "$it/" }
    val out = File(o.req("out"))
    val files = o.positional.map { File(it) }
    if (files.isEmpty()) throw IllegalArgumentException("HWDB ファイルを指定してください")
    var version = ""
    var created: String? = null
    val entries = files.map { f ->
        val hw = HwdbFile.open(f)
        hw.use {
            if (it.dataVersion > version) {
                version = it.dataVersion
                created = it.createdAt
            }
            HwdbManifest.FileEntry(f.name, base + f.name, f.length(), HwdbManifest.sha256(f), it.meta[HwdbFile.META_COVERAGE], it.meta[HwdbFile.META_PREFECTURES])
        }
    }
    val m = HwdbManifest(HwdbManifest.FORMAT, version, created, o.get("description") ?: "全国の高速道路・有料道路・都市高速・主要バイパス（OpenStreetMap）", entries)
    out.absoluteFile.parentFile?.mkdirs()
    out.writeText(m.toJson(), Charsets.UTF_8)
    println(m.toJson())
}

private fun info(o: Opts) {
    for (p in o.positional) verify(File(p))
}

private fun verify(f: File) {
    HwdbFile.open(f).use { hw ->
        println("${f.name}: 版 ${hw.dataVersion}, 作成 ${hw.createdAt}, 範囲 ${hw.meta[HwdbFile.META_COVERAGE]}, タイル ${hw.tileCount}（データあり ${hw.nonEmptyTileCount}）")
        for (k in listOf("roads", "restAreas", "cameras", "partialTiles")) hw.meta[k]?.let { println("  $k = $it") }
        val t0 = System.currentTimeMillis()
        val n = hw.verifyAll()
        println("  検証 OK: $n タイル（${System.currentTimeMillis() - t0} ms）")
    }
}

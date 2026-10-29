package com.example.highwaymode.core.hwdb

import com.example.highwaymode.core.graph.BypassClassifier
import com.example.highwaymode.core.net.CancelToken
import com.example.highwaymode.core.net.CancelledException
import com.example.highwaymode.core.net.OverpassHttp
import com.example.highwaymode.core.osm.OsmDataSet
import com.example.highwaymode.core.osm.OsmTileData
import com.example.highwaymode.core.osm.OverpassParser
import com.example.highwaymode.core.osm.OverpassQuery
import java.io.File
import java.io.IOException

/**
 * Overpass API から都道府県単位でまとめて取得し、HWDB を作る（端末内・PC 用ツール共通）。
 *
 * タイル単位（約 22km 四方）で何百回も問い合わせるより、都道府県単位の 1 回の問い合わせの方が
 * サーバーの負担も待ち時間もずっと少ない。失敗した県は後回しにして再試行し、
 * それでも取れなければその県を除いたデータを作る（その県の範囲は従来どおりオンラインで取得される）。
 */
class OverpassDatasetBuilder(
    private val http: OverpassHttp,
    private val retries: Int = 3,
    private val retryWaitMs: Long = 20_000
) {
    class Progress(val done: Int, val total: Int, val message: String)

    class Result(val file: File, val meta: Map<String, String>, val succeeded: List<Prefectures.Pref>, val failed: List<Pair<Prefectures.Pref, String>>)

    fun build(
        prefectures: List<Prefectures.Pref>,
        out: File,
        cancel: CancelToken? = null,
        extraMeta: Map<String, String> = emptyMap(),
        progress: (Progress) -> Unit = {}
    ): Result {
        val builder = DatasetBuilder()
        val ok = ArrayList<Prefectures.Pref>()
        val failed = ArrayList<Pair<Prefectures.Pref, String>>()
        var pending = prefectures.toMutableList()
        var round = 0
        while (pending.isNotEmpty() && round < retries) {
            val next = ArrayList<Prefectures.Pref>()
            for (p in pending) {
                cancel?.check()
                progress(Progress(ok.size, prefectures.size, "${p.name} を取得中" + if (round > 0) "（再試行 $round）" else ""))
                try {
                    val q = OverpassQuery.forPrefecture(p.code)
                    var server = ""
                    val data: OsmTileData = http.query(q, cancel, { server = it }) { reader -> OverpassParser.parse(reader) }
                    if (data.roads.isEmpty()) throw IOException("${p.name}: 道路データが空です（サーバーの一時的な不具合の可能性）")
                    // 一般国道は IC のある区間だけを残してから蓄積する（メモリ節約）
                    val filtered = BypassClassifier.filter(OsmDataSet.merge(listOf(data)), markBypass = true)
                    builder.add(OsmTileData(filtered.roads.values.toList(), filtered.junctions.values.toList(), filtered.tollBooths.values.toList(),
                        filtered.restAreas.values.toList(), filtered.pois.values.toList(), filtered.cameras.values.toList()))
                    ok.add(p)
                    progress(Progress(ok.size, prefectures.size, "${p.name}: 道路 ${filtered.roads.size} 本（$server）"))
                } catch (e: CancelledException) {
                    throw e
                } catch (e: Exception) {
                    next.add(p)
                    if (round == retries - 1) failed.add(p to (e.message ?: e.javaClass.simpleName))
                    progress(Progress(ok.size, prefectures.size, "${p.name}: 失敗（${e.message?.take(80)}）"))
                }
            }
            pending = next
            round++
            if (pending.isNotEmpty() && round < retries) {
                progress(Progress(ok.size, prefectures.size, "${pending.size} 県を ${retryWaitMs / 1000} 秒後に再試行します"))
                val until = System.currentTimeMillis() + retryWaitMs * round
                while (System.currentTimeMillis() < until) {
                    cancel?.check()
                    Thread.sleep(500)
                }
            }
        }
        if (ok.isEmpty()) throw IOException("どの都道府県も取得できませんでした: " + failed.joinToString { "${it.first.name}: ${it.second.take(60)}" })
        progress(Progress(ok.size, prefectures.size, "ファイルを作成中"))
        val meta = builder.write(out, ok, extraMeta + mapOf("builder" to "overpass"))
        return Result(out, meta, ok, failed)
    }
}

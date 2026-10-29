package com.example.highwaymode.core

import com.example.highwaymode.core.facility.FacilityType
import com.example.highwaymode.core.graph.GraphBuilder
import com.example.highwaymode.core.hwdb.DatasetBuilder
import com.example.highwaymode.core.hwdb.HwdbDownloader
import com.example.highwaymode.core.hwdb.HwdbFile
import com.example.highwaymode.core.hwdb.HwdbManifest
import com.example.highwaymode.core.hwdb.HwdbStore
import com.example.highwaymode.core.hwdb.Prefectures
import com.example.highwaymode.core.hwdb.TileCodec
import com.example.highwaymode.core.json.JsonReader
import com.example.highwaymode.core.net.OverpassHttp
import com.example.highwaymode.core.osm.OsmDataSet
import com.example.highwaymode.core.osm.OsmNode
import com.example.highwaymode.core.osm.OsmTileData
import com.example.highwaymode.core.osm.OsmWay
import com.example.highwaymode.core.osm.OverpassParser
import com.example.highwaymode.core.osm.TileId
import com.example.highwaymode.core.route.RouteAheadBuilder
import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Locale
import java.util.zip.GZIPOutputStream

class HwdbTest {

    private val net = TestNetwork().build()

    private fun tmpDir(): File = Files.createTempDirectory("hwdb").toFile().also { it.deleteOnExit() }

    @Test
    fun tileCodecRoundTripIsCompactAndExact() {
        val t = net.tileData()
        val bytes = TileCodec.encode(t)
        val d = TileCodec.decode(bytes)
        assertEquals(t.roads.size, d.roads.size)
        assertEquals(t.junctions.size, d.junctions.size)
        assertEquals(t.tollBooths.size, d.tollBooths.size)
        assertEquals(t.restAreas.size, d.restAreas.size)
        assertEquals(t.pois.size, d.pois.size)
        for (i in t.roads.indices) {
            val a = t.roads[i]
            val b = d.roads[i]
            assertEquals(a.id, b.id)
            assertArrayEquals(a.nodeIds, b.nodeIds)
            for (k in a.lat.indices) {
                assertEquals(a.lat[k], b.lat[k], 1e-7)
                assertEquals(a.lon[k], b.lon[k], 1e-7)
            }
            assertEquals(a.tags.filterKeys { it in TileCodec.ROAD_KEYS }, b.tags)
        }
        assertEquals(t.restAreas[0].rings[0].closed, d.restAreas[0].rings[0].closed)
        val json = toOverpassJson(t)
        println(String.format(Locale.US, "タイル符号化: Overpass JSON %.1f KB → %.1f KB（deflate 前）", json.length / 1024.0, bytes.size / 1024.0))
        assertTrue(bytes.size * 8 < json.length)
    }

    @Test
    fun streamingJsonParseMatchesStringParse() {
        val json = toOverpassJson(net.tileData())
        val a = OverpassParser.parse(json)
        // 小さいバッファでバッファ境界をまたぐ文字列・数値を確実に通す
        for (buf in listOf(16, 37, 1000)) {
            val r = JsonReader(StringReader(json), buf)
            var n = 0
            r.beginObject()
            while (r.hasNext()) {
                if (r.nextName() == "elements") {
                    r.beginArray()
                    while (r.hasNext()) {
                        r.skipValue()
                        n++
                    }
                    r.endArray()
                } else r.skipValue()
            }
            r.endObject()
            assertTrue(n > 0)
        }
        val b = OverpassParser.parse(StringReader(json))
        assertEquals(a.roads.size, b.roads.size)
        assertEquals(a.roads.sumOf { it.nodeIds.size }, b.roads.sumOf { it.nodeIds.size })
        assertEquals(a.junctions.map { it.tags["name"] }, b.junctions.map { it.tags["name"] })
        assertEquals(a.restAreas.size, b.restAreas.size)
        assertEquals(a.pois.size, b.pois.size)
    }

    @Test
    fun datasetFileGivesSameFacilitiesAsDirectData() {
        val dir = tmpDir()
        val b = DatasetBuilder()
        b.add(net.tileData())
        val f = File(dir, "test.hwdb")
        val tiles = HashSet<TileId>()
        for (w in net.ways) for (i in w.lat.indices) tiles.add(TileId.of(w.lat[i], w.lon[i]))
        // テスト道路網にかかる都道府県（長野・山梨・静岡など）を取得した想定
        val prefs = Prefectures.ALL.filter { p -> tiles.any { p.intersects(it) } }
        val nagano = listOf(Prefectures.byCode("JP-20")!!)
        b.write(f, prefs)
        val hw = HwdbFile.open(f)
        println("HWDB: ${f.length() / 1024} KB, タイル ${hw.tileCount}（データあり ${hw.nonEmptyTileCount}）, 版 ${hw.dataVersion}, 県 ${prefs.joinToString { it.name }}")
        assertEquals(hw.tileCount, hw.verifyAll())
        val store = HwdbStore(listOf(hw))
        val loaded = tiles.map { t -> store.read(t) ?: throw AssertionError("tile ${t.key()} missing") }
        val g = GraphBuilder.build(OsmDataSet.merge(loaded), tiles)
        val p0 = net.sbPoint(1000.0)
        val p = g.nearby(p0.lat, p0.lon, 20.0) { it.wayId in net.sbWayIds }.first()
        val r = RouteAheadBuilder().build(g, p.edge, p.offset)
        assertEquals(
            listOf(FacilityType.IC, FacilityType.SA, FacilityType.JCT, FacilityType.SIC, FacilityType.IC, FacilityType.TB),
            r.facilities.map { it.facility.type }
        )
        // 範囲外（長野県の外の海上など）は「このファイルには無い」
        assertNull(store.read(TileId.of(30.0, 140.0)))
        // 長野県内の道路の無いタイルは空タイル（オンライン取得不要）
        val empty = Prefectures.tilesOf(nagano[0]).first { it !in tiles && store.has(it) }
        assertTrue(store.read(empty)!!.roads.isEmpty())
        hw.close()
    }

    @Test
    fun hwdbCanBeReadAtAnOffsetInsideAnotherFile() {
        // APK に無圧縮で格納したアセットを、コピーせずに APK ファイルの途中から読む場合
        val dir = tmpDir()
        val b = DatasetBuilder()
        b.add(net.tileData())
        val f = File(dir, "x.hwdb")
        b.write(f, null)
        val pack = File(dir, "pack.bin")
        val junk = ByteArray(12345) { (it * 31).toByte() }
        pack.writeBytes(junk + f.readBytes() + junk)
        val hw = HwdbFile.open(pack, junk.size.toLong(), f.length(), "asset")
        assertEquals(hw.tileCount, hw.verifyAll())
        val t = TileId.of(net.sbPoint(1000.0).lat, net.sbPoint(1000.0).lon)
        assertEquals(HwdbFile.open(f).read(t)!!.roads.size, hw.read(t)!!.roads.size)
        hw.close()
    }

    @Test
    fun partialBorderTilesMergeAcrossRegionFiles() {
        val dir = tmpDir()
        val t = TileId.of(35.9, 138.3)
        val needed = Prefectures.ALL.filter { it.intersects(t) }.map { it.code }.sorted()
        assertEquals(listOf("JP-10", "JP-19", "JP-20"), needed)
        fun way(id: Long, lat: Double): OsmWay = OsmWay(id, longArrayOf(id * 10, id * 10 + 1), doubleArrayOf(lat, lat + 0.01), doubleArrayOf(138.3, 138.3), mapOf("highway" to "motorway"))
        fun file(name: String, prefs: String, w: OsmWay): HwdbFile {
            val f = File(dir, name)
            HwdbFile.write(
                f, mapOf(HwdbFile.META_DATA_VERSION to "1", HwdbFile.META_PREFECTURES to prefs, HwdbFile.META_COVERAGE to "partial"),
                mapOf(t to OsmTileData(listOf(w), emptyList(), emptyList(), emptyList(), emptyList())), setOf(t)
            )
            return HwdbFile.open(f)
        }
        val a = file("a.hwdb", "JP-20", way(1, 35.85))
        val b = file("b.hwdb", "JP-19,JP-10", way(2, 35.95))
        assertNull("長野県分だけでは不完全", HwdbStore(listOf(a)).read(t))
        val merged = HwdbStore(listOf(a, b)).read(t)
        assertNotNull(merged)
        assertEquals(setOf(1L, 2L), merged!!.roads.map { it.id }.toSet())
    }

    @Test
    fun completeTilesForAllJapan() {
        val all = Prefectures.completeTiles(Prefectures.ALL)
        val kanto = Prefectures.completeTiles(Prefectures.resolve("kanto"))
        println("完全タイル: 全国 ${all.size} / 関東のみ ${kanto.size}")
        assertTrue(all.size > 1500)
        assertTrue(kanto.isNotEmpty() && kanto.size < all.size)
        // 東京駅付近は関東だけで完全、山梨県境（関東と中部にまたがる）は関東だけでは不完全
        assertTrue(TileId.of(35.68, 139.77) in kanto)
        assertTrue(TileId.of(35.7, 139.0) !in kanto)
        assertEquals(47, Prefectures.resolve("all").size)
        assertEquals(listOf("JP-23", "JP-24"), Prefectures.resolve("23, JP-24").map { it.code })
    }

    @Test
    fun downloaderResumesAndVerifies() {
        val dir = tmpDir()
        val src = File(dir, "src.hwdb")
        val b = DatasetBuilder()
        b.add(net.tileData())
        b.write(src, listOf(Prefectures.byCode("JP-20")!!))
        val bytes = src.readBytes()
        val sha = HwdbManifest.sha256(src)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var rangeRequests = 0
        server.createContext("/data/") { ex ->
            val path = ex.requestURI.path
            if (path.endsWith("hwdb-manifest.json")) {
                val m = HwdbManifest(1, "test-1", null, "テスト", listOf(HwdbManifest.FileEntry("japan.hwdb", "japan.hwdb", bytes.size.toLong(), sha, "partial", "JP-20")))
                val body = m.toJson().toByteArray(Charsets.UTF_8)
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            } else if (path.endsWith("japan.hwdb")) {
                val range = ex.requestHeaders.getFirst("Range")
                if (range != null) {
                    rangeRequests++
                    val from = range.removePrefix("bytes=").removeSuffix("-").toInt()
                    ex.responseHeaders.add("Content-Range", "bytes $from-${bytes.size - 1}/${bytes.size}")
                    ex.sendResponseHeaders(206, (bytes.size - from).toLong())
                    ex.responseBody.use { it.write(bytes, from, bytes.size - from) }
                } else {
                    ex.sendResponseHeaders(200, bytes.size.toLong())
                    ex.responseBody.use { it.write(bytes) }
                }
            } else {
                ex.sendResponseHeaders(404, -1)
                ex.close()
            }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}/data/"
            val outDir = File(dir, "out")
            outDir.mkdirs()
            // 途中まで受信した状態を再現
            File(outDir, "japan.hwdb.part").writeBytes(bytes.copyOf(bytes.size / 3))
            val dl = HwdbDownloader(outDir)
            val m = dl.fetchManifest(base + "hwdb-manifest.json")
            assertEquals("test-1", m.dataVersion)
            assertEquals(base + "japan.hwdb", m.files[0].url)
            val files = dl.download(m)
            assertEquals(1, rangeRequests)
            assertArrayEquals(bytes, files[0].readBytes())
            // 既に最新ならダウンロードしない
            dl.download(m)
            assertEquals(1, rangeRequests)
            // SHA-256 が合わなければ失敗し、既存ファイルは壊さない
            val bad = HwdbManifest(1, "test-2", null, null, listOf(HwdbManifest.FileEntry("japan.hwdb", base + "japan.hwdb", bytes.size.toLong(), "0".repeat(64), null, null)))
            try {
                dl.download(bad)
                fail("SHA-256 mismatch must fail")
            } catch (e: IOException) {
                assertTrue(e.message!!.contains("SHA-256"))
            }
            assertArrayEquals(bytes, File(outDir, "japan.hwdb").readBytes())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun overpassHedgesSlowServerAndSkipsBusyServer() {
        val json = toOverpassJson(net.tileData())
        val gz = java.io.ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(json.toByteArray(Charsets.UTF_8)) } }.toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        val hits = java.util.concurrent.ConcurrentHashMap<String, Int>()
        server.createContext("/") { ex ->
            val p = ex.requestURI.path
            hits.merge(p, 1, Int::plus)
            ex.requestBody.readBytes()
            when (p) {
                "/busy" -> {
                    ex.sendResponseHeaders(429, -1)
                    ex.close()
                }
                "/slow" -> {
                    try {
                        Thread.sleep(4000)
                        ex.sendResponseHeaders(200, json.length.toLong())
                        ex.responseBody.use { it.write(json.toByteArray()) }
                    } catch (_: Exception) {
                    }
                }
                else -> {
                    ex.responseHeaders.add("Content-Encoding", "gzip")
                    ex.sendResponseHeaders(200, gz.size.toLong())
                    ex.responseBody.use { it.write(gz) }
                }
            }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val http = OverpassHttp({ listOf("$base/busy", "$base/slow", "$base/fast") }, hedgeAfterMs = 500)
            val t0 = System.currentTimeMillis()
            var used = ""
            val d = http.query("[out:json];", onServer = { used = it }) { OverpassParser.parse(it) }
            val ms = System.currentTimeMillis() - t0
            println("Overpass ヘッジ: ${ms}ms で取得（busy→slow→fast, hits=$hits）")
            assertEquals(net.tileData().roads.size, d.roads.size)
            assertTrue("遅いサーバーを待ち続けない", ms < 3000)
            assertEquals(1, hits["/busy"])
            http.shutdown()
        } finally {
            server.stop(0)
        }
    }

    companion object {
        /** OsmTileData を Overpass API の JSON 形式に書き出す（テスト用） */
        fun toOverpassJson(t: OsmTileData): String {
            val sb = StringBuilder("{\"version\":0.6,\"generator\":\"test\",\"elements\":[\n")
            var first = true
            fun sep() {
                if (!first) sb.append(",\n")
                first = false
            }
            fun tags(m: Map<String, String>) {
                sb.append("\"tags\":{")
                sb.append(m.entries.joinToString(",") { (k, v) -> "\"$k\":\"" + v.replace("\"", "\\\"") + "\"" })
                sb.append("}")
            }
            for (w in t.roads) {
                sep()
                sb.append("{\"type\":\"way\",\"id\":").append(w.id).append(",\"nodes\":[").append(w.nodeIds.joinToString(",")).append("],\"geometry\":[")
                sb.append(w.lat.indices.joinToString(",") { String.format(Locale.US, "{\"lat\":%.7f,\"lon\":%.7f}", w.lat[it], w.lon[it]) })
                sb.append("],")
                tags(w.tags)
                sb.append("}")
            }
            for (n in t.junctions + t.tollBooths + t.cameras) {
                sep()
                sb.append(String.format(Locale.US, "{\"type\":\"node\",\"id\":%d,\"lat\":%.7f,\"lon\":%.7f,", n.id, n.lat, n.lon))
                tags(n.tags)
                sb.append("}")
            }
            for (a in t.restAreas) {
                sep()
                val r = a.rings.first()
                sb.append("{\"type\":\"way\",\"id\":").append(a.id).append(",\"geometry\":[")
                sb.append(r.lat.indices.joinToString(",") { String.format(Locale.US, "{\"lat\":%.7f,\"lon\":%.7f}", r.lat[it], r.lon[it]) })
                sb.append("],")
                tags(a.tags)
                sb.append("}")
            }
            for (p in t.pois) {
                sep()
                sb.append(String.format(Locale.US, "{\"type\":\"node\",\"id\":%s,\"lat\":%.7f,\"lon\":%.7f,", p.key.drop(1), p.lat, p.lon))
                tags(p.tags)
                sb.append("}")
            }
            sb.append("\n]}")
            return sb.toString()
        }

        @Suppress("unused")
        private fun node(id: Long, lat: Double, lon: Double, tags: Map<String, String>) = OsmNode(id, lat, lon, tags)
    }
}

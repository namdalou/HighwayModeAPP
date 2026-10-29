package com.example.highwaymode.core.hwdb

import com.example.highwaymode.core.osm.OsmTileData
import com.example.highwaymode.core.osm.TileId
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * 高速道路データベース（.hwdb）ファイル。
 *
 * 構成:
 *   "HWDB" | 版数(u8) | メタ情報（key=value 行, UTF-8）| タイル目録 | タイル本体（deflate 圧縮）
 *
 *  - タイル（緯度 0.2°×経度 0.25°）ごとに独立して圧縮しているので、必要なタイルだけを
 *    ファイルの途中から読める（全国分でもメモリに全部載せない）。
 *  - 目録に「空タイル」（道路が無いと確認済み）も記録するので、海や山間部でも通信しない。
 *  - 各タイルに CRC32 を持たせ、壊れたタイルは読み込まない。
 */
class HwdbFile private constructor(
    val file: File,
    val meta: Map<String, String>,
    private val entries: Map<TileId, Entry>,
    /** ファイル先頭からタイル本体までの位置（APK 内に無圧縮で格納した場合はその開始位置を含む） */
    private val dataStart: Long,
    /** 表示用の名前 */
    val name: String,
    /** このデータが占めるバイト数 */
    val byteLength: Long
) : Closeable {

    /** partial = 隣接する未取得の都道府県の道路が欠けている可能性があるタイル */
    class Entry(val offset: Long, val compLen: Int, val rawLen: Int, val crc: Long, val partial: Boolean)

    private var raf: RandomAccessFile? = null

    val dataVersion: String get() = meta[META_DATA_VERSION] ?: "unknown"
    val createdAt: String? get() = meta[META_CREATED_AT]
    /** 日本全域を含む（目録に無いタイルは「道路なし」） */
    val coversAll: Boolean get() = meta[META_COVERAGE] == "all"
    val tileCount: Int get() = entries.size
    val nonEmptyTileCount: Int get() = entries.values.count { it.compLen > 0 }
    val tiles: Set<TileId> get() = entries.keys
    /** 作成時に取得した都道府県コード */
    val prefectures: Set<String> = meta[META_PREFECTURES]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    /** このファイルだけで完全な内容を持つタイルか */
    fun has(t: TileId): Boolean {
        val e = entries[t] ?: return coversAll && t.inJapan
        return !e.partial
    }

    /** 一部の都道府県分だけを持つタイルか */
    fun hasPartial(t: TileId): Boolean = entries[t]?.partial == true

    /**
     * @return タイルのデータ（空タイルは空の OsmTileData）。このファイルに無いタイルは null
     * @throws IOException 読み込み・検証に失敗した場合
     */
    @Synchronized
    fun read(t: TileId): OsmTileData? {
        val e = entries[t] ?: return if (coversAll && t.inJapan) EMPTY else null
        if (e.compLen == 0) return EMPTY
        val r = raf ?: RandomAccessFile(file, "r").also { raf = it }
        val comp = ByteArray(e.compLen)
        r.seek(dataStart + e.offset)
        r.readFully(comp)
        val raw = inflate(comp, e.rawLen)
        val crc = CRC32()
        crc.update(raw)
        if (crc.value != e.crc) throw IOException("HWDB: タイル ${t.key()} が壊れています（CRC 不一致）")
        return try {
            TileCodec.decode(raw)
        } catch (ex: RuntimeException) {
            throw IOException("HWDB: タイル ${t.key()} を解析できません: ${ex.message}")
        }
    }

    /** すべてのタイルを読んで検証する（ダウンロード後の確認用） */
    fun verifyAll(): Int {
        var n = 0
        for (t in entries.keys) {
            read(t)
            n++
        }
        return n
    }

    @Synchronized
    override fun close() {
        raf?.close()
        raf = null
    }

    companion object {
        private val MAGIC = byteArrayOf('H'.code.toByte(), 'W'.code.toByte(), 'D'.code.toByte(), 'B'.code.toByte())
        const val FORMAT_VERSION = 1

        const val META_DATA_VERSION = "dataVersion"
        const val META_CREATED_AT = "createdAt"
        const val META_COVERAGE = "coverage"
        const val META_PREFECTURES = "prefectures"
        const val META_SOURCE = "source"
        const val META_LICENSE = "license"

        val EMPTY = OsmTileData(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

        /**
         * @param offset ファイル内の開始位置（APK に無圧縮で入れたアセットを直接読む場合）
         * @param length データの長さ
         */
        fun open(file: File, offset: Long = 0L, length: Long = file.length() - offset, name: String = file.name): HwdbFile {
            FileInputStream(file).use { fis ->
                if (offset > 0) fis.channel.position(offset)
                val input = CountingStream(BufferedInputStream(fis, 64 * 1024))
                val magic = ByteArray(4)
                input.readFully(magic)
                if (!magic.contentEquals(MAGIC)) throw IOException("HWDB ファイルではありません: ${file.name}")
                val ver = input.read()
                if (ver != FORMAT_VERSION) throw IOException("未対応の HWDB 版数 $ver（アプリを更新してください）")
                val metaLen = input.readVarInt()
                if (metaLen > 1 shl 20) throw IOException("HWDB: メタ情報が大きすぎます")
                val metaBytes = ByteArray(metaLen)
                input.readFully(metaBytes)
                val meta = LinkedHashMap<String, String>()
                for (line in String(metaBytes, Charsets.UTF_8).lines()) {
                    val i = line.indexOf('=')
                    if (i > 0) meta[line.substring(0, i)] = line.substring(i + 1)
                }
                val n = input.readVarInt()
                if (n > 200_000) throw IOException("HWDB: タイル数が不正です")
                val entries = HashMap<TileId, Entry>(n * 2)
                repeat(n) {
                    val iy = input.readZigzag().toInt()
                    val ix = input.readZigzag().toInt()
                    val off = input.readVarLong()
                    val comp = input.readVarInt()
                    val raw = input.readVarInt()
                    val crc = input.readVarLong()
                    val flags = input.readVarInt()
                    entries[TileId(iy, ix)] = Entry(off, comp, raw, crc, flags and 1 != 0)
                }
                val dataStart = input.count
                val dataLen = length - dataStart
                for (e in entries.values) {
                    if (e.offset < 0 || e.offset + e.compLen > dataLen) throw IOException("HWDB: ファイルが途中で切れています")
                }
                return HwdbFile(file, meta, entries, offset + dataStart, name, length)
            }
        }

        /**
         * タイル群を書き出す（一時ファイルに書いてから置き換える）。
         * @param tiles 値が空の OsmTileData のタイルは「空タイル」として記録する
         * @param partial 内容が欠けている可能性のあるタイル
         */
        fun write(file: File, meta: Map<String, String>, tiles: Map<TileId, OsmTileData>, partial: Set<TileId> = emptySet(), level: Int = 9) {
            val keys = tiles.keys.sortedWith(compareBy({ it.iy }, { it.ix }))
            val tmpData = File(file.path + ".data.tmp")
            val entries = ArrayList<Pair<TileId, Entry>>(keys.size)
            val deflater = Deflater(level, true)
            try {
                FileOutputStream(tmpData).use { out ->
                    var offset = 0L
                    for (t in keys) {
                        val d = tiles[t]!!
                        if (d.roads.isEmpty() && d.restAreas.isEmpty() && d.cameras.isEmpty()) {
                            entries.add(t to Entry(0, 0, 0, 0, t in partial))
                            continue
                        }
                        val raw = TileCodec.encode(d)
                        val crc = CRC32()
                        crc.update(raw)
                        val comp = deflate(deflater, raw)
                        out.write(comp)
                        entries.add(t to Entry(offset, comp.size, raw.size, crc.value, t in partial))
                        offset += comp.size
                    }
                }
            } finally {
                deflater.end()
            }
            val head = ByteSink(64 * 1024)
            head.bytes(MAGIC)
            head.u8(FORMAT_VERSION)
            val metaText = meta.entries.joinToString("\n") { (k, v) -> k + "=" + v.replace('\n', ' ') }
            val mb = metaText.toByteArray(Charsets.UTF_8)
            head.varInt(mb.size)
            head.bytes(mb)
            head.varInt(entries.size)
            for ((t, e) in entries) {
                head.zigzag(t.iy.toLong())
                head.zigzag(t.ix.toLong())
                head.varLong(e.offset)
                head.varInt(e.compLen)
                head.varInt(e.rawLen)
                head.varLong(e.crc)
                head.varInt(if (e.partial) 1 else 0)
            }
            val tmp = File(file.path + ".tmp")
            FileOutputStream(tmp).use { out ->
                out.write(head.toByteArray())
                FileInputStream(tmpData).use { it.copyTo(out, 256 * 1024) }
                out.fd.sync()
            }
            tmpData.delete()
            if (file.exists() && !file.delete()) throw IOException("${file.name} を置き換えられません")
            if (!tmp.renameTo(file)) throw IOException("${file.name} を書き込めません")
        }

        private fun deflate(d: Deflater, raw: ByteArray): ByteArray {
            d.reset()
            d.setInput(raw)
            d.finish()
            val out = ByteSink(raw.size / 3 + 64)
            val buf = ByteArray(64 * 1024)
            while (!d.finished()) {
                val n = d.deflate(buf)
                out.bytes(buf, 0, n)
            }
            return out.toByteArray()
        }

        private fun inflate(comp: ByteArray, rawLen: Int): ByteArray {
            val inf = Inflater(true)
            try {
                // nowrap=true の場合は末尾に 1 バイトのダミーが必要
                val input = comp.copyOf(comp.size + 1)
                inf.setInput(input)
                val raw = ByteArray(rawLen)
                var n = 0
                while (n < rawLen) {
                    val k = inf.inflate(raw, n, rawLen - n)
                    if (k == 0 && (inf.finished() || inf.needsInput() || inf.needsDictionary())) break
                    n += k
                }
                if (n != rawLen) throw IOException("HWDB: 展開サイズが一致しません")
                return raw
            } finally {
                inf.end()
            }
        }
    }

    private class CountingStream(private val inner: InputStream) {
        var count = 0L
            private set

        fun read(): Int {
            val b = inner.read()
            if (b >= 0) count++
            return b
        }

        fun readFully(b: ByteArray) {
            var off = 0
            while (off < b.size) {
                val n = inner.read(b, off, b.size - off)
                if (n < 0) throw IOException("HWDB: ファイルが途中で切れています")
                off += n
            }
            count += b.size
        }

        fun readVarLong(): Long {
            var shift = 0
            var result = 0L
            while (true) {
                val b = read()
                if (b < 0) throw IOException("HWDB: ファイルが途中で切れています")
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift > 63) throw IOException("HWDB: 目録が壊れています")
            }
        }

        fun readVarInt(): Int {
            val v = readVarLong()
            if (v < 0 || v > Int.MAX_VALUE) throw IOException("HWDB: 目録が壊れています")
            return v.toInt()
        }

        fun readZigzag(): Long {
            val v = readVarLong()
            return (v ushr 1) xor -(v and 1)
        }
    }
}

/**
 * 複数の HWDB（ダウンロードした最新版・アプリ同梱版・端末内で作成した地方版など）を優先順に束ねる。
 * 地方ごとに作ったファイルの境界タイルは、両方の地方がそろえば合成して完全なタイルとして扱う。
 */
class HwdbStore(files: List<HwdbFile>) : Closeable {
    val files: List<HwdbFile> = files.toList()

    /** 完全な内容を返せるタイルか */
    fun has(t: TileId): Boolean {
        if (files.any { it.has(t) }) return true
        return partialsCover(t) != null
    }

    private fun partialsCover(t: TileId): List<HwdbFile>? {
        val parts = files.filter { it.hasPartial(t) }
        if (parts.isEmpty()) return null
        val covered = parts.flatMap { it.prefectures }.toHashSet()
        val needed = Prefectures.ALL.filter { it.intersects(t) }
        return if (needed.isNotEmpty() && needed.all { it.code in covered }) parts else null
    }

    /**
     * 完全な内容を持つファイルから読む（壊れていれば次のファイル）。
     * @return 完全な内容が無ければ null（呼び出し側はオンライン取得へ）
     */
    fun read(t: TileId): OsmTileData? {
        var err: IOException? = null
        for (f in files) {
            if (!f.has(t)) continue
            try {
                return f.read(t)
            } catch (e: IOException) {
                err = e
            }
        }
        val parts = partialsCover(t)
        if (parts != null) {
            try {
                return mergeTiles(parts.mapNotNull { it.read(t) })
            } catch (e: IOException) {
                err = e
            }
        }
        if (err != null) throw err
        return null
    }

    val isEmpty: Boolean get() = files.isEmpty()

    override fun close() {
        for (f in files) f.close()
    }

    companion object {
        fun mergeTiles(list: List<OsmTileData>): OsmTileData {
            if (list.size == 1) return list[0]
            val roads = LinkedHashMap<Long, com.example.highwaymode.core.osm.OsmWay>()
            val j = LinkedHashMap<Long, com.example.highwaymode.core.osm.OsmNode>()
            val tb = LinkedHashMap<Long, com.example.highwaymode.core.osm.OsmNode>()
            val cams = LinkedHashMap<Long, com.example.highwaymode.core.osm.OsmNode>()
            val areas = LinkedHashMap<String, com.example.highwaymode.core.osm.OsmArea>()
            val pois = LinkedHashMap<String, com.example.highwaymode.core.osm.OsmPoi>()
            for (d in list) {
                for (w in d.roads) roads.putIfAbsent(w.id, w)
                for (n in d.junctions) j.putIfAbsent(n.id, n)
                for (n in d.tollBooths) tb.putIfAbsent(n.id, n)
                for (n in d.cameras) cams.putIfAbsent(n.id, n)
                for (a in d.restAreas) areas.putIfAbsent(a.key, a)
                for (p in d.pois) pois.putIfAbsent(p.key, p)
            }
            return OsmTileData(roads.values.toList(), j.values.toList(), tb.values.toList(), areas.values.toList(), pois.values.toList(), cams.values.toList())
        }
    }
}

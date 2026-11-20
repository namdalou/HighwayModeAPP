package com.example.highwaymode.core.traffic

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.tan

/**
 * Mapbox Vector Tile（protobuf 形式の地図タイル）の最小限の読み取り（v2.23）。
 * TomTom の「道路の流れ」のベクトルタイル（Vector Flow Tiles）を読むために、線の地物と属性だけを取り出す。
 * 形式は公開仕様（vector-tile-spec 2.1）のとおり: Tile{3: Layer}、Layer{1: 名前, 2: Feature, 3: 属性名, 4: 値, 5: extent}、
 * Feature{1: id, 2: 属性（名前と値の番号の組）, 3: 種類, 4: 形（命令と差分の列）}。
 */
object VectorTile {

    const val TYPE_POINT = 1
    const val TYPE_LINE = 2
    const val TYPE_POLYGON = 3

    /**
     * 1 つの地物。[lines] はタイル内の座標（0〜extent。少しはみ出すこともある）を x, y の順に並べた線（MoveTo ごとに 1 本）
     */
    class Feature(val layer: String, val type: Int, val tags: Map<String, Any>, val lines: List<IntArray>, val extent: Int) {
        fun number(key: String): Double? = when (val v = tags[key]) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull()
            else -> null
        }

        fun bool(key: String): Boolean? = when (val v = tags[key]) {
            is Boolean -> v
            is Number -> v.toDouble() != 0.0
            is String -> when (v.lowercase()) {
                "true", "yes", "1" -> true
                "false", "no", "0" -> false
                else -> null
            }
            else -> null
        }

        fun text(key: String): String? = tags[key]?.toString()
    }

    class Layer(val name: String, val extent: Int, val features: List<Feature>)

    /** タイル全体を読む。形式が壊れていれば IllegalArgumentException */
    fun decode(bytes: ByteArray): List<Layer> {
        val r = Reader(bytes, 0, bytes.size)
        val out = ArrayList<Layer>()
        while (r.hasMore()) {
            val tag = r.varint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7).toInt()
            if (field == 3 && wire == 2) {
                val len = r.length()
                out.add(readLayer(Reader(bytes, r.pos, r.pos + len)))
                r.pos += len
            } else r.skip(wire)
        }
        return out
    }

    private fun readLayer(r: Reader): Layer {
        var name = ""
        var extent = 4096
        val keys = ArrayList<String>()
        val values = ArrayList<Any?>()
        val featureRanges = ArrayList<IntArray>()
        while (r.hasMore()) {
            val tag = r.varint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7).toInt()
            when {
                field == 1 && wire == 2 -> name = r.string()
                field == 2 && wire == 2 -> {
                    val len = r.length()
                    featureRanges.add(intArrayOf(r.pos, r.pos + len))
                    r.pos += len
                }
                field == 3 && wire == 2 -> keys.add(r.string())
                field == 4 && wire == 2 -> {
                    val len = r.length()
                    values.add(readValue(Reader(r.buf, r.pos, r.pos + len)))
                    r.pos += len
                }
                field == 5 && wire == 0 -> extent = r.varint().toInt()
                else -> r.skip(wire)
            }
        }
        if (extent <= 0) extent = 4096
        // 地物は属性名・値の一覧より前に書かれていることがあるので、一覧を読み終えてから読む
        val features = ArrayList<Feature>(featureRanges.size)
        for (fr in featureRanges) features.add(readFeature(Reader(r.buf, fr[0], fr[1]), name, keys, values, extent))
        return Layer(name, extent, features)
    }

    private fun readValue(r: Reader): Any? {
        var v: Any? = null
        while (r.hasMore()) {
            val tag = r.varint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7).toInt()
            when {
                field == 1 && wire == 2 -> v = r.string()
                field == 2 && wire == 5 -> v = java.lang.Float.intBitsToFloat(r.fixed32()).toDouble()
                field == 3 && wire == 1 -> v = java.lang.Double.longBitsToDouble(r.fixed64())
                field == 4 && wire == 0 -> v = r.varint()
                field == 5 && wire == 0 -> v = r.varint()
                field == 6 && wire == 0 -> v = zigzag(r.varint())
                field == 7 && wire == 0 -> v = r.varint() != 0L
                else -> r.skip(wire)
            }
        }
        return v
    }

    private fun readFeature(r: Reader, layer: String, keys: List<String>, values: List<Any?>, extent: Int): Feature {
        var type = 0
        val tags = HashMap<String, Any>()
        var geom: LongArray? = null
        while (r.hasMore()) {
            val tag = r.varint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7).toInt()
            when {
                field == 2 && wire == 2 -> {
                    val packed = r.packed()
                    var i = 0
                    while (i + 1 < packed.size) {
                        val k = keys.getOrNull(packed[i].toInt())
                        val v = values.getOrNull(packed[i + 1].toInt())
                        if (k != null && v != null) tags[k] = v
                        i += 2
                    }
                }
                field == 3 && wire == 0 -> type = r.varint().toInt()
                field == 4 && wire == 2 -> geom = r.packed()
                else -> r.skip(wire)
            }
        }
        val lines = if (geom != null && (type == TYPE_LINE || type == TYPE_POLYGON || type == TYPE_POINT)) geometry(geom) else emptyList()
        return Feature(layer, type, tags, lines, extent)
    }

    /** 形の命令列（MoveTo=1・LineTo=2・ClosePath=7、座標は前の点からの差分を zigzag で）を線の一覧にする */
    fun geometry(g: LongArray): List<IntArray> {
        val out = ArrayList<IntArray>()
        var cur: IntArrayBuilder? = null
        var x = 0
        var y = 0
        var i = 0
        while (i < g.size) {
            val cmd = g[i++]
            val id = (cmd and 7).toInt()
            val count = (cmd ushr 3).toInt()
            when (id) {
                1, 2 -> {
                    for (k in 0 until count) {
                        if (i + 1 >= g.size) {
                            i = g.size
                            break
                        }
                        x += zigzag(g[i]).toInt()
                        y += zigzag(g[i + 1]).toInt()
                        i += 2
                        if (id == 1) {
                            cur?.let { if (it.size >= 2) out.add(it.toArray()) }
                            cur = IntArrayBuilder()
                        }
                        cur?.add(x, y)
                    }
                }
                7 -> {
                    // 多角形を閉じる: 線としては始点に戻す
                    cur?.let { if (it.size >= 2) it.add(it.get(0), it.get(1)) }
                }
                else -> i = g.size
            }
        }
        cur?.let { if (it.size >= 2) out.add(it.toArray()) }
        return out
    }

    private fun zigzag(n: Long): Long = (n ushr 1) xor -(n and 1)

    /** タイルの中の座標 → 経度・緯度（Web メルカトル。XYZ 方式のタイル番号） */
    fun lon(z: Int, tx: Int, px: Double, extent: Int): Double = (tx + px / extent) / (1 shl z) * 360.0 - 180.0

    fun lat(z: Int, ty: Int, py: Double, extent: Int): Double {
        val n = PI * (1.0 - 2.0 * (ty + py / extent) / (1 shl z))
        return Math.toDegrees(atan(0.5 * (exp(n) - exp(-n))))
    }

    /** 経度・緯度 → タイル番号（x, y） */
    fun tileX(z: Int, lon: Double): Int = (((lon + 180.0) / 360.0) * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)

    fun tileY(z: Int, lat: Double): Int {
        val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        val y = (1.0 - ln(tan(r) + 1.0 / kotlin.math.cos(r)) / PI) / 2.0 * (1 shl z)
        return y.toInt().coerceIn(0, (1 shl z) - 1)
    }

    private class IntArrayBuilder {
        private var a = IntArray(32)
        var size = 0
            private set

        fun add(x: Int, y: Int) {
            if (size + 2 > a.size) a = a.copyOf(a.size * 2)
            a[size++] = x
            a[size++] = y
        }

        fun get(i: Int): Int = a[i]
        fun toArray(): IntArray = a.copyOf(size)
    }

    private class Reader(val buf: ByteArray, var pos: Int, val end: Int) {
        init {
            require(pos in 0..end && end <= buf.size) { "壊れたタイル" }
        }

        fun hasMore(): Boolean = pos < end

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                require(pos < end) { "壊れたタイル（varint）" }
                val b = buf[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                require(shift < 64) { "壊れたタイル（varint が長すぎる）" }
            }
        }

        fun length(): Int {
            val n = varint()
            require(n >= 0 && n <= end - pos) { "壊れたタイル（長さ）" }
            return n.toInt()
        }

        fun string(): String {
            val n = length()
            val s = String(buf, pos, n, Charsets.UTF_8)
            pos += n
            return s
        }

        fun fixed32(): Int {
            require(pos + 4 <= end) { "壊れたタイル（32bit）" }
            val v = (buf[pos].toInt() and 0xFF) or ((buf[pos + 1].toInt() and 0xFF) shl 8) or
                ((buf[pos + 2].toInt() and 0xFF) shl 16) or ((buf[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            return v
        }

        fun fixed64(): Long {
            require(pos + 8 <= end) { "壊れたタイル（64bit）" }
            var v = 0L
            for (k in 0 until 8) v = v or ((buf[pos + k].toLong() and 0xFF) shl (8 * k))
            pos += 8
            return v
        }

        fun packed(): LongArray {
            val n = length()
            val stop = pos + n
            val tmp = ArrayList<Long>()
            while (pos < stop) {
                var result = 0L
                var shift = 0
                while (true) {
                    require(pos < stop) { "壊れたタイル（packed）" }
                    val b = buf[pos++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) break
                    shift += 7
                    require(shift < 64) { "壊れたタイル（packed）" }
                }
                tmp.add(result)
            }
            return tmp.toLongArray()
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> {
                    require(pos + 8 <= end) { "壊れたタイル" }
                    pos += 8
                }
                2 -> pos += length()
                5 -> {
                    require(pos + 4 <= end) { "壊れたタイル" }
                    pos += 4
                }
                else -> throw IllegalArgumentException("壊れたタイル（種類 $wire）")
            }
        }
    }
}

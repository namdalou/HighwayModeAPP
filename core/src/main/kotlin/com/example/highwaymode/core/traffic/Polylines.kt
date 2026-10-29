package com.example.highwaymode.core.traffic

import kotlin.math.roundToLong

/** HERE Flexible Polyline（形式 1, 2 次元）の符号化・復号 */
object FlexPolyline {
    private const val TABLE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private const val VERSION = 1L

    fun encode(lat: DoubleArray, lon: DoubleArray, precision: Int = 5): String {
        val sb = StringBuilder(lat.size * 6 + 4)
        unsigned(VERSION, sb)
        unsigned(precision.toLong(), sb) // 3 次元なし・3 次元精度 0
        val mul = Math.pow(10.0, precision.toDouble())
        var pLat = 0L
        var pLon = 0L
        for (i in lat.indices) {
            val la = (lat[i] * mul).roundToLong()
            val lo = (lon[i] * mul).roundToLong()
            signed(la - pLat, sb)
            signed(lo - pLon, sb)
            pLat = la
            pLon = lo
        }
        return sb.toString()
    }

    fun decode(s: String): Pair<DoubleArray, DoubleArray> {
        var pos = 0
        fun next(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (pos >= s.length) throw IllegalArgumentException("polyline が途中で終わっています")
                val v = TABLE.indexOf(s[pos++])
                if (v < 0) throw IllegalArgumentException("polyline に不正な文字があります")
                result = result or ((v and 0x1f).toLong() shl shift)
                if (v and 0x20 == 0) return result
                shift += 5
            }
        }
        val ver = next()
        if (ver != VERSION) throw IllegalArgumentException("未対応の flexible polyline 版数 $ver")
        val header = next()
        val precision = (header and 0xf).toInt()
        val thirdDim = ((header shr 4) and 0x7).toInt()
        val mul = Math.pow(10.0, precision.toDouble())
        val la = ArrayList<Double>()
        val lo = ArrayList<Double>()
        var pLat = 0L
        var pLon = 0L
        while (pos < s.length) {
            pLat += unzig(next())
            pLon += unzig(next())
            if (thirdDim != 0) next()
            la.add(pLat / mul)
            lo.add(pLon / mul)
        }
        return la.toDoubleArray() to lo.toDoubleArray()
    }

    private fun unzig(v: Long): Long = if (v and 1L != 0L) (v shr 1).inv() else v shr 1

    private fun signed(v: Long, sb: StringBuilder) {
        var x = v shl 1
        if (v < 0) x = x.inv()
        unsigned(x, sb)
    }

    private fun unsigned(v: Long, sb: StringBuilder) {
        var x = v
        while (x > 0x1f) {
            sb.append(TABLE[((x and 0x1f) or 0x20).toInt()])
            x = x shr 5
        }
        sb.append(TABLE[x.toInt()])
    }
}

/** Google Encoded Polyline（精度 1e-5）の復号 */
object GooglePolyline {
    fun decode(s: String): Pair<DoubleArray, DoubleArray> {
        val la = ArrayList<Double>()
        val lo = ArrayList<Double>()
        var i = 0
        var lat = 0L
        var lng = 0L
        fun next(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (i >= s.length) throw IllegalArgumentException("polyline が途中で終わっています")
                val b = s[i++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
                if (b < 0x20) break
            }
            return if (result and 1L != 0L) (result shr 1).inv() else result shr 1
        }
        while (i < s.length) {
            lat += next()
            lng += next()
            la.add(lat / 1e5)
            lo.add(lng / 1e5)
        }
        return la.toDoubleArray() to lo.toDoubleArray()
    }

    fun encode(lat: DoubleArray, lon: DoubleArray): String {
        val sb = StringBuilder()
        var pLat = 0L
        var pLon = 0L
        fun put(v: Long) {
            var x = if (v < 0) (v shl 1).inv() else v shl 1
            while (x >= 0x20) {
                sb.append(((0x20 or (x and 0x1f).toInt()) + 63).toChar())
                x = x shr 5
            }
            sb.append((x.toInt() + 63).toChar())
        }
        for (k in lat.indices) {
            val a = (lat[k] * 1e5).roundToLong()
            val b = (lon[k] * 1e5).roundToLong()
            put(a - pLat)
            put(b - pLon)
            pLat = a
            pLon = b
        }
        return sb.toString()
    }
}

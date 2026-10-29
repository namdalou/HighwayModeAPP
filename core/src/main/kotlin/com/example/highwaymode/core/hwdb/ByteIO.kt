package com.example.highwaymode.core.hwdb

/** 可変長整数（LEB128）と zigzag 符号化の書き込み */
class ByteSink(initial: Int = 4096) {
    private var buf = ByteArray(initial)
    var size = 0
        private set

    private fun ensure(n: Int) {
        if (size + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + n))
    }

    fun u8(v: Int) {
        ensure(1)
        buf[size++] = v.toByte()
    }

    fun varLong(v: Long) {
        ensure(10)
        var x = v
        while (x and 0x7fL.inv() != 0L) {
            buf[size++] = ((x and 0x7f) or 0x80).toByte()
            x = x ushr 7
        }
        buf[size++] = x.toByte()
    }

    fun varInt(v: Int) = varLong(v.toLong() and 0xffffffffL)

    fun zigzag(v: Long) = varLong((v shl 1) xor (v shr 63))

    fun bytes(b: ByteArray, off: Int = 0, len: Int = b.size) {
        ensure(len)
        System.arraycopy(b, off, buf, size, len)
        size += len
    }

    fun string(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        varInt(b.size)
        bytes(b)
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}

/** ByteSink の読み出し側 */
class ByteSource(private val buf: ByteArray, private var pos: Int = 0, private val end: Int = buf.size) {

    class FormatException(msg: String) : RuntimeException(msg)

    val remaining: Int get() = end - pos
    val position: Int get() = pos

    fun u8(): Int {
        if (pos >= end) throw FormatException("unexpected end")
        return buf[pos++].toInt() and 0xff
    }

    fun varLong(): Long {
        var shift = 0
        var result = 0L
        while (true) {
            if (pos >= end) throw FormatException("unexpected end in varint")
            val b = buf[pos++].toInt()
            result = result or ((b and 0x7f).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 63) throw FormatException("varint too long")
        }
    }

    fun varInt(): Int {
        val v = varLong()
        if (v < 0 || v > Int.MAX_VALUE) throw FormatException("varint out of range: $v")
        return v.toInt()
    }

    fun zigzag(): Long {
        val v = varLong()
        return (v ushr 1) xor -(v and 1)
    }

    fun string(): String {
        val n = varInt()
        if (n > remaining) throw FormatException("string too long")
        val s = String(buf, pos, n, Charsets.UTF_8)
        pos += n
        return s
    }

    fun bytes(n: Int): ByteArray {
        if (n > remaining) throw FormatException("unexpected end")
        val b = buf.copyOfRange(pos, pos + n)
        pos += n
        return b
    }
}

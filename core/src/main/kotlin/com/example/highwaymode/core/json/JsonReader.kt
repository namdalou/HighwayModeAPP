package com.example.highwaymode.core.json

import java.io.Reader

/**
 * 依存ライブラリ無しの軽量ストリーミング JSON リーダー。
 * Overpass API の数十 MB 規模のレスポンスを、DOM を作らずに読み進めるために使う。
 * 文字列全体を渡す形と、Reader（HTTP の応答ストリームなど）から逐次読む形の両方に対応する。
 * API は android.util.JsonReader / Gson の JsonReader に似せている。
 */
class JsonReader private constructor(private val reader: Reader?, private var buf: CharArray, private var limit: Int) {

    constructor(src: String) : this(null, src.toCharArray(), src.length)

    /** reader は呼び出し側で閉じること */
    constructor(reader: Reader, bufferSize: Int = 64 * 1024) : this(reader, CharArray(maxOf(16, bufferSize)), 0)

    enum class Token { BEGIN_OBJECT, END_OBJECT, BEGIN_ARRAY, END_ARRAY, NAME, STRING, NUMBER, BOOLEAN, NULL, END_DOCUMENT }

    class JsonException(msg: String) : RuntimeException(msg)

    private var pos = 0
    /** buf[0] より前に読み捨てた文字数（エラー位置の表示用） */
    private var discarded = 0L
    private var stack = IntArray(32)
    private var depth = 0
    private var peeked = P_NONE

    private val where: Long get() = discarded + pos

    init {
        push(EMPTY_DOCUMENT)
    }

    private fun push(scope: Int) {
        if (depth == stack.size) stack = stack.copyOf(depth * 2)
        stack[depth++] = scope
    }

    fun peek(): Token = when (peekInternal()) {
        P_BEGIN_OBJECT -> Token.BEGIN_OBJECT
        P_END_OBJECT -> Token.END_OBJECT
        P_BEGIN_ARRAY -> Token.BEGIN_ARRAY
        P_END_ARRAY -> Token.END_ARRAY
        P_NAME -> Token.NAME
        P_STRING -> Token.STRING
        P_NUMBER -> Token.NUMBER
        P_TRUE, P_FALSE -> Token.BOOLEAN
        P_NULL -> Token.NULL
        else -> Token.END_DOCUMENT
    }

    fun beginObject() {
        expect(P_BEGIN_OBJECT, "{")
        push(EMPTY_OBJECT)
        peeked = P_NONE
    }

    fun endObject() {
        expect(P_END_OBJECT, "}")
        depth--
        peeked = P_NONE
    }

    fun beginArray() {
        expect(P_BEGIN_ARRAY, "[")
        push(EMPTY_ARRAY)
        peeked = P_NONE
    }

    fun endArray() {
        expect(P_END_ARRAY, "]")
        depth--
        peeked = P_NONE
    }

    fun hasNext(): Boolean {
        val p = peekInternal()
        return p != P_END_OBJECT && p != P_END_ARRAY && p != P_EOF
    }

    fun nextName(): String {
        expect(P_NAME, "name")
        val s = readQuoted()
        peeked = P_NONE
        return s
    }

    fun nextString(): String {
        val p = peekInternal()
        val s = when (p) {
            P_STRING -> readQuoted()
            P_NUMBER -> readNumberText()
            P_TRUE -> "true"
            P_FALSE -> "false"
            else -> throw JsonException("Expected string at $where")
        }
        peeked = P_NONE
        return s
    }

    fun nextDouble(): Double {
        val p = peekInternal()
        val v = when (p) {
            P_NUMBER -> readNumberText().toDouble()
            P_STRING -> readQuoted().trim().toDoubleOrNull() ?: throw JsonException("Bad number at $where")
            else -> throw JsonException("Expected number at $where")
        }
        peeked = P_NONE
        return v
    }

    fun nextLong(): Long {
        val p = peekInternal()
        val v = when (p) {
            P_NUMBER -> {
                val t = readNumberText()
                t.toLongOrNull() ?: t.toDouble().toLong()
            }
            P_STRING -> readQuoted().trim().toLongOrNull() ?: throw JsonException("Bad long at $where")
            else -> throw JsonException("Expected number at $where")
        }
        peeked = P_NONE
        return v
    }

    fun nextBoolean(): Boolean {
        val p = peekInternal()
        val v = when (p) {
            P_TRUE -> true
            P_FALSE -> false
            else -> throw JsonException("Expected boolean at $where")
        }
        peeked = P_NONE
        return v
    }

    fun nextNull() {
        expect(P_NULL, "null")
        peeked = P_NONE
    }

    /** 次の値（オブジェクト・配列なら中身ごと）を読み飛ばす */
    fun skipValue() {
        when (peekInternal()) {
            P_BEGIN_OBJECT -> {
                beginObject()
                while (hasNext()) {
                    nextName()
                    skipValue()
                }
                endObject()
            }
            P_BEGIN_ARRAY -> {
                beginArray()
                while (hasNext()) skipValue()
                endArray()
            }
            P_NAME -> {
                nextName()
                skipValue()
            }
            P_STRING -> {
                skipQuoted()
                peeked = P_NONE
            }
            P_NUMBER -> {
                readNumberText()
                peeked = P_NONE
            }
            P_TRUE, P_FALSE, P_NULL -> peeked = P_NONE
            else -> throw JsonException("Nothing to skip at $where")
        }
    }

    // ---------------------------------------------------------------- internals

    private fun expect(p: Int, what: String) {
        val actual = peekInternal()
        if (actual != p) throw JsonException("Expected $what but was token $actual at $where")
    }

    private fun peekInternal(): Int {
        if (peeked == P_NONE) peeked = doPeek()
        return peeked
    }

    private fun doPeek(): Int {
        val top = stack[depth - 1]
        when (top) {
            EMPTY_ARRAY -> stack[depth - 1] = NONEMPTY_ARRAY
            NONEMPTY_ARRAY -> {
                when (nextNonWs()) {
                    ']'.code -> return P_END_ARRAY
                    ','.code -> Unit
                    else -> throw JsonException("Unterminated array at $where")
                }
            }
            EMPTY_OBJECT, NONEMPTY_OBJECT -> {
                stack[depth - 1] = DANGLING_NAME
                if (top == NONEMPTY_OBJECT) {
                    when (nextNonWs()) {
                        '}'.code -> return P_END_OBJECT
                        ','.code -> Unit
                        else -> throw JsonException("Unterminated object at $where")
                    }
                }
                return when (nextNonWs()) {
                    '"'.code -> P_NAME
                    '}'.code -> {
                        if (top == EMPTY_OBJECT) P_END_OBJECT
                        else throw JsonException("Trailing comma at $where")
                    }
                    else -> throw JsonException("Expected name at $where")
                }
            }
            DANGLING_NAME -> {
                stack[depth - 1] = NONEMPTY_OBJECT
                if (nextNonWs() != ':'.code) throw JsonException("Expected ':' at $where")
            }
            EMPTY_DOCUMENT -> stack[depth - 1] = NONEMPTY_DOCUMENT
            NONEMPTY_DOCUMENT -> {
                return if (nextNonWs() == -1) P_EOF else throw JsonException("Trailing data at $where")
            }
        }
        val c = nextNonWs()
        return when (c) {
            ']'.code -> {
                if (top == EMPTY_ARRAY) P_END_ARRAY else throw JsonException("Unexpected ] at $where")
            }
            '['.code -> P_BEGIN_ARRAY
            '{'.code -> P_BEGIN_OBJECT
            '"'.code -> P_STRING
            't'.code -> {
                consumeLiteral("rue"); P_TRUE
            }
            'f'.code -> {
                consumeLiteral("alse"); P_FALSE
            }
            'n'.code -> {
                consumeLiteral("ull"); P_NULL
            }
            -1 -> P_EOF
            else -> {
                pos-- // 数値の先頭に戻す（直前に消費した文字なのでバッファ内にある）
                val ch = buf[pos]
                if (ch == '-' || ch in '0'..'9') P_NUMBER else throw JsonException("Unexpected '$ch' at $where")
            }
        }
    }

    /**
     * 未読の文字が minimum 文字以上ある状態にする（Reader 入力時は読み済み部分を捨てて詰める）。
     * @return 足りない（入力の終わり）なら false
     */
    private fun fill(minimum: Int): Boolean {
        if (limit - pos >= minimum) return true
        val r = reader ?: return false
        if (pos > 0) {
            val remain = limit - pos
            if (remain > 0) System.arraycopy(buf, pos, buf, 0, remain)
            discarded += pos
            limit = remain
            pos = 0
        }
        if (buf.size < minimum) buf = buf.copyOf(maxOf(minimum, buf.size * 2))
        while (limit < minimum) {
            val n = r.read(buf, limit, buf.size - limit)
            if (n < 0) return false
            limit += n
        }
        return true
    }

    private fun consumeLiteral(rest: String) {
        if (!fill(rest.length)) throw JsonException("Bad literal at $where")
        for (i in rest.indices) if (buf[pos + i] != rest[i]) throw JsonException("Bad literal at $where")
        pos += rest.length
    }

    /** 空白を飛ばして次の 1 文字を消費して返す。末尾なら -1 */
    private fun nextNonWs(): Int {
        while (true) {
            if (pos >= limit && !fill(1)) return -1
            val c = buf[pos++]
            if (c != ' ' && c != '\n' && c != '\r' && c != '\t') return c.code
        }
    }

    /** 開き引用符の直後から閉じ引用符までを読み、閉じ引用符も消費する */
    private fun readQuoted(): String {
        // 高速パス：バッファ内でエスケープ無しに閉じている
        var i = pos
        while (i < limit) {
            val c = buf[i]
            if (c == '"') {
                val s = String(buf, pos, i - pos)
                pos = i + 1
                return s
            }
            if (c == '\\') break
            i++
        }
        val sb = StringBuilder(i - pos + 16)
        sb.append(buf, pos, i - pos)
        pos = i
        while (true) {
            if (pos >= limit && !fill(1)) break
            val c = buf[pos++]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (pos >= limit && !fill(1)) break
                    when (val e = buf[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (!fill(4)) throw JsonException("Bad unicode escape at $where")
                            val code = String(buf, pos, 4).toInt(16)
                            sb.append(code.toChar())
                            pos += 4
                        }
                        else -> throw JsonException("Bad escape \\$e at $where")
                    }
                }
                else -> sb.append(c)
            }
        }
        throw JsonException("Unterminated string")
    }

    private fun skipQuoted() {
        while (true) {
            if (pos >= limit && !fill(1)) break
            val c = buf[pos++]
            if (c == '"') return
            if (c == '\\') {
                if (pos >= limit && !fill(1)) break
                pos++
            }
        }
        throw JsonException("Unterminated string")
    }

    private fun readNumberText(): String {
        var sb: StringBuilder? = null
        var start = pos
        var any = false
        while (true) {
            if (pos >= limit) {
                if (reader == null) break
                if (sb == null) sb = StringBuilder(24)
                sb.append(buf, start, pos - start)
                if (!fill(1)) {
                    start = pos
                    break
                }
                start = pos
            }
            val c = buf[pos]
            if ((c in '0'..'9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                pos++
                any = true
            } else break
        }
        if (!any) throw JsonException("Empty number at $where")
        return if (sb == null) String(buf, start, pos - start) else sb.append(buf, start, pos - start).toString()
    }

    private companion object {
        const val EMPTY_ARRAY = 1
        const val NONEMPTY_ARRAY = 2
        const val EMPTY_OBJECT = 3
        const val DANGLING_NAME = 4
        const val NONEMPTY_OBJECT = 5
        const val EMPTY_DOCUMENT = 6
        const val NONEMPTY_DOCUMENT = 7

        const val P_NONE = 0
        const val P_BEGIN_OBJECT = 1
        const val P_END_OBJECT = 2
        const val P_BEGIN_ARRAY = 3
        const val P_END_ARRAY = 4
        const val P_TRUE = 5
        const val P_FALSE = 6
        const val P_NULL = 7
        const val P_STRING = 8
        const val P_NAME = 9
        const val P_NUMBER = 10
        const val P_EOF = 11
    }
}

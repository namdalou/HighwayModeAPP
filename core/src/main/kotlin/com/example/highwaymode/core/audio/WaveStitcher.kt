package com.example.highwaymode.core.audio

/**
 * Android の Visualizer が返す波形（1 回に最大 1024 サンプル = 約 21ms）をつないで、長い履歴にする（v2.15）。
 *
 * 1024 サンプルでは周波数の細かさが約 47Hz しか無く、低音（40〜200Hz）を音程ごとに分けられない。
 * Visualizer の中には約 1.4 秒ぶんの波形がしまってあり、読むたびに「いま鳴っているあたり」の 1024 サンプルを返す。
 * 短い間隔（約 12ms）で読むと、続けて読んだ 2 回は半分ほど重なる。重なった部分は同じ値（しまってある値をそのまま返すため）なので、
 * ぴったり重なる位置を探して、新しい部分だけを足していく。つながらなかったとき（読むのが遅れた・音声の経路が変わった）は、
 * その 1 回ぶんから作り直す（長い窓の解析は、履歴がたまるまで短い窓で代用する）。
 *
 * Android に依存しない（JVM でテストできる）。値は符号つき 8bit（Visualizer の値を xor 0x80 したもの）。
 */
class WaveStitcher(val capacity: Int = 8192) {

    companion object {
        /** これより重なりが短いときは、つながったとみなさない（たまたま一致することがあるため） */
        const val MIN_OVERLAP = 128

        /** 読む位置が少し戻ることがある（Visualizer の中の時刻合わせのゆらぎ）。ここまでの戻りは「新しい部分なし」として受け入れる */
        const val MAX_BACK = 256
    }

    private val ring = ByteArray(capacity)
    private var write = 0

    /** つながっている最新のサンプルの数（[capacity] まで） */
    var length = 0
        private set

    private var last = ByteArray(0)
    private var lastN = 0

    /** つながらずに作り直した回数（確認用） */
    var resets = 0
        private set

    /** 履歴を捨てる */
    fun clear() {
        length = 0
        write = 0
        lastN = 0
    }

    /**
     * 読んだ波形を履歴につなぐ。
     * @param capture 符号つき 8bit の波形（古い順）
     * @param n サンプル数
     * @param expected 前回からの経過時間から見積もった、新しいサンプルの数（探し始める位置）
     * @return 足したサンプルの数（0 = 前回と同じ位置か、少し戻った）。つながらず作り直したときは -n
     */
    fun push(capture: ByteArray, n: Int, expected: Int): Int {
        if (n <= 0) return 0
        if (lastN != n || n <= MIN_OVERLAP) return restart(capture, n)
        val maxShift = n - MIN_OVERLAP
        val start = expected.coerceIn(0, maxShift)
        // 見積もりに近い順に、前回の波形とぴったり重なるずれを探す
        var found = -1
        var k = 0
        while (k <= maxShift) {
            val a = start + k
            if (a <= maxShift && overlaps(capture, n, a)) {
                found = a
                break
            }
            val b = start - k
            if (k > 0 && b >= 0 && overlaps(capture, n, b)) {
                found = b
                break
            }
            if (a > maxShift && b < 0) break
            k++
        }
        if (found < 0) {
            // 読む位置が少し戻った（新しい部分は無い）: 前回の波形はそのまま持っておく
            var back = 1
            while (back <= MAX_BACK && back <= maxShift) {
                if (overlapsBack(capture, n, back)) return 0
                back++
            }
            return restart(capture, n)
        }
        if (found > 0) {
            append(capture, n - found, found)
            System.arraycopy(capture, 0, last, 0, n)
        }
        return found
    }

    /** capture[0, n-shift) と last[shift, n) が同じか */
    private fun overlaps(capture: ByteArray, n: Int, shift: Int): Boolean {
        val m = n - shift
        // まず数か所だけ見る（ほとんどの候補はここで外れる）
        val step = if (m >= 8) m / 8 else 1
        var i = 0
        while (i < m) {
            if (capture[i] != last[i + shift]) return false
            i += step
        }
        i = 0
        while (i < m) {
            if (capture[i] != last[i + shift]) return false
            i++
        }
        return true
    }

    /** capture[back, n) と last[0, n-back) が同じか（読む位置が back だけ戻った） */
    private fun overlapsBack(capture: ByteArray, n: Int, back: Int): Boolean {
        val m = n - back
        var i = 0
        while (i < m) {
            if (capture[i + back] != last[i]) return false
            i++
        }
        return true
    }

    private fun restart(capture: ByteArray, n: Int): Int {
        if (lastN != 0) resets++
        if (last.size < n) last = ByteArray(n)
        System.arraycopy(capture, 0, last, 0, n)
        lastN = n
        write = 0
        length = 0
        append(capture, 0, n)
        return -n
    }

    private fun append(src: ByteArray, from: Int, count: Int) {
        var c = count
        var f = from
        if (c > capacity) {
            f += c - capacity
            c = capacity
        }
        val first = minOf(c, capacity - write)
        System.arraycopy(src, f, ring, write, first)
        if (c > first) System.arraycopy(src, f + first, ring, 0, c - first)
        write = (write + c) % capacity
        length = minOf(capacity, length + c)
    }

    /**
     * 最新から [back] サンプル手前で終わる [count] サンプルを、古い順に out[0, count) へ入れる。
     * 履歴が足りない古い側は 0 で埋める。
     * @return 入れた本物のサンプルの数（out の後ろ側）
     */
    fun read(out: FloatArray, count: Int, back: Int = 0): Int {
        val avail = (length - back).coerceIn(0, count)
        val pad = count - avail
        for (i in 0 until pad) out[i] = 0f
        if (avail == 0) return 0
        // 最新のサンプルは write-1。読み始めは write - back - avail
        var p = ((write - back - avail) % capacity + capacity) % capacity
        for (i in 0 until avail) {
            out[pad + i] = ring[p].toFloat()
            p++
            if (p == capacity) p = 0
        }
        return avail
    }
}

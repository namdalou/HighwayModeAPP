package com.example.highwaymode.core.graph

/**
 * Long → Int のオープンアドレス法ハッシュマップ（ボクシング無し）。
 * 数十万ノード規模の道路データをスマホのメモリで扱うために使う。
 */
class LongIntMap(expected: Int = 16) {
    private var keys: LongArray
    private var values: IntArray
    private var used: BooleanArray
    var size = 0
        private set

    init {
        var cap = 16
        while (cap < expected * 2) cap = cap shl 1
        keys = LongArray(cap)
        values = IntArray(cap)
        used = BooleanArray(cap)
    }

    private fun slot(k: Long, mask: Int): Int {
        var h = k * -0x61c8864680b583ebL // 黄金比ハッシュ
        h = h xor (h ushr 32)
        return h.toInt() and mask
    }

    fun get(k: Long, default: Int = 0): Int {
        val mask = keys.size - 1
        var i = slot(k, mask)
        while (used[i]) {
            if (keys[i] == k) return values[i]
            i = (i + 1) and mask
        }
        return default
    }

    fun put(k: Long, v: Int) {
        if ((size + 1) * 2 > keys.size) grow()
        val mask = keys.size - 1
        var i = slot(k, mask)
        while (used[i]) {
            if (keys[i] == k) {
                values[i] = v
                return
            }
            i = (i + 1) and mask
        }
        used[i] = true
        keys[i] = k
        values[i] = v
        size++
    }

    fun add(k: Long, delta: Int) = put(k, get(k) + delta)

    private fun grow() {
        val oldK = keys
        val oldV = values
        val oldU = used
        keys = LongArray(oldK.size * 2)
        values = IntArray(oldK.size * 2)
        used = BooleanArray(oldK.size * 2)
        size = 0
        for (i in oldK.indices) if (oldU[i]) put(oldK[i], oldV[i])
    }
}

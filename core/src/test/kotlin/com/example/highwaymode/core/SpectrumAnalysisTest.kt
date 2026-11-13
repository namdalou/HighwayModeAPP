package com.example.highwaymode.core

import com.example.highwaymode.core.audio.BandAnalyzer
import com.example.highwaymode.core.audio.SpectrumMapper
import com.example.highwaymode.core.audio.WaveStitcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * スペアナの解析（v2.15）: 波形をつなぐ・周波数に応じた窓で帯の強さを出す・高さにする。
 * 「聞こえている音と、光る場所が合う」ことを確かめる。
 */
class SpectrumAnalysisTest {

    private val sr = 48000f

    // ------------------------------------------------------------------ 波形をつなぐ

    @Test
    fun stitcherRebuildsTheStreamFromOverlappingCaptures() {
        val rnd = Random(7)
        val stream = ByteArray(60_000) { (rnd.nextInt(256) - 128).toByte() }
        val st = WaveStitcher()
        val cap = ByteArray(1024)
        var pos = 0          // いま読んでいる波形の先頭
        var head = 1024      // つないだ履歴の最後（stream の中の位置）
        System.arraycopy(stream, pos, cap, 0, 1024)
        assertEquals(-1024, st.push(cap, 1024, 0))
        val buf = FloatArray(4096)
        repeat(90) { step ->
            // ふつうは 300〜800 サンプル進む。ときどき、同じ位置・少し戻る
            val move = when {
                step % 11 == 5 -> 0
                step % 13 == 7 -> -60
                else -> 300 + rnd.nextInt(500)
            }
            pos += move
            System.arraycopy(stream, pos, cap, 0, 1024)
            // 見積もりは実際より少しずれていてよい
            val added = st.push(cap, 1024, move + rnd.nextInt(80) - 40)
            if (move > 0 && pos + 1024 > head) {
                assertEquals("進んだぶんだけ足される", pos + 1024 - head, added)
                head = pos + 1024
            } else {
                assertEquals("同じ位置・戻ったときは足さない", 0, added)
                if (move < 0) pos -= move     // 戻った波形は捨てる（次は、もとの位置から進む）
            }
            assertEquals(minOf(8192, head), st.length)
            val n = minOf(4096, st.length)
            assertEquals(n, st.read(buf, n))
            for (i in 0 until n) assertEquals(stream[head - n + i].toFloat(), buf[i], 0f)
        }
        assertEquals(0, st.resets)
    }

    @Test
    fun stitcherRestartsAfterAGapAndPadsShortHistory() {
        val rnd = Random(3)
        val stream = ByteArray(20_000) { (rnd.nextInt(256) - 128).toByte() }
        val st = WaveStitcher()
        val cap = ByteArray(1024)
        System.arraycopy(stream, 0, cap, 0, 1024)
        st.push(cap, 1024, 0)
        System.arraycopy(stream, 500, cap, 0, 1024)
        assertEquals(500, st.push(cap, 1024, 480))
        assertEquals(1524, st.length)
        // 読むのが遅れて、重なりが無くなった → その 1 回ぶんから作り直す
        System.arraycopy(stream, 5000, cap, 0, 1024)
        assertEquals(-1024, st.push(cap, 1024, 3500))
        assertEquals(1024, st.length)
        assertEquals(1, st.resets)
        // 履歴より長く読むと、古い側は 0 で埋まる
        val buf = FloatArray(2048) { 99f }
        assertEquals(1024, st.read(buf, 2048))
        for (i in 0 until 1024) assertEquals(0f, buf[i], 0f)
        for (i in 0 until 1024) assertEquals(stream[5000 + i].toFloat(), buf[1024 + i], 0f)
        // 無音（全部同じ値）は、見積もりどおりに進んだものとしてつなぐ
        val quiet = WaveStitcher()
        val zeros = ByteArray(1024)
        quiet.push(zeros, 1024, 0)
        assertEquals(600, quiet.push(zeros, 1024, 600))
        assertEquals(1624, quiet.length)
    }

    @Test
    fun stitcherJoinsBackToBackCapturesWhenTheyContinueSmoothly() {
        // 端末によっては、読む位置がちょうど 1 回ぶん（1024 サンプル）ずつ進み、重なりが無い。
        // 音楽らしい波形（低い音が中心）なら、前回の最後と今回の最初がなめらかにつながるので、続きとしてつなぐ
        val n = 12_000
        val wave = ByteArray(n) {
            val t = it / sr
            (60 * sin(2 * PI * 70.0 * t) + 30 * sin(2 * PI * 440.0 * t) + 8 * sin(2 * PI * 3000.0 * t)).roundToInt().toByte()
        }
        val st = WaveStitcher()
        val cap = ByteArray(1024)
        for (k in 0 until 8) {
            System.arraycopy(wave, k * 1024, cap, 0, 1024)
            val added = st.push(cap, 1024, if (k % 2 == 0) 600 else 1200)   // 見積もりは、読む間隔の都合でずれる
            assertEquals(if (k == 0) -1024 else 1024, added)
        }
        assertEquals(8192, st.length)
        assertEquals(0, st.resets)
        assertEquals(7, st.joins)
        val buf = FloatArray(4096)
        st.read(buf, 4096)
        for (i in 0 until 4096) assertEquals(wave[8192 - 4096 + i].toFloat(), buf[i], 0f)

        // 間が大きく抜けたとき（時間の見積もりが 1 回ぶんよりずっと長い）・つなぎ目が飛んでいるときは、つながない
        val gap = WaveStitcher()
        System.arraycopy(wave, 0, cap, 0, 1024)
        gap.push(cap, 1024, 0)
        System.arraycopy(wave, 3000, cap, 0, 1024)
        assertEquals(-1024, gap.push(cap, 1024, 2400))
        val jump = WaveStitcher()
        System.arraycopy(wave, 0, cap, 0, 1024)
        jump.push(cap, 1024, 0)
        System.arraycopy(wave, 1024 + 343, cap, 0, 1024)      // 70Hz の波の半周期ぶん先（つなぎ目で値が大きく飛ぶ）
        assertEquals(-1024, jump.push(cap, 1024, 1200))
        assertEquals(1, jump.resets)
    }

    // ------------------------------------------------------------------ 周波数の目盛り

    @Test
    fun bandsAreSpacedByPitchWithBassInTheLeftQuarter() {
        val an = BandAnalyzer(sr, 48)
        assertEquals(49, an.edges.size)
        assertEquals(35f, an.edges[0], 0.01f)
        assertEquals(16000f, an.edges[48], 20f)
        for (b in 0 until 48) {
            val bw = an.edges[b + 1] - an.edges[b]
            assertTrue("帯の幅は解析の細かさより狭くしない", bw >= sr / 4096 - 0.01f)
        }
        // 画面の左 1/4 が低音（〜約 220Hz）、半分で約 900Hz、3/4 で約 3.8kHz
        assertEquals(216f, an.edges[12], 12f)
        assertEquals(907f, an.edges[24], 45f)
        assertEquals(3810f, an.edges[36], 190f)
        // 低い帯は長い窓、高い帯は短い窓
        assertEquals(BandAnalyzer.RES_LOW, an.resolution[0])
        assertEquals(BandAnalyzer.RES_MID, an.resolution[13])
        assertEquals(BandAnalyzer.RES_HIGH, an.resolution[47])
        // 44.1kHz でも同じ形
        val an44 = BandAnalyzer(44100f, 48)
        assertEquals(216f, an44.edges[12], 20f)
        assertEquals(16000f, an44.edges[48], 20f)
    }

    /** 波形（-1..1）を 8bit にして、600 サンプルずつ進めながら読んだことにする */
    private fun feed(st: WaveStitcher, wave: FloatArray, from: Int, to: Int, cap: ByteArray = ByteArray(1024)) {
        var end = maxOf(from, 1024)
        while (end <= to) {
            for (i in 0 until 1024) cap[i] = (wave[end - 1024 + i] * 126f).roundToInt().coerceIn(-128, 127).toByte()
            st.push(cap, 1024, 600)
            end += 600
        }
    }

    private fun tone(freq: Float, amp: Float, n: Int): FloatArray = FloatArray(n) { amp * sin(2.0 * PI * freq * it / sr).toFloat() }

    private fun bandOf(an: BandAnalyzer, f: Float): Int = (0 until an.bands).first { f < an.edges[it + 1] }

    @Test
    fun aToneLandsInItsOwnBandWithoutSpreading() {
        val an = BandAnalyzer(sr, 48)
        val p = FloatArray(48)
        for (f in floatArrayOf(41.2f, 55f, 82.4f, 110f, 220f, 440f, 1000f, 3000f, 8000f, 12000f)) {
            val st = WaveStitcher()
            feed(st, tone(f, 0.8f, 12_000), 0, 12_000)
            assertTrue(an.compute(st, p))
            val want = bandOf(an, f)
            val top = p.indices.maxByOrNull { p[it] }!!
            assertTrue("$f Hz は帯 $want のはず（いちばん強いのは $top）", abs(top - want) <= 1)
            // 近くの帯を足すと、その音の強さ（振幅の 2 乗 ÷ 2）になる
            var near = 0f
            for (b in maxOf(0, want - 3)..minOf(47, want + 3)) near += p[b]
            val expect = (0.8f * 126f).pow(2) / 2f
            // いちばん低い帯（35Hz〜）の近くの音は、すその一部が 35Hz より下に出るので少し小さく読める
            if (f < 50f) assertTrue("$f Hz の強さ", near / expect in 0.6f..1.12f)
            else assertEquals("$f Hz の強さ", 1.0, (near / expect).toDouble(), 0.12)
            // 離れた帯にはにじまない（v2.14 までは、強い音が画面の広い範囲に出ていた）
            for (b in 0 until 48) {
                if (abs(b - want) >= 6) {
                    val rel = 10f * log10(p[b] / p[top] + 1e-12f)
                    assertTrue("$f Hz: 帯 $b に ${rel.toInt()} dB のにじみ", rel < -45f)
                }
            }
        }
    }

    @Test
    fun shortHistoryStillGivesTheRightLevel() {
        // つなぎ直した直後（履歴が 1024 サンプルだけ）でも、強さは同じくらいに出る（細かさは落ちる）
        val an = BandAnalyzer(sr, 48)
        val p = FloatArray(48)
        val st = WaveStitcher()
        val w = tone(1000f, 0.8f, 4096)
        val cap = ByteArray(1024) { (w[it] * 126f).roundToInt().toByte() }
        st.push(cap, 1024, 0)
        assertTrue(an.compute(st, p))
        val want = bandOf(an, 1000f)
        var near = 0f
        for (b in want - 3..want + 3) near += p[b]
        assertEquals(1.0, (near / ((0.8f * 126f).pow(2) / 2f)).toDouble(), 0.15)
        // 履歴が 1 回ぶんに満たないときは解析しない
        assertFalse(an.compute(WaveStitcher(), p))
    }

    // ------------------------------------------------------------------ 高さ

    /** 波形を 12.5ms ずつ読んで解析し、最後の高さ（と、途中の高さの記録）を返す */
    private class Run(val an: BandAnalyzer) {
        val st = WaveStitcher()
        val mapper = SpectrumMapper(an)
        val p = FloatArray(an.bands)
        val cap = ByteArray(1024)
        val history = ArrayList<FloatArray>()
        val dips = ArrayList<Float>()

        fun play(wave: FloatArray) {
            var end = 1024
            while (end <= wave.size) {
                var peak = 0
                for (i in 0 until 1024) {
                    val v = (wave[end - 1024 + i] * 126f).roundToInt().coerceIn(-128, 127)
                    cap[i] = v.toByte()
                    if (abs(v) > peak) peak = abs(v)
                }
                st.push(cap, 1024, 600)
                if (an.compute(st, p)) {
                    history.add(mapper.map(p, null, null, peak, 0.0125f).copyOf())
                    dips.add(mapper.lastDip)
                }
                end += 600
            }
        }
    }

    @Test
    fun silenceShowsNothing() {
        val r = Run(BandAnalyzer(sr, 48))
        r.play(FloatArray(24_000))
        assertTrue(r.mapper.silent)
        assertTrue(r.history.last().all { it == 0f })
    }

    @Test
    fun bassLightsOnlyTheLeftAndHatsOnlyTheRight() {
        val an = BandAnalyzer(sr, 48)
        // ベース（55Hz と倍音）だけ → 左側だけ
        val bass = FloatArray(72_000) {
            val t = it / sr
            (0.6 * sin(2 * PI * 55.0 * t) + 0.25 * sin(2 * PI * 110.0 * t) + 0.1 * sin(2 * PI * 165.0 * t)).toFloat()
        }
        val rb = Run(an)
        rb.play(bass)
        val hb = rb.history.last()
        assertTrue("ベースの帯が光る", hb[bandOf(an, 55f)] > 0.3f)
        for (b in 16 until 48) assertEquals("ベースだけのとき、帯 $b（${an.centers[b].toInt()}Hz）は光らない", 0f, hb[b], 0.02f)

        // ハイハット（8〜12kHz の雑音のかたまり）だけ → 右側だけ
        val rnd = Random(5)
        val partials = 160
        val freqs = FloatArray(partials) { 8000f + 4000f * it / partials }
        val phases = FloatArray(partials) { (rnd.nextFloat() * 2 * PI).toFloat() }
        val hats = FloatArray(72_000) {
            val t = it / sr
            var s = 0.0
            for (k in 0 until partials) s += sin(2 * PI * freqs[k] * t + phases[k])
            val beat = (t % 0.125f) / 0.125f               // 16 分音符ごとに鳴って消える
            // 波形が頭打ちにならない大きさにする（頭打ちになると、低い音の成分ができてしまう）
            (s / partials * 3.0 * Math.exp(-beat * 5.0)).toFloat().coerceIn(-1f, 1f)
        }
        val rh = Run(an)
        rh.play(hats)
        val seen = FloatArray(48)
        val mean = FloatArray(48)
        val tail = rh.history.takeLast(80)
        for (h in tail) for (b in 0 until 48) {
            if (h[b] > seen[b]) seen[b] = h[b]
            mean[b] += h[b] / tail.size
        }
        assertTrue("ハイハットの帯が光る", seen[bandOf(an, 10000f)] > 0.3f)
        // 急に鳴り始める瞬間は、どんな音でも一瞬だけ広い音域に成分が出る（パチッという音）。その一瞬のごく小さな値を除けば、左側は光らない
        for (b in 0 until 30) {
            assertTrue("ハイハットだけのとき、帯 $b は光らない（平均 ${mean[b]}）", mean[b] < 0.01f)
            if (b < 24) assertTrue("ハイハットだけのとき、画面の左半分の帯 $b は光らない（最大 ${seen[b]}）", seen[b] < 0.2f)
        }
    }

    /** 400Hz〜8kHz に広がる、鳴りっぱなしの音（たくさんの正弦波の和。決まった乱数） */
    private fun pad(n: Int, seed: Long, fromHz: Float = 400f, toHz: Float = 8000f, count: Int = 220): FloatArray {
        val rnd = Random(seed)
        val f = FloatArray(count) { fromHz * (toHz / fromHz).pow(it.toFloat() / (count - 1)) * (1f + (rnd.nextFloat() - 0.5f) * 0.01f) }
        val ph = FloatArray(count) { (rnd.nextFloat() * 2 * PI).toFloat() }
        // 高い音ほど少し小さく（音楽らしい傾き）
        val a = FloatArray(count) { (1.0 / kotlin.math.sqrt(f[it] / fromHz.toDouble())).toFloat() }
        val out = FloatArray(n)
        for (i in 0 until n) {
            val t = i / sr
            var s = 0.0
            for (k in 0 until count) s += a[k] * sin(2 * PI * f[k] * t + ph[k])
            out[i] = s.toFloat()
        }
        val peak = out.maxOf { abs(it) }
        for (i in 0 until n) out[i] = out[i] / peak * 0.9f
        return out
    }

    @Test
    fun sustainedSoundDoesNotSinkWhenEverythingDropsTogether() {
        // Android の正規化のまね: 鳴りっぱなしの音が、1.5 秒目から 60ms のあいだだけ全体に半分（-6 dB）に読める
        // （大きなバスドラムが鳴って、その間の波形が小さく読めるのと同じ）
        val an = BandAnalyzer(sr, 48)
        val n = 96_000
        val w = pad(n, 11)
        val from = 72_000
        val to = from + 2880
        for (i in from until to) w[i] *= 0.5f
        val r = Run(an)
        r.play(w)
        val frameAt = { sample: Int -> (sample - 1024) / 600 }
        val before = r.history[frameAt(from) - 2]
        val mids = (0 until 48).filter { an.resolution[it] == BandAnalyzer.RES_HIGH && before[it] > 0.3f }
        assertTrue("鳴っている帯が十分ある（${mids.size}）", mids.size >= 12)
        // 下がっている間、そろって下がったぶん（約 -6 dB）を見つけて戻している
        val during = (frameAt(from) + 3..frameAt(to)).toList()
        val dip = during.minOf { r.dips[it] }
        assertEquals("そろって下がった量", -6.0, dip.toDouble(), 2.0)
        for (f in during) {
            val avgNow = mids.map { r.history[f][it] }.average()
            val avgBefore = mids.map { before[it] }.average()
            assertTrue("下がっている間も、鳴りっぱなしの帯は沈まない（$avgBefore → $avgNow）", avgNow > avgBefore * 0.7)
        }
    }

    @Test
    fun bandsThatReallyStopAreNotHeldUp() {
        // 音の半分（2kHz より上）が本当に止まる → そろった下がりではないので、戻さない（止まった帯は下がる）
        val an = BandAnalyzer(sr, 48)
        val n = 96_000
        val low = pad(n, 21, 400f, 2000f, 120)
        val high = pad(n, 22, 2000f, 8000f, 120)
        val from = 72_000
        val w = FloatArray(n) { 0.5f * low[it] + (if (it < from) 0.5f * high[it] else 0f) }
        val r = Run(an)
        r.play(w)
        val frameAt = { sample: Int -> (sample - 1024) / 600 }
        val after = frameAt(from) + 6..frameAt(from) + 20
        for (f in after) assertEquals("止まったのは一部の帯だけ → 戻さない", 0f, r.dips[f], 0.01f)
        val last = r.history[frameAt(from) + 20]
        val stopped = bandOf(an, 4000f)
        assertEquals("止まった帯は消える", 0f, last[stopped], 0.05f)
        assertTrue("鳴り続けている帯は残る", last[bandOf(an, 800f)] > 0.2f)
    }
    // ------------------------------------------------------------------ 高さの付け方（v2.16）

    /** 帯の強さを直接作って、高さの計算だけを動かす（1 回 12.5ms） */
    private class Feed(val an: BandAnalyzer) {
        val mapper = SpectrumMapper(an)
        val p = FloatArray(an.bands)
        val rnd = Random(11)
        val rows = ArrayList<FloatArray>()

        /**
         * 鳴りっぱなしの音（帯ごとに wobble dB の揺れ）に、kickEvery 秒ごとの低音の山（kickDb。帯 0〜9）を重ねて sec 秒ぶん流す。
         * rmsDb・peakDb は音量の読み取り（null なら測れない端末）
         */
        fun play(sec: Float, wobble: Float, kickDb: Float = 0f, kickEvery: Float = 0.5f, rmsDb: Float? = null, peakDb: Float? = null): Feed {
            val steps = (sec / 0.0125f).toInt()
            for (i in 0 until steps) {
                val t = i * 0.0125f
                val sinceKick = t % kickEvery
                val kick = if (kickDb > 0f) kickDb * Math.exp(-sinceKick / 0.08).toFloat() else 0f
                for (b in 0 until an.bands) {
                    // 全体はゆるやかな山形（中ほどが強い）。8bit の雑音より十分大きい
                    val base = 28f - 0.012f * (b - 20) * (b - 20)
                    val db = base + wobble * (rnd.nextFloat() * 2f - 1f) + (if (b < 10) kick else 0f)
                    p[b] = 10f.pow(db / 10f)
                }
                rows.add(mapper.map(p, rmsDb, peakDb, 100, 0.0125f).copyOf())
            }
            return this
        }

        /** 最後の sec 秒の、帯 from〜to の平均の高さ */
        fun mean(sec: Float, from: Int = 0, to: Int = an.bands): Float {
            val tail = rows.takeLast((sec / 0.0125f).toInt())
            var s = 0f
            for (r in tail) for (b in from until to) s += r[b]
            return s / (tail.size * (to - from))
        }

        /** 最後の sec 秒の、帯 from〜to が上の 2 段（17/18 以上）にいる割合 */
        fun topShare(sec: Float, from: Int = 0, to: Int = an.bands): Float {
            val tail = rows.takeLast((sec / 0.0125f).toInt())
            var c = 0
            for (r in tail) for (b in from until to) if (r[b] >= 17f / 18f) c++
            return c.toFloat() / (tail.size * (to - from))
        }

        fun max(sec: Float, from: Int, to: Int): Float {
            var m = 0f
            for (r in rows.takeLast((sec / 0.0125f).toInt())) for (b in from until to) if (r[b] > m) m = r[b]
            return m
        }
    }

    @Test
    fun strongBeatsRiseButDoNotStickToTheTop() {
        // 実際の曲のバスドラム: 帯の平均より 15 dB 以上跳ね上がる。v2.15 は拍のたびに左の帯が天井に届いた（利用者の録画で 14〜22%）
        for (kick in floatArrayOf(12f, 18f, 26f)) {
            val f = Feed(BandAnalyzer(sr, 48)).play(30f, wobble = 3f, kickDb = kick)
            assertTrue("拍で上がる（山 $kick dB。最大 ${f.max(10f, 0, 8)}）", f.max(10f, 0, 8) > 0.6f)
            assertTrue("上の 2 段にいる時間は少ない（山 $kick dB。${f.topShare(10f, 0, 8)}）", f.topShare(10f, 0, 8) < 0.03f)
            assertTrue("全体でも張り付かない（${f.topShare(10f)}）", f.topShare(10f) < 0.01f)
        }
    }

    @Test
    fun overallHeightIsTheSameWhateverTheInputLooksLike() {
        // 揺れの小さい音・大きい音・拍の強い音のどれでも、全体の高さの平均はほぼ同じ（自動でそろえる）
        val means = ArrayList<Float>()
        for ((wobble, kick) in listOf(2f to 0f, 6f to 0f, 3f to 14f, 9f to 20f)) {
            val f = Feed(BandAnalyzer(sr, 48)).play(40f, wobble = wobble, kickDb = kick)
            means.add(f.mean(15f))
        }
        for (m in means) assertTrue("平均の高さ $means", m in 0.33f..0.60f)
        assertTrue("入力による差が小さい $means", means.max() - means.min() < 0.15f)
    }

    @Test
    fun quietSectionsAreLowerThanLoudOnes() {
        // サビ（実効値 -9 dBFS・最大 -0.3）→ A メロ（-13.5）→ サビ。A メロは 30 秒続いても低いまま
        val f = Feed(BandAnalyzer(sr, 48))
        f.play(20f, 4f, 12f, rmsDb = -9f, peakDb = -0.3f)
        val chorus = f.mean(8f)
        f.play(30f, 4f, 12f, rmsDb = -13.5f, peakDb = -1f)
        val verseEnd = f.mean(8f)
        f.play(15f, 4f, 12f, rmsDb = -9f, peakDb = -0.3f)
        val chorus2 = f.mean(8f)
        assertTrue("A メロはサビより低い（$verseEnd / $chorus）", verseEnd < chorus * 0.8f)
        assertTrue("A メロも見える（$verseEnd）", verseEnd > chorus * 0.4f)
        assertEquals("サビに戻ると元の高さ", chorus.toDouble(), chorus2.toDouble(), chorus * 0.1)
        // 静かな間奏（-21）はもっと低い
        f.play(8f, 4f, 0f, rmsDb = -21f, peakDb = -9f)
        assertTrue("静かな間奏（${f.mean(4f)} / $chorus）", f.mean(4f) < chorus * 0.45f)
    }

    @Test
    fun quietIntroIsLowFromTheFirstPlay() {
        // 曲の頭の静かな前奏（まだ大きい音を聞いていない）→ 盛り上がる部分
        val f = Feed(BandAnalyzer(sr, 48))
        f.play(10f, 4f, 0f, rmsDb = -24f, peakDb = -7f)
        val intro = f.mean(5f)
        f.play(15f, 4f, 12f, rmsDb = -9.5f, peakDb = -0.3f)
        val loud = f.mean(6f)
        assertTrue("前奏は低い（$intro / $loud）", intro < loud * 0.7f)
    }

    @Test
    fun loudnessDoesNotDependOnTheVolumeSetting() {
        // 音量つまみを下げる（実効値も最大値も 20 dB 下がる）→ 高さは変わらない
        val a = Feed(BandAnalyzer(sr, 48)).play(20f, 4f, 12f, rmsDb = -13f, peakDb = -1f)
        val b = Feed(BandAnalyzer(sr, 48)).play(20f, 4f, 12f, rmsDb = -33f, peakDb = -21f)
        assertEquals(a.mean(8f).toDouble(), b.mean(8f).toDouble(), 0.02)
        // 再生中に下げたとき（アプリが音量の変化に気づいて resetLoudness を呼ぶ）
        a.mapper.resetLoudness()
        a.play(10f, 4f, 12f, rmsDb = -33f, peakDb = -21f)
        assertEquals(b.mean(8f).toDouble(), a.mean(8f).toDouble(), 0.03)
        // 音量が測れない端末は、強弱を付けない（元の高さ）
        val c = Feed(BandAnalyzer(sr, 48)).play(20f, 4f, 12f)
        assertEquals(-1f, c.mapper.loudness, 0f)
        assertTrue(c.mean(8f) > a.mean(8f))
    }
}

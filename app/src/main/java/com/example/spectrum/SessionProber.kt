package com.example.spectrum

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.Visualizer
import android.os.SystemClock
import kotlin.math.abs

/**
 * 音声の番号（オーディオセッション）を自分から知らせないアプリ（Amazon Music など）の音を、
 * DUMP 権限（Shizuku）なしで見つける。
 *
 * Android は新しい音声の番号を、端末全体で 8 ずつ増やしながら順に割り当てる。そこで、いま割り当てられる
 * 番号から少しずつさかのぼって解析用の Visualizer を付け、音が出ている番号を探す
 * （音の大きさを測るだけで、録音はしない。必要な権限はスペアナと同じマイク権限のみ）。
 * 見つけた番号はアプリごとに覚えておき、次からはすぐに使う。
 */
object SessionProber {

    /** 音声の番号の間隔（Android の内部で、番号の種類を下位 3 ビットで表すため） */
    private const val STEP = 8
    /** 一度に調べる番号の数 */
    private const val BATCH = 16
    /** さかのぼる番号の数の上限（新しいものから） */
    private const val MAX_CANDIDATES = 2400
    /** Visualizer を付けてから音を測るまで */
    private const val SETTLE_MS = 230L
    /** 確認のために測り直すまで */
    private const val CONFIRM_MS = 450L
    /** 音が出ているとみなす大きさ（-60dB、単位は mB） */
    private const val AUDIBLE_MB = -6000
    /** 波形での判定（中心 128 からのずれ） */
    private const val AUDIBLE_WAVE = 3

    /** 直近の結果（診断画面用） */
    @Volatile
    var lastStatus = "未実行"
        private set

    @Volatile
    var running = false
        private set

    /**
     * 音が出ている番号を探す（時間がかかるので専用のスレッドから呼ぶ）。
     * @param exclude 調べない番号（他のアプリから知らされた番号など）
     * @param stillWanted 探し続けてよいか（再生が止まった・別のアプリになったら false）
     * @return 音が出ている番号。見つからなければ null
     */
    fun probe(ctx: Context, exclude: Set<Int>, stillWanted: () -> Boolean): Int? {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return null
        val top = try {
            am.generateAudioSessionId()
        } catch (e: Exception) {
            lastStatus = "番号を取得できません"
            return null
        }
        if (top <= 0) {
            lastStatus = "番号を取得できません"
            return null
        }
        running = true
        val started = SystemClock.uptimeMillis()
        lastStatus = "探しています…"
        try {
            var next = top - STEP
            var checked = 0
            while (next > 0 && checked < MAX_CANDIDATES) {
                if (!stillWanted()) {
                    lastStatus = "中断（再生が止まった・曲のアプリが変わった）"
                    return null
                }
                val batch = ArrayList<Int>(BATCH)
                while (batch.size < BATCH && next > 0 && checked < MAX_CANDIDATES) {
                    if (next !in exclude) batch.add(next)
                    next -= STEP
                    checked++
                }
                val hits = measure(batch, SETTLE_MS)
                if (hits.isEmpty()) continue
                // もう一度測って、続けて音が出ているもの（＝曲）を選ぶ。案内の音声などの短い音は除く
                val again = measure(hits.map { it.first }, CONFIRM_MS)
                val best = again.maxByOrNull { it.second }
                if (best != null) {
                    val sec = (SystemClock.uptimeMillis() - started) / 1000
                    lastStatus = "見つかりました（セッション${best.first}・${checked}件目・${sec}秒）"
                    return best.first
                }
            }
            lastStatus = "見つかりませんでした（${checked}件を確認）"
            return null
        } finally {
            running = false
        }
    }

    /**
     * 番号ごとに Visualizer を付けて、音の大きさを測る。
     * @return 音が出ている番号と大きさ（mB）
     */
    private fun measure(ids: List<Int>, settleMs: Long): List<Pair<Int, Int>> {
        if (ids.isEmpty()) return emptyList()
        val vs = ArrayList<Pair<Int, Visualizer>>(ids.size)
        try {
            val minSize = try {
                Visualizer.getCaptureSizeRange()[0]
            } catch (e: Exception) {
                128
            }
            for (id in ids) {
                val v = try {
                    Visualizer(id)
                } catch (e: Throwable) {
                    null
                } ?: continue
                try {
                    v.enabled = false
                    v.captureSize = minSize
                    try {
                        v.measurementMode = Visualizer.MEASUREMENT_MODE_PEAK_RMS
                    } catch (_: Exception) {
                    }
                    v.enabled = true
                    vs.add(id to v)
                } catch (e: Throwable) {
                    try {
                        v.release()
                    } catch (_: Throwable) {
                    }
                }
            }
            if (vs.isEmpty()) return emptyList()
            Thread.sleep(settleMs)
            val out = ArrayList<Pair<Int, Int>>()
            val m = Visualizer.MeasurementPeakRms()
            for ((id, v) in vs) {
                val rms = try {
                    if (v.getMeasurementPeakRms(m) == Visualizer.SUCCESS) m.mRms else Int.MIN_VALUE
                } catch (e: Throwable) {
                    Int.MIN_VALUE
                }
                if (rms != Int.MIN_VALUE) {
                    if (rms > AUDIBLE_MB) out.add(id to rms)
                    continue
                }
                // 音の大きさを測れない端末: 波形のゆれで判定
                val buf = ByteArray(v.captureSize)
                val ok = try {
                    v.getWaveForm(buf) == Visualizer.SUCCESS
                } catch (e: Throwable) {
                    false
                }
                if (!ok) continue
                var dev = 0
                for (b in buf) dev = maxOf(dev, abs((b.toInt() and 0xFF) - 128))
                if (dev > AUDIBLE_WAVE) out.add(id to -3000 + dev)
            }
            return out
        } catch (e: InterruptedException) {
            return emptyList()
        } finally {
            for ((_, v) in vs) {
                try {
                    v.enabled = false
                } catch (_: Throwable) {
                }
                try {
                    v.release()
                } catch (_: Throwable) {
                }
            }
        }
    }
}

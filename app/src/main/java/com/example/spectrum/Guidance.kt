package com.example.spectrum

import android.graphics.Bitmap
import android.os.SystemClock
import java.util.Locale

/**
 * 音楽の画面に出す、カーナビの次の案内（統合したアプリでは Google マップのナビの通知から入れる）。
 * 高速道路の外（一般道）でも、ナビ中はスペアナの上に「矢印・距離・案内の文」の帯を出す（[PlayerRenderer]）。
 * Spectrum 単体では何も入らない（text が null の間は何も出さない）。
 */
object Guidance {

    const val ARROW_NONE = 0
    const val ARROW_STRAIGHT = 1
    const val ARROW_LEFT = 2
    const val ARROW_RIGHT = 3
    const val ARROW_SLIGHT_LEFT = 4
    const val ARROW_SLIGHT_RIGHT = 5
    const val ARROW_UTURN = 6
    const val ARROW_DEST = 7

    /** 案内が更新されないまま、この時間が過ぎたら出さない（ナビアプリが止まったときなど） */
    private const val STALE_MS = 10L * 60L * 1000L

    /** 設定で出すかどうか（統合したアプリの設定「音楽の画面にナビの案内を出す」） */
    @Volatile var enabled = true

    /** 案内の文（例「〇〇交差点を右折」） */
    @Volatile var text: String? = null
        private set

    /** 案内地点までの距離 [m]（分からなければ null） */
    @Volatile var distanceM: Double? = null
        private set

    /** 曲がる方向の矢印（ナビアプリの画像を白くしたもの。無ければ [arrow] を描く） */
    @Volatile var icon: Bitmap? = null
        private set

    /** 画像が無いときの矢印（案内の文から推定。ARROW_*） */
    @Volatile var arrow = ARROW_NONE
        private set

    /** ナビアプリの名前 */
    @Volatile var source = "Google マップ"
        private set

    @Volatile var updatedAt = 0L
        private set

    /** 内容が変わるたびに増える（描く側が文字の組み立てを作り直すかを決める） */
    @Volatile var version = 0
        private set

    val active: Boolean
        get() = enabled && !text.isNullOrBlank() && SystemClock.elapsedRealtime() - updatedAt < STALE_MS

    fun set(text: String, distanceM: Double?, icon: Bitmap?, source: String) {
        val changed = text != this.text || distanceM != this.distanceM || icon !== this.icon || source != this.source
        this.text = text
        this.distanceM = distanceM
        this.icon = icon
        this.arrow = arrowFor(text)
        this.source = source
        updatedAt = SystemClock.elapsedRealtime()
        if (changed) version++
    }

    fun clear() {
        if (text == null && icon == null) return
        text = null
        distanceM = null
        icon = null
        arrow = ARROW_NONE
        version++
    }

    /** 案内の文から、曲がる方向を推定（ナビアプリの矢印の画像が無いとき用） */
    fun arrowFor(t: String): Int {
        val s = t.replace(" ", "").replace("　", "").lowercase(Locale.ROOT)
        fun has(vararg w: String) = w.any { s.contains(it) }
        return when {
            has("uターン", "ｕターン", "u-turn", "uturn", "makeau") -> ARROW_UTURN
            has("目的地", "到着", "destination", "arrive") -> ARROW_DEST
            has("斜め右", "やや右", "右方向", "右寄り", "右側の車線", "slightright", "keepright", "bearright") -> ARROW_SLIGHT_RIGHT
            has("斜め左", "やや左", "左方向", "左寄り", "左側の車線", "slightleft", "keepleft", "bearleft") -> ARROW_SLIGHT_LEFT
            has("右折", "右へ", "右に曲", "turnright") -> ARROW_RIGHT
            has("左折", "左へ", "左に曲", "turnleft") -> ARROW_LEFT
            has("直進", "道なり", "continue", "straight", "headnorth", "headsouth", "headeast", "headwest") -> ARROW_STRAIGHT
            else -> ARROW_NONE
        }
    }

    /** 距離の表示（「300m」「1.2km」「12km」） */
    fun distanceLabel(m: Double): String = when {
        m < 0 -> "0m"
        m < 1000 -> "${(Math.round(m / 10.0) * 10).coerceAtLeast(0)}m"
        m < 10_000 -> String.format(Locale.US, "%.1fkm", m / 1000.0)
        else -> "${Math.round(m / 1000.0)}km"
    }
}

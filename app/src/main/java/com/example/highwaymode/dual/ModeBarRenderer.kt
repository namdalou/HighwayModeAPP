package com.example.highwaymode.dual

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import com.example.highwaymode.core.dual.DualLayout
import com.example.highwaymode.core.engine.EngineStatus
import com.example.highwaymode.core.engine.HighwaySnapshot
import com.example.highwaymode.core.facility.FacilityType
import com.example.highwaymode.core.traffic.TrafficKind
import com.example.highwaymode.ui.Formats
import com.example.spectrum.NowPlaying
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 下部の切替バー（車の画面・スマホで共用）。
 *
 *  右側（車では運転席に近い側）: ［♪ 音楽］［2画面］［ハイウェイ］（スマホは右端に［⋮］メニュー）
 *  左側（幅に余裕があるとき）:
 *   - 音楽だけの表示中 … 次の IC/SA などまでの距離・到着時刻（オービス 2km 以内・通行止め・渋滞を優先）
 *   - ハイウェイだけの表示中 … 再生中の曲と 再生/一時停止
 *   - 2画面 … 何も出さない（両方見えているため）
 */
class ModeBarRenderer(var density: Float) {

    enum class Target { MUSIC, SPLIT, HIGHWAY, MENU, PLAY_PAUSE, OPEN_SPLIT }

    class Hit(val rect: RectF, val target: Target)

    /** スマホ: 右端にメニューボタン */
    var showMenuButton = false

    /** 対象エリア外で音楽だけを表示しているときの案内（スマホ横画面など） */
    var idleHint: String? = null

    /** 2 画面のときの案内（例:「高速道路を出ると自動で音楽に戻ります」）。null なら何も出さない */
    var splitHint: String? = null

    @Volatile
    private var published: List<Hit> = emptyList()
    private val hits = ArrayList<Hit>()

    private fun dp(v: Float) = v * density

    // ---- 色（Spectrum の配色に合わせる）
    private val cBg = Color.rgb(7, 13, 19)
    private val cEdge = Color.rgb(30, 46, 60)
    private val cSeg = Color.rgb(19, 30, 41)
    private val cSegEdge = Color.rgb(40, 58, 74)
    private val cSel = Color.rgb(40, 190, 245)
    private val cSelText = Color.rgb(3, 26, 40)
    private val cText = Color.rgb(212, 226, 236)
    private val cSub = Color.rgb(128, 150, 166)
    private val cGreen = 0xFF1E8E3E.toInt()
    private val cBlue = 0xFF1565C0.toInt()
    private val cRed = 0xFFE53935.toInt()
    private val cOrange = 0xFFF08C00.toInt()
    private val cDarkRed = 0xFFB71C1C.toInt()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val textN = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()

    fun draw(c: Canvas, l: Float, t: Float, r: Float, b: Float, layout: DualLayout, snap: HighwaySnapshot?) {
        hits.clear()
        fill.color = cBg
        c.drawRect(l, t, r, b, fill)
        stroke.color = cEdge
        stroke.strokeWidth = max(1f, dp(1f))
        c.drawLine(l, t + stroke.strokeWidth / 2, r, t + stroke.strokeWidth / 2, stroke)

        val h = b - t
        val pad = min(h * 0.13f, dp(8f))
        val segT = t + pad
        val segB = b - pad
        val segH = segB - segT
        val gap = dp(4f)
        val menuW = if (showMenuButton) segH * 1.1f else 0f
        val width = r - l
        // 幅に余裕があれば、左側に情報を出して切替ボタンは右側にまとめる
        val wide = width >= dp(520f)
        val segW = if (wide) min(dp(150f), (width * 0.56f - menuW) / 3f) else (width - 2 * pad - 2 * gap - (if (showMenuButton) menuW + gap else 0f)) / 3f
        val groupW = segW * 3 + gap * 2 + (if (showMenuButton) gap + menuW else 0f)
        val segL = if (wide) r - pad - groupW else l + pad

        val items = listOf(
            Triple(Target.MUSIC, DualLayout.MUSIC.label, layout == DualLayout.MUSIC),
            Triple(Target.SPLIT, DualLayout.SPLIT.label, layout == DualLayout.SPLIT),
            Triple(Target.HIGHWAY, DualLayout.HIGHWAY.label, layout == DualLayout.HIGHWAY)
        )
        // 3 つのボタンで文字の大きさとアイコンの有無をそろえる（いちばん長い「ハイウェイ」に合わせる）
        val iconSize = segH * 0.46f
        val iconGap = segH * 0.14f
        val avail = segW - segH * 0.3f
        var size = min(segH * 0.36f, dp(18f))
        text.textSize = size
        val longest = items.maxOf { text.measureText(it.second) }
        var withIcon = true
        if (iconSize + iconGap + longest > avail) {
            // まず文字を少し小さくしてアイコンを残す。それでも入らなければアイコンを省く
            val fitWithIcon = size * (avail - iconSize - iconGap) / longest
            if (fitWithIcon >= size * 0.78f) {
                size = fitWithIcon
            } else {
                withIcon = false
                if (longest > avail) size *= avail / longest
            }
        }
        var x = segL
        for ((target, label, selected) in items) {
            rect.set(x, segT, x + segW, segB)
            drawSegment(c, rect, target, label, selected, size, if (withIcon) iconSize else 0f, iconGap)
            hits.add(Hit(RectF(x, t, x + segW, b), target))
            x += segW + gap
        }
        if (showMenuButton) {
            rect.set(x, segT, x + menuW, segB)
            fill.color = cSeg
            c.drawRoundRect(rect, segH * 0.22f, segH * 0.22f, fill)
            stroke.color = cSegEdge
            stroke.strokeWidth = dp(1f)
            c.drawRoundRect(rect, segH * 0.22f, segH * 0.22f, stroke)
            fill.color = cText
            val cx = rect.centerX()
            val rr = segH * 0.065f
            for (k in -1..1) c.drawCircle(cx, rect.centerY() + k * segH * 0.2f, rr, fill)
            hits.add(Hit(RectF(x, t, x + menuW, b), Target.MENU))
        }

        if (wide) {
            val cr = segL - pad * 1.5f
            if (cr - (l + pad) > dp(120f)) drawContext(c, l + pad, segT, cr, segB, layout, snap)
        }
        published = ArrayList(hits)
    }

    /** タップされた場所の操作（描画と別スレッドから呼んでよい） */
    fun hitAt(x: Float, y: Float): Target? = published.firstOrNull { it.rect.contains(x, y) }?.target

    // ================================================================== 切替ボタン

    /** @param icon アイコンの大きさ（0 ならアイコンなし） */
    private fun drawSegment(c: Canvas, r: RectF, target: Target, label: String, selected: Boolean, textSize: Float, icon: Float, gapIcon: Float) {
        val h = r.height()
        val rad = h * 0.22f
        fill.color = if (selected) cSel else cSeg
        c.drawRoundRect(r, rad, rad, fill)
        if (!selected) {
            stroke.color = cSegEdge
            stroke.strokeWidth = dp(1f)
            c.drawRoundRect(r, rad, rad, stroke)
        }
        val fg = if (selected) cSelText else cText
        text.textSize = textSize
        text.color = fg
        val tw = text.measureText(label)
        val total = if (icon > 0f) icon + gapIcon + tw else tw
        var x = r.centerX() - total / 2
        val cy = r.centerY()
        if (icon > 0f) {
            drawIcon(c, target, x, cy - icon / 2, icon, fg)
            x += icon + gapIcon
        }
        drawTextCentered(c, label, x, cy, text, Paint.Align.LEFT)
    }

    /** 音楽: 音符 / 2画面: 左右に分かれた画面 / ハイウェイ: 道路（路肩の線と中央の破線） */
    private fun drawIcon(c: Canvas, target: Target, x: Float, y: Float, s: Float, fg: Int) {
        fill.color = fg
        when (target) {
            Target.MUSIC -> {
                // 八分音符 2 つ（連桁）
                val stemW = s * 0.11f
                val hx1 = x + s * 0.28f
                val hx2 = x + s * 0.78f
                val hy1 = y + s * 0.80f
                val hy2 = y + s * 0.70f
                c.drawOval(RectF(hx1 - s * 0.2f, hy1 - s * 0.14f, hx1 + s * 0.12f, hy1 + s * 0.14f), fill)
                c.drawOval(RectF(hx2 - s * 0.2f, hy2 - s * 0.14f, hx2 + s * 0.12f, hy2 + s * 0.14f), fill)
                c.drawRect(hx1 + s * 0.12f - stemW, y + s * 0.18f, hx1 + s * 0.12f, hy1, fill)
                c.drawRect(hx2 + s * 0.12f - stemW, y + s * 0.08f, hx2 + s * 0.12f, hy2, fill)
                path.reset()
                path.moveTo(hx1 + s * 0.12f - stemW, y + s * 0.18f)
                path.lineTo(hx2 + s * 0.12f, y + s * 0.08f)
                path.lineTo(hx2 + s * 0.12f, y + s * 0.26f)
                path.lineTo(hx1 + s * 0.12f - stemW, y + s * 0.36f)
                path.close()
                c.drawPath(path, fill)
            }
            Target.SPLIT -> {
                val rr = s * 0.08f
                c.drawRoundRect(RectF(x, y + s * 0.12f, x + s * 0.38f, y + s * 0.88f), rr, rr, fill)
                c.drawRoundRect(RectF(x + s * 0.46f, y + s * 0.12f, x + s, y + s * 0.88f), rr, rr, fill)
            }
            Target.HIGHWAY -> {
                stroke.color = fg
                stroke.strokeWidth = s * 0.13f
                stroke.strokeCap = Paint.Cap.ROUND
                c.drawLine(x + s * 0.30f, y + s * 0.10f, x + s * 0.06f, y + s * 0.92f, stroke)
                c.drawLine(x + s * 0.70f, y + s * 0.10f, x + s * 0.94f, y + s * 0.92f, stroke)
                stroke.strokeCap = Paint.Cap.BUTT
                c.drawRect(x + s * 0.455f, y + s * 0.10f, x + s * 0.545f, y + s * 0.28f, fill)
                c.drawRect(x + s * 0.445f, y + s * 0.42f, x + s * 0.555f, y + s * 0.62f, fill)
                c.drawRect(x + s * 0.435f, y + s * 0.76f, x + s * 0.565f, y + s * 0.95f, fill)
            }
            else -> {}
        }
    }

    // ================================================================== 左側の情報

    private fun drawContext(c: Canvas, l: Float, t: Float, r: Float, b: Float, layout: DualLayout, s: HighwaySnapshot?) {
        when (layout) {
            DualLayout.MUSIC -> {
                val onHw = s != null && (s.status == EngineStatus.ON_HIGHWAY || s.status == EngineStatus.REST_STOP)
                if (onHw) drawTicker(c, l, t, r, b, s!!)
                else idleHint?.let { hint ->
                    textN.color = cSub
                    textN.textSize = fitSize(textN, hint, r - l, min((b - t) * 0.32f, dp(14f)))
                    drawTextCentered(c, ellipsize(textN, hint, r - l), l, (t + b) / 2, textN, Paint.Align.LEFT)
                }
            }
            DualLayout.HIGHWAY -> drawNowPlaying(c, l, t, r, b)
            DualLayout.SPLIT -> splitHint?.let { hint ->
                textN.color = cSub
                textN.textSize = fitSize(textN, hint, r - l, min((b - t) * 0.32f, dp(14f)))
                drawTextCentered(c, ellipsize(textN, hint, r - l), l, (t + b) / 2, textN, Paint.Align.LEFT)
            }
        }
    }

    /** 次の施設（注意があればそちらを優先）。タップで 2 画面へ */
    private fun drawTicker(c: Canvas, l: Float, t: Float, r: Float, b: Float, s: HighwaySnapshot) {
        val h = b - t
        val cam = s.cameras.firstOrNull()?.takeIf { it.distanceM < 2000 }
        val closure = s.traffic?.events?.firstOrNull { it.second == TrafficKind.CLOSURE && it.first < 50_000 }
        val tr = s.traffic
        val jamD = tr?.nextJamDistanceM
        val jamL = tr?.nextJamLengthM
        val next = s.facilities.firstOrNull { it.type != FacilityType.CAMERA }
        val badge: String
        val badgeColor: Int
        val main: String
        val sub: String?
        when {
            cam != null -> {
                badge = "オービス"
                badgeColor = cRed
                main = "${Formats.distance(cam.distanceM)}先"
                sub = cam.maxspeedKmh?.let { "制限${it}km/h" }
            }
            closure != null -> {
                badge = "通行止"
                badgeColor = cDarkRed
                main = "${Formats.distance(closure.first)}先"
                sub = closure.third?.take(16)
            }
            jamD != null && jamL != null && jamD < 10_000 -> {
                badge = "渋滞"
                badgeColor = if (jamL >= 1000) cRed else cOrange
                main = "${Formats.distance(jamD)}先 ${Formats.distance(jamL)}"
                sub = tr.nextJamDelaySec?.let { if (it >= 60) "+${(it / 60).roundToInt()}分" else null }
            }
            next != null -> {
                badge = if (next.type == FacilityType.TB) "料金所" else next.type.badge
                badgeColor = if (next.type == FacilityType.TB) cBlue else cGreen
                main = next.name.ifEmpty { next.displayName }
                sub = Formats.distance(next.distanceM) + "  " + (if (next.closedBefore) "通行止" else Formats.clock(next.etaEpochMs) + "着")
            }
            else -> {
                badge = ""
                badgeColor = cGreen
                main = s.roadName ?: "高速道路"
                sub = null
            }
        }
        var x = l
        val bh = h * 0.62f
        if (badge.isNotEmpty()) {
            text.textSize = bh * 0.5f
            val bw = max(bh * 1.25f, text.measureText(badge) + bh * 0.4f)
            rect.set(x, (t + b) / 2 - bh / 2, x + bw, (t + b) / 2 + bh / 2)
            fill.color = badgeColor
            c.drawRoundRect(rect, dp(4f), dp(4f), fill)
            text.color = Color.WHITE
            drawTextCentered(c, badge, rect.centerX(), rect.centerY(), text, Paint.Align.CENTER)
            x += bw + h * 0.18f
        }
        // 右端の「›」（タップで 2 画面）
        val chevW = h * 0.36f
        val textR = r - chevW - h * 0.1f
        text.color = cText
        text.textSize = min(h * 0.42f, dp(19f))
        val subW = sub?.let { textN.textSize = min(h * 0.36f, dp(16f)); textN.measureText(it) } ?: 0f
        val mainMax = max(h, textR - x - (if (sub != null) subW + h * 0.25f else 0f))
        val mainShown = ellipsize(text, main, mainMax)
        drawTextCentered(c, mainShown, x, (t + b) / 2, text, Paint.Align.LEFT)
        x += text.measureText(mainShown) + h * 0.25f
        if (sub != null && x + subW <= textR + 0.5f) {
            textN.color = cText
            drawTextCentered(c, sub, x, (t + b) / 2, textN, Paint.Align.LEFT)
        }
        stroke.color = cSub
        stroke.strokeWidth = max(1.5f, h * 0.05f)
        stroke.strokeCap = Paint.Cap.ROUND
        val cx = r - chevW / 2
        val cy = (t + b) / 2
        c.drawLine(cx - chevW * 0.18f, cy - h * 0.16f, cx + chevW * 0.18f, cy, stroke)
        c.drawLine(cx + chevW * 0.18f, cy, cx - chevW * 0.18f, cy + h * 0.16f, stroke)
        stroke.strokeCap = Paint.Cap.BUTT
        hits.add(Hit(RectF(l, t, r, b), Target.OPEN_SPLIT))
    }

    /** 再生中の曲と 再生/一時停止。曲名側のタップで 2 画面へ */
    private fun drawNowPlaying(c: Canvas, l: Float, t: Float, r: Float, b: Float) {
        val h = b - t
        // 再生/一時停止（丸いボタン）
        val bs = h
        val bx = r - bs
        rect.set(bx, t, r, b)
        fill.color = cSel
        c.drawOval(rect, fill)
        fill.color = cSelText
        val cx = rect.centerX()
        val cy = rect.centerY()
        val q = bs * 0.2f
        if (NowPlaying.isPlaying) {
            val bw = q * 0.62f
            c.drawRect(cx - q * 0.85f, cy - q, cx - q * 0.85f + bw, cy + q, fill)
            c.drawRect(cx + q * 0.85f - bw, cy - q, cx + q * 0.85f, cy + q, fill)
        } else {
            path.reset()
            path.moveTo(cx - q * 0.6f, cy - q)
            path.lineTo(cx - q * 0.6f, cy + q)
            path.lineTo(cx + q, cy)
            path.close()
            c.drawPath(path, fill)
        }
        hits.add(Hit(RectF(bx - h * 0.15f, t, r, b), Target.PLAY_PAUSE))

        // ジャケット
        var x = l
        val art: Bitmap? = NowPlaying.art
        rect.set(x, t, x + h, b)
        if (art != null && !art.isRecycled) {
            val side = min(art.width, art.height)
            val src = Rect((art.width - side) / 2, (art.height - side) / 2, (art.width + side) / 2, (art.height + side) / 2)
            c.drawBitmap(art, src, rect, bmpPaint)
        } else {
            fill.color = Color.rgb(20, 40, 55)
            c.drawRoundRect(rect, dp(4f), dp(4f), fill)
            textN.color = Color.rgb(110, 140, 160)
            textN.textSize = h * 0.5f
            drawTextCentered(c, "♪", rect.centerX(), rect.centerY(), textN, Paint.Align.CENTER)
        }
        x += h + h * 0.22f
        val maxW = bx - h * 0.25f - x
        if (maxW < dp(40f)) return
        val title = NowPlaying.title.ifEmpty { "曲情報なし" }
        val artist = NowPlaying.artist
        if (artist.isNotEmpty() && h >= dp(46f)) {
            text.color = Color.WHITE
            text.textSize = min(h * 0.36f, dp(17f))
            drawTextCentered(c, ellipsize(text, title, maxW), x, t + h * 0.32f, text, Paint.Align.LEFT)
            textN.color = cSub
            textN.textSize = min(h * 0.30f, dp(14f))
            drawTextCentered(c, ellipsize(textN, artist, maxW), x, t + h * 0.74f, textN, Paint.Align.LEFT)
        } else {
            text.color = Color.WHITE
            text.textSize = min(h * 0.40f, dp(18f))
            val line = if (artist.isNotEmpty()) "$title — $artist" else title
            drawTextCentered(c, ellipsize(text, line, maxW), x, (t + b) / 2, text, Paint.Align.LEFT)
        }
        hits.add(Hit(RectF(l, t, bx - h * 0.15f, b), Target.OPEN_SPLIT))
    }

    // ================================================================== 文字

    private fun drawTextCentered(c: Canvas, s: String, x: Float, cy: Float, p: Paint, align: Paint.Align) {
        p.textAlign = align
        val fm = p.fontMetrics
        c.drawText(s, x, cy - (fm.ascent + fm.descent) / 2, p)
        p.textAlign = Paint.Align.LEFT
    }

    private fun ellipsize(p: Paint, s: String, maxWidth: Float): String {
        if (p.measureText(s) <= maxWidth) return s
        var lo = 0
        var hi = s.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (p.measureText(s.substring(0, mid) + "…") <= maxWidth) lo = mid else hi = mid - 1
        }
        return if (lo <= 0) "…" else s.substring(0, lo) + "…"
    }

    private fun fitSize(p: Paint, s: String, maxWidth: Float, size: Float): Float {
        if (maxWidth <= 0) return size * 0.5f
        p.textSize = size
        val w = p.measureText(s)
        return if (w <= maxWidth) size else max(size * 0.6f, size * maxWidth / w)
    }
}

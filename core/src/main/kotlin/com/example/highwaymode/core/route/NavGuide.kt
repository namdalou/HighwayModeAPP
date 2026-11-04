package com.example.highwaymode.core.route

import com.example.highwaymode.core.facility.Facility
import com.example.highwaymode.core.facility.FacilityType
import com.example.highwaymode.core.graph.Edge
import com.example.highwaymode.core.graph.NameNorm
import com.example.highwaymode.core.graph.RoadContinuity
import com.example.highwaymode.core.graph.RoadGraph
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * カーナビ（Google マップなど）の次の案内。ナビの通知から読み取った文（「豊田JCT で 東名高速道路 東京方面へ」など）と、
 * 案内地点までの距離。前方経路の分岐の選び方と、降りる IC の判定に使う。
 */
class NavHint(
    /** 案内の文（通知の文字をつなげたもの） */
    val text: String,
    /** 案内地点までの距離 [m]（受け取った時点。通知に無ければ null） */
    val distanceM: Double?,
    /** 受け取ったときの走行距離 [m]（エンジンの積算距離） */
    val odometerAtM: Double,
    /** ナビアプリの名前（表示用） */
    val source: String = "Google マップ"
) {
    /** 照合用の文（全角→半角、空白・記号を除き、英字は大文字） */
    val norm: String = NavGuide.normalize(text)

    /** 出口（降りる）を思わせる言葉がある */
    val exitWords: Boolean = NavGuide.EXIT_WORDS.any { norm.contains(it) }

    /** いまの案内地点までの距離（受け取ってから進んだ分を引く） */
    fun distanceNow(odometerM: Double): Double? = distanceM?.let { it - (odometerM - odometerAtM) }
}

/** 分岐で進む先を選ぶ（前方経路を作るときに分岐ごとに呼ぶ） */
fun interface ForkChooser {
    /**
     * @param dist 分岐までの道のり [m]（前方経路の始まりから）
     * @param successors 後続エッジと評価（RoadContinuity の順）
     * @param default いつもの選び方で選んだ後続エッジ
     * @return 進む後続エッジ
     */
    fun choose(graph: RoadGraph, dist: Double, cur: Edge, successors: List<Pair<Int, Double>>, default: Int): Int
}

/** 前方経路をナビに合わせた結果 */
class NavMatch(
    /** 降りる IC の施設 id と、前方経路の始まりからの道のり [m] */
    val exitId: String?,
    val exitDistance: Double?,
    val exitName: String?,
    /** 乗り換える JCT の施設 id と、乗り換え先（例「東名高速道路 東京方面」） */
    val transferId: String?,
    val transferText: String?,
    /** ナビの案内と前方経路（施設の名前・分岐の先の路線）が合った */
    val matched: Boolean
)

/**
 * カーナビの案内に合わせて、前方経路（JCT の乗り換え先）と降りる IC を決める。
 *
 * 案内の文の言い回しはナビアプリ・版によって変わるので、文を細かく読み解くのではなく、
 * 「前方経路の施設の名前（豊田JCT・豊田IC…）」「分岐の先の本線の名前・路線番号」「ランプの行き先（東京方面…）」が
 * 案内の文に含まれるかで照合する。案内地点までの距離が分かれば、その付近の施設・分岐だけを見る。
 *
 *  1. いつもの前方経路（同じ路線を優先）で、案内に名前が出てくる JCT・IC を探す
 *  2. JCT なら（または名前が出なくても案内地点の付近の分岐で）、分岐の先が案内の路線・方面に合う方へ進むよう作り直す
 *  3. IC で、乗り換えの案内でなければ「降りる IC」。その先の施設は出さない
 */
object NavGuide {

    /** 出口を思わせる言葉（「下り」は上り・下り線と紛らわしいので使わない） */
    val EXIT_WORDS = listOf("出口", "降り", "おり", "出る", "出て", "下車")
    private val JCT_WORDS = listOf("JCT", "ジャンクション")
    /** 高速道路・有料道路に入る案内（乗り換え）を思わせる言葉 */
    private val ENTER_WORDS = listOf("に入", "へ入", "合流", "に進入")
    private val EXPRESS_WORDS = listOf("自動車道", "高速", "有料", "首都高", "阪神高速", "名古屋高速", "都市高速", "道路")

    private val STRIP = Regex("[\\s・,、。()（）「」『』\\[\\]/:：]")

    fun normalize(s: String): String = NameNorm.toHalfWidth(s).replace(STRIP, "").uppercase()

    /** 道路名の種類の語（照合ではこれを除いた「核」でも探す。東名高速道路 → 東名） */
    private val ROAD_SUFFIXES = listOf("高速自動車国道", "自動車道", "高速道路", "有料道路", "連絡道路", "高速", "道路", "バイパス", "BP", "線")

    fun core(n: String): String {
        for (suf in ROAD_SUFFIXES) {
            if (n.endsWith(suf) && n.length > suf.length + 1) return n.dropLast(suf.length)
        }
        return n
    }

    /** 道路の名前が案内の文に出てくるか（名前全体なら高く、核だけなら低め。長い名前ほど高い） */
    fun roadNameScore(norm: String, name: String?): Int {
        if (name.isNullOrBlank()) return 0
        val n = normalize(name)
        if (n.length >= 2 && norm.contains(n)) return 10 + min(n.length, 10)
        val c = core(n)
        if (c.length >= 2 && norm.contains(c)) return 6 + min(c.length, 8)
        return 0
    }

    /** 路線番号（E1・E1A・C2 など）が、前後を英数字に挟まれずに出てくるか */
    fun containsRef(norm: String, ref: String): Boolean {
        if (ref.isEmpty()) return false
        var i = norm.indexOf(ref)
        while (i >= 0) {
            val before = if (i > 0) norm[i - 1] else ' '
            val after = if (i + ref.length < norm.length) norm[i + ref.length] else ' '
            if (!before.isLetterOrDigitAscii() && !after.isLetterOrDigitAscii()) return true
            i = norm.indexOf(ref, i + 1)
        }
        return false
    }

    private fun Char.isLetterOrDigitAscii(): Boolean = this in 'A'..'Z' || this in '0'..'9'

    /** 施設の名前が案内の文に出てくるか（「豊田JCT」のように種別まで合えば高い。長い名前ほど高い） */
    fun facilityScore(norm: String, f: Facility): Int {
        val b = normalize(f.baseName)
        if (b.length < 2) return 0
        val words = when (f.type) {
            FacilityType.JCT -> JCT_WORDS
            FacilityType.IC, FacilityType.SIC -> listOf("IC", "インター", "スマートIC", "SIC", "出口", "ランプ")
            FacilityType.SA -> listOf("SA", "サービスエリア")
            FacilityType.PA -> listOf("PA", "パーキングエリア")
            else -> emptyList()
        }
        if (words.any { norm.contains(b + it) }) return 20 + min(b.length, 12)
        if (norm.contains(b)) return 8 + min(b.length, 12)
        return 0
    }

    /** 乗り換えの案内らしい（JCT の言葉、または高速道路に入る言い回し） */
    fun transferWords(norm: String): Boolean =
        JCT_WORDS.any { norm.contains(it) } || (ENTER_WORDS.any { norm.contains(it) } && EXPRESS_WORDS.any { norm.contains(it) })

    // ================================================================== 計画

    /** 案内をどこで・何として扱うか */
    class Plan(
        /** 分岐でナビの路線を選ぶ（乗り換えの案内） */
        val chooser: NavForkChooser?,
        /** 名前が合った施設（無ければ null） */
        val named: UpcomingFacility?,
        /** 案内地点までの距離（前方経路の始まりから。分からなければ null） */
        val anchor: Double?
    )

    /**
     * いつもの前方経路 base と案内から、どう扱うかを決める。
     * @param distNow 案内地点までのいまの距離（分からなければ null）
     */
    fun plan(base: RouteAhead, hint: NavHint, distNow: Double?): Plan {
        val norm = hint.norm
        // 1. 案内に名前が出てくる施設（案内地点までの距離が分かれば、その付近のものだけ）
        var best: UpcomingFacility? = null
        var bestScore = 0
        for (u in base.facilities) {
            val t = u.facility.type
            if (t != FacilityType.JCT && t != FacilityType.IC && t != FacilityType.SIC) continue
            if (u.distance <= 0.0) continue
            var sc = facilityScore(norm, u.facility)
            if (sc <= 0) continue
            if (distNow != null) {
                val tol = max(4000.0, distNow * 0.3)
                if (abs(u.distance - distNow) > tol) continue
                // 近いほど少し高く
                sc += (5 - (abs(u.distance - distNow) / 1000.0).toInt()).coerceIn(0, 5)
            }
            if (sc > bestScore) {
                best = u
                bestScore = sc
            }
        }
        val named = best
        // 2. 乗り換えの扱い: 名前が合ったのが JCT、または名前は無いが乗り換えの言い回しがある
        val transfer = when {
            named != null -> named.facility.type == FacilityType.JCT || (transferWords(norm) && !hint.exitWords)
            else -> transferWords(norm) || !hint.exitWords
        }
        val anchor = named?.distance ?: distNow
        val chooser = if (transfer && anchor != null) {
            // JCT の名前で位置が分かったときは、その少し手前から、ランプが分かれる先まで
            val (from, to) = if (named != null) (anchor - 1500.0) to (anchor + 4000.0)
            else (anchor - max(2000.0, anchor * 0.15)) to (anchor + max(2500.0, anchor * 0.15))
            NavForkChooser(hint, from, to)
        } else null
        return Plan(chooser, named, anchor)
    }

    /**
     * 作り直した前方経路と案内から、降りる IC・乗り換える JCT を決める。
     */
    fun match(route: RouteAhead, hint: NavHint, plan: Plan, distNow: Double?): NavMatch {
        val named = plan.named?.let { n -> route.facilities.firstOrNull { it.facility.id == n.facility.id } }
        val chooser = plan.chooser
        // 降りる IC: 名前が合った IC（乗り換えの案内でないとき）。名前が無ければ、出口の言葉があり案内地点の近く（1.2km 以内）の IC
        var exit: UpcomingFacility? = null
        if (named != null && named.facility.type.isExit && !named.facility.noExit && (chooser == null || !chooser.changed)) {
            if (hint.exitWords || !transferWords(hint.norm)) exit = named
        }
        if (exit == null && named == null && hint.exitWords && distNow != null && (chooser == null || !chooser.changed)) {
            exit = route.facilities.filter { it.facility.type.isExit && !it.facility.noExit && abs(it.distance - distNow) < 1200.0 }
                .minByOrNull { abs(it.distance - distNow) }
        }
        // 乗り換える JCT: 名前が合った JCT、または分岐を選んだ地点の近くの JCT
        var transfer: UpcomingFacility? = null
        if (named != null && named.facility.type == FacilityType.JCT) transfer = named
        val pickAt = chooser?.decidedAt
        if (transfer == null && pickAt != null) {
            transfer = route.facilities.filter { it.facility.type == FacilityType.JCT && abs(it.distance - pickAt) < 2500.0 }
                .minByOrNull { abs(it.distance - pickAt) }
        }
        val transferText = chooser?.decidedText ?: if (transfer != null) route.roadChanges.firstOrNull { abs(it.distance - transfer.distance) < 4000.0 }?.let { it.toName ?: it.toRef } else null
        val matched = exit != null || transfer != null || chooser?.decidedText != null
        return NavMatch(
            exitId = exit?.facility?.id, exitDistance = exit?.distance, exitName = exit?.facility?.displayName,
            transferId = transfer?.facility?.id, transferText = transferText, matched = matched
        )
    }
}

/**
 * 分岐（[from], [to] の範囲）で、分岐の先が案内の路線・方面に合う方へ進む。
 * 分岐の先は、ランプ（連絡路）を 6km までたどって行き先の案内（destination）を集め、その先の本線の名前・路線番号を見る。
 * 合う先が、いつもの選び方の先よりはっきり良い（4 点以上）ときだけ変える。
 */
class NavForkChooser(private val hint: NavHint, private val from: Double, private val to: Double) : ForkChooser {

    /** いつもと違う先を選んだ */
    var changed = false
        private set

    /** 案内に合う分岐を見つけた地点（前方経路の始まりからの道のり） */
    var decidedAt: Double? = null
        private set

    /** 乗り換え先の表示（例「東名高速道路 東京方面」） */
    var decidedText: String? = null
        private set

    override fun choose(graph: RoadGraph, dist: Double, cur: Edge, successors: List<Pair<Int, Double>>, default: Int): Int {
        if (successors.size < 2 || dist < from || dist > to) return default
        var best = default
        var bestScore = Int.MIN_VALUE
        var bestText: String? = null
        var defaultScore = 0
        for ((id, _) in successors) {
            val (sc, txt) = branchScore(graph, id)
            if (id == default) defaultScore = sc
            if (sc > bestScore) {
                bestScore = sc
                best = id
                bestText = txt
            }
        }
        if (best != default && bestScore >= 8 && bestScore >= defaultScore + 4) {
            changed = true
            if (decidedAt == null) decidedAt = dist
            decidedText = bestText ?: decidedText
            return best
        }
        // いつもの先が案内に合っている（乗り換え先が今の路線の続き）なら、その表示だけ覚える
        if (best == default && bestScore >= 8 && decidedText == null) {
            decidedAt = dist
            decidedText = bestText
        }
        return default
    }

    /** 分岐の先の点数と、表示用の「路線名 方面」 */
    private fun branchScore(graph: RoadGraph, start: Int): Pair<Int, String?> {
        val norm = hint.norm
        var e = graph.edges[start]
        var walked = 0.0
        var score = 0
        val dests = ArrayList<String>()
        var guard = 0
        while (guard++ < 60) {
            e.attr.destination?.let { d ->
                for (tok in d.split(';', '・', ',', '、')) {
                    val t = NavGuide.normalize(tok)
                    if (t.length >= 2 && t !in dests && norm.contains(t)) {
                        dests.add(t)
                        score += 4
                    }
                }
            }
            if (!e.attr.isLink) break
            walked += e.length
            if (walked > 6000.0) break
            val nx = RoadContinuity.rankedSuccessors(graph, e).firstOrNull()?.first ?: break
            e = graph.edges[nx]
        }
        var road: String? = null
        if (!e.attr.isLink) {
            val ns = NavGuide.roadNameScore(norm, e.attr.name)
            score += ns
            var refHit = false
            for (ref in e.attr.normRefs) {
                if (NavGuide.containsRef(norm, ref)) {
                    score += 8
                    refHit = true
                    break
                }
            }
            if (ns > 0 || refHit) road = e.attr.displayName
        }
        val text = listOfNotNull(road, dests.firstOrNull()?.let { "${it}方面" }).joinToString(" ").ifBlank { null }
        return score to text
    }
}

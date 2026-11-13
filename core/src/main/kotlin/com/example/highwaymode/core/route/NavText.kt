package com.example.highwaymode.core.route

/**
 * カーナビのアプリの通知の文字から、案内地点までの距離と案内の文を取り出す。
 * 距離は「52 km」「1.2 km」「800 m」の形（題名の部品を優先）。到着時刻・残りの時間の部品は案内の文に入れない。
 *
 * v2.16 から、**案内（次に曲がる場所・分岐・出口など）かどうか**も決める（[Parsed.guidance]）。
 * カーナビのアプリは、案内を始めていないときも「実行中」の通知を出すことがある
 * （Android Auto につなぐと出る Google マップの「運転モード」など）。そういう通知は案内として扱わない。
 * 案内とするのは、案内地点までの距離が読めたとき（題名・距離だけの部品・距離で始まる部品）か、
 * 曲がる方向・出口・分岐・○○IC などの言葉があって、案内を始めていないときの言葉（運転モードなど）が無いとき。
 */
object NavText {

    /**
     * @param distanceM 案内地点までの距離 [m]（分からなければ null）
     * @param instruction 案内の文
     * @param guidance 案内（距離か、曲がる方向・出口・分岐などの言葉がある）なら true
     */
    class Parsed(val distanceM: Double?, val instruction: String, val guidance: Boolean)

    private val DIST = Regex("(\\d+(?:[.,]\\d+)?)\\s*(km|ｋｍ|キロメートル|キロ|m|ｍ|メートル)(?![A-Za-z])", RegexOption.IGNORE_CASE)
    private val ONLY_DIST = Regex("^(\\d+(?:[.,]\\d+)?)\\s*(km|ｋｍ|キロメートル|キロ|m|ｍ|メートル)\\s*(先)?$", RegexOption.IGNORE_CASE)
    /** 距離で始まる部品（「300m先 左方向」「1.2 km 先、右折」） */
    private val START_DIST = Regex("^\\s*(約)?\\s*\\d+(?:[.,]\\d+)?\\s*(km|ｋｍ|キロメートル|キロ|m|ｍ|メートル)(?![A-Za-z])", RegexOption.IGNORE_CASE)
    private val TIME = Regex("\\d{1,2}[:：]\\d{2}")

    /** 案内ではない通知の言葉（空白を除き、小文字にした文で探す） */
    private val NOT_GUIDANCE = listOf(
        "運転モード", "ドライブモード", "drivingmode",
        "ナビを開始", "ナビを始め", "案内を開始", "経路を検索", "ルートを検索", "目的地を設定", "目的地を入力",
        "位置情報を共有", "現在地を共有", "ダウンロード", "オフラインマップ", "更新しています"
    )

    /** 案内の言葉（空白を除き、小文字にした文で探す） */
    private val MANEUVER = listOf(
        "右折", "左折", "右方向", "左方向", "斜め右", "斜め左", "やや右", "やや左", "右へ", "左へ", "右に", "左に", "右側", "左側",
        "直進", "道なり", "uターン", "転回", "出口", "入口", "方面", "分岐", "合流", "料金所", "ランプ", "交差点",
        "進む", "進みます", "曲が", "降り", "乗り換え", "乗る", "入る", "目的地", "到着", "経由地", "ロータリー", "環状交差点",
        "turnleft", "turnright", "keepleft", "keepright", "slightleft", "slightright", "u-turn", "uturn",
        "exit", "merge", "continue", "headnorth", "headsouth", "headeast", "headwest", "roundabout", "destination", "arrive", "toward"
    )

    /** 「〇〇IC」「〇〇JCT」など（英字の単語の一部は除く） */
    private val ROAD_POINT = Regex("(?<![A-Za-z])(IC|JCT|SIC|SA|PA)(?![A-Za-z])")

    fun distanceOf(s: String): Double? {
        val m = DIST.find(s) ?: return null
        val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val unit = m.groupValues[2].lowercase()
        return if (unit.startsWith("k") || unit.startsWith("ｋ") || unit.startsWith("キ")) v * 1000.0 else v
    }

    /** 到着時刻・残りの時間の部品（「13:05 到着」「1 時間 23 分 · 98 km · 13:05」） */
    private fun isEta(s: String): Boolean =
        (TIME.containsMatchIn(s) && (s.contains("着") || s.contains("到着") || s.contains("·") || s.contains("・") || s.contains("分"))) ||
            s.contains("到着予定")

    /** 全角の英数字を半角に、空白を除いて小文字に（言葉を探すため） */
    private fun squash(s: String, lower: Boolean): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            val c = when (ch) {
                in '０'..'９' -> '0' + (ch - '０')
                in 'Ａ'..'Ｚ' -> 'A' + (ch - 'Ａ')
                in 'ａ'..'ｚ' -> 'a' + (ch - 'ａ')
                else -> ch
            }
            if (c == ' ' || c == '　' || c == ' ') continue
            sb.append(if (lower) c.lowercaseChar() else c)
        }
        return sb.toString()
    }

    /** 案内ではない通知の言葉が入っているか */
    fun isNotGuidance(text: String): Boolean {
        val s = squash(text, lower = true)
        return NOT_GUIDANCE.any { s.contains(it) }
    }

    /** 曲がる方向・出口・分岐などの言葉が入っているか */
    fun hasManeuver(text: String): Boolean {
        val s = squash(text, lower = true)
        if (MANEUVER.any { s.contains(it) }) return true
        return ROAD_POINT.containsMatchIn(squash(text, lower = false))
    }

    /**
     * @param pieces 通知の文字（部品の名前, 文字）
     * @return 文字が無ければ null
     */
    fun parse(pieces: List<Pair<String, String>>): Parsed? {
        if (pieces.isEmpty()) return null
        // 距離: 題名の部品（nav_title・title）→ 距離だけの部品 → 距離で始まる部品
        var dist: Double? = null
        for ((name, t) in pieces) {
            if ((name == "nav_title" || name == "title") && !isEta(t)) {
                distanceOf(t)?.let { dist = it }
                if (dist != null) break
            }
        }
        if (dist == null) {
            for ((_, t) in pieces) {
                if (ONLY_DIST.matches(t.trim())) {
                    dist = distanceOf(t)
                    break
                }
            }
        }
        // 距離で始まる部品（「300m先 左方向」。Google マップ以外のアプリの書き方）
        if (dist == null) {
            for ((_, t) in pieces) {
                if (!isEta(t) && START_DIST.containsMatchIn(t)) {
                    dist = distanceOf(t)
                    break
                }
            }
        }
        val words = ArrayList<String>()
        for ((_, t) in pieces) {
            val tt = t.trim()
            if (ONLY_DIST.matches(tt)) continue
            if (isEta(tt)) continue
            if (tt.length <= 1) continue
            words.add(tt)
        }
        val instruction = words.joinToString(" ").take(300)
        if (instruction.isBlank()) return null
        // 案内地点までの距離が読めれば案内。読めなければ、曲がる方向・出口などの言葉があり、
        // 案内を始めていないときの言葉（運転モードなど）が無いときだけ案内とする。
        // （運転モードの通知の本文に次の案内が出る場合もあるので、距離が読めたときは「運転モード」の言葉で打ち消さない）
        val guidance = dist != null || (hasManeuver(instruction) && !isNotGuidance(instruction))
        return Parsed(dist, instruction, guidance)
    }
}

package com.example.highwaymode.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import com.example.highwaymode.BuildConfig
import com.example.highwaymode.R
import com.example.highwaymode.TrackingService
import com.example.highwaymode.alerts.AlertManager
import com.example.highwaymode.core.dual.DualLayout
import com.example.highwaymode.core.engine.EngineStatus
import com.example.highwaymode.data.AppSettings
import com.example.highwaymode.data.DataSyncState
import com.example.highwaymode.data.Prefs
import com.example.highwaymode.data.UserCameraStore
import com.example.highwaymode.dual.DualMode
import com.example.highwaymode.engine.EngineHost
import com.example.highwaymode.engine.RunDiagnostics
import com.example.highwaymode.nav.NavNotifications
import android.view.View
import com.example.spectrum.SessionScanner
import com.example.spectrum.SpectrumEngine
import com.example.spectrum.SpectrumStyle
import kotlin.concurrent.thread

/**
 * スマホの画面の設定（ひとつの画面にまとめたもの）。下部のバーの［⋮］・ハイウェイ側の［設定］から開く。
 *
 * 上から: よく使う操作（再探索・一般道モード・終了）／画面の切り替え／ハイウェイの表示／スマホの画面／
 * 音楽（スペアナ）／音声案内／オービス／地図データ／Android Auto・電池／権限／その他。
 * 変えた設定はすぐ反映する（保存ボタンは無い）。スマホと車の画面の両方に効く。
 *
 * 開発者向けの設定（診断・デモ走行・渋滞情報の取得元と API キー・地図データの細かい設定など）はここには出さず、
 * 音楽の画面の何もない場所を 20 回続けてタップ → パスワードで開く（[DevSettingsActivity]）。
 */
class SettingsActivity : Activity(), SettingsDialogs.CameraFileActions {

    private lateinit var ui: SettingsUi
    private lateinit var prefs: Prefs
    /** 画面に戻るたびに、いまの状態（権限・登録数・地図データなど）を表示し直す行 */
    private val refreshers = ArrayList<() -> Unit>()
    private var mapRow: SettingsUi.Row? = null
    /** 地図データの版（作成日時）。更新が終わったら読み直す */
    private var versionRow: SettingsUi.Row? = null
    private var generalTile: SettingsUi.TileView? = null
    private var spectrumPreview: SpectrumPreviewView? = null
    /** スペアナの表示の種類・色の行と見本を表示し直す */
    private var spectrumRefresh: (() -> Unit)? = null
    /** 音声案内のテスト再生用（開いている間だけ） */
    private var voiceTest: AlertManager? = null
    /** 地図データの更新の進み具合（終わったら、端末内のデータの版も読み直す） */
    private val syncListener = DataSyncState.Listener {
        mapRow?.summary(SettingsDialogs.syncProgress())
        if (!DataSyncState.running) refreshMap()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        AppSettings.apply(this)
        ScreenOptions.applyOrientation(this, prefs)
        ui = SettingsUi(this)
        build()
        DataSyncState.addListener(syncListener)
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    override fun onDestroy() {
        DataSyncState.removeListener(syncListener)
        voiceTest?.shutdown()
        voiceTest = null
        super.onDestroy()
    }

    private fun refreshAll() {
        for (r in refreshers) r()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ================================================================== 画面の中身

    private fun build() {
        val c = ui.page("設定", null) { finish() }

        // ---- よく使う操作
        val ops = ui.section(c, "操作")
        val tiles = ui.tiles(
            ops,
            listOf(
                SettingsUi.Tile("道路を再探索", "上下線を取り違えたとき") { reroute() },
                SettingsUi.Tile("一般道モード", "オフ") { toggleGeneralRoad() },
                SettingsUi.Tile("終了", "常駐を止めて閉じる") { quit() }
            )
        )
        generalTile = tiles[1]
        refreshers += { showGeneralRoad(generalRoadOn()) }
        ui.note(c, "一般道モード: 高架の高速道路の真下を走るときなどに、高速道路の判定を止めて音楽の画面のままにします。")

        // ---- 画面の切り替え
        val sw = ui.section(
            c, "画面の切り替え",
            "高速道路・有料道路・都市高速・主要バイパスに入って 5 秒で切り替わります。IC のランプ・料金所・JCT・SA/PA では戻りません。下のバーでいつでも切り替えられます。"
        )
        val layouts = arrayOf("2画面（左に音楽・右にハイウェイ）", "ハイウェイだけ", "自動で切り替えない（下のバーで切り替える）")
        lateinit var exitRow: SettingsUi.Row
        ui.choiceRow(sw, "高速道路に入ったときの画面", layouts, layoutIndex()) { which ->
            when (which) {
                0 -> {
                    prefs.autoSwitch = true
                    prefs.highwayLayout = DualLayout.SPLIT.name
                }
                1 -> {
                    prefs.autoSwitch = true
                    prefs.highwayLayout = DualLayout.HIGHWAY.name
                }
                else -> prefs.autoSwitch = false
            }
            DualMode.settingsChanged()
            AppSettings.changed(this)
            exitRow.enabled(which != 2)
        }
        val exitLabels = Prefs.EXIT_DELAYS.mapIndexed { i, s ->
            (if (s < 60) "$s 秒" else "${s / 60} 分") + if (i == 0) "（標準）" else ""
        }.toTypedArray()
        exitRow = ui.choiceRow(
            sw, "高速道路を出てから音楽に戻るまで", exitLabels,
            Prefs.EXIT_DELAYS.indexOf(prefs.exitDelaySec).coerceAtLeast(0),
            "高架下・側道などで何度も切り替わるときは長めに"
        ) { which ->
            prefs.exitDelaySec = Prefs.EXIT_DELAYS[which]
            DualMode.settingsChanged()
        }
        exitRow.enabled(prefs.autoSwitch)

        // ---- ハイウェイの表示
        val hw = ui.section(c, "ハイウェイの表示")
        ui.choiceRow(hw, "表示の種類", arrayOf("大きく（3〜4 件）", "一覧（多く並べる）", "SA・PA だけ"), prefs.displayMode) {
            prefs.displayMode = it
            AppSettings.changed(this)
        }
        ui.choiceRow(hw, "到着予定の表示", arrayOf("到着時刻（例: 17:50）", "残り時間（例: あと12分）"), prefs.etaStyle) {
            prefs.etaStyle = it
            AppSettings.changed(this)
        }
        ui.switchRow(hw, "施設の一覧にオービスも並べる", "オフでも、2km 手前から上部に表示します", prefs.camerasInList) {
            prefs.camerasInList = it
            AppSettings.changed(this)
        }

        // ---- Google マップのナビ連携
        val nv = ui.section(
            c, "Google マップのナビ連携",
            "Google マップでルートを決めてナビを始めると、ナビの通知から次の案内を読み取ります。" +
                "音楽の画面に次の案内（矢印・距離・案内の文）を出し（一般道でも）、高速道路ではハイウェイの一覧をナビの経路に合わせます" +
                "（JCT で乗り換え先の路線へ、降りる IC より先は出しません）。" +
                "通知へのアクセス（曲名と同じ許可）を使います。読み取った内容は端末の中だけで使い、保存・送信しません。"
        )
        ui.switchRow(nv, "音楽の画面にナビの案内を出す", "スペアナの上に、次に曲がる方向・距離・案内の文を出します（一般道でも）。押すと Google マップを開きます", prefs.navBanner) {
            prefs.navBanner = it
            AppSettings.changed(this)
            NavNotifications.settingsChanged(this)
        }
        ui.switchRow(nv, "ハイウェイの一覧をナビに合わせる", "高速道路で、JCT の乗り換え先の路線へ進み、降りる IC までを表示します。ナビをしていないときは、いつもどおり同じ路線を先まで表示します", prefs.navSync) {
            prefs.navSync = it
            NavNotifications.settingsChanged(this)
        }
        val navStatus = ui.infoRow(nv, "いまの状態", null)
        refreshers += {
            val st = EngineHost.latest?.nav
            val text = when {
                !prefs.navSync && !prefs.navBanner -> "オフ"
                !isListenerEnabled() -> "通知へのアクセスを許可すると使えます（下の「権限」）"
                st != null && st.exitName != null -> "${st.exitName} で降ります（${st.source}）"
                st != null && st.transferText != null -> "乗り換え: ${st.transferText}（${st.source}）"
                NavNotifications.active -> "ナビの案内を受け取っています: ${NavNotifications.lastText?.take(40) ?: ""}"
                else -> "Google マップでナビを始めると使えます"
            }
            navStatus.value(text, SettingsUi.C_SUB)
        }

        // ---- スマホの画面
        val ph = ui.section(c, "スマホの画面", "車の画面（Android Auto）には関係しません。")
        ui.choiceRow(
            ph, "画面の向き", arrayOf("自動（端末の設定どおり）", "縦に固定", "横に固定"), prefs.orientation,
            "スマホを車に固定して使うとき、向きが変わらないように"
        ) {
            prefs.orientation = it
            ScreenOptions.applyOrientation(this, prefs)
        }
        ui.choiceRow(ph, "画面を消さない", arrayOf("いつも（標準）", "高速道路を走行中だけ", "端末の設定どおりに消す"), prefs.keepScreenOn) {
            prefs.keepScreenOn = it
        }

        // ---- 音楽（スペアナ）
        val mu = ui.section(
            c, "音楽（スペアナ）",
            "ジャケットをタップすると、再生中の音楽アプリを開きます。音楽の画面でスペアナを長押しすると、表示の種類を順に切り替えられます。"
        )
        val preview = SpectrumPreviewView(this)
        spectrumPreview = preview
        ui.viewRow(mu, preview, 108f) { SpectrumPickers.chooseVisual(this) { spectrumChanged() } }
        val visualRow = ui.actionRow(mu, "表示の種類", "LED・バー・ライン・レトロ・針のメーターなど（見本を見て選べます）") {
            SpectrumPickers.chooseVisual(this) { spectrumChanged() }
        }
        val swatch = View(this).apply {
            minimumWidth = ui.dp(30)
            minimumHeight = ui.dp(30)
        }
        val colorRow = ui.actionRow(mu, "色", "ジャケットの色・12 色と虹色・カラーコード", right = swatch) {
            SpectrumPickers.chooseColor(this) { spectrumChanged() }
        }
        spectrumRefresh = {
            visualRow.value(SpectrumStyle.VISUAL_LABELS[SpectrumStyle.visual])
            colorRow.value(SpectrumStyle.themeLabel())
            swatch.background = SpectrumPickers.swatchDrawable(this, SpectrumStyle.theme, false)
            preview.refresh()
        }
        spectrumRefresh?.invoke()
        ui.choiceRow(mu, "高さ", SpectrumStyle.GAIN_LABELS, SpectrumStyle.gainIndex, "音が小さくバーが低いときは「高め」に") {
            SpectrumStyle.setGain(this, it)
            AppSettings.changed(this)
        }
        val shizuku = ui.actionRow(mu, "音声の読み取り（Shizuku）", "スペアナが動かない音楽アプリがあるとき（手順を表示します）") {
            SettingsDialogs.showShizukuGuide(this) { refreshAll() }
        }
        refreshers += {
            val ok = SessionScanner.hasPermission(this)
            shizuku.value(if (ok) "許可済み" else "未許可（通常は不要）", if (ok) SettingsUi.C_OK else SettingsUi.C_SUB)
        }

        // ---- 渋滞情報（リアルタイム）
        buildTraffic(c)

        // ---- 音声案内
        val vo = ui.section(c, "音声案内", "案内はナビの音声として流れ、その間は音楽の音が小さくなります。")
        ui.switchRow(vo, "オービスの接近", "1km・500m 手前で知らせます（合図の音つき）", prefs.cameraVoice) { prefs.cameraVoice = it }
        val jam = ui.switchRow(vo, "前方の渋滞", "10km 以内に新しく渋滞が現れたとき（長さ・遅れ）", prefs.voiceJam) { prefs.voiceJam = it }
        // 渋滞の案内は、リアルタイムの渋滞情報を使う設定のときだけ
        refreshers += { jam.shown(prefs.trafficProvider != "none") }
        ui.switchRow(vo, "ETC カードの未挿入", "Android Auto で、車から ETC カードの状態が分かるとき", prefs.voiceEtc) { prefs.voiceEtc = it }
        ui.switchRow(vo, "給油・充電の目安", "Android Auto で、燃料・電池の残りが分かるとき", prefs.voiceFuel) { prefs.voiceFuel = it }
        ui.choiceRow(vo, "音量", arrayOf("標準", "やや小さく", "小さく"), prefs.voiceVolume, "音楽の音量に対する大きさ") { prefs.voiceVolume = it }
        ui.choiceRow(vo, "話す速さ", arrayOf("ゆっくり", "標準", "速め"), prefs.voiceRate) { prefs.voiceRate = it }
        ui.actionRow(vo, "テスト再生", "いまの音量・速さで案内を流します") { testVoice() }

        // ---- オービス
        val cam = ui.section(
            c, "オービス",
            "地図データの速度取締機（OpenStreetMap）に加えて、自分で登録・取り込みした地点を表示します。位置は網羅されていません。制限速度を守って運転してください。"
        )
        val camRow = ui.actionRow(cam, "登録した地点", "現在地の登録・CSV の取り込みと書き出し・削除") {
            SettingsDialogs.showCameras(this, this) { refreshAll() }
        }
        refreshers += { camRow.value("${UserCameraStore(this).count()} 件") }

        // ---- 地図データ
        val map = ui.section(c, "地図データ", "地図 © OpenStreetMap contributors（ODbL）。施設・設備・ETC 専用の情報は OpenStreetMap の登録内容によります。地図データは毎週新しい版が作られます。")
        versionRow = ui.infoRow(map, "地図データの版（作成日時）", "読み込み中…")
        mapRow = ui.actionRow(map, "最新の地図データに更新", null) { SettingsDialogs.startDownload(this) }
        refreshers += { refreshMap() }
        ui.switchRow(map, "起動時に新しい地図データを確認する", "1 日 1 回。新しい版があればお知らせします（自動では更新しません）", prefs.autoCheckUpdate) {
            prefs.autoCheckUpdate = it
        }

        // ---- Android Auto・電池
        val aa = ui.section(c, "Android Auto・電池")
        val run = ui.actionRow(aa, "スマホを開かずに Android Auto で使う", "電池の設定（最適化しない）と、いまの動作状態を確認します") {
            SettingsDialogs.showRunStatus(this)
        }
        refreshers += {
            val bad = RunDiagnostics.collect(this).filter { !it.ok }
            if (bad.isEmpty()) run.value("問題ありません", SettingsUi.C_OK)
            else run.value("確認が必要: " + bad.joinToString("・") { it.title }, SettingsUi.C_WARN)
        }
        ui.switchRow(
            aa, "電池の節約",
            "高速道路から離れているときや停車中は、位置情報の取得を控えます（高速道路の近くでは毎秒）。オフにすると、いつも毎秒取得します。",
            prefs.batterySaver
        ) { prefs.batterySaver = it }

        // ---- 権限
        val pm = ui.section(c, "権限", "押すと、許可の確認か、端末の設定画面を開きます。")
        val loc = ui.actionRow(pm, "位置情報（正確な位置）", "高速道路の判定・距離・到着予定に使います") {
            askPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
        val mic = ui.actionRow(pm, "マイク", "スペアナで、再生中の音を解析します（録音はしません）") {
            askPermissions(arrayOf(Manifest.permission.RECORD_AUDIO))
        }
        val listener = ui.actionRow(pm, "通知へのアクセス", "曲名・ジャケット・再生の操作に使います") { openNotificationAccess() }
        val notify = if (Build.VERSION.SDK_INT >= 33) {
            ui.actionRow(pm, "通知の表示", "走行中の次の施設などを通知に出します") {
                askPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
        } else null
        refreshers += {
            mark(loc, has(Manifest.permission.ACCESS_FINE_LOCATION), if (has(Manifest.permission.ACCESS_COARSE_LOCATION)) "「おおよその位置」だけ許可（正確な位置が必要）" else null)
            mark(mic, has(Manifest.permission.RECORD_AUDIO))
            mark(listener, isListenerEnabled())
            if (notify != null && Build.VERSION.SDK_INT >= 33) mark(notify, has(Manifest.permission.POST_NOTIFICATIONS))
        }

        // ---- その他
        val ot = ui.section(c, "その他")
        ui.actionRow(ot, "到着予定の学習をリセット", "運転のくせ（速さ）の学習を最初からやり直します（別の人が運転するときなど）") {
            ui.confirm("到着予定の学習をリセット", "これまでに学習した運転のくせを消して、最初から学習し直します。", "リセット") {
                EngineHost.post { it.driver.reset() }
                prefs.driverProfile = null
                toast("リセットしました")
            }
        }
        ui.actionRow(ot, "このアプリについて", null) { showAbout() }.value("版 ${BuildConfig.VERSION_NAME}", SettingsUi.C_SUB)
    }

    /** スペアナの表示の種類・色を変えた（スマホと車の画面にはすぐ反映される） */
    private fun spectrumChanged() {
        AppSettings.changed(this)
        spectrumRefresh?.invoke()
    }

    /**
     * 渋滞情報（リアルタイム）: 取得元（おすすめは TomTom）・API キー（取り方の手順つき）・キーの確認・取得間隔・通信量・いまの状態。
     * API キーは利用者が自分で作って入れる（v2.5 から開発者向け設定から移した）。
     */
    private fun buildTraffic(c: android.widget.LinearLayout) {
        val tr = ui.section(
            c, "渋滞情報（リアルタイム）",
            "高速道路を走行中だけ取得し、到着予定時刻・左の帯の色（赤＝渋滞）・施設の行の「渋滞」「事故」「通行止」に反映します。" +
                "使うには取得元の API キー（無料で作れます。おすすめは TomTom）が必要です。キーは端末の中にだけ保存します。"
        )
        val keys = SettingsDialogs.PROVIDERS.map { it.first }
        val labels = SettingsDialogs.PROVIDERS.map { it.second }.toTypedArray()
        lateinit var keyRow: SettingsUi.Row
        lateinit var guideRow: SettingsUi.Row
        lateinit var testRow: SettingsUi.Row
        lateinit var intervalRow: SettingsUi.Row
        lateinit var shortRow: SettingsUi.Row
        val update = {
            val p = prefs.trafficProvider
            val on = p != "none"
            keyRow.value(SettingsDialogs.maskedKey(prefs.trafficKey(p)), if (prefs.trafficKey(p).isBlank()) SettingsUi.C_WARN else SettingsUi.C_OK)
            guideRow.summary(if (on) "${SettingsDialogs.providerName(p)} の開発者サイトでキーを作る手順" else "おすすめの TomTom の手順を表示します")
            for (r in listOf(keyRow, testRow, intervalRow, shortRow)) r.enabled(on)
        }
        ui.choiceRow(tr, "取得元", labels, keys.indexOf(prefs.trafficProvider).coerceAtLeast(0)) { which ->
            prefs.trafficProvider = keys[which]
            EngineHost.refreshTraffic()
            update()
            if (keys[which] != "none" && prefs.trafficKey(keys[which]).isBlank()) {
                // キーがまだ無いなら、続けて入れてもらう
                SettingsDialogs.editKey(this, keys[which]) { update() }
            }
        }
        keyRow = ui.actionRow(tr, "API キー", "取得元の開発者サイトで作ったキーを貼り付けます") {
            SettingsDialogs.editKey(this, prefs.trafficProvider) { update() }
        }
        guideRow = ui.actionRow(tr, "API キーの取り方", null) { SettingsDialogs.showKeyGuide(this, prefs.trafficProvider) }
        testRow = ui.actionRow(tr, "キーを確かめる", "1 回だけ問い合わせて、キーが使えるか確かめます") {
            testRow.value("確かめています…", SettingsUi.C_SUB)
            SettingsDialogs.testKey(this, prefs.trafficProvider, prefs.trafficKey(prefs.trafficProvider)) { ok, msg ->
                testRow.value(msg, if (ok) SettingsUi.C_OK else SettingsUi.C_DANGER)
            }
        }
        val ivals = SettingsDialogs.INTERVALS
        intervalRow = ui.choiceRow(tr, "取得間隔", ivals.map { it.second }.toTypedArray(), ivals.indexOfFirst { it.first == prefs.trafficIntervalMin }.coerceAtLeast(0)) {
            prefs.trafficIntervalMin = ivals[it].first
            EngineHost.refreshTraffic()
        }
        shortRow = ui.switchRow(tr, "通信量を抑える", "問い合わせる範囲を前方 60km までにします（通常は 100km）", prefs.trafficShortRange) {
            prefs.trafficShortRange = it
        }
        val status = ui.infoRow(tr, "いまの状態", null)
        refreshers += {
            update()
            val t = EngineHost.latest?.traffic
            val p = prefs.trafficProvider
            val message = t?.message
            val fetched = t?.fetchedWallMs
            val text = when {
                p == "none" -> "使っていません（自車の走行から渋滞を推定）"
                prefs.trafficKey(p).isBlank() -> "API キーを入れると使えます"
                message != null -> message
                fetched != null -> "${Formats.clock(fetched)} に取得（${t.provider ?: SettingsDialogs.providerName(p)}）"
                else -> "高速道路を走行中に取得します"
            }
            status.value(text, SettingsUi.C_SUB)
        }
    }

    private fun layoutIndex(): Int = when {
        !prefs.autoSwitch -> 2
        prefs.highwayLayout == DualLayout.HIGHWAY.name -> 1
        else -> 0
    }

    private fun refreshMap() {
        val row = mapRow ?: return
        if (prefs.manifestUrl.isBlank()) {
            row.enabled(false)
            row.summary("このアプリには地図データの配布元が設定されていません")
        } else {
            row.enabled(true)
            row.summary(SettingsDialogs.syncProgress())
        }
        SettingsDialogs.mapSummary(this) { row.value(it) }
        versionRow?.let { v ->
            SettingsDialogs.mapVersion(this) { text, note ->
                v.value(text, SettingsUi.C_TITLE)
                v.summary(note)
            }
        }
    }

    private fun mark(row: SettingsUi.Row, ok: Boolean, partial: String? = null) {
        when {
            ok -> row.value("許可済み", SettingsUi.C_OK)
            partial != null -> row.value(partial, SettingsUi.C_WARN)
            else -> row.value("未許可（押して許可）", SettingsUi.C_WARN)
        }
    }

    // ================================================================== よく使う操作

    private fun generalRoadOn(): Boolean = EngineHost.latest?.status == EngineStatus.GENERAL_ROAD_MODE

    private fun showGeneralRoad(on: Boolean) {
        val t = generalTile ?: return
        t.sub.text = if (on) "オン（判定を止めています）" else "オフ"
        t.sub.setTextColor(if (on) SettingsUi.C_WARN else SettingsUi.C_SUB)
    }

    private fun reroute() {
        if (!EngineHost.running) {
            toast("位置情報を許可すると使えます")
            return
        }
        EngineHost.post { it.forceRematch() }
        toast("現在地の道路を再探索します")
    }

    private fun toggleGeneralRoad() {
        if (!EngineHost.running) {
            toast("位置情報を許可すると使えます")
            return
        }
        val on = !generalRoadOn()
        EngineHost.post { it.generalRoadMode = on }
        showGeneralRoad(on)
        toast(if (on) "一般道モードにしました（高速道路の判定を止めます）" else "一般道モードを解除しました")
    }

    private fun quit() {
        ui.confirm("アプリを終了", "位置情報の常駐を止めて、アプリを閉じます。", "終了") {
            TrackingService.stop(this)
            finishAndRemoveTask()
        }
    }

    // ================================================================== 音声案内のテスト

    private fun testVoice() {
        val am = voiceTest ?: AlertManager(this).also {
            it.onUnavailable = { msg -> runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() } }
            voiceTest = it
        }
        am.preview()
    }

    // ================================================================== 権限

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun isListenerEnabled(): Boolean =
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true

    /** 許可していなければ確認を出す。許可済みなら端末の設定画面（取り消したいとき用） */
    private fun askPermissions(perms: Array<String>) {
        if (has(perms[0])) {
            openAppSettings()
        } else {
            requestPermissions(perms, REQ_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSIONS) return
        refreshAll()
        SpectrumEngine.retry(applicationContext)
        if (has(Manifest.permission.ACCESS_FINE_LOCATION)) TrackingService.start(this)
        // 確認を出しても許可されず、もう確認が出ない状態 → アプリの設定画面へ
        val blocked = permissions.indices.any { i ->
            grantResults.getOrNull(i) != PackageManager.PERMISSION_GRANTED && !shouldShowRequestPermissionRationale(permissions[i])
        }
        if (blocked && !has(permissions[0])) {
            AlertDialog.Builder(this, SettingsUi.DIALOG_THEME)
                .setTitle("権限の設定")
                .setMessage("確認の画面が出ない状態です。アプリの設定 →「権限」で許可してください。")
                .setPositiveButton("設定を開く") { _, _ -> openAppSettings() }
                .setNegativeButton("閉じる", null)
                .show()
        }
    }

    private fun openAppSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            toast("設定を開けませんでした")
        }
    }

    private fun openNotificationAccess() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (e: Exception) {
            toast("設定を開けませんでした")
        }
    }

    // ================================================================== オービスの CSV

    override fun importCameraCsv() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/octet-stream"))
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_IMPORT)
        } catch (e: Exception) {
            toast("ファイルを選ぶ画面を開けませんでした")
        }
    }

    override fun exportCameraCsv() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/csv")
            .putExtra(Intent.EXTRA_TITLE, "highway_cameras.csv")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_EXPORT)
        } catch (e: Exception) {
            toast("ファイルを選ぶ画面を開けませんでした")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        val store = UserCameraStore(this)
        thread(name = "camera-csv") {
            val msg = try {
                when (requestCode) {
                    REQ_IMPORT -> {
                        val (added, skipped) = store.import(uri)
                        EngineHost.reloadUserCameras()
                        "取り込みました: $added 件" + if (skipped > 0) "（読めなかった行 $skipped）" else ""
                    }
                    REQ_EXPORT -> {
                        store.export(uri)
                        "書き出しました: ${store.count()} 件"
                    }
                    else -> null
                }
            } catch (e: Exception) {
                "失敗しました: ${e.message}"
            }
            if (msg != null) {
                runOnUiThread {
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    refreshAll()
                }
            }
        }
    }

    // ================================================================== このアプリについて

    private fun showAbout() {
        val msg = """
            音楽（Spectrum）とハイウェイモードを 1 つにまとめたアプリです。

            ・高速道路の外: 再生中の曲（ジャケット・曲名・操作）とスペクトラムアナライザーを表示します。
            ・高速道路・有料道路・都市高速・主要バイパス（名豊道路・名阪国道など）に入ると、自動で 2 画面（左に音楽、右にハイウェイモード）に切り替わり、出ると音楽の画面に戻ります。下のバーでいつでも切り替えられます。
            ・ハイウェイモード: 前方の IC / JCT / SA / PA / スマート IC / 本線料金所までの距離と到着予定時刻、SA/PA の設備、ETC 専用、渋滞・事故・通行止め、オービスを表示します。
            ・Android Auto に対応しています（車速・ETC カード・燃料の情報も使います）。

            位置情報・再生中の曲の情報・音の解析結果は端末の中だけで使い、外へ送りません（録音もしません）。

            地図データ © OpenStreetMap contributors（ODbL）
            施設・設備・ETC 専用の情報は OpenStreetMap の登録内容に依存します。運転中は画面を注視せず、安全を最優先にしてください。
        """.trimIndent()
        AlertDialog.Builder(this, SettingsUi.DIALOG_THEME)
            .setTitle(getString(R.string.app_name) + "　版 " + BuildConfig.VERSION_NAME)
            .setMessage(msg)
            .setPositiveButton("閉じる", null)
            .show()
    }

    companion object {
        private const val REQ_PERMISSIONS = 30
        private const val REQ_IMPORT = 20
        private const val REQ_EXPORT = 21
    }
}

/** スマホの画面の設定（画面の向き・画面を消さない）を画面に当てはめる */
object ScreenOptions {

    fun applyOrientation(a: Activity, prefs: Prefs) {
        val want = when (prefs.orientation) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            2 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        if (a.requestedOrientation != want) a.requestedOrientation = want
    }

    /** 画面を消さない（いつも / 高速道路を走行中だけ / 端末の設定どおり） */
    fun applyKeepScreenOn(a: Activity, prefs: Prefs, onHighway: Boolean) {
        val keep = when (prefs.keepScreenOn) {
            0 -> true
            1 -> onHighway
            else -> false
        }
        if (keep) a.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else a.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

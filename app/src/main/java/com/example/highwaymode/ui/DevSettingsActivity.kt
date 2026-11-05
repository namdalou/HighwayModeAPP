package com.example.highwaymode.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import com.example.highwaymode.car.AutoDiagnostics
import com.example.highwaymode.data.DataSyncService
import com.example.highwaymode.data.DataSyncState
import com.example.highwaymode.data.Prefs
import com.example.highwaymode.engine.DemoPreset
import com.example.highwaymode.engine.EngineHost
import com.example.highwaymode.nav.NavNotifications
import com.example.spectrum.DiagnosticsActivity
import java.security.MessageDigest

/**
 * 開発者向け設定への入口（パスワードの確認）。
 * 音楽の画面の何もない場所を 20 回続けてタップすると、パスワードを聞く（Spectrum の診断画面と同じ操作）。
 * パスワードそのものはコードに書かず、SHA-256 の値だけを持つ。
 */
object DevAccess {

    /** パスワードの SHA-256（パスワードを変えるときは、新しいパスワードの SHA-256 に置き換える） */
    private const val PASSWORD_SHA256 = "550a4fe94719d587889800b74a2e017c615595b8b8639f6e560e78d5de6fb9f6"

    /** 開いてから、この時間を過ぎて作り直された画面（アプリが裏で終了した後の復元など）はパスワードを聞き直す */
    private const val UNLOCK_MS = 30 * 60_000L

    @Volatile
    private var unlockedAt = 0L

    fun isUnlocked(): Boolean {
        val t = unlockedAt
        return t != 0L && SystemClock.elapsedRealtime() - t < UNLOCK_MS
    }

    fun lock() {
        unlockedAt = 0L
    }

    private fun matches(input: String): Boolean {
        val d = MessageDigest.getInstance("SHA-256").digest(input.trim().toByteArray(Charsets.UTF_8))
        val hex = d.joinToString("") { String.format("%02x", it.toInt() and 0xFF) }
        return MessageDigest.isEqual(hex.toByteArray(Charsets.US_ASCII), PASSWORD_SHA256.toByteArray(Charsets.US_ASCII))
    }

    /** パスワードを聞いて、合っていれば開発者向け設定を開く */
    fun prompt(a: Activity) {
        if (a.isFinishing) return
        val density = a.resources.displayMetrics.density
        val edit = EditText(a).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "パスワード"
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF7D8590.toInt())
        }
        val box = FrameLayout(a).apply {
            val p = (20 * density).toInt()
            setPadding(p, (8 * density).toInt(), p, 0)
            addView(edit)
        }
        val dialog = AlertDialog.Builder(a, SettingsUi.DIALOG_THEME)
            .setTitle("開発者向け設定")
            .setMessage("パスワードを入力してください")
            .setView(box)
            .setPositiveButton("開く", null)
            .setNegativeButton("キャンセル", null)
            .create()
        val submit = {
            if (matches(edit.text.toString())) {
                unlockedAt = SystemClock.elapsedRealtime()
                dialog.dismiss()
                a.startActivity(Intent(a, DevSettingsActivity::class.java))
            } else {
                edit.setText("")
                edit.error = "パスワードが違います"
            }
        }
        dialog.setOnShowListener {
            // 違うときは閉じずに入れ直してもらう
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { submit() }
        }
        edit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else false
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
        edit.requestFocus()
    }
}

/**
 * 開発者向け設定（公開版の設定画面には出さない項目）。[DevAccess] でパスワードを確認してから開く。
 * Spectrum の診断・音声の読み取り（Shizuku）・デモ走行・渋滞情報の取得元と API キー・地図データの細かい設定・
 * Android Auto の状態（インストール元）。戻ると閉じて、次に開くときはパスワードが必要。
 */
class DevSettingsActivity : Activity() {

    private lateinit var ui: SettingsUi
    private lateinit var prefs: Prefs
    /** 画面に戻るたびに、いまの状態を表示し直す行 */
    private val refreshers = ArrayList<() -> Unit>()
    /** 更新・作成の進み具合（進むたびに呼ばれるので、進み具合の行だけ。終わったら全体を読み直す） */
    private var progressRefresh: (() -> Unit)? = null
    private val syncListener = DataSyncState.Listener {
        progressRefresh?.invoke()
        if (!DataSyncState.running) refreshAll()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // パスワードを確かめずに開かれた（アプリが裏で終了した後に画面だけ作り直されたなど）
        if (!DevAccess.isUnlocked()) {
            finish()
            return
        }
        prefs = Prefs(this)
        ui = SettingsUi(this)
        build()
        DataSyncState.addListener(syncListener)
    }

    override fun onResume() {
        super.onResume()
        if (::ui.isInitialized) refreshAll()
    }

    override fun onDestroy() {
        DataSyncState.removeListener(syncListener)
        // 閉じたら、次に開くときはパスワードが必要
        if (isFinishing) DevAccess.lock()
        super.onDestroy()
    }

    private fun refreshAll() {
        for (r in refreshers) r()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun build() {
        val c = ui.page("開発者向け設定", "公開版の設定画面には出さない項目です") { finish() }

        // ---- Spectrum（音楽）
        val sp = ui.section(c, "Spectrum（音楽）", "音声の読み取り（Shizuku）は v2.5 から通常の設定（音楽）に移しました。")
        ui.actionRow(sp, "Spectrum の診断画面", "曲情報の取得元の選択・音声の解析の状態") {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }

        // ---- 走行のテスト
        val dm = ui.section(c, "走行のテスト")
        val demo = ui.actionRow(dm, "デモ走行", "実際の地図データ上を模擬走行します（数秒で 2 画面に切り替わるので、自動切替の確認にも使えます）") {
            if (EngineHost.demoRunning) {
                EngineHost.stopDemo()
                toast("デモ走行を停止しました")
                c.postDelayed({ refreshAll() }, 300)
            } else {
                showDemoMenu()
            }
        }
        refreshers += {
            demo.value(if (EngineHost.demoRunning) "走行中（押すと停止）" else if (EngineHost.running) "停止中" else "使えません（位置情報の許可が必要）")
        }

        // ---- Google マップのナビ連携（確認用）
        val gm = ui.section(c, "Google マップのナビ連携（確認用）", "うまく合わないときは、ここの内容をコピーして送ってください（通知の言い回しに合わせて直せます）。")
        val navRow = ui.actionRow(gm, "最後に受け取ったナビの通知", "通知の文字（部品の名前つき）・読み取った距離と案内・照合の結果") {
            showNavDebug()
        }
        refreshers += {
            val at = NavNotifications.lastReadWallMs
            navRow.value(if (at == 0L) "まだありません" else Formats.clock(at) + if (NavNotifications.active) "（ナビ中）" else "", SettingsUi.C_SUB)
        }

        // ---- 地図データ（詳細）
        val md = ui.section(c, "地図データ（詳細）")
        val info = ui.infoRow(md, "端末内の地図データ", "読み込み中…")
        refreshers += { SettingsDialogs.mapDetails(this) { info.value(it, SettingsUi.C_TITLE) } }
        val progress = ui.infoRow(md, "更新・作成の進み具合", null)
        val showProgress = {
            val p = SettingsDialogs.syncProgress()
            progress.shown(p != null)
            progress.value(p, SettingsUi.C_WARN)
        }
        progressRefresh = showProgress
        refreshers += showProgress
        ui.actionRow(md, "地方を指定して端末内で作成", "Overpass API から都道府県ごとに取得します（1 地方 数分〜20 分）") {
            SettingsDialogs.chooseRegions(this)
        }
        ui.actionRow(md, "更新・作成を中止", null) {
            DataSyncService.cancel(this)
            toast("中止しました")
        }
        ui.switchRow(md, "端末内データに無い範囲はオンラインで取得する", "Overpass API から取得（取得した範囲は 60 日キャッシュ）", prefs.onlineTiles) {
            prefs.onlineTiles = it
        }
        val url = ui.actionRow(md, "配布元（目録 URL）", null) {
            SettingsDialogs.editManifestUrl(this, prefs) { refreshAll() }
        }
        refreshers += { url.value(prefs.manifestUrl.ifBlank { "未設定" }, SettingsUi.C_SUB) }
        val ep = ui.actionRow(md, "Overpass サーバー", null) {
            SettingsDialogs.editEndpoints(this, prefs) { refreshAll() }
        }
        refreshers += { ep.value(prefs.endpoints.joinToString("\n"), SettingsUi.C_SUB) }
        ui.actionRow(md, "オンライン取得のキャッシュを削除", null) {
            EngineHost.clearCache()
            toast("削除しました")
            refreshAll()
        }
        ui.actionRow(md, "ダウンロード・作成したデータを削除", "アプリに同梱のデータは残ります", danger = true) {
            SettingsDialogs.deleteDownloaded(this) { refreshAll() }
        }

        // ---- Android Auto
        val aa = ui.section(c, "Android Auto")
        ui.actionRow(aa, "Android Auto の状態（一覧に出ないとき）", "インストール元・Android Auto の版・宣言の有無と対処") {
            AutoDiagnostics.show(this)
        }
        ui.actionRow(aa, "動作状態（詳細）", "常駐サービス・GPS・電池・通信・渋滞情報・曲情報") {
            SettingsDialogs.showRunStatus(this)
        }
        ui.actionRow(aa, "車の画面の表示範囲（確認用）", "右上のボタンと一覧が重なるときの確認用（画面の大きさ・Android Auto が知らせた範囲・下げた量）") {
            val msg = com.example.highwaymode.car.DualCarScreen.areaInfo
            AlertDialog.Builder(this, SettingsUi.DIALOG_THEME)
                .setTitle("車の画面の表示範囲")
                .setMessage(msg + "\n\n右上のボタンを出した状態で開くと、ボタン表示中の値になります（車の画面を表示したままスマホで開く）。")
                .setPositiveButton("閉じる", null)
                .setNeutralButton("コピー") { _, _ ->
                    val cm = getSystemService(android.content.ClipboardManager::class.java)
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("車の画面の表示範囲", msg))
                    toast("コピーしました")
                }
                .show()
        }

        ui.note(c, "戻ると閉じます。次に開くときは、音楽の画面の何もない場所を 20 回続けてタップして、パスワードを入力してください。")
    }

    /** ナビの通知の受信内容（確認用） */
    private fun showNavDebug() {
        val nav = EngineHost.latest?.nav
        val msg = buildString {
            append("受信: ").append(if (NavNotifications.lastReadWallMs == 0L) "まだありません" else Formats.clock(NavNotifications.lastReadWallMs)).append('\n')
            append("ナビ中: ").append(if (NavNotifications.active) "はい" else "いいえ").append('\n')
            append("距離: ").append(NavNotifications.lastDistanceM?.let { Formats.distance(it) } ?: "読み取れず").append('\n')
            append("案内: ").append(NavNotifications.lastText ?: "—").append('\n')
            append("矢印の画像: ").append(if (NavNotifications.lastIconOk) "読み取れた" else "読み取れず（案内の文から矢印を描く）").append("\n\n")
            append("照合: ").append(
                when {
                    nav == null -> "なし（高速道路の外、または案内なし）"
                    nav.exitName != null -> "降りる IC = ${nav.exitName}"
                    nav.transferText != null -> "乗り換え = ${nav.transferText}"
                    else -> "前方の施設・分岐と合いませんでした"
                }
            ).append("\n\n通知の文字:\n")
            if (NavNotifications.lastPieces.isEmpty()) append("（なし）") else NavNotifications.lastPieces.forEach { append("・").append(it).append('\n') }
        }
        AlertDialog.Builder(this, SettingsUi.DIALOG_THEME)
            .setTitle("ナビの通知（確認用）")
            .setMessage(msg)
            .setPositiveButton("閉じる", null)
            .setNeutralButton("コピー") { _, _ ->
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("ナビの通知", msg))
                toast("コピーしました")
            }
            .show()
    }

    private fun showDemoMenu() {
        if (!EngineHost.running) {
            toast("位置情報を許可すると使えます")
            return
        }
        val presets = DemoPreset.values()
        AlertDialog.Builder(this, SettingsUi.DIALOG_THEME)
            .setTitle("デモ走行（実際の地図データ上を模擬走行）")
            .setItems(presets.map { it.label }.toTypedArray()) { _, which ->
                EngineHost.startDemo(presets[which])
                toast("デモ走行を始めました")
                window.decorView.postDelayed({ refreshAll() }, 300)
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }
}

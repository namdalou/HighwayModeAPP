package com.example.spectrum

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 曲情報がどこから取れるかを確認する画面。
 * メイン画面の何もない場所を20回連続タップで開く。通知の一覧から曲情報の取得元を選べる。
 */
class DiagnosticsActivity : Activity() {

    private lateinit var body: LinearLayout
    private val report = StringBuilder()

    private val prefs by lazy { getSharedPreferences(MediaListenerService.PREFS, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 48)
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.rgb(10, 20, 30))
            addView(body)
            // Android 15 以降の画面端までの描画で、システムバーと重ならないようにする
            setOnApplyWindowInsetsListener { v, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val b = insets.getInsets(
                        WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                    )
                    v.setPadding(b.left, b.top, b.right, b.bottom)
                }
                insets
            }
        })
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        body.removeAllViews()
        report.setLength(0)

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(btn("更新") { render() }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(btn("診断内容をコピー") { copyReport() }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(btn("閉じる") { finish() }, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(top)

        header("状態")
        line("音声解析(スペアナ): ${SpectrumEngine.status.name}")
        line("通知アクセス: ${if (NowPlaying.listenerConnected) "接続中" else "未接続"}")
        val cs = SpectrumEngine.currentSession
        line("解析中の音声: ${when { cs < 0 -> "なし"; cs == 0 -> "端末全体の出力音(セッション0)"; else -> "アプリの音声(セッション$cs)" }}")
        val ld = SpectrumEngine.loudness
        line("曲の音量(強弱): ${if (ld < 0f) "測定できない端末(従来の方式)" else "${(ld * 100).toInt()}%"}")
        line("音声の読み取り(DUMP 権限): ${if (SessionScanner.hasPermission(this)) "許可済み" else "未許可"}  / ${SessionScanner.lastStatus}")
        if (!SessionScanner.hasPermission(this)) {
            val shizukuState = when {
                !ShizukuHelper.isInstalled(this) -> "未インストール"
                !ShizukuHelper.isRunning() -> "停止中"
                else -> "起動中"
            }
            line("Shizuku: $shizukuState")
            body.addView(btn("Shizuku で音声の読み取りを許可") {
                ShizukuHelper.grantDump(this) { ok, msg ->
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    if (!ok && !ShizukuHelper.isRunning()) ShizukuHelper.openShizuku(this)
                    render()
                }
            })
        }
        val sc = SessionScanner.lastResult
        line("読み取った再生中の音声: ${if (sc.isEmpty()) "なし" else sc.entries.joinToString { "${it.key}=${it.value}" }}")
        val apps = SpectrumEngine.appSessions
        line("音楽アプリから届いた音声セッション: ${if (apps.isEmpty()) "なし" else apps.entries.joinToString { "${it.key}=${it.value}" }}")
        val probed = SpectrumEngine.probedSessions
        line("自動で見つけた音声(Shizuku なし): ${if (probed.isEmpty()) "なし" else probed.entries.joinToString { "${it.key}=${it.value}" }}  / ${SessionProber.lastStatus}")

        header("いま表示している曲情報")
        line("取得元: ${sourceName(NowPlaying.source)}  ${NowPlaying.sourcePackage}")
        line("曲名: ${NowPlaying.title}")
        line("アーティスト: ${NowPlaying.artist}")
        line("アルバム: ${NowPlaying.album}")
        line("ジャケット: ${if (NowPlaying.art != null) "あり" else "なし"}")
        line("操作: ${if (NowPlaying.controller != null) "セッション経由" else "メディアキー送信"}")

        header("通知からの取得元")
        val chosen = prefs.getString(MediaListenerService.KEY_FALLBACK_PKG, null)
        line("現在の設定: ${chosen ?: "自動（パッケージ名に zlink を含むアプリ）"}")
        if (chosen != null) {
            body.addView(btn("自動に戻す") {
                prefs.edit().remove(MediaListenerService.KEY_FALLBACK_PKG).apply()
                MediaListenerService.instance?.refreshFallback(evenWithSession = true)
                render()
            })
        }

        header("メディアセッション")
        val sessions = Diagnostics.sessions
        if (sessions.isEmpty()) line("（なし）") else sessions.forEach { line("・$it") }

        header("曲変更ブロードキャスト（最後に受信したもの）")
        line(Diagnostics.lastBroadcast.ifEmpty { "（なし）" })

        header("通知を出しているアプリ（タップで曲情報の取得元に設定）")
        val list = Diagnostics.notifications.values.sortedByDescending { it.time }
        if (list.isEmpty()) line("（なし）")
        list.forEach { e ->
            val mark = if (e.hasSession) " [セッションあり]" else ""
            val label = "${e.label} (${e.pkg})$mark\n${e.title} / ${e.text}"
            report.append("・").append(label.replace("\n", "  ")).append('\n')
            body.addView(btn(label) {
                AlertDialog.Builder(this)
                    .setTitle("曲情報の取得元を変更")
                    .setMessage(
                        "${e.label} の通知を曲情報の取得元にしますか？\n" +
                        "(音楽アプリの再生情報が取れないときだけ使われます)"
                    )
                    .setPositiveButton("設定する") { _, _ ->
                        prefs.edit().putString(MediaListenerService.KEY_FALLBACK_PKG, e.pkg).apply()
                        MediaListenerService.instance?.refreshFallback()
                        Toast.makeText(this, "${e.label} を取得元に設定しました", Toast.LENGTH_SHORT).show()
                        render()
                    }
                    .setNegativeButton("キャンセル", null)
                    .show()
            })
        }
    }

    private fun sourceName(s: String) = when (s) {
        "session" -> "メディアセッション"
        "notification" -> "通知"
        "broadcast" -> "ブロードキャスト"
        else -> "なし"
    }

    private fun header(t: String) {
        report.append("\n■ ").append(t).append('\n')
        body.addView(TextView(this).apply {
            text = t
            textSize = 15f
            setTextColor(Color.rgb(40, 190, 245))
            setPadding(0, 28, 0, 8)
        })
    }

    private fun line(t: String) {
        report.append(t).append('\n')
        body.addView(TextView(this).apply {
            text = t
            textSize = 13f
            setTextColor(Color.rgb(220, 230, 240))
        })
    }

    private fun btn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        textSize = 12f
        setOnClickListener { onClick() }
    }

    private fun copyReport() {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("Spectrum 診断", report.toString()))
        Toast.makeText(this, "コピーしました", Toast.LENGTH_SHORT).show()
    }
}

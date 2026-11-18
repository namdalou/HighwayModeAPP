package com.example.highwaymode.car

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RenderNode
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.highwaymode.CrashLog
import com.example.highwaymode.R
import com.example.highwaymode.core.dual.Box
import com.example.highwaymode.core.dual.DualFrame
import com.example.highwaymode.core.dual.DualGeometry
import com.example.highwaymode.core.dual.DualLayout
import com.example.highwaymode.core.engine.HighwaySnapshot
import com.example.highwaymode.data.AppSettings
import com.example.highwaymode.data.Prefs
import com.example.highwaymode.dual.DualMode
import com.example.highwaymode.dual.ModeBarRenderer
import com.example.highwaymode.engine.EngineHost
import com.example.highwaymode.ui.PanelRenderer
import com.example.spectrum.MediaTracker
import com.example.spectrum.NowPlaying
import com.example.spectrum.PlayerRenderer
import com.example.spectrum.SpectrumEngine
import java.util.concurrent.locks.LockSupport
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * 車の画面（Android Auto）。ナビ用テンプレートの地図面に、Spectrum とハイウェイモードを描く。
 *
 *  - 高速道路の外: Spectrum を全画面（従来の Spectrum と同じ）
 *  - 高速道路の対象エリアに入ると自動で 2 画面（左: Spectrum の縦レイアウト / 右: ハイウェイモード）
 *  - 下部に切替バー（音楽 / 2画面 / ハイウェイ / ≡ メニュー）。v2.17 から高速道路の外でもいつも出す
 *    （右上のボタンの代わりに「アップデートを確認」しか出ない車があった。右上のボタンが出なくても、画面のタッチで操作できるように）。
 *    v2.18 から、2 画面のときはハイウェイモード側の下だけに置き、Spectrum 側は下端まで使う
 *  - 右上のボタン（Android Auto が出す。1 つは必ず要る）は v2.18 から、画面にタッチ操作が届いたことのある車では 1 つだけ（アイコン）:
 *    高速道路では「オービス登録」、外では「スペアナの表示」。画面の切替・メニューは下部バーと重複するので出さない（利用者の指示）。
 *    タッチ操作が一度も届いていない車（タッチのできない車・ダイヤル操作の車）では、今までどおり画面切替・メニューも出す
 *
 * 描画は専用スレッド（表示用の優先度）で、決まった間隔で行う。
 * Spectrum が再生中で見えている間は 30fps（Android Auto の映像と同じ程度）、一時停止中は 15fps、
 * ハイウェイだけのときは 10fps（下のバーの曲名を流している間は 30fps）。
 * ハイウェイモードの画面は内容が変わったとき（約 1 秒ごと）だけ描き直し、間は記録した描画を再生する。
 *
 * **画面へ出す時刻をそろえる（v2.15）**: Android Auto の映像は、スマホの中の画面の合成（約 16.7ms ごと）を通って車へ送られる。
 * 出す時刻が数 ms ずつぶれると、合成の区切りをまたいだフレームだけ 1 区切り遅れ、動きがときどき引っかかって見える
 * （利用者の報告: Android Auto でスペアナがときどきカクつく）。そこで
 *  - 出す時刻は 33.33ms ごとの決まった時刻（ナノ秒で管理）にする。描くのにかかる時間（最近の最大に近い値）だけ前から描き始め、
 *    描き終わったら決まった時刻まで待ってから出す。描くのにかかる時間が変わっても、出す時刻は変わらない
 *  - 約 1 秒ごとのハイウェイモードの画面の描き直しは、フレームを出した直後の空き時間に行う（その分だけ遅れるフレームを作らない）
 *
 * **起動直後・大きい画面のカクつき（v2.21）**: 利用者の報告「Android Auto の起動時や、大きい画面のディスプレイオーディオで、
 * ときどきスペアナがカクつく」「画面の切り替えがもっさりする」。
 *  - ハイウェイモードの画面の約 1 秒ごとの描き直しは、v2.15 からフレームを出した直後の空き時間に描画スレッドで行っていたが、
 *    描き直しにかかる時間が空き時間より長いと次のフレームが遅れる。1 秒に 1 回しか動かない処理は、起動してしばらくは
 *    速いコードに置き換わらない（Android の実行時の最適化が進まない）うえ、大きい画面では行・文字が多い。そこで、
 *    描き直しは別のスレッド（car-panel）で裏の記録に描き、できあがったら表と入れ替える（2 枚の記録を交互に使う）。
 *    すぐ反映したいとき（操作・区画の変更）だけ、これまでどおり描画スレッドでその場で描く
 *  - 下部の切替バーは毎フレーム描いていた（文字の幅を測る・文字列を作る処理が毎回あった）。記録（RenderNode）に描いておき、
 *    中身（画面構成・エンジンの状態・曲・案内の文・曲名の流し表示）が変わったとき・1 秒ごとだけ描き直す
 *  - 利用者がボタンで画面を切り替えたときは、黒からのフェード（約 0.26 秒）をやめてすぐ切り替える（自動の切替だけ短くフェード）
 *  - 確認用に、描画の様子（1 秒あたりのフレーム数・遅れたフレーム・描く時間）を「車の画面の表示範囲（確認用）」に出す
 *  - 開発者向け設定で 60fps にもできる（試験。車・スマホによっては、なめらかになる）
 *
 * 表示する状態は、毎フレーム エンジンの最新の状態（EngineHost.latest）を読む。
 * 画面の状態（他の画面を重ねた・Android Auto の別のアプリを前に出した）によらず、スマホの画面と同じ内容になる。
 *
 * 右上のボタン（Android Auto が表示。操作しないと約 10 秒で隠れる）のために画面を下げない。
 * 画面いっぱいに描き、ボタンが出ている間だけ、施設の一覧の列をボタンの下へずらす。
 * ボタンが隠れたことを表示範囲の通知で知らせてこない車もあるので、最後の操作（タップ・ボタン・画面の表示）から
 * 約 10 秒たったら、通知が無くても隠れたとみなして画面いっぱいに戻す（ずらす・戻すときは短くなめらかに動かす）。
 */
class DualCarScreen(carContext: CarContext, private val onReady: () -> Unit) :
    Screen(carContext), SurfaceCallback, DualMode.Listener {

    companion object {
        private const val TAG = "DualCarScreen"
        /** Spectrum が再生中で見えているときの描画間隔（30fps） */
        private const val FRAME_MS = 1000.0 / 30.0
        /** 開発者向け設定で 60fps を選んだとき（v2.21。試験） */
        private const val FRAME_MS_60 = 1000.0 / 60.0
        /** Spectrum が見えていて一時停止中の描画間隔（曲名の流し表示用。15fps） */
        private const val PAUSED_FRAME_MS = 1000.0 / 15.0
        /** ハイウェイモードだけのときの描画間隔 */
        private const val IDLE_FRAME_MS = 100.0
        // ---- 画面へ出す時刻の管理（System.nanoTime 基準）
        /** 描くのにかかる時間の見積もりの初期値・下限・上限 [ns] */
        private const val DRAW_COST_START_NS = 6_000_000L
        private const val DRAW_COST_MIN_NS = 2_000_000L
        private const val DRAW_COST_MAX_NS = 20_000_000L
        /** 見積もりを 1 フレームごとに下げる量 [ns]（たまに重いフレームがあっても、約 1 秒に 1ms ずつ元に戻る） */
        private const val DRAW_COST_DECAY_NS = 30_000L
        /** 見積もりに足す余裕 [ns] */
        private const val DRAW_MARGIN_NS = 1_500_000L
        /** Handler に起こしてもらう時刻を、描き始める時刻のどれだけ前にするか [ns]（Handler は 1ms 刻みなので、残りは正確に待つ） */
        private const val WAKE_EARLY_NS = 2_000_000L
        /** 出す時刻がこれ以上遅れたら、決まった時刻を数え直す [ns] */
        private const val ON_TIME_NS = 3_000_000L
        /** 予定の時刻をこれ以上過ぎていたら（止まっていた）、いまから数え直す [ns] */
        private const val LATE_RESET_NS = 50_000_000L

        /** 曲情報の取りこぼしを確認する間隔 */
        private const val HEAL_MS = 2000L
        /** ハイウェイモードの画面を描き直す間隔（時計・残り時間） */
        private const val PANEL_REFRESH_MS = 1000L
        /** 下部の切替バーを描き直す間隔（到着予定の時刻など。中身が変わったときは、すぐ描き直す） */
        private const val BAR_REFRESH_MS = 1000L
        /**
         * 自動の切替（高速道路に入った・出た）のときのフェードの長さと、最初の暗さ（0〜255）。
         * 利用者がボタンで切り替えたときはフェードしない（v2.21。v2.20 までは 260ms・170 で、もっさりして見えた）
         */
        private const val AUTO_FADE_MS = 180L
        private const val AUTO_FADE_ALPHA = 120
        /**
         * ハイウェイだけにしてから Spectrum の音声解析を止めるまで（すぐ戻す場合に備える）。
         * v2.21 で 4 秒 → 30 秒（戻したときに解析の付け直し・音量の慣らしからやり直さず、すぐ動くように。解析は軽い）
         */
        private const val SPECTRUM_RELEASE_DELAY_MS = 30_000L
        /**
         * 右上のボタンが、最後の操作から隠れるまで。Android Auto は約 10 秒操作が無いとボタンを隠す
         * （この時間はアプリからは変えられない）。隠れたことを知らせてこない車でも、この時間で一覧を元に戻す
         */
        private const val STRIP_HIDE_MS = 10_000L
        /** 一覧をボタンの下へずらす・戻す動きの時定数（約 0.2 秒で動き終わる） */
        private const val SHIFT_TAU_MS = 70.0
        /** 車の画面を出してから、メニューの見本の絵を作り始めるまで（起動直後の描画と重ならないように） */
        private const val PREWARM_DELAY_MS = 8000L

        /**
         * 右上のボタンの下端の見積もり [dp]（上の余白 12 ＋ ボタン 60 ＋ 一覧との間 8）。
         * 車によっては Android Auto が知らせる表示範囲の上端がボタンの下端より小さく、
         * その分だけ下げると一覧がボタンに重なる（v2.6 まで）。知らせてくる値とこの見積もりの大きいほうまで下げる
         */
        private const val STRIP_BOTTOM_DP = 80f

        /** 確認用（開発者向け設定）: 車の画面の大きさ・Android Auto が知らせた表示範囲・一覧を下げた量 */
        @Volatile
        var areaInfo: String = "まだ車の画面を表示していません"
            private set
    }

    private val prefs = Prefs(carContext)
    private val main = Handler(Looper.getMainLooper())

    // ---- 描画部品
    // 2 画面の左側は、運転中でも読めるよう曲名・アルバム名・アーティスト名を大きくした表示（large）
    private val music = PlayerRenderer().apply {
        onArtTap = { openPlayingAppOnCar() }
    }
    private val panel = PanelRenderer(1f).apply {
        showButtons = false
        attribution = true
        // 時刻は Android Auto が出すので省き、見出しの右側は右上のボタンのために空ける
        showClock = false
        displayMode = prefs.displayMode
    }
    private val bar = ModeBarRenderer(1f).apply {
        // 右上のボタンが出ない車でも、メニュー（画面の切替・スペアナの表示・設定）を開けるように（v2.17）
        showMenuButton = true
        menuAsLines = true
        idleHint = "高速道路に入ると自動で 2 画面になります"
    }
    private val dividerPaint = Paint().apply { color = Color.rgb(24, 38, 50) }
    private val fadePaint = Paint()

    // ---- 描画面（描画スレッドとメインスレッドで共有）
    @Volatile private var surface: Surface? = null
    /** Android Auto と取り決めた Car App API のレベル（確認用。読めなければ -1） */
    @Volatile private var hostApiLevel = -1
    @Volatile private var width = 0
    @Volatile private var height = 0
    @Volatile private var density = 1f
    private val visible = Rect()
    private val stable = Rect()
    /** 描画スレッドで使う写し（毎フレームの確保をしない） */
    private val vCopy = Rect()
    private val sCopy = Rect()
    /** Android Auto が右上のボタンの表示・非表示を表示範囲で知らせてくる（一度でも隠れたのを確認した） */
    @Volatile private var hostReportsStrip = false
    /**
     * 右上のボタンが出た（出たまま）とみなした時刻（SystemClock.uptimeMillis）。
     * 画面の表示・タップ・ボタンの操作・表示範囲の上端が下がった通知のたびに更新し、
     * ここから [STRIP_HIDE_MS] たったらボタンは隠れたとみなす
     */
    @Volatile private var stripShownAt = SystemClock.uptimeMillis()
    /** いま一覧をずらしている量 [px]（描画スレッド専用。目標へなめらかに近づける） */
    private var shiftShownH = 0f
    private var shiftAnimAt = 0L
    /** 次のフレームを画面へ出す予定の時刻 [ns]（System.nanoTime 基準。0 = 予定なし: すぐ描く。描画スレッド専用） */
    private var postAtNs = 0L
    /** 「すぐ描く」の依頼の数（メインスレッドが進める。最初の 1 回も依頼として数える） */
    @Volatile private var frameRequests = 1
    /** 描画スレッドが応えた依頼の数（描画スレッド専用） */
    private var handledRequests = 0
    /** 描き始めてから画面へ出せるようになるまでの時間の見積もり [ns]（描画スレッド専用） */
    private var drawCostNs = DRAW_COST_START_NS
    /** いまのフレームを描き終えた（出せるようになった）時刻 [ns]。描かなかったら 0（描画スレッド専用） */
    private var frameReadyNs = 0L
    /** 右上のボタンの組み合わせ（v2.18） */
    private enum class StripKind {
        /** タッチ操作が届いていない車・音楽だけ（高速道路の外）: ハイウェイ表示・メニュー */
        FULL_MUSIC,
        /** タッチ操作が届いていない車・2 画面など: 画面切替・施設・オービス登録・メニュー */
        FULL_HIGHWAY,
        /** タッチできる車・高速道路の外: スペアナの表示（アイコンだけ） */
        TOUCH_MUSIC,
        /** タッチできる車・高速道路: オービス登録（アイコンだけ） */
        TOUCH_HIGHWAY
    }

    /**
     * この車の画面にタッチ操作が届いたことがある（v2.18。設定に保存して次の接続でも使う）。
     * 届いたことがあれば、画面の切替・メニューは下部バーで操作できるので、右上のボタンは 1 つだけにする
     */
    @Volatile private var touchSeen = prefs.carTouch
    /** テンプレート（右上のボタン）を最後に作ったときの組み合わせ */
    @Volatile private var templateStrip: StripKind? = null
    @Volatile private var snap: HighwaySnapshot? = EngineHost.latest
    @Volatile private var lastFrame: DualFrame? = null
    @Volatile private var panelInvalid = true
    /**
     * ハイウェイモードの画面の中身が古くなった（新しい位置情報が来た）。[panelInvalid] と違い、急がない:
     * 次のフレームを出した直後の空き時間に描き直す（描き直しの分だけフレームが遅れないように）
     */
    @Volatile private var panelStale = false
    /** 自動切替の設定（描画スレッドで設定を読まないよう、メインスレッドで写しておく） */
    @Volatile private var autoSwitch = prefs.autoSwitch
    /** 再生中の描画間隔（30fps。開発者向け設定で 60fps。v2.21） */
    @Volatile private var frameMs = if (prefs.carFps60) FRAME_MS_60 else FRAME_MS

    private var drawThread: HandlerThread? = null
    @Volatile private var drawHandler: Handler? = null

    // ---- ハイウェイモード画面の記録（Android 10 以降。v2.21 から 2 枚を交互に使う）
    /** 描画スレッドが毎フレーム描く記録（表）と、別のスレッドが描き直す記録（裏）。型は RenderNode（Android 9 以前で読み込まないよう Any） */
    private var panelFront: Any? = null
    private var panelBack: Any? = null
    /** 裏の記録を別のスレッドで描き直している最中（描画スレッド専用） */
    private var panelRecording = false
    /** 別のスレッドで描き直し終えた裏の記録（別のスレッドが書き、描画スレッドが受け取る） */
    @Volatile private var panelResult: PanelResult? = null
    /** 記録の世代（その場で描き直したら増やす。古い世代の描き直しは使わない。描画スレッド専用） */
    private var panelGen = 0
    private var panelThread: HandlerThread? = null
    @Volatile private var panelHandler: Handler? = null
    /** ハイウェイモードの画面の部品（PanelRenderer）を 2 つのスレッドで同時に使わないための鍵 */
    private val panelLock = Any()
    private var panelDrawnAt = 0L
    /** 記録したときの区画と画面の大きさ（変わったら描き直す。毎フレーム文字列を作らないよう数値で持つ） */
    private var panelBoxL = -1f
    private var panelBoxT = -1f
    private var panelBoxR = -1f
    private var panelBoxB = -1f
    private var panelSurfaceW = 0
    private var panelSurfaceH = 0
    /** 右上のボタンのために空ける幅・一覧を下げる量（描画スレッドが決めた値。記録するときに部品へ渡す） */
    private var panelReserveW = 0f
    private var panelShiftH = 0f

    /** 別のスレッドで描き直した記録（node は RenderNode）と、その世代・かかった時間 */
    private class PanelResult(val node: Any, val gen: Int, val costNs: Long)

    // ---- 下部の切替バーの記録（Android 10 以降。v2.21。中身が変わったときだけ描き直す）
    private var barNode: Any? = null
    private var barL = -1f
    private var barT = -1f
    private var barR = -1f
    private var barB = -1f
    private var barSurfaceW = 0
    private var barSurfaceH = 0
    private var barLayout: DualLayout? = null
    private var barSnap: HighwaySnapshot? = null
    private var barHint: String? = null
    private var barTitle: String? = null
    private var barArtist: String? = null
    private var barPlaying = false
    private var barArt: Any? = null
    /** 次に描き直す時刻（SystemClock.uptimeMillis。曲名を流している間は 0 = 毎フレーム） */
    private var barNextAt = 0L

    // ---- 描画の様子（確認用。描画スレッド専用。1 秒ごとに文字にする。v2.21）
    private var statSince = 0L
    private var statFrames = 0
    private var statLate = 0
    private var statDrawSumNs = 0L
    private var statDrawMaxNs = 0L
    private var statPanelSyncNs = 0L
    @Volatile private var statPanelBgNs = 0L
    private var statText = ""

    // ---- Spectrum の音声解析
    private val specListener: (FloatArray) -> Unit = { music.setSpectrum(it) }
    private var spectrumAcquired = false
    private val releaseSpectrum = Runnable { stopSpectrum() }

    private val snapshotListener = EngineHost.SnapshotListener { s ->
        snap = s
        panelStale = true
    }

    private val frame = object : Runnable {
        override fun run() {
            val h = drawHandler ?: return
            // 二重に予約されていたら 1 つにまとめる
            h.removeCallbacks(this)
            val requests = frameRequests
            // 「すぐ描く」の依頼が来ていたら（最初・タップなど）、予定の時刻を待たずに描いて、そこから数え直す。
            // 依頼は数で見分ける（予定の時刻を別のスレッドから書き換えると、ここで書き戻してしまうことがあるため）
            var target = if (requests != handledRequests) 0L else postAtNs
            handledRequests = requests
            // 大きく遅れたとき（止まっていた）も、そこから数え直す
            if (target != 0L && System.nanoTime() - target > LATE_RESET_NS) target = 0L
            // 描き始める時刻まで正確に待つ（描き始めの間隔がそろうと、経過時間で計算している動きもそろう）
            if (target != 0L) waitUntil(target - drawCostNs - DRAW_MARGIN_NS)
            val t0 = System.nanoTime()
            frameReadyNs = 0L
            val periodMs = try {
                drawFrame(target)
            } catch (e: Exception) {
                Log.w(TAG, "draw failed: ${e.message}")
                CrashLog.note("車の画面: 描画", e)
                IDLE_FRAME_MS
            }
            val posted = System.nanoTime()
            // 描くのにかかった時間の見積もり（最近の最大に近い値。少しずつ下げる）
            if (frameReadyNs != 0L) {
                val cost = frameReadyNs - t0
                drawCostNs = max(cost, drawCostNs - DRAW_COST_DECAY_NS).coerceIn(DRAW_COST_MIN_NS, DRAW_COST_MAX_NS)
                // 確認用の数（描き終えたのが出す予定の時刻より 1ms 以上後なら「遅れた」）
                statFrames++
                statDrawSumNs += cost
                if (cost > statDrawMaxNs) statDrawMaxNs = cost
                if (target != 0L && frameReadyNs - target > 1_000_000L) statLate++
            }
            // ハイウェイモードの画面の記録が古くなっていたら、別のスレッドで描き直してもらう（v2.21。描画スレッドは待たない）
            try {
                schedulePanelRefresh()
            } catch (e: Exception) {
                Log.w(TAG, "panel refresh failed: ${e.message}")
                CrashLog.note("車の画面: ハイウェイ側の描き直しの依頼", e)
            }
            // 描いている間に「すぐ描く」の依頼が来ていたら、その予約に任せる
            if (frameRequests != requests) return
            // 次に出す時刻: 予定どおり出せたなら予定の時刻から数える（ずれをためない）。遅れたら出した時刻から
            val base = if (target != 0L && posted - target < ON_TIME_NS) target else posted
            val next = base + (periodMs * 1_000_000.0).toLong()
            postAtNs = next
            val wakeNs = next - drawCostNs - DRAW_MARGIN_NS - WAKE_EARLY_NS
            h.postDelayed(this, ((wakeNs - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L))
        }
    }

    /** 決まった時刻まで待つ（描画スレッド。Handler の 1ms 刻みより細かく合わせる） */
    private fun waitUntil(deadlineNs: Long) {
        while (true) {
            val left = deadlineNs - System.nanoTime()
            // 長すぎる待ちはしない（予定の計算違いに備える）
            if (left <= 0L || left > 200_000_000L) return
            LockSupport.parkNanos(left)
            if (Thread.currentThread().isInterrupted) return
        }
    }

    /** 曲情報が空になっていたら取り直す（Spectrum と同じ）。スマホで設定を変えたら読み直す */
    private val heal = object : Runnable {
        override fun run() {
            MediaTracker.heal(carContext)
            if (settingsVersion != AppSettings.version) reloadSettings()
            main.postDelayed(this, HEAL_MS)
        }
    }

    /** 最後に読んだ設定の版（スマホの設定画面で変えたら読み直す） */
    private var settingsVersion = AppSettings.version
    /** 右上のボタンが出たときに一覧を下げる量に足す分 [dp]（設定。0 / 16 / 32） */
    @Volatile private var stripExtraDp = prefs.stripExtra * 16f

    /** 表示の設定を読み直す（メインスレッド） */
    private fun reloadSettings() {
        settingsVersion = AppSettings.version
        synchronized(panelLock) {
            panel.displayMode = prefs.displayMode
            panel.showCamerasInList = prefs.camerasInList
        }
        autoSwitch = prefs.autoSwitch
        stripExtraDp = prefs.stripExtra * 16f
        frameMs = if (prefs.carFps60) FRAME_MS_60 else FRAME_MS
        panelInvalid = true
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(this@DualCarScreen)
                DualMode.attach(carContext)
                DualMode.addListener(this@DualCarScreen)
                onReady()
            }

            override fun onStart(owner: LifecycleOwner) {
                // メニュー・施設一覧から戻ったとき、設定の変更を反映する
                reloadSettings()
                // 画面が前に出ると Android Auto は右上のボタンを出す
                stripTouched()
                EngineHost.addListener(snapshotListener)
                EngineHost.latest?.let { snap = it }
            }

            override fun onResume(owner: LifecycleOwner) {
                MediaTracker.heal(carContext)
                // テンプレートは画面が前に戻ったときに Android Auto が取り直すので、ここでは作り直さない
                // （作り直すたびに右上のボタンが出直して、隠れなくなるため）
            }

            override fun onStop(owner: LifecycleOwner) {
                EngineHost.removeListener(snapshotListener)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                stopDrawing()
                DualMode.removeListener(this@DualCarScreen)
                DualMode.detach()
                try {
                    carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
                } catch (e: Exception) {
                    // ホストとの接続が切れている
                }
            }
        })
    }

    // ================================================================== テンプレート（右上のボタン）

    override fun onGetTemplate(): Template = try {
        buildTemplate()
    } catch (e: Exception) {
        // 画面を作る処理で例外が出ると、アプリごと落とされる（CarTemplates の決まり 5）。最小限のボタンで出し直す
        Log.w(TAG, "template failed: ${e.message}")
        CrashLog.note("車の画面: 右上のボタンを作る処理", e)
        NavigationTemplate.Builder()
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle("メニュー")
                            .setOnClickListener(CarTemplates.click { screenManager.push(CarMenuScreen(carContext)) })
                            .build()
                    )
                    .build()
            )
            .build()
    }

    /** いまの状態での右上のボタンの組み合わせ */
    private fun stripKind(): StripKind = when {
        !touchSeen -> if (DualMode.barVisible) StripKind.FULL_HIGHWAY else StripKind.FULL_MUSIC
        DualMode.highwayActive -> StripKind.TOUCH_HIGHWAY
        else -> StripKind.TOUCH_MUSIC
    }

    private fun buildTemplate(): Template {
        // テンプレートを渡すと Android Auto は右上のボタンを出し直す（隠れるまでの時間を数え直す）
        stripTouched()
        val strip = ActionStrip.Builder()
        val kind = stripKind()
        templateStrip = kind
        when (kind) {
            // タッチできる車（v2.18）: 1 つだけ・アイコンだけ（画面を隠さないように）。
            // NavigationTemplate は右上のボタンが 1 つは要るので、その画面で役に立つものを置く
            StripKind.TOUCH_HIGHWAY -> strip.addAction(iconAction(R.drawable.ic_car_camera) { registerCamera() })
            StripKind.TOUCH_MUSIC -> strip.addAction(iconAction(R.drawable.ic_car_spectrum) {
                screenManager.push(CarSpectrumScreen(carContext))
            })
            // タッチ操作が届いていない車: 画面の切替・メニューも右上に（下部バーを押せないため）
            StripKind.FULL_MUSIC -> {
                strip.addAction(action("ハイウェイ表示", R.drawable.ic_car_highway) {
                    val d = DualMode.highwayDefault()
                    DualMode.select(if (d == DualLayout.MUSIC) DualLayout.SPLIT else d)
                })
                strip.addAction(action("メニュー", R.drawable.ic_car_menu) { screenManager.push(CarMenuScreen(carContext)) })
            }
            StripKind.FULL_HIGHWAY -> {
                strip.addAction(action("画面切替", R.drawable.ic_car_split) { DualMode.cycle() })
                strip.addAction(action("施設", R.drawable.ic_car_list) { screenManager.push(FacilityListScreen(carContext)) })
                strip.addAction(action("オービス登録", R.drawable.ic_car_camera) { registerCamera() })
                strip.addAction(action("メニュー", R.drawable.ic_car_menu) { screenManager.push(CarMenuScreen(carContext)) })
            }
        }
        return NavigationTemplate.Builder()
            .setActionStrip(strip.build())
            .setBackgroundColor(CarColor.createCustom(Color.BLACK, Color.BLACK))
            .build()
    }

    /** 文字とアイコンの両方を持つボタン（画面の幅に応じて Android Auto がどちらかを表示する） */
    private fun action(title: String, icon: Int, onClick: () -> Unit): Action =
        Action.Builder()
            .setTitle(title)
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, icon)).build())
            .setOnClickListener(actionClick(onClick))
            .build()

    /** アイコンだけのボタン（v2.18。文字のボタンより小さく、画面を隠さない） */
    private fun iconAction(icon: Int, onClick: () -> Unit): Action =
        Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, icon)).build())
            .setOnClickListener(actionClick(onClick))
            .build()

    private fun actionClick(onClick: () -> Unit) = CarTemplates.click {
        // ボタンを押した後も、操作が無くなってから約 10 秒はボタンが出ている
        stripTouched()
        // 車の画面の操作のたびに、スマホ側の常駐（GPS・通信）が動いているか確かめる
        CarRuntime.ensure(carContext)
        onClick()
    }

    /** 現在地をオービスとして登録する（右上のボタン） */
    private fun registerCamera() {
        EngineHost.registerCameraHere { ok ->
            CarToast.makeText(carContext, if (ok) "現在地をオービスとして登録しました" else "現在地が未取得です", CarToast.LENGTH_SHORT).show()
        }
    }

    /**
     * 画面にタッチ操作が届いた（v2.18）: この車はタッチできる。右上のボタンを 1 つだけにする
     * （初めて届いたときだけテンプレートを作り直す。次の接続からは最初から 1 つ）
     */
    private fun noteTouch() {
        if (touchSeen) return
        touchSeen = true
        try {
            prefs.carTouch = true
        } catch (e: Exception) {
            // 保存できなくても、この接続の間は 1 つにする
        }
        if (templateStrip != null && templateStrip != stripKind()) invalidate()
    }

    /**
     * 右上のボタンが出た（または出たまま）とみなす。ここから [STRIP_HIDE_MS] 操作が無ければ、
     * Android Auto から知らせが無くても隠れたとみなし、一覧を画面いっぱいに戻す
     */
    private fun stripTouched() {
        stripShownAt = SystemClock.uptimeMillis()
        panelInvalid = true
    }

    /** 画面構成が変わった（自動切替・ボタン操作） */
    override fun onDualChanged() {
        autoSwitch = prefs.autoSwitch
        // 右上のボタンの組み合わせが変わるときだけテンプレートを作り直す
        // （作り直すと Android Auto がボタンを表示し直すため、必要なとき以外は呼ばない）
        if (templateStrip != stripKind()) invalidate()
        panelInvalid = true
        updateSpectrumUse()
        requestFrame()
    }

    // ================================================================== 描画面

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        // 画面サイズが変わったときにも呼ばれる。新しい描画面の大きさで描き直す
        val old = surface
        surface = surfaceContainer.surface
        width = surfaceContainer.width
        height = surfaceContainer.height
        val dpi = surfaceContainer.dpi
        density = if (dpi > 0) (dpi / 160f).coerceIn(0.75f, 3f) else 1f
        hostApiLevel = try {
            carContext.carAppApiLevel
        } catch (e: Exception) {
            -1
        }
        synchronized(panelLock) { panel.density = density }
        bar.density = density
        // 表示範囲は新しい描画面に合わせて改めて通知されるので、古い値は捨てる
        synchronized(visible) { visible.setEmpty() }
        panelInvalid = true
        MediaTracker.start(carContext)
        updateSpectrumUse()
        // 車の画面に描いている間は、スマホの画面の描き直しを 30fps に下げる（車の画面を優先。v2.21）
        com.example.spectrum.PlayerView.carScreenActive = true
        // ハイウェイモードの画面を描き直すスレッド（v2.21。描画スレッドを待たせないように。優先度は普通）
        if (panelThread == null) {
            val pt = HandlerThread("car-panel", Process.THREAD_PRIORITY_DEFAULT).also { it.start() }
            panelThread = pt
            panelHandler = Handler(pt.looper)
        }
        // 描画スレッドは 1 本だけ（素早い切り替えで多重に起動しないように）
        if (drawThread == null) {
            // 表示用の優先度（エンジンの計算・音声解析より先に描けるように）
            val t = HandlerThread("car-draw", Process.THREAD_PRIORITY_DISPLAY).also { it.start() }
            drawThread = t
            drawHandler = Handler(t.looper).also { it.post(frame) }
            // メニューの「スペアナの表示」の見本の絵を先に作っておく（開いたときに待たせない。v2.21）
            main.postDelayed({ CarSpectrumScreen.prewarm() }, PREWARM_DELAY_MS)
        } else {
            requestFrame()
        }
        if (old != null && old !== surface) {
            drawHandler?.post { releaseSurface(old) }
        }
        main.removeCallbacks(heal)
        main.post(heal)
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        stopDrawing()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        var stripAppeared = false
        // 描画面からはみ出す範囲が来たら（切り替え途中の古い値など）無視して全体を使う
        synchronized(visible) {
            // 上端が下がっていた（ボタン表示中）のが 0 に戻った → ボタンが隠れたことを知らせてくる車
            if (!visible.isEmpty && visible.top > 0 && visibleArea.top == 0) hostReportsStrip = true
            // 上端が下がった・下がり方が変わった → ボタンが出た（隠れるまでの時間を数え直す）。
            // 同じ値が続くだけなら数え直さない（隠れても値を戻さない車では、古い値のままになるため）
            if (visibleArea.top > 0 && (visible.isEmpty || visible.top != visibleArea.top)) stripAppeared = true
            if (visibleArea.left < 0 || visibleArea.right > width || visibleArea.width() <= 0) visible.setEmpty()
            else visible.set(visibleArea)
        }
        if (stripAppeared) stripTouched()
        panelInvalid = true
        requestFrame()
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        synchronized(visible) {
            if (stableArea.left < 0 || stableArea.right > width || stableArea.width() <= 0) stable.setEmpty()
            else stable.set(stableArea)
        }
        panelInvalid = true
        requestFrame()
    }

    // 画面を触ると Android Auto は右上のボタンを出す（隠れるまでの時間を数え直す）
    override fun onScroll(distanceX: Float, distanceY: Float) {
        stripTouched()
        requestFrame()
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        stripTouched()
        requestFrame()
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        stripTouched()
        requestFrame()
    }

    override fun onClick(x: Float, y: Float) {
        // 押したときの処理で例外が出ると、アプリごと落とされる（CarTemplates の決まり 5）
        try {
            noteTouch()
            handleClick(x, y)
        } catch (e: Exception) {
            Log.w(TAG, "click failed: ${e.message}")
            CrashLog.note("車の画面: タップの処理", e)
        }
    }

    private fun handleClick(x: Float, y: Float) {
        stripTouched()
        requestFrame()
        CarRuntime.ensure(carContext)
        val f = lastFrame ?: return
        val b = f.bar
        if (b != null && b.contains(x, y)) {
            when (bar.hitAt(x, y)) {
                ModeBarRenderer.Target.MUSIC -> DualMode.select(DualLayout.MUSIC)
                ModeBarRenderer.Target.SPLIT, ModeBarRenderer.Target.OPEN_SPLIT -> DualMode.select(DualLayout.SPLIT)
                ModeBarRenderer.Target.HIGHWAY -> DualMode.select(DualLayout.HIGHWAY)
                ModeBarRenderer.Target.PLAY_PAUSE -> NowPlaying.playPause()
                ModeBarRenderer.Target.MENU -> screenManager.push(CarMenuScreen(carContext))
                else -> {}
            }
            requestFrame()
            return
        }
        val m = f.music
        if (m != null && m.contains(x, y)) {
            music.onTap(x, y)
            requestFrame()
            return
        }
        val h = f.highway
        if (h != null && h.contains(x, y)) {
            // ハイウェイモードの画面の部品は、別のスレッドが描き直している最中のことがあるので、鍵を取ってから触る（v2.21）
            val hit = synchronized(panelLock) { panel.hitAt(x, y) } ?: return
            val fac = hit.facility
            if (fac != null) {
                screenManager.push(FacilityDetailScreen(carContext, fac))
                return
            }
            // ［︿ この先］［現在地へ］［﹀ 手前へ］: さらに先の施設・渋滞を見る / 戻る
            if (synchronized(panelLock) { panel.pageButton(hit.button) }) {
                panelInvalid = true
                requestFrame()
            }
        }
    }

    /** すぐ描き直す（タップ・画面構成の変更など。決まった時刻は、その後から数え直す） */
    private fun requestFrame() {
        val h = drawHandler ?: return
        frameRequests++
        h.removeCallbacks(frame)
        h.post(frame)
    }

    private fun stopDrawing() {
        main.removeCallbacks(heal)
        val h = drawHandler
        val t = drawThread
        drawHandler = null
        drawThread = null
        h?.removeCallbacksAndMessages(null)
        t?.quitSafely()
        try {
            t?.join(500)
        } catch (e: InterruptedException) {
            // そのまま後始末を続ける
        }
        // ハイウェイモードの画面を描き直すスレッドも止める（描きかけの記録は捨てる）
        val ph = panelHandler
        val pt = panelThread
        panelHandler = null
        panelThread = null
        ph?.removeCallbacksAndMessages(null)
        pt?.quitSafely()
        try {
            pt?.join(500)
        } catch (e: InterruptedException) {
            // そのまま後始末を続ける
        }
        com.example.spectrum.PlayerView.carScreenActive = false
        panelResult = null
        panelRecording = false
        panelFront = null
        panelBack = null
        barNode = null
        surface?.let { releaseSurface(it) }
        surface = null
        lastFrame = null
        main.removeCallbacks(releaseSpectrum)
        stopSpectrum()
    }

    private fun releaseSurface(s: Surface) {
        try {
            s.release()
        } catch (e: Exception) {
            // 解放済み
        }
    }

    // ================================================================== Spectrum の音声解析

    /** Spectrum が見えている間だけ音声を解析する */
    private fun updateSpectrumUse() {
        val showing = surface != null && DualMode.layout != DualLayout.HIGHWAY
        if (showing) {
            main.removeCallbacks(releaseSpectrum)
            if (!spectrumAcquired) {
                spectrumAcquired = true
                SpectrumEngine.listeners.add(specListener)
                SpectrumEngine.acquire(carContext.applicationContext)
            }
        } else if (spectrumAcquired) {
            main.removeCallbacks(releaseSpectrum)
            main.postDelayed(releaseSpectrum, SPECTRUM_RELEASE_DELAY_MS)
        }
    }

    private fun stopSpectrum() {
        if (!spectrumAcquired) return
        spectrumAcquired = false
        SpectrumEngine.listeners.remove(specListener)
        SpectrumEngine.release()
    }

    // ================================================================== 1 フレーム（描画スレッド）

    /**
     * @param targetNs 画面へ出す予定の時刻 [ns]（0 なら描けたらすぐ出す）
     * @return 次のフレームまでの時間 [ms]
     */
    private fun drawFrame(targetNs: Long): Double {
        val s = surface ?: return IDLE_FRAME_MS
        if (!s.isValid || width <= 0 || height <= 0) return IDLE_FRAME_MS
        val w = width.toFloat()
        val h = height.toFloat()
        // 上下は画面いっぱいに描く。左右は Android Auto の表示範囲（横に並ぶカードなど、いつも出ているもの）に合わせる
        synchronized(visible) {
            vCopy.set(visible)
            sCopy.set(stable)
        }
        val v = vCopy
        val horiz = if (!sCopy.isEmpty) sCopy else v
        val layout = DualMode.layout
        // エンジンの最新の状態（画面の開始・停止の通知に頼らない）
        val latest = EngineHost.latest
        if (latest != null && latest !== snap) {
            snap = latest
            panelStale = true
        }
        music.large = layout == DualLayout.SPLIT
        // 下部の切替バーは、v2.17 から車の画面ではいつも出す（右上のボタンが出ない車でも、メニュー・画面の切替ができるように）
        val f = DualGeometry.car(
            w, h,
            left = if (horiz.isEmpty) 0f else horiz.left.toFloat(),
            right = if (horiz.isEmpty) w else horiz.right.toFloat(),
            highwayTop = 0f,
            layout = layout, barVisible = true, density = density,
            // 下端は「安定した範囲」だけで決める（表示範囲は一時的な表示で上下するので、バーが跳ねないように）
            bottom = if (sCopy.isEmpty) h else sCopy.bottom.toFloat()
        )
        // 右上のボタン（Android Auto が描く）が出ている間だけ、その部分を空ける（ハイウェイ側の右上）。
        // ボタンは操作すると出て、約 10 秒操作が無いと隠れる。隠れても表示範囲を元に戻さない車があるので、
        // 最後の操作から STRIP_HIDE_MS たったら、知らせが無くても隠れたとみなす。
        //  - ボタンが隠れた（操作が無い・上端が 0 に戻った） → 画面いっぱい
        //  - 表示範囲の上端が下がっている = ボタン表示中 → 一覧の列をボタンの下へずらす
        //  - ボタンの位置を知らせてこない車 → ボタンが出ている間だけ、一番上の段の右側を空ける（下へはずらさない）
        val now = SystemClock.uptimeMillis()
        val stripUp = now - stripShownAt < STRIP_HIDE_MS
        val reserveW: Float
        val targetH: Float
        when {
            !stripUp -> {
                reserveW = 0f
                targetH = 0f
            }
            v.top > 0 -> {
                // ボタンの下端まで下げる: 知らせてきた表示範囲の上端・安定した範囲の上端・ボタンの大きさの見積もりの最大
                reserveW = stripWidthEstimate(w)
                targetH = max(max(v.top.toFloat(), if (sCopy.isEmpty) 0f else sCopy.top.toFloat()), stripBottomEstimate())
            }
            hostReportsStrip -> {
                reserveW = 0f
                targetH = 0f
            }
            else -> {
                reserveW = stripWidthEstimate(w)
                targetH = 0f
            }
        }
        val shiftH = animateShift(targetH, now)
        val shifting = shiftH != targetH
        if (now - areaInfoAt > 1000L) {
            updateStats(now)
            areaInfoAt = now
            areaInfo = "画面 ${w.toInt()}×${h.toInt()}・密度 ${"%.2f".format(density)}・Car App API レベル ${if (hostApiLevel > 0) hostApiLevel else "不明"}\n" +
                "表示範囲 ${if (v.isEmpty) "（知らせなし）" else "${v.left},${v.top},${v.right},${v.bottom}"}\n" +
                "安定した範囲 ${if (sCopy.isEmpty) "（知らせなし）" else "${sCopy.left},${sCopy.top},${sCopy.right},${sCopy.bottom}"}\n" +
                "ボタン ${if (stripUp) "表示中" else "隠れている"}・一覧を下げる量 ${targetH.toInt()}px（見積もり ${stripBottomEstimate().toInt()}px）\n" +
                "右上のボタン ${stripLabel(templateStrip)}（タッチ操作 ${if (touchSeen) "確認済み" else "まだ届いていない"}）\n" +
                statText
        }
        // 右上のボタンのための空き・一覧を下げる量が変わったら、ハイウェイモードの画面をその場で描き直す
        if (reserveW != panelReserveW || shiftH != panelShiftH) {
            panelReserveW = reserveW
            panelShiftH = shiftH
            panelInvalid = true
        }
        bar.splitHint = if (DualMode.highwayActive && autoSwitch) "高速道路を出ると自動で音楽に戻ります" else null
        val canvas: Canvas = try {
            s.lockHardwareCanvas()
        } catch (e: Exception) {
            try {
                s.lockCanvas(null)
            } catch (e2: Exception) {
                return IDLE_FRAME_MS
            }
        }
        // 画面構成が変わった直後のフェード: 自動の切替だけ（利用者がボタンで選んだときは、すぐ切り替える。v2.21）
        val fadeMs = if (DualMode.changedByUser) 0L else AUTO_FADE_MS
        val sinceChange = now - DualMode.changedAtMs
        try {
            canvas.drawColor(Color.BLACK)
            f.music?.let { m -> music.draw(canvas, m.l.toInt(), m.t.toInt(), m.w.toInt(), m.h.toInt()) }
            f.highway?.let { hb -> drawPanel(canvas, hb, now) }
            f.divider?.let { d -> canvas.drawRect(d.l, d.t, d.r, d.b, dividerPaint) }
            f.bar?.let { b -> drawBar(canvas, b, layout, now) }
            if (sinceChange in 0 until fadeMs) {
                val x = 1f - sinceChange.toFloat() / fadeMs
                fadePaint.color = Color.argb((x * x * AUTO_FADE_ALPHA).toInt(), 0, 0, 0)
                canvas.drawRect(0f, 0f, w, h, fadePaint)
            }
        } finally {
            // 描き終えた。決まった時刻まで待ってから画面へ出す（描くのにかかった時間によらず、出す時刻をそろえる）
            frameReadyNs = System.nanoTime()
            if (targetNs != 0L) waitUntil(targetNs)
            try {
                s.unlockCanvasAndPost(canvas)
            } catch (e: Exception) {
                // 描画面が破棄された
            }
        }
        lastFrame = f
        val fading = sinceChange in 0 until fadeMs
        return when {
            fading || shifting -> frameMs
            // ハイウェイだけ: 下のバーの曲名を流している間は 30fps、止まっている間は 10fps
            f.music == null -> if (f.bar != null && bar.marqueeMoving) FRAME_MS else IDLE_FRAME_MS
            NowPlaying.isPlaying || music.animating -> frameMs
            else -> PAUSED_FRAME_MS
        }
    }

    /** 確認用: この 1 秒ほどの描画の様子を文字にする（描画スレッド。1 秒に 1 回） */
    private fun updateStats(now: Long) {
        val span = now - statSince
        if (statSince != 0L && span > 0L) {
            val fps = statFrames * 1000.0 / span
            val avg = if (statFrames > 0) statDrawSumNs / statFrames / 1e6 else 0.0
            statText = "描画 ${"%.1f".format(fps)}fps（目標 ${(1000.0 / frameMs).toInt()}）・遅れ ${statLate} 回・描く時間 平均 ${"%.1f".format(avg)}ms／最大 ${"%.1f".format(statDrawMaxNs / 1e6)}ms\n" +
                "ハイウェイ側の描き直し: その場 ${"%.1f".format(statPanelSyncNs / 1e6)}ms・別のスレッド ${"%.1f".format(statPanelBgNs / 1e6)}ms（最後の 1 回）"
        }
        statSince = now
        statFrames = 0
        statLate = 0
        statDrawSumNs = 0L
        statDrawMaxNs = 0L
    }

    /**
     * 一覧をずらす量を目標へなめらかに近づける（ボタンが出る・隠れるときに、一覧が飛ばずに動く）。
     * @return いまずらす量 [px]
     */
    private fun animateShift(target: Float, now: Long): Float {
        val dt = (now - shiftAnimAt).coerceIn(16L, 100L)
        shiftAnimAt = now
        val diff = target - shiftShownH
        shiftShownH = if (abs(diff) < 1f) target else shiftShownH + diff * (1f - exp(-dt / SHIFT_TAU_MS).toFloat())
        return shiftShownH
    }

    private var areaInfoAt = 0L

    /** 右上のボタンの下端の見積もり [px]（設定で足す分を含む） */
    private fun stripBottomEstimate(): Float = (STRIP_BOTTOM_DP + stripExtraDp) * density

    /**
     * 右上のボタン列のおおよその幅。
     *  - タッチできる車（v2.18）: アイコンだけのボタン 1 つ（約 60dp の丸と、画面の端・すき間で約 72dp）
     *  - タッチ操作が届いていない車: Android Auto は幅の広い画面では文字、狭い画面ではアイコンで出す
     *    （ボタン 1 つ: 文字なら約 104dp、アイコンなら約 58dp。2 画面などは 4 つ、音楽だけは 2 つ）
     */
    private fun stripWidthEstimate(surfaceWidth: Float): Float {
        val k = templateStrip ?: stripKind()
        if (k == StripKind.TOUCH_MUSIC || k == StripKind.TOUCH_HIGHWAY) return 72f * density
        val wideScreen = surfaceWidth / density >= 900f
        val count = if (k == StripKind.FULL_MUSIC) 2 else 4
        return (if (wideScreen) count * 104f else count * 58f) * density + 8f * density
    }

    /** 確認用の表示名 */
    private fun stripLabel(k: StripKind?): String = when (k) {
        StripKind.TOUCH_HIGHWAY -> "オービス登録（アイコン 1 つ）"
        StripKind.TOUCH_MUSIC -> "スペアナの表示（アイコン 1 つ）"
        StripKind.FULL_HIGHWAY -> "画面切替・施設・オービス登録・メニュー"
        StripKind.FULL_MUSIC -> "ハイウェイ表示・メニュー"
        null -> "まだ作っていない"
    }

    /**
     * ハイウェイモードの画面。記録した描画（表）を再生する（Android 10 以降）。
     *  - 別のスレッドで描き直し終えた記録（裏）があれば、表と入れ替えて使う（同じ世代・同じ区画のときだけ。v2.21）
     *  - すぐ反映したいとき（操作・区画や画面の大きさの変更・記録がまだ無い）は、この場で表に描き直す
     *  - 約 1 秒ごとの描き直し（残り時間・新しい位置情報）は、フレームを出した直後に [schedulePanelRefresh] が別のスレッドへ頼む
     *
     * 2 枚の記録を交互に使うので、別のスレッドが描いている記録を、描画スレッドが同時に画面へ出すことは無い
     * （描画スレッドが描くのはいつも表。裏は、前のフレームで出し終えてから別のスレッドに渡す）
     */
    private fun drawPanel(c: Canvas, b: Box, now: Long) {
        if (Build.VERSION.SDK_INT >= 29 && c.isHardwareAccelerated) {
            takePanelResult(b)
            val node = (panelFront as? RenderNode) ?: RenderNode("highway-panel").also { panelFront = it }
            if (panelInvalid || !panelBoxMatches(b) || !node.hasDisplayList()) {
                // 別のスレッドで描き直している最中の記録は、古い世代として使わない
                panelGen++
                val sw = width
                val sh = height
                val t0 = System.nanoTime()
                recordPanel(node, b.l, b.t, b.r, b.b, sw, sh, snap, panelReserveW, panelShiftH)
                statPanelSyncNs = System.nanoTime() - t0
                panelInvalid = false
                panelStale = false
                panelDrawnAt = now
                panelBoxL = b.l
                panelBoxT = b.t
                panelBoxR = b.r
                panelBoxB = b.b
                panelSurfaceW = sw
                panelSurfaceH = sh
            }
            c.drawRenderNode(node)
        } else {
            panelInvalid = false
            panelStale = false
            synchronized(panelLock) {
                panel.headerRightReserve = panelReserveW
                panel.topRightReserveH = panelShiftH
                panel.draw(c, b.l, b.t, b.r, b.b, snap, System.currentTimeMillis())
            }
        }
    }

    /** 別のスレッドで描き直し終えた記録を受け取る（描画スレッド。Android 10 以降） */
    private fun takePanelResult(b: Box) {
        val r = panelResult ?: return
        panelResult = null
        panelRecording = false
        if (r.gen == panelGen && panelBoxMatches(b)) {
            // 出し終えた表を裏に回し、描き直した記録を表にする
            panelBack = panelFront
            panelFront = r.node
        } else {
            // 描き直している間に、その場で描き直した・区画が変わった: 古いので使わず、次の描き直しに使う
            panelBack = r.node
        }
    }

    private fun panelBoxMatches(b: Box): Boolean =
        b.l == panelBoxL && b.t == panelBoxT && b.r == panelBoxR && b.b == panelBoxB && width == panelSurfaceW && height == panelSurfaceH

    /**
     * ハイウェイモードの画面を記録に描く（描画スレッド・描き直し用のスレッドのどちらからも呼ぶ。Android 10 以降）。
     * 部品（PanelRenderer）は 1 つなので、鍵を取って 1 つのスレッドずつ使う
     */
    private fun recordPanel(
        node: RenderNode, l: Float, t: Float, r: Float, b: Float, sw: Int, sh: Int,
        s: HighwaySnapshot?, reserveW: Float, shiftH: Float
    ) {
        if (Build.VERSION.SDK_INT < 29) return
        synchronized(panelLock) {
            panel.headerRightReserve = reserveW
            panel.topRightReserveH = shiftH
            node.setPosition(0, 0, sw, sh)
            val rc = node.beginRecording(sw, sh)
            try {
                panel.draw(rc, l, t, r, b, s, System.currentTimeMillis())
            } finally {
                node.endRecording()
            }
        }
    }

    /**
     * ハイウェイモードの画面の記録が古くなっていたら（新しい位置情報・約 1 秒たった）、別のスレッドで裏の記録に描き直してもらう
     * （描画スレッド。フレームを出した直後に呼ぶ。描き直しの最中は重ねて頼まない。v2.21）。
     * 記録がまだ無い・区画が変わったときは [drawPanel] がその場で描くので、ここでは何もしない
     */
    private fun schedulePanelRefresh() {
        if (Build.VERSION.SDK_INT < 29) return
        if (panelRecording) return
        val front = panelFront as? RenderNode ?: return
        val b = lastFrame?.highway ?: return
        if (!front.hasDisplayList() || !panelBoxMatches(b)) return
        val now = SystemClock.uptimeMillis()
        if (!panelStale && now - panelDrawnAt < PANEL_REFRESH_MS) return
        panelStale = false
        panelDrawnAt = now
        val s = snap
        val sw = panelSurfaceW
        val sh = panelSurfaceH
        val l = b.l
        val t = b.t
        val r = b.r
        val bb = b.b
        val reserveW = panelReserveW
        val shiftH = panelShiftH
        val ph = panelHandler
        if (ph == null) {
            // 描き直し用のスレッドが無い（止めている途中）: これまでどおり、ここで表に描き直す
            recordPanel(front, l, t, r, bb, sw, sh, s, reserveW, shiftH)
            return
        }
        val node = (panelBack as? RenderNode) ?: RenderNode("highway-panel").also { panelBack = it }
        val gen = panelGen
        panelRecording = true
        val posted = ph.post {
            val t0 = System.nanoTime()
            panelResult = try {
                recordPanel(node, l, t, r, bb, sw, sh, s, reserveW, shiftH)
                val cost = System.nanoTime() - t0
                statPanelBgNs = cost
                PanelResult(node, gen, cost)
            } catch (e: Exception) {
                Log.w(TAG, "panel record failed: ${e.message}")
                CrashLog.note("車の画面: ハイウェイ側の描き直し", e)
                // 使えない記録として返す（描画スレッドが裏に戻す）
                PanelResult(node, -1, 0L)
            }
        }
        if (!posted) panelRecording = false
    }

    /**
     * 下部の切替バー（v2.21）。記録（RenderNode）に描いておき、中身（区画・画面構成・エンジンの状態・案内の文・曲・再生中か）が
     * 変わったとき、1 秒ごと（到着予定の時刻など）、曲名を流している間（毎フレーム）だけ描き直す。
     * v2.20 までは毎フレーム描いていて、文字の幅を測る・文字列を作る処理が 1 秒に 30 回あった
     */
    private fun drawBar(c: Canvas, b: Box, layout: DualLayout, now: Long) {
        if (Build.VERSION.SDK_INT < 29 || !c.isHardwareAccelerated) {
            bar.draw(c, b.l, b.t, b.r, b.b, layout, snap)
            return
        }
        val node = (barNode as? RenderNode) ?: RenderNode("mode-bar").also { barNode = it }
        val s = snap
        val hint = bar.splitHint
        val title = NowPlaying.title
        val artist = NowPlaying.artist
        val playing = NowPlaying.isPlaying
        val art = NowPlaying.art
        val sw = width
        val sh = height
        val changed = !node.hasDisplayList() || now >= barNextAt ||
            b.l != barL || b.t != barT || b.r != barR || b.b != barB || sw != barSurfaceW || sh != barSurfaceH ||
            layout != barLayout || s !== barSnap || hint != barHint ||
            title != barTitle || artist != barArtist || playing != barPlaying || art !== barArt
        if (changed) {
            barL = b.l
            barT = b.t
            barR = b.r
            barB = b.b
            barSurfaceW = sw
            barSurfaceH = sh
            barLayout = layout
            barSnap = s
            barHint = hint
            barTitle = title
            barArtist = artist
            barPlaying = playing
            barArt = art
            node.setPosition(0, 0, sw, sh)
            val rc = node.beginRecording(sw, sh)
            try {
                bar.draw(rc, b.l, b.t, b.r, b.b, layout, s)
            } finally {
                node.endRecording()
            }
            // 曲名を流している間は毎フレーム、止まっている間は動き出す時刻か 1 秒後に描き直す
            barNextAt = if (bar.marqueeMoving) 0L else now + min(bar.marqueeWaitMs, BAR_REFRESH_MS)
        }
        c.drawRenderNode(node)
    }

    // ================================================================== Spectrum: 再生中のアプリを開く

    /**
     * Android Auto 上で、再生中の音楽アプリ(Spotify など)の画面へ切り替えを試みる（Spectrum と同じ）。
     * Android Auto は他のアプリを開く方法を公式には用意していないため、
     * 受け付けられる可能性のある指定を順に試し、すべて断られたら案内を出す。
     */
    private fun openPlayingAppOnCar() {
        val pkg = NowPlaying.controller?.packageName ?: NowPlaying.sourcePackage
        if (pkg.isEmpty()) {
            toast("再生中のアプリが分かりません")
            return
        }
        val label = appLabel(pkg)
        val comp = findMediaBrowserService(pkg)
        val candidates = mutableListOf<Intent>()
        if (comp != null) {
            // 車載 OS 向けの「音楽アプリを開く」指定
            candidates += Intent("android.car.intent.action.MEDIA_TEMPLATE")
                .putExtra("android.car.intent.extra.MEDIA_COMPONENT", comp.flattenToString())
            // 音楽アプリのサービスを直接指定
            candidates += Intent().setComponent(comp)
        }
        toast("${label}に切り替えます")
        for (i in candidates) {
            try {
                carContext.startCarApp(i)
                return
            } catch (e: Exception) {
                // 次の方法を試す
            }
        }
        toast("Android Auto の制限で切り替えできません。画面下の${label}のアイコンから開いてください")
    }

    private fun findMediaBrowserService(pkg: String): ComponentName? {
        val intent = Intent("android.media.browse.MediaBrowserService").setPackage(pkg)
        val info = try {
            carContext.packageManager.queryIntentServices(intent, 0).firstOrNull()
        } catch (e: Exception) {
            null
        } ?: return null
        return ComponentName(info.serviceInfo.packageName, info.serviceInfo.name)
    }

    private fun appLabel(pkg: String): String = try {
        val pm = carContext.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }

    private fun toast(msg: String) {
        CarToast.makeText(carContext, msg, CarToast.LENGTH_LONG).show()
    }
}

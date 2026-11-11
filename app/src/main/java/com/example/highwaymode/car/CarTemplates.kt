package com.example.highwaymode.car

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.OnClickListener
import androidx.car.app.model.Template

/**
 * 車の画面（Android Auto のテンプレート）の決まりと、共通の部品（v2.15）。
 *
 * Android Auto は、運転中の操作を減らすために画面の流れを制限していて、破るとアプリが
 * 「予期せぬエラーが発生したため終了します」で落とされる（日本メーカーの車は、制限をゆるめる仕組みが使えない）。
 * このアプリの車の画面は、次の決まりを守って作る:
 *
 *  1. **画面は 5 段まで**（最初の音楽・ハイウェイの画面が 1 段目）。**5 段目は一覧（ListTemplate）にできない**
 *     （v2.14 の「照明の色を選ぶ」は、メニュー → スペアナの表示 → アナログメーター → 色の一覧 でちょうど 5 段目の一覧になり、落ちた）。
 *     このアプリは、どの流れも 4 段まで（5 段目を使うのは施設の詳細（PaneTemplate）だけ）にする。
 *  2. **同じ画面の中身を変えるのも 1 段と数えられる**。数えられないのは「更新」だけで、一覧の場合は
 *     「画面の題名・行の数・各行の題名が同じ」とき（行の説明・絵・スイッチの状態は変えてよい）。
 *     → 行の題名は変えない。状態は行の説明（addText）かスイッチ（Toggle）で見せる。
 *     行の数・題名が変わる内容に替えたいときは、最初の画面に戻してから開き直す（[reopenLater]）。
 *     最初の画面（NavigationTemplate）に戻ると、段の数え方は最初に戻る。
 *  3. **一覧・詳細の画面の右上のボタンは 2 つまで、文字を出せるのは 1 つだけ**（2 つとも文字にすると、画面を作るところで例外になる。
 *     v2.14 までの施設一覧は「SA/PA」「更新」の 2 つが文字で、開くだけで落ちていた）。
 *  4. 行・画面の題名は空にできない（[title] で必ず何か入れる）。
 *  5. 画面を作る処理・押したときの処理で例外が出ると、アプリごと落とされる。[safe]・[click] で受け止める。
 */
internal object CarTemplates {
    const val TAG = "CarTemplates"

    /** 最初の画面に戻してから、別の画面を開き直すまでの待ち時間 [ms]（Android Auto が最初の画面を受け取るのを待つ） */
    const val REOPEN_DELAY_MS = 450L

    /** 画面を作る途中で例外が出ても、アプリを落とさず案内の画面にする */
    inline fun safe(title: String, build: () -> Template): Template = try {
        build()
    } catch (e: Exception) {
        Log.w(TAG, "template failed: ${e.message}")
        error(title)
    }

    fun error(title: String): Template =
        @Suppress("DEPRECATION")
        MessageTemplate.Builder("この画面を表示できませんでした。前の画面に戻ってください。")
            .setTitle(title(title))
            .setHeaderAction(Action.BACK)
            .build()

    /** 押したときの処理で例外が出ても、アプリを落とさない */
    inline fun click(crossinline block: () -> Unit): OnClickListener = OnClickListener {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "click failed: ${e.message}")
        }
    }

    /**
     * 最初の画面（音楽・ハイウェイ）に戻してから、少し待って別の画面を開く。
     * 行の数・題名が変わる内容に替えるときに使う（最初の画面に戻ると、段の数え方が最初に戻る）
     */
    fun reopenLater(screenManager: ScreenManager, make: () -> Screen) {
        try {
            screenManager.popToRoot()
        } catch (e: Exception) {
            Log.w(TAG, "popToRoot failed: ${e.message}")
            return
        }
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                screenManager.push(make())
            } catch (e: Exception) {
                // 接続が切れた（開き直さない）
                Log.w(TAG, "reopen failed: ${e.message}")
            }
        }, REOPEN_DELAY_MS)
    }

    /** 行・画面の題名（空にはできないので、空なら代わりの文字） */
    fun title(s: String?, fallback: String = "（名称なし）"): String = if (s.isNullOrBlank()) fallback else s
}

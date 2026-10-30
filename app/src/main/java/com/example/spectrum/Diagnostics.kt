package com.example.spectrum

import java.util.concurrent.ConcurrentHashMap

/** 診断画面に表示するための記録 */
object Diagnostics {
    data class Entry(
        val pkg: String,
        val label: String,
        val title: String,
        val text: String,
        val hasSession: Boolean,
        val time: Long
    )

    /** パッケージごとの最新の通知 */
    val notifications = ConcurrentHashMap<String, Entry>()

    /** 見つかったメディアセッション */
    @Volatile var sessions: List<String> = emptyList()

    /** 最後に受信した曲変更ブロードキャスト */
    @Volatile var lastBroadcast = ""
}

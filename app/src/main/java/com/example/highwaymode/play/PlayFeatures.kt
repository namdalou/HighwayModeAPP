package com.example.highwaymode.play

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import com.example.highwaymode.core.engine.EngineStatus
import com.example.highwaymode.data.LocalState
import com.example.highwaymode.data.Prefs
import com.example.highwaymode.engine.EngineHost
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.android.play.core.review.ReviewManagerFactory
import java.lang.ref.WeakReference

/**
 * Google Play のアプリ内アップデート・アプリ内レビュー（v2.20）。
 *
 *  - アプリ内アップデート: Google Play に新しい版が出てから [UPDATE_STALE_DAYS] 日たっても更新されていなければ、
 *    バックグラウンドでのダウンロード（FLEXIBLE）を勧める（1 日 1 回まで）。ダウンロードが終わったら、再起動して更新するかを聞く
 *  - アプリ内レビュー: 高速道路を [REVIEW_MIN_TRIPS] 回以上走り、使い始めて [REVIEW_MIN_DAYS] 日以上たったら、
 *    Google Play の評価の画面を 1 回だけ出す（次は [REVIEW_INTERVAL_DAYS] 日後。実際に出すかどうかは Google Play が決める）
 *
 * どちらも Google Play から入れたときだけ動き、それ以外（APK など）では黙って何もしない。
 * 運転中に出さないよう、スマホの画面で開いていて、ほかの案内が出ていない（画面にフォーカスがある）、
 * 車につながっていない、高速道路を走っていない、止まっている（10km/h 未満）ときだけ出す。
 */
object PlayFeatures {

    private const val REQ_UPDATE = 4820
    private const val DAY_MS = 86_400_000L
    private const val UPDATE_STALE_DAYS = 2
    private const val REVIEW_MIN_TRIPS = 3
    private const val REVIEW_MIN_DAYS = 3
    private const val REVIEW_INTERVAL_DAYS = 120

    private val main = Handler(Looper.getMainLooper())
    private var updateManager: AppUpdateManager? = null
    private var installListener: InstallStateUpdatedListener? = null

    /** スマホの画面が前に出たとき（MainActivity.onStart） */
    fun onStart(a: Activity, prefs: Prefs) {
        if (prefs.firstUseMs == 0L) prefs.firstUseMs = System.currentTimeMillis()
        val ref = WeakReference(a)
        // 起動直後のほかの案内（権限の確認・地図データのお知らせなど）が先に出るので、少し待ってから
        main.postDelayed({ ref.get()?.let { checkUpdate(it) } }, 4000)
        main.postDelayed({ ref.get()?.let { maybeAskReview(it, prefs) } }, 7000)
    }

    /** スマホの画面が隠れたとき（MainActivity.onStop） */
    fun onStop() {
        main.removeCallbacksAndMessages(null)
        val m = updateManager
        val l = installListener
        if (m != null && l != null) {
            try {
                m.unregisterListener(l)
            } catch (_: Throwable) {
            }
        }
        installListener = null
    }

    /** 運転中ではなく、いま案内を出してよい */
    private fun safeToAsk(a: Activity): Boolean {
        if (a.isFinishing || a.isDestroyed || !a.hasWindowFocus()) return false
        if (EngineHost.carConnected) return false
        val s = EngineHost.latest ?: return true
        if (s.status == EngineStatus.ON_HIGHWAY) return false
        return (s.speedKmh ?: 0.0) < 10.0
    }

    // ================================================================== アプリ内アップデート

    private fun checkUpdate(a: Activity) {
        val m = updateManager ?: try {
            AppUpdateManagerFactory.create(a.applicationContext)
        } catch (_: Throwable) {
            return
        }.also { updateManager = it }
        if (installListener == null) {
            val ref = WeakReference(a)
            val l = InstallStateUpdatedListener { state ->
                if (state.installStatus() == InstallStatus.DOWNLOADED) ref.get()?.let { askRestart(it, m) }
            }
            installListener = l
            try {
                m.registerListener(l)
            } catch (_: Throwable) {
            }
        }
        try {
            m.appUpdateInfo.addOnSuccessListener { info ->
                // ダウンロードが終わっている → 再起動して更新するかを聞く
                if (info.installStatus() == InstallStatus.DOWNLOADED) {
                    askRestart(a, m)
                    return@addOnSuccessListener
                }
                if (info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE) return@addOnSuccessListener
                if (!info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) return@addOnSuccessListener
                if ((info.clientVersionStalenessDays() ?: 0) < UPDATE_STALE_DAYS) return@addOnSuccessListener
                val st = LocalState(a)
                if (System.currentTimeMillis() - st.updateAskedMs < DAY_MS) return@addOnSuccessListener
                if (!safeToAsk(a)) return@addOnSuccessListener
                st.updateAskedMs = System.currentTimeMillis()
                try {
                    m.startUpdateFlowForResult(info, a, AppUpdateOptions.defaultOptions(AppUpdateType.FLEXIBLE), REQ_UPDATE)
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
            // Google Play 以外から入れた・Google Play が無い端末
        }
    }

    private fun askRestart(a: Activity, m: AppUpdateManager) {
        if (!safeToAsk(a)) return
        AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("アップデートの準備ができました")
            .setMessage("新しい版をダウンロードしました。アプリを再起動して更新しますか？（数秒で終わります。設定はそのまま残ります）")
            .setPositiveButton("再起動して更新") { _, _ ->
                try {
                    m.completeUpdate()
                } catch (_: Throwable) {
                }
            }
            .setNegativeButton("あとで", null)
            .show()
    }

    // ================================================================== アプリ内レビュー

    private fun maybeAskReview(a: Activity, p: Prefs) {
        val now = System.currentTimeMillis()
        if (p.highwayTrips < REVIEW_MIN_TRIPS) return
        if (now - p.firstUseMs < REVIEW_MIN_DAYS * DAY_MS) return
        if (p.reviewAskedMs != 0L && now - p.reviewAskedMs < REVIEW_INTERVAL_DAYS * DAY_MS) return
        if (!safeToAsk(a)) return
        p.reviewAskedMs = now
        try {
            val rm = ReviewManagerFactory.create(a.applicationContext)
            rm.requestReviewFlow().addOnCompleteListener { t ->
                if (t.isSuccessful && !a.isFinishing && !a.isDestroyed) {
                    try {
                        rm.launchReviewFlow(a, t.result)
                    } catch (_: Throwable) {
                    }
                }
            }
        } catch (_: Throwable) {
            // Google Play 以外から入れた・Google Play が無い端末
        }
    }
}

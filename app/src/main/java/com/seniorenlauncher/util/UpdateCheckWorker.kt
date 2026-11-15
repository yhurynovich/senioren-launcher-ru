package com.seniorenlauncher.util

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.seniorenlauncher.LauncherApp
import com.seniorenlauncher.MainActivity
import com.seniorenlauncher.R
import com.seniorenlauncher.data.api.GitHubRelease
import java.util.concurrent.TimeUnit

/**
 * Checks GitHub for a newer release once a day (and once when the launcher starts) and shows a
 * notification. Nothing is downloaded until the notification is tapped; MainActivity then
 * downloads and installs it (see NAVIGATE_TO = [MainActivity.NAV_INSTALL_UPDATE]).
 */
class UpdateCheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val release = UpdateManager(applicationContext).checkForUpdates() ?: return Result.success()
        notifyAvailable(applicationContext, release)
        return Result.success()
    }

    companion object {
        private const val TAG = "UpdateCheckWorker"
        private const val PERIODIC = "update_check_daily"
        private const val ONCE = "update_check_now"
        const val NOTIF_ID = 47100

        /** Daily background check plus one check right away. */
        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            wm.enqueueUniquePeriodicWork(
                PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS).setConstraints(net).build()
            )
            wm.enqueueUniqueWork(
                ONCE, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<UpdateCheckWorker>().setConstraints(net).build()
            )
        }

        fun notifyAvailable(context: Context, release: GitHubRelease) {
            try {
                val version = release.tagName.removePrefix("v")
                val intent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("NAVIGATE_TO", MainActivity.NAV_INSTALL_UPDATE)
                }
                val pi = PendingIntent.getActivity(context, NOTIF_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                // First few lines of the release notes as "what's new".
                val notes = release.body.lines().map { it.trim().removePrefix("-").removePrefix("*").trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("Co-Authored-By") }
                    .take(4).joinToString("\n")
                val text = i18n(R.string.upd_text)
                val n = NotificationCompat.Builder(context, LauncherApp.CH_UPDATE)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(i18n(R.string.upd_title, version))
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(if (notes.isEmpty()) text else "$text\n\n$notes"))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .build()
                context.getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, n)
            } catch (e: Exception) {
                Log.e(TAG, "notify failed", e)
            }
        }
    }
}

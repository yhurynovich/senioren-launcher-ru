package com.seniorenlauncher.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.seniorenlauncher.LauncherApp
import com.seniorenlauncher.R
import com.seniorenlauncher.data.telegram.TgCallManager
import com.seniorenlauncher.ui.telegram.TelegramCallActivity
import com.seniorenlauncher.util.i18n

/**
 * Keeps a Telegram call alive while the screen is off or another app is in front
 * (foreground service of type phoneCall + microphone), and shows the incoming-call notification
 * with a full-screen intent so the call screen opens over the lock screen.
 */
class TgCallService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        // Stop as soon as the call is over, whatever ended it, so the ongoing notification never lingers.
        scope.launch {
            TgCallManager.current.collect { c -> if (!isActive(c)) removeSelf() }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun isActive(c: com.seniorenlauncher.data.telegram.TgCall?) =
        c != null && c.phase !is com.seniorenlauncher.data.telegram.TgCallPhase.Ended

    private fun removeSelf() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val call = TgCallManager.current.value
        val name = call?.name.orEmpty()
        val n = baseBuilder(this, name)
            .setContentText(i18n(R.string.tgc_ongoing))
            .setOngoing(true)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person(name), hangUpIntent(this)))
            .build()
        var type = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && call?.cameraOn == true &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        ) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, n, type)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            stopSelf()
        }
        // A start request can arrive after the call already ended (it was queued when the call stopped).
        // startForeground had to be called anyway (Android requires it); now remove the notification again.
        if (!isActive(call)) {
            Log.i(TAG, "start after call ended: removing")
            removeSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "TgCallService"
        const val NOTIF_ID = 47001
        const val ACTION_HANG_UP = "com.seniorenlauncher.TG_CALL_HANG_UP"

        /** Call this while the call screen is in front (Android blocks microphone services started from the background). */
        fun startOngoing(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, TgCallService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "start failed", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TgCallService::class.java))
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
        }

        /** Ringing notification; the full-screen intent opens the call screen, also on the lock screen. */
        fun showIncoming(context: Context) {
            val name = TgCallManager.current.value?.name.orEmpty()
            val answer = PendingIntent.getActivity(
                context, 1, screenIntent(context).putExtra(TelegramCallActivity.EXTRA_ANSWER, true),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = baseBuilder(context, name)
                .setContentText(i18n(R.string.tgc_incoming))
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setOngoing(true)
                .setFullScreenIntent(screenPending(context), true)
                .setStyle(NotificationCompat.CallStyle.forIncomingCall(person(name), hangUpIntent(context), answer))
                .build()
            n.flags = n.flags or Notification.FLAG_INSISTENT
            try {
                context.getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, n)
            } catch (e: Exception) {
                Log.e(TAG, "notify failed", e)
            }
            // The launcher is the home app, so it may also bring the screen up directly.
            try { TgCallManager.openScreen(context) } catch (_: Exception) {}
        }

        private fun baseBuilder(context: Context, name: String) = NotificationCompat.Builder(context, LauncherApp.CH_TG_CALL)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle(name.ifBlank { i18n(R.string.tgc_title) })
            .setContentIntent(screenPending(context))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        private fun person(name: String) = Person.Builder().setName(name.ifBlank { i18n(R.string.tgc_title) }).setImportant(true).build()

        private fun screenIntent(context: Context) = Intent(context, TelegramCallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        private fun screenPending(context: Context) = PendingIntent.getActivity(
            context, 0, screenIntent(context), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        private fun hangUpIntent(context: Context) = PendingIntent.getBroadcast(
            context, 2, Intent(context, TgCallActionReceiver::class.java).setAction(ACTION_HANG_UP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

/** Decline / hang up from the notification. */
class TgCallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TgCallService.ACTION_HANG_UP) TgCallManager.hangUp()
    }
}

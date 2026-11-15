package com.seniorenlauncher.util

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.ContactsContract
import android.util.Log
import androidx.core.app.NotificationCompat
import com.seniorenlauncher.LauncherApp
import com.seniorenlauncher.MainActivity
import com.seniorenlauncher.R
import com.seniorenlauncher.service.SOSService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Caregiver side of SOS: when this app is installed on a caregiver's phone and an SMS arrives that
 * starts with the SOS marker (🆘, see [SOSService.SOS_MARKER]), it sounds a loud alarm on the alarm
 * stream (which silent mode does not mute) at full volume and shows a full-screen alert, also over
 * the lock screen, until the caregiver stops it.
 */
object CaregiverAlarm {
    private const val TAG = "CaregiverAlarm"
    private const val NOTIF_ID = 47200

    data class Alert(val number: String, val name: String?, val text: String, val mapUrl: String?)

    private val _alert = MutableStateFlow<Alert?>(null)
    val alert: StateFlow<Alert?> = _alert.asStateFlow()

    private var ringtone: Ringtone? = null
    private var savedAlarmVolume: Int? = null

    fun isSosMessage(body: String) = body.trimStart().startsWith(SOSService.SOS_MARKER.trim())

    fun trigger(context: Context, number: String, body: String) {
        val ctx = context.applicationContext
        val text = body.trimStart().removePrefix(SOSService.SOS_MARKER.trim()).trim()
        val map = Regex("https://www\\.google\\.com/maps\\?q=[-0-9.,]+").find(text)?.value
        _alert.value = Alert(number, contactName(ctx, number), text, map)
        Log.i(TAG, "SOS received from $number")
        startSound(ctx)

        val open = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("NAVIGATE_TO", MainActivity.NAV_CAREGIVER_SOS)
        }
        val pi = PendingIntent.getActivity(ctx, NOTIF_ID, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val who = _alert.value?.name ?: number
        val n = NotificationCompat.Builder(ctx, LauncherApp.CH_ALARM)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(i18n(R.string.cg_title, who))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        try { ctx.getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, n) } catch (e: Exception) { Log.w(TAG, "notify", e) }
        try { ctx.startActivity(open) } catch (e: Exception) { Log.w(TAG, "open", e) }
    }

    /** "Stop alarm": silence it and close the alert. */
    fun stop(context: Context) {
        val ctx = context.applicationContext
        stopSound(ctx)
        _alert.value = null
        ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
    }

    @Synchronized
    private fun startSound(ctx: Context) {
        if (ringtone?.isPlaying == true) return
        val am = ctx.getSystemService(AudioManager::class.java)
        try {
            if (am != null) {
                if (savedAlarmVolume == null) savedAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
                am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            }
        } catch (e: Exception) { Log.w(TAG, "volume", e) }
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(ctx, uri)?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                play()
            }
            vibrator(ctx)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 1000, 500), 0))
        } catch (e: Exception) { Log.w(TAG, "sound", e) }
    }

    @Synchronized
    private fun stopSound(ctx: Context) {
        try { ringtone?.stop() } catch (_: Exception) {}
        ringtone = null
        vibrator(ctx)?.cancel()
        savedAlarmVolume?.let { v ->
            try { ctx.getSystemService(AudioManager::class.java)?.setStreamVolume(AudioManager.STREAM_ALARM, v, 0) } catch (_: Exception) {}
        }
        savedAlarmVolume = null
    }

    private fun vibrator(ctx: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else ctx.getSystemService(Vibrator::class.java)

    private fun contactName(ctx: Context, number: String): String? = try {
        ctx.contentResolver.query(
            Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (e: Exception) { null }
}

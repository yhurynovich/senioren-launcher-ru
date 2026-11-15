package com.seniorenlauncher.util

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * During an emergency (SOS, or a call to an emergency number) the phone must be heard even when it
 * was set to silent or Do Not Disturb: the call-back from family or from the emergency service has
 * to ring. [enable] saves the current sound settings, switches to normal ringing at full volume and
 * turns Do Not Disturb off (needs "Do Not Disturb access" for the launcher). [restore] puts
 * everything back; it runs when the senior taps "I'm safe", or by itself after 30 minutes.
 */
object EmergencyAudio {
    private const val TAG = "EmergencyAudio"
    private const val PREFS = "emergency_audio"
    private const val WORK = "emergency_audio_restore"
    private val STREAMS = intArrayOf(AudioManager.STREAM_RING, AudioManager.STREAM_VOICE_CALL, AudioManager.STREAM_ALARM, AudioManager.STREAM_NOTIFICATION)

    fun enable(context: Context) {
        val ctx = context.applicationContext
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // Save the settings only once, so a second SOS doesn't overwrite them with our loud values.
        if (!prefs.getBoolean("active", false)) {
            val e = prefs.edit().putBoolean("active", true).putInt("ringer", am.ringerMode)
            nm?.let { e.putInt("filter", it.currentInterruptionFilter) }
            STREAMS.forEach { e.putInt("vol_$it", am.getStreamVolume(it)) }
            e.apply()
        }
        try {
            if (nm != null && nm.isNotificationPolicyAccessGranted) nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            else Log.w(TAG, "No Do Not Disturb access: DND can't be turned off")
        } catch (e: Exception) { Log.w(TAG, "dnd", e) }
        try { am.ringerMode = AudioManager.RINGER_MODE_NORMAL } catch (e: Exception) { Log.w(TAG, "ringer", e) }
        STREAMS.forEach { s ->
            try { am.setStreamVolume(s, am.getStreamMaxVolume(s), 0) } catch (e: Exception) { Log.w(TAG, "volume $s", e) }
        }
        Log.i(TAG, "emergency sound on")

        WorkManager.getInstance(ctx).enqueueUniqueWork(
            WORK, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<RestoreWorker>().setInitialDelay(30, TimeUnit.MINUTES).build()
        )
    }

    fun restore(context: Context) {
        val ctx = context.applicationContext
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("active", false)) return
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        STREAMS.forEach { s ->
            try { am.setStreamVolume(s, prefs.getInt("vol_$s", am.getStreamVolume(s)), 0) } catch (e: Exception) { Log.w(TAG, "volume $s", e) }
        }
        try { am.ringerMode = prefs.getInt("ringer", AudioManager.RINGER_MODE_NORMAL) } catch (e: Exception) { Log.w(TAG, "ringer", e) }
        try {
            if (nm != null && nm.isNotificationPolicyAccessGranted && prefs.contains("filter")) {
                nm.setInterruptionFilter(prefs.getInt("filter", NotificationManager.INTERRUPTION_FILTER_ALL))
            }
        } catch (e: Exception) { Log.w(TAG, "dnd", e) }
        prefs.edit().clear().apply()
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK)
        Log.i(TAG, "sound settings restored")
    }

    class RestoreWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
        override fun doWork(): Result { restore(applicationContext); return Result.success() }
    }
}

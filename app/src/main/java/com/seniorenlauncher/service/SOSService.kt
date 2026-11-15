package com.seniorenlauncher.service
import com.seniorenlauncher.R
import com.seniorenlauncher.util.i18n

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Task
import com.seniorenlauncher.LauncherApp
import com.seniorenlauncher.data.model.QuickContact
import android.app.Activity
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.seniorenlauncher.MainActivity
import com.seniorenlauncher.data.telegram.TelegramClient
import com.seniorenlauncher.data.telegram.TgCallManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/** Where the last SOS stands, for the UI. */
sealed interface SosStatus {
    data object Idle : SosStatus
    data object Sending : SosStatus
    data class Sent(val sms: Int, val telegram: Int, val called: Boolean) : SosStatus
    data class Failed(val reason: SosFailure) : SosStatus
}

enum class SosFailure { NoContacts, NoSimNoTelegram, SendFailed }

class SOSService : Service() {
    companion object {
        private val _status = MutableStateFlow<SosStatus>(SosStatus.Idle)
        val status: StateFlow<SosStatus> = _status.asStateFlow()

        /** Close the result screen. */
        fun clearStatus() { _status.value = SosStatus.Idle }

        /** Put at the start of every SOS message so a caregiver's phone with this app can recognise it. */
        const val SOS_MARKER = "\uD83C\uDD98 "

        /** A call this long means someone really talked with the senior. */
        private const val ANSWERED_SECONDS = 25L

        @Volatile private var stopCalls = false

        /** "I'm safe": stop calling the SOS contacts. */
        fun stopCalling() { stopCalls = true }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val tag = "SOSService"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, LauncherApp.CH_SOS)
            .setContentTitle(i18n(R.string.sos_alarm_actief))
            .setContentText(i18n(R.string.uw_locatie_is_verzonden_en))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                1001, 
                notification, 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(1001, notification)
        }

        scope.launch {
            try {
                processSOS()
            } catch (e: Exception) {
                Log.e(tag, "Fout bij uitvoeren SOS", e)
            } finally {
                // We stoppen de service niet direct, zodat de intercom-modus actief kan blijven
                delay(10000)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private suspend fun processSOS() {
        val context = applicationContext
        _status.value = SosStatus.Sending
        // Silent mode / Do Not Disturb must not hide the call-back.
        com.seniorenlauncher.util.EmergencyAudio.enable(context)
        val dao = LauncherApp.instance.database.contactDao()
        val sosContacts = dao.getSosContacts().first()

        if (sosContacts.isEmpty()) {
            Log.w(tag, "Geen SOS contacten gevonden")
            reportFailure(SosFailure.NoContacts)
            return
        }

        // 1. Locatie ophalen
        val location = try {
            getLocation()
        } catch (e: Exception) {
            Log.e(tag, "Fout bij ophalen locatie", e)
            null
        }

        val mapsUrl = location?.let { "https://www.google.com/maps?q=${it.latitude},${it.longitude}" }
        val message = SOS_MARKER + if (mapsUrl != null) {
            i18n(R.string.sionro_noodgeval_ik_heb_direct, mapsUrl)
        } else {
            i18n(R.string.sionro_noodgeval_ik_heb_direct_2)
        }

        // 2. SMS to every SOS contact when there is a SIM; wait for each send result.
        val hasSim = com.seniorenlauncher.util.SimUtil.hasUsableSim(this)
        val smsOk = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        if (hasSim) {
            coroutineScope {
                sosContacts.map { contact ->
                    async {
                        if (sendSmsConfirmed(contact.phoneNumber, message)) {
                            smsOk += contact.phoneNumber
                            Log.d(tag, "SOS SMS verstuurd naar ${contact.name}")
                        } else Log.w(tag, "SOS SMS mislukt naar ${contact.name}")
                    }
                }.awaitAll()
            }
        } else Log.w(tag, "Geen SIM-kaart: SOS via Telegram")

        // 3. Telegram for every contact the SMS did not reach (all of them without a SIM).
        var tgOk = 0
        val tgTargets = mutableListOf<Pair<Long, String>>()
        val needTelegram = sosContacts.filter { it.phoneNumber !in smsOk }
        if (needTelegram.isNotEmpty() || !hasSim) {
            if (TelegramClient.awaitReady(context)) {
                for (contact in sosContacts) {
                    val chatId = TelegramClient.chatForPhone(contact.phoneNumber) ?: continue
                    tgTargets += chatId to contact.name
                    if (contact in needTelegram && TelegramClient.sendTextConfirmed(chatId, message)) {
                        tgOk++
                        Log.d(tag, "SOS Telegram verstuurd naar ${contact.name}")
                    }
                }
            } else Log.w(tag, "Telegram niet beschikbaar voor SOS")
        }

        // 4. Calls: phone calls with a SIM, otherwise Telegram calls (on speaker).
        val canCall = if (hasSim) ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
            else tgTargets.isNotEmpty()
        val sent = smsOk.size + tgOk
        Log.i(tag, "SOS result: sim=$hasSim sms=${smsOk.size} telegram=$tgOk canCall=$canCall")
        if (sent == 0 && !canCall) {
            reportFailure(if (hasSim) SosFailure.SendFailed else SosFailure.NoSimNoTelegram)
            return
        }
        _status.value = SosStatus.Sent(sms = smsOk.size, telegram = tgOk, called = canCall)
        updateNotification(i18n(R.string.sos_alarm_actief), i18n(R.string.sos_sent_summary, smsOk.size, tgOk))
        if (canCall) callUntilAnswered(sosContacts, tgTargets, hasSim)
    }

    /**
     * Calls the SOS contacts in turn, up to three rounds, until someone talks with the senior for a
     * while (or the senior taps "I'm safe"). Calling the same person again within 15 minutes also
     * rings through their silent mode on most phones ("repeat callers").
     */
    private suspend fun callUntilAnswered(contacts: List<QuickContact>, tg: List<Pair<Long, String>>, hasSim: Boolean) {
        stopCalls = false
        val count = if (hasSim) contacts.size else tg.size
        repeat(3) { round ->
            for (i in 0 until count) {
                if (stopCalls) return
                val talked = if (hasSim) phoneCallAndWait(contacts[i].phoneNumber) else tgCallAndWait(tg[i])
                Log.i(tag, "SOS call round ${round + 1}, contact ${i + 1}: talked ${talked}s")
                if (talked >= ANSWERED_SECONDS || stopCalls) return
                delay(3000)
            }
        }
    }

    /** Places a phone call and waits until it ends. Returns how long the other side was on the line (seconds). */
    private suspend fun phoneCallAndWait(number: String): Long {
        SeniorInCallService.setForceSpeaker(true)
        if (!makeEmergencyCall(number)) return 0
        val tm = getSystemService(TelephonyManager::class.java)
        fun inCall(): Boolean = SeniorInCallService.currentCall.value != null ||
            try { @Suppress("DEPRECATION") (tm?.callState ?: TelephonyManager.CALL_STATE_IDLE) != TelephonyManager.CALL_STATE_IDLE } catch (e: Exception) { false }
        withTimeoutOrNull(15_000) { while (!inCall()) delay(300) } ?: return 0
        val start = System.currentTimeMillis()
        var activeSince = 0L
        withTimeoutOrNull(20 * 60_000L) {
            while (inCall()) {
                if (activeSince == 0L && SeniorInCallService.currentCall.value != null &&
                    SeniorInCallService.callState.value == android.telecom.Call.STATE_ACTIVE) activeSince = System.currentTimeMillis()
                delay(500)
            }
        }
        val end = System.currentTimeMillis()
        // Without the in-call service (launcher not the default phone app) we only know how long the
        // line was busy, ringing included: count roughly 30 s of ringing.
        return if (activeSince > 0) (end - activeSince) / 1000 else ((end - start) / 1000 - 30).coerceAtLeast(0)
    }

    /** Places a Telegram call and waits until it ends. Returns the talk time in seconds. */
    private suspend fun tgCallAndWait(target: Pair<Long, String>): Long {
        withContext(Dispatchers.Main) { TgCallManager.start(applicationContext, target.first, target.second, speaker = true) }
        var activeSince = 0L
        withTimeoutOrNull(20 * 60_000L) {
            delay(1000)
            while (true) {
                val c = TgCallManager.current.value ?: break
                val ph = c.phase
                if (ph is com.seniorenlauncher.data.telegram.TgCallPhase.Active) activeSince = ph.sinceMs
                if (ph is com.seniorenlauncher.data.telegram.TgCallPhase.Ended) break
                delay(500)
            }
        }
        return if (activeSince > 0) (System.currentTimeMillis() - activeSince) / 1000 else 0
    }

    /** Sends an SMS (in parts if long) and waits for the radio's result. True only if every part was sent. */
    @Suppress("DEPRECATION")
    private suspend fun sendSmsConfirmed(number: String, text: String): Boolean {
        val sms = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) getSystemService(SmsManager::class.java)
            else SmsManager.getDefault()) ?: return false
        val parts = try { sms.divideMessage(text) } catch (e: Exception) { arrayListOf(text) }
        val action = "com.seniorenlauncher.SOS_SMS_SENT." + System.nanoTime()
        val done = CompletableDeferred<Boolean>()
        var remaining = parts.size
        var allOk = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (resultCode != Activity.RESULT_OK) allOk = false
                remaining -= 1
                if (remaining <= 0) done.complete(allOk)
            }
        }
        ContextCompat.registerReceiver(this, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        return try {
            val sent = ArrayList(parts.indices.map { idx ->
                PendingIntent.getBroadcast(this, idx, Intent(action).setPackage(packageName),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
            })
            if (parts.size == 1) sms.sendTextMessage(number, null, text, sent[0], null)
            else sms.sendMultipartTextMessage(number, null, parts, sent, null)
            withTimeoutOrNull(30_000) { done.await() } ?: false
        } catch (e: Exception) {
            Log.e(tag, "SMS naar $number mislukt", e)
            false
        } finally {
            try { unregisterReceiver(receiver) } catch (_: Exception) {}
        }
    }

    /** Nobody was reached: tell the senior clearly, also over the lock screen. */
    private fun reportFailure(reason: SosFailure) {
        Log.e(tag, "SOS NOT SENT: $reason")
        _status.value = SosStatus.Failed(reason)
        val open = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("NAVIGATE_TO", MainActivity.NAV_SOS_FAILED)
        }
        val pi = PendingIntent.getActivity(this, 1002, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, LauncherApp.CH_SOS)
            .setContentTitle(i18n(R.string.sos_not_coming_title))
            .setContentText(i18n(R.string.sos_not_coming_notif))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(1003, n)
        try { startActivity(open) } catch (e: Exception) { Log.w(tag, "open failure screen", e) }
    }

    private fun updateNotification(title: String, text: String) {
        val n = NotificationCompat.Builder(this, LauncherApp.CH_SOS)
            .setContentTitle(title).setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setOngoing(true).setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM).build()
        getSystemService(NotificationManager::class.java)?.notify(1001, n)
    }

    private fun makeEmergencyCall(phoneNumber: String): Boolean {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) return false
        return try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phoneNumber")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(tag, "SOS call failed", e)
            false
        }
    }

    private suspend fun <T> Task<T>.awaitTask(): T = suspendCoroutine { cont ->
        addOnCompleteListener { task ->
            if (task.isSuccessful) {
                cont.resume(task.result)
            } else {
                cont.resumeWithException(task.exception ?: Exception("Task failed"))
            }
        }
    }

    private suspend fun getLocation(): Location? {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return null
        }

        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        return try {
            val lastLocation = fusedLocationClient.lastLocation.awaitTask()
            if (lastLocation != null && (System.currentTimeMillis() - lastLocation.time) < 60000) {
                lastLocation
            } else {
                fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).awaitTask()
            }
        } catch (e: Exception) {
            Log.e(tag, "Fout in getLocation", e)
            null
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

package com.seniorenlauncher.util

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.seniorenlauncher.LauncherApp
import com.seniorenlauncher.data.model.AppSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Places voice calls through the Telegram app.
 *
 * TDLib (used for Telegram chats) has no audio engine, so calls go through the official app:
 * when Telegram syncs contacts it adds a "Telegram call" row to every phone contact that has
 * Telegram. Opening that row starts the call straight away, the same way the system Contacts
 * app does it.
 */
object TelegramCalls {
    private const val TAG = "TelegramCalls"

    /** Contacts data mimetype that the Telegram app registers for "voice call". */
    private const val CALL_MIMETYPE = "vnd.android.cursor.item/vnd.org.telegram.messenger.android.call"

    /** Emergency and service numbers always go over the normal phone network. */
    fun isServiceNumber(number: String): Boolean {
        val digits = number.filter { it.isDigit() }
        return !number.trim().startsWith("+") && digits.length <= 5
    }

    /** Should this number be called through Telegram according to the user's settings? */
    fun shouldUseTelegram(context: Context, number: String, settings: AppSettings): Boolean {
        if (isServiceNumber(number)) return false
        if (settings.telegramCallsAll) return true
        return settings.telegramCallsInternational && isInternational(context, number)
    }

    /**
     * Places a call to [number] the best available way:
     * - no SIM card: Telegram only (in the launcher, else the official Telegram app); if the contact
     *   has no Telegram, a clear message says the call cannot be made;
     * - SIM card and the settings prefer Telegram for this number: Telegram in the launcher, else the
     *   official app, else a normal call;
     * - otherwise [phoneCall] (normal call). Emergency/short numbers always use [phoneCall].
     */
    fun placeCall(context: Context, number: String, phoneCall: () -> Unit) {
        if (isServiceNumber(number)) { phoneCall(); return }
        val noSim = !SimUtil.hasUsableSim(context)
        val prefer = noSim || runBlocking {
            withTimeoutOrNull(500) { LauncherApp.instance.settingsRepository.settingsFlow.first() }
        }?.let { shouldUseTelegram(context, number, it) } == true
        if (!prefer) { phoneCall(); return }

        fun fallback() {
            if (tryCall(context, number)) return
            if (noSim) {
                android.widget.Toast.makeText(context, i18n(com.seniorenlauncher.R.string.tgc_no_sim_no_tg), android.widget.Toast.LENGTH_LONG).show()
            } else phoneCall()
        }
        val tg = com.seniorenlauncher.data.telegram.TelegramClient
        if (tg.auth.value !is com.seniorenlauncher.data.telegram.TgAuth.Ready) { fallback(); return }
        tg.findChatByPhone(number) { chatId ->
            if (chatId != null && chatId > 0) {
                val name = contactName(context, number) ?: tg.displayName(chatId)
                com.seniorenlauncher.data.telegram.TgCallManager.start(context, chatId, name)
            } else fallback()
        }
    }

    private fun contactName(context: Context, number: String): String? = try {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) null
        else context.contentResolver.query(
            Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (e: Exception) { null }

    /**
     * Calls through Telegram if the settings prefer it for this number and the contact has Telegram.
     * Returns false when the caller should place a normal phone call instead.
     */
    fun callIfPreferred(context: Context, number: String): Boolean {
        val settings = runBlocking {
            withTimeoutOrNull(500) { LauncherApp.instance.settingsRepository.settingsFlow.first() }
        } ?: return false
        return shouldUseTelegram(context, number, settings) && tryCall(context, number)
    }

    /**
     * Starts a Telegram call to [number] if the contact has Telegram.
     * Returns false when that isn't possible, so the caller can fall back to a normal call.
     */
    fun tryCall(context: Context, number: String): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return false
        val dataId = findCallDataId(context, number) ?: return false
        return try {
            val uri = ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, dataId)
            context.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, CALL_MIMETYPE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (e: Exception) {
            Log.w(TAG, "Telegram call failed", e)
            false
        }
    }

    private fun findCallDataId(context: Context, number: String): Long? {
        val cr = context.contentResolver
        return try {
            val contactIds = mutableListOf<Long>()
            cr.query(
                Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                arrayOf(ContactsContract.PhoneLookup._ID), null, null, null
            )?.use { c -> while (c.moveToNext()) contactIds += c.getLong(0) }
            for (cid in contactIds) {
                cr.query(
                    ContactsContract.Data.CONTENT_URI,
                    arrayOf(ContactsContract.Data._ID),
                    "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                    arrayOf(cid.toString(), CALL_MIMETYPE), null
                )?.use { c -> if (c.moveToFirst()) return c.getLong(0) }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "contact lookup failed", e)
            null
        }
    }

    // ---- international detection ------------------------------------------------------

    /**
     * True when the number is written with an international prefix (+, 00, 011, 810)
     * and its country code differs from the phone's own country.
     */
    fun isInternational(context: Context, number: String): Boolean {
        val own = ownCallingCode(context)
        val trimmed = number.trim()
        val digits = trimmed.filter { it.isDigit() }
        val rest = when {
            trimmed.startsWith("+") -> digits
            digits.startsWith("00") -> digits.drop(2)
            own == "1" && digits.startsWith("011") -> digits.drop(3)
            own == "7" && digits.startsWith("810") -> digits.drop(3)
            else -> return false // national format: same country
        }
        if (rest.isEmpty()) return false
        if (own == null) return true // unknown home country: treat explicit international format as foreign
        return !rest.startsWith(own)
    }

    private fun ownCallingCode(context: Context): String? {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val iso = listOfNotNull(tm?.simCountryIso, tm?.networkCountryIso, Locale.getDefault().country)
            .map { it.uppercase(Locale.ROOT) }
            .firstOrNull { it.length == 2 }
        return iso?.let { CALLING_CODES[it] }
    }

    private val CALLING_CODES: Map<String, String> = """
        US 1,CA 1,PR 1,DO 1,JM 1,TT 1,BS 1,BB 1,
        RU 7,KZ 7,
        EG 20,ZA 27,GR 30,NL 31,BE 32,FR 33,ES 34,HU 36,IT 39,RO 40,CH 41,AT 43,GB 44,DK 45,SE 46,NO 47,PL 48,DE 49,
        PE 51,MX 52,CU 53,AR 54,BR 55,CL 56,CO 57,VE 58,
        MY 60,AU 61,ID 62,PH 63,NZ 64,SG 65,TH 66,
        JP 81,KR 82,VN 84,CN 86,
        TR 90,IN 91,PK 92,AF 93,LK 94,MM 95,IR 98,
        MA 212,DZ 213,TN 216,LY 218,NG 234,ET 251,KE 254,
        PT 351,LU 352,IE 353,IS 354,AL 355,MT 356,CY 357,FI 358,BG 359,
        LT 370,LV 371,EE 372,MD 373,AM 374,BY 375,AD 376,MC 377,SM 378,UA 380,RS 381,ME 382,XK 383,HR 385,SI 386,BA 387,MK 389,
        CZ 420,SK 421,LI 423,
        IL 972,AE 971,SA 966,JO 962,LB 961,SY 963,IQ 964,KW 965,QA 974,BH 973,OM 968,YE 967,PS 970,
        MN 976,NP 977,TJ 992,TM 993,AZ 994,GE 995,KG 996,UZ 998,
        HK 852,MO 853,TW 886,BD 880,KH 855,LA 856
    """.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        .associate { it.substringBefore(' ') to it.substringAfter(' ') }
}

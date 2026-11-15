package com.seniorenlauncher.ui.screens

import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.seniorenlauncher.R
import com.seniorenlauncher.service.SOSService
import com.seniorenlauncher.service.SosFailure
import com.seniorenlauncher.util.i18n
import java.util.Locale

/**
 * Shown when an SOS reached nobody (no SMS, no Telegram message, no call). It says plainly that
 * help is not coming, and offers the local emergency number, which works even without a SIM card.
 */
@Composable
fun SosFailedScreen(reason: SosFailure, onClose: () -> Unit) {
    val context = LocalContext.current
    val emergency = emergencyNumber(context)
    val why = when (reason) {
        SosFailure.NoContacts -> i18n(R.string.sos_reason_no_contacts)
        SosFailure.NoSimNoTelegram -> i18n(R.string.sos_reason_no_network)
        SosFailure.SendFailed -> i18n(R.string.sos_reason_send_failed)
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFB91C1C))
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Spacer(Modifier.height(24.dp))
        Icon(Icons.Default.Warning, null, tint = Color.White, modifier = Modifier.size(96.dp))
        Text(
            i18n(R.string.sos_not_coming_title), color = Color.White, fontSize = 38.sp, lineHeight = 44.sp,
            fontWeight = FontWeight.Black, textAlign = TextAlign.Center
        )
        Text(
            i18n(R.string.sos_not_coming_notif), color = Color.White, fontSize = 22.sp, lineHeight = 28.sp,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
        )
        Text(why, color = Color.White, fontSize = 20.sp, lineHeight = 26.sp, textAlign = TextAlign.Center)

        Button(
            onClick = { makeDirectCall(context, emergency, allowTelegram = false) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFFB91C1C)),
            shape = RoundedCornerShape(40.dp)
        ) {
            Icon(Icons.Default.Call, null, modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(12.dp))
            Text(i18n(R.string.sos_call_emergency, emergency), fontSize = 30.sp, fontWeight = FontWeight.Black)
        }
        Text(
            i18n(R.string.sos_emergency_hint, emergency), color = Color.White, fontSize = 18.sp,
            lineHeight = 24.sp, textAlign = TextAlign.Center
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (reason != SosFailure.NoContacts) {
                Button(
                    onClick = {
                        SOSService.clearStatus()
                        context.startForegroundService(Intent(context, SOSService::class.java))
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 80.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7F1D1D), contentColor = Color.White),
                    shape = RoundedCornerShape(40.dp)
                ) { Text(i18n(R.string.sos_try_again), fontSize = 20.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center) }
            }
            Button(
                onClick = onClose,
                modifier = Modifier.weight(1f).heightIn(min = 80.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7F1D1D), contentColor = Color.White),
                shape = RoundedCornerShape(40.dp)
            ) { Text(i18n(R.string.sos_close), fontSize = 20.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** The local emergency number: 911 in North America, 112 elsewhere (112 also works on most GSM networks). */
fun emergencyNumber(context: Context): String {
    val tm = context.getSystemService(TelephonyManager::class.java)
    val iso = listOfNotNull(tm?.networkCountryIso, tm?.simCountryIso, Locale.getDefault().country)
        .map { it.uppercase(Locale.ROOT) }.firstOrNull { it.length == 2 }
    return if (iso in setOf("US", "CA", "MX", "PR")) "911" else "112"
}

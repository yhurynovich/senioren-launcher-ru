package com.seniorenlauncher.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.seniorenlauncher.R
import com.seniorenlauncher.util.CaregiverAlarm
import com.seniorenlauncher.util.i18n

/** Full-screen alert on a caregiver's phone when a senior's SOS arrives (see [CaregiverAlarm]). */
@Composable
fun CaregiverSosScreen(alert: CaregiverAlarm.Alert) {
    val context = LocalContext.current
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
            i18n(R.string.cg_title, alert.name ?: alert.number), color = Color.White, fontSize = 34.sp,
            lineHeight = 40.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center
        )
        Text(alert.text, color = Color.White, fontSize = 20.sp, lineHeight = 26.sp, textAlign = TextAlign.Center)

        AlertButton(i18n(R.string.cg_call_back), Icons.Default.Call, Color.White, Color(0xFFB91C1C)) {
            CaregiverAlarm.stop(context)
            makeDirectCall(context, alert.number)
        }
        alert.mapUrl?.let { url ->
            AlertButton(i18n(R.string.cg_open_map), Icons.Default.LocationOn, Color(0xFF7F1D1D), Color.White) {
                CaregiverAlarm.stop(context)
                try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            }
        }
        AlertButton(i18n(R.string.cg_stop), Icons.Default.NotificationsOff, Color(0xFF7F1D1D), Color.White) {
            CaregiverAlarm.stop(context)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun AlertButton(label: String, icon: ImageVector, bg: Color, fg: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp),
        colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg),
        shape = RoundedCornerShape(40.dp)
    ) {
        Icon(icon, null, modifier = Modifier.size(36.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 24.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
    }
}

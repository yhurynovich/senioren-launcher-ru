package com.seniorenlauncher.ui.screens.home
import com.seniorenlauncher.R
import com.seniorenlauncher.util.i18n

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.seniorenlauncher.ui.theme.SeniorenLauncherTheme
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun HomeTopBar(notificationCount: Int, onNotificationsClick: () -> Unit) {
    val context = LocalContext.current
    var currentTime by remember { mutableStateOf(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())) }
    var currentDate by remember { mutableStateOf(SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date())) }
    
    var batteryLevel by remember { mutableIntStateOf(100) }
    var isCharging by remember { mutableStateOf(false) }
    var isWifiConnected by remember { mutableStateOf(false) }
    // 0..1, null = onbekend
    var wifiQuality by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            val now = Date()
            currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
            currentDate = SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now)

            val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
                context.registerReceiver(null, filter)
            }
            batteryLevel = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            isWifiConnected = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            wifiQuality = if (isWifiConnected) wifiSignalQuality(context, capabilities) else null

            // Every 10 s so the clock and Wi-Fi strength stay current.
            kotlinx.coroutines.delay(10_000)
        }
    }

    val statusCapsule: @Composable () -> Unit = {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = Color(0xFF1E293B).copy(alpha = 0.6f),
            modifier = Modifier.height(44.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                BatteryGauge(level = batteryLevel, charging = isCharging)
                val q = wifiQuality
                Icon(
                    imageVector = when {
                        !isWifiConnected -> Icons.Default.SignalCellularAlt
                        q == null || q >= 0.67f -> Icons.Default.Wifi
                        q >= 0.34f -> Icons.Default.Wifi2Bar
                        else -> Icons.Default.Wifi1Bar
                    },
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (q != null) signalColor(q) else Color.White.copy(alpha = 0.6f)
                )
            }
        }
    }

    val bell: @Composable () -> Unit = {
        Surface(
            onClick = onNotificationsClick,
            modifier = Modifier.size(60.dp),
            shape = CircleShape,
            color = Color(0xFF1E293B).copy(alpha = 0.6f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Notifications,
                    contentDescription = i18n(R.string.meldingen_2),
                    modifier = Modifier.size(32.dp),
                    tint = if (notificationCount > 0) Color(0xFF60A5FA) else Color.White.copy(alpha = 0.4f)
                )
                if (notificationCount > 0) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(2.dp)
                            .size(22.dp),
                        shape = CircleShape,
                        color = Color(0xFFEF4444),
                        border = BorderStroke(1.5.dp, Color(0xFF0F172A))
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                "$notificationCount",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }
            }
        }
    }

    // Tapping the clock or date shows them in very large type (read-only; any tap closes it).
    var showBigClock by remember { mutableStateOf(false) }
    if (showBigClock) BigClockDialog(onDismiss = { showBigClock = false })
    val openBigClock = Modifier.clickable(
        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
        indication = null
    ) { showBigClock = true }

    val clock: @Composable () -> Unit = {
        Text(
            currentTime,
            maxLines = 1,
            softWrap = false,
            fontSize = 44.sp,
            lineHeight = 46.sp,
            fontWeight = FontWeight.W900,
            color = Color.White,
            letterSpacing = (-2).sp,
            modifier = openBigClock
        )
    }

    val date: @Composable () -> Unit = {
        Text(
            currentDate.uppercase(),
            maxLines = 2,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.Black,
            color = Color(0xFF60A5FA), // Blue
            letterSpacing = 1.sp,
            modifier = openBigClock
        )
    }

    // Het hele scherm schaalt mee met de tekstgrootte (Theme.kt). Bij normale grootte past
    // alles op één rij (status · klok+datum · meldingen); bij grote tekst gaat de datum
    // tussen status en meldingen en komt de klok eronder.
    // The status bar is hidden, so the header starts at the very top. Step the side items
    // clear of a camera hole in a top corner; a hole in the middle pushes the whole header down.
    val cutout = com.seniorenlauncher.ui.components.rememberTopCutout()
    val outer = 16.dp
    val gap = 6.dp
    val cutoutPadding = Modifier.padding(
        start = if (cutout.left > 0.dp) (cutout.left - outer + gap).coerceAtLeast(0.dp) else 0.dp,
        end = if (cutout.right > 0.dp) (cutout.right - outer + gap).coerceAtLeast(0.dp) else 0.dp,
        top = cutout.center
    )

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = outer, vertical = 6.dp)
            .then(cutoutPadding)
    ) {
        // oval (~80dp) + clock (~120dp) + bell (60dp) + spacing
        if (maxWidth >= 300.dp) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                statusCapsule()
                Column(Modifier.weight(1f).then(openBigClock), horizontalAlignment = Alignment.CenterHorizontally) {
                    clock()
                    date()
                }
                bell()
            }
        } else {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    statusCapsule()
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { date() }
                    bell()
                }
                clock()
            }
        }
    }
}

/**
 * Full-screen, read-only view of the time and date in very large type. Any tap closes it.
 * Font sizes follow the screen width so the time always fits on one line, also at the largest text size.
 */
@Composable
fun BigClockDialog(onDismiss: () -> Unit) {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) { now = Date(); kotlinx.coroutines.delay(1_000) }
    }
    val locale = Locale.getDefault()
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF0F172A))
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            // "HH:mm" in heavy digits is about 3 em wide.
            val timeSize = with(density) { (maxWidth / 3.1f).toSp() }
            val daySize = with(density) { (maxWidth / 9f).toSp() }
            val dateSize = with(density) { (maxWidth / 12f).toSp() }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    SimpleDateFormat("HH:mm", locale).format(now),
                    fontSize = timeSize, lineHeight = timeSize, maxLines = 1, softWrap = false,
                    fontWeight = FontWeight.W900, color = Color.White, letterSpacing = (-2).sp
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    SimpleDateFormat("EEEE", locale).format(now).replaceFirstChar { it.titlecase(locale) },
                    fontSize = daySize, lineHeight = daySize * 1.15f, fontWeight = FontWeight.Black,
                    color = Color(0xFF60A5FA), textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    SimpleDateFormat("d MMMM yyyy", locale).format(now),
                    fontSize = dateSize, lineHeight = dateSize * 1.15f, fontWeight = FontWeight.Bold,
                    color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

/**
 * Horizontal battery whose fill length shows the charge. The fill is one solid colour that
 * moves from red (empty) through yellow to green (full).
 */
@Composable
private fun BatteryGauge(level: Int, charging: Boolean) {
    val fraction = level.coerceIn(0, 100) / 100f
    Box(contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.size(width = 30.dp, height = 16.dp)) {
            val stroke = 1.5.dp.toPx()
            val capW = 2.5.dp.toPx()
            val bodyW = size.width - capW
            val r = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
            // outline
            drawRoundRect(
                color = Color.White.copy(alpha = 0.8f),
                size = androidx.compose.ui.geometry.Size(bodyW, size.height),
                cornerRadius = r,
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke)
            )
            // terminal cap
            drawRoundRect(
                color = Color.White.copy(alpha = 0.8f),
                topLeft = androidx.compose.ui.geometry.Offset(bodyW, size.height * 0.3f),
                size = androidx.compose.ui.geometry.Size(capW, size.height * 0.4f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx())
            )
            // fill
            val inset = stroke + 1.dp.toPx()
            val innerW = bodyW - inset * 2
            val fillW = (innerW * fraction).coerceAtLeast(if (level > 0) 2.dp.toPx() else 0f)
            if (fillW > 0f) {
                drawRoundRect(
                    color = signalColor(fraction),
                    topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                    size = androidx.compose.ui.geometry.Size(fillW, size.height - inset * 2),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx())
                )
            }
        }
        if (charging) {
            Icon(
                Icons.Default.Bolt,
                contentDescription = null,
                modifier = Modifier.size(14.dp).offset(x = (-1).dp),
                tint = Color.White
            )
        }
    }
}

/** Wi-Fi signal quality from 0 (unusable, about -90 dBm) to 1 (excellent, about -55 dBm), or null if unknown. */
@Suppress("DEPRECATION")
private fun wifiSignalQuality(context: Context, caps: NetworkCapabilities?): Float? {
    var rssi = Int.MIN_VALUE
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        caps?.signalStrength?.let { if (it != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED) rssi = it }
    }
    if (rssi == Int.MIN_VALUE) {
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val r = wm.connectionInfo?.rssi ?: Int.MIN_VALUE
            // -127 is what Android reports when the value is unavailable
            if (r > -127 && r < 0) rssi = r
        } catch (_: Exception) { }
    }
    if (rssi == Int.MIN_VALUE) return null
    return ((rssi + 90f) / 35f).coerceIn(0f, 1f)
}

/** Red (0) → amber → green (1), via the HSV hue so the middle stays a clean yellow instead of brown. */
private fun signalColor(quality: Float): Color =
    Color.hsv(hue = 120f * quality.coerceIn(0f, 1f), saturation = 0.85f, value = 0.95f)

@Composable
fun HomeSOSButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth(),
        shape = RoundedCornerShape(40.dp),
        color = Color(0xFFEF4444),
        shadowElevation = 16.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .padding(vertical = 8.dp)
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(Color(0xFFEF4444), Color(0xFF991B1B))
                    )
                )
                .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(40.dp)),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Default.HealthAndSafety,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = Color.White
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    i18n(R.string.sos_hulp_nodig),
                    fontSize = 22.sp,
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.W900,
                    color = Color.White,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}

@Composable
fun HomeStatusCard(
    pendingMedsCount: Int,
    onMedsClick: () -> Unit
) {
    Surface(
        onClick = onMedsClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        shape = RoundedCornerShape(36.dp),
        color = Color(0xFF10B981).copy(alpha = 0.15f),
        border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.3f))
    ) {
        Row(
            Modifier.padding(28.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(64.dp).background(Color(0xFF10B981).copy(alpha = 0.2f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("💊", fontSize = 36.sp)
            }
            Spacer(Modifier.width(24.dp))
            Column {
                Text(
                    i18n(R.string.herinnering_2),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.5.sp,
                    color = Color(0xFF10B981)
                )
                Text(
                    i18n(R.string.nog_medicijnen, pendingMedsCount), 
                    fontSize = 22.sp, 
                    fontWeight = FontWeight.Black,
                    color = Color.White
                )
            }
        }
    }
}

fun getHomeWeatherEmoji(iconUrl: String): String {
    return when {
        iconUrl.contains("01d") -> "☀️"
        iconUrl.contains("01n") -> "🌙"
        iconUrl.contains("02d") || iconUrl.contains("02n") -> "⛅"
        iconUrl.contains("03") || iconUrl.contains("04") -> "☁️"
        iconUrl.contains("09") || iconUrl.contains("10") -> "🌧️"
        iconUrl.contains("11") -> "⛈️"
        iconUrl.contains("13") -> "❄️"
        iconUrl.contains("50") -> "🌫️"
        else -> "☁️"
    }
}

package com.seniorenlauncher.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.telephony.TelephonyManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.seniorenlauncher.R
import com.seniorenlauncher.data.telegram.TelegramClient
import com.seniorenlauncher.data.telegram.TgAuth
import com.seniorenlauncher.util.i18n

/** One-step-per-screen Telegram sign-in with big buttons. Calls [onDone] once signed in. */
@Composable
fun TelegramSetupScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val auth by TelegramClient.auth.collectAsState()
    val error by TelegramClient.lastError.collectAsState()

    LaunchedEffect(Unit) { TelegramClient.start(context) }
    LaunchedEffect(auth) { if (auth is TgAuth.Ready) onDone() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        when (val a = auth) {
            is TgAuth.NotConfigured -> Info(i18n(R.string.tg_not_configured))
            is TgAuth.Loading, is TgAuth.Ready -> {
                CircularProgressIndicator(color = Color.White)
                Info(i18n(R.string.tg_connecting))
            }
            is TgAuth.Failed -> Info(i18n(R.string.tg_error, a.message))
            is TgAuth.NeedsRegistration -> Info(i18n(R.string.tg_registration))
            is TgAuth.WaitPhone -> {
                var phone by remember { mutableStateOf(guessOwnNumber(context)) }
                Info(i18n(R.string.tg_intro))
                Text(i18n(R.string.tg_phone_question), fontSize = 22.sp, fontWeight = FontWeight.Black, color = Color.White, textAlign = TextAlign.Center)
                Field(phone, { phone = it }, i18n(R.string.tg_phone_hint), KeyboardType.Phone)
                ErrorLine(error)
                BigAction(i18n(R.string.tg_send_code), enabled = phone.count { it.isDigit() } >= 6) { TelegramClient.submitPhone(phone) }
                BigAction(i18n(R.string.tg_qr_button), secondary = true) { TelegramClient.requestQrLogin() }
            }
            is TgAuth.WaitCode -> {
                var code by remember { mutableStateOf("") }
                Info(if (a.viaSms) i18n(R.string.tg_waiting_code) else i18n(R.string.tg_code_in_app))
                Text(i18n(R.string.tg_enter_code), fontSize = 22.sp, fontWeight = FontWeight.Black, color = Color.White)
                Field(code, { code = it.filter { c -> c.isDigit() }.take(6) }, "12345", KeyboardType.NumberPassword, big = true)
                ErrorLine(error)
                BigAction(i18n(R.string.tg_confirm), enabled = code.length >= 5) { TelegramClient.submitCode(code) }
            }
            is TgAuth.WaitPassword -> {
                var pw by remember { mutableStateOf("") }
                Info(i18n(R.string.tg_password_hint))
                Field(pw, { pw = it }, i18n(R.string.tg_password_title), KeyboardType.Password, password = true)
                ErrorLine(error)
                BigAction(i18n(R.string.tg_confirm), enabled = pw.isNotEmpty()) { TelegramClient.submitPassword(pw) }
            }
            is TgAuth.WaitQr -> {
                Text(i18n(R.string.tg_qr_title), fontSize = 22.sp, fontWeight = FontWeight.Black, color = Color.White)
                Info(i18n(R.string.tg_qr_hint))
                val bmp = remember(a.link) { qrBitmap(a.link) }
                Image(bmp.asImageBitmap(), null, Modifier.size(260.dp))
            }
        }
    }
}

@Composable
private fun Info(text: String) =
    Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp))

@Composable
private fun ErrorLine(error: String?) {
    if (!error.isNullOrBlank()) Text(i18n(R.string.tg_error, error), color = Color(0xFFF87171), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, hint: String, type: KeyboardType, big: Boolean = false, password: Boolean = false) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(hint) },
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = if (big) 36.sp else 26.sp, fontWeight = FontWeight.Black, color = Color.White, textAlign = if (big) TextAlign.Center else TextAlign.Start),
        keyboardOptions = KeyboardOptions(keyboardType = type),
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        shape = RoundedCornerShape(24.dp)
    )
}

@Composable
private fun BigAction(text: String, enabled: Boolean = true, secondary: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(80.dp),
        shape = RoundedCornerShape(40.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (secondary) Color(0xFF334155) else Color(0xFF2AABEE))
    ) { Text(text, fontSize = 20.sp, fontWeight = FontWeight.Black, color = Color.White, textAlign = TextAlign.Center) }
}

/** Best-effort guess of the phone's own number so the user only has to confirm it. */
private fun guessOwnNumber(ctx: Context): String {
    return try {
        val perm = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        if (!perm) return ""
        @Suppress("DEPRECATION", "MissingPermission")
        val n = (ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).line1Number.orEmpty()
        if (n.startsWith("+")) n else ""
    } catch (e: Exception) { "" }
}

private fun qrBitmap(text: String, size: Int = 512): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, if (m[x, y]) AColor.BLACK else AColor.WHITE)
    return bmp
}

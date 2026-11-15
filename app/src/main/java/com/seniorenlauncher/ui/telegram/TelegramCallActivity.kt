package com.seniorenlauncher.ui.telegram

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.seniorenlauncher.LauncherApp
import com.seniorenlauncher.R
import com.seniorenlauncher.data.model.AppSettings
import com.seniorenlauncher.data.telegram.TgCall
import com.seniorenlauncher.data.telegram.TgCallEnd
import com.seniorenlauncher.data.telegram.TgCallManager
import com.seniorenlauncher.data.telegram.TgCallPhase
import com.seniorenlauncher.service.TgCallService
import com.seniorenlauncher.ui.theme.SeniorenLauncherTheme
import com.seniorenlauncher.util.i18n
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * Full-screen Telegram call screen: incoming (Answer / Decline), outgoing and active calls,
 * voice and video. Shows over the lock screen.
 */
class TelegramCallActivity : ComponentActivity() {

    companion object {
        const val EXTRA_ANSWER = "tg_call_answer"
    }

    private var proximity: PowerManager.WakeLock? = null
    private var answerAfterPermission = false
    private var cameraAfterPermission = false

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // Without the microphone the other side can't hear us, but the call still connects so we can hear them.
        when {
            answerAfterPermission -> { answerAfterPermission = false; doAnswer() }
            cameraAfterPermission -> { cameraAfterPermission = false; if (has(Manifest.permission.CAMERA)) toggleCamera() }
            else -> onCallStarted()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        proximity = try {
            getSystemService(PowerManager::class.java)?.takeIf { it.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) }
                ?.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "SeniorenLauncher:TgCallProximity")
        } catch (e: Exception) { null }

        val c = TgCallManager.current.value
        if (c == null) { finish(); return }
        if (c.outgoing && savedInstanceState == null) {
            val missing = needed(c.video)
            if (missing.isEmpty()) onCallStarted() else permissions.launch(missing)
        }
        handleAnswerExtra(intent)

        setContent {
            val settings by LauncherApp.instance.settingsRepository.settingsFlow.collectAsState(initial = AppSettings())
            val call by TgCallManager.current.collectAsState()
            SeniorenLauncherTheme(appTheme = settings.theme, fontSize = settings.fontSize) {
                val shown = call
                LaunchedEffect(shown?.phase, shown?.speaker, shown?.cameraOn) {
                    updateProximity(shown)
                    if (shown == null) finish()
                    else if (shown.phase is TgCallPhase.Ended) {
                        delay(2000)
                        TgCallManager.clearEnded()
                        finish()
                    }
                }
                if (shown != null) CallScreen(shown, onAnswer = { answer() }, onToggleCamera = { toggleCamera() })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAnswerExtra(intent)
    }

    override fun onDestroy() {
        try { if (proximity?.isHeld == true) proximity?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun handleAnswerExtra(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_ANSWER, false) == true) {
            intent.removeExtra(EXTRA_ANSWER)
            answer()
        }
    }

    private fun answer() {
        val c = TgCallManager.current.value ?: return
        if (c.phase != TgCallPhase.Incoming) return
        val missing = needed(c.video)
        if (missing.isEmpty()) doAnswer() else { answerAfterPermission = true; permissions.launch(missing) }
    }

    private fun doAnswer() {
        TgCallManager.accept()
        onCallStarted()
    }

    private fun toggleCamera() {
        val c = TgCallManager.current.value ?: return
        if (!c.cameraOn && !has(Manifest.permission.CAMERA)) {
            cameraAfterPermission = true
            permissions.launch(arrayOf(Manifest.permission.CAMERA))
            return
        }
        TgCallManager.toggleCamera()
        // Restart the foreground service so its type includes (or drops) the camera.
        TgCallService.startOngoing(this)
    }

    /** The call is ours now (placed or answered): call audio mode + foreground service, while we're in front. */
    private fun onCallStarted() {
        TgCallManager.enterCallAudio(this)
        TgCallService.startOngoing(this)
    }

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun needed(video: Boolean): Array<String> =
        listOfNotNull(Manifest.permission.RECORD_AUDIO, if (video) Manifest.permission.CAMERA else null)
            .filterNot { has(it) }.toTypedArray()

    private fun updateProximity(c: TgCall?) {
        // Screen off near the ear, only while talking on the earpiece (never in a video call).
        val want = c != null && !c.speaker && !c.cameraOn && !c.video &&
            (c.phase is TgCallPhase.Active || c.phase == TgCallPhase.Connecting || c.phase is TgCallPhase.Outgoing)
        try {
            if (want && proximity?.isHeld == false) proximity?.acquire(2 * 60 * 60 * 1000L)
            if (!want && proximity?.isHeld == true) proximity?.release()
        } catch (_: Exception) {}
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

@Composable
private fun statusText(call: TgCall): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(call.phase) {
        while (call.phase is TgCallPhase.Active) { now = System.currentTimeMillis(); delay(1000) }
    }
    return when (val p = call.phase) {
        TgCallPhase.Incoming -> if (call.video) i18n(R.string.tgc_incoming_video) else i18n(R.string.tgc_incoming)
        is TgCallPhase.Outgoing -> if (p.ringing) i18n(R.string.tgc_ringing) else i18n(R.string.tgc_calling)
        TgCallPhase.Connecting -> i18n(R.string.tgc_connecting)
        is TgCallPhase.Active -> ((now - p.sinceMs) / 1000).coerceAtLeast(0).let { "%d:%02d".format(it / 60, it % 60) }
        is TgCallPhase.Ended -> when (p.reason) {
            TgCallEnd.Declined -> i18n(R.string.tgc_declined)
            TgCallEnd.Busy -> i18n(R.string.tgc_busy)
            TgCallEnd.Missed -> i18n(R.string.tgc_no_answer)
            TgCallEnd.Failed -> i18n(R.string.tgc_failed)
            TgCallEnd.HungUp -> i18n(R.string.tgc_ended)
        }
    }
}

@Composable
private fun CallScreen(call: TgCall, onAnswer: () -> Unit, onToggleCamera: () -> Unit) {
    val inCall = call.phase !is TgCallPhase.Ended && call.phase != TgCallPhase.Incoming
    if (inCall && (call.cameraOn || call.remoteVideo || call.video)) VideoCallScreen(call, onToggleCamera)
    else VoiceCallScreen(call, onAnswer, onToggleCamera)
}

@Composable
private fun VoiceCallScreen(call: TgCall, onAnswer: () -> Unit, onToggleCamera: () -> Unit) {
    val status = statusText(call)
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 48.dp)) {
            Text(i18n(R.string.tgc_title), color = Color(0xFF38BDF8), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Text(
                call.name.ifBlank { "Telegram" }, color = Color.White, fontSize = 42.sp, fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center, lineHeight = 48.sp
            )
            Spacer(Modifier.height(12.dp))
            Text(status, color = Color.White.copy(alpha = 0.75f), fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }

        Spacer(Modifier.height(32.dp))

        when (call.phase) {
            TgCallPhase.Incoming -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BigCallButton(i18n(R.string.weigeren), Icons.Default.CallEnd, Color(0xFFE53E3E), Modifier.weight(1f)) { TgCallManager.hangUp() }
                BigCallButton(
                    i18n(R.string.opnemen), if (call.video) Icons.Default.Videocam else Icons.Default.Call,
                    Color(0xFF38A169), Modifier.weight(1f), onClick = onAnswer
                )
            }
            is TgCallPhase.Ended -> Unit
            else -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SpeakerButton(call, Modifier.weight(1f))
                    MicButton(call, Modifier.weight(1f))
                }
                BigCallButton(i18n(R.string.tgc_start_video), Icons.Default.Videocam, Color(0xFF1E293B), Modifier.fillMaxWidth(), onClick = onToggleCamera)
                BigCallButton(i18n(R.string.ophangen), Icons.Default.CallEnd, Color(0xFFE53E3E), Modifier.fillMaxWidth()) { TgCallManager.hangUp() }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Their video fills the screen; ours is a small picture the senior can drag anywhere. The button
 * panel can be folded away (handle, or tap the video); Hang up always stays visible.
 */
@Composable
private fun VideoCallScreen(call: TgCall, onToggleCamera: () -> Unit) {
    val status = statusText(call)
    var panelOpen by rememberSaveable { mutableStateOf(true) }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures { panelOpen = !panelOpen } }
    ) {
        val density = LocalDensity.current
        if (call.remoteVideo) {
            VideoView(local = false, mirror = false, modifier = Modifier.fillMaxSize())
        } else {
            // Their camera is off: show who we're talking to.
            Text(
                call.name.ifBlank { "Telegram" }, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center, lineHeight = 46.sp, modifier = Modifier.align(Alignment.Center).padding(24.dp)
            )
        }

        // Name and timer on one line
        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth().background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                call.name.ifBlank { "Telegram" }, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
            )
            Text("  ·  $status", color = Color.White.copy(alpha = 0.85f), fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }

        // Our own picture: drag it anywhere, kept inside the screen.
        if (call.cameraOn) {
            val w = 120.dp; val h = 170.dp; val margin = 16.dp
            val maxX = with(density) { (maxWidth - w - margin).toPx() }
            val maxY = with(density) { (maxHeight - h - margin).toPx() }
            val minPx = with(density) { margin.toPx() }
            var pos by remember { mutableStateOf(androidx.compose.ui.geometry.Offset(maxX, with(density) { 80.dp.toPx() })) }
            VideoView(
                local = true, mirror = call.frontCamera,
                modifier = Modifier
                    .offset { IntOffset(pos.x.roundToInt(), pos.y.roundToInt()) }
                    .size(width = w, height = h)
                    .pointerInput(maxX, maxY) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            pos = androidx.compose.ui.geometry.Offset(
                                (pos.x + drag.x).coerceIn(minPx, maxX.coerceAtLeast(minPx)),
                                (pos.y + drag.y).coerceIn(minPx, maxY.coerceAtLeast(minPx))
                            )
                        }
                    }
                    .clip(RoundedCornerShape(20.dp))
                    .border(2.dp, Color.White, RoundedCornerShape(20.dp))
            )
        }

        if (call.phase !is TgCallPhase.Ended) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Handle: fold the panel away or bring it back.
                Surface(
                    onClick = { panelOpen = !panelOpen },
                    color = Color.White.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(40.dp),
                    modifier = Modifier.align(Alignment.CenterHorizontally).size(width = 120.dp, height = 44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (panelOpen) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp, null,
                            tint = Color.White, modifier = Modifier.size(40.dp)
                        )
                    }
                }
                AnimatedVisibility(visible = panelOpen) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            BigCallButton(
                                if (call.cameraOn) i18n(R.string.tgc_camera_on) else i18n(R.string.tgc_camera_off),
                                if (call.cameraOn) Icons.Default.Videocam else Icons.Default.VideocamOff,
                                if (call.cameraOn) Color(0xFF2563EB) else Color(0xFF334155), Modifier.weight(1f), small = true, onClick = onToggleCamera
                            )
                            if (call.cameraOn) {
                                BigCallButton(i18n(R.string.tgc_switch_camera), Icons.Default.Cameraswitch, Color(0xFF334155), Modifier.weight(1f), small = true) {
                                    TgCallManager.switchCamera()
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SpeakerButton(call, Modifier.weight(1f), small = true)
                            MicButton(call, Modifier.weight(1f), small = true)
                        }
                    }
                }
                BigCallButton(i18n(R.string.ophangen), Icons.Default.CallEnd, Color(0xFFE53E3E), Modifier.fillMaxWidth(), small = true) { TgCallManager.hangUp() }
            }
        }
    }
}

/** A WebRTC renderer registered with TgCallManager for remote or local frames. */
@Composable
private fun VideoView(local: Boolean, mirror: Boolean, modifier: Modifier) {
    AndroidView(
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(TgCallManager.eglContext(), null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
                if (local) setZOrderMediaOverlay(true)
                setMirror(mirror)
                TgCallManager.setVideoSink(local, this)
            }
        },
        update = { it.setMirror(mirror) },
        onRelease = {
            TgCallManager.setVideoSink(local, null)
            it.release()
        },
        modifier = modifier
    )
}

@Composable
private fun SpeakerButton(call: TgCall, modifier: Modifier, small: Boolean = false) = BigCallButton(
    if (call.speaker) i18n(R.string.luidspreker_aan) else i18n(R.string.luidspreker_uit),
    if (call.speaker) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
    if (call.speaker) Color(0xFF2563EB) else Color(0xFF1E293B), modifier, small
) { TgCallManager.toggleSpeaker() }

@Composable
private fun MicButton(call: TgCall, modifier: Modifier, small: Boolean = false) = BigCallButton(
    if (call.muted) i18n(R.string.tgc_mic_off) else i18n(R.string.tgc_mic_on),
    if (call.muted) Icons.Default.MicOff else Icons.Default.Mic,
    if (call.muted) Color(0xFFB45309) else Color(0xFF1E293B), modifier, small
) { TgCallManager.toggleMute() }

@Composable
private fun BigCallButton(label: String, icon: ImageVector, color: Color, modifier: Modifier = Modifier, small: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = if (small) 76.dp else 110.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
        shape = RoundedCornerShape(40.dp),
        contentPadding = PaddingValues(8.dp)
    ) {
        if (small) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(8.dp))
                Text(label, fontSize = 16.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, maxLines = 2)
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(icon, null, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(4.dp))
                Text(label, fontSize = 18.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, maxLines = 2)
            }
        }
    }
}

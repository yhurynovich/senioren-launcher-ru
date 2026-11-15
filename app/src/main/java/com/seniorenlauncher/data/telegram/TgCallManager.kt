package com.seniorenlauncher.data.telegram

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Base64
import android.util.Log
import com.seniorenlauncher.service.TgCallService
import com.seniorenlauncher.ui.telegram.TelegramCallActivity
import io.github.pytgcalls.ConnectionState
import io.github.pytgcalls.NTgCalls
import io.github.pytgcalls.media.AudioDescription
import io.github.pytgcalls.media.MediaDescription
import io.github.pytgcalls.media.MediaSource
import io.github.pytgcalls.media.StreamDevice
import io.github.pytgcalls.media.VideoDescription
import io.github.pytgcalls.media.StreamMode
import io.github.pytgcalls.p2p.RTCServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/** What a Telegram call is doing right now. */
sealed interface TgCallPhase {
    /** Outgoing: asked Telegram to place the call. [ringing] once the other phone rings. */
    data class Outgoing(val ringing: Boolean) : TgCallPhase
    /** Incoming: our phone is ringing. */
    data object Incoming : TgCallPhase
    /** Accepted; keys exchanged and the media connection is being set up. */
    data object Connecting : TgCallPhase
    data class Active(val sinceMs: Long) : TgCallPhase
    data class Ended(val reason: TgCallEnd) : TgCallPhase
}

enum class TgCallEnd { HungUp, Declined, Missed, Busy, Failed }

data class TgCall(
    val id: Int,
    val userId: Long,
    val name: String,
    val outgoing: Boolean,
    val video: Boolean,
    val phase: TgCallPhase,
    val muted: Boolean = false,
    val speaker: Boolean = false,
    /** We send our camera. */
    val cameraOn: Boolean = false,
    val frontCamera: Boolean = true,
    /** The other person's camera is on (their video is coming in). */
    val remoteVideo: Boolean = false
)

/**
 * Telegram voice and video calls inside the launcher.
 *
 * TDLib does the signalling and key exchange (createCall / acceptCall / updateCall); NTgCalls
 * (io.github.pytgcalls:ntgcalls, WebRTC) carries audio and video once TDLib reports callStateReady.
 * Incoming audio is played by RemoteAudioPlayer; incoming and local video frames (I420) are handed
 * to the SurfaceViewRenderers registered with [setVideoSink].
 * Signalling packets are passed both ways: NTgCalls.onSignalingData -> sendCallSignalingData,
 * updateNewCallSignalingData -> NTgCalls.sendSignalingData.
 */
object TgCallManager {
    private const val TAG = "TgCallManager"

    private val _current = MutableStateFlow<TgCall?>(null)
    val current: StateFlow<TgCall?> = _current.asStateFlow()

    /** NTgCalls calls are blocking native calls: keep them off the TDLib and main threads. */
    private val media = Executors.newSingleThreadExecutor { r -> Thread(r, "tg-call-media").apply { isDaemon = true } }

    @Volatile private var ntg: NTgCalls? = null
    @Volatile private var remoteAudio: RemoteAudioPlayer? = null
    @Volatile private var micDesc: AudioDescription? = null
    @Volatile private var camera: LocalCamera? = null
    /** Size of the frames our camera produces; the EXTERNAL capture description must match it. */
    @Volatile private var camW = 640
    @Volatile private var camH = 480
    @Volatile private var remoteSink: org.webrtc.VideoSink? = null
    @Volatile private var localSink: org.webrtc.VideoSink? = null
    private val eglBase: org.webrtc.EglBase by lazy { org.webrtc.EglBase.create() }

    /** Shared GL context for the video views. */
    fun eglContext(): org.webrtc.EglBase.Context = eglBase.eglBaseContext

    /** The call screen registers its video views here (null when they go away). */
    fun setVideoSink(local: Boolean, sink: org.webrtc.VideoSink?) {
        if (local) localSink = sink else remoteSink = sink
    }
    @Volatile private var mediaStarted = false
    @Volatile private var appContext: Context? = null
    private var ringtone: Ringtone? = null
    private var ringback: ToneGenerator? = null
    private var savedAudioMode: Int? = null

    private fun ctx(): Context? = appContext ?: TelegramClient.appContextOrNull()

    // ---- public actions --------------------------------------------------------------

    /** Starts an outgoing voice or video call to a Telegram user and opens the call screen. */
    fun start(context: Context, userId: Long, name: String, speaker: Boolean = false, video: Boolean = false) {
        appContext = context.applicationContext
        if (_current.value?.let { it.phase !is TgCallPhase.Ended } == true) { openScreen(context); return }
        _current.value = TgCall(id = 0, userId = userId, name = name, outgoing = true, video = video,
            phase = TgCallPhase.Outgoing(ringing = false), speaker = speaker || video, cameraOn = video)
        openScreen(context)
        TelegramClient.request(
            obj("createCall").put("user_id", userId).put("protocol", protocol()).put("is_video", video)
        ) { r ->
            if (r.optString("@type") == "error") {
                Log.w(TAG, "createCall failed: ${r.optString("message")}")
                end(TgCallEnd.Failed)
            } else {
                val id = r.optInt("id")
                _current.value = _current.value?.copy(id = id)
            }
        }
    }

    fun accept() {
        val c = _current.value ?: return
        if (c.phase != TgCallPhase.Incoming) return
        stopRinging()
        val cam = c.video && hasCameraPermission()
        _current.value = c.copy(phase = TgCallPhase.Connecting, cameraOn = cam, speaker = c.speaker || c.video)
        TelegramClient.request(obj("acceptCall").put("call_id", c.id).put("protocol", protocol())) { r ->
            if (r.optString("@type") == "error") { Log.w(TAG, "acceptCall: ${r.optString("message")}"); end(TgCallEnd.Failed) }
        }
    }

    /** Hang up, decline or cancel, depending on the phase. */
    fun hangUp() {
        val c = _current.value ?: return
        if (c.phase is TgCallPhase.Ended) return
        val duration = (c.phase as? TgCallPhase.Active)?.let { ((System.currentTimeMillis() - it.sinceMs) / 1000).toInt() } ?: 0
        if (c.id != 0) {
            TelegramClient.request(
                obj("discardCall").put("call_id", c.id).put("is_disconnected", false).put("invite_link", "")
                    .put("duration", duration).put("is_video", c.video || c.cameraOn).put("connection_id", 0)
            )
        }
        end(if (c.phase == TgCallPhase.Incoming) TgCallEnd.Declined else TgCallEnd.HungUp)
    }

    fun toggleMute() {
        val c = _current.value ?: return
        val muted = !c.muted
        _current.value = c.copy(muted = muted)
        val uid = c.userId
        media.execute { try { ntg?.let { if (muted) it.mute(uid) else it.unmute(uid) } } catch (e: Exception) { Log.w(TAG, "mute", e) } }
    }

    fun toggleSpeaker() {
        val c = _current.value ?: return
        val on = !c.speaker
        _current.value = c.copy(speaker = on)
        applySpeaker(on)
    }

    /** Turns our camera on or off during a call. */
    fun toggleCamera() {
        val c = _current.value ?: return
        if (!c.cameraOn && !hasCameraPermission()) return
        val on = !c.cameraOn
        _current.value = c.copy(cameraOn = on, speaker = if (on) true else c.speaker)
        if (on) applySpeaker(true)
        applyCapture()
        syncCamera()
    }

    /** Front <-> back camera. */
    fun switchCamera() {
        val c = _current.value ?: return
        _current.value = c.copy(frontCamera = !c.frontCamera)
        camera?.switchTo(!c.frontCamera)
    }

    private fun applyCapture() {
        val c = _current.value ?: return
        val n = ntg ?: return
        media.execute {
            try {
                n.setStreamSources(c.userId, StreamMode.CAPTURE, MediaDescription(micDesc, null, cameraDesc(c), null))
            } catch (e: Exception) { Log.w(TAG, "camera", e) }
        }
    }

    private fun hasCameraPermission(): Boolean = ctx()?.let {
        androidx.core.content.ContextCompat.checkSelfPermission(it, android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    } == true

    /** Camera capture description for the current call, or null when the camera is off. */
    private fun cameraDesc(c: TgCall): VideoDescription? {
        if (!c.cameraOn || !hasCameraPermission()) return null
        return VideoDescription(MediaSource.EXTERNAL, camW, camH, 30, "", true)
    }

    /** Starts or stops our camera to match the call state. */
    private fun syncCamera() {
        val c = _current.value
        val want = c != null && c.cameraOn && c.phase !is TgCallPhase.Ended && ntg != null && hasCameraPermission()
        val cam = camera
        if (want && cam == null) {
            val context = ctx() ?: return
            camera = LocalCamera(context) { data, w, h, rot -> onLocalFrame(data, w, h, rot) }.also { it.start(c!!.frontCamera) }
        } else if (!want && cam != null) {
            camera = null
            cam.stop()
        }
    }

    /** A frame from our camera: send it to the other side and show it in the self-view. */
    private fun onLocalFrame(data: ByteArray, w: Int, h: Int, rotation: Int) {
        val c = _current.value ?: return
        val n = ntg ?: return
        if (w != camW || h != camH) {
            // First frame (or a new camera) has another size: tell NTgCalls before sending.
            camW = w; camH = h
            Log.i(TAG, "local camera ${w}x$h rotation $rotation")
            applyCapture()
            return
        }
        val rot = when (rotation) {
            90 -> io.github.pytgcalls.VideoRotation.VIDEO_ROTATION_90
            180 -> io.github.pytgcalls.VideoRotation.VIDEO_ROTATION_180
            270 -> io.github.pytgcalls.VideoRotation.VIDEO_ROTATION_270
            else -> io.github.pytgcalls.VideoRotation.VIDEO_ROTATION_0
        }
        val fd = io.github.pytgcalls.models.FrameData(System.currentTimeMillis(), rot, w, h)
        try { n.sendExternalFrame(c.userId, StreamDevice.CAMERA, data, fd) } catch (e: Exception) { /* not connected yet */ }
        deliver(io.github.pytgcalls.models.Frame(0, data, fd), localSink)
    }

    // ---- video frames ---------------------------------------------------------------

    private var badFrameLogged = false

    /** NTgCalls video frame (I420) -> WebRTC VideoFrame for a SurfaceViewRenderer. */
    private fun deliver(f: io.github.pytgcalls.models.Frame, sink: org.webrtc.VideoSink?) {
        sink ?: return
        val fd = f.frame_data ?: return
        val w = fd.width; val h = fd.height
        if (w <= 0 || h <= 0) return
        val cw = (w + 1) / 2; val ch = (h + 1) / 2
        val ySize = w * h; val cSize = cw * ch
        if (f.data.size < ySize + 2 * cSize) {
            if (!badFrameLogged) { badFrameLogged = true; Log.w(TAG, "unexpected video frame: ${f.data.size} bytes for ${w}x$h") }
            return
        }
        val all = java.nio.ByteBuffer.allocateDirect(ySize + 2 * cSize)
        all.put(f.data, 0, ySize + 2 * cSize)
        fun plane(off: Int, len: Int): java.nio.ByteBuffer {
            all.limit(off + len); all.position(off)
            return all.slice()
        }
        val y = plane(0, ySize); val u = plane(ySize, cSize); val v = plane(ySize + cSize, cSize)
        val buffer = org.webrtc.JavaI420Buffer.wrap(w, h, y, w, u, cw, v, cw, null)
        val rotation = when (fd.rotation) {
            io.github.pytgcalls.VideoRotation.VIDEO_ROTATION_90 -> 90
            io.github.pytgcalls.VideoRotation.VIDEO_ROTATION_180 -> 180
            io.github.pytgcalls.VideoRotation.VIDEO_ROTATION_270 -> 270
            else -> 0
        }
        val frame = org.webrtc.VideoFrame(buffer, rotation, System.nanoTime())
        try { sink.onFrame(frame) } catch (e: Exception) { Log.w(TAG, "render", e) } finally { frame.release() }
    }

    /** Forget an ended call once its screen has closed. */
    fun clearEnded() {
        if (_current.value?.phase is TgCallPhase.Ended) _current.value = null
    }

    // ---- TDLib updates (called on the TDLib receiver thread) --------------------------

    internal fun onUpdate(o: JSONObject) {
        when (o.optString("@type")) {
            "updateCall" -> onCall(o.getJSONObject("call"))
            "updateNewCallSignalingData" -> {
                val c = _current.value ?: return
                if (o.optInt("call_id") != c.id) return
                val data = Base64.decode(o.optString("data"), Base64.DEFAULT)
                media.execute { try { ntg?.sendSignalingData(c.userId, data) } catch (e: Exception) { Log.w(TAG, "signaling in", e) } }
            }
        }
    }

    private fun onCall(call: JSONObject) {
        val id = call.optInt("id")
        val userId = call.optLong("user_id")
        val outgoing = call.optBoolean("is_outgoing")
        val state = call.optJSONObject("state") ?: return
        val type = state.optString("@type")
        val cur = _current.value

        // A new incoming call while we're busy with another one: turn it down.
        if (!outgoing && type == "callStatePending" && cur != null && cur.id != id && cur.phase !is TgCallPhase.Ended) {
            TelegramClient.request(obj("discardCall").put("call_id", id).put("is_disconnected", false).put("invite_link", "")
                .put("duration", 0).put("is_video", false).put("connection_id", 0))
            return
        }
        // Outgoing call created by start(): adopt its id before the createCall answer arrives.
        val mine = cur != null && (cur.id == id || (cur.id == 0 && cur.outgoing && outgoing && cur.userId == userId))
        if (!mine && !(type == "callStatePending" && !outgoing)) return

        when (type) {
            "callStatePending" -> {
                if (outgoing) {
                    _current.value = cur!!.copy(id = id, phase = TgCallPhase.Outgoing(ringing = state.optBoolean("is_received")))
                    if (state.optBoolean("is_received")) startRingback()
                } else if (cur?.id != id) {
                    appContext = appContext ?: TelegramClient.appContextOrNull()
                    _current.value = TgCall(id = id, userId = userId, name = TelegramClient.displayName(userId), outgoing = false,
                        video = call.optBoolean("is_video"), phase = TgCallPhase.Incoming)
                    TelegramClient.request(obj("getUser").put("user_id", userId)) { u ->
                        val n = TelegramClient.nameOf(u)
                        if (n.isNotBlank()) _current.value?.takeIf { it.id == id }?.let { _current.value = it.copy(name = n) }
                    }
                    ctx()?.let { onIncoming(it) }
                }
            }
            "callStateExchangingKeys" -> {
                stopRinging()
                _current.value = _current.value?.copy(id = id, phase = TgCallPhase.Connecting)
            }
            "callStateReady" -> {
                stopRinging()
                _current.value = _current.value?.copy(id = id, phase = TgCallPhase.Connecting)
                startMedia(userId, outgoing, state)
            }
            "callStateHangingUp" -> Unit
            "callStateDiscarded" -> {
                val reason = when (state.optJSONObject("reason")?.optString("@type")) {
                    "callDiscardReasonMissed" -> TgCallEnd.Missed
                    "callDiscardReasonDeclined" -> if (outgoing) TgCallEnd.Busy else TgCallEnd.Declined
                    else -> TgCallEnd.HungUp
                }
                end(reason)
            }
            "callStateError" -> {
                Log.w(TAG, "call error: ${state.optJSONObject("error")?.optString("message")}")
                end(TgCallEnd.Failed)
            }
        }
    }

    // ---- media -----------------------------------------------------------------------

    private fun startMedia(userId: Long, outgoing: Boolean, ready: JSONObject) {
        if (mediaStarted) return
        mediaStarted = true
        val context = ctx()
        media.execute {
            try {
                context?.let { org.webrtc.ContextUtils.initialize(it) }
                val n = NTgCalls()
                ntg = n
                n.onSignalingData { _, data ->
                    val id = _current.value?.id ?: return@onSignalingData
                    TelegramClient.request(obj("sendCallSignalingData").put("call_id", id)
                        .put("data", Base64.encodeToString(data, Base64.NO_WRAP)))
                }
                n.onConnectionChange { _, info ->
                    Log.i(TAG, "connection: ${info.state}")
                    when (info.state) {
                        ConnectionState.CONNECTED -> _current.value?.let {
                            if (it.phase !is TgCallPhase.Active && it.phase !is TgCallPhase.Ended)
                                _current.value = it.copy(phase = TgCallPhase.Active(System.currentTimeMillis()))
                        }
                        ConnectionState.FAILED, ConnectionState.TIMEOUT -> { hangUpAfterFailure() }
                        else -> Unit
                    }
                }
                val devices = NTgCalls.getMediaDevices()
                Log.i(TAG, "mics=${devices.microphone.map { it.name + "|" + it.metadata }} speakers=${devices.speaker.map { it.name + "|" + it.metadata }}")
                Log.i(TAG, "cameras=${devices.camera.map { it.name + "|" + it.metadata }}")
                val mic = AudioDescription(MediaSource.DEVICE, 48000, 1, devices.microphone.firstOrNull()?.metadata ?: "", true)
                micDesc = mic
                // Incoming media in PLAYBACK mode is named after its source on the other phone: the caller's
                // voice is the "microphone" slot, their video the "camera" slot. EXTERNAL hands us raw frames:
                // PCM for audio (played by RemoteAudioPlayer; DEVICE playback opens no output on Android).
                val remoteVoice = AudioDescription(MediaSource.EXTERNAL, 48000, 1, "", true)
                // 0x0@0 = "auto resolution": frames arrive at their real size and shape (a fixed size stretched them).
                val remoteVideo = VideoDescription(MediaSource.EXTERNAL, 0, 0, 0, "", true)
                val player = RemoteAudioPlayer(48000, 1)
                remoteAudio = player
                val seen = java.util.Collections.synchronizedSet(HashSet<String>())
                n.onFrames { _, mode, device, frames ->
                    val f = frames.firstOrNull()
                    if (f != null && seen.add("$mode/$device")) {
                        Log.i(TAG, "first frames $mode/$device: n=${frames.size} bytes=${f.data.size} ${f.frame_data?.width}x${f.frame_data?.height}")
                    }
                    when {
                        mode == StreamMode.PLAYBACK && (device == StreamDevice.MICROPHONE || device == StreamDevice.SPEAKER) ->
                            frames.forEach { player.write(it.data) }
                        device == StreamDevice.CAMERA ->
                            frames.lastOrNull()?.let { deliver(it, if (mode == StreamMode.PLAYBACK) remoteSink else localSink) }
                        else -> Unit
                    }
                }
                n.onRemoteSourceChange { _, src ->
                    Log.i(TAG, "remote source: ${src.device} ${src.state}")
                    if (src.device == StreamDevice.CAMERA || src.device == StreamDevice.SCREEN) {
                        _current.value?.let { _current.value = it.copy(remoteVideo = src.state == io.github.pytgcalls.media.StreamStatus.ACTIVE) }
                    }
                }

                n.createP2pCall(userId)
                n.setStreamSources(userId, StreamMode.CAPTURE, MediaDescription(mic, null, _current.value?.let { cameraDesc(it) }, null))
                n.setStreamSources(userId, StreamMode.PLAYBACK, MediaDescription(remoteVoice, null, remoteVideo, null))
                n.skipExchange(userId, Base64.decode(ready.optString("encryption_key"), Base64.DEFAULT), outgoing)

                val servers = toServers(ready.optJSONArray("servers") ?: JSONArray())
                val versions = ready.optJSONObject("protocol")?.optJSONArray("library_versions")?.let { a -> (0 until a.length()).map { a.getString(it) } }
                    ?: NTgCalls.getProtocol().library_versions
                // NTgCalls parses this as JSON in native code; an empty or invalid string throws a C++
                // exception that kills the whole process. TDLib often sends "", so always pass a JSON object.
                val custom = ready.optString("custom_parameters", "").let { p ->
                    try { JSONObject(p).toString() } catch (e: Exception) { "{}" }
                }
                Log.i(TAG, "custom_parameters=$custom")
                n.connectP2p(userId, servers, versions, ready.optBoolean("allow_p2p"), custom)
                Log.i(TAG, "connectP2p sent: ${servers.size} servers, versions=$versions")
                syncCamera()
            } catch (e: Throwable) {
                Log.e(TAG, "media start failed", e)
                hangUpAfterFailure()
            }
        }
    }

    private fun hangUpAfterFailure() {
        val c = _current.value ?: return
        if (c.phase is TgCallPhase.Ended) return
        if (c.id != 0) TelegramClient.request(obj("discardCall").put("call_id", c.id).put("is_disconnected", true).put("invite_link", "")
            .put("duration", 0).put("is_video", c.video).put("connection_id", 0))
        end(TgCallEnd.Failed)
    }

    private fun toServers(arr: JSONArray): List<RTCServer> = (0 until arr.length()).mapNotNull { i ->
        val s = arr.getJSONObject(i)
        val t = s.optJSONObject("type") ?: return@mapNotNull null
        when (t.optString("@type")) {
            "callServerTypeTelegramReflector" -> RTCServer(
                s.optLong("id"), s.optString("ip_address"), s.optString("ipv6_address"), s.optInt("port"),
                "", "", true, false, t.optBoolean("is_tcp"), Base64.decode(t.optString("peer_tag"), Base64.DEFAULT)
            )
            "callServerTypeWebrtc" -> RTCServer(
                s.optLong("id"), s.optString("ip_address"), s.optString("ipv6_address"), s.optInt("port"),
                t.optString("username"), t.optString("password"), t.optBoolean("supports_turn"), t.optBoolean("supports_stun"), false, ByteArray(0)
            )
            else -> null
        }
    }

    private fun protocol(): JSONObject {
        val p = try { NTgCalls.getProtocol() } catch (e: Throwable) { null }
        return obj("callProtocol")
            .put("udp_p2p", p?.udp_p2p ?: true).put("udp_reflector", p?.udp_reflector ?: true)
            .put("min_layer", p?.min_layer ?: 65).put("max_layer", p?.max_layer ?: 92)
            .put("library_versions", JSONArray(p?.library_versions ?: listOf("9.0.0")))
    }

    // ---- lifecycle helpers ------------------------------------------------------------

    private fun end(reason: TgCallEnd) {
        stopRinging()
        val c = _current.value
        if (c != null && c.phase !is TgCallPhase.Ended) _current.value = c.copy(phase = TgCallPhase.Ended(reason))
        val n = ntg
        ntg = null
        mediaStarted = false
        val player = remoteAudio
        remoteAudio = null
        micDesc = null
        camera?.stop()
        camera = null
        if (n != null && c != null) media.execute { try { n.stop(c.userId) } catch (e: Exception) { Log.w(TAG, "stop", e) } }
        media.execute { player?.release() }
        restoreAudio()
        ctx()?.let { TgCallService.stop(it) }
    }

    private fun onIncoming(context: Context) {
        com.seniorenlauncher.service.NotificationListener.hideTelegramCallBanners()
        startRinging(context)
        TgCallService.showIncoming(context)
    }

    fun openScreen(context: Context) {
        context.startActivity(Intent(context, TelegramCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    /** Puts the audio system in call mode; the call screen calls this once the call is accepted or placed. */
    fun enterCallAudio(context: Context) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        if (savedAudioMode == null) savedAudioMode = am.mode
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        applySpeaker(_current.value?.speaker == true)
    }

    private fun applySpeaker(on: Boolean) {
        val am = ctx()?.getSystemService(AudioManager::class.java) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (on) am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { am.setCommunicationDevice(it) }
                else am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = on
            }
        } catch (e: Exception) { Log.w(TAG, "speaker", e) }
    }

    @Suppress("DEPRECATION")
    private fun restoreAudio() {
        val am = ctx()?.getSystemService(AudioManager::class.java) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.clearCommunicationDevice()
            else am.isSpeakerphoneOn = false
            savedAudioMode?.let { am.mode = it }
        } catch (e: Exception) { Log.w(TAG, "restore audio", e) }
        savedAudioMode = null
    }

    @Synchronized
    private fun startRinging(context: Context) {
        try {
            ringtone = RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                play()
            }
            vibrator(context)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 800), 0))
        } catch (e: Exception) { Log.w(TAG, "ring", e) }
    }

    @Synchronized
    private fun startRingback() {
        if (ringback != null) return
        try {
            ringback = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80).apply { startTone(ToneGenerator.TONE_SUP_RINGTONE) }
        } catch (e: Exception) { Log.w(TAG, "ringback", e) }
    }

    @Synchronized
    private fun stopRinging() {
        try { ringtone?.stop() } catch (_: Exception) {}
        ringtone = null
        try { ringback?.stopTone(); ringback?.release() } catch (_: Exception) {}
        ringback = null
        ctx()?.let { vibrator(it)?.cancel() }
    }

    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else context.getSystemService(Vibrator::class.java)

    private fun obj(type: String) = JSONObject().put("@type", type)
}


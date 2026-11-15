package com.seniorenlauncher.data.telegram

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * Plays the other person's voice in a Telegram call.
 *
 * NTgCalls' DEVICE speaker source opens no audio output on Android, so playback is EXTERNAL:
 * NTgCalls hands us 16-bit PCM frames (onFrames, PLAYBACK / SPEAKER) and we write them to an
 * AudioTrack with voice-call attributes. That follows the call audio routing (earpiece or
 * speaker via AudioManager's communication device) and the in-call volume keys.
 */
class RemoteAudioPlayer(private val sampleRate: Int, private val channels: Int) {
    private var track: AudioTrack? = null
    private var frames = 0L

    @Synchronized
    fun write(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        val t = track ?: create().also { track = it } ?: return
        if (frames++ == 0L) Log.i(TAG, "first remote audio frame: ${pcm.size} bytes")
        // Never block NTgCalls' thread: if the buffer is full, drop this frame.
        t.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING)
    }

    @Synchronized
    fun release() {
        try { track?.stop() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        track = null
        if (frames > 0) Log.i(TAG, "remote audio frames played: $frames")
        frames = 0
    }

    private fun create(): AudioTrack? = try {
        val mask = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val min = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        // ~200 ms of audio absorbs network jitter without adding much delay.
        val size = maxOf(min, sampleRate * channels * 2 / 5)
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(mask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
            )
            .setBufferSizeInBytes(size)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }
    } catch (e: Exception) {
        Log.e(TAG, "AudioTrack failed", e)
        null
    }

    companion object { private const val TAG = "TgCallManager" }
}

package dev.muse.fakecall

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/**
 * Synthesizes an authentic Japanese phone ringback tone ("プルルルル…":
 * 400 Hz carrier amplitude-modulated at 16 Hz, 1.0 s ON / 2.0 s OFF loop)
 * directly on `USAGE_VOICE_COMMUNICATION` so it routes through the earpiece
 * (or speakerphone when toggled) while waiting for Muse to pick up.
 */
class RingbackTone {

    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile private var track: AudioTrack? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ playLoop() }, "fakecall-ringback").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        try { track?.pause() } catch (ignored: Exception) { }
        try { track?.flush() } catch (ignored: Exception) { }
        try { track?.stop() } catch (ignored: Exception) { }
        try { track?.release() } catch (ignored: Exception) { }
        track = null
        try { thread?.interrupt() } catch (ignored: Exception) { }
        thread = null
    }

    private fun playLoop() {
        val sr = 16000
        val onSamples = sr // 1.0 s tone
        val offSamples = sr * 2 // 2.0 s silence
        val pcm = ShortArray(onSamples)
        val fadeSamples = sr / 50 // 20 ms fade in/out to avoid clicks
        for (i in 0 until onSamples) {
            val t = i.toDouble() / sr
            val carrier = sin(2.0 * PI * 400.0 * t)
            val mod = 0.5 + 0.5 * sin(2.0 * PI * 16.0 * t)
            val env = when {
                i < fadeSamples -> i.toDouble() / fadeSamples
                i > onSamples - fadeSamples -> (onSamples - i).toDouble() / fadeSamples
                else -> 1.0
            }
            val sample = carrier * mod * env * 0.28 * Short.MAX_VALUE
            pcm[i] = sample.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        val silenceChunk = ShortArray(sr / 10) // 100 ms chunks so stop() responds immediately

        val minBuf = AudioTrack.getMinBufferSize(
            sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val at = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sr)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minBuf * 2)
                .build()
        } catch (e: Exception) {
            running = false
            return
        }
        track = at
        try {
            at.play()
            while (running) {
                var off = 0
                val step = sr / 20 // 50 ms
                while (running && off < pcm.size) {
                    val len = minOf(step, pcm.size - off)
                    val w = at.write(pcm, off, len, AudioTrack.WRITE_BLOCKING)
                    if (w <= 0) break
                    off += w
                }
                var silLeft = offSamples
                while (running && silLeft > 0) {
                    val len = minOf(silenceChunk.size, silLeft)
                    val w = at.write(silenceChunk, 0, len, AudioTrack.WRITE_BLOCKING)
                    if (w <= 0) break
                    silLeft -= w
                }
            }
        } catch (ignored: Exception) {
        } finally {
            try { at.stop() } catch (ignored: Exception) { }
            try { at.release() } catch (ignored: Exception) { }
            if (track === at) track = null
        }
    }
}

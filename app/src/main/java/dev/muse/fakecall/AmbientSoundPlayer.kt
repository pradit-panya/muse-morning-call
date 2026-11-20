package dev.muse.fakecall

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.Calendar
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Low-volume continuous ambient & foley mixer for phone-call presence.
 *
 * Streams a subtle 48 kHz mono bed on [AudioAttributes.USAGE_VOICE_COMMUNICATION]:
 * - Room tone (faint indoor AC / air) continuously while in call
 * - Distant morning birds when morning call or 05:00-09:59 local time
 * - Occasional soft cloth/desk rustles
 * - Soft muffled keyboard typing while waiting >2.6 s for Muse's reply
 *
 * Ducks automatically during [Mode.LISTENING] so Android SpeechRecognizer is never triggered.
 */
class AmbientSoundPlayer(
    context: Context,
    private val morningOverride: Boolean = false
) {
    enum class Mode { NORMAL, LISTENING, MUTED }

    private val appCtx = context.applicationContext
    @Volatile private var released = false
    @Volatile private var mode: Mode = Mode.NORMAL
    @Volatile private var typingTargetGain = 0f

    private var typingCurrentGain = 0f
    private var workerThread: Thread? = null

    fun start() {
        if (released || workerThread != null) return
        workerThread = Thread({ runMixLoop() }, "fakecall-ambient").apply {
            isDaemon = true
            start()
        }
    }

    fun setMode(newMode: Mode) {
        mode = newMode
    }

    fun setTypingActive(active: Boolean) {
        typingTargetGain = if (active) TYPING_GAIN else 0f
    }

    fun release() {
        released = true
        workerThread?.interrupt()
        workerThread = null
    }

    private fun isMorningHour(): Boolean {
        if (morningOverride) return true
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour in 5..9
    }

    private fun loadPcm16Mono(assetPath: String): ShortArray? {
        return try {
            val bytes = appCtx.assets.open(assetPath).use { it.readBytes() }
            val wav = PcmAudioPlayer.parseWav(bytes) ?: return null
            val n = wav.pcm.size / 2
            val out = ShortArray(n)
            for (i in 0 until n) {
                val lo = wav.pcm[i * 2].toInt() and 0xFF
                val hi = wav.pcm[i * 2 + 1].toInt()
                out[i] = ((hi shl 8) or lo).toShort()
            }
            out
        } catch (e: Exception) {
            Log.w("FakeCallDbg", "AmbientSoundPlayer: failed to load $assetPath", e)
            null
        }
    }

    private fun runMixLoop() {
        val room = loadPcm16Mono("ambient/room_tone.wav") ?: return
        val birds = if (isMorningHour()) loadPcm16Mono("ambient/birds_morning.wav") else null
        val typing = loadPcm16Mono("ambient/typing_loop.wav")
        val rustles = listOfNotNull(
            loadPcm16Mono("ambient/rustle_1.wav"),
            loadPcm16Mono("ambient/rustle_2.wav")
        )

        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minBuf * 2)
                .build()
        } catch (e: Exception) {
            Log.w("FakeCallDbg", "AmbientSoundPlayer: AudioTrack init failed", e)
            return
        }

        val chunkFrames = 2400 // 50 ms at 48 kHz
        val outBuf = ShortArray(chunkFrames)
        var roomPos = 0
        var birdsPos = 0
        var typingPos = 0
        var activeRustle: ShortArray? = null
        var rustlePos = 0
        var nextRustleFrames = SAMPLE_RATE * Random.nextInt(8, 16)
        var masterGain = 0f

        try {
            track.play()
            while (!released) {
                val targetMaster = when (mode) {
                    Mode.NORMAL, Mode.LISTENING -> 1.0f
                    Mode.MUTED -> 0.0f
                }

                for (i in 0 until chunkFrames) {
                    masterGain += (targetMaster - masterGain) * 0.002f
                    typingCurrentGain += (typingTargetGain - typingCurrentGain) * 0.004f

                    var mix = room[roomPos] * ROOM_GAIN
                    roomPos++
                    if (roomPos >= room.size) roomPos = 0

                    if (birds != null) {
                        mix += birds[birdsPos] * BIRDS_GAIN
                        birdsPos++
                        if (birdsPos >= birds.size) birdsPos = 0
                    }

                    if (typing != null && typingCurrentGain > 0.0005f) {
                        mix += typing[typingPos] * typingCurrentGain
                        typingPos++
                        if (typingPos >= typing.size) typingPos = 0
                    }

                    val rus = activeRustle
                    if (rus != null) {
                        mix += rus[rustlePos] * RUSTLE_GAIN
                        rustlePos++
                        if (rustlePos >= rus.size) {
                            activeRustle = null
                            rustlePos = 0
                            nextRustleFrames = SAMPLE_RATE * Random.nextInt(9, 19)
                        }
                    } else if (mode == Mode.NORMAL && rustles.isNotEmpty()) {
                        nextRustleFrames--
                        if (nextRustleFrames <= 0) {
                            activeRustle = rustles[Random.nextInt(rustles.size)]
                            rustlePos = 0
                        }
                    }

                    val s = (mix * masterGain).roundToInt().coerceIn(-32767, 32767)
                    outBuf[i] = s.toShort()
                }

                val written = track.write(outBuf, 0, chunkFrames, AudioTrack.WRITE_BLOCKING)
                if (written < 0) break
            }
        } catch (ignored: InterruptedException) {
        } catch (e: Exception) {
            Log.w("FakeCallDbg", "AmbientSoundPlayer loop error", e)
        } finally {
            try { track.stop() } catch (ignored: Exception) { }
            try { track.release() } catch (ignored: Exception) { }
        }
    }

    companion object {
        private const val SAMPLE_RATE = 48000
        private const val ROOM_GAIN = 0.018f
        private const val BIRDS_GAIN = 0.014f
        private const val RUSTLE_GAIN = 0.022f
        private const val TYPING_GAIN = 0.045f
    }
}

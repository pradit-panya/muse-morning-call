package dev.muse.fakecall

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-process runtime bridge between [CallServerService], [RingService], and [CallActivity].
 *
 * Replaces broadcast IPC and overwritten `say_audio.wav` files with direct thread-safe
 * RAM handoffs, serial sentence-pipelined PC TTS generation, and clean remote hangup.
 */
object CallRuntime {

    interface ActiveCallHandler {
        val sessionId: Long
        fun onAudioBytesArrived(bytes: ByteArray, ext: String, textHint: String?)
        fun onRemoteAccept()
        fun onRemoteHangup(reason: String)
        fun onPendingTtsCountChanged(remaining: Int)
    }

    data class SayTextResult(
        val ok: Boolean,
        val delivered: Boolean,
        val sentences: Int,
        val firstGenMs: Long,
        val error: String? = null
    )

    @Volatile
    private var activeHandler: ActiveCallHandler? = null

    private val pendingTtsJobs = AtomicInteger(0)
    private val callEpoch = AtomicInteger(0)
    private val ttsExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "fakecall-tts-pipe").apply { isDaemon = true }
    }

    val isCallLive: Boolean
        get() = activeHandler != null || RingService.isRunning

    val pendingTtsCount: Int
        get() = pendingTtsJobs.get()

    fun registerHandler(handler: ActiveCallHandler) {
        activeHandler = handler
        pendingTtsJobs.set(0)
    }

    fun unregisterHandler(handler: ActiveCallHandler) {
        if (activeHandler === handler) {
            activeHandler = null
            callEpoch.incrementAndGet()
            pendingTtsJobs.set(0)
        }
    }

    /** Delivers raw audio bytes directly in RAM to the live CallActivity. */
    fun deliverAudioBytes(bytes: ByteArray, ext: String, textHint: String? = null): Boolean {
        val handler = activeHandler ?: return false
        if (bytes.isEmpty()) return false
        handler.onAudioBytesArrived(bytes, ext, textHint)
        return true
    }

    /**
     * Synthesizes [text] directly from the PC Irodori-TTS server over Tailscale LAN
     * and delivers it to the active call.
     *
     * Sentence 1 is awaited synchronously so Muse gets immediate success/fallback feedback
     * (~1.5-2.0 s, zero cloud proxy WAV transfer); sentences 2..N are generated sequentially
     * on [ttsExecutor] and streamed into the FIFO while sentence 1 is already playing.
     */
    fun deliverSayText(
        ctx: Context,
        text: String,
        split: Boolean = true,
        ttsUrlOverride: String? = null
    ): SayTextResult {
        val handler = activeHandler
            ?: return SayTextResult(ok = true, delivered = false, sentences = 0, firstGenMs = 0)
        val sentences = if (split) LocalTtsClient.splitSentences(text) else listOf(text.trim()).filter { it.isNotEmpty() }
        if (sentences.isEmpty()) {
            return SayTextResult(ok = true, delivered = false, sentences = 0, firstGenMs = 0)
        }

        val ttsUrl = ttsUrlOverride?.takeIf { it.isNotBlank() } ?: Store.ttsServerUrl(ctx)
        val epoch = callEpoch.get()
        val remaining = sentences.drop(1)

        // Reserve pending count BEFORE delivering sentence 1 so CallActivity never
        // switches back to LISTENING in the gap between sentence 1 ending and sentence 2 arriving.
        if (remaining.isNotEmpty()) {
            val count = pendingTtsJobs.addAndGet(remaining.size)
            handler.onPendingTtsCountChanged(count)
        }

        val firstRes = LocalTtsClient.synthesize(ttsUrl, sentences[0])
        if (firstRes.wavBytes == null) {
            if (remaining.isNotEmpty()) {
                val count = pendingTtsJobs.addAndGet(-remaining.size).coerceAtLeast(0)
                pendingTtsJobs.set(count)
                handler.onPendingTtsCountChanged(count)
            }
            return SayTextResult(
                ok = false,
                delivered = false,
                sentences = sentences.size,
                firstGenMs = firstRes.genMs,
                error = firstRes.error ?: "tts_failed"
            )
        }

        if (callEpoch.get() != epoch || activeHandler !== handler) {
            if (remaining.isNotEmpty()) {
                pendingTtsJobs.set(0)
            }
            return SayTextResult(ok = true, delivered = false, sentences = sentences.size, firstGenMs = firstRes.genMs)
        }

        handler.onAudioBytesArrived(firstRes.wavBytes, "wav", sentences[0])

        for (sentence in remaining) {
            ttsExecutor.execute {
                try {
                    if (callEpoch.get() != epoch || activeHandler !== handler) return@execute
                    val res = LocalTtsClient.synthesize(ttsUrl, sentence)
                    if (res.wavBytes != null && callEpoch.get() == epoch && activeHandler === handler) {
                        handler.onAudioBytesArrived(res.wavBytes, "wav", sentence)
                    } else if (res.wavBytes == null) {
                        Log.w("FakeCallDbg", "CallRuntime: follow-up sentence TTS failed: ${res.error}")
                    }
                } finally {
                    val left = pendingTtsJobs.decrementAndGet().coerceAtLeast(0)
                    if (left == 0) pendingTtsJobs.set(0)
                    activeHandler?.onPendingTtsCountChanged(left)
                }
            }
        }

        return SayTextResult(
            ok = true,
            delivered = true,
            sentences = sentences.size,
            firstGenMs = firstRes.genMs
        )
    }

    /** Answers a user-initiated outgoing call (`CallState.DIALING` -> `ACTIVE`). */
    fun requestAccept(): Boolean {
        val handler = activeHandler ?: return false
        handler.onRemoteAccept()
        return true
    }

    /**
     * Officially ends a ringing, dialing, or active call (used by `POST /hangup`
     * and the ongoing-call notification's hangup button).
     */
    fun requestHangup(ctx: Context, reason: String = "remote_hangup"): Boolean {
        callEpoch.incrementAndGet()
        pendingTtsJobs.set(0)
        val handler = activeHandler
        if (handler != null) {
            handler.onRemoteHangup(reason)
            return true
        }
        if (RingService.isRunning) {
            val session = CallEventBus.activeSession
            RingService.stopAll(ctx)
            CallStarter.cancelNotification(ctx)
            CallEventBus.updateState(
                state = CallEventBus.CallState.IDLE,
                phase = CallEventBus.DialogPhase.OFF
            )
            CallEventBus.emit("CALL_ENDED", session, mapOf("reason" to reason))
            return true
        }
        return false
    }
}

package dev.muse.fakecall

/**
 * In-process thread-safe call state store & event bus for instant Long Polling
 * (`GET /events?since=<seq>&timeout=<ms>`).
 *
 * Replaces 0.7 s polling: when the user answers, speaks, or hangs up, waiting
 * HTTP threads wake up immediately via [lock.notifyAll].
 */
object CallEventBus {

    enum class CallState { IDLE, RINGING, DIALING, ACTIVE }

    enum class DialogPhase { OFF, PLAYING, LISTENING, WAITING, MUTED, IDLE }

    data class Event(
        val seq: Long,
        val type: String,
        val session: Long,
        val ts: Long,
        val fields: Map<String, Any?>
    )

    private const val MAX_HISTORY = 64
    private val lock = Object()
    private val history = ArrayDeque<Event>(MAX_HISTORY)
    private var nextSeq: Long = 0L

    @Volatile var callState: CallState = CallState.IDLE
        private set
    @Volatile var dialogPhase: DialogPhase = DialogPhase.OFF
        private set
    @Volatile var activeSession: Long = 0L
        private set
    @Volatile var activeMorning: Boolean = false
        private set
    @Volatile var activeDialog: Boolean = false
        private set
    @Volatile var activeMuted: Boolean = false
        private set
    @Volatile var activeSpeaker: Boolean = false
        private set

    fun latestSeq(): Long = synchronized(lock) { nextSeq }

    fun updateState(
        state: CallState = callState,
        phase: DialogPhase = dialogPhase,
        session: Long = activeSession,
        morning: Boolean = activeMorning,
        dialog: Boolean = activeDialog,
        muted: Boolean = activeMuted,
        speaker: Boolean = activeSpeaker
    ) {
        synchronized(lock) {
            callState = state
            dialogPhase = phase
            activeSession = session
            activeMorning = morning
            activeDialog = dialog
            activeMuted = muted
            activeSpeaker = speaker
            lock.notifyAll()
        }
    }

    fun emit(type: String, session: Long, fields: Map<String, Any?> = emptyMap()): Event {
        synchronized(lock) {
            nextSeq += 1
            val ev = Event(
                seq = nextSeq,
                type = type,
                session = session,
                ts = System.currentTimeMillis(),
                fields = fields
            )
            if (history.size >= MAX_HISTORY) {
                history.removeFirst()
            }
            history.addLast(ev)
            lock.notifyAll()
            return ev
        }
    }

    /**
     * Blocks until at least one event with `seq > sinceSeq` exists or [timeoutMs]
     * elapses. Returns all matching events in ascending seq order (may be empty on
     * timeout).
     */
    fun waitForEvents(sinceSeq: Long, timeoutMs: Long): List<Event> {
        val clampedTimeout = timeoutMs.coerceIn(0L, 55_000L)
        val deadline = System.currentTimeMillis() + clampedTimeout
        synchronized(lock) {
            while (true) {
                val matching = history.filter { it.seq > sinceSeq }
                if (matching.isNotEmpty()) return matching
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L) return emptyList()
                try {
                    lock.wait(remaining)
                } catch (e: InterruptedException) {
                    return history.filter { it.seq > sinceSeq }
                }
            }
        }
    }

    fun eventsToJson(events: List<Event>): String {
        val sb = StringBuilder(256)
        sb.append("{\"seq\":").append(latestSeq())
        sb.append(",\"state\":\"").append(callState.name).append('"')
        sb.append(",\"dialog_phase\":\"").append(dialogPhase.name).append('"')
        sb.append(",\"session\":").append(activeSession)
        sb.append(",\"events\":[")
        events.forEachIndexed { idx, ev ->
            if (idx > 0) sb.append(',')
            sb.append("{\"seq\":").append(ev.seq)
            sb.append(",\"type\":\"").append(jsonEscape(ev.type)).append('"')
            sb.append(",\"session\":").append(ev.session)
            sb.append(",\"ts\":").append(ev.ts)
            for ((k, v) in ev.fields) {
                sb.append(",\"").append(jsonEscape(k)).append("\":")
                when (v) {
                    null -> sb.append("null")
                    is Boolean, is Number -> sb.append(v.toString())
                    else -> sb.append('"').append(jsonEscape(v.toString())).append('"')
                }
            }
            sb.append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    fun jsonEscape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c.code < 0x20) sb.append(String.format("\\u%04x", c.code))
                    else sb.append(c)
                }
            }
        }
        return sb.toString()
    }
}

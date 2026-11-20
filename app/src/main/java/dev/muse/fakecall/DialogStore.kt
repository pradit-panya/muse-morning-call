package dev.muse.fakecall

import android.content.Context

/**
 * Stores the latest recognized user utterance and dialogue active flag.
 * Backwards compatible with GET /utterance while also supporting Long Polling
 * (`GET /utterance?since=<id>&timeout=<ms>`) and emitting `USER_SPEECH` events
 * onto [CallEventBus].
 */
object DialogStore {
    private const val PREFS = "fakecall"
    private val lock = Object()

    data class Utterance(val id: Long, val text: String, val ts: Long)

    /** Records one recognized utterance; wakes long-pollers and returns its new id. */
    fun recordUtterance(ctx: Context, text: String, session: Long = CallEventBus.activeSession): Long {
        val id: Long
        val ts = System.currentTimeMillis()
        synchronized(lock) {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            id = prefs.getLong("dialog_utt_id", 0L) + 1
            prefs.edit()
                .putLong("dialog_utt_id", id)
                .putString("dialog_utt_text", text)
                .putLong("dialog_utt_ts", ts)
                .commit()
            lock.notifyAll()
        }
        CallEventBus.emit(
            "USER_SPEECH",
            session,
            mapOf("utterance_id" to id, "text" to text)
        )
        return id
    }

    /** The latest utterance, or null if none has ever been recorded. */
    fun latestUtterance(ctx: Context): Utterance? {
        synchronized(lock) {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val id = prefs.getLong("dialog_utt_id", 0L)
            if (id == 0L) return null
            return Utterance(
                id,
                prefs.getString("dialog_utt_text", "") ?: "",
                prefs.getLong("dialog_utt_ts", 0L)
            )
        }
    }

    fun setDialogActive(ctx: Context, active: Boolean) {
        synchronized(lock) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean("dialog_active", active).commit()
            lock.notifyAll()
        }
    }

    fun dialogActive(ctx: Context): Boolean =
        synchronized(lock) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean("dialog_active", false)
        }

    /**
     * Long-poll helper for `GET /utterance?since=<id>&timeout=<ms>`.
     * Blocks until a newer utterance (`id > sinceId`) is recorded, OR `dialog_active`
     * becomes false (call ended), OR [timeoutMs] elapses.
     */
    fun waitForUpdate(ctx: Context, sinceId: Long, timeoutMs: Long): Pair<Utterance?, Boolean> {
        val clamped = timeoutMs.coerceIn(0L, 55_000L)
        val deadline = System.currentTimeMillis() + clamped
        synchronized(lock) {
            while (true) {
                val active = dialogActive(ctx)
                val utt = latestUtterance(ctx)
                if ((utt != null && utt.id > sinceId) || !active) {
                    return Pair(utt, active)
                }
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L) {
                    return Pair(utt, active)
                }
                try {
                    lock.wait(remaining)
                } catch (e: InterruptedException) {
                    return Pair(latestUtterance(ctx), dialogActive(ctx))
                }
            }
        }
    }
}

package dev.muse.fakecall

import android.content.Context
import java.security.SecureRandom

/** SharedPreferences-backed settings shared by the UI, services, receivers and scheduler. */
object Store {
    private const val PREFS = "fakecall"
    const val DEFAULT_MESSAGE = "おはよう。朝だよ、起きて。"
    const val DEFAULT_CALLER = "muse"
    const val DEFAULT_TTS_URL = "http://100.64.0.1:8793"
    const val DEFAULT_FILLER_DELAY_MS = 1800L

    fun message(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("message", DEFAULT_MESSAGE) ?: DEFAULT_MESSAGE

    fun caller(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("caller_name", DEFAULT_CALLER) ?: DEFAULT_CALLER

    /** Absolute path (app-private storage) of the user-picked answer audio, if any. */
    fun customAudioPath(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("custom_audio_path", null)

    /** Original display name of the picked file, for the settings UI. */
    fun customAudioName(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("custom_audio_name", null)

    fun saveCustomAudio(ctx: Context, path: String, name: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("custom_audio_path", path)
            .putString("custom_audio_name", name)
            .apply()
    }

    fun clearCustomAudio(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("custom_audio_path")
            .remove("custom_audio_name")
            .apply()
    }

    fun saveMessage(ctx: Context, msg: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("message", msg).apply()
    }

    fun saveSchedule(ctx: Context, hour: Int, minute: Int, daily: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("sched_hour", hour)
            .putInt("sched_minute", minute)
            .putBoolean("sched_daily", daily)
            .putBoolean("sched_on", true)
            .apply()
    }

    fun clearSchedule(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("sched_on", false).apply()
    }

    fun scheduleOn(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("sched_on", false)

    fun scheduleHour(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("sched_hour", 7)

    fun scheduleMinute(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("sched_minute", 30)

    fun scheduleDaily(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("sched_daily", true)

    // ---------- Tailscale call server & PC TTS ----------

    /**
     * Shared secret for the CallServerService HTTP endpoints. Generated once
     * (36 hex chars from SecureRandom) and kept in SharedPreferences.
     */
    fun serverToken(ctx: Context): String {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString("server_token", null)?.let { return it }
        val bytes = ByteArray(18)
        SecureRandom().nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString("server_token", token).apply()
        return token
    }

    /** Whether the user has turned the Tailscale receive-server on. Default OFF. */
    fun serverEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("server_enabled", false)

    fun setServerEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("server_enabled", on).apply()
    }

    /** Base URL of the local/tailnet Irodori-TTS server (default PC over Tailscale). */
    fun ttsServerUrl(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("tts_server_url", DEFAULT_TTS_URL)
            ?.trim()
            ?.trimEnd('/')
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_TTS_URL

    fun setTtsServerUrl(ctx: Context, url: String) {
        val clean = url.trim().trimEnd('/').ifEmpty { DEFAULT_TTS_URL }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("tts_server_url", clean).apply()
    }

    /** Whether calls start with speakerphone ON (default false = earpiece like a real phone). */
    fun defaultSpeakerOn(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("default_speaker_on", false)

    fun setDefaultSpeakerOn(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("default_speaker_on", on).apply()
    }

    /** Delay (ms) before playing a backchannel filler while waiting for Muse's reply. */
    fun fillerDelayMs(ctx: Context): Long =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong("filler_delay_ms", DEFAULT_FILLER_DELAY_MS)
            .coerceIn(0L, 10_000L)

    fun setFillerDelayMs(ctx: Context, ms: Long) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong("filler_delay_ms", ms.coerceIn(0L, 10_000L)).apply()
    }

    // ---------- morning retry guard ----------

    @Synchronized
    fun claimRetry(ctx: Context, session: Long): Boolean {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getLong("retry_for_session", -1L) == session) return false
        prefs.edit().putLong("retry_for_session", session).commit()
        return true
    }

    fun setActiveRingSession(ctx: Context, session: Long) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong("active_ring_session", session).apply()
    }

    fun activeRingSession(ctx: Context): Long =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong("active_ring_session", -1L)

    fun clearActiveRingSession(ctx: Context, session: Long) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getLong("active_ring_session", -1L) == session) {
            prefs.edit().remove("active_ring_session").apply()
        }
    }
}

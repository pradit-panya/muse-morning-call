package dev.muse.fakecall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Single unified entry point that starts a fake incoming call on the custom UI path.
 *
 * v1.0.0: Managed Telecom (`ConnectionService`) has been removed so every call —
 * including scheduled morning calls — supports full two-way dialogue seamlessly
 * as soon as the user answers.
 */
object CallStarter {
    const val CHANNEL_ID = "incoming_call"
    const val ONGOING_CHANNEL_ID = "ongoing_call"
    const val NOTIF_ID = 1001

    const val EXTRA_MESSAGE = "message"
    const val EXTRA_CALLER = "caller_name"
    const val EXTRA_MORNING = "morning"
    const val EXTRA_ATTEMPT = "attempt"
    const val EXTRA_AUDIO_PATH = "audio_path"
    const val EXTRA_ANSWERED = "answered"
    const val EXTRA_OUTGOING = "outgoing"
    const val EXTRA_DIALOG = "dialog"
    const val EXTRA_SESSION = "session_id"

    /** Starts a user-initiated outgoing call to Muse (opens CallActivity in DIALING state). */
    fun startOutgoingCall(ctx: Context, callerName: String = Store.caller(ctx)): Long {
        val appCtx = ctx.applicationContext
        ensureChannels(appCtx)
        val session = System.currentTimeMillis()
        PcmAudioPlayer.preloadFillersAsync(appCtx)
        val intent = Intent(appCtx, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CALLER, callerName)
            putExtra(EXTRA_MESSAGE, "")
            putExtra(EXTRA_MORNING, false)
            putExtra(EXTRA_ATTEMPT, 1)
            putExtra(EXTRA_SESSION, session)
            putExtra(EXTRA_ANSWERED, false)
            putExtra(EXTRA_OUTGOING, true)
            putExtra(EXTRA_DIALOG, true)
        }
        appCtx.startActivity(intent)
        return session
    }

    fun startCall(
        ctx: Context,
        message: String,
        callerName: String,
        morning: Boolean,
        attempt: Int,
        audioPath: String? = null,
        dialog: Boolean = true
    ): Long {
        val appCtx = ctx.applicationContext
        ensureChannels(appCtx)
        val session = System.currentTimeMillis()
        PcmAudioPlayer.preloadFillersAsync(appCtx)
        RingService.startRinging(
            appCtx, message, callerName, morning, attempt, audioPath, session, dialog
        )
        return session
    }

    /** Intent that opens CallActivity with the given call extras. */
    fun callActivityIntent(
        ctx: Context,
        message: String,
        callerName: String,
        morning: Boolean,
        attempt: Int,
        audioPath: String?,
        session: Long,
        answered: Boolean,
        dialog: Boolean = true
    ): Intent = Intent(ctx, CallActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(EXTRA_MESSAGE, message)
        putExtra(EXTRA_CALLER, callerName)
        putExtra(EXTRA_MORNING, morning)
        putExtra(EXTRA_ATTEMPT, attempt)
        if (audioPath != null) putExtra(EXTRA_AUDIO_PATH, audioPath)
        putExtra(EXTRA_SESSION, session)
        putExtra(EXTRA_ANSWERED, answered)
        putExtra(EXTRA_DIALOG, dialog)
    }

    /**
     * Schedules the next morning retry in 5 minutes iff this is a morning call,
     * attempts remain, and no other component has already claimed this session's retry.
     */
    fun scheduleRetryOnce(
        ctx: Context,
        session: Long,
        message: String,
        callerName: String,
        morning: Boolean,
        attempt: Int
    ): Boolean {
        if (!morning || attempt >= Scheduler.MAX_ATTEMPTS) return false
        if (!Store.claimRetry(ctx, session)) return false
        Scheduler.scheduleRetry(ctx, message, callerName, attempt + 1)
        return true
    }

    fun cancelNotification(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
    }

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                CHANNEL_ID, "着信", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Muse からの着信"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null)
            }
            nm.createNotificationChannel(ch)
        }
        if (nm.getNotificationChannel(ONGOING_CHANNEL_ID) == null) {
            val ongoing = NotificationChannel(
                ONGOING_CHANNEL_ID, "通話中", NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Muse と通話中の通知"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null)
                enableVibration(false)
            }
            nm.createNotificationChannel(ongoing)
        }
    }
}

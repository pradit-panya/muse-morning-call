package dev.muse.fakecall

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log

/**
 * Owns both foreground notification phases of a call:
 *
 * 1. **Ringing phase (`ACTION_START_RING`)**:
 *    Plays the ringtone + HyperOS-safe repeating vibration, posts a native
 *    `Notification.CallStyle.forIncomingCall` (API 31+) with full-screen intent
 *    to [CallActivity] and direct Activity PendingIntent for 応答 (avoiding the
 *    Android 12+ broadcast trampoline delay), and enforces the 60 s ring timeout.
 *
 * 2. **Ongoing-call phase (`ACTION_ENTER_ONGOING`)**:
 *    Immediately stops ringtone/vibration and transitions the foreground
 *    notification into `Notification.CallStyle.forOngoingCall` with a live
 *    chronometer timer and red 通話終了 button. Keeping this foreground service
 *    alive prevents HyperOS from throttling/killing the call when the proximity
 *    sensor turns the screen off against the user's ear.
 */
class RingService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var message: String = Store.DEFAULT_MESSAGE
    private var callerName: String = Store.DEFAULT_CALLER
    private var morning: Boolean = false
    private var attempt: Int = 1
    private var audioPath: String? = null
    private var session: Long = 0L
    private var dialog: Boolean = true
    private var ringing = false
    private var ongoing = false

    private val timeoutRunnable = Runnable { onRingTimeout() }

    /** Re-triggers a one-shot buzz every 2 s (works around HyperOS waveform repeat bug). */
    private val vibrateRunnable = object : Runnable {
        override fun run() {
            if (!ringing) return
            try {
                vibrator?.vibrate(
                    VibrationEffect.createOneShot(
                        VIBRATE_ON_MS, VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            } catch (e: Exception) { }
            handler.postDelayed(this, VIBRATE_CYCLE_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent.action == ACTION_ENTER_ONGOING) {
            enterOngoingPhase(intent)
            return START_NOT_STICKY
        }

        // Default: ACTION_START_RING — a new call replaces any previous ringing state.
        stopRingingInternal()
        handler.removeCallbacks(timeoutRunnable)
        ongoing = false

        message = intent.getStringExtra(CallStarter.EXTRA_MESSAGE) ?: Store.message(this)
        callerName = intent.getStringExtra(CallStarter.EXTRA_CALLER) ?: Store.caller(this)
        morning = intent.getBooleanExtra(CallStarter.EXTRA_MORNING, false)
        attempt = intent.getIntExtra(CallStarter.EXTRA_ATTEMPT, 1)
        audioPath = intent.getStringExtra(CallStarter.EXTRA_AUDIO_PATH)
        session = intent.getLongExtra(CallStarter.EXTRA_SESSION, System.currentTimeMillis())
        dialog = intent.getBooleanExtra(CallStarter.EXTRA_DIALOG, true)

        CallStarter.ensureChannels(this)
        val notif = buildIncomingNotification()
        startForegroundCompat(notif)

        isRunning = true
        isRinging = true
        Store.setActiveRingSession(this, session)
        CallEventBus.updateState(
            state = CallEventBus.CallState.RINGING,
            phase = CallEventBus.DialogPhase.OFF,
            session = session,
            morning = morning,
            dialog = dialog
        )
        CallEventBus.emit(
            "CALL_RINGING",
            session,
            mapOf(
                "caller" to callerName,
                "morning" to morning,
                "dialog" to dialog,
                "attempt" to attempt
            )
        )
        startRingingInternal()
        handler.postDelayed(timeoutRunnable, RING_TIMEOUT_MS)
        return START_NOT_STICKY
    }

    private fun enterOngoingPhase(intent: Intent) {
        stopRingingInternal()
        handler.removeCallbacks(timeoutRunnable)
        isRinging = false
        Store.clearActiveRingSession(this, session)

        callerName = intent.getStringExtra(CallStarter.EXTRA_CALLER) ?: callerName
        session = intent.getLongExtra(CallStarter.EXTRA_SESSION, session)
        morning = intent.getBooleanExtra(CallStarter.EXTRA_MORNING, morning)
        dialog = intent.getBooleanExtra(CallStarter.EXTRA_DIALOG, dialog)
        ongoing = true
        isRunning = true

        CallStarter.ensureChannels(this)
        val notif = buildOngoingNotification()
        startForegroundCompat(notif)
    }

    private fun startForegroundCompat(notif: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                CallStarter.NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(CallStarter.NOTIF_ID, notif)
        }
    }

    override fun onDestroy() {
        isRunning = false
        isRinging = false
        ongoing = false
        Store.clearActiveRingSession(this, session)
        handler.removeCallbacks(timeoutRunnable)
        stopRingingInternal()
        CallStarter.cancelNotification(this)
        super.onDestroy()
    }

    // ---------- notifications ----------

    private fun buildCallerPerson(): Person {
        val builder = Person.Builder()
            .setName(callerName)
            .setImportant(true)
        buildAvatarIcon(callerName)?.let { builder.setIcon(it) }
        return builder.build()
    }

    private fun buildAvatarIcon(name: String): Icon? {
        return try {
            val resId = resources.getIdentifier("ic_launcher_round", "mipmap", packageName)
            if (resId != 0) Icon.createWithResource(this, resId) else null
        } catch (e: Exception) {
            null
        }
    }

    private fun buildIncomingNotification(): Notification {
        val activityIntent = CallStarter.callActivityIntent(
            this, message, callerName, morning, attempt, audioPath, session,
            answered = false, dialog = dialog
        )
        val activityPi = PendingIntent.getActivity(
            this, CallStarter.NOTIF_ID, activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val answerIntent = CallStarter.callActivityIntent(
            this, message, callerName, morning, attempt, audioPath, session,
            answered = true, dialog = dialog
        )
        val answerPi = PendingIntent.getActivity(
            this, answerRequestCode(session), answerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val declineIntent = Intent(this, CallActionReceiver::class.java).apply {
            action = CallActionReceiver.ACTION_DECLINE
            putCallExtras()
        }
        val declinePi = PendingIntent.getBroadcast(
            this, REQ_DECLINE, declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val subtitle = if (morning) "モーニングコール（$attempt 回目）" else "着信中…"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val style = Notification.CallStyle.forIncomingCall(
                    buildCallerPerson(), declinePi, answerPi
                )
                return Notification.Builder(this, CallStarter.CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.sym_call_incoming)
                    .setContentTitle(callerName)
                    .setContentText(subtitle)
                    .setCategory(Notification.CATEGORY_CALL)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setOngoing(true)
                    .setStyle(style)
                    .setContentIntent(activityPi)
                    .setFullScreenIntent(activityPi, true)
                    .build()
            } catch (e: Exception) {
                Log.w("FakeCallDbg", "CallStyle.forIncomingCall fallback", e)
            }
        }

        return Notification.Builder(this, CallStarter.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(callerName)
            .setContentText(subtitle)
            .setCategory(Notification.CATEGORY_CALL)
            .setPriority(Notification.PRIORITY_MAX)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(activityPi)
            .setFullScreenIntent(activityPi, true)
            .addAction(android.R.drawable.sym_action_call, "応答", answerPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "拒否", declinePi)
            .build()
    }

    private fun buildOngoingNotification(): Notification {
        val openIntent = CallStarter.callActivityIntent(
            this, message, callerName, morning, attempt, null, session,
            answered = true, dialog = dialog
        )
        val openPi = PendingIntent.getActivity(
            this, REQ_OPEN_ONGOING, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val hangupIntent = Intent(this, CallActionReceiver::class.java).apply {
            action = CallActionReceiver.ACTION_HANGUP
        }
        val hangupPi = PendingIntent.getBroadcast(
            this, REQ_HANGUP, hangupIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val now = System.currentTimeMillis()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val style = Notification.CallStyle.forOngoingCall(
                    buildCallerPerson(), hangupPi
                )
                return Notification.Builder(this, CallStarter.ONGOING_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_phone_call)
                    .setContentTitle(callerName)
                    .setContentText("通話中")
                    .setCategory(Notification.CATEGORY_CALL)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setOngoing(true)
                    .setWhen(now)
                    .setUsesChronometer(true)
                    .setStyle(style)
                    .setContentIntent(openPi)
                    .build()
            } catch (e: Exception) {
                Log.w("FakeCallDbg", "CallStyle.forOngoingCall fallback", e)
            }
        }

        return Notification.Builder(this, CallStarter.ONGOING_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle(callerName)
            .setContentText("通話中")
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setWhen(now)
            .setUsesChronometer(true)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "通話終了", hangupPi)
            .build()
    }

    private fun answerRequestCode(session: Long): Int {
        var code = (session % Int.MAX_VALUE).toInt()
        if (code == CallStarter.NOTIF_ID || code == REQ_OPEN_ONGOING) code += 2
        return code
    }

    private fun Intent.putCallExtras() {
        putExtra(CallStarter.EXTRA_MESSAGE, message)
        putExtra(CallStarter.EXTRA_CALLER, callerName)
        putExtra(CallStarter.EXTRA_MORNING, morning)
        putExtra(CallStarter.EXTRA_ATTEMPT, attempt)
        if (audioPath != null) putExtra(CallStarter.EXTRA_AUDIO_PATH, audioPath)
        putExtra(CallStarter.EXTRA_SESSION, session)
        putExtra(CallStarter.EXTRA_DIALOG, dialog)
    }

    // ---------- ringing ----------

    private fun startRingingInternal() {
        ringing = true
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
                // USAGE_ALARM ensures morning calls and incoming triggers ring even in manner mode.
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    isLooping = true
                }
                play()
            }
        } catch (e: Exception) {
            // No ringtone available; vibration still runs.
        }
        try {
            vibrator = getSystemService(Vibrator::class.java)
            handler.removeCallbacks(vibrateRunnable)
            handler.post(vibrateRunnable)
        } catch (e: Exception) { }
        try {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "dev.muse.fakecall:ring"
            )?.apply { acquire(RING_TIMEOUT_MS + 30_000L) }
        } catch (e: Exception) { }
    }

    private fun stopRingingInternal() {
        ringing = false
        handler.removeCallbacks(vibrateRunnable)
        try { ringtone?.stop() } catch (e: Exception) { }
        ringtone = null
        try { vibrator?.cancel() } catch (e: Exception) { }
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) { }
        wakeLock = null
    }

    private fun onRingTimeout() {
        if (!ringing) return
        stopRingingInternal()
        isRinging = false
        CallStarter.scheduleRetryOnce(this, session, message, callerName, morning, attempt)
        CallEventBus.updateState(
            state = CallEventBus.CallState.IDLE,
            phase = CallEventBus.DialogPhase.OFF
        )
        CallEventBus.emit("CALL_ENDED", session, mapOf("reason" to "no_answer"))
        stopSelf()
    }

    companion object {
        private const val ACTION_START_RING = "dev.muse.fakecall.START_RING"
        private const val ACTION_ENTER_ONGOING = "dev.muse.fakecall.ENTER_ONGOING"
        private const val RING_TIMEOUT_MS = 60_000L
        private const val REQ_DECLINE = 1004
        private const val REQ_HANGUP = 1005
        private const val REQ_OPEN_ONGOING = 1006
        private const val VIBRATE_ON_MS = 1000L
        private const val VIBRATE_CYCLE_MS = 2000L

        @Volatile var isRunning: Boolean = false
            private set
        @Volatile var isRinging: Boolean = false
            private set

        fun startRinging(
            ctx: Context,
            message: String,
            callerName: String,
            morning: Boolean,
            attempt: Int,
            audioPath: String?,
            session: Long,
            dialog: Boolean = true
        ) {
            val intent = Intent(ctx, RingService::class.java).apply {
                action = ACTION_START_RING
                putExtra(CallStarter.EXTRA_MESSAGE, message)
                putExtra(CallStarter.EXTRA_CALLER, callerName)
                putExtra(CallStarter.EXTRA_MORNING, morning)
                putExtra(CallStarter.EXTRA_ATTEMPT, attempt)
                if (audioPath != null) putExtra(CallStarter.EXTRA_AUDIO_PATH, audioPath)
                putExtra(CallStarter.EXTRA_SESSION, session)
                putExtra(CallStarter.EXTRA_DIALOG, dialog)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        /** Stops ringing and switches to the ongoing-call foreground notification. */
        fun enterOngoing(ctx: Context, callerName: String, session: Long, morning: Boolean, dialog: Boolean) {
            val intent = Intent(ctx, RingService::class.java).apply {
                action = ACTION_ENTER_ONGOING
                putExtra(CallStarter.EXTRA_CALLER, callerName)
                putExtra(CallStarter.EXTRA_SESSION, session)
                putExtra(CallStarter.EXTRA_MORNING, morning)
                putExtra(CallStarter.EXTRA_DIALOG, dialog)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
            } catch (e: Exception) {
                Log.w("FakeCallDbg", "RingService.enterOngoing failed", e)
            }
        }

        fun stopAll(ctx: Context) {
            if (!isRunning) return
            try {
                ctx.stopService(Intent(ctx, RingService::class.java))
            } catch (e: Exception) { }
        }
    }
}

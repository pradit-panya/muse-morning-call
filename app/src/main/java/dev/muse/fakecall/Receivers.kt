package dev.muse.fakecall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * External trigger entry point (fireable via adb):
 *   adb shell am broadcast -a dev.muse.fakecall.TRIGGER \
 *     --es message "おはよう" --es caller_name "Muse" --ez morning true --ez dialog true
 */
class TriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val message = intent.getStringExtra("message") ?: Store.message(context)
        val caller = intent.getStringExtra("caller_name") ?: Store.caller(context)
        val morning = intent.getBooleanExtra("morning", false)
        val dialog = intent.getBooleanExtra("dialog", true)
        CallStarter.startCall(context, message, caller, morning, attempt = 1, dialog = dialog)
    }
}

/**
 * After a reboot or in-place APK update, restart the Tailscale receive-server
 * (if enabled) AND re-arm the local morning schedule.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        if (Store.serverEnabled(context)) {
            CallServerService.start(context)
        }
        if (Store.scheduleOn(context)) {
            val at = Scheduler.nextTriggerMillis(
                Store.scheduleHour(context), Store.scheduleMinute(context)
            )
            Scheduler.scheduleLocal(context, at)
        }
    }
}

/**
 * Notification actions posted by [RingService]:
 * - ACTION_DECLINE: rejects a ringing call without launching UI.
 * - ACTION_HANGUP: ends an active call from the ongoing-call CallStyle notification.
 *
 * (The Answer button uses a direct Activity PendingIntent to CallActivity to
 * avoid the Android 12+ broadcast-receiver trampoline BAL delay.)
 */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_DECLINE -> {
                val message = intent.getStringExtra(CallStarter.EXTRA_MESSAGE)
                    ?: Store.message(context)
                val caller = intent.getStringExtra(CallStarter.EXTRA_CALLER)
                    ?: Store.caller(context)
                val morning = intent.getBooleanExtra(CallStarter.EXTRA_MORNING, false)
                val attempt = intent.getIntExtra(CallStarter.EXTRA_ATTEMPT, 1)
                val session = intent.getLongExtra(CallStarter.EXTRA_SESSION, 0L)

                CallStarter.scheduleRetryOnce(
                    context, session, message, caller, morning, attempt
                )
                RingService.stopAll(context)
                CallStarter.cancelNotification(context)
                CallEventBus.updateState(
                    state = CallEventBus.CallState.IDLE,
                    phase = CallEventBus.DialogPhase.OFF
                )
                CallEventBus.emit("CALL_ENDED", session, mapOf("reason" to "declined"))
            }
            ACTION_HANGUP -> {
                CallRuntime.requestHangup(context, "user_hangup")
            }
        }
    }

    companion object {
        const val ACTION_DECLINE = "dev.muse.fakecall.ACTION_DECLINE"
        const val ACTION_HANGUP = "dev.muse.fakecall.ACTION_HANGUP"
    }
}

/**
 * Fired by AlarmManager: local morning schedule and 5-minute retries.
 * Morning calls always enter dialogue mode (`dialog = true`) so answering
 * plays the cached morning greeting immediately and then listens for replies.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Scheduler.ACTION_SCHEDULED -> {
                CallStarter.startCall(
                    context,
                    Store.message(context),
                    Store.caller(context),
                    morning = true,
                    attempt = 1,
                    dialog = true
                )
                if (Store.scheduleOn(context) && Store.scheduleDaily(context)) {
                    Scheduler.scheduleLocal(
                        context,
                        Scheduler.nextTriggerMillis(
                            Store.scheduleHour(context), Store.scheduleMinute(context)
                        )
                    )
                }
            }
            Scheduler.ACTION_RETRY -> {
                val message = intent.getStringExtra(CallStarter.EXTRA_MESSAGE)
                    ?: Store.message(context)
                val caller = intent.getStringExtra(CallStarter.EXTRA_CALLER)
                    ?: Store.caller(context)
                val attempt = intent.getIntExtra(CallStarter.EXTRA_ATTEMPT, 2)
                CallStarter.startCall(
                    context, message, caller, morning = true, attempt = attempt, dialog = true
                )
            }
        }
    }
}

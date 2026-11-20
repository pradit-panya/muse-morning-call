package dev.muse.fakecall

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/**
 * AlarmManager scheduling for local morning calls and 5-minute retries.
 * Uses `setAlarmClock` when exact alarms are permitted so HyperOS / Android 16
 * Doze mode wakes the phone on the exact second with maximum reliability.
 */
object Scheduler {
    const val ACTION_SCHEDULED = "dev.muse.fakecall.SCHEDULED_CALL"
    const val ACTION_RETRY = "dev.muse.fakecall.RETRY_CALL"
    private const val REQ_SCHEDULE = 2001
    private const val REQ_RETRY = 2002
    private const val REQ_SHOW = 2003
    const val RETRY_DELAY_MS = 5 * 60 * 1000L
    const val MAX_ATTEMPTS = 3

    fun canExact(ctx: Context): Boolean {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            am.canScheduleExactAlarms()
    }

    /** Next occurrence of hour:minute (today if still ahead, else tomorrow). */
    fun nextTriggerMillis(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.timeInMillis <= now.timeInMillis) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }
        return target.timeInMillis
    }

    fun scheduleLocal(ctx: Context, triggerAt: Long) {
        val intent = Intent(ctx, AlarmReceiver::class.java).apply {
            action = ACTION_SCHEDULED
        }
        val pi = PendingIntent.getBroadcast(
            ctx, REQ_SCHEDULE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        setAlarmReliable(ctx, triggerAt, pi)
    }

    fun cancelLocal(ctx: Context) {
        val intent = Intent(ctx, AlarmReceiver::class.java).apply {
            action = ACTION_SCHEDULED
        }
        val pi = PendingIntent.getBroadcast(
            ctx, REQ_SCHEDULE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        ctx.getSystemService(AlarmManager::class.java)?.cancel(pi)
        pi.cancel()
    }

    fun scheduleRetry(ctx: Context, message: String, callerName: String, nextAttempt: Int) {
        val intent = Intent(ctx, AlarmReceiver::class.java).apply {
            action = ACTION_RETRY
            putExtra(CallStarter.EXTRA_MESSAGE, message)
            putExtra(CallStarter.EXTRA_CALLER, callerName)
            putExtra(CallStarter.EXTRA_ATTEMPT, nextAttempt)
        }
        val pi = PendingIntent.getBroadcast(
            ctx, REQ_RETRY, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        setAlarmReliable(ctx, System.currentTimeMillis() + RETRY_DELAY_MS, pi)
    }

    private fun setAlarmReliable(ctx: Context, triggerAt: Long, pi: PendingIntent) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        try {
            if (canExact(ctx)) {
                val showIntent = Intent(ctx, MainActivity::class.java)
                val showPi = PendingIntent.getActivity(
                    ctx, REQ_SHOW, showIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showPi), pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (e: SecurityException) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } catch (ignored: Exception) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        }
    }
}

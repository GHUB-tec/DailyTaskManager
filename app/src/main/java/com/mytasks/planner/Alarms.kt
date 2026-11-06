package com.mytasks.planner

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate

const val EXTRA_TASK_ID = "task_id"
const val EXTRA_KIND = "kind"
const val EXTRA_MINUTES = "minutes"

/**
 * Schedules AlarmManager alarms for a task. Alarm "kinds":
 *  0 = start, 1 = deadline, 2 = overdue nag, 10..14 = reminders before start, 15 = snooze.
 * PendingIntent request code = taskId * 20 + kind, so every alarm is unique and cancellable.
 */
object AlarmScheduler {
    val REMINDER_OPTIONS = listOf(5, 10, 15, 30, 60)
    const val KIND_START = 0
    const val KIND_DEADLINE = 1
    const val KIND_OVERDUE = 2
    const val KIND_REMINDER_BASE = 10
    const val KIND_SNOOZE = 15
    const val OVERDUE_NAG_MIN = 15
    const val SNOOZE_MIN = 10
    private const val SCHEDULE_WINDOW_DAYS = 7L

    fun canExact(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    private fun pending(ctx: Context, taskId: Long, kind: Int, minutes: Int, flags: Int): PendingIntent? {
        val i = Intent(ctx, AlarmReceiver::class.java)
            .putExtra(EXTRA_TASK_ID, taskId)
            .putExtra(EXTRA_KIND, kind)
            .putExtra(EXTRA_MINUTES, minutes)
        return PendingIntent.getBroadcast(ctx, (taskId * 20 + kind).toInt(), i, flags or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun setAt(ctx: Context, taskId: Long, kind: Int, minutes: Int, at: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pending(ctx, taskId, kind, minutes, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        try {
            if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancel(ctx: Context, taskId: Long, includeSnooze: Boolean = true) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val last = if (includeSnooze) KIND_SNOOZE else KIND_SNOOZE - 1
        for (kind in 0..last) {
            val pi = pending(ctx, taskId, kind, 0, PendingIntent.FLAG_NO_CREATE)
            if (pi != null) {
                am.cancel(pi)
                pi.cancel()
            }
        }
    }

    /** (Re)schedules every alarm for one task. Safe to call repeatedly. */
    fun schedule(ctx: Context, t: TaskEntity) {
        cancel(ctx, t.id, includeSnooze = false)
        if (!t.isOpen()) return
        val now = System.currentTimeMillis()
        val start = t.startMillis()
        val end = t.endMillis()

        if (start > now) setAt(ctx, t.id, AlarmScheduler.KIND_START, 0, start)
        for (m in t.reminderList()) {
            val idx = REMINDER_OPTIONS.indexOf(m)
            val at = start - m * 60_000L
            if (idx >= 0 && at > now) setAt(ctx, t.id, KIND_REMINDER_BASE + idx, m, at)
        }
        if (end > now) setAt(ctx, t.id, KIND_DEADLINE, 0, end)
        val nag = end + OVERDUE_NAG_MIN * 60_000L
        if (nag > now) setAt(ctx, t.id, KIND_OVERDUE, 0, nag)
    }

    fun snooze(ctx: Context, taskId: Long) {
        setAt(ctx, taskId, KIND_SNOOZE, 0, System.currentTimeMillis() + SNOOZE_MIN * 60_000L)
    }

    /** Used after reboot / app update / time change / daily refresh. */
    suspend fun scheduleAll(ctx: Context, dao: TaskDao) {
        val today = LocalDate.now().toEpochDay()
        dao.openBetween(today - 1, today + SCHEDULE_WINDOW_DAYS).forEach { schedule(ctx, it) }
    }
}

object Notifier {
    const val CHANNEL_ID = "task_alarms_v1"
    const val ACTION_DONE = "com.mytasks.planner.DONE"
    const val ACTION_SNOOZE = "com.mytasks.planner.SNOOZE"
    const val ACTION_DISMISS = "com.mytasks.planner.DISMISS"

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val channel = NotificationChannel(CHANNEL_ID, "Task alarms", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Start, reminder, deadline and overdue alarms for your tasks"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 600)
            setSound(
                sound,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(channel)
    }

    fun dismiss(ctx: Context, taskId: Long) {
        ctx.getSystemService(NotificationManager::class.java).cancel(taskId.toInt())
    }

    fun show(ctx: Context, t: TaskEntity, kind: Int, minutes: Int) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(ctx)

        val timeRange = "${formatMinute(t.startMinute)} – ${formatMinute(t.endMinute)}"
        val (title, text) = when {
            kind == AlarmScheduler.KIND_START -> "Time to start: ${t.title}" to "Scheduled $timeRange"
            kind == AlarmScheduler.KIND_DEADLINE ->
                (if (t.status == Status.RUNNING) "Time's up (timer still running): ${t.title}" else "Time's up: ${t.title}") to
                    "The deadline ${formatMinute(t.endMinute)} has arrived."
            kind == AlarmScheduler.KIND_OVERDUE -> "Overdue: ${t.title}" to "This task was due at ${formatMinute(t.endMinute)}."
            kind == AlarmScheduler.KIND_SNOOZE -> "Reminder: ${t.title}" to "Snoozed reminder · $timeRange"
            else -> "Starting in $minutes min: ${t.title}" to "Scheduled $timeRange"
        }

        val openApp = PendingIntent.getActivity(
            ctx, t.id.toInt(),
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        fun action(name: String, n: Int): PendingIntent = PendingIntent.getBroadcast(
            ctx, (t.id * 20 + 16 + n).toInt(),
            Intent(ctx, ActionReceiver::class.java).setAction(name).putExtra(EXTRA_TASK_ID, t.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .addAction(0, "Done", action(ACTION_DONE, 0))
            .addAction(0, "Snooze ${AlarmScheduler.SNOOZE_MIN}m", action(ACTION_SNOOZE, 1))
            .addAction(0, "Dismiss", action(ACTION_DISMISS, 2))
            .build()

        try {
            ctx.getSystemService(NotificationManager::class.java).notify(t.id.toInt(), n)
        } catch (e: SecurityException) {
            // permission revoked in the meantime: nothing we can do
        }
    }
}

/** Fires when an AlarmManager alarm goes off, even if the app is closed. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        val kind = intent.getIntExtra(EXTRA_KIND, 0)
        val minutes = intent.getIntExtra(EXTRA_MINUTES, 0)
        if (id < 0) return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val t = AppDatabase.get(app).dao().getTask(id)
                if (t != null && t.isOpen()) Notifier.show(app, t, kind, minutes)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Handles the Done / Snooze / Dismiss buttons on the notification. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        if (id < 0) return
        val app = context.applicationContext
        val action = intent.action
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    Notifier.ACTION_DONE -> (app as TaskApp).repo.complete(id)
                    Notifier.ACTION_SNOOZE -> AlarmScheduler.snooze(app, id)
                }
                Notifier.dismiss(app, id)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Restores alarms after reboot, app update, clock or time-zone change. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                (app as TaskApp).repo.refresh()
            } finally {
                pending.finish()
            }
        }
    }
}

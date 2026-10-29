package com.example.widget

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.ml.RhythmTarget
import com.example.data.model.TimeFormat
import com.example.data.model.TrackedItem
import com.example.data.model.formatMinutes
import com.example.data.repository.BabyBuddyRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Feed due", "diaper change due", "awake for a while" and "asleep for a while" reminders (Settings → Notifications).
 * An alarm fires at the due time; the receiver refreshes from the server first, so a feed, change or sleep logged
 * on another phone cancels it. With a heads-up set ([ReminderSettings.earlyMinutes]) an alarm also fires that long
 * before, posting "due in 10 min", which the due notification later replaces. Each stage fires once per feed /
 * change / wake-up / sleep ("anchor").
 */
object Reminders {

    const val KIND_FEED = "feed"
    const val KIND_AWAKE = "awake"
    const val KIND_SLEEP = "sleep"
    const val KIND_DIAPER = "diaper"
    private val KINDS = listOf(KIND_FEED, KIND_AWAKE, KIND_SLEEP, KIND_DIAPER)

    private const val CHANNEL_ID = "reminders"
    private const val PREFS = "reminders"
    private const val MISSED_GRACE_MS = 2 * 60 * 60_000L // a reminder missed by more than this is skipped
    private const val EARLY_ALARM_SLACK_MS = 60_000L // an alarm a little early still counts as on time

    /**
     * (anchor, due time): the last feed/change/wake-up/sleep start and when to remind, or null when off or not
     * applicable. A model picked in Trends → Models for that rhythm sets the due time instead of the fixed interval.
     */
    private fun due(kind: String): Pair<Long, Long>? {
        val repo = BabyBuddyRepository.instance
        val settings = repo.reminders.value
        val hidden = repo.hiddenItems.value
        return when (kind) {
            KIND_FEED -> {
                if (settings.feedAfterMinutes <= 0 || repo.liveTimerState.value.isRunning) return null
                if (TrackedItem.BREASTFEEDING in hidden && TrackedItem.BOTTLE in hidden) return null
                val last = repo.lastFeedMillis.value ?: return null
                last to (repo.modelDue(RhythmTarget.FEED_GAP, last) ?: (last + settings.feedAfterMinutes * 60_000L))
            }

            KIND_DIAPER -> {
                if (settings.diaperAfterMinutes <= 0 || TrackedItem.DIAPERS in hidden) return null
                val last = repo.lastDiaper.value?.timeMillis ?: return null
                last to (repo.modelDue(RhythmTarget.DIAPER_GAP, last) ?: (last + settings.diaperAfterMinutes * 60_000L))
            }

            KIND_SLEEP -> {
                // Long sleep: counts from the start of the sleep being timed (on any phone)
                val sleep = repo.sleepTimer.value
                if (settings.sleepAfterMinutes <= 0 || !sleep.isRunning || TrackedItem.SLEEP in hidden) return null
                sleep.startMillis to (repo.modelDue(RhythmTarget.SLEEP_LENGTH, sleep.startMillis) ?: (sleep.startMillis + settings.sleepAfterMinutes * 60_000L))
            }

            else -> {
                if (settings.awakeAfterMinutes <= 0 || repo.sleepTimer.value.isRunning) return null
                if (TrackedItem.SLEEP in hidden) return null
                val woke = repo.lastSleepEndMillis.value ?: return null
                woke to (repo.modelDue(RhythmTarget.AWAKE_WINDOW, woke) ?: (woke + settings.awakeAfterMinutes * 60_000L))
            }
        }
    }

    /** Sets (or clears) every reminder's alarm from the current entries and settings. */
    fun schedule(context: Context) {
        KINDS.forEach { schedule(context, it) }
    }

    /** Posts whatever stage is due by [now] (missed by less than the grace period), else sets the alarm for the next one. */
    private fun schedule(context: Context, kind: String, now: Long = System.currentTimeMillis()) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = alarmIntent(context, kind)
        val due = due(kind)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // A shown reminder about an older feed/change/wake-up is out of date
        if (prefs.getLong("shown_$kind", -1L).let { it >= 0 && it != due?.first }) {
            NotificationManagerCompat.from(context).cancel(notificationId(kind))
            prefs.edit().remove("shown_$kind").apply()
        }
        if (due == null || prefs.getLong("done_$kind", -1L) == due.first) {
            alarms.cancel(pending)
            return
        }
        val (anchor, dueAt) = due
        val earlyMs = BabyBuddyRepository.instance.reminders.value.earlyMinutes * 60_000L
        val earlyAt = dueAt - earlyMs
        val earlyPending = earlyMs > 0 && earlyAt > anchor && prefs.getLong("early_$kind", -1L) != anchor
        when {
            dueAt <= now -> {
                alarms.cancel(pending)
                if (now - dueAt < MISSED_GRACE_MS) post(context, kind, anchor, dueAt)
            }

            earlyPending && earlyAt <= now -> {
                post(context, kind, anchor, dueAt)
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dueAt, pending)
            }

            // Inexact is fine for a reminder, and needs no exact-alarm permission
            else -> alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, if (earlyPending) earlyAt else dueAt, pending)
        }
    }

    /** Alarm fired: post if still due (after a refresh), else reschedule. */
    internal suspend fun onAlarm(context: Context, kind: String) {
        withTimeoutOrNull(20_000L) {
            BabyBuddyRepository.instance.refresh()
            BabyBuddyRepository.instance.updatePredictions()
        }
        schedule(context, kind, System.currentTimeMillis() + EARLY_ALARM_SLACK_MS)
    }

    /** The heads-up ("due in 10 min") while [dueAt] is still ahead, else the reminder itself. */
    private fun post(context: Context, kind: String, anchor: Long, dueAt: Long) {
        val now = System.currentTimeMillis()
        val early = dueAt - now > EARLY_ALARM_SLACK_MS
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putLong(if (early) "early_$kind" else "done_$kind", anchor).putLong("shown_$kind", anchor).apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(context)
        val minutes = ((now - anchor) / 60_000L).toInt()
        val inMinutes = formatMinutes(((dueAt - now + 59_999L) / 60_000L).toInt())
        val (title, text) = when (kind) {
            KIND_FEED -> (if (early) context.getString(R.string.reminder_feed_due_in, inMinutes) else context.getString(R.string.reminder_feed_due)) to
                context.getString(R.string.reminder_feed_text, formatMinutes(minutes), TimeFormat.clock(anchor))

            KIND_DIAPER -> (if (early) context.getString(R.string.reminder_diaper_due_in, inMinutes) else context.getString(R.string.reminder_diaper_due)) to
                context.getString(R.string.reminder_diaper_text, formatMinutes(minutes), TimeFormat.clock(anchor))

            KIND_SLEEP -> (if (early) context.getString(R.string.reminder_wake_up_in, inMinutes) else context.getString(R.string.reminder_asleep_for, formatMinutes(minutes))) to
                context.getString(R.string.reminder_fell_asleep_at, TimeFormat.clock(anchor))

            else -> (if (early) context.getString(R.string.reminder_nap_in, inMinutes) else context.getString(R.string.reminder_awake_for, formatMinutes(minutes))) to
                context.getString(if (early) R.string.reminder_woke_up_at else R.string.reminder_woke_up_at_nap, TimeFormat.clock(anchor))
        }
        val open = PendingIntent.getActivity(
            context,
            notificationId(kind),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(BabyBuddyQuickLogWidget.EXTRA_NAVIGATE_TO, if (kind == KIND_FEED || kind == KIND_DIAPER) "TODAY_DASHBOARD" else "SLEEP")
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val icon = when (kind) {
            KIND_FEED -> R.drawable.ic_widget_feed
            KIND_DIAPER -> R.drawable.ic_widget_diaper
            else -> R.drawable.ic_widget_sleep
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notificationId(kind), notification)
        } catch (_: SecurityException) {
            // Permission revoked; nothing to do.
        }
    }

    private fun notificationId(kind: String) = when (kind) {
        KIND_FEED -> 4301
        KIND_AWAKE -> 4302
        KIND_SLEEP -> 4303
        else -> 4304
    }

    private fun alarmIntent(context: Context, kind: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        notificationId(kind),
        Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_REMIND).putExtra(ReminderReceiver.EXTRA_KIND, kind),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_DEFAULT))
    }
}

/**
 * Alarms are cleared by a restart and an app update, and a clock change moves their due times, so this
 * sets them again. Starting the process also brings back the lock-screen timer notifications
 * ([com.example.BabyBuddyApplication] restores them on start).
 */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> Unit

            else -> return
        }
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Fresh entries first, so a feed logged on another phone meanwhile is counted
                withTimeoutOrNull(20_000L) {
                    BabyBuddyRepository.instance.refresh()
                    BabyBuddyRepository.instance.updatePredictions()
                }
                Reminders.schedule(context.applicationContext)
            } finally {
                result.finish()
            }
        }
    }
}

/** Receives reminder alarms. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REMIND) return
        val kind = intent.getStringExtra(EXTRA_KIND) ?: return
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Reminders.onAlarm(context.applicationContext, kind)
            } finally {
                result.finish()
            }
        }
    }

    companion object {
        const val ACTION_REMIND = "com.example.babybuddy.ACTION_REMIND"
        const val EXTRA_KIND = "kind"
    }
}

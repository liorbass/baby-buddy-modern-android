package com.example.widget

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.model.SleepTimerState
import com.example.data.model.TimeFormat

/** Ongoing, lock-screen-visible notification while a sleep is being timed, with a "Woke up" action. */
object SleepNotification {

    private const val CHANNEL_ID = "sleep_timer"
    const val NOTIFICATION_ID = 4202

    private const val REQUEST_CONTENT = 4220
    private const val REQUEST_STOP = 4221

    /** Shows the notification for a running sleep, or removes it when none runs or [enabled] is false. */
    fun update(context: Context, state: SleepTimerState, enabled: Boolean) {
        if (!enabled || !state.isRunning) {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, build(context, state))
        } catch (_: SecurityException) {
            // Permission revoked between the check and notify; nothing to do.
        }
    }

    /** The notification for a running sleep (also [TimerSyncService]'s foreground notification when no feed runs). */
    fun build(context: Context, state: SleepTimerState): Notification {
        ensureChannel(context)
        // The start never changes while it runs, so the system chronometer can count on its own
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_widget_sleep)
            .setContentTitle(context.getString(R.string.sleeping))
            .setContentText(context.getString(R.string.since_time, TimeFormat.clock(state.startMillis)))
            .setUsesChronometer(true)
            .setWhen(state.startMillis)
            .setShowWhen(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent(context))
            .addAction(0, context.getString(R.string.woke_up), stopIntent(context))
            .build()
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_sleep_timer), NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    private fun contentIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(BabyBuddyQuickLogWidget.EXTRA_NAVIGATE_TO, "SLEEP")
        }
        return PendingIntent.getActivity(
            context,
            REQUEST_CONTENT,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun stopIntent(context: Context): PendingIntent {
        val intent = Intent(context, WidgetActionReceiver::class.java).setAction(WidgetActionReceiver.ACTION_STOP_SLEEP)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_STOP,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}

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
import com.example.data.model.BreastSide
import com.example.data.model.LiveTimerState
import com.example.data.model.TimeFormat

/**
 * Ongoing, lock-screen-visible notification for a running or paused breastfeed,
 * with Left / Right / End actions handled by [WidgetActionReceiver].
 *
 * While a side runs, the system chronometer counts the total by itself, so the notification is only
 * re-posted when something changes (start, pause, side switch, edits), not every second.
 */
object FeedNotification {

    const val NOTIFICATION_ID = 4201
    private const val CHANNEL_ID = "feeding"

    private const val REQUEST_CONTENT = 4210
    private const val REQUEST_LEFT = 4211
    private const val REQUEST_RIGHT = 4212
    private const val REQUEST_END = 4213

    /** Shows/updates the notification for [state], or removes it when no feed is running or [enabled] is false. */
    fun update(context: Context, state: LiveTimerState, enabled: Boolean) {
        if (!enabled || !state.isRunning) {
            cancel(context)
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

    /** The notification for a running feed (also used as [TimerSyncService]'s foreground notification). */
    fun build(context: Context, state: LiveTimerState): Notification {
        ensureChannel(context)
        val running = !state.isPaused
        val title = when {
            !running -> context.getString(R.string.feed_paused)
            state.activeBreast == BreastSide.LEFT -> context.getString(R.string.feeding_left_side)
            else -> context.getString(R.string.feeding_right_side)
        }
        val leftLabel = context.getString(if (running && state.activeBreast == BreastSide.LEFT) R.string.pause_left else R.string.side_left)
        val rightLabel = context.getString(if (running && state.activeBreast == BreastSide.RIGHT) R.string.pause_right else R.string.side_right)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(if (running) R.drawable.ic_widget_play else R.drawable.ic_widget_pause)
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent(context))
            .addAction(0, leftLabel, actionIntent(context, WidgetActionReceiver.ACTION_TAP_LEFT, REQUEST_LEFT))
            .addAction(0, rightLabel, actionIntent(context, WidgetActionReceiver.ACTION_TAP_RIGHT, REQUEST_RIGHT))
            .addAction(0, context.getString(R.string.end), actionIntent(context, WidgetActionReceiver.ACTION_END_FEED, REQUEST_END))

        val started = if (state.sessionStartMillis > 0) context.getString(R.string.feed_started_suffix, TimeFormat.clock(state.sessionStartMillis)) else ""
        if (running) {
            // The chronometer counts the total; only the running side grows, so the other side's time stays exact
            val other = if (state.activeBreast == BreastSide.LEFT) context.getString(R.string.right_short_time, formatDuration(state.rightSeconds)) else context.getString(R.string.left_short_time, formatDuration(state.leftSeconds))
            builder
                .setUsesChronometer(true)
                .setWhen(System.currentTimeMillis() - state.totalSeconds * 1000L)
                .setShowWhen(true)
                .setContentText("$other$started")
        } else {
            builder
                .setUsesChronometer(false)
                .setShowWhen(false)
                .setSubText(formatDuration(state.totalSeconds))
                .setContentText(
                    context.getString(R.string.feed_paused_text, formatDuration(state.totalSeconds), formatDuration(state.leftSeconds), formatDuration(state.rightSeconds)) +
                        started
                )
        }
        return builder.build()
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_feed_timer), NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    private fun contentIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(BabyBuddyQuickLogWidget.EXTRA_NAVIGATE_TO, "ACTIVE_NURSING_TIMER")
        }
        return PendingIntent.getActivity(
            context,
            REQUEST_CONTENT,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun actionIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, WidgetActionReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun formatDuration(totalSec: Int): String {
        val s = totalSec.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }
}

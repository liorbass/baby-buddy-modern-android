package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.data.model.BreastSide
import com.example.data.model.dayAndTime
import com.example.data.repository.BabyBuddyRepository

class BabyBuddyTimerWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val repo = BabyBuddyRepository.instance
        val timer = repo.liveTimerState.value

        val lastFeed = repo.lastBreastfeed.value
        val lastSide = lastFeed?.lastSide
        val nextSide = repo.nextStartSide()
        val activeName = sideName(context, timer.activeBreast)
        val sessionLabel = when {
            !timer.isRunning -> lastSide?.let { context.getString(R.string.widget_last_side, sideName(context, it)) } ?: ""
            timer.isPaused -> context.getString(R.string.widget_paused_side, activeName)
            else -> "● $activeName"
        }
        val subtext = when {
            timer.isRunning -> context.getString(R.string.widget_on_side_started, activeName, timer.sessionStartedTime)

            lastFeed != null && lastSide != null ->
                context.getString(R.string.widget_last_ended_on, sideName(context, lastSide), dayAndTime(lastFeed.timeMillis, lastFeed.timeFormatted))

            else -> context.getString(R.string.widget_tap_start)
        }
        // The Chronometer ticks by itself from this base, so the widget only needs updating on start/pause/switch
        val base = SystemClock.elapsedRealtime() - timer.totalSeconds * 1000L

        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_timer)

            views.setChronometer(R.id.tv_timer_digits, base, null, timer.isRunning && !timer.isPaused)
            views.setTextViewText(R.id.tv_timer_subtext, subtext)
            views.setTextViewText(R.id.tv_timer_session_label, sessionLabel)

            // Pause / Resume / Start button state
            if (!timer.isRunning) {
                views.setTextViewText(R.id.tv_pause_play_label, context.getString(R.string.widget_start_side, sideName(context, nextSide)))
                views.setImageViewResource(R.id.iv_pause_play_icon, R.drawable.ic_widget_play)
            } else if (timer.isPaused) {
                views.setTextViewText(R.id.tv_pause_play_label, context.getString(R.string.resume))
                views.setImageViewResource(R.id.iv_pause_play_icon, R.drawable.ic_widget_play)
            } else {
                views.setTextViewText(R.id.tv_pause_play_label, context.getString(R.string.pause))
                views.setImageViewResource(R.id.iv_pause_play_icon, R.drawable.ic_widget_pause)
            }

            // 1. Pause/Resume Broadcast Intent
            val pauseIntent = Intent(context, WidgetActionReceiver::class.java).apply {
                action = WidgetActionReceiver.ACTION_TIMER_PAUSE_RESUME
            }
            val pausePendingIntent = PendingIntent.getBroadcast(
                context,
                REQ_PAUSE,
                pauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btn_timer_pause_play, pausePendingIntent)

            // 2. Switch Breast Broadcast Intent
            val switchIntent = Intent(context, WidgetActionReceiver::class.java).apply {
                action = WidgetActionReceiver.ACTION_TIMER_SWITCH_SIDE
            }
            val switchPendingIntent = PendingIntent.getBroadcast(
                context,
                REQ_SWITCH,
                switchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btn_timer_switch, switchPendingIntent)

            // 3. Open full timer in app
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(BabyBuddyQuickLogWidget.EXTRA_NAVIGATE_TO, "ACTIVE_NURSING_TIMER")
            }
            val openPendingIntent = PendingIntent.getActivity(
                context,
                REQ_OPEN,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btn_open_timer_screen, openPendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    private fun sideName(context: Context, side: BreastSide) = context.getString(if (side == BreastSide.LEFT) R.string.side_left else R.string.side_right)

    companion object {
        private const val REQ_PAUSE = 201
        private const val REQ_SWITCH = 202
        private const val REQ_OPEN = 203
    }
}

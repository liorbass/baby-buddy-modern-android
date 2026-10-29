package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.data.repository.BabyBuddyRepository

class BabyBuddyQuickLogWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val repo = BabyBuddyRepository.instance
        val profile = repo.babyProfile.value

        val lastFeed = repo.feedings.value.firstOrNull()
        val statusText = if (lastFeed != null) context.getString(R.string.widget_last_fed, lastFeed.timeFormatted) else context.getString(R.string.widget_no_feeds_today)

        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_quick_log)

            // Baby info header
            views.setTextViewText(R.id.tv_baby_name, "${profile.shortName} · ${profile.ageFormatted}")
            views.setTextViewText(R.id.tv_quick_status, statusText)

            // 1. Feed action -> opens the log sheet (breastfeed or bottle)
            val feedIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_NAVIGATE_TO, "QUICK_LOG_MODAL")
            }
            val feedPendingIntent = PendingIntent.getActivity(
                context,
                REQ_FEED,
                feedIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btn_action_feed, feedPendingIntent)

            // 2. Diaper action -> opens Diaper Log Screen
            val diaperIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_NAVIGATE_TO, "LOG_DIAPER")
            }
            val diaperPendingIntent = PendingIntent.getActivity(
                context,
                REQ_DIAPER,
                diaperIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btn_action_diaper, diaperPendingIntent)

            // 3. Tummy action -> Broadcast quick tummy
            val tummyIntent = Intent(context, WidgetActionReceiver::class.java).apply {
                action = WidgetActionReceiver.ACTION_QUICK_TUMMY
            }
            val tummyPendingIntent = PendingIntent.getBroadcast(
                context,
                REQ_TUMMY,
                tummyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btn_action_tummy, tummyPendingIntent)

            // Header click opens app
            val headerIntent = Intent(context, MainActivity::class.java)
            val headerPendingIntent = PendingIntent.getActivity(
                context,
                REQ_HEADER,
                headerIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_header, headerPendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    companion object {
        const val EXTRA_NAVIGATE_TO = "extra_navigate_to"
        private const val REQ_FEED = 101
        private const val REQ_DIAPER = 102
        private const val REQ_TUMMY = 104
        private const val REQ_HEADER = 105
    }
}

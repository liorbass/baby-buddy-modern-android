package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.data.ml.RhythmTarget
import com.example.data.model.AppText
import com.example.data.model.TimeFormat
import com.example.data.repository.BabyBuddyRepository

class BabyBuddySummaryWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val repo = BabyBuddyRepository.instance
        val feeds = repo.feedings.value
        val diapers = repo.diapers.value

        val feedText = "${feeds.size}"
        val wetCount = diapers.count { it.type.name.contains("WET") || it.type.name.contains("BOTH") }
        val dirtyCount = diapers.count { it.type.name.contains("DIRTY") || it.type.name.contains("BOTH") }
        val diaperText = context.getString(R.string.widget_wet_dirty, wetCount, dirtyCount)
        val nextText = nextUpText(repo.modelNextTimes.value)

        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_summary)

            views.setTextViewText(R.id.tv_summary_feed, feedText)
            views.setTextViewText(R.id.tv_summary_diapers, diaperText)
            views.setTextViewText(R.id.tv_summary_next, nextText)
            views.setViewVisibility(R.id.tv_summary_next, if (nextText.isEmpty()) View.GONE else View.VISIBLE)

            // Clicking opens Today Dashboard
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(BabyBuddyQuickLogWidget.EXTRA_NAVIGATE_TO, "TODAY_DASHBOARD")
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                REQ_SUMMARY_OPEN,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_summary_root, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    companion object {
        private const val REQ_SUMMARY_OPEN = 301

        /** "Next: feed ~15:40 · diaper ~16:10 · nap ~14:55", or empty without picked models. */
        fun nextUpText(next: Map<RhythmTarget, Long>): String {
            if (next.isEmpty()) return ""
            val parts = next.entries.sortedBy { it.value }.map { (target, at) ->
                val what = when (target) {
                    RhythmTarget.FEED_GAP -> R.string.next_up_feed
                    RhythmTarget.DIAPER_GAP -> R.string.next_up_diaper
                    RhythmTarget.SLEEP_LENGTH -> R.string.next_up_wake_up
                    RhythmTarget.AWAKE_WINDOW -> R.string.next_up_nap
                }
                AppText.get(R.string.next_up_item, AppText.get(what), TimeFormat.clock(at))
            }
            return AppText.get(R.string.next_up_line, parts.joinToString(" · "))
        }
    }
}

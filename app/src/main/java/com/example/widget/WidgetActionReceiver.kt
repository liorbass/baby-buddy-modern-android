package com.example.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.example.MainActivity
import com.example.R
import com.example.data.model.BreastSide
import com.example.data.model.DiaperEvent
import com.example.data.model.DiaperType
import com.example.data.repository.BabyBuddyRepository

class WidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val repo = BabyBuddyRepository.instance
        val action = intent.action ?: return

        when (action) {
            ACTION_TIMER_PAUSE_RESUME -> {
                if (!repo.liveTimerState.value.isRunning) {
                    // Start on the side from Settings → You (by default opposite the last feed)
                    repo.startNewNursingSession(repo.nextStartSide())
                } else {
                    repo.togglePause()
                }
                val isPaused = repo.liveTimerState.value.isPaused
                Toast.makeText(
                    context,
                    context.getString(if (isPaused) R.string.timer_paused else R.string.timer_running),
                    Toast.LENGTH_SHORT
                ).show()
                WidgetManagerHelper.updateAllWidgets(context)
            }

            ACTION_TIMER_SWITCH_SIDE -> {
                repo.switchActiveSide()
                val activeSide = repo.liveTimerState.value.activeBreast
                Toast.makeText(
                    context,
                    context.getString(if (activeSide == BreastSide.LEFT) R.string.switched_to_left else R.string.switched_to_right),
                    Toast.LENGTH_SHORT
                ).show()
                WidgetManagerHelper.updateAllWidgets(context)
            }

            ACTION_QUICK_TUMMY -> {
                repo.addTummyTimeMinutes(5)
                Toast.makeText(context, context.getString(R.string.tummy_time_logged_5), Toast.LENGTH_SHORT).show()
                WidgetManagerHelper.updateAllWidgets(context)
            }

            // Lock-screen feed notification actions: no toasts.
            ACTION_TAP_LEFT -> {
                repo.tapSide(BreastSide.LEFT)
                WidgetManagerHelper.updateAllWidgets(context)
            }

            ACTION_TAP_RIGHT -> {
                repo.tapSide(BreastSide.RIGHT)
                WidgetManagerHelper.updateAllWidgets(context)
            }

            ACTION_END_FEED -> {
                repo.endFeed()
                WidgetManagerHelper.updateAllWidgets(context)
            }

            ACTION_STOP_SLEEP -> {
                repo.stopSleep()
                Toast.makeText(context, context.getString(R.string.sleep_saved), Toast.LENGTH_SHORT).show()
            }

            ACTION_WIDGET_PINNED -> {
                Toast.makeText(context, context.getString(R.string.widget_added), Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        const val ACTION_TIMER_PAUSE_RESUME = "com.example.babybuddy.ACTION_TIMER_PAUSE_RESUME"
        const val ACTION_TIMER_SWITCH_SIDE = "com.example.babybuddy.ACTION_TIMER_SWITCH_SIDE"
        const val ACTION_QUICK_TUMMY = "com.example.babybuddy.ACTION_QUICK_TUMMY"
        const val ACTION_WIDGET_PINNED = "com.example.babybuddy.ACTION_WIDGET_PINNED"
        const val ACTION_TAP_LEFT = "com.example.babybuddy.ACTION_TAP_LEFT"
        const val ACTION_TAP_RIGHT = "com.example.babybuddy.ACTION_TAP_RIGHT"
        const val ACTION_END_FEED = "com.example.babybuddy.ACTION_END_FEED"
        const val ACTION_STOP_SLEEP = "com.example.babybuddy.ACTION_STOP_SLEEP"
    }
}

package com.example.widget

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.AppLanguage
import com.example.data.model.LiveTimerState
import com.example.data.repository.BabyBuddyRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runs while a feed or sleep is being timed (and the lock-screen timers are on), so the app isn't frozen in
 * the background: it checks the shared timers every [FEED_POLL_MS] during a feed ([SLEEP_POLL_MS] during a
 * sleep), so a feed or sleep ended on another phone also ends here instead of counting on. Its notification is
 * the feed notification (or the sleep one when only a sleep runs); [BabyBuddyApplication] keeps both current.
 */
class TimerSyncService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var foregroundId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppLanguage.apply(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val current = currentNotification()
        // A service started with startForegroundService must go foreground even if the timer has just ended
        val (id, notification) = current ?: (FeedNotification.NOTIFICATION_ID to FeedNotification.build(this, LiveTimerState()))
        try {
            ServiceCompat.startForeground(
                this,
                id,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            )
        } catch (e: Exception) {
            // Not allowed to start from the background right now; the next return to the app starts it
            stopSelf()
            return START_NOT_STICKY
        }
        if (current == null) {
            stopSelfNow()
            return START_NOT_STICKY
        }
        // Moved from the feed to the sleep notification (or back): the old one is no longer tied to the service
        if (foregroundId != 0 && foregroundId != id) refreshPlainNotifications()
        foregroundId = id
        if (pollJob?.isActive != true) {
            pollJob = scope.launch {
                val repository = BabyBuddyRepository.instance
                while (isActive) {
                    delay(if (repository.liveTimerState.value.isRunning) FEED_POLL_MS else SLEEP_POLL_MS)
                    runCatching { repository.pollSharedTimers() }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        refreshPlainNotifications()
        super.onDestroy()
    }

    /** The feed notification while a feed runs, else the sleep one; null when neither runs. */
    private fun currentNotification(): Pair<Int, Notification>? {
        val repository = BabyBuddyRepository.instance
        val feed = repository.liveTimerState.value
        val sleep = repository.sleepTimer.value
        return when {
            feed.isRunning -> FeedNotification.NOTIFICATION_ID to FeedNotification.build(this, feed)
            sleep.isRunning -> SleepNotification.NOTIFICATION_ID to SleepNotification.build(this, sleep)
            else -> null
        }
    }

    /** Re-posts (or clears) both notifications as ordinary ones after the service lets go of one. */
    private fun refreshPlainNotifications() {
        val repository = BabyBuddyRepository.instance
        val enabled = repository.feedNotificationEnabled.value
        if (!repository.liveTimerState.value.isRunning) NotificationManagerCompat.from(this).cancel(FeedNotification.NOTIFICATION_ID)
        if (!repository.sleepTimer.value.isRunning) NotificationManagerCompat.from(this).cancel(SleepNotification.NOTIFICATION_ID)
        FeedNotification.update(this, repository.liveTimerState.value, enabled)
        SleepNotification.update(this, repository.sleepTimer.value, enabled)
    }

    private fun stopSelfNow() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val FEED_POLL_MS = 15_000L
        private const val SLEEP_POLL_MS = 60_000L

        /** Starts or refreshes the service while a feed/sleep runs and the lock-screen timers are on; stops it otherwise. */
        fun sync(context: Context, timerRunning: Boolean, enabled: Boolean) {
            val intent = Intent(context, TimerSyncService::class.java)
            if (timerRunning && enabled) {
                try {
                    ContextCompat.startForegroundService(context, intent)
                } catch (e: Exception) {
                    // Background start not allowed (e.g. a feed adopted while the app is closed); polling resumes
                    // with the app
                }
            } else {
                context.stopService(intent)
            }
        }
    }
}

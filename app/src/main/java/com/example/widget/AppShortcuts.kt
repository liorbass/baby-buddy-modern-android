package com.example.widget

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.example.MainActivity
import com.example.R
import com.example.data.model.TrackedItem

/**
 * Launcher long-press shortcuts: Feed left / Feed right (start or switch to that side), Diaper and Sleep now.
 * They open [MainActivity] with [EXTRA_SHORTCUT_ACTION] (done there by `handleWidgetNavigation`) and a screen.
 * Set again when the login or the tracked items change; none while signed out.
 */
object AppShortcuts {

    const val EXTRA_SHORTCUT_ACTION = "shortcut_action"
    const val FEED_LEFT = "FEED_LEFT"
    const val FEED_RIGHT = "FEED_RIGHT"
    const val START_SLEEP = "START_SLEEP"

    fun update(context: Context, signedIn: Boolean, hidden: Set<TrackedItem>) {
        val shortcuts = if (!signedIn) {
            emptyList()
        } else {
            listOfNotNull(
                shortcut(context, "feed_left", context.getString(R.string.shortcut_feed_left), context.getString(R.string.shortcut_feed_left_desc), R.drawable.ic_widget_feed, "ACTIVE_NURSING_TIMER", FEED_LEFT)
                    .takeIf { TrackedItem.BREASTFEEDING !in hidden },
                shortcut(context, "feed_right", context.getString(R.string.shortcut_feed_right), context.getString(R.string.shortcut_feed_right_desc), R.drawable.ic_widget_feed, "ACTIVE_NURSING_TIMER", FEED_RIGHT)
                    .takeIf { TrackedItem.BREASTFEEDING !in hidden },
                shortcut(context, "diaper", context.getString(R.string.shortcut_diaper), context.getString(R.string.shortcut_diaper_desc), R.drawable.ic_widget_diaper, "LOG_DIAPER", null)
                    .takeIf { TrackedItem.DIAPERS !in hidden },
                shortcut(context, "sleep", context.getString(R.string.shortcut_sleep), context.getString(R.string.shortcut_sleep_desc), R.drawable.ic_widget_sleep, "SLEEP", START_SLEEP)
                    .takeIf { TrackedItem.SLEEP !in hidden }
            )
        }
        runCatching {
            // Launchers show about four; keep within what this one allows
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts.take(ShortcutManagerCompat.getMaxShortcutCountPerActivity(context)))
        }
    }

    private fun shortcut(context: Context, id: String, label: String, longLabel: String, icon: Int, screen: String, action: String?): ShortcutInfoCompat {
        val intent = Intent(context, MainActivity::class.java).apply {
            this.action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(BabyBuddyQuickLogWidget.EXTRA_NAVIGATE_TO, screen)
            action?.let { putExtra(EXTRA_SHORTCUT_ACTION, it) }
        }
        return ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(label)
            .setLongLabel(longLabel)
            .setIcon(IconCompat.createWithResource(context, icon))
            .setIntent(intent)
            .build()
    }
}

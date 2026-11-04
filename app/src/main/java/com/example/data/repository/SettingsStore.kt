package com.example.data.repository

import android.content.SharedPreferences
import androidx.compose.runtime.snapshotFlow
import com.example.R
import com.example.data.model.AppText
import com.example.data.model.BabySex
import com.example.data.model.DashboardWidgetConfig
import com.example.data.model.DashboardWidgetType
import com.example.data.model.DayHours
import com.example.data.model.QuickAction
import com.example.data.model.ReminderSettings
import com.example.data.model.StartSide
import com.example.data.model.ThemeMode
import com.example.data.model.TimeFormat
import com.example.data.model.TodaySection
import com.example.data.model.TodayTile
import com.example.data.model.TrackedItem
import com.example.data.model.WakeWindowPart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One setting saved in SharedPreferences, exposed as a StateFlow. */
internal class PrefValue<T>(
    initial: T,
    private val read: (SharedPreferences) -> T,
    private val write: (SharedPreferences.Editor, T) -> Unit,
    private val prefs: () -> SharedPreferences?
) {
    private val flow = MutableStateFlow(initial)
    val state: StateFlow<T> = flow.asStateFlow()
    val value: T get() = flow.value

    fun load(p: SharedPreferences) {
        flow.value = read(p)
    }

    /** Sets the value and saves it, unless [save] is false (demo mode's stand-ins). */
    fun set(value: T, save: Boolean = true) {
        flow.value = value
        if (save) prefs()?.edit()?.also { write(it, value) }?.apply()
    }
}

/**
 * This phone's settings (prefs `baby_buddy`), each a StateFlow with its setter: Settings → Baby, Tracking,
 * Notifications, Appearance and You, plus Customize Today. Reached as `repository.settings`.
 */
class SettingsStore internal constructor(
    /** The clock format changed: shown times need reformatting. */
    private val onClockFormatChanged: () -> Unit
) {
    private var prefs: SharedPreferences? = null
    private val all = mutableListOf<PrefValue<*>>()

    private fun <T> pref(initial: T, read: (SharedPreferences) -> T, write: (SharedPreferences.Editor, T) -> Unit) = PrefValue(initial, read, write) { prefs }.also { all += it }

    private fun boolPref(key: String, default: Boolean) = pref(default, { it.getBoolean(key, default) }, { e, v -> e.putBoolean(key, v) })

    private inline fun <reified E : Enum<E>> enumPref(key: String, default: E) = pref(
        default,
        { p -> p.getString(key, null)?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default },
        { e, v -> e.putString(key, v.name) }
    )

    /** A set of enum values saved by name; unknown names (from other versions) are skipped. */
    private inline fun <reified E : Enum<E>> enumSetPref(key: String) = pref(
        emptySet<E>(),
        { p -> enumNames<E>(p.getString(key, null)).toSet() },
        { e, v -> e.putString(key, v.joinToString(",") { it.name }) }
    )

    private inline fun <reified E : Enum<E>> enumNames(saved: String?): List<E> = saved.orEmpty().split(",").mapNotNull { name -> enumValues<E>().firstOrNull { it.name == name } }

    /** Loads every setting (once, at app start). */
    internal fun load(p: SharedPreferences, scope: CoroutineScope) {
        prefs = p
        all.forEach { it.load(p) }
        TimeFormat.use24h = use24hPref.value
        DayHours.dayStartHour = p.getInt(PREF_DAY_START, DayHours.DEFAULT_DAY_START)
        DayHours.nightStartHour = p.getInt(PREF_NIGHT_START, DayHours.DEFAULT_NIGHT_START)
        DayHours.trendsDayStartHour = p.getInt(PREF_TRENDS_DAY_START, 0)
        TimeFormat.useWheel = p.getBoolean(PREF_WHEEL_INPUT, false)
        scope.launch {
            // Remember the wheel/keyboard choice made in any picker
            snapshotFlow { TimeFormat.useWheel }.collect { p.edit().putBoolean(PREF_WHEEL_INPUT, it).apply() }
        }
    }

    // --- Baby ---

    private val babySexPref = pref<BabySex?>(
        null,
        { p -> p.getString(PREF_BABY_SEX, null)?.let { name -> BabySex.entries.firstOrNull { it.name == name } } },
        { e, v -> e.putString(PREF_BABY_SEX, v?.name) }
    )

    /** Picks the WHO reference charts; null until chosen (Settings → Baby, or the Trends growth card). */
    val babySex: StateFlow<BabySex?> = babySexPref.state

    fun setBabySex(sex: BabySex) = babySexPref.set(sex)

    /** Settings → Baby → Day and night; ignored when both are the same hour. */
    fun setDayHours(dayStartHour: Int, nightStartHour: Int) {
        if (dayStartHour == nightStartHour) return
        DayHours.dayStartHour = dayStartHour.mod(24)
        DayHours.nightStartHour = nightStartHour.mod(24)
        prefs?.edit()?.putInt(PREF_DAY_START, DayHours.dayStartHour)?.putInt(PREF_NIGHT_START, DayHours.nightStartHour)?.apply()
    }

    // --- Tracking ---

    private val hiddenItemsPref = enumSetPref<TrackedItem>(PREF_HIDDEN_ITEMS)

    /** Items this phone doesn't track; the UI leaves them out everywhere. */
    val hiddenItems: StateFlow<Set<TrackedItem>> = hiddenItemsPref.state

    fun setItemTracked(item: TrackedItem, tracked: Boolean) = hiddenItemsPref.set(if (tracked) hiddenItemsPref.value - item else hiddenItemsPref.value + item)

    private val startSidePref = enumPref(PREF_START_SIDE, StartSide.OPPOSITE)

    /** Which side one-tap feeds start on (timer widget, Quick Settings tile); Settings → Tracking. */
    val startSide: StateFlow<StartSide> = startSidePref.state

    fun setStartSide(side: StartSide) = startSidePref.set(side)

    /** The hour Trends' days start at (Settings → Tracking). */
    fun setTrendsDayStart(hour: Int) {
        DayHours.trendsDayStartHour = hour.mod(24)
        prefs?.edit()?.putInt(PREF_TRENDS_DAY_START, DayHours.trendsDayStartHour)?.apply()
    }

    private val modelsTabPref = boolPref(PREF_MODELS_TAB, false)

    /** The Models tab in Trends (rhythm models fitted on the phone). */
    val modelsTab: StateFlow<Boolean> = modelsTabPref.state

    fun setModelsTab(enabled: Boolean) = modelsTabPref.set(enabled)

    // --- Notifications ---

    private val remindersPref = pref(
        ReminderSettings(),
        { p ->
            ReminderSettings(
                p.getInt(PREF_REMIND_FEED, 0),
                p.getInt(PREF_REMIND_AWAKE, 0),
                p.getInt(PREF_REMIND_SLEEP, 0),
                p.getInt(PREF_REMIND_DIAPER, 0),
                p.getInt(PREF_REMIND_EARLY, 0)
            )
        },
        { e, v ->
            e.putInt(PREF_REMIND_FEED, v.feedAfterMinutes)
                .putInt(PREF_REMIND_AWAKE, v.awakeAfterMinutes)
                .putInt(PREF_REMIND_SLEEP, v.sleepAfterMinutes)
                .putInt(PREF_REMIND_DIAPER, v.diaperAfterMinutes)
                .putInt(PREF_REMIND_EARLY, v.earlyMinutes)
        }
    )
    val reminders: StateFlow<ReminderSettings> = remindersPref.state

    fun setReminders(settings: ReminderSettings) = remindersPref.set(settings)

    private val feedNotificationPref = boolPref(PREF_FEED_NOTIFICATION, true)

    /** Lock-screen feed and sleep timers. */
    val feedNotificationEnabled: StateFlow<Boolean> = feedNotificationPref.state

    fun setFeedNotificationEnabled(enabled: Boolean) = feedNotificationPref.set(enabled)

    private val nextUpPref = boolPref(PREF_NEXT_UP_NOTIFICATION, false)

    /** Silent notification with what's next (feed, diaper, sleep) while no timer runs (Settings → Notifications). */
    val nextUpNotification: StateFlow<Boolean> = nextUpPref.state

    fun setNextUpNotification(enabled: Boolean) = nextUpPref.set(enabled)

    // --- Appearance ---

    private val themeModePref = enumPref(PREF_THEME_MODE, ThemeMode.BY_TIME)

    /** Light/dark choice for this phone. */
    val themeMode: StateFlow<ThemeMode> = themeModePref.state

    fun setThemeMode(mode: ThemeMode) = themeModePref.set(mode)

    private val use24hPref = boolPref(PREF_USE_24H, true)

    /** 24-hour clock (default) or 12-hour with AM/PM. */
    val use24h: StateFlow<Boolean> = use24hPref.state

    fun setUse24h(enabled: Boolean) {
        use24hPref.set(enabled)
        TimeFormat.use24h = enabled
        onClockFormatChanged()
    }

    // --- You ---

    private val caregiverPref = pref("", { it.getString(PREF_CAREGIVER, "").orEmpty() }, { e, v -> e.putString(PREF_CAREGIVER, v) })

    /** This device's caregiver; written as "Caregiver: Name" into new entries, like the server's other clients do. */
    val caregiverName: StateFlow<String> = caregiverPref.state

    /** [save] false: demo mode's stand-in name, shown but not stored. */
    fun setCaregiverName(name: String, save: Boolean = true) = caregiverPref.set(name.trim(), save)

    // --- Customize Today ---

    private val quickActionsPref = pref(
        QuickAction.DEFAULT,
        { p -> p.getString(PREF_QUICK_ACTIONS, null)?.let { saved -> enumNames<QuickAction>(saved) } ?: QuickAction.DEFAULT },
        { e, v -> e.putString(PREF_QUICK_ACTIONS, v.joinToString(",") { it.name }) }
    )

    /** Today's shortcut buttons, in the order shown. */
    val quickActions: StateFlow<List<QuickAction>> = quickActionsPref.state

    fun setQuickActions(actions: List<QuickAction>) = quickActionsPref.set(actions.distinct())

    // Summary tiles and Wake windows card parts turned off (saved as what's off, so new ones show)
    private val hiddenTilesPref = enumSetPref<TodayTile>(PREF_HIDDEN_SUMMARY_TILES)
    val hiddenTodayTiles: StateFlow<Set<TodayTile>> = hiddenTilesPref.state

    fun setTodayTileShown(tile: TodayTile, shown: Boolean) = hiddenTilesPref.set(if (shown) hiddenTilesPref.value - tile else hiddenTilesPref.value + tile)

    private val hiddenWakePartsPref = enumSetPref<WakeWindowPart>(PREF_HIDDEN_WAKE_PARTS)
    val hiddenWakeWindowParts: StateFlow<Set<WakeWindowPart>> = hiddenWakePartsPref.state

    fun setWakeWindowPartShown(part: WakeWindowPart, shown: Boolean) = hiddenWakePartsPref.set(if (shown) hiddenWakePartsPref.value - part else hiddenWakePartsPref.value + part)

    private val dashboardWidgetsPref = pref(
        defaultDashboardWidgets(),
        { p -> readDashboardWidgets(p, defaultDashboardWidgets()) },
        { e, v ->
            e.putString(PREF_DASHBOARD_WIDGETS, v.filter { it.isEnabled }.joinToString(",") { it.type.name })
                .putString(PREF_DASHBOARD_WIDGETS_KNOWN, DashboardWidgetType.entries.joinToString(",") { it.name })
        }
    )

    /** Today's cards and which are on. */
    val dashboardWidgets: StateFlow<List<DashboardWidgetConfig>> = dashboardWidgetsPref.state

    fun toggleWidget(type: DashboardWidgetType) = setWidget(type) { !it }

    fun addWidget(type: DashboardWidgetType) = setWidget(type) { true }

    fun removeWidget(type: DashboardWidgetType) = setWidget(type) { false }

    private fun setWidget(type: DashboardWidgetType, enabled: (Boolean) -> Boolean) = dashboardWidgetsPref.set(dashboardWidgetsPref.value.map { if (it.type == type) it.copy(isEnabled = enabled(it.isEnabled)) else it })

    /** Demo mode: every card on, without changing the saved choice. */
    internal fun showAllCards() = dashboardWidgetsPref.set(dashboardWidgetsPref.value.map { it.copy(isEnabled = true) }, save = false)

    private val todayOrderPref = pref(
        TodaySection.entries.toList(),
        { p -> TodaySection.order(p.getString(PREF_TODAY_ORDER, null).orEmpty().split(",")) },
        { e, v -> e.putString(PREF_TODAY_ORDER, v.joinToString(",") { it.name }) }
    )

    /** The order of Today's sections. */
    val todayOrder: StateFlow<List<TodaySection>> = todayOrderPref.state

    /** Moves [section] by [delta] among the sections that can show (never swapping with a hidden one). */
    fun moveTodaySection(section: TodaySection, delta: Int) {
        val cards = dashboardWidgets.value.filter { it.isEnabled }.map { it.type }.toSet()
        todayOrderPref.set(TodaySection.move(todayOrder.value, section, delta) { it.isShown(hiddenItems.value) { type -> type in cards } })
    }

    fun resetTodayOrder() = todayOrderPref.set(TodaySection.entries.toList())

    companion object {
        fun defaultDashboardWidgets() = listOf(
            DashboardWidgetConfig(DashboardWidgetType.LAST_FEED, DashboardWidgetType.LAST_FEED.displayName, AppText.get(R.string.card_last_feed_desc), isEnabled = true),
            DashboardWidgetConfig(DashboardWidgetType.LAST_DIAPER, DashboardWidgetType.LAST_DIAPER.displayName, AppText.get(R.string.card_last_diaper_desc), isEnabled = true),
            DashboardWidgetConfig(DashboardWidgetType.WAKE_WINDOWS, DashboardWidgetType.WAKE_WINDOWS.displayName, AppText.get(R.string.card_wake_windows_desc), isEnabled = true),
            DashboardWidgetConfig(DashboardWidgetType.TUMMY_TIME_GOAL, DashboardWidgetType.TUMMY_TIME_GOAL.displayName, AppText.get(R.string.card_tummy_time_desc), isEnabled = false),
            DashboardWidgetConfig(DashboardWidgetType.HEALTH_VITALS, DashboardWidgetType.HEALTH_VITALS.displayName, AppText.get(R.string.card_temperature_desc), isEnabled = false),
            DashboardWidgetConfig(DashboardWidgetType.CAREGIVER_NOTES, DashboardWidgetType.CAREGIVER_NOTES.displayName, AppText.get(R.string.card_notes_desc), isEnabled = false),
            DashboardWidgetConfig(DashboardWidgetType.GROWTH, DashboardWidgetType.GROWTH.displayName, AppText.get(R.string.card_growth_desc), isEnabled = false)
        )

        /** The saved card choice applied to [defaults]; cards added since it was saved keep their default. */
        private fun readDashboardWidgets(p: SharedPreferences, defaults: List<DashboardWidgetConfig>): List<DashboardWidgetConfig> {
            val saved = p.getString(PREF_DASHBOARD_WIDGETS, null) ?: return defaults
            val enabled = saved.split(",").map { if (it == "WEIGHT") "GROWTH" else it }.toSet()
            // Saves from before the known list was kept knew only these four
            val known = p.getString(PREF_DASHBOARD_WIDGETS_KNOWN, null)?.split(",")?.toSet()
                ?: setOf("TUMMY_TIME_GOAL", "HEALTH_VITALS", "CAREGIVER_NOTES", "GROWTH")
            return defaults.map { if (it.type.name in known) it.copy(isEnabled = it.type.name in enabled) else it }
        }

        private const val PREF_BABY_SEX = "baby_sex"
        private const val PREF_DASHBOARD_WIDGETS = "dashboard_widgets"
        private const val PREF_DASHBOARD_WIDGETS_KNOWN = "dashboard_widgets_known"
        private const val PREF_REMIND_FEED = "remind_feed_minutes"
        private const val PREF_REMIND_AWAKE = "remind_awake_minutes"
        private const val PREF_REMIND_SLEEP = "remind_sleep_minutes"
        private const val PREF_REMIND_DIAPER = "remind_diaper_minutes"
        private const val PREF_REMIND_EARLY = "remind_early_minutes"
        private const val PREF_HIDDEN_ITEMS = "hidden_items"
        private const val PREF_HIDDEN_SUMMARY_TILES = "hidden_summary_tiles"
        private const val PREF_HIDDEN_WAKE_PARTS = "hidden_wake_window_parts"
        private const val PREF_TODAY_ORDER = "today_order"
        private const val PREF_WHEEL_INPUT = "time_input_wheel_v2" // v2: typing became the default
        private const val PREF_MODELS_TAB = "trends_models_tab"
        private const val PREF_CAREGIVER = "caregiver_name"
        private const val PREF_FEED_NOTIFICATION = "feed_notification"
        private const val PREF_NEXT_UP_NOTIFICATION = "next_up_notification"
        private const val PREF_QUICK_ACTIONS = "quick_actions"
        private const val PREF_USE_24H = "use_24h"
        private const val PREF_DAY_START = "day_start_hour"
        private const val PREF_NIGHT_START = "night_start_hour"
        private const val PREF_TRENDS_DAY_START = "trends_day_start_hour"
        private const val PREF_THEME_MODE = "theme_mode"
        private const val PREF_START_SIDE = "start_side"
    }
}

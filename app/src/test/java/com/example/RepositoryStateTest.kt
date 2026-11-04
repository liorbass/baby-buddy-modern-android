package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.DashboardWidgetType
import com.example.data.model.FeedType
import com.example.data.model.QuickAction
import com.example.data.model.ReminderSettings
import com.example.data.model.StartSide
import com.example.data.model.TodaySection
import com.example.data.model.TrackedItem
import com.example.data.repository.BabyBuddyRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RepositoryStateTest {

    private fun waitFor(what: String, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000L
        while (!condition()) {
            check(System.currentTimeMillis() < until) { "timed out waiting for $what" }
            Thread.sleep(20)
        }
    }

    @Test
    fun settingsAreSavedAndRestoredOnTheNextStart() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = BabyBuddyRepository().apply { attach(context) }
        first.settings.setStartSide(StartSide.RIGHT)
        first.settings.setItemTracked(TrackedItem.BATH, tracked = false)
        first.settings.setQuickActions(listOf(QuickAction.SLEEP, QuickAction.DIAPER, QuickAction.SLEEP))
        first.settings.setReminders(ReminderSettings(feedAfterMinutes = 180, diaperAfterMinutes = 120))
        first.settings.toggleWidget(DashboardWidgetType.GROWTH)
        first.settings.setCaregiverName("  Alex ")
        first.settings.moveTodaySection(TodaySection.entries.last(), -1)
        val order = first.settings.todayOrder.value

        val second = BabyBuddyRepository().apply { attach(context) }
        with(second.settings) {
            assertEquals(StartSide.RIGHT, startSide.value)
            assertEquals(setOf(TrackedItem.BATH), hiddenItems.value)
            assertEquals(listOf(QuickAction.SLEEP, QuickAction.DIAPER), quickActions.value)
            assertEquals(ReminderSettings(feedAfterMinutes = 180, diaperAfterMinutes = 120), reminders.value)
            assertTrue(dashboardWidgets.value.first { it.type == DashboardWidgetType.GROWTH }.isEnabled)
            assertEquals("Alex", caregiverName.value)
            assertEquals(order, todayOrder.value)
        }
    }

    @Test
    fun deletingTheLastBreastfeedFallsBackToTheOneBefore() {
        val repository = BabyBuddyRepository()
        repository.enterDemoMode()
        waitFor("the last breastfeed") { repository.lastBreastfeed.value != null }
        val last = repository.lastBreastfeed.value!!
        // Demo history runs up to now; just after midnight today may have no breastfeed yet
        assumeTrue(repository.feedings.value.any { it.id == last.id })

        repository.deleteFeeding(last.id)

        waitFor("the last breastfeed to change") { repository.lastBreastfeed.value?.id != last.id }
        val now = repository.lastBreastfeed.value
        assertNotEquals(last.id, now?.id)
        assertFalse(repository.feedings.value.any { it.id == last.id })
        if (now != null) assertEquals(FeedType.NURSING, now.type)
    }
}

package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.DiaperType
import com.example.data.repository.BabyBuddyRepository
import com.example.ui.MainViewModel
import com.example.ui.ScreenDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Baby Buddy Companion", appName)
    }

    @Test
    fun `repository starts without a baby until login and handles live timer switch`() {
        val repository = BabyBuddyRepository()
        // No built-in sample baby: the profile comes from the server (or demo mode) after login
        assertTrue(repository.babyProfile.value.name.isEmpty())

        val initialSide = repository.liveTimerState.value.activeBreast
        repository.switchActiveSide()
        val switchedSide = repository.liveTimerState.value.activeBreast
        assertTrue(initialSide != switchedSide)
    }

    @Test
    fun `viewmodel handles navigation and quick logging`() {
        val viewModel = MainViewModel()
        viewModel.navigateTo(ScreenDestination.LOG_DIAPER)
        assertEquals(ScreenDestination.LOG_DIAPER, viewModel.currentScreen.value)

        val initialDiaperCount = viewModel.diapers.value.size
        viewModel.saveDiaperChange(
            type = DiaperType.BOTH,
            amount = "Medium",
            consistency = "Seedy / Mustard",
            color = "Mustard",
            rashPresent = false,
            skinBarriers = listOf("Aquaphor / Barrier"),
            timeMillis = null, // now
            notes = "Test diaper log",
            startNursingAfter = false
        )
        assertEquals(initialDiaperCount + 1, viewModel.diapers.value.size)
        assertEquals(ScreenDestination.TODAY_DASHBOARD, viewModel.currentScreen.value)
    }
}

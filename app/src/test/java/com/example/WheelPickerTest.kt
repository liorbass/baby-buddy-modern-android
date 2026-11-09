package com.example

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import com.example.data.model.TimeFormat
import com.example.ui.components.ClockTimeEntry
import com.example.ui.components.DurationWheels
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WheelPickerTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun useWheels() {
        TimeFormat.useWheel = true
        TimeFormat.use24h = true
    }

    @After
    fun reset() {
        TimeFormat.useWheel = false
    }

    @Test
    fun `minutes going back past 00 carry into the hour, the date and the month`() {
        // Midnight on the 1st of a month, a week ago: one minute back is 23:59 on the last day of the month before
        val start = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -7)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        var picked by mutableLongStateOf(start)
        compose.setContent {
            ClockTimeEntry(millis = picked, onChange = { picked = it }, fromTyped = { _, _ -> error("typed") })
        }
        // The row just above 00 on the minute wheel (the hour wheel shows 22, 23, 00, 01, 02)
        compose.onAllNodesWithText("59").onFirst().performClick()
        compose.waitForIdle()
        val expected = Calendar.getInstance().apply {
            timeInMillis = start
            add(Calendar.MINUTE, -1)
        }
        assertEquals(expected.timeInMillis, picked)
        // The hour and date wheels followed without carrying again
        compose.onAllNodesWithText("23").onFirst().performClick()
        compose.waitForIdle()
        assertEquals(expected.timeInMillis, picked)
        // Going forward past 59 (the minute wheel's 00, after the hour's) comes back to midnight on the 1st
        compose.onAllNodesWithText("00").onLast().performClick()
        compose.waitForIdle()
        assertEquals(start, picked)
    }

    @Test
    fun `duration seconds going back past 00 lower the minutes but not below zero`() {
        var value by mutableStateOf(60)
        compose.setContent {
            DurationWheels(first = value / 60, second = value % 60, firstMax = 180, firstUnit = "m", secondUnit = "s", onChange = { m, s ->
                value = m * 60 + s
            })
        }
        compose.onAllNodesWithText("59").onFirst().performClick()
        compose.waitForIdle()
        assertEquals(59, value)
    }
}

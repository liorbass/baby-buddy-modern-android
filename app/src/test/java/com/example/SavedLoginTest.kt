package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.repository.SavedLogin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The saved login: demo mode replaces it, signing out clears it, and a token that can't be decrypted isn't restored. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SavedLoginTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("saved_login_test", Context.MODE_PRIVATE)

    @Test
    fun demoModeIsSavedInsteadOfALoginAndClearedOnSignOut() {
        prefs.edit().putString("server_url", "https://example.invalid").putInt("child_id", 3).apply()
        SavedLogin.clear(prefs, demoMode = true)
        assertTrue(SavedLogin.isDemoMode(prefs))
        assertFalse(prefs.contains("server_url"))
        assertFalse(prefs.contains("child_id"))

        SavedLogin.clear(prefs)
        assertFalse(SavedLogin.isDemoMode(prefs))
    }

    @Test
    fun aLoginWithoutAReadableTokenIsNotRestored() {
        prefs.edit().putString("server_url", "https://example.invalid").putString("api_token_enc", "not encrypted").putString("api_token", "legacy").apply()
        assertNull(SavedLogin.read(prefs))
        assertFalse(prefs.contains("api_token")) // the plain-text token of earlier builds is dropped
    }

    @Test
    fun savingALoginEndsDemoMode() {
        SavedLogin.clear(prefs, demoMode = true)
        SavedLogin("https://example.invalid", "token", false, "", "", "", 1, "Sam", "2026-01-01").save(prefs)
        assertFalse(SavedLogin.isDemoMode(prefs))
        assertTrue(prefs.getString("server_url", null) == "https://example.invalid")
    }
}

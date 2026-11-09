package com.example.data.repository

import android.content.SharedPreferences

/**
 * The server login saved in prefs, with the child it found; the API token and the Cloudflare Access secret are
 * stored encrypted ([SecretCipher]). Demo mode is saved instead of a login.
 */
internal data class SavedLogin(
    val url: String,
    val token: String,
    val allowSelfSigned: Boolean,
    val certPin: String,
    val cfClientId: String,
    val cfClientSecret: String,
    val childId: Int?,
    val childName: String?,
    val birthDate: String?
) {
    /** Saves this login (which ends demo mode, so logging in from it sticks after a restart). */
    fun save(p: SharedPreferences) {
        p.edit()
            .putString(URL, url)
            .putString(TOKEN_ENC, encrypt(token))
            .putBoolean(SELF_SIGNED, allowSelfSigned)
            .putString(CERT_PIN, certPin)
            .putString(CF_ID, cfClientId)
            .putString(CF_SECRET_ENC, cfClientSecret.takeIf { it.isNotBlank() }?.let(::encrypt))
            .putInt(CHILD_ID, childId ?: -1)
            .putString(CHILD_NAME, childName)
            .putString(BIRTH_DATE, birthDate)
            .remove(DEMO_MODE)
            .apply()
    }

    companion object {
        private const val URL = "server_url"
        private const val TOKEN_ENC = "api_token_enc"
        private const val LEGACY_TOKEN = "api_token" // earlier builds stored the token in plain text
        private const val SELF_SIGNED = "allow_self_signed"
        private const val CERT_PIN = "pinned_cert_sha256"
        private const val CF_ID = "cf_access_client_id"
        private const val CF_SECRET_ENC = "cf_access_client_secret_enc"
        private const val CHILD_ID = "child_id"
        private const val CHILD_NAME = "child_name"
        private const val BIRTH_DATE = "birth_date"
        private const val DEMO_MODE = "is_demo_mode"

        private fun encrypt(secret: String): String? = runCatching { SecretCipher.encrypt(secret) }.getOrNull()

        /** The saved login, or null if there is none (or its token can't be decrypted). */
        fun read(p: SharedPreferences): SavedLogin? {
            p.edit().remove(LEGACY_TOKEN).apply()
            val url = p.getString(URL, null) ?: return null
            val token = p.getString(TOKEN_ENC, null)?.let(SecretCipher::decrypt) ?: return null
            return SavedLogin(
                url = url,
                token = token,
                allowSelfSigned = p.getBoolean(SELF_SIGNED, false),
                certPin = p.getString(CERT_PIN, null).orEmpty(),
                cfClientId = p.getString(CF_ID, null).orEmpty(),
                cfClientSecret = p.getString(CF_SECRET_ENC, null)?.let(SecretCipher::decrypt).orEmpty(),
                childId = p.getInt(CHILD_ID, -1).takeIf { it >= 0 },
                childName = p.getString(CHILD_NAME, null),
                birthDate = p.getString(BIRTH_DATE, null)
            )
        }

        fun isDemoMode(p: SharedPreferences): Boolean = p.getBoolean(DEMO_MODE, false)

        /** Forgets the login; with [demoMode], demo mode is saved instead. */
        fun clear(p: SharedPreferences, demoMode: Boolean = false) {
            val editor = p.edit()
            listOf(URL, TOKEN_ENC, SELF_SIGNED, CERT_PIN, CF_ID, CF_SECRET_ENC, CHILD_ID, CHILD_NAME, BIRTH_DATE).forEach(editor::remove)
            if (demoMode) editor.putBoolean(DEMO_MODE, true) else editor.remove(DEMO_MODE)
            editor.apply()
        }
    }
}

package com.example.data.model

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * The app's string resources for code outside composables (view model, repository, widgets, notifications, enum labels).
 * Set by [com.example.BabyBuddyApplication] on start; composables use stringResource instead.
 */
object AppText {
    @Volatile
    var resources: Resources? = null

    fun get(@StringRes id: Int, vararg args: Any): String = resources!!.getString(id, *args)

    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String = resources!!.getQuantityString(id, count, *args)
}

package com.example.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/**
 * The package installer's answer to [AppUpdater]'s session: shows its confirm screen, or reports a cancel or
 * failure back to Settings. On success Android replaces the app, so there is nothing to do.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STATUS) return
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                // Settings is open, so the app is in the foreground and may start the system's confirm screen
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { context.startActivity(it) } }
                    ?: AppUpdater.onInstallFinished(cancelled = false, message = null)
            }

            PackageInstaller.STATUS_SUCCESS -> Unit

            else -> AppUpdater.onInstallFinished(
                cancelled = status == PackageInstaller.STATUS_FAILURE_ABORTED,
                message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            )
        }
    }

    companion object {
        const val ACTION_STATUS = "dev.liorbass.babybuddy.UPDATE_INSTALL_STATUS"
    }
}

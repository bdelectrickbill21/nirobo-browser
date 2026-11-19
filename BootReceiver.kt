package org.nirobo.browser

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Puts the daily NewsHub alarm back after the phone restarts or the app updates.
 * Without this, a restart would silently stop the daily save.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val app = context.applicationContext
            NewsSaver.ensureScheduled(app)
            NewsSaver.saveIfDue(app) { }
        }
    }
}

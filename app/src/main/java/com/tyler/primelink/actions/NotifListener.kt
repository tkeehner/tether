package com.tyler.primelink.actions

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService

/** Read-only window onto active notifications for read_notifications / dismiss / media. Keeps nothing. */
class NotifListener : NotificationListenerService() {
    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { if (instance === this) instance = null }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }
    companion object {
        @Volatile var instance: NotifListener? = null
        fun enabled(c: Context): Boolean {
            val flat = Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(c, NotifListener::class.java)
            return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}

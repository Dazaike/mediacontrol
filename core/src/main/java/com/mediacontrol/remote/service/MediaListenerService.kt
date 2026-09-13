package com.mediacontrol.remote.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.mediacontrol.remote.data.RepoHost

/**
 * Shared between the watch and phone apps (declared once in :core's manifest, merged into
 * both). Discovers/ranks/refreshes whichever media sessions are local to the device it runs on.
 */
class MediaListenerService : NotificationListenerService() {

    private fun repo() = (application as RepoHost).repo

    override fun onListenerConnected() {
        repo().refreshSessions()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        repo().refreshSessions()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        repo().refreshSessions()
    }
}

package com.example.voidroid

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.net.Socket
import kotlin.concurrent.thread

class NotificationService : NotificationListenerService() {

    private var lastPayloadHash: Int = 0
    private var lastTimestamp: Long = 0L

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        // 1. Ignore group summaries
        val isGroupSummary = (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
        if (isGroupSummary) return

        // 2. Resolve the human-readable app label (e.g. "WhatsApp")
        val appName = try {
            val pm = applicationContext.packageManager
            val appInfo = pm.getApplicationInfo(sbn.packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            sbn.packageName
        }

        // 3. Extract sender or title
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() 
            ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: appName

        // 4. Extract message body
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() 
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() 
            ?: ""

        if (text.isBlank() || sbn.isOngoing) return

        // Format: AppName|Title|Body
        val payload = "$appName|$title|$text"

        // 5. Deduplication
        val currentHash = payload.hashCode()
        val now = System.currentTimeMillis()
        if (currentHash == lastPayloadHash && (now - lastTimestamp) < 1500) {
            return
        }
        lastPayloadHash = currentHash
        lastTimestamp = now

        // 6. Transmit over socket
        thread {
            try {
                Socket("127.0.0.1", 5000).use { socket ->
                    val bytes = payload.toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().write(bytes)
                    socket.getOutputStream().flush()
                }
            } catch (e: Exception) {
                // USB or daemon disconnected
            }
        }
    }
}

package com.example.voidroid

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.net.Socket
import kotlin.concurrent.thread

class NotificationService : NotificationListenerService() {

    // Store the hash of the last transmitted payload to drop instant re-posts/updates
    private var lastPayloadHash: Int = 0
    private var lastTimestamp: Long = 0L

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        // 1. Ignore group summaries (WhatsApp creates an invisible "summary" for batches)
        val isGroupSummary = (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
        if (isGroupSummary) return

        // 2. Resolve the human-readable app label (e.g., "WhatsApp" instead of "com.whatsapp")
        val appName = try {
            val pm = applicationContext.packageManager
            val appInfo = pm.getApplicationInfo(sbn.packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            sbn.packageName
        }

        // 3. Extract sender / chat title (use getCharSequence to catch rich text)
        val senderOrTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() 
            ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: appName

        // 4. Extract message body
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() 
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() 
            ?: ""

        // Skip blank messages or ongoing system notifications (like media players)
        if (text.isBlank() || sbn.isOngoing) return

        // Format: "AppName • Sender|MessageBody"
        val displayTitle = if (senderOrTitle == appName) appName else "$appName • $senderOrTitle"
        val payload = "$displayTitle|$text"

        // 5. Deduplication: Drop duplicate payloads arriving within a 1.5-second window
        val currentHash = payload.hashCode()
        val now = System.currentTimeMillis()
        if (currentHash == lastPayloadHash && (now - lastTimestamp) < 1500) {
            return
        }
        lastPayloadHash = currentHash
        lastTimestamp = now

        // 6. Push over socket
        thread {
            try {
                Socket("127.0.0.1", 5000).use { socket ->
                    val bytes = payload.toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().write(bytes)
                    socket.getOutputStream().flush()
                }
            } catch (e: Exception) {
                // USB or server disconnected
            }
        }
    }
}

package com.example.voidroid

import android.app.Notification
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.net.Socket
import kotlin.concurrent.thread

class NotificationService : NotificationListenerService() {

    private var lastSentMessageText: String = ""
    private var lastSentTimestamp: Long = 0L

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        // 1. Ignore pure group summaries across different chats
        val isGroupSummary = (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
        if (isGroupSummary) return

        // 2. Resolve App Name (e.g., "WhatsApp")
        val appName = try {
            val pm = applicationContext.packageManager
            val appInfo = pm.getApplicationInfo(sbn.packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            sbn.packageName
        }

        // 3. Resolve Title (Sender or Group Name)
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: appName

        // 4. Extract the true latest message body
        val text = extractLatestMessage(extras)

        if (text.isBlank() || sbn.isOngoing) return

        // 5. Smart Deduplication:
        // Only drop if the EXACT same message text arrives within 1 second.
        // A new incoming message with different text will pass through immediately.
        val now = System.currentTimeMillis()
        if (text == lastSentMessageText && (now - lastSentTimestamp) < 1000) {
            return
        }
        lastSentMessageText = text
        lastSentTimestamp = now

        val payload = "$appName|$title|$text"

        // 6. Push over socket
        thread {
            try {
                Socket("127.0.0.1", 5000).use { socket ->
                    val bytes = payload.toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().write(bytes)
                    socket.getOutputStream().flush()
                }
            } catch (e: Exception) {
                // USB or daemon not listening
            }
        }
    }

    /**
     * Extracts the most recent message line from MessagingStyle notifications,
     * falling back to standard text extras if not present.
     */
    private fun extractLatestMessage(extras: Bundle): String {
        // WhatsApp & modern messengers use MessagingStyle, storing messages in a Parcelable array
        val rawMessages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (!rawMessages.isNullOrEmpty()) {
            val lastMsg = rawMessages.lastOrNull()
            if (lastMsg is Bundle) {
                val msgText = lastMsg.getCharSequence("text")?.toString()
                if (!msgText.isNullOrBlank()) {
                    return msgText
                }
            }
        }

        // Fallback for apps using inbox style lines (array of CharSequence)
        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (!textLines.isNullOrEmpty()) {
            val lastLine = textLines.lastOrNull()?.toString()
            if (!lastLine.isNullOrBlank()) {
                return lastLine
            }
        }

        // Standard fallbacks
        return extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: ""
    }
}

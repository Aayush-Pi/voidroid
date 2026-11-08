package com.example.voidroid

import android.app.Notification
import android.content.pm.PackageManager
import android.os.Bundle
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

        // 1. Ignore pure group summaries across chats
        val isGroupSummary = (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
        if (isGroupSummary) return

        // 2. Resolve Package Name
        val pkgName = sbn.packageName ?: "android"

        // 3. Resolve Display App Name (e.g. "WhatsApp")
        val appName = try {
            val pm = applicationContext.packageManager
            val appInfo = pm.getApplicationInfo(pkgName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkgName
        }

        // 4. Resolve Title (Sender or Chat Title)
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: appName

        // 5. Extract latest incoming message
        val text = extractLatestMessage(extras)

        if (text.isBlank() || sbn.isOngoing) return

        // 6. Deduplication: drop exact duplicates within 1000ms
        val now = System.currentTimeMillis()
        if (text == lastSentMessageText && (now - lastSentTimestamp) < 1000) {
            return
        }
        lastSentMessageText = text
        lastSentTimestamp = now

        // 7. 4-part payload
        val payload = "$pkgName|$appName|$title|$text"

        // 8. Stream over local reverse-tunnel socket
        thread {
            try {
                Socket("127.0.0.1", 5000).use { socket ->
                    val bytes = payload.toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().write(bytes)
                    socket.getOutputStream().flush()
                }
            } catch (e: Exception) {
                // USB unplugged or host daemon not running
            }
        }
    }

    private fun extractLatestMessage(extras: Bundle): String {
        // Inspect MessagingStyle array (WhatsApp, Signal, Telegram)
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

        // Inbox style lines
        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (!textLines.isNullOrEmpty()) {
            val lastLine = textLines.lastOrNull()?.toString()
            if (!lastLine.isNullOrBlank()) {
                return lastLine
            }
        }

        // General fallback chain
        return extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString()
            ?: ""
    }
}

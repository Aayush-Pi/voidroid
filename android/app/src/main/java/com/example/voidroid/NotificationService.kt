package com.example.voidroid

import android.app.Notification
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

class NotificationService : NotificationListenerService() {

    private data class ExtractedMessage(
        val text: String,
        val latestTime: Long,
        val messageCount: Int,
        val lineCount: Int,
        val hasStructuredMessages: Boolean
    )

    private data class SentRecord(
        val title: String,
        val text: String,
        val latestTime: Long,
        val messageCount: Int,
        val lineCount: Int,
        val hasStructuredMessages: Boolean,
        val timestamp: Long
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateLock = Any()
    private val sentByKey = HashMap<String, SentRecord>()
    private val lastChildPostByGroup = HashMap<String, Long>()
    private val pendingSummaries = HashMap<String, Runnable>()

    companion object {
        private const val HOST = "127.0.0.1"
        private const val PORT = 5000
        private const val SOCKET_TIMEOUT_MS = 2000
        private const val DEDUP_WINDOW_MS = 1000L
        private const val GROUP_CHILD_SUPPRESS_WINDOW_MS = 500L
        private const val SUMMARY_FALLBACK_DELAY_MS = 250L
        private const val MAX_TRACKED_KEYS = 200
        private const val STALE_RECORD_AGE_MS = 3_600_000L
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationLogStore.log("SVC", "Listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        NotificationLogStore.log("SVC", "Listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null || sbn.isOngoing) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val pkgName = sanitizeField(sbn.packageName ?: "android")
        val appName = resolveAppName(pkgName)
        val title = sanitizeField(
            extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
                ?: appName
        )
        val extracted = extractLatestMessage(extras)
        val isGroupSummary = (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0

        if (extracted.text.isBlank()) {
            NotificationLogStore.log(
                "DROP",
                "blank pkg=$pkgName title=\"${preview(title)}\" sum=$isGroupSummary"
            )
            return
        }

        val sbnKey = sbn.key ?: "$pkgName|$title"
        val groupId = "$pkgName|${sbn.groupKey ?: ""}"

        if (!isGroupSummary) {
            val pending = synchronized(stateLock) {
                lastChildPostByGroup[groupId] = System.currentTimeMillis()
                pendingSummaries.remove(groupId)
            }
            if (pending != null) {
                mainHandler.removeCallbacks(pending)
            }
            forwardIfNew(sbnKey, pkgName, appName, title, extracted, isGroupSummary = false)
            return
        }

        // Only use group summaries as a fallback when no child notification handles the group update,
        // and require structured lines/messages so dismissal count updates ("2 messages") don't spam.
        if (!extracted.hasStructuredMessages && extracted.lineCount == 0) {
            NotificationLogStore.log(
                "DROP",
                "sum_no_struct pkg=$pkgName title=\"${preview(title)}\" text=\"${preview(extracted.text)}\""
            )
            return
        }

        val postedAt = System.currentTimeMillis()
        synchronized(stateLock) {
            val lastChild = lastChildPostByGroup[groupId] ?: 0L
            if (postedAt - lastChild < GROUP_CHILD_SUPPRESS_WINDOW_MS) {
                NotificationLogStore.log(
                    "DROP",
                    "sum_by_child pkg=$pkgName title=\"${preview(title)}\" text=\"${preview(extracted.text)}\""
                )
                return
            }
            pendingSummaries.remove(groupId)?.let { mainHandler.removeCallbacks(it) }

            val runnable = Runnable {
                val suppressedByChild = synchronized(stateLock) {
                    pendingSummaries.remove(groupId)
                    val latestChild = lastChildPostByGroup[groupId] ?: 0L
                    latestChild >= postedAt - GROUP_CHILD_SUPPRESS_WINDOW_MS
                }
                if (suppressedByChild) {
                    NotificationLogStore.log(
                        "DROP",
                        "sum_delayed_by_child pkg=$pkgName title=\"${preview(title)}\""
                    )
                } else {
                    forwardIfNew(sbnKey, pkgName, appName, title, extracted, isGroupSummary = true)
                }
            }
            pendingSummaries[groupId] = runnable
            mainHandler.postDelayed(runnable, SUMMARY_FALLBACK_DELAY_MS)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn == null) return
        val sbnKey = sbn.key ?: return
        synchronized(stateLock) {
            sentByKey.remove(sbnKey)
        }
    }

    private fun forwardIfNew(
        sbnKey: String,
        pkgName: String,
        appName: String,
        title: String,
        extracted: ExtractedMessage,
        isGroupSummary: Boolean
    ) {
        val dupReason = synchronized(stateLock) {
            val now = System.currentTimeMillis()
            val prev = sentByKey[sbnKey]
            val reason = duplicateReason(prev, title, extracted, now)
            if (reason == null) {
                if (sentByKey.size >= MAX_TRACKED_KEYS) {
                    sentByKey.entries.removeIf { now - it.value.timestamp > STALE_RECORD_AGE_MS }
                }
                sentByKey[sbnKey] = SentRecord(
                    title = title,
                    text = extracted.text,
                    latestTime = extracted.latestTime,
                    messageCount = extracted.messageCount,
                    lineCount = extracted.lineCount,
                    hasStructuredMessages = extracted.hasStructuredMessages,
                    timestamp = now
                )
            }
            reason
        }
        if (dupReason != null) {
            NotificationLogStore.log(
                "DROP",
                "$dupReason [$appName] \"${preview(title)}\" - \"${preview(extracted.text)}\" " +
                    "(msgs=${extracted.messageCount} lines=${extracted.lineCount} sum=$isGroupSummary)"
            )
            return
        }

        val payload = "$pkgName|$appName|$title|${extracted.text}"
        thread {
            val (attempt, error) = sendPayloadWithAck(payload)
            if (error == null) {
                NotificationLogStore.log(
                    "SENT",
                    "[$appName] \"${preview(title)}\" - \"${preview(extracted.text)}\" " +
                        "(msgs=${extracted.messageCount} lines=${extracted.lineCount} sum=$isGroupSummary try=$attempt)"
                )
            } else {
                NotificationLogStore.log(
                    "SOCK_ERR",
                    "[$appName] \"${preview(title)}\" tries=$attempt $error"
                )
            }
        }
    }

    private fun sendPayloadWithAck(payload: String): Pair<Int, String?> {
        val bytes = (payload.replace("\u0000", "") + "\u0000").toByteArray(Charsets.UTF_8)
        var lastError: String? = null
        for (attempt in 1..3) {
            try {
                Socket().use { socket ->
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress(HOST, PORT), SOCKET_TIMEOUT_MS)
                    socket.soTimeout = SOCKET_TIMEOUT_MS
                    socket.getOutputStream().write(bytes)
                    socket.getOutputStream().flush()

                    // Wait for daemon ACK ("OK\n") before closing so adbd doesn't drop
                    // buffered data via premature CLSE while OPEN/OKAY is in flight.
                    val ack = socket.getInputStream().read()
                    if (ack == 'O'.code) {
                        return Pair(attempt, null)
                    }
                    lastError = "no_ack(read=$ack)"
                }
            } catch (e: Exception) {
                lastError = "${e.javaClass.simpleName}: ${e.message ?: "unknown"}"
            }
            if (attempt < 3) {
                try {
                    Thread.sleep(150L * attempt)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        return Pair(3, lastError ?: "unknown")
    }

    private fun duplicateReason(
        prev: SentRecord?,
        title: String,
        extracted: ExtractedMessage,
        now: Long
    ): String? {
        if (prev == null) return null

        if (extracted.hasStructuredMessages) {
            // Unchanged MessagingStyle re-post when another chat in the group updates
            if (prev.title == title &&
                prev.text == extracted.text &&
                prev.latestTime == extracted.latestTime &&
                prev.messageCount == extracted.messageCount
            ) {
                return "dup_msg_unchanged"
            }
        } else if (extracted.lineCount > 0) {
            // Shrinking InboxStyle summary (e.g. a chat was dismissed) or identical re-post
            if (extracted.lineCount < prev.lineCount) {
                return "dup_lines_shrunk"
            }
            if (prev.title == title &&
                prev.text == extracted.text &&
                prev.lineCount == extracted.lineCount
            ) {
                return "dup_lines_unchanged"
            }
        }

        if (prev.title == title &&
            prev.text == extracted.text &&
            (now - prev.timestamp) < DEDUP_WINDOW_MS
        ) {
            return "dup_1s"
        }
        return null
    }

    private fun preview(value: String, maxLen: Int = 36): String =
        if (value.length <= maxLen) value else value.take(maxLen) + "..."

    private fun resolveAppName(pkgName: String): String {
        val label = try {
            val pm = applicationContext.packageManager
            val appInfo = pm.getApplicationInfo(pkgName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkgName
        }
        return sanitizeField(label).ifBlank { pkgName }
    }

    private fun sanitizeField(value: String): String =
        value.replace('|', '/').replace('\n', ' ').replace('\r', ' ').trim()

    private fun extractLatestMessage(extras: Bundle): ExtractedMessage {
        // Inspect MessagingStyle array (WhatsApp, Signal, Telegram), picking newest by timestamp
        @Suppress("DEPRECATION")
        val rawMessages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (!rawMessages.isNullOrEmpty()) {
            var latestBundle: Bundle? = null
            var latestTime = Long.MIN_VALUE
            for (item in rawMessages) {
                if (item is Bundle) {
                    val msgTime = item.getLong("time", Long.MIN_VALUE)
                    if (latestBundle == null || msgTime >= latestTime) {
                        latestBundle = item
                        latestTime = msgTime
                    }
                }
            }
            if (latestBundle != null) {
                val msgText = latestBundle.getCharSequence("text")?.toString()?.trim()
                if (!msgText.isNullOrBlank()) {
                    return ExtractedMessage(
                        text = msgText,
                        latestTime = latestTime,
                        messageCount = rawMessages.size,
                        lineCount = 0,
                        hasStructuredMessages = true
                    )
                }
                if (latestBundle.containsKey("type") || latestBundle.containsKey("uri")) {
                    val mediaFallback = firstNonBlank(
                        extras.getCharSequence(Notification.EXTRA_TEXT),
                        extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                    ) ?: "Attachment"
                    return ExtractedMessage(
                        text = mediaFallback,
                        latestTime = latestTime,
                        messageCount = rawMessages.size,
                        lineCount = 0,
                        hasStructuredMessages = true
                    )
                }
            }
        }

        // Inbox style lines
        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (!textLines.isNullOrEmpty()) {
            val lastLine = textLines.lastOrNull { !it.isNullOrBlank() }?.toString()?.trim()
            if (!lastLine.isNullOrBlank()) {
                return ExtractedMessage(
                    text = lastLine,
                    latestTime = 0L,
                    messageCount = 0,
                    lineCount = textLines.size,
                    hasStructuredMessages = false
                )
            }
        }

        // General fallback chain
        val fallback = firstNonBlank(
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
            extras.getCharSequence(Notification.EXTRA_TEXT),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT),
            extras.getCharSequence(Notification.EXTRA_INFO_TEXT)
        ) ?: ""

        return ExtractedMessage(
            text = fallback,
            latestTime = 0L,
            messageCount = 0,
            lineCount = 0,
            hasStructuredMessages = false
        )
    }

    private fun firstNonBlank(vararg values: CharSequence?): String? {
        for (value in values) {
            val str = value?.toString()?.trim()
            if (!str.isNullOrBlank()) return str
        }
        return null
    }
}

package com.example.voidroid

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory circular buffer storing recent notification service events without disk writes.
 */
object NotificationLogStore {

    private const val MAX_ENTRIES = 50

    private val lock = Any()
    private val entries = ArrayDeque<String>(MAX_ENTRIES)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun log(tag: String, message: String) {
        val line = synchronized(lock) {
            val ts = timeFormat.format(Date())
            val formatted = "[$ts] $tag: $message"
            if (entries.size >= MAX_ENTRIES) {
                entries.removeFirst()
            }
            entries.addLast(formatted)
            formatted
        }
        android.util.Log.d("VoidroidLog", line)
    }

    fun dumpNewestFirst(): String = synchronized(lock) {
        if (entries.isEmpty()) {
            "No events recorded in RAM yet."
        } else {
            entries.reversed().joinToString("\n")
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
        }
    }
}

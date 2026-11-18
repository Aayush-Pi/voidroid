/*
 * voidroid - In-memory circular ring buffer for Android notification service logs
 * Copyright (C) 2026 Aayush-Pi
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

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

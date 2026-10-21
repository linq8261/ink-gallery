package com.ink.gallery.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object DateFormatter {

    fun formatTimestamp(millis: Long, timeZone: TimeZone = TimeZone.getDefault()): String {
        if (millis <= 0L) return ""
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        formatter.timeZone = timeZone
        return formatter.format(Date(millis))
    }

    fun formatCount(count: Int): String = "$count 张"
}

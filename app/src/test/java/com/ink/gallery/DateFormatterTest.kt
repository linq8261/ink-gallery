package com.ink.gallery

import com.ink.gallery.util.DateFormatter
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class DateFormatterTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    @Test
    fun formatsMillisInGivenTimeZone() {
        assertEquals("2024-01-02 03:04", DateFormatter.formatTimestamp(1704164640000L, utc))
    }

    @Test
    fun returnsEmptyStringForInvalidTimestamp() {
        assertEquals("", DateFormatter.formatTimestamp(0L, utc))
    }

    @Test
    fun formatsPhotoCount() {
        assertEquals("12 张", DateFormatter.formatCount(12))
    }
}

package com.ink.gallery.util

import java.util.Locale

/** 文件大小显示：KB / MB / GB */
fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = bytes / 1024.0
    return when {
        kb < 1024 -> String.format(Locale.getDefault(), "%.0f KB", kb)
        kb < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", kb / 1024)
        else -> String.format(Locale.getDefault(), "%.2f GB", kb / 1024 / 1024)
    }
}

package com.platter.desktop.ui

import com.platter.desktop.i18n.t

fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

fun formatSeconds(seconds: Int?): String = formatClock((seconds ?: 0) * 1000L)

/** "1 hr 5 min" or "45 min", for album and playlist headers. */
fun formatTotal(seconds: Int): String {
    val minutes = seconds / 60
    return if (minutes >= 60) t("%d hr %d min", minutes / 60, minutes % 60) else t("%d min", minutes)
}

/** "850 KB", "12.4 MB", "1.3 GB" - sizes as a person reads them. */
fun formatBytes(bytes: Long): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_048_576 -> "%.0f KB".format(bytes / 1_024.0)
    bytes < 1_073_741_824 -> "%.1f MB".format(bytes / 1_048_576.0)
    else -> "%.2f GB".format(bytes / 1_073_741_824.0)
}

package com.cappielloantonio.tempo.subsonic.models

import android.os.Parcelable
import androidx.annotation.Keep
import kotlinx.parcelize.Parcelize
import java.text.DateFormat
import java.util.Calendar
import java.util.Locale

@Keep
@Parcelize
open class ItemDate : Parcelable {
    var year: Int? = null
    var month: Int? = null
    var day: Int? = null

    /**
     * The date as a reader would write it - "12 марта 2026 г.", "March 12,
     * 2026" - or just the year when that is all the server knows.
     *
     * The month comes from the server counted from 1 and Calendar counts from
     * 0, so every date used to come out a month late; a missing day became
     * the last day of the month before.
     */
    fun getFormattedDate(): String {
        val year = year ?: return ""
        val month = month
        val day = day
        if (month == null || day == null) return year.toString()

        val calendar = Calendar.getInstance()
        calendar.set(year, month - 1, day)

        return DateFormat.getDateInstance(DateFormat.LONG, Locale.getDefault()).format(calendar.time)
    }
}
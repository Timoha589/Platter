package com.cappielloantonio.tempo.deemix

import android.content.Context
import com.cappielloantonio.tempo.R

/** How the site's account and quota read on screen - the same words in settings and in search. */
object DeemixQuota {
    @JvmStatic
    fun role(context: Context, role: String?): String = context.getString(when (role) {
        "admin" -> R.string.deemix_role_admin
        "special" -> R.string.deemix_role_special
        else -> R.string.deemix_role_user
    })

    @JvmStatic
    fun describe(context: Context, usage: DeemixUsage?): String {
        if (usage == null) return ""

        val remaining = usage.remaining()
                ?: return context.getString(R.string.deemix_quota_unlimited, usage.userDaily ?: 0)

        return when {
            remaining == 0 -> context.getString(R.string.deemix_quota_exhausted)
            usage.globalIsTighter() -> context.getString(R.string.deemix_quota_left_global, remaining)
            else -> context.getString(R.string.deemix_quota_left, remaining, usage.userLimit ?: 0)
        }
    }
}

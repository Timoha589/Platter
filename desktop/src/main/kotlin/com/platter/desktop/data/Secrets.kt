package com.platter.desktop.data

import com.sun.jna.platform.win32.Crypt32Util
import java.util.Base64

/**
 * Keeps the password the wave service needs without writing it down in the
 * clear: sealed with Windows DPAPI, which ties it to this Windows user on this
 * machine. Anyone who can read settings.json still cannot read the password
 * unless they are this user.
 *
 * Returns null instead of failing where DPAPI is not there (another OS, a
 * locked-down profile) - the caller then simply has no password to send, and
 * the service falls back to its own password file.
 */
object Secrets {
    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    fun seal(plain: String): String? {
        if (!windows) return null
        return try {
            Base64.getEncoder().encodeToString(Crypt32Util.cryptProtectData(plain.toByteArray(Charsets.UTF_8)))
        } catch (e: Throwable) {
            null
        }
    }

    fun open(sealed: String?): String? {
        if (sealed.isNullOrEmpty() || !windows) return null
        return try {
            String(Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(sealed)), Charsets.UTF_8)
        } catch (e: Throwable) {
            null
        }
    }
}

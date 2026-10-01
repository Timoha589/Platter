package com.cappielloantonio.tempo.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.github.models.LatestRelease
import com.cappielloantonio.tempo.github.utils.UpdateUtil
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A release newer than the running build, with the one APK worth installing. */
class UpdateInfo(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val size: Long,
    /** Lower-case hex SHA-256 as published by GitHub, or null for older uploads. */
    val sha256: String?,
)

enum class UpdatePhase { IDLE, DOWNLOADING, INSTALLING, NEEDS_PERMISSION, FAILED }

class UpdateState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val progress: Int = 0,
    val error: String? = null,
)

fun interface UpdateCheckCallback {
    /** Main thread. [info] is null when this build is current; [error] when the check failed. */
    fun onChecked(info: UpdateInfo?, error: String?)
}

/**
 * Updates Platter from its own GitHub releases, without leaving the app: ask the
 * Releases API for the latest tag, stream the APK into the app's cache, check
 * it, and hand it to the system PackageInstaller.
 *
 * Android still shows its own "Update?" confirmation - an app cannot install
 * silently - and only accepts an APK signed with the same key as the installed
 * one, so a tampered download cannot take over the app; the checksum is there to
 * catch a damaged one before the user is asked anything.
 *
 * It is a process-wide singleton: a download goes on when the dialog is closed.
 */
object AppUpdater {
    const val ACTION_INSTALL_RESULT = "com.platter.music.UPDATE_INSTALL_RESULT"

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    // Deliberately not the Retrofit client: that one logs response bodies, and
    // this body is the APK.
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _state = MutableLiveData(UpdateState())

    @JvmStatic
    val state: LiveData<UpdateState> get() = _state

    @Volatile
    private var busy = false

    /** The release the dialog is about; set by every check that finds one. */
    @JvmStatic
    @Volatile
    var pending: UpdateInfo? = null

    @JvmStatic
    fun check(callback: UpdateCheckCallback) {
        io.execute {
            var info: UpdateInfo? = null
            var error: String? = null
            try {
                info = fetchNewer()
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            main.post {
                if (info != null) pending = info
                callback.onChecked(info, error)
            }
        }
    }

    private fun fetchNewer(): UpdateInfo? {
        val response = App.getGithubClientInstance().releaseClient.latestRelease.execute()

        // No release has been published yet - nothing to update to.
        if (response.code() == 404) return null
        if (!response.isSuccessful) throw IOException("GitHub: HTTP ${response.code()}")

        val release: LatestRelease = response.body() ?: return null
        if (release.draft == true || release.prerelease == true) return null
        if (!UpdateUtil.isNewer(release.tagName)) return null

        val apk = release.assets.firstOrNull { it.name?.endsWith(".apk", ignoreCase = true) == true }
            ?: return null
        val url = apk.browserDownloadUrl ?: return null

        return UpdateInfo(
            version = UpdateUtil.cleanVersion(release.tagName),
            notes = release.body?.trim().orEmpty(),
            apkUrl = url,
            size = apk.size ?: 0L,
            sha256 = apk.digest?.removePrefix("sha256:")?.lowercase(),
        )
    }

    /** Whether Android lets Platter install packages yet ("Install unknown apps"). */
    @JvmStatic
    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /**
     * Download (unless already downloaded) and install. Without the one-time
     * "Install unknown apps" permission it opens that settings page instead and
     * waits in NEEDS_PERMISSION; call again once the user is back.
     */
    @JvmStatic
    fun install(context: Context, info: UpdateInfo) {
        val app = context.applicationContext

        if (!canInstall(app)) {
            post(UpdateState(UpdatePhase.NEEDS_PERMISSION))
            app.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }

        if (busy) return
        busy = true

        io.execute {
            try {
                val file = download(app, info)
                post(UpdateState(UpdatePhase.INSTALLING))
                commit(app, file)
            } catch (e: Exception) {
                fail(e.message ?: e.javaClass.simpleName)
            } finally {
                busy = false
            }
        }
    }

    private fun download(context: Context, info: UpdateInfo): File {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        val target = File(dir, "Platter-${info.version}.apk")

        // Whatever is left from an earlier version or an interrupted attempt.
        dir.listFiles()?.filter { it != target }?.forEach { it.delete() }

        if (target.exists() && matches(target, info)) return target
        target.delete()

        val part = File(dir, "Platter-${info.version}.apk.part")
        val digest = MessageDigest.getInstance("SHA-256")

        post(UpdateState(UpdatePhase.DOWNLOADING, 0))

        client.newCall(Request.Builder().url(info.apkUrl).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub: HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty response")
            val total = body.contentLength().takeIf { it > 0 } ?: info.size

            part.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPercent = -1

                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n

                        val percent = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
                        if (percent != lastPercent) {
                            lastPercent = percent
                            post(UpdateState(UpdatePhase.DOWNLOADING, percent))
                        }
                    }
                }
            }
        }

        if (info.size > 0 && part.length() != info.size) {
            part.delete()
            throw IOException(context.getString(R.string.update_error_incomplete))
        }
        if (info.sha256 != null && toHex(digest.digest()) != info.sha256) {
            part.delete()
            throw IOException(context.getString(R.string.update_error_checksum))
        }
        if (!part.renameTo(target)) throw IOException("Could not store the download")

        return target
    }

    /** An APK left from an earlier attempt is reused only if it is whole. */
    private fun matches(file: File, info: UpdateInfo): Boolean {
        if (info.size > 0 && file.length() != info.size) return false
        val expected = info.sha256 ?: return true

        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return toHex(digest.digest()) == expected
    }

    private fun commit(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setSize(file.length())

        // Android 12+: skips the confirmation when Platter is updating itself
        // and was itself the installer; otherwise it still asks, which is fine.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }

        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            file.inputStream().use { input ->
                session.openWrite("platter.apk", 0, file.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }

            // The system fills in the status extras, so the intent must be mutable
            // (and, from Android 14, explicit - which it is).
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val result = PendingIntent.getBroadcast(
                context, id,
                Intent(context, UpdateInstallReceiver::class.java).setAction(ACTION_INSTALL_RESULT),
                flags
            )
            session.commit(result.intentSender)
        }
    }

    /** From [UpdateInstallReceiver]: the install is over, one way or another. */
    internal fun finished() = post(UpdateState(UpdatePhase.IDLE))

    internal fun fail(message: String) = post(UpdateState(UpdatePhase.FAILED, error = message))

    private fun post(state: UpdateState) = _state.postValue(state)

    private fun toHex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}

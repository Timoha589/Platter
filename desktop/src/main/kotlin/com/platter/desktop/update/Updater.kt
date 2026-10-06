package com.platter.desktop.update

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** The version this build was made as. The build writes it as `-Dplatter.version`; a run from the sources has none. */
object AppVersion {
    val current: String? get() = System.getProperty("platter.version")?.takeIf { it.isNotBlank() }
}

/** A release newer than the running build, with the installer worth running. */
class UpdateInfo(
    val version: String,
    val notes: String,
    val url: String,
    val size: Long,
    /** Lower-case hex SHA-256 as published by GitHub, or null for older uploads. */
    val sha256: String?,
)

/** The pieces of GitHub's Releases API that are read; the rest is ignored. */
private class Release(
    @SerializedName("tag_name") val tag: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<Asset> = emptyList(),
)

private class Asset(
    val name: String? = null,
    @SerializedName("browser_download_url") val url: String? = null,
    val size: Long = 0,
    val digest: String? = null,
)

/**
 * Updates Platter for Windows from its own GitHub releases, the way the phone does: ask GitHub which release is
 * newest, stream its `.msi` to the disk, check it, and hand it to Windows Installer.
 *
 * A running program cannot be replaced, so [install] starts a small PowerShell script that waits for this process to
 * end, runs the installer, and opens Platter again; the caller then quits. The MSI is a major upgrade of the one
 * that is installed (same upgrade code, per user), so it needs no administrator and leaves no second copy.
 *
 * The phone's updater reads `releases/latest`; this one reads the list and takes the newest release that carries an
 * `.msi`, so a release made only for Windows (published with `--latest=false`) never hides the phone's.
 */
class Updater(
    private val directory: Path,
    /** The version running; null (a run from the sources) means there is nothing to update, and nothing is asked. */
    val currentVersion: String? = AppVersion.current,
    private val releasesUrl: String = RELEASES_URL,
    private val http: OkHttpClient = DEFAULT_HTTP,
    /** How the installer is started; tests replace it so no installer runs. */
    private val launch: (Path, Path) -> Unit = ::startInstaller,
) {
    private val gson = Gson()

    /** The newest release with an installer, if it is newer than this build. */
    suspend fun newer(): UpdateInfo? {
        val current = currentVersion ?: return null
        return withContext(Dispatchers.IO) {
            val request = Request.Builder().url(releasesUrl).header("Accept", "application/vnd.github+json").build()
            val releases: List<Release> = http.newCall(request).execute().use { response ->
                // No release has been published yet - nothing to update to.
                if (response.code == 404) return@withContext null
                if (!response.isSuccessful) throw IOException("GitHub: HTTP ${response.code}")
                val text = response.body?.string().orEmpty()
                gson.fromJson(text, Array<Release>::class.java)?.toList().orEmpty()
            }

            releases.asSequence()
                .filter { !it.draft && !it.prerelease }
                .mapNotNull { release ->
                    val asset = release.assets.firstOrNull { it.name?.endsWith(".msi", ignoreCase = true) == true && it.url != null }
                    val version = release.tag?.let(::versionOf)
                    if (asset == null || version == null) null else Triple(release, asset, version)
                }
                .filter { (_, _, version) -> compare(version, current) > 0 }
                .maxWithOrNull { a, b -> compare(a.third, b.third) }
                ?.let { (release, asset, version) ->
                    UpdateInfo(
                        version = version,
                        notes = release.body?.trim().orEmpty(),
                        url = asset.url!!,
                        size = asset.size,
                        sha256 = asset.digest?.removePrefix("sha256:")?.lowercase(),
                    )
                }
        }
    }

    /**
     * Fetches the installer into the update folder and returns it; [progress] gets 0..1 as it comes. A copy left by an
     * earlier try is used if it is whole. Whatever else is in the folder (older versions) is cleared first.
     */
    suspend fun download(info: UpdateInfo, progress: (Float) -> Unit = {}): Path = withContext(Dispatchers.IO) {
        Files.createDirectories(directory)
        val target = directory.resolve("Platter-${info.version}.msi")
        Files.list(directory).use { files ->
            files.filter { it != target && it.fileName.toString().let { n -> n.endsWith(".msi") || n.endsWith(".part") } }
                .forEach { Files.deleteIfExists(it) }
        }

        if (Files.exists(target) && whole(target, info)) return@withContext target
        Files.deleteIfExists(target)

        val part = directory.resolve("Platter-${info.version}.msi.part")
        val digest = MessageDigest.getInstance("SHA-256")
        progress(0f)

        http.newCall(Request.Builder().url(info.url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub: HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty response")
            val total = body.contentLength().takeIf { it > 0 } ?: info.size

            Files.newOutputStream(part).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        if (total > 0) progress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }

        if (info.size > 0 && Files.size(part) != info.size) {
            Files.deleteIfExists(part)
            throw IOException(INCOMPLETE)
        }
        if (info.sha256 != null && hex(digest.digest()) != info.sha256) {
            Files.deleteIfExists(part)
            throw IOException(CHECKSUM)
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING)
        progress(1f)
        target
    }

    /** Starts the installer for [msi] to run once this process has gone, and Platter again after it. The caller then quits. */
    fun install(msi: Path) = launch(msi, directory)

    /** A file left from an earlier try is reused only if it is whole. */
    private fun whole(file: Path, info: UpdateInfo): Boolean {
        if (info.size > 0 && Files.size(file) != info.size) return false
        val expected = info.sha256 ?: return true
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return hex(digest.digest()) == expected
    }

    companion object {
        const val RELEASES_URL = "https://api.github.com/repos/Timoha589/Platter/releases?per_page=30"

        /** The messages of a download that arrived damaged; the app shows them under its own words. */
        const val INCOMPLETE = "The download is incomplete"
        const val CHECKSUM = "The download does not match its checksum"

        // Not the Subsonic client: its call timeout would cut off a slow download, and this body is a whole installer.
        private val DEFAULT_HTTP: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        /** The digits of a release tag: "v1.2.0" and "desktop-v1.2.0" are both 1.2.0; null for a tag with none. */
        fun versionOf(tag: String): String? = Regex("""\d+(\.\d+)*""").find(tag)?.value

        /** Positive when [a] is the newer version. A missing part counts as zero, so 1.2 equals 1.2.0; a suffix like -beta is ignored. */
        fun compare(a: String, b: String): Int {
            val left = parts(a)
            val right = parts(b)
            for (i in 0 until maxOf(left.size, right.size)) {
                val l = left.getOrElse(i) { 0 }
                val r = right.getOrElse(i) { 0 }
                if (l != r) return l.compareTo(r)
            }
            return 0
        }

        private fun parts(version: String): List<Int> =
            (versionOf(version) ?: "0").split('.').map { it.toIntOrNull() ?: 0 }

        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

        /** The script that waits for Platter to close, installs, and opens Platter again. */
        private val SCRIPT = """
            param([int]${'$'}ProcessId, [string]${'$'}Msi, [string]${'$'}Exe, [string]${'$'}Log)
            Wait-Process -Id ${'$'}ProcessId -Timeout 60 -ErrorAction SilentlyContinue
            Start-Process msiexec -ArgumentList @('/i', "`"${'$'}Msi`"", '/qb', '/norestart', '/l*v', "`"${'$'}Log`"") -Wait
            if (${'$'}Exe -and (Test-Path ${'$'}Exe)) { Start-Process ${'$'}Exe }
        """.trimIndent()

        private fun startInstaller(msi: Path, directory: Path) {
            val script = directory.resolve("apply-update.ps1")
            // Windows PowerShell 5 reads a script without a byte-order mark as the system code page.
            Files.write(script, byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + SCRIPT.toByteArray(Charsets.UTF_8))

            // Platter.exe is the launcher of the installed app; from the sources the command is java, which is not to be reopened.
            val exe = ProcessHandle.current().info().command().orElse(null)?.takeIf { it.endsWith("Platter.exe", ignoreCase = true) }

            val command = mutableListOf(
                "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
                "-File", script.toString(),
                "-ProcessId", ProcessHandle.current().pid().toString(),
                "-Msi", msi.toString(),
                "-Log", directory.resolve("install.log").toString(),
            )
            if (exe != null) command += listOf("-Exe", exe)

            ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        }
    }
}

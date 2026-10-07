package com.platter.desktop.player

import com.platter.desktop.log.AppLog
import uk.co.caprica.vlcj.binding.lib.LibC
import uk.co.caprica.vlcj.binding.support.runtime.RuntimeUtil
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.factory.discovery.strategy.BaseNativeDiscoveryStrategy
import uk.co.caprica.vlcj.factory.discovery.strategy.NativeDiscoveryStrategy
import uk.co.caprica.vlcj.factory.discovery.strategy.WindowsNativeDiscoveryStrategy
import java.io.File

/**
 * Finds libvlc. The installer carries its own, audio only (`vlc/` among the app's resources: libvlc, libvlccore and
 * the plugins music needs), so nothing has to be installed beside Platter; a VLC installed on the machine is only the
 * fallback, for a run from the sources or a copy without the bundle.
 */
object VlcDiscovery {
    /** Where the bundle is looked for besides the app's resources; set by the tests that check the bundle itself. */
    const val DIRECTORY_PROPERTY = "platter.vlc"

    /** The folder holding the bundled libvlc.dll, or null when this copy of Platter has none. */
    fun bundledDirectory(): File? {
        val candidates = listOfNotNull(
            System.getProperty(DIRECTORY_PROPERTY),
            System.getProperty("compose.application.resources.dir")?.let { File(it, "vlc").path },
        )
        return candidates.map(::File).firstOrNull { File(it, "libvlc.dll").isFile && File(it, "libvlccore.dll").isFile && File(it, "plugins").isDirectory }
    }

    /** The folder libvlc was loaded from, once [discover] has found it. */
    @Volatile
    var loadedFrom: String? = null
        private set

    /** True when libvlc was found and loaded; says in the log where from. */
    fun discover(): Boolean {
        val bundled = bundledDirectory()
        val strategies = buildList<NativeDiscoveryStrategy> {
            if (bundled != null) add(Bundled(bundled.path))
            add(WindowsNativeDiscoveryStrategy())
        }
        val discovery = NativeDiscovery(*strategies.toTypedArray())
        val found = discovery.discover()
        if (found) {
            loadedFrom = discovery.discoveredPath()
            AppLog.info("libvlc loaded from ${discovery.discoveredPath()}" + if (bundled == null) " (an installed VLC: this copy has no bundled one)" else "")
        } else {
            AppLog.error("libvlc was not found" + if (bundled != null) " (the bundled one in ${bundled.path} would not load, and no VLC is installed)" else " (no bundled copy, no VLC installed)")
        }
        return found
    }

    /** The bundle: libvlc and libvlccore in [directory], its plugins beside them. */
    private class Bundled(private val directory: String) : BaseNativeDiscoveryStrategy(
        arrayOf("libvlc\\.dll", "libvlccore\\.dll"),
        arrayOf("%s\\plugins"),
    ) {
        override fun supported(): Boolean = RuntimeUtil.isWindows()

        override fun discoveryDirectories(): List<String> = listOf(directory)

        override fun setPluginPath(pluginPath: String): Boolean = LibC.INSTANCE._putenv("VLC_PLUGIN_PATH=$pluginPath") == 0
    }
}

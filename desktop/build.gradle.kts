import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.1.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21"
    id("org.jetbrains.compose") version "1.8.1"
}

/** The one place the version is written: the installer, the running app (`-Dplatter.version`) and the updater all read it. */
val platterVersion = "1.0.2"

group = "com.platter"
version = platterVersion

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("io.coil-kt.coil3:coil-compose:3.2.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.2.0")

    implementation("uk.co.caprica:vlcj:4.8.3")
    // libvlc is reached through JNA; jna-platform adds Windows DPAPI for the stored password. Kept on one version.
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")

    testImplementation(kotlin("test"))
    @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
    testImplementation(compose.uiTest)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

tasks.test {
    useJUnitPlatform()
    // The tests read the interface in English, whatever the language of the machine they run on.
    jvmArgs("-Duser.language=en", "-Duser.country=US")
}

// --- libvlc for the installer, audio only -------------------------------------------------------------------
// Platter plays through libvlc, and the people it is given to should not have to install VLC first. The 64-bit libvlc
// of an installed VLC (-PvlcHome=..., or the VLC_HOME variable, or the usual place) is copied with only the plugins
// that music needs - access, demuxers and decoders for audio, the audio outputs and filters, TLS for https; no video
// output, filters or decoders, no interface, no Lua. That is about a third of VLC's own size.
val vlcHome: String = (findProperty("vlcHome") as String?) ?: System.getenv("VLC_HOME") ?: "C:/Program Files/VideoLAN/VLC"

val vlcPlugins = listOf(
    "access/libfilesystem_plugin.dll", "access/libhttp_plugin.dll", "access/libhttps_plugin.dll", "access/libtcp_plugin.dll",
    "access/libidummy_plugin.dll", "access/libattachment_plugin.dll",
    "audio_filter/*.dll", "audio_mixer/*.dll", "audio_output/*.dll",
    "codec/libaraw_plugin.dll", "codec/libavcodec_plugin.dll", "codec/libflac_plugin.dll", "codec/libmpg123_plugin.dll",
    "codec/libopus_plugin.dll", "codec/libvorbis_plugin.dll", "codec/libfaad_plugin.dll", "codec/libspeex_plugin.dll",
    "codec/libadpcm_plugin.dll", "codec/libaes3_plugin.dll", "codec/libg711_plugin.dll", "codec/liblpcm_plugin.dll",
    "codec/libspdif_plugin.dll", "codec/libddummy_plugin.dll", "codec/libedummy_plugin.dll",
    "demux/libadaptive_plugin.dll", "demux/libaiff_plugin.dll", "demux/libasf_plugin.dll", "demux/libau_plugin.dll",
    "demux/libcaf_plugin.dll", "demux/libdirectory_demux_plugin.dll", "demux/libes_plugin.dll", "demux/libflacsys_plugin.dll",
    "demux/libmkv_plugin.dll", "demux/libmp4_plugin.dll", "demux/libmpc_plugin.dll", "demux/libnoseek_plugin.dll",
    "demux/libogg_plugin.dll", "demux/libplaylist_plugin.dll", "demux/libps_plugin.dll", "demux/librawaud_plugin.dll",
    "demux/libts_plugin.dll", "demux/libtta_plugin.dll", "demux/libvoc_plugin.dll", "demux/libwav_plugin.dll", "demux/libxa_plugin.dll",
    "keystore/*.dll", "logger/libfile_logger_plugin.dll",
    "misc/libgnutls_plugin.dll", "misc/libxml_plugin.dll",
    "packetizer/libpacketizer_a52_plugin.dll", "packetizer/libpacketizer_copy_plugin.dll", "packetizer/libpacketizer_dts_plugin.dll",
    "packetizer/libpacketizer_flac_plugin.dll", "packetizer/libpacketizer_mlp_plugin.dll",
    "packetizer/libpacketizer_mpeg4audio_plugin.dll", "packetizer/libpacketizer_mpegaudio_plugin.dll",
    "stream_filter/*.dll",
    // Four tiny ones that show nothing: vlcj sets their options on every player, and without them libvlc logs an error per option.
    "spu/liblogo_plugin.dll", "spu/libmarq_plugin.dll", "spu/libsubsdelay_plugin.dll", "video_filter/libadjust_plugin.dll", "video_output/libvmem_plugin.dll",
)

val prepareVlc by tasks.registering(Sync::class) {
    val home = file(vlcHome)
    into(layout.buildDirectory.dir("vlc-resources"))
    from(home) {
        include("libvlc.dll", "libvlccore.dll", "COPYING.txt", "AUTHORS.txt")
        into("windows-x64/vlc")
    }
    from(File(home, "plugins")) {
        include(vlcPlugins)
        into("windows-x64/vlc/plugins")
    }
    doFirst {
        if (!File(home, "libvlc.dll").isFile) {
            logger.warn("VLC was not found in $vlcHome (give -PvlcHome=<folder>): the build has no bundled audio engine.")
        }
    }
}

// An installer without libvlc would play nothing on a machine without VLC, so building one without it is an error.
tasks.matching { it.name == "packageMsi" || it.name == "createDistributable" }.configureEach {
    dependsOn(prepareVlc)
    doFirst {
        check(File(vlcHome, "libvlc.dll").isFile) { "No VLC to take libvlc from in $vlcHome: install 64-bit VLC or pass -PvlcHome=<folder>." }
    }
}

tasks.test {
    // The bundle test plays through the bundle alone.
    dependsOn(prepareVlc)
    systemProperty("platter.test.vlcBundle", layout.buildDirectory.dir("vlc-resources/windows-x64/vlc").get().asFile.path)
}

compose.desktop {
    application {
        mainClass = "com.platter.desktop.MainKt"
        // What the updater compares a release against; set for the installed app and for `run` alike.
        jvmArgs("-Dplatter.version=$platterVersion")

        nativeDistributions {
            // libvlc: whatever is under windows-x64/ there is copied next to the app and found by VlcDiscovery.
            appResourcesRootDir.set(layout.dir(prepareVlc.map { it.destinationDir }))
            // Only the MSI: the app updates itself by running the next one over it.
            targetFormats(TargetFormat.Msi)
            // jlink keeps only what it can see: TLS curves, OkHttp's hostname check and JNA (libvlc) need these by name.
            modules("java.naming", "java.sql", "jdk.crypto.ec", "jdk.unsupported")
            packageName = "Platter"
            packageVersion = platterVersion
            vendor = "Timoha589"
            windows {
                iconFile.set(project.file("icons/platter.ico"))
                menuGroup = "Platter"
                shortcut = true
                // Per user: installing and updating need no administrator, so the app can update itself without a prompt.
                perUserInstall = true
                // Fixed for good: it is how Windows knows a newer MSI replaces the installed Platter instead of sitting beside it.
                upgradeUuid = "07BD8386-05B1-4800-BD3D-2CA74B41BE78"
            }
        }
    }
}

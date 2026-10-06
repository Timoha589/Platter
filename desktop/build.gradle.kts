import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.1.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21"
    id("org.jetbrains.compose") version "1.8.1"
}

/** The one place the version is written: the installer, the running app (`-Dplatter.version`) and the updater all read it. */
val platterVersion = "1.0.0"

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

compose.desktop {
    application {
        mainClass = "com.platter.desktop.MainKt"
        // What the updater compares a release against; set for the installed app and for `run` alike.
        jvmArgs("-Dplatter.version=$platterVersion")

        nativeDistributions {
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

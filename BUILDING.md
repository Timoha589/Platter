# Building Platter into an APK

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| JDK | 17 or newer | Android Studio ships one at `<Studio>/jbr` — that is the safest choice |
| Android SDK | Platform **35** | `compileSdk 35` |
| Build tools | **35.0.0** | pinned in `app/build.gradle` |
| Gradle | 8.10.2 | downloaded automatically by `gradlew` |

`local.properties` must point at your SDK (it is git-ignored, one line):

```
sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk
```

The project has a single product flavour, `platter`, so Gradle task names carry it:
`assemblePlatterDebug`, `assemblePlatterRelease`, `bundlePlatterRelease`.

## 1. Debug APK — the quick path

Nothing to configure; Gradle signs it with the auto-generated debug key.

```bash
./gradlew assemblePlatterDebug
```

Output: `app/build/outputs/apk/platter/debug/app-platter-debug.apk`

Install it on a connected device:

```bash
adb install -r app/build/outputs/apk/platter/debug/app-platter-debug.apk
```

Or copy the file to the phone and open it (Android will ask you to allow installing from
unknown sources).

## 2. Release APK — signed, minified, what you actually ship

A release build runs R8 (`minifyEnabled` + `shrinkResources`), so it is smaller and faster than the
debug build. Android refuses to install an unsigned APK, so you need a keystore.

### 2.1 Create a keystore (once)

Run this yourself — it prompts for passwords, which should not pass through anything but your own
terminal. Keep the resulting file and passwords safe: **losing them means you can never ship an
update to the same app again.**

```bash
keytool -genkey -v -keystore platter-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias platter
```

### 2.2 Tell Gradle about it

`app/build.gradle` picks the keystore up from Gradle properties, so credentials never live in the
repo. Put these in `~/.gradle/gradle.properties` (on Windows:
`C:\Users\<you>\.gradle\gradle.properties`):

```properties
PLATTER_KEYSTORE=C:/keys/platter-release.jks
PLATTER_KEYSTORE_PASSWORD=<store password>
PLATTER_KEY_ALIAS=platter
PLATTER_KEY_PASSWORD=<key password>
```

If those properties are absent the build still succeeds — it just produces an *unsigned* APK.

### 2.3 Build

```bash
./gradlew assemblePlatterRelease
```

Output: `app/build/outputs/apk/platter/release/app-platter-release.apk`

Without the keystore properties the same task still succeeds, but the file is named
`app-platter-release-unsigned.apk` and cannot be installed until you sign it (section 3).

Verify the signature before distributing:

```bash
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs app/build/outputs/apk/platter/release/app-platter-release.apk
```

## 3. Signing an APK you already built unsigned

```bash
"$ANDROID_HOME/build-tools/35.0.0/zipalign" -p -f 4 in.apk aligned.apk
"$ANDROID_HOME/build-tools/35.0.0/apksigner" sign --ks platter-release.jks --out platter.apk aligned.apk
```

## 3a. Publishing an update (the app updates itself from GitHub)

Platter checks <https://github.com/Timoha589/Platter/releases/latest> on start and from
Settings > Check for updates, downloads the APK and hands it to Android's installer.

1. Raise `platterVersionName` at the top of `app/build.gradle` (e.g. `'1.0.1'`). `versionCode`
   follows from it (1.2.3 -> 10203); Android will not install an update whose code is not higher.
2. `./gradlew assemblePlatterRelease` - it must be signed with the **same key** as the installed
   app, or Android refuses the update.
3. Publish a release whose **tag is `v` + the version** and which carries the APK as an asset:

```
gh release create v1.0.1 app/build/outputs/apk/platter/release/app-platter-release.apk --repo Timoha589/Platter --title "Platter 1.0.1" --notes "What changed"
```

The release must be public, not a draft or pre-release, and the asset name must end in `.apk`.
The first install of a build must be done by hand (adb or file); only updates are automatic.
On a phone with Google Play Protect the install can be stopped with "App blocked": More details >
Install anyway.

## 3b. Publishing a release for phone and Windows together

One GitHub release carries both files, `app-platter-release.apk` and `Platter-<version>.msi`, under one version. The
phone reads `releases/latest` and takes the `.apk`; the Windows app lists the releases and takes the newest `.msi`
(see `desktop/README.md`, "Самообновление"). Each ignores the other's file.

Rules that keep both updaters working:

- **One version for both.** Set the same number in `platterVersionName` (`app/build.gradle`) and `platterVersion`
  (`desktop/build.gradle.kts`). Three numbers; the MSI needs each below 256.
- **Every release carries both files**, even when only one app changed (the other is rebuilt with the new number).
  A release with only an MSI would become "latest", and a phone that is a version behind would find no APK in it.
- Never change `upgradeUuid` in `desktop/build.gradle.kts`: it is how Windows knows a new MSI replaces the old one.
- Tags are `v` + the version and are never reused or overwritten; a mistake is fixed by the next version.

Building the MSI needs the WiX Toolset 3 on the machine that builds (not on the ones that install). The first install
on a computer is done by hand (run the MSI); later versions install themselves from inside the app.

1. Raise both version numbers (see above) and commit.
2. Phone: `./gradlew assemblePlatterRelease` (signed, see 2.2).
3. Windows: `cd desktop && ./gradlew packageMsi` - the installer lands in `desktop/build/compose/binaries/main/msi/`.
4. Give the MSI the same file name as the APK, so the release page shows `app-platter-release.apk` and
   `app-platter-release.msi` (the updater only looks at the `.msi` ending, so the name is free):

```
cp desktop/build/compose/binaries/main/msi/Platter-1.0.1.msi desktop/build/compose/binaries/main/msi/app-platter-release.msi
```

5. Publish one release with both files:

```
gh release create v1.0.1 app/build/outputs/apk/platter/release/app-platter-release.apk desktop/build/compose/binaries/main/msi/app-platter-release.msi --repo Timoha589/Platter --title "Platter 1.0.1" --notes "What changed"
```

To add the MSI to a release that already exists, upload it under that name: `gh release upload v1.0.0 <the copied .msi> --repo Timoha589/Platter`.
A published asset can be renamed without a new version:
`gh api -X PATCH repos/Timoha589/Platter/releases/assets/<asset id> -f name=<new name>` (ids: `gh api repos/Timoha589/Platter/releases/tags/v1.0.0 --jq '.assets[] | "\(.id) \(.name)"'`).

## 4. Google Play — an AAB, not an APK

Play requires an Android App Bundle:

```bash
./gradlew bundlePlatterRelease
```

Output: `app/build/outputs/bundle/platterRelease/app-platter-release.aab`

Note that the Play flavour of the upstream project was dropped in this fork, and shipping a Subsonic
client to Play has its own review considerations.

## 5. From Android Studio instead

Open the project root, wait for the Gradle sync, then:

- **Build → Select Build Variant** → `platterDebug` or `platterRelease`
- **Build → Build Bundle(s) / APK(s) → Build APK(s)** for a quick debug build
- **Build → Generate Signed Bundle / APK…** to create or select a keystore through the UI

## Troubleshooting

**`SDK location not found`** — `local.properties` is missing or the path is wrong. Use forward
slashes; a Windows path with single backslashes is parsed as escape sequences and silently mangled.

**`Your project path contains non-ASCII characters`** — this project lives under
`Мои проекты`, and the Android Gradle Plugin refuses such a path by default. `gradle.properties`
already waives the check with `android.overridePathCheck=true`. If a native/NDK toolchain ever
trips over the path anyway, the fix is to move the project to an ASCII path such as `C:/dev/Platter`.

**`Failed to find Build Tools revision 35.0.0`** — install it from the SDK Manager, or via
`sdkmanager "build-tools;35.0.0" "platforms;android-35"`.

**`Unsupported class file major version`** — Gradle is running on too new (or too old) a JDK. Pin it:

```bash
./gradlew assemblePlatterDebug -Dorg.gradle.java.home="C:/Program Files/Android/Android Studio/jbr"
```

**`INSTALL_FAILED_UPDATE_INCOMPATIBLE` on install** — a build signed with a different key is already
installed. Uninstall it first: `adb uninstall com.platter.music`.

**Debug and release will not coexist** — both use the application ID `com.platter.music`. Installing
one replaces the other.

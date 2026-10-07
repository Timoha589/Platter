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

## 3b. Publishing a release: phone, Windows, or both

There is one counter of versions for the whole project (1.0.1, 1.0.2, ...) and one GitHub release per number, tagged
`v` + the number. A release carries the file of each app that changed in it:

| What changed | Files in the release | How to publish |
|---|---|---|
| Phone only | `app-platter-release.apk` | plain `gh release create` |
| Windows only | `app-platter-release.msi` | add `--latest=false` |
| Both | the APK and the MSI | plain `gh release create` |

The phone reads `releases/latest` and takes the `.apk`; the Windows app lists the releases and takes the newest `.msi`
(see `desktop/README.md`, "Самообновление"). Each ignores the other's file, so an app that did not change in a release is
not asked to update.

Rules:

- **The number only goes up**, across both apps, and a tag is never reused or overwritten; a mistake is fixed by the next
  number.
- **Change the number only in the app you are releasing**: `platterVersionName` in `app/build.gradle` for the phone,
  `platterVersion` in `desktop/build.gradle.kts` for Windows. Windows' number must be **exactly the tag's number**, or the
  installed app would keep finding "a newer version" after it updated. So the numbers of the two apps may differ (the phone
  at 1.0.1 while Windows is still at 1.0.0); each is the number of the last release that carried its file.
- A release with **only an MSI must be published with `--latest=false`**. Otherwise it becomes "latest", and a phone that is
  a version behind finds no APK in it and never updates.
- The MSI needs each part of its number below 256. Never change `upgradeUuid` in `desktop/build.gradle.kts`: it is how
  Windows knows a new MSI replaces the installed Platter instead of sitting beside it.
- Give the files the same names every time, so the release page shows `app-platter-release.apk` and
  `app-platter-release.msi` (the updaters only look at the `.apk` / `.msi` ending, so the names are free).

Building the MSI needs the WiX Toolset 3 on the machine that builds (not on the ones that install). It also needs a
64-bit VLC installed there (`C:\Program Files\VideoLAN\VLC`, or `-PvlcHome=<folder>` / `VLC_HOME`): the build copies
its libvlc, audio plugins only, into the installer (about 40 MB), so the people who install Platter need no VLC. The first install on a
computer is done by hand (run the MSI); later versions install themselves from inside the app.

Before any release: set the number (see above), then

```
git add -A
git commit -m "What changed"
git push
```

**Phone** - `./gradlew assemblePlatterRelease` (signed with the same key as before, see 2.2). The APK is
`app/build/outputs/apk/platter/release/app-platter-release.apk`.

**Windows** - build the MSI and copy it under the release name:

```
cd desktop
./gradlew packageMsi
cp build/compose/binaries/main/msi/Platter-1.0.2.msi build/compose/binaries/main/msi/app-platter-release.msi
```

Then publish (use the number you set; the first command is for both files, the next two for one):

```
gh release create v1.0.2 app/build/outputs/apk/platter/release/app-platter-release.apk desktop/build/compose/binaries/main/msi/app-platter-release.msi --repo Timoha589/Platter --title "Platter 1.0.2" --notes "What changed"

gh release create v1.0.1 app/build/outputs/apk/platter/release/app-platter-release.apk --repo Timoha589/Platter --title "Platter 1.0.1" --notes "What changed"

gh release create v1.0.3 desktop/build/compose/binaries/main/msi/app-platter-release.msi --repo Timoha589/Platter --title "Platter 1.0.3" --notes "What changed" --latest=false
```

The notes are what the person sees in the update window. To add a file to a release that already exists:
`gh release upload v1.0.0 <file> --repo Timoha589/Platter`. A published file can be renamed without a new version:
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

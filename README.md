# Ripster

An on-device media downloader. Paste a link and Ripster works out what it points to:

- an **HLS stream** (`.m3u8`): choose a quality, download it, and get a standalone MP4;
- a **direct media file** (MP4, MKV, WebM): download it and keep it as is;
- a **web page**: Ripster reads the media links the page openly declares (video elements, Open
  Graph, schema.org) and uses the first one that passes the same checks as a pasted link.

Everything happens on the device: no backend, and no media is uploaded anywhere for processing.
Downloads run in the background, survive app restarts, resume after network loss, and can be
cancelled or retried from the queue.

Ripster is a Kotlin Multiplatform (KMP) project with Compose Multiplatform UI. Android is the
first and currently only implemented platform; the shared code is kept free of Android types so
an iOS app can be added later.

> Only download media you're allowed to download. Ripster never runs page scripts, never reads a
> site player's private configuration, and never bypasses DRM, sign-in, age verification or
> other access restrictions. A page that doesn't openly provide a downloadable source is reported
> as such, not worked around.

## Requirements

- Android Studio (or the Android SDK command-line tools) with API level 37 installed.
- JDK 17 or newer to start Gradle. The Gradle daemon itself runs on JDK 21
  (`gradle/gradle-daemon-jvm.properties`), which Gradle downloads automatically if missing.
- An Android device or emulator running Android 9 (API 28) or newer.

## Build, run and test

```bash
# Debug APK: androidApp/build/outputs/apk/debug/androidApp-debug.apk
./gradlew :androidApp:assembleDebug

# Build and install on the connected device
./gradlew :androidApp:installDebug

# Unit tests (commonTest and androidHostTest, on the local JVM; no device needed)
./gradlew :shared:testAndroidHostTest

# Android lint
./gradlew :androidApp:lint
```

The sample-stream picker is only shown in debug builds. Debug builds install as
`in.geekofia.ripster.debug` ("Ripster Debug"), next to a release build.

## Versions and release builds

The version lives in `version.properties` (`VERSION_NAME` in SemVer, `VERSION_CODE` as an
integer). Change it with the script rather than by hand:

```bash
scripts/bump-version.sh patch            # or: major | minor | build | set X.Y.Z
scripts/bump-version.sh minor --dry-run  # show the change without writing it
```

Release builds are minified with R8. To sign them, copy `keystore.properties.example` to
`keystore.properties` (git-ignored) and fill it in; without it, release builds are unsigned.

```bash
./gradlew :androidApp:assembleRelease   # APK
./gradlew :androidApp:bundleRelease     # AAB for Play
```

Every build also copies its outputs to `androidApp/build/dist/` with readable names and prints
their paths, e.g. `Ripster-v0.1.0-1-release.apk`, `Ripster-v0.1.0-1-release.aab` and
`Ripster-v0.1.0-1-release-mapping.txt` (R8's mapping, to read release stack traces).

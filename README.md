# Ripster

An on-device HLS downloader. Paste a direct link to an HLS manifest (`.m3u8`), inspect its
quality variants, download one, and get a standalone MP4. Everything happens on the device: no
backend, no uploading media anywhere for processing.

Ripster is a Kotlin Multiplatform (KMP) project with Compose Multiplatform UI. Android is the
first and currently only implemented platform; the shared code is kept free of Android types so
an iOS app can be added later.

> Only download media you're allowed to download. Ripster accepts direct manifest links only:
> it doesn't scrape web pages, extract from third-party sites, or bypass DRM, authentication or
> other access restrictions.

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

The sample-stream picker is only shown in debug builds.

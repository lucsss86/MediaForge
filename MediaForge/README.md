# MediaForge Android

MediaForge is a local Android media utility with four workflows:

- MP4 → MP3
- MP3 → MP4 (creates a normal MP4 video with a dark background and AAC audio)
- Direct media URL → MP3
- Direct media URL → MP4

## Privacy and storage

- Local conversions are performed on-device using FFmpegKit.
- The app uses Android's Storage Access Framework, so it does not request broad storage permission.
- URL mode accepts direct HTTP/HTTPS media URLs. If a URL returns HTML, it is rejected as a webpage rather than trying to bypass a site's download protections.

## Build in Android Studio

1. Open this folder in a recent Android Studio.
2. Let Gradle sync and download dependencies.
3. Select **Build > Build App Bundle(s) / APK(s) > Build APK(s)**.
4. The debug APK will be under `app/build/outputs/apk/debug/app-debug.apk`.

The project uses:

- compileSdk 35
- minSdk 24
- targetSdk 35
- Java 17
- Android Gradle Plugin 8.9.2 / Gradle 8.11.1
- `dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.9`

The FFmpegKit dependency is LGPL-family software. Review its license and any distribution obligations before publishing commercially.

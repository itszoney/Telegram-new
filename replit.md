# Zoneygram (Telegram X fork)

A custom Android client for Telegram based on [Telegram X](https://github.com/TGX-Android/Telegram-X) and [TDLib](https://github.com/tdlib/td).

## Project overview

- **Language**: Java / C++ (Android app)
- **Build system**: Gradle + Android NDK (CMake)
- **App ID**: `com.itszoney` (configurable via `local.properties`)
- **App name**: Zoneygram
- **Min NDK**: 23.2.8568313

This is not a web app — it cannot run in Replit's preview pane. Development and code changes are done here; the APK is built via GitHub Actions CI.

## How to build

Building happens in GitHub Actions (`.github/workflows/build-release.yml`). Push to `main`/`master` or trigger `workflow_dispatch` to kick off a build.

The workflow:
1. Checks out the repo with all submodules (`tdlib`, media codecs, etc.)
2. Sets up JDK 17, Android SDK, and NDK 23.2.8568313
3. Writes `local.properties` from GitHub Secrets (see below)
4. Runs `./gradlew assembleRelease`
5. Uploads APKs as workflow artifacts and (on tag/manual trigger) creates a GitHub Release

## Required GitHub Secrets

| Secret | Description |
|---|---|
| `TELEGRAM_API_ID` | Telegram API ID from https://my.telegram.org |
| `TELEGRAM_API_HASH` | Telegram API hash from https://my.telegram.org |
| `APP_ID` | Android application ID (default: `com.itszoney`) |
| `APP_NAME` | App display name (default: `Zoneygram`) |
| `APP_DOWNLOAD_URL` | Download URL shown in-app (default: `https://telegram.org/dlx`) |
| `SIGNING_KEYSTORE_BASE64` | Base64-encoded release keystore (optional; unsigned build if absent) |
| `SIGNING_STORE_PASSWORD` | Keystore password |
| `SIGNING_KEY_ALIAS` | Key alias |
| `SIGNING_KEY_PASSWORD` | Key password |

## Key submodules

- `tdlib/` — prebuilt TDLib + prebuilt OpenSSL for all ABIs (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`)
- `app/jni/third_party/` — FFmpeg, libvpx, Opus, WebRTC, and other native deps
- `vkryl/` — shared Android utilities (LevelDB, core, TDLib utils)

## User preferences

- Keep the existing project structure and Gradle/NDK stack.
- Do not add a web server or preview workflow — this is a native Android project.

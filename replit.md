# Telegram X – Android Client

A fork of [TGX-Android/Telegram-X](https://github.com/TGX-Android/Telegram-X), the official
alternative Android Telegram client built on TDLib, tgcalls, and WebRTC.

## Stack

| Layer | Technology |
|-------|-----------|
| Language | Java + Kotlin (app), C/C++ (JNI) |
| Build | Gradle (Kotlin DSL) + CMake + NDK |
| Telegram API | TDLib (submodule) |
| VoIP 1-on-1 | libtgvoip + tgcalls (submodule) |
| Group calls | tgcalls `GroupInstanceCustomImpl` via TDLib |
| NTgCalls (opt.) | `app/src/main/java/…/voip/NTGCallsGroupController.java` |
| Audio effects | `AudioEffectsProcessor.kt` → `AudioTrackJNI.java` |
| CI/CD | `.github/workflows/build-release.yml` |

## Building locally

1. **Clone with submodules**
   ```bash
   git clone --recurse-submodules https://github.com/<your-fork>/Telegram-X
   ```

2. **Create `local.properties`** (never committed):
   ```properties
   sdk.dir=/path/to/android-sdk
   telegram.api_id=YOUR_API_ID
   telegram.api_hash=YOUR_API_HASH
   app.id=org.example.tgx
   app.name=Telegram X
   app.download_url=https://telegram.org/dlx
   # Optional signing (leave blank to produce unsigned debug APKs):
   keystore.file=/path/to/keystore.jks
   keystore.store_password=...
   keystore.key_alias=...
   keystore.key_password=...
   ```

3. **Build**
   ```bash
   ./gradlew assembleRelease
   ```
   APKs end up in `app/build/outputs/apk/*/release/`.

## GitHub Actions CI/CD

The workflow at `.github/workflows/build-release.yml`:

- **Every push to `main`/`master`** → builds all ABI flavours, uploads APKs as
  workflow artifacts (30-day retention).
- **Tag push `v*`** → builds + creates a GitHub Release with APKs attached.
- **Manual dispatch** → optional release toggle.

### Required GitHub secrets

| Secret | Description |
|--------|-------------|
| `TELEGRAM_API_ID` | Numeric Telegram API id |
| `TELEGRAM_API_HASH` | Telegram API hash |
| `APP_ID` | Android application id (e.g. `org.thunderdog.challegram`) |
| `APP_NAME` | Display name |
| `APP_DOWNLOAD_URL` | Public download URL |
| `SIGNING_KEYSTORE_BASE64` | Base64-encoded JKS/PKCS12 keystore |
| `SIGNING_STORE_PASSWORD` | Keystore password |
| `SIGNING_KEY_ALIAS` | Key alias |
| `SIGNING_KEY_PASSWORD` | Key password |

### Optional variables

| Variable | Description |
|----------|-------------|
| `NTGCALLS_ENABLED` | Set to `"true"` to auto-download the NTgCalls AAR |

## Volume & audio features

### Architecture

All call audio output — both 1-on-1 and group/channel calls — flows through
`AudioTrackJNI.java`, which runs each PCM frame through `AudioEffectsProcessor.kt`.
The processor reads the shared `AudioEffectsConfig` singleton (volatile fields), so
any slider change in the Sound Settings dialog is reflected immediately for all active
call types without additional wiring.

### Sound settings dialog

`CallSoundSettingsController.show(context)` — triggered by the equalizer button in
`CallController`. For group calls, use the overload:
```java
CallSoundSettingsController.show(context, tdlib, groupCallId, participantList);
```
This also pushes TDLib participant-volume levels (capped at 200 % by the Telegram API)
alongside the PCM-level boost.

### Sliders

| Slider | Range | Effect |
|--------|-------|--------|
| Volume | 0–100 % | Output attenuation (never exceeds 100 %) |
| **Volume Boost** | **100–500 %** | **Amplification above 100 % via PCM + TDLib** |
| Signal Gain | level 1–30 | Fine-grained signal-processing gain |
| Bass | 0–25 | Low-frequency boost via low-pass filter blend |
| Treble | 0–25 | High-frequency boost via high-pass filter blend |

### NTgCalls integration

`NTGCallsGroupController.java` provides a wrapper around the
[NTgCalls](https://github.com/pytgcalls/ntgcalls) Android SDK for group-call
streaming. Enable by placing the AAR in `app/libs/ntgcalls.aar` and setting
`NTGCALLS_ENABLED = true` in the class. The CI workflow downloads the latest AAR
automatically when the `NTGCALLS_ENABLED` repository variable is `"true"`.

## User preferences

- Keep existing package structure (`org.thunderdog.challegram.*`)
- Do not restructure or migrate the build system
- New audio features go in `app/src/main/java/…/voip/`
- New UI components go in `app/src/main/java/…/widget/voip/`

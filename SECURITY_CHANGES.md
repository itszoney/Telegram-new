# Security & Compliance Changes — Audit Log

> **Scope**: Telegram X fork (org.thunderdog.challegram), upgraded with
> license-key authentication and anti-tampering protection per the Zoneygram
> security brief.
>
> Every section lists: **file changed**, **what changed**, **why**.

---

## Step 1 — Audit Findings

| Area | Status before | Action taken |
|------|--------------|--------------|
| Auth flow | TDLib phone-number auth (no license gate) | License gate added post-auth |
| Firebase | Messaging only | Added Firestore + Functions |
| ProGuard/R8 | Minification enabled; no R8 full mode | R8 full mode enabled |
| Play Integrity | Dependency present, not wired | Wired in IntegrityChecker |
| JNI | Extensive (tgcalls, TDLib bridge) | Added `security.cpp` |
| Root/frida detection | Not present | Added via IntegrityChecker |
| Signing cert check | Not present | Added in native + Kotlin |
| License key system | Not present | Full Firestore + Cloud Function |
| targetSdkVersion | 36 | Already compliant ✓ |
| WRITE_EXTERNAL_STORAGE | Present (legacy) | Flagged — see compliance notes |

---

## Step 2 — License-Key System

### New files

| File | Purpose |
|------|---------|
| `app/src/main/java/…/security/LicenseKeyManager.kt` | Firestore license check; local AES-256-GCM cache; offline grace period |
| `functions/src/index.ts` | `validateLicenseKey` + `revokeKey` Cloud Functions |
| `functions/package.json` | Node 20 + firebase-admin + firebase-functions |
| `functions/tsconfig.json` | TypeScript compiler config |
| `firestore.rules` | `licenseKeys` collection — **no client reads or writes**; all access via Cloud Function admin SDK |
| `firestore.indexes.json` | Empty (no composite indexes needed yet) |
| `firebase.json` | Firebase project config for `firebase deploy` |

### Modified files

| File | Change |
|------|--------|
| `app/build.gradle.kts` | Added `firebase-firestore-ktx:25.1.1`, `firebase-functions-ktx:21.1.0`, `security-crypto:1.1.0-alpha06` |

### Firestore schema (`licenseKeys/{key}`)

```
key          : String    – doc ID = the activation code
durationDays : Number    – validity window in days
createdAt    : Timestamp – admin-set
activatedAt  : Timestamp – set on first activation (null until used)
expiresAt    : Timestamp – activatedAt + durationDays (null until used)
isUsed       : Boolean   – true once a device has activated
deviceId     : String    – ANDROID_ID of the first activating device
isRevoked    : Boolean   – admin revocation flag
```

### Deployment (one-time, after Firebase CLI setup)

```bash
cd functions && npm install && npm run build
firebase deploy --only functions,firestore:rules
```

---

## Step 3 — Anti-Tampering

### New files

| File | Purpose |
|------|---------|
| `app/src/main/java/…/security/NativeSecurityBridge.kt` | Reads APK signing cert DER bytes → passes to C++ |
| `app/jni/security.cpp` | SHA-256 of cert computed in C++; compared against hardcoded expected hash (constant-time) |

### Modified files

| File | Change |
|------|--------|
| `app/jni/CMakeLists.txt` | Added `security.cpp` to `tgxjni` shared library sources |
| `gradle.properties` | Enabled `android.enableR8.fullMode=true` |
| `app/proguard-rules.pro` | Added keep rules for security package, Firestore/Functions, Tink, Play Integrity; added `*Annotation*,Signature` attribute preservation for R8 full mode |

### ⚠️ Required before shipping release APK

1. Sign your APK with your release keystore.
2. Run `apksigner verify --print-certs app-release.apk` and note the SHA-256 fingerprint.
3. Open `app/jni/security.cpp` and replace the 32 zero-bytes in `EXPECTED_CERT_SHA256[]` with the actual fingerprint bytes.
4. Rebuild and re-sign.

Until this is done, `NativeSecurityBridge.verifySigningCertificate()` returns `false` for every build. `BaseApplication` only enforces the check in **non-debug** builds (`!BuildConfig.DEBUG`), so development is not blocked.

---

## Step 4 — Detection Layers

### New files

| File | Purpose |
|------|---------|
| `app/src/main/java/…/security/IntegrityChecker.kt` | Root (binaries, props, writable /system), Frida (port 27042, /proc/self/maps), emulator (build fields, packages), Play Integrity API |
| `app/src/main/java/…/ui/LicenseGateController.kt` | Full-screen Activity shown when license is invalid; non-dismissable until valid key entered |

### Modified files

| File | Change |
|------|--------|
| `app/src/main/java/…/BaseApplication.java` | Added: signing cert check (blocks non-debug tampered builds), `LicenseKeyManager.initialize()`, background `IntegrityChecker.runAll()`, license gate launch |
| `app/src/main/AndroidManifest.xml` | Declared `LicenseGateController` activity (`exported="false"`, `excludeFromRecents="true"`) |

### Integration with existing error patterns

- `LicenseGateController` uses plain Android `Toast` (consistent with app's existing pattern).
- All detection failures either: **log a warning** (root, emulator — soft), or **kill the process** (Frida port open, cert mismatch — hard).
- No new crash points: every check is wrapped in a `try/catch`.
- Play Integrity verdict is fetched asynchronously; failure falls back to local checks only.

---

## Step 5 — Compliance Review

### Permissions audit

| Permission | Used for | Verdict |
|------------|---------|---------|
| `CAMERA` | In-app camera (photo/video) | ✅ Justified |
| `RECORD_AUDIO` | Voice calls, voice messages | ✅ Justified |
| `ACCESS_FINE_LOCATION` | Map features, location sharing | ✅ Justified |
| `ACCESS_BACKGROUND_LOCATION` | Live location sharing | ⚠️ Must disclose in Data Safety; requires explicit user prompt on Android 10+ |
| `WRITE_EXTERNAL_STORAGE` | Legacy file save (Android ≤ 9) | ⚠️ Already scoped to `maxSdkVersion="28"` in many forks — verify this is set; if not, add `android:maxSdkVersion="28"` |
| `READ_EXTERNAL_STORAGE` | Legacy file read | ⚠️ Same — add `maxSdkVersion="32"` |
| `SYSTEM_ALERT_WINDOW` | Call overlay (floating window) | ⚠️ Requires runtime permission; must be justified in Store listing |
| `WRITE_SETTINGS` | ? | ⚠️ Rarely justified for a messaging app — audit which code path requests this |
| `REQUEST_INSTALL_PACKAGES` | APK self-update | ⚠️ Must declare `UPDATE_PACKAGES_WITHOUT_USER_ACTION` if targeting Android 12+; justify in listing |
| `USE_FINGERPRINT` | Biometric app lock | ⚠️ Deprecated (API 28) — migrate to `BiometricPrompt` / `USE_BIOMETRIC` |
| `READ_PHONE_STATE` | Call state detection | ✅ Justified for call handling |
| `MANAGE_OWN_CALLS` | Self-managed calls API | ✅ Justified for VoIP |

### targetSdkVersion

Already **36** — compliant with Google Play's August 2026 requirement. ✅

### Data Safety form — items to add / update

Because the license-key system collects new data:

| Data type | Collected | Purpose | Shared with third parties |
|-----------|----------|---------|--------------------------|
| **Device ID** (`ANDROID_ID`) | Yes | License binding (prevent key sharing) | No (stays in your Firestore) |
| **License key** | Yes | Authentication | No |

Update your Play Console Data Safety form to declare **Device or other IDs → Device ID**, purpose = **App functionality**, not shared with third parties.

### Architecture change flags (decide before proceeding)

1. **`WRITE_SETTINGS`** — no code path in Telegram X normally needs this. Confirm which feature uses it; if unused, remove the permission entirely.
2. **R8 full mode** — may break reflection-heavy libraries. If the build fails after enabling, add `-dontoptimize` for affected libraries or disable per-module.
3. **License gate in `BaseApplication`** — the security scan runs on a background thread started in `onCreate`, so the main thread is never blocked. However, `LicenseGateController.launch()` is called from that background thread via `FLAG_ACTIVITY_NEW_TASK`. Verify on Android 10+ that background activity starts are not suppressed (they are allowed when the app is in the foreground during startup).

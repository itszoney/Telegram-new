---
name: Security layer architecture
description: License-key auth + anti-tampering added to Telegram X (Zoneygram build). Key integration points and required manual steps before release.
---

# Security layer — Telegram X / Zoneygram

## What was added
- `org.thunderdog.challegram.security.LicenseKeyManager` — Firestore + Cloud Function license check; AES-256-GCM encrypted local cache (EncryptedSharedPreferences).
- `org.thunderdog.challegram.security.IntegrityChecker` — root / Frida / emulator detection + Play Integrity API.
- `org.thunderdog.challegram.security.NativeSecurityBridge` — Kotlin JNI bridge to `security.cpp`.
- `app/jni/security.cpp` — self-contained SHA-256 implementation; compares cert DER hash against `EXPECTED_CERT_SHA256[]`.
- `org.thunderdog.challegram.ui.LicenseGateController` — full-screen Activity (non-dismissable) shown when license invalid.
- `functions/src/index.ts` — `validateLicenseKey` + `revokeKey` Cloud Functions.
- `firestore.rules` — denies all direct client access to `licenseKeys` collection.

## Integration point
`BaseApplication.onCreate()` — signs cert check (blocking, debug-gated), then background thread for integrity + license gate.

## Required before release
1. Fill `EXPECTED_CERT_SHA256[32]` in `app/jni/security.cpp` with real release cert SHA-256 bytes.
2. `firebase deploy --only functions,firestore:rules` to deploy backend.
3. Create at least one doc in Firestore `licenseKeys/{key}` with `isUsed:false, durationDays:365, createdAt:now, isRevoked:false`.
4. R8 full mode enabled — if build fails, add `-dontoptimize` for specific broken libraries.

**Why:** Signing cert hash is 32 zero-bytes placeholder; release enforcement only triggers in non-debug builds (`!BuildConfig.DEBUG`).

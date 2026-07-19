# Zoneygram — Full Setup Guide

## What Was Changed
| Item | Old Value | New Value |
|------|-----------|-----------|
| App display name | Telegram X | **Zoneygram** |
| Package / Application ID | `org.thunderdog.challegram` | **`com.itszoney`** |
| `local.properties.sample` | old defaults | `app.id=com.itszoney` / `app.name=Zoneygram` |
| `app/google-services.json` | old package | `com.itszoney` |
| GitHub workflow defaults | old values | updated |

> **Note on source packages:** Java/Kotlin source files still live under the `org.thunderdog.challegram` source package (hundreds of files). The *Application ID* shown on-device and on the Play Store is now `com.itszoney`. A full source-package rename is a separate large refactor.

---

## Part 1 — Telegram Login Key (API ID + API Hash)

Every Telegram client needs its own API credentials. These are **not** your phone login — they are developer keys that allow your app to talk to Telegram's servers.

### How to Generate

1. Open **https://my.telegram.org** in a browser.
2. Enter your phone number → receive a code in Telegram → enter it.
3. Click **"API development tools"**.
4. Fill in the form:

   | Field | Value |
   |-------|-------|
   | App title | `Zoneygram` |
   | Short name | `zoneygram` |
   | Platform | Android |
   | Description | (anything) |

5. Click **"Create application"**.
6. You will see:
   ```
   App api_id:   12345678          ← TELEGRAM_API_ID
   App api_hash: abcdef1234567890  ← TELEGRAM_API_HASH
   ```

> ⚠️ **Never share these.** Anyone with your api_id + api_hash can impersonate your app.

### Where to Put Them

**Local builds** — add to `local.properties`:
```properties
telegram.api_id=12345678
telegram.api_hash=abcdef1234567890
```

**GitHub CI** — add as Repository Secrets (see Part 4).

---

## Part 2 — Device Key for Login

The app uses **two device identifiers** for login and push notifications:

### 2A — Android Device ID (License Binding)

Used by the Firebase license system to bind a key to one device.

```java
// How the app reads it (Android):
import android.provider.Settings;
String deviceId = Settings.Secure.getString(
    context.getContentResolver(),
    Settings.Secure.ANDROID_ID
);
```

- This is a 16-character hex string unique to each device + app combination.
- It is sent to the `validateLicenseKey` Firebase Cloud Function on first activation.
- After activation, **that key is permanently locked to that device**.
- If a user reinstalls or factory-resets, their deviceId changes — they must use a new key or contact you to reset.

### 2B — FCM Device Token (Push Notifications)

Used by Telegram's servers to deliver messages when the app is in the background.

**How it works:**
```
App starts → FirebaseTokenRetriever fetches token from FirebaseMessaging.getInstance().getToken()
           → token sent to Telegram servers via TDLib registerDevice()
           → Telegram sends push notifications through Firebase to that token
```

- The token is stored locally under `registered_device_token` (in `TdlibSettingsManager`).
- It rotates periodically — the app re-registers automatically.
- **You do not manage this manually.** It works once Firebase is configured correctly (Part 3).

---

## Part 3 — Firebase Setup

### 3A — Create a Firebase Project

1. Go to **https://console.firebase.google.com**
2. **Add project** → name it `Zoneygram` → follow the wizard.
3. Click the **Android icon** to add an Android app.

### 3B — Register the Android App in Firebase

| Field | Value |
|-------|-------|
| Android package name | `com.itszoney` |
| App nickname | `Zoneygram` |

Click **Register app** → **Download `google-services.json`** → replace `app/google-services.json` in this repo.

### 3C — Generate Firebase API Keys

Firebase creates several keys automatically. Here is where each lives and what it does:

#### 1. Web API Key (in google-services.json)
```json
"api_key": [{ "current_key": "AIzaSy..." }]
```
- Auto-generated. Already in your downloaded `google-services.json`.
- Used by Firebase SDK for authentication calls.
- **Restrict it** → Firebase Console → Project Settings → API Keys → Edit → Restrict to `com.itszoney`.

#### 2. SafetyNet / Play Integrity API Key
Required for Telegram's server-side device verification.

- Go to **https://console.cloud.google.com** → APIs & Services → Enable **Android Device Verification API**.
- Create credentials → **API Key** → restrict to your package.
- Add to `local.properties`:
  ```properties
  safetynet.api_key=YOUR_SAFETYNET_KEY_HERE
  ```
- Add as GitHub secret: `SAFETYNET_API_KEY`.

#### 3. Firebase App Check (blocks fake clients)
App Check ensures only genuine Zoneygram APKs can call your Cloud Functions.

- Firebase Console → **App Check** → Register your Android app.
- Choose provider: **Play Integrity** (recommended for production).
- After registering, the `validateLicenseKey` function already has `enforceAppCheck: true`.
- For debug builds, register a debug token:
  ```
  Firebase Console → App Check → Apps → Zoneygram → Manage debug tokens → Add token
  ```
  Add the debug token to `local.properties` (never commit this):
  ```properties
  # Debug App Check token — local only, never commit
  firebase.appcheck.debug_token=YOUR_DEBUG_TOKEN
  ```

#### 4. Google Maps API Key
Already in `AndroidManifest.xml`. To restrict it:
- Go to Google Cloud Console → APIs & Services → Credentials → find the Maps key.
- Add restriction: Android apps → package `com.itszoney` + your signing cert SHA-1.

### 3D — Enable Firebase Services

| Service | How to enable |
|---------|--------------|
| Cloud Messaging (FCM) | Enabled by default — confirm in Project Settings → Cloud Messaging |
| Firestore | Build → Firestore Database → Create database → Start in test mode |
| Cloud Functions | Build → Functions → Get started (requires Blaze plan) |
| App Check | Build → App Check → Get started |
| Authentication | Build → Authentication → Get started (for developer admin sign-in) |

### 3E — Deploy Cloud Functions

```bash
# Install Firebase CLI
npm install -g firebase-tools

# Login
firebase login

# Select your project
firebase use --add   # choose Zoneygram project

# Set the developer super-access secret (see Part 5)
firebase functions:secrets:set ADMIN_UID

# Deploy everything
firebase deploy
```

---

## Part 4 — GitHub Actions CI/CD

### 4A — Required Repository Secrets

Go to: **Repo → Settings → Secrets and variables → Actions → New repository secret**

| Secret Name | Value | Required |
|-------------|-------|----------|
| `TELEGRAM_API_ID` | Numeric API ID from my.telegram.org | ✅ |
| `TELEGRAM_API_HASH` | API hash from my.telegram.org | ✅ |
| `APP_ID` | `com.itszoney` | ✅ |
| `APP_NAME` | `Zoneygram` | ✅ |
| `APP_DOWNLOAD_URL` | Your website URL | ✅ |
| `SIGNING_KEYSTORE_BASE64` | Base64-encoded JKS keystore | ✅ for signed builds |
| `SIGNING_STORE_PASSWORD` | Keystore store password | ✅ for signed builds |
| `SIGNING_KEY_ALIAS` | Key alias | ✅ for signed builds |
| `SIGNING_KEY_PASSWORD` | Key password | ✅ for signed builds |
| `SAFETYNET_API_KEY` | SafetyNet / Play Integrity key | optional |

### 4B — Generate a Signing Keystore

```bash
keytool -genkey -v \
  -keystore zoneygram-release.jks \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -alias zoneygram
```

Base64-encode it for GitHub:
```bash
# macOS
base64 -i zoneygram-release.jks | tr -d '\n' | pbcopy

# Linux
base64 -w 0 zoneygram-release.jks | xclip -selection clipboard
```

Paste the result as `SIGNING_KEYSTORE_BASE64`.

### 4C — Workflow Triggers

| Event | Result |
|-------|--------|
| Push to `main` / `master` | Build APKs, upload as artifacts |
| Push tag `v1.0.0` | Build + create GitHub Release |
| Pull request | Build only, no release |
| Manual (Actions tab) | Optional release toggle |

### 4D — Create Your First Release

```bash
git tag v1.0.0
git push origin v1.0.0
```

### 4E — Debug Build Failures

- Actions tab → click the failed run → expand the failing step.
- **Missing secret:** the `Write local.properties` step shows empty values.
- **NDK not found:** check `version.ndk_legacy` in `version.properties`.
- **Signing failure:** test your keystore locally:
  ```bash
  echo "$SIGNING_KEYSTORE_BASE64" | base64 --decode > test.jks
  keytool -list -keystore test.jks
  ```

### 4F — Pull Request Protection

1. Repo → **Settings** → **Branches** → **Add branch protection rule** → Branch name: `main`
2. Enable **"Require status checks to pass before merging"**
3. Search for and add: `Build Release APK`

---

## Part 5 — Developer Super-Access Key (Admin Only)

> ⚠️ **This key must never be shared, logged, committed, or revealed to anyone.**
> It grants full power to create, revoke, and list all license keys.

### How It Works

The `ADMIN_UID` is your **Firebase Authentication UID** — a string like `abc123XYZ...`. It is stored in **Firebase Secret Manager** (not in code, not in `.env`, not in git). Every admin-only Cloud Function checks this UID before executing:

```typescript
// From functions/src/index.ts
function assertAdmin(request, adminUid) {
  if (!request.auth) throw new HttpsError("unauthenticated", "...");
  if (request.auth.uid !== adminUid) throw new HttpsError("permission-denied", "Access denied.");
}
```

Any call from a non-admin UID — including every user's device — gets `permission-denied` with no further information.

### Step 1 — Get Your Firebase Admin UID

1. Firebase Console → **Authentication** → **Users** → **Add user** (or sign in with Google).
2. Copy your **User UID** (looks like `XaBcD1234efGH5678`).

### Step 2 — Store It in Firebase Secret Manager

```bash
firebase functions:secrets:set ADMIN_UID
# Paste your UID when prompted — it will not echo on screen
```

This stores the value encrypted in Google Cloud Secret Manager. It is injected into the function at runtime only — never written to disk or logs.

To verify it was set (without revealing the value):
```bash
firebase functions:secrets:access ADMIN_UID
# Will print the UID — only run this in a private terminal
```

### Step 3 — Deploy

```bash
firebase deploy --only functions
```

### Step 4 — Use Admin Functions Privately

These functions are available **only to your authenticated Firebase admin account**. Call them using the Firebase Admin SDK from a trusted local script — not from the app.

**Create a new license key (30 days):**
```javascript
// run locally with: node admin-tool.js
const { initializeApp } = require("firebase/app");
const { getFunctions, httpsCallable } = require("firebase/functions");
const { getAuth, signInWithEmailAndPassword } = require("firebase/auth");

const app  = initializeApp({ /* your firebaseConfig */ });
const auth = getAuth(app);
const fns  = getFunctions(app);

// Sign in as YOUR admin account
await signInWithEmailAndPassword(auth, "YOUR_EMAIL", "YOUR_PASSWORD");

const createKey = httpsCallable(fns, "createKey");
const result = await createKey({ durationDays: 30 });
console.log("New key:", result.data.key);   // e.g. A1B2-C3D4-E5F6-G7H8
```

**Revoke a key:**
```javascript
const revokeKey = httpsCallable(fns, "revokeKey");
await revokeKey({ key: "A1B2-C3D4-E5F6-G7H8" });
```

**List all keys:**
```javascript
const listKeys = httpsCallable(fns, "listKeys");
const { data } = await listKeys({});
console.table(data.keys);
```

### Security Rules Summary

| Who can call | `validateLicenseKey` | `createKey` | `revokeKey` | `listKeys` |
|--------------|---------------------|-------------|-------------|------------|
| Any genuine device (App Check pass) | ✅ | ❌ | ❌ | ❌ |
| Signed-in user (non-admin) | ✅ | ❌ | ❌ | ❌ |
| **Developer admin (ADMIN_UID)** | ✅ | ✅ | ✅ | ✅ |
| Unauthenticated / fake client | ❌ | ❌ | ❌ | ❌ |

Firestore itself has **no direct client access** — all reads/writes go through Cloud Functions.

### What Happens if ADMIN_UID Leaks

If you suspect the UID was exposed:
1. Change your Firebase account password immediately.
2. Generate a new admin account with a new UID.
3. Update the secret: `firebase functions:secrets:set ADMIN_UID`
4. Redeploy: `firebase deploy --only functions`
5. The old UID is now useless.

---

## Part 6 — SHA-1 Fingerprint for Firebase OAuth

Required if you add Google Sign-In.

```bash
# Debug keystore (for development)
keytool -list -v \
  -keystore ~/.android/debug.keystore \
  -alias androiddebugkey \
  -storepass android -keypass android

# Release keystore
keytool -list -v \
  -keystore zoneygram-release.jks \
  -alias zoneygram
```

Copy the `SHA1:` line → Firebase Console → Project Settings → Your Android app → **Add fingerprint** → re-download `google-services.json`.

---

## Quick-Start Checklist

- [ ] Got `TELEGRAM_API_ID` + `TELEGRAM_API_HASH` from my.telegram.org
- [ ] Created Firebase project, registered `com.itszoney`, downloaded new `google-services.json`
- [ ] Enabled Firestore, Cloud Functions (Blaze plan), App Check, Authentication
- [ ] Deployed Cloud Functions: `firebase deploy --only functions`
- [ ] Set `ADMIN_UID` secret in Firebase Secret Manager
- [ ] Created signing keystore (`zoneygram-release.jks`)
- [ ] Filled `local.properties` with all values
- [ ] Local build works: `./gradlew assembleRelease`
- [ ] Added all GitHub Secrets
- [ ] Pushed a tag to trigger first GitHub Release
- [ ] Enabled branch protection on `main` requiring CI to pass

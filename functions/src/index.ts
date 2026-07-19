/*
 * Firebase Cloud Functions v2 – License-key validation backend
 *
 * Deploy with:  firebase deploy --only functions
 *
 * Firestore schema  (collection: licenseKeys)
 * ─────────────────────────────────────────────────────────────────────────────
 * key          : string    – the activation code the buyer receives
 * durationDays : number    – how many days from activation the license is valid
 * createdAt    : Timestamp – when the admin created the key
 * activatedAt  : Timestamp – first-use timestamp (null if not yet activated)
 * expiresAt    : Timestamp – activatedAt + durationDays (null if not activated)
 * isUsed       : boolean   – true once a device has activated this key
 * deviceId     : string    – ANDROID_ID of the first device to activate
 * isRevoked    : boolean   – admin can set true to invalidate at any time
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * DEVELOPER SUPER-ACCESS
 * ─────────────────────────────────────────────────────────────────────────────
 * The ADMIN_UID environment variable (set via Firebase Secret Manager) is the
 * Firebase Auth UID of the developer account.  Only that UID may call
 * revokeKey, createKey, or listKeys.  Never hard-code this value.
 *
 * Set it once:
 *   firebase functions:secrets:set ADMIN_UID
 *   (enter your Firebase Auth UID when prompted)
 */

import { onCall, HttpsError, CallableRequest } from "firebase-functions/v2/https";
import { defineSecret }                         from "firebase-functions/params";
import { logger }                              from "firebase-functions/v2";
import * as admin from "firebase-admin";
import * as crypto from "crypto";

admin.initializeApp();
const db = admin.firestore();

// ── Developer super-access secret (stored in Firebase Secret Manager) ─────────
// Value is the Firebase Auth UID of the owner/developer account.
// It is NEVER logged, printed, or returned to any client.
const ADMIN_UID_SECRET = defineSecret("ADMIN_UID");

// ── Types ─────────────────────────────────────────────────────────────────────

interface ValidateRequest {
  key:      string;
  deviceId: string;
  appId?:   string;  // reserved for future multi-app support
}

interface ValidateResponse {
  valid:       boolean;
  message:     string;
  expiresAtMs: number;  // epoch-millis; 0 if invalid
}

// ── Auth guard helper ─────────────────────────────────────────────────────────

/**
 * Throws permission-denied if the caller is not the developer admin.
 * Uses the ADMIN_UID secret — never exposed to any client.
 */
function assertAdmin(request: CallableRequest<unknown>, adminUid: string): void {
  if (!request.auth) {
    throw new HttpsError("unauthenticated", "You must be signed in.");
  }
  if (request.auth.uid !== adminUid) {
    // Intentionally vague — do not reveal that an admin UID exists
    throw new HttpsError("permission-denied", "Access denied.");
  }
}

// ── validateLicenseKey ────────────────────────────────────────────────────────

/**
 * Callable function – validates or activates a license key.
 *
 * Behaviour:
 *   • Key not found                      → invalid
 *   • Key is revoked                     → invalid
 *   • Key already used by another device → invalid
 *   • Key already used by THIS device    → re-validate (check expiry)
 *   • Key not yet used                   → activate now, return expiry
 *   • Key expired                        → invalid
 */
export const validateLicenseKey = onCall(
  {
    enforceAppCheck: true,   // ← enable Firebase App Check (blocks non-genuine clients)
    secrets: [ADMIN_UID_SECRET],
  },
  async (request: CallableRequest<ValidateRequest>): Promise<ValidateResponse> => {

    const data     = request.data;
    const key      = (data.key      || "").trim();
    const deviceId = (data.deviceId || "").trim();

    if (!key || !deviceId) {
      return { valid: false, message: "Missing key or device ID.", expiresAtMs: 0 };
    }

    const keyRef = db.collection("licenseKeys").doc(key);
    const snap   = await keyRef.get();

    if (!snap.exists) {
      return { valid: false, message: "License key not found.", expiresAtMs: 0 };
    }

    const record = snap.data()!;

    // ── Revocation check ─────────────────────────────────────────────────────
    if (record.isRevoked === true) {
      return { valid: false, message: "License key has been revoked.", expiresAtMs: 0 };
    }

    const now = admin.firestore.Timestamp.now();

    // ── Already activated ────────────────────────────────────────────────────
    if (record.isUsed === true) {
      // Different device → reject
      if (record.deviceId && record.deviceId !== deviceId) {
        return {
          valid:       false,
          message:     "Key is already activated on another device.",
          expiresAtMs: 0,
        };
      }

      // Same device → check expiry
      const expiresAt: admin.firestore.Timestamp = record.expiresAt;
      if (expiresAt && expiresAt.toMillis() < now.toMillis()) {
        return { valid: false, message: "License has expired.", expiresAtMs: 0 };
      }

      return {
        valid:       true,
        message:     "License valid.",
        expiresAtMs: expiresAt ? expiresAt.toMillis() : 0,
      };
    }

    // ── First activation ─────────────────────────────────────────────────────
    const durationDays: number = record.durationDays ?? 365;
    const expiresAt = admin.firestore.Timestamp.fromMillis(
      now.toMillis() + durationDays * 24 * 60 * 60 * 1000
    );

    await keyRef.update({
      isUsed:      true,
      deviceId:    deviceId,
      activatedAt: now,
      expiresAt:   expiresAt,
    });

    logger.info("License key activated", { deviceId });  // key intentionally omitted from log

    return {
      valid:       true,
      message:     "License activated successfully.",
      expiresAtMs: expiresAt.toMillis(),
    };
  }
);

// ── revokeKey ─────────────────────────────────────────────────────────────────

/**
 * DEVELOPER-ONLY: Revoke a license key by its ID.
 * Protected by ADMIN_UID secret — only the owner Firebase account may call this.
 */
export const revokeKey = onCall(
  {
    enforceAppCheck: false,   // admin tool — called from trusted env, not Android
    secrets: [ADMIN_UID_SECRET],
  },
  async (request: CallableRequest<{ key: string }>) => {
    assertAdmin(request, ADMIN_UID_SECRET.value());

    const key = (request.data.key || "").trim();
    if (!key) throw new HttpsError("invalid-argument", "Missing key.");

    await db.collection("licenseKeys").doc(key).update({ isRevoked: true });
    logger.info("License key revoked by admin");
    return { success: true };
  }
);

// ── createKey ─────────────────────────────────────────────────────────────────

/**
 * DEVELOPER-ONLY: Create a new license key.
 * Protected by ADMIN_UID secret.
 *
 * Request: { durationDays: number }
 * Returns: { key: string }
 */
export const createKey = onCall(
  {
    enforceAppCheck: false,
    secrets: [ADMIN_UID_SECRET],
  },
  async (request: CallableRequest<{ durationDays?: number }>) => {
    assertAdmin(request, ADMIN_UID_SECRET.value());

    const durationDays = request.data.durationDays ?? 365;
    // Generate a cryptographically secure random key: XXXX-XXXX-XXXX-XXXX
    const raw = crypto.randomBytes(8).toString("hex").toUpperCase();
    const key  = `${raw.slice(0,4)}-${raw.slice(4,8)}-${raw.slice(8,12)}-${raw.slice(12,16)}`;

    await db.collection("licenseKeys").doc(key).set({
      durationDays,
      createdAt:   admin.firestore.Timestamp.now(),
      activatedAt: null,
      expiresAt:   null,
      isUsed:      false,
      deviceId:    null,
      isRevoked:   false,
    });

    logger.info("License key created by admin");
    return { key };  // returned ONLY to the authenticated admin caller
  }
);

// ── listKeys ──────────────────────────────────────────────────────────────────

/**
 * DEVELOPER-ONLY: List all license keys with their status.
 * Protected by ADMIN_UID secret.
 */
export const listKeys = onCall(
  {
    enforceAppCheck: false,
    secrets: [ADMIN_UID_SECRET],
  },
  async (request: CallableRequest<Record<string, never>>) => {
    assertAdmin(request, ADMIN_UID_SECRET.value());

    const snap = await db.collection("licenseKeys").get();
    const keys = snap.docs.map(doc => ({
      key:         doc.id,
      isUsed:      doc.data().isUsed,
      isRevoked:   doc.data().isRevoked,
      durationDays: doc.data().durationDays,
      activatedAt: doc.data().activatedAt?.toMillis() ?? null,
      expiresAt:   doc.data().expiresAt?.toMillis()   ?? null,
      // deviceId intentionally omitted — partial privacy for end users
    }));

    return { keys };
  }
);

/*
 * License-key authentication layer for Telegram X / Zoneygram.
 *
 * Firestore schema  (collection: licenseKeys)
 * ─────────────────────────────────────────────
 * key          : String   – unique activation code shown to buyer
 * durationDays : Number   – validity window starting from first activation
 * createdAt    : Timestamp
 * activatedAt  : Timestamp (null until first use)
 * expiresAt    : Timestamp (null until first use)
 * isUsed       : Boolean  – true once activated
 * deviceId     : String   – ANDROID_ID of the activating device
 * isRevoked    : Boolean  – admin-only revocation flag
 */
package org.thunderdog.challegram.security

import android.content.Context
import android.provider.Settings
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.firebase.functions.FirebaseFunctions
import org.thunderdog.challegram.BuildConfig
import java.util.concurrent.TimeUnit

/**
 * Singleton that manages the license-key lifecycle.
 *
 * Call [initialize] from [BaseApplication.onCreate].
 * Call [isLicenseValid] before showing any premium UI.
 * Call [activateKey] when the user enters a new key.
 */
object LicenseKeyManager {

  private const val TAG = "LicenseKeyManager"

  // ── EncryptedSharedPreferences keys ────────────────────────────────────────
  private const val PREFS_NAME   = "zoneygram_license"
  private const val PREF_KEY     = "lk_key"
  private const val PREF_EXPIRY  = "lk_expiry_ms"  // epoch-millis
  private const val PREF_DEVICE  = "lk_device_id"

  // ── Skip grace period: allow offline use for up to N hours ─────────────────
  private val OFFLINE_GRACE_MS = TimeUnit.HOURS.toMillis(24)

  @Volatile private var appContext: Context? = null
  @Volatile private var cachedValid: Boolean = false

  // ── Listener support ────────────────────────────────────────────────────────
  interface LicenseCallback {
    fun onLicenseResult(valid: Boolean, message: String)
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Init
  // ──────────────────────────────────────────────────────────────────────────

  fun initialize(context: Context) {
    appContext = context.applicationContext
    cachedValid = isLocallyValid()
    Log.d(TAG, "License init: locally_valid=$cachedValid")
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Fast local check (no network)
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Returns true if a non-expired license is cached locally.
   * This is the gate used by the UI — O(1), no network.
   */
  fun isLicenseValid(): Boolean = cachedValid

  private fun isLocallyValid(): Boolean {
    val ctx = appContext ?: return false
    return try {
      val prefs = openPrefs(ctx)
      val expiryMs = prefs.getLong(PREF_EXPIRY, 0L)
      val key = prefs.getString(PREF_KEY, null)
      val now = System.currentTimeMillis()
      !key.isNullOrBlank() && expiryMs > 0 && now < expiryMs + OFFLINE_GRACE_MS
    } catch (e: Exception) {
      Log.e(TAG, "Error reading local license", e)
      false
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Server-side activation (via Cloud Function)
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Sends [key] and the device-ID to the `validateLicenseKey` Cloud Function.
   * On success, caches the result locally and invokes [callback] on a
   * background thread.
   */
  fun activateKey(key: String, callback: LicenseCallback) {
    val ctx = appContext ?: run {
      callback.onLicenseResult(false, "Not initialized")
      return
    }
    val deviceId = getDeviceId(ctx)
    val data = hashMapOf(
      "key"      to key.trim(),
      "deviceId" to deviceId,
      "appId"    to BuildConfig.APPLICATION_ID
    )
    FirebaseFunctions.getInstance()
      .getHttpsCallable("validateLicenseKey")
      .call(data)
      .addOnSuccessListener { result ->
        @Suppress("UNCHECKED_CAST")
        val resp = result.data as? Map<String, Any> ?: emptyMap()
        val valid    = resp["valid"]    as? Boolean ?: false
        val message  = resp["message"] as? String  ?: ""
        val expiryMs = (resp["expiresAtMs"] as? Number)?.toLong() ?: 0L
        if (valid && expiryMs > 0) {
          cacheLocally(ctx, key.trim(), expiryMs)
          cachedValid = true
        }
        callback.onLicenseResult(valid, message)
      }
      .addOnFailureListener { e ->
        Log.e(TAG, "Cloud Function error", e)
        // Fall back to local cache during connectivity issues
        val fallback = isLocallyValid()
        cachedValid  = fallback
        callback.onLicenseResult(
          fallback,
          if (fallback) "Offline – using cached license" else "Validation failed: ${e.message}"
        )
      }
  }

  /**
   * Silently refreshes the license in the background (called on app start
   * after [isLocallyValid] passes). Updates [cachedValid] without blocking UI.
   */
  fun refreshInBackground() {
    val ctx = appContext ?: return
    val prefs = try { openPrefs(ctx) } catch (_: Exception) { return }
    val key = prefs.getString(PREF_KEY, null) ?: return
    activateKey(key, object : LicenseCallback {
      override fun onLicenseResult(valid: Boolean, message: String) {
        Log.d(TAG, "Background refresh: valid=$valid msg=$message")
      }
    })
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Helpers
  // ──────────────────────────────────────────────────────────────────────────

  private fun cacheLocally(ctx: Context, key: String, expiryMs: Long) {
    try {
      openPrefs(ctx).edit()
        .putString(PREF_KEY,    key)
        .putLong  (PREF_EXPIRY, expiryMs)
        .putString(PREF_DEVICE, getDeviceId(ctx))
        .apply()
    } catch (e: Exception) {
      Log.e(TAG, "Error caching license", e)
    }
  }

  private fun openPrefs(ctx: Context) = EncryptedSharedPreferences.create(
    ctx,
    PREFS_NAME,
    MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
  )

  fun getDeviceId(ctx: Context): String =
    Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
}

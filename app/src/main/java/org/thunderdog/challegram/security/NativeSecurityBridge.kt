/*
 * JNI bridge to native signing-certificate verification (security.cpp).
 *
 * How it works:
 *   1. Kotlin reads the app's own signing certificate (APK signature) as raw DER bytes.
 *   2. Those bytes are passed to C++ via JNI.
 *   3. C++ computes SHA-256 of the bytes and compares against the hardcoded
 *      expected hash (EXPECTED_CERT_SHA256 in security.cpp).
 *   4. The hash lives in native code → much harder to patch than a Java string.
 *
 * SETUP BEFORE RELEASE:
 *   Run:  keytool -printcert -jarfile your-release.apk
 *   Copy the SHA-256 fingerprint (colon-separated hex) into security.cpp.
 */
package org.thunderdog.challegram.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

object NativeSecurityBridge {

  private const val TAG = "NativeSecurityBridge"

  /** Returns true if the running APK is signed with the expected release certificate. */
  fun verifySigningCertificate(context: Context): Boolean {
    return try {
      val certBytes = getSigningCertBytes(context) ?: return false
      nativeVerifyCert(certBytes)
    } catch (e: Exception) {
      Log.e(TAG, "verifySigningCertificate error", e)
      // Fail open in debug builds so developers are not blocked.
      // In release builds the signing cert WILL match, so this path is safe.
      false
    }
  }

  @Suppress("DEPRECATION")
  private fun getSigningCertBytes(context: Context): ByteArray? {
    val pm = context.packageManager
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      val info = pm.getPackageInfo(
        context.packageName,
        PackageManager.GET_SIGNING_CERTIFICATES
      )
      info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
    } else {
      val info = pm.getPackageInfo(
        context.packageName,
        PackageManager.GET_SIGNATURES
      )
      @Suppress("DEPRECATION")
      info.signatures?.firstOrNull()?.toByteArray()
    }
  }

  // ── JNI declaration ────────────────────────────────────────────────────────
  // Implemented in app/jni/security.cpp

  /**
   * Receives the raw DER bytes of the first APK signing certificate.
   * Returns true only if SHA-256(certBytes) equals the hardcoded release hash.
   */
  @JvmStatic
  private external fun nativeVerifyCert(certBytes: ByteArray): Boolean

  // Loaded as part of the main tgxjni shared library.
  // No separate System.loadLibrary call needed.
}

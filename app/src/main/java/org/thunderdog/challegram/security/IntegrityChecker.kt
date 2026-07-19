/*
 * Anti-tampering detection layer: root, Frida, emulator, Play Integrity.
 *
 * All checks are isolated in this module. Failures are reported via
 * [IntegrityResult] so callers can decide how to react (log, warn, or block)
 * without this class introducing new crash points.
 *
 * Integration pattern (wired in BaseApplication.onCreate):
 *   IntegrityChecker.runAll(context) { result ->
 *     if (result.shouldBlock) { ... }
 *   }
 */
package org.thunderdog.challegram.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

object IntegrityChecker {

  private const val TAG = "IntegrityChecker"

  // ── Result ─────────────────────────────────────────────────────────────────

  enum class Flag {
    ROOT_BINARY,
    ROOT_PROPS,
    ROOT_WRITABLE_SYSTEM,
    FRIDA_PORT,
    FRIDA_MAPS,
    EMULATOR_BUILD,
    EMULATOR_PROPS,
    EMULATOR_HARDWARE,
    SIGNING_CERT_MISMATCH,
  }

  data class IntegrityResult(
    val flags: Set<Flag> = emptySet(),
    val playVerdictJson: String? = null,  // raw Play Integrity verdict if available
  ) {
    val isClean: Boolean get() = flags.isEmpty()

    /** Block the session if any hard-fail flag is set. */
    val shouldBlock: Boolean get() =
      Flag.SIGNING_CERT_MISMATCH in flags || Flag.FRIDA_PORT in flags

    override fun toString() = "IntegrityResult(flags=$flags, clean=$isClean)"
  }

  // ── Public entry-point ─────────────────────────────────────────────────────

  /** Runs all local checks synchronously on the calling thread, then fires the callback.
   *  Dispatch to a background thread before calling to avoid blocking main. */
  fun runAll(context: Context, callback: (IntegrityResult) -> Unit) {
    val flags = mutableSetOf<Flag>()
    flags += checkRoot()
    flags += checkFrida()
    flags += checkEmulator(context)
    if (!NativeSecurityBridge.verifySigningCertificate(context)) {
      flags += Flag.SIGNING_CERT_MISMATCH
    }
    val result = IntegrityResult(flags)
    Log.d(TAG, "Local integrity: $result")
    callback(result)
  }

  /** Asynchronously fetch Play Integrity verdict and merge it with local results. */
  fun runWithPlayIntegrity(
    context: Context,
    nonce: String,
    callback: (IntegrityResult, shouldBlock: Boolean) -> Unit
  ) {
    val localFlags = mutableSetOf<Flag>()
    localFlags += checkRoot()
    localFlags += checkFrida()
    localFlags += checkEmulator(context)
    if (!NativeSecurityBridge.verifySigningCertificate(context)) {
      localFlags += Flag.SIGNING_CERT_MISMATCH
    }

    val manager = IntegrityManagerFactory.create(context)
    val tokenRequest = IntegrityTokenRequest.builder()
      .setNonce(nonce)
      .build()

    manager.requestIntegrityToken(tokenRequest)
      .addOnSuccessListener { tokenResponse ->
        val token = tokenResponse.token()
        Log.d(TAG, "Play Integrity token acquired (send to your backend for verdict)")
        // The token is a JWT — send to your backend to decode and interpret.
        // Here we just pass it to the callback for the caller to forward.
        val result = IntegrityResult(localFlags, token)
        callback(result, result.shouldBlock)
      }
      .addOnFailureListener { e ->
        Log.w(TAG, "Play Integrity unavailable: ${e.message}")
        val result = IntegrityResult(localFlags)
        callback(result, result.shouldBlock)
      }
  }

  // ── Root detection ─────────────────────────────────────────────────────────

  private val ROOT_BINARIES = arrayOf(
    "/system/app/Superuser.apk", "/sbin/su", "/system/bin/su",
    "/system/xbin/su", "/data/local/xbin/su", "/data/local/bin/su",
    "/system/sd/xbin/su", "/system/bin/failsafe/su", "/data/local/su",
    "/su/bin/su"
  )

  private val DANGEROUS_PROPS = mapOf(
    "ro.debuggable"     to "1",
    "ro.secure"         to "0",
    "service.adb.root"  to "1",
  )

  private fun checkRoot(): Set<Flag> {
    val flags = mutableSetOf<Flag>()

    // 1. Known root binaries
    if (ROOT_BINARIES.any { File(it).exists() }) flags += Flag.ROOT_BINARY

    // 2. Build tags contain test-keys
    if (Build.TAGS?.contains("test-keys") == true) flags += Flag.ROOT_PROPS

    // 3. Dangerous system props
    try {
      val process = Runtime.getRuntime().exec(arrayOf("getprop"))
      val output = process.inputStream.bufferedReader().readText()
      val dangerous = DANGEROUS_PROPS.any { (k, v) -> output.contains("[$k]: [$v]") }
      if (dangerous) flags += Flag.ROOT_PROPS
    } catch (_: Exception) {}

    // 4. /system is writable
    try {
      if (File("/system").canWrite()) flags += Flag.ROOT_WRITABLE_SYSTEM
    } catch (_: Exception) {}

    return flags
  }

  // ── Frida detection ────────────────────────────────────────────────────────

  private val FRIDA_DEFAULT_PORT = 27042

  private fun checkFrida(): Set<Flag> {
    val flags = mutableSetOf<Flag>()

    // 1. Frida server default port open
    try {
      Socket().use { s ->
        s.connect(InetSocketAddress("127.0.0.1", FRIDA_DEFAULT_PORT), 50)
        flags += Flag.FRIDA_PORT   // connection succeeded → Frida running
      }
    } catch (_: Exception) {}

    // 2. Frida libraries in /proc/self/maps
    try {
      val maps = File("/proc/self/maps").readText()
      if (maps.contains("frida") || maps.contains("gum-js-loop")) {
        flags += Flag.FRIDA_MAPS
      }
    } catch (_: Exception) {}

    return flags
  }

  // ── Emulator detection ─────────────────────────────────────────────────────

  private val EMULATOR_MANUFACTURERS = setOf(
    "goldfish", "ranchu", "vbox86", "sdk", "emulator"
  )
  private val EMULATOR_FINGERPRINTS = listOf(
    "generic", "unknown", "google/sdk_gphone", "sdk_gphone64",
    "Genymotion", "Andy", "Nox", "BlueStacks"
  )

  private fun checkEmulator(context: Context? = null): Set<Flag> {
    val flags = mutableSetOf<Flag>()

    // 1. Build fields
    val manufacturer = Build.MANUFACTURER.lowercase()
    val model        = Build.MODEL.lowercase()
    val fingerprint  = Build.FINGERPRINT
    val hardware     = Build.HARDWARE.lowercase()

    if (EMULATOR_MANUFACTURERS.any { manufacturer.contains(it) || hardware.contains(it) }) {
      flags += Flag.EMULATOR_BUILD
    }
    if (EMULATOR_FINGERPRINTS.any { fingerprint.contains(it, ignoreCase = true) }) {
      flags += Flag.EMULATOR_BUILD
    }

    // 2. System props
    try {
      val board = Build.BOARD?.lowercase() ?: ""
      if (board.isEmpty() || board == "unknown") flags += Flag.EMULATOR_PROPS
    } catch (_: Exception) {}

    // 3. Known emulator packages present
    if (context != null) {
      val emulatorPackages = listOf(
        "com.bluestacks", "com.bignox.app", "com.vphone.launcher",
        "com.genymotion.superuser", "com.microvirt.market"
      )
      val pm = context.packageManager
      if (emulatorPackages.any { pkg ->
          try { pm.getPackageInfo(pkg, 0); true } catch (_: PackageManager.NameNotFoundException) { false }
        }) flags += Flag.EMULATOR_HARDWARE
    }

    return flags
  }
}

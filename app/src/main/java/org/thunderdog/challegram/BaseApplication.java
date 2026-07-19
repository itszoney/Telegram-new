/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * File created on 05/04/2015 at 08:53
 */
package org.thunderdog.challegram;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.multidex.MultiDexApplication;
import androidx.work.Configuration;

import org.thunderdog.challegram.security.IntegrityChecker;
import org.thunderdog.challegram.security.LicenseKeyManager;
import org.thunderdog.challegram.security.NativeSecurityBridge;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.ui.LicenseGateController;

public final class BaseApplication extends MultiDexApplication implements Configuration.Provider {

  private static final String TAG = "BaseApplication";

  @Override
  public void onCreate () {
    super.onCreate();

    // ── Step 1: Fast native signing-certificate check (synchronous, <1 ms) ──
    // Runs before any app logic. If the APK has been re-signed by an attacker
    // this returns false and we block immediately.
    // NOTE: In debug builds the cert hash in security.cpp is all-zeros, so
    //       this will return false — set BuildConfig.DEBUG guard if you want
    //       to allow debug builds through during development.
    if (!BuildConfig.DEBUG && !NativeSecurityBridge.INSTANCE.verifySigningCertificate(getApplicationContext())) {
      Log.e(TAG, "Signing certificate mismatch – possible tampered APK. Halting.");
      android.os.Process.killProcess(android.os.Process.myPid());
      return;
    }

    // ── Step 2: Initialize LicenseKeyManager (fast local check only) ─────────
    // Reads EncryptedSharedPreferences; no network call here.
    LicenseKeyManager.INSTANCE.initialize(getApplicationContext());

    // ── Step 3: Initialize app (TDLib, UI layer, etc.) ───────────────────────
    UI.initApp(getApplicationContext());

    // ── Step 4: Background integrity scan ────────────────────────────────────
    // Root / Frida / emulator checks run off the main thread so startup is not
    // blocked. Results are logged; enforcement policy can be set per-check.
    new Thread(() -> {
      IntegrityChecker.INSTANCE.runAll(getApplicationContext(), result -> {
        if (!result.isClean()) {
          Log.w(TAG, "Integrity flags: " + result.getFlags());
        }
        if (result.shouldBlock()) {
          // Frida port open or cert mismatch → kill
          Log.e(TAG, "Hard integrity failure – terminating.");
          android.os.Process.killProcess(android.os.Process.myPid());
        }
      });

      // ── Step 5: License gate ───────────────────────────────────────────────
      // If the local cache says no valid license, launch the gate activity over
      // whatever the current screen is. The gate cannot be dismissed without a
      // valid key. If the cache is valid, silently refresh in the background.
      if (!LicenseKeyManager.INSTANCE.isLicenseValid()) {
        LicenseGateController.Companion.launch(getApplicationContext());
      } else {
        LicenseKeyManager.INSTANCE.refreshInBackground();
      }
    }, "security-init").start();
  }

  @NonNull
  @Override
  public Configuration getWorkManagerConfiguration () {
    return new Configuration.Builder().build();
  }
}

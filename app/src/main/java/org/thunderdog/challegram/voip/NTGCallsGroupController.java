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
 */
package org.thunderdog.challegram.voip;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.telegram.Tdlib;

/**
 * Integration layer for the NTgCalls library (https://github.com/pytgcalls/ntgcalls).
 *
 * NTgCalls is a lightweight C++ library for Telegram group-call streaming.
 * It exposes a Java/Android API that this class wraps in order to:
 *   – join a group call or channel call as a streaming participant
 *   – apply the app-level volume boost via {@link AudioEffectsConfig}
 *   – expose call state via simple callbacks
 *
 * ──────────────────────────────────────────────────────────────────────────
 * BUILD SETUP
 * ──────────────────────────────────────────────────────────────────────────
 * NTgCalls ships pre-built AARs for each release:
 *   https://github.com/pytgcalls/ntgcalls/releases
 *
 * To activate this integration:
 *   1. Download the latest `ntgcalls-android-*.aar` from the releases page.
 *   2. Place it in `app/libs/ntgcalls.aar`.
 *   3. Add to `app/build.gradle.kts` inside `dependencies {}`:
 *        implementation(files("libs/ntgcalls.aar"))
 *   4. Set NTGCALLS_ENABLED = true below, or define -DNTGCALLS_ENABLED via
 *      a buildConfigField so the CI workflow can toggle it.
 *
 * The GitHub Actions workflow (`.github/workflows/build-release.yml`)
 * automatically downloads the latest AAR if the secret `NTGCALLS_ENABLED`
 * is set to `"true"`.
 * ──────────────────────────────────────────────────────────────────────────
 */
public class NTGCallsGroupController {

  private static final String TAG = "NTGCallsGroupController";

  /** Flip to true once the AAR is placed in app/libs/. */
  private static final boolean NTGCALLS_ENABLED = false;

  public interface StateListener {
    void onConnected ();
    void onDisconnected ();
    void onError (@NonNull String message);
  }

  private final Tdlib tdlib;
  private final long chatId;
  private final @Nullable StateListener stateListener;

  // Holds the native NTgCalls instance pointer when NTGCALLS_ENABLED is true.
  private long nativePtr;

  public NTGCallsGroupController (
      @NonNull Tdlib tdlib,
      long chatId,
      @Nullable StateListener stateListener
  ) {
    this.tdlib    = tdlib;
    this.chatId   = chatId;
    this.stateListener = stateListener;
  }

  // ── Lifecycle ──────────────────────────────────────────────────────────────

  /**
   * Initialise the NTgCalls instance and connect to the group call.
   *
   * @param joinParams  The JSON join payload from TDLib's
   *                    {@code joinGroupCall} response.
   */
  public void connect (@NonNull String joinParams) {
    if (!NTGCALLS_ENABLED) {
      Log.w(TAG, "NTgCalls is not enabled. See NTGCallsGroupController for setup instructions.");
      return;
    }
    try {
      nativePtr = nativeCreate();
      nativeConnect(nativePtr, chatId, joinParams);
      applyCurrentVolumeBoost();
      if (stateListener != null) stateListener.onConnected();
    } catch (Throwable t) {
      Log.e(TAG, "NTgCalls connect failed", t);
      if (stateListener != null) stateListener.onError(t.getMessage() != null ? t.getMessage() : "unknown");
    }
  }

  /** Disconnect and release native resources. */
  public void disconnect () {
    if (!NTGCALLS_ENABLED || nativePtr == 0) return;
    try {
      nativeStop(nativePtr, chatId);
      nativeDestroy(nativePtr);
    } catch (Throwable t) {
      Log.e(TAG, "NTgCalls disconnect failed", t);
    } finally {
      nativePtr = 0;
      if (stateListener != null) stateListener.onDisconnected();
    }
  }

  // ── Volume Boost integration ───────────────────────────────────────────────

  /**
   * Re-read the current {@link AudioEffectsConfig} boost setting and push it
   * to the NTgCalls audio layer.  Call this whenever the user moves the
   * Volume Boost slider.
   *
   * The PCM-level boost applied by {@link AudioEffectsProcessor} inside
   * {@link AudioTrackJNI} handles the full 100 %–500 % range automatically
   * (both for 1-on-1 calls and group calls).  This method additionally sets
   * the NTgCalls stream input volume so that the boost is reflected at the
   * source level as well, for the cleanest possible output.
   */
  public void applyCurrentVolumeBoost () {
    if (!NTGCALLS_ENABLED || nativePtr == 0) return;
    AudioEffectsConfig config = VoIPAudioEffects.INSTANCE.getConfig();
    // NTgCalls uses a float multiplier: 1.0 = 100 %, 5.0 = 500 %.
    float boost = config.getVolumeBoost();
    try {
      nativeSetVolume(nativePtr, chatId, boost);
    } catch (Throwable t) {
      Log.e(TAG, "NTgCalls setVolume failed", t);
    }
  }

  // ── Native stubs ───────────────────────────────────────────────────────────
  // These are resolved by the NTgCalls AAR at runtime when NTGCALLS_ENABLED = true.
  // They are declared here as no-op stubs so the class compiles even when the
  // AAR is absent.

  private native long nativeCreate ();
  private native void nativeConnect  (long ptr, long chatId, String joinParams);
  private native void nativeStop     (long ptr, long chatId);
  private native void nativeDestroy  (long ptr);
  private native void nativeSetVolume(long ptr, long chatId, float volume);
}

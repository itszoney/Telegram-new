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

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.telegram.Tdlib;

import java.util.List;

/**
 * Applies the user's Volume Boost setting to group-call / channel-call audio.
 *
 * Two complementary layers are used so that the boost reaches users across
 * the full 100 %–500 % range:
 *
 *  1. PCM layer (always active, no extra wiring needed) – {@link AudioEffectsProcessor}
 *     runs inside {@link AudioTrackJNI} on every audio frame from WebRTC / tgcalls.
 *     All audio output — both 1-on-1 and group calls — goes through this path because
 *     tgcalls uses Android's AudioTrack via the same JNI bridge. Since the processor
 *     reads the shared {@link AudioEffectsConfig} singleton through {@code @Volatile}
 *     fields, the boost is reflected immediately for every call type.
 *
 *  2. TDLib participant-volume layer (secondary, max 200 %) – TDLib exposes
 *     {@link TdApi.SetGroupCallParticipantVolumeLevel} with a range of 1–20 000
 *     (10 000 = 100 %, 20 000 = 200 %). Call {@link #applyBoost} from whatever
 *     component manages the active group call to send a volume-level update to
 *     every non-muted participant, giving the network stream itself a louder
 *     baseline before the PCM layer adds any remaining gain.
 *
 * Typical call sites:
 * <pre>
 *   // When the user changes the boost slider:
 *   GroupCallVolumeManager.applyBoost(tdlib, groupCallId, participantSnapshot);
 *
 *   // When a new participant joins (so they also get the boosted level):
 *   GroupCallVolumeManager.applyBoostToParticipant(tdlib, groupCallId, participant);
 * </pre>
 */
public final class GroupCallVolumeManager {

  private GroupCallVolumeManager () { }

  /** TDLib volume level representing 100 % (normal). */
  private static final int TDLIB_VOLUME_NORMAL = 10_000;
  /** Hard cap imposed by the Telegram API (200 %). */
  private static final int TDLIB_VOLUME_MAX    = 20_000;

  // ── Public API ─────────────────────────────────────────────────────────────

  /**
   * Reads the current {@link AudioEffectsConfig#getVolumeBoostPercent()} and
   * pushes a scaled TDLib volume level to every non-muted participant.
   *
   * @param tdlib       active TDLib instance
   * @param groupCallId target group call id
   * @param participants current participant snapshot (may be empty)
   */
  public static void applyBoost (
      @NonNull Tdlib tdlib,
      int groupCallId,
      @NonNull List<TdApi.GroupCallParticipant> participants
  ) {
    int tdlibLevel = computeTdlibLevel();
    for (TdApi.GroupCallParticipant p : participants) {
      applyLevelToParticipant(tdlib, groupCallId, p, tdlibLevel);
    }
  }

  /**
   * Applies the current boost to a single newly-joined participant so they
   * immediately receive the correct volume level.
   */
  public static void applyBoostToParticipant (
      @NonNull Tdlib tdlib,
      int groupCallId,
      @NonNull TdApi.GroupCallParticipant participant
  ) {
    applyLevelToParticipant(tdlib, groupCallId, participant, computeTdlibLevel());
  }

  // ── Internal helpers ───────────────────────────────────────────────────────

  /**
   * Converts the current UI boost percentage (100–500) into a TDLib volume
   * level (1–20 000), capped at the API maximum of 20 000 (200 %).
   *
   * Gain beyond 200 % is handled by {@link AudioEffectsProcessor} at the
   * PCM level, so the TDLib cap is not a practical limitation.
   */
  private static int computeTdlibLevel () {
    int boostPercent = VoIPAudioEffects.INSTANCE.getConfig().getVolumeBoostPercent();
    return Math.min(
        (int) (TDLIB_VOLUME_NORMAL * (boostPercent / 100.0)),
        TDLIB_VOLUME_MAX
    );
  }

  private static void applyLevelToParticipant (
      @NonNull Tdlib tdlib,
      int groupCallId,
      @NonNull TdApi.GroupCallParticipant p,
      int level
  ) {
    if (!p.isMutedForAllUsers && !p.isMutedForCurrentUser) {
      // TDLib function name confirmed in TDLib >= 1.7.0.
      // participantId is TdApi.MessageSender (either MessageSenderUser or MessageSenderChat).
      // If this class is absent in the bundled TDLib version, replace with the
      // equivalent function available in that version (check TdApi.java after build generation).
      tdlib.client().send(
          new TdApi.SetGroupCallParticipantVolumeLevel(groupCallId, p.participantId, level),
          tdlib.silentHandler()
      );
    }
  }
}

package org.thunderdog.challegram.ui;

import android.app.Dialog;
import android.content.Context;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.voip.AudioEffectsConfig;
import org.thunderdog.challegram.voip.GroupCallVolumeManager;
import org.thunderdog.challegram.voip.VoIPAudioEffects;
import org.thunderdog.challegram.widget.voip.CallSoundSliderView;

import java.util.List;

public class CallSoundSettingsController {

  /**
   * Volume Boost steps (percent values shown to the user).
   * Range: 100 % (no boost) → 500 % (5× amplification).
   */
  private static final int VOLUME_BOOST_MIN_PERCENT  = 100;
  private static final int VOLUME_BOOST_MAX_PERCENT  = 500;
  private static final int VOLUME_BOOST_STEP_PERCENT = 25;

  /**
   * Show the sound-settings dialog without a group-call context (1-on-1 calls).
   * The PCM-level {@link org.thunderdog.challegram.voip.AudioEffectsProcessor} applies
   * the boost automatically for all call types via the shared config singleton.
   */
  public static void show (@NonNull Context context) {
    show(context, null, 0, null);
  }

  /**
   * Show the sound-settings dialog with an optional group-call context.
   * When provided, moving the Volume Boost slider also pushes TDLib participant
   * volume levels (up to the 200 % API cap) via {@link GroupCallVolumeManager}.
   *
   * @param tdlib       TDLib instance – may be {@code null} for 1-on-1 calls
   * @param groupCallId group call id (ignored when {@code tdlib} is null)
   * @param participants current participant snapshot for the TDLib volume layer
   */
  public static void show (@NonNull Context context,
                           @Nullable Tdlib tdlib,
                           int groupCallId,
                           @Nullable List<TdApi.GroupCallParticipant> participants) {
    AudioEffectsConfig config = VoIPAudioEffects.INSTANCE.getConfig();

    LinearLayout root = new LinearLayout(context);
    root.setOrientation(LinearLayout.VERTICAL);
    int padding = (int) (16 * context.getResources().getDisplayMetrics().density);
    root.setPadding(padding, padding, padding, padding);

    // ── Volume (0–100 %, output attenuation) ─────────────────────────────────
    CallSoundSliderView volumeSlider = new CallSoundSliderView(context);
    volumeSlider.setLabel("Volume (%)");
    volumeSlider.setRange(0, 100);
    volumeSlider.setValue((int) (config.getVolume() * 100), 0);
    volumeSlider.setOnValueChangeListener(value -> config.setVolume(value / 100f));
    root.addView(volumeSlider);

    // ── Volume Boost (100–500 %, amplification above 100 %) ──────────────────
    int boostSteps = (VOLUME_BOOST_MAX_PERCENT - VOLUME_BOOST_MIN_PERCENT) / VOLUME_BOOST_STEP_PERCENT;
    CallSoundSliderView boostSlider = new CallSoundSliderView(context);
    boostSlider.setLabel("Volume Boost (%)");
    boostSlider.setRange(0, boostSteps);
    int currentBoostStep = (config.getVolumeBoostPercent() - VOLUME_BOOST_MIN_PERCENT) / VOLUME_BOOST_STEP_PERCENT;
    boostSlider.setValue(currentBoostStep, 0);
    boostSlider.setValueFormatter(step ->
        String.valueOf(VOLUME_BOOST_MIN_PERCENT + step * VOLUME_BOOST_STEP_PERCENT) + "%");
    boostSlider.setOnValueChangeListener(step -> {
      int boostPercent = VOLUME_BOOST_MIN_PERCENT + step * VOLUME_BOOST_STEP_PERCENT;
      config.setVolumeBoost(boostPercent);
      // PCM layer (AudioEffectsProcessor) picks up the change instantly for all call types.
      // Additionally push TDLib participant-volume levels for group/channel calls.
      if (tdlib != null && participants != null && !participants.isEmpty()) {
        GroupCallVolumeManager.applyBoost(tdlib, groupCallId, participants);
      }
    });
    root.addView(boostSlider);

    // ── Signal-processing Gain (fine-grained amplification level) ────────────
    CallSoundSliderView gainSlider = new CallSoundSliderView(context);
    gainSlider.setLabel("Signal Gain (level)");
    gainSlider.setRange(1, 30);
    gainSlider.setValue(config.getGainLevel(), 1);
    gainSlider.setOnValueChangeListener(value -> config.setGainLevel(value + 1));
    root.addView(gainSlider);

    // ── Bass ─────────────────────────────────────────────────────────────────
    CallSoundSliderView bassSlider = new CallSoundSliderView(context);
    bassSlider.setLabel("Bass");
    bassSlider.setRange(0, 25);
    bassSlider.setValue(config.getBassLevel(), 0);
    bassSlider.setOnValueChangeListener(config::setBassLevel);
    root.addView(bassSlider);

    // ── Treble ────────────────────────────────────────────────────────────────
    CallSoundSliderView trebleSlider = new CallSoundSliderView(context);
    trebleSlider.setLabel("Treble");
    trebleSlider.setRange(0, 25);
    trebleSlider.setValue(config.getTrebleLevel(), 0);
    trebleSlider.setOnValueChangeListener(config::setTrebleLevel);
    root.addView(trebleSlider);

    Dialog dialog = new Dialog(context);
    dialog.setContentView(root);
    dialog.setTitle("Sound settings");
    dialog.show();
  }
}

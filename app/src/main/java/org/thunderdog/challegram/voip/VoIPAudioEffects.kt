package org.thunderdog.challegram.voip

object VoIPAudioEffects {
  @JvmField
  val config: AudioEffectsConfig = AudioEffectsConfig()

  @JvmStatic
  fun reset () {
    config.setGainLevel(1)
    config.setVolume(1.0f)
    config.setVolumeBoost(100)   // 100 % = no boost
    config.setBassLevel(0)
    config.setTrebleLevel(0)
  }
}

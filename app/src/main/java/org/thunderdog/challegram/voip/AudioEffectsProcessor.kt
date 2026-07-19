package org.thunderdog.challegram.voip

import kotlin.math.tanh

object AudioEffectsLevels {
  val GAIN_LEVELS: Map<Int, Float> = buildMap {
    put(1, 1.0f); put(2, 1.5f); put(3, 2.0f); put(4, 3.0f); put(5, 4.0f)
    put(6, 5.0f); put(7, 10.0f); put(8, 15.0f); put(9, 20.0f); put(10, 30.0f)
    put(11, 50.0f); put(12, 100.0f); put(13, 150.0f); put(14, 200.0f); put(15, 300.0f)
    put(16, 400.0f); put(17, 500.0f); put(18, 750.0f); put(19, 1000.0f); put(20, 1500.0f)
    put(21, 2000.0f); put(22, 2500.0f); put(23, 3000.0f); put(24, 4000.0f); put(25, 5000.0f)
    put(26, 6000.0f); put(27, 7000.0f); put(28, 8000.0f); put(29, 9000.0f); put(30, 10000.0f)
  }
  val BASS_LEVELS: Map<Int, Float> = (0..25).associateWith { it * 0.1f }
  val TREBLE_LEVELS: Map<Int, Float> = (0..25).associateWith { it * 0.1f }
}

class AudioEffectsConfig {
  @Volatile var gainLevel: Int = 1
  @Volatile var volume: Float = 1.0f
  /** Volume boost multiplier: 1.0 = 100 %, 5.0 = 500 %. Applied on top of [volume]. */
  @Volatile var volumeBoost: Float = 1.0f
  @Volatile var bassLevel: Int = 0
  @Volatile var trebleLevel: Int = 0

  fun setGainLevel (level: Int) {
    gainLevel = level.coerceIn(1, 30)
  }

  /** [linear] must be in 0.0–1.0 (0 %–100 % of normal output). */
  fun setVolume (linear: Float) {
    volume = linear.coerceIn(0.0f, 1.0f)
  }

  /**
   * Boost output beyond 100 %.
   * [percent] is the slider value 100–500; stored internally as a 1.0–5.0 multiplier.
   */
  fun setVolumeBoost (percent: Int) {
    volumeBoost = (percent / 100f).coerceIn(1.0f, 5.0f)
  }

  fun getVolumeBoostPercent (): Int = (volumeBoost * 100).toInt()

  fun setBassLevel (level: Int) {
    bassLevel = level.coerceIn(0, 25)
  }

  fun setTrebleLevel (level: Int) {
    trebleLevel = level.coerceIn(0, 25)
  }

  fun gainMultiplier (): Float {
    return AudioEffectsLevels.GAIN_LEVELS[gainLevel] ?: 1.0f
  }

  fun bassBlend (): Float {
    return AudioEffectsLevels.BASS_LEVELS[bassLevel] ?: 0.0f
  }

  fun trebleBlend (): Float {
    return AudioEffectsLevels.TREBLE_LEVELS[trebleLevel] ?: 0.0f
  }
}

class AudioEffectsProcessor (private val config: AudioEffectsConfig) {
  private val lpAlpha = 0.85f
  private val hpAlpha = 0.9f

  private var prevBassOutput = 0.0f
  private var prevTrebleInput = 0.0f
  private var prevTrebleOutput = 0.0f

  fun reset () {
    prevBassOutput = 0.0f
    prevTrebleInput = 0.0f
    prevTrebleOutput = 0.0f
  }

  fun process (buffer: ByteArray, offset: Int, lengthBytes: Int) {
    val sampleCount = lengthBytes / 2
    val samples = FloatArray(sampleCount)

    for (i in 0 until sampleCount) {
      val lo = buffer[offset + i * 2].toInt() and 0xFF
      val hi = buffer[offset + i * 2 + 1].toInt()
      samples[i] = ((hi shl 8) or lo).toShort().toFloat()
    }

    val bassBlend = config.bassBlend()
    if (bassBlend > 0f) {
      var prev = prevBassOutput
      for (i in samples.indices) {
        val filtered = (1f - lpAlpha) * samples[i] + lpAlpha * prev
        prev = filtered
        samples[i] += bassBlend * filtered
      }
      prevBassOutput = prev
    }

    val trebleBlend = config.trebleBlend()
    if (trebleBlend > 0f) {
      var prevIn = prevTrebleInput
      var prevOut = prevTrebleOutput
      for (i in samples.indices) {
        val x = samples[i]
        val filtered = hpAlpha * (prevOut + x - prevIn)
        prevIn = x
        prevOut = filtered
        samples[i] += trebleBlend * filtered
      }
      prevTrebleInput = prevIn
      prevTrebleOutput = prevOut
    }

    // effectiveGain = signal-processing gain × output attenuation × boost above 100 %
    val gain = config.gainMultiplier()
    val volume = config.volume          // 0.0–1.0  (attenuation, never exceeds 100 %)
    val boost = config.volumeBoost      // 1.0–5.0  (amplification above 100 %)
    val effectiveGain = gain * volume * boost

    for (i in samples.indices) {
      val normalized = samples[i] / 32768f * effectiveGain
      val clipped = tanh(normalized) * 32767f
      val outSample = clipped.toInt().coerceIn(-32768, 32767).toShort()
      buffer[offset + i * 2] = (outSample.toInt() and 0xFF).toByte()
      buffer[offset + i * 2 + 1] = ((outSample.toInt() shr 8) and 0xFF).toByte()
    }
  }
}

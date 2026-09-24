package com.findmyphone.wakeword.core

import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * @param targetDb   level the loudest recent chunk is scaled to (dBFS RMS)
 * @param maxGainDb  gain ceiling; also caps how far the room's noise floor is lifted
 * @param releaseS   how quickly the level tracker forgets a loud chunk
 */
data class AgcConfig(
    val targetDb: Float = -22f,
    val maxGainDb: Float = 30f,
    val releaseS: Double = 4.0,
    val sampleRate: Int = 16000,
)

/**
 * Digital gain in front of the VAD and spotter. The mic runs as VOICE_RECOGNITION (no
 * platform AGC), so speech from 2-4 m arrives 20-30 dB quieter than the close-talk audio the
 * model was trained on; see README "Distance". Never attenuates (gain >= 1).
 *
 * Tracks the loudest recent chunk (instant attack, [AgcConfig.releaseS] release). Gain drops
 * immediately, within the same chunk, so a loud onset isn't clipped; it rises gradually,
 * ramped per sample, so it doesn't pump inside a word.
 * Port of python/wakeword/agc.py. Not thread-safe.
 */
class Agc(private val config: AgcConfig = AgcConfig()) {
    private val target = dbToAmp(config.targetDb)
    private val maxGain = dbToAmp(config.maxGainDb)
    private var env = dbToAmp(-40f)
    private var gain = 1f

    /** Gain applied to the end of the last chunk, in dB. */
    val gainDb: Float get() = ampToDb(gain)

    fun process(x: FloatArray): FloatArray {
        if (x.isEmpty()) return x
        val r = rms(x) + 1e-9f
        val rel = exp(-x.size.toDouble() / config.sampleRate / config.releaseS).toFloat()
        env = if (r > env) r else rel * env + (1 - rel) * r
        val want = min(maxGain, max(1f, target / env))
        val start = if (want < gain) want else gain
        val end = if (want < gain) want else gain + UP_RATE * (want - gain)
        gain = end
        val step = (end - start) / x.size
        return FloatArray(x.size) { ((start + step * (it + 1)) * x[it]).coerceIn(-1f, 1f) }
    }

    private companion object {
        /** fraction of the remaining gap closed per chunk when gain rises */
        const val UP_RATE = 0.3f
    }
}

fun rms(x: FloatArray, n: Int = x.size): Float {
    var s = 0.0
    for (i in 0 until n) s += x[i].toDouble() * x[i]
    return if (n > 0) sqrt(s / n).toFloat() else 0f
}

fun ampToDb(a: Float): Float = (20 * log10(a.toDouble() + 1e-9)).toFloat()
fun dbToAmp(db: Float): Float = 10.0.pow(db / 20.0).toFloat()

package com.findmyphone.wakeword

import android.content.Context
import com.findmyphone.wakeword.core.Agc
import com.findmyphone.wakeword.core.AgcConfig
import com.findmyphone.wakeword.core.Detection
import com.findmyphone.wakeword.core.EngineConfig
import com.findmyphone.wakeword.core.EngineStats
import com.findmyphone.wakeword.core.GatedSpotter
import com.findmyphone.wakeword.core.KeywordSpec
import com.findmyphone.wakeword.core.LinearResampler
import com.findmyphone.wakeword.core.UnigramTokenizer
import com.findmyphone.wakeword.core.ampToDb
import com.findmyphone.wakeword.core.rms
import com.findmyphone.wakeword.core.toFloatPcm

/**
 * Frames in, detections out. Owns no microphone, so it can sit inside an existing capture
 * loop (e.g. the clap/whistle detector's) — see INTEGRATION.md — or be driven by
 * [WakeWordService]. Call every method from one thread (the audio thread).
 *
 * Construction loads ~4 MB of models (~100-300 ms); do it off the main thread.
 */
class WakeWordEngine private constructor(
    private val decoder: SherpaKeywordDecoder,
    private val gate: SileroSpeechGate,
    private val spotter: GatedSpotter,
    inputSampleRate: Int,
    private val agc: Agc?,
) {
    private val resampler = if (inputSampleRate != 16000) LinearResampler(inputSampleRate) else null
    private var utt: UtteranceBuilder? = null

    /**
     * One span of speech the spotter ran on, reported when it ends. [peakDb] is the loudest
     * 100 ms of mic input *before* gain: use it to see how quiet the phone hears you at a distance.
     */
    data class Utterance(val peakDb: Float, val gainDb: Float, val durationS: Double, val detected: List<String>)

    private class UtteranceBuilder(var peakDb: Float = -120f, var gainDb: Float = 0f, var n: Long = 0, val detected: MutableList<String> = ArrayList())

    /** Called on the audio thread at the end of each utterance. */
    var utteranceListener: ((Utterance) -> Unit)? = null

    /** Gain the AGC is applying now, dB (0 when disabled). */
    val gainDb: Float get() = agc?.gainDb ?: 0f

    val stats: EngineStats get() = spotter.stats

    /** True while the neural spotter is running (someone is talking). */
    val isSpotting: Boolean get() = spotter.isActive

    fun accept(pcm16: ShortArray, count: Int = pcm16.size): List<Detection> = accept(pcm16.toFloatPcm(count))

    fun accept(samples: FloatArray): List<Detection> {
        val x = resampler?.process(samples) ?: samples
        val levelDb = ampToDb(rms(x))
        val wasActive = spotter.isActive
        val dets = spotter.accept(agc?.process(x) ?: x)
        if (spotter.isActive || wasActive) {
            val u = utt ?: UtteranceBuilder().also { utt = it }
            if (levelDb > u.peakDb) { u.peakDb = levelDb; u.gainDb = gainDb }
            u.n += x.size
            dets.mapTo(u.detected) { it.keyword }
        }
        if (wasActive && !spotter.isActive) endUtterance()
        return dets
    }

    /** End of input: decode anything still buffered (a keyword right at the end of the audio). */
    fun flush(): List<Detection> {
        val dets = spotter.flush()
        utt?.detected?.addAll(dets.map { it.keyword })
        endUtterance()
        return dets
    }

    private fun endUtterance() {
        val u = utt ?: return
        utt = null
        utteranceListener?.invoke(Utterance(u.peakDb, u.gainDb, u.n / 16000.0, u.detected))
    }

    fun release() {
        decoder.release()
        gate.release()
    }

    companion object {
        /**
         * @param agc digital gain before the VAD and spotter; null disables it. On by default:
         *   it roughly doubles detection at 4 m in simulation without adding false alarms (README "Distance").
         * @throws com.findmyphone.wakeword.core.KeywordException if a phrase can't be spotted
         *   (digits, symbols, empty). Validate user input with [validate] first.
         */
        fun create(
            context: Context,
            keywords: List<KeywordSpec>,
            inputSampleRate: Int = 16000,
            config: EngineConfig = EngineConfig(),
            maxActivePaths: Int = DEFAULT_MAX_ACTIVE_PATHS,
            vadThreshold: Float = 0.4f,
            agc: AgcConfig? = AgcConfig(),
        ): WakeWordEngine {
            require(keywords.isNotEmpty()) { "need at least one keyword" }
            val assets = context.applicationContext.assets
            val tok = tokenizer(context)
            val decoder = SherpaKeywordDecoder(assets, KeywordSpec.sherpaKeywords(keywords, tok), maxActivePaths)
            val gate = SileroSpeechGate(assets, vadThreshold)
            return WakeWordEngine(decoder, gate, GatedSpotter(gate, decoder, config), inputSampleRate, agc?.let { Agc(it) })
        }

        fun validate(context: Context, keyword: KeywordSpec): KeywordValidation = try {
            KeywordValidation(warning = keyword.shortWarning(tokenizer(context)))
        } catch (e: IllegalArgumentException) {
            KeywordValidation(error = e.message ?: "invalid keyword")
        }

        @Volatile private var tok: UnigramTokenizer? = null
        private fun tokenizer(context: Context): UnigramTokenizer =
            tok ?: synchronized(this) {
                tok ?: context.applicationContext.assets.open(ModelAssets.VOCAB).reader().use { UnigramTokenizer(it) }
                    .also { tok = it }
            }

        /** See README "Tuning": 4 = sherpa default. */
        const val DEFAULT_MAX_ACTIVE_PATHS = 4
    }
}

/** [error] = can't be used (block it); [warning] = usable but likely to false-trigger. */
data class KeywordValidation(val error: String? = null, val warning: String? = null) {
    val ok: Boolean get() = error == null
}

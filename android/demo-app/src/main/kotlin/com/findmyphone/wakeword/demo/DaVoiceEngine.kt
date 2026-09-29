package com.findmyphone.wakeword.demo

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.davoice.keywordsdetection.keywordslibrary.KeyWordsDetection
import com.davoice.keywordsdetection.keywordslibrary.LicenseInfo
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/**
 * The DaVoice SDK as a second engine for side-by-side QA: fixed-phrase models trained by
 * DaVoice (.dm files in assets, from ../fetch_davoice.sh), a licence key, and its own mic
 * capture and foreground service. Follows the call order documented in DaVoice's
 * WakeWordDetectionAPI: create + initialize -> startForegroundService -> licence -> startListening.
 * Lives in [DemoApp] so it keeps listening with the screen off.
 */
class DaVoiceEngine(private val context: Context) {
    sealed interface State {
        data object Stopped : State
        data object Starting : State
        data class Listening(val model: String, val threshold: Float) : State
        data class Error(val message: String) : State
    }

    @Volatile var state: State = State.Stopped
        private set
    val stateListeners = CopyOnWriteArraySet<(State) -> Unit>()
    val detectionListeners = CopyOnWriteArraySet<(String) -> Unit>()

    private val worker = Executors.newSingleThreadExecutor() // SDK calls off the main thread, in order
    private val main = Handler(Looper.getMainLooper())
    private var detector: KeyWordsDetection? = null

    val isActive get() = state is State.Listening || state is State.Starting

    /** Model files bundled in the APK, minus the shared base layer. */
    fun models(): List<String> =
        (context.assets.list("") ?: emptyArray()).filter { it.endsWith(".dm") && it != BASE_LAYER }.sorted()

    fun start(model: String, threshold: Float, licence: String) = worker.execute {
        stopDetector()
        publish(State.Starting)
        val d = try {
            KeyWordsDetection(context, model, threshold, BUFFER_CNT).also { d ->
                d.initialize { detected, fired -> if (detected) main.post { onDetected(fired ?: model) } }
            }
        } catch (e: Throwable) { // incl. UnsatisfiedLinkError on ABIs the AAR doesn't ship (armeabi-v7a)
            Log.e(TAG, "model load failed: $model", e)
            publish(State.Error("couldn't load $model: ${e.message ?: e.javaClass.simpleName}"))
            return@execute
        }
        try {
            d.startForegroundService()
            if (!d.setLicenseKey(licence.trim())) {
                val info = runCatching { d.setLicenseKeyPerModelInfo(model, licence.trim()) }.getOrNull()
                if (info?.valid != true) {
                    Log.w(TAG, "licence rejected: ${describe(info)}")
                    runCatching { d.stopForegroundService() }
                    publish(State.Error("licence rejected (${describe(info)}). Paste a key from DaVoice."))
                    return@execute
                }
            }
            Log.i(TAG, "licence ok; listening for $model at threshold $threshold")
            d.startListening(threshold)
            detector = d
            publish(State.Listening(model, threshold))
        } catch (e: Throwable) {
            Log.e(TAG, "start failed", e)
            runCatching { d.stopForegroundService() }
            publish(State.Error("start failed: ${e.message ?: e.javaClass.simpleName}"))
        }
    }

    fun stop() = worker.execute {
        stopDetector()
        publish(State.Stopped)
    }

    private fun stopDetector() {
        val d = detector ?: return
        detector = null
        runCatching { d.stopListening() }
        runCatching { d.stopForegroundService() }
        Log.i(TAG, "stopped")
    }

    private fun onDetected(model: String) {
        Log.i(TAG, "wake word ${label(model)} ($model)")
        for (l in detectionListeners) l(model)
    }

    private fun publish(s: State) {
        state = s
        main.post { for (l in stateListeners) l(s) }
    }

    private fun describe(info: LicenseInfo?): String {
        if (info == null) return "invalid key"
        val exp = if (info.expMillis > 0) ", expires ${DateFormat.getDateInstance().format(Date(info.expMillis))}" else ""
        return (info.reason ?: "invalid") + exp
    }

    companion object {
        const val TAG = "DaVoiceDemo"
        const val BASE_LAYER = "layer1.dm"
        /** frames aggregated per decision; 4 in every DaVoice example */
        const val BUFFER_CNT = 4
        /** DaVoice's examples all use 0.99; lower = easier to trigger. Tune once real tests are in. */
        const val NORMAL = 0.99f
        const val SENSITIVE = 0.95f

        /** "coca_cola_model_28_05052025.dm" -> "coca cola" */
        fun label(model: String) = model.removeSuffix(".dm").replace(Regex("_model.*$"), "").replace('_', ' ')
    }
}

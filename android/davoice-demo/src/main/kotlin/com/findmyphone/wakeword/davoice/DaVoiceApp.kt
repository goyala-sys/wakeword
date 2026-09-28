package com.findmyphone.wakeword.davoice

import android.app.Application
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
 * Owns the DaVoice detector for the whole process, so listening (and the ring) keeps going
 * with the screen off or the activity closed. The SDK records the mic itself and runs its
 * own foreground service; this only follows the documented call order from DaVoice's
 * WakeWordDetectionAPI: create + initialize -> startForegroundService -> licence -> startListening.
 */
class DaVoiceApp : Application() {
    sealed interface State {
        data object Stopped : State
        data object Starting : State
        data class Listening(val model: String, val threshold: Float) : State
        data class Error(val message: String) : State
    }

    data class Event(val model: String, val wallTimeMs: Long)

    lateinit var ringer: Ringer
        private set
    @Volatile var state: State = State.Stopped
        private set
    val stateListeners = CopyOnWriteArraySet<(State) -> Unit>()
    val detectionListeners = CopyOnWriteArraySet<(Event) -> Unit>()
    private val recent = ArrayDeque<Event>()
    val recentDetections: List<Event> get() = synchronized(recent) { recent.toList() }

    private val worker = Executors.newSingleThreadExecutor() // SDK calls off the main thread, in order
    private val main = Handler(Looper.getMainLooper())
    private var detector: KeyWordsDetection? = null

    override fun onCreate() {
        super.onCreate()
        ringer = Ringer(this)
    }

    /** Model files bundled in the APK, minus the shared base layer. */
    fun models(): List<String> =
        (assets.list("") ?: emptyArray()).filter { it.endsWith(".dm") && it != BASE_LAYER }.sorted()

    fun start(model: String, threshold: Float, licence: String) = worker.execute {
        stopDetector()
        publish(State.Starting)
        val d = try {
            KeyWordsDetection(this, model, threshold, BUFFER_CNT).also { d ->
                d.initialize { detected, fired -> if (detected) main.post { onDetected(fired ?: model) } }
            }
        } catch (e: Throwable) {
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
        val e = Event(model, System.currentTimeMillis())
        synchronized(recent) {
            recent.addLast(e)
            while (recent.size > MAX_RECENT) recent.removeFirst()
        }
        if (prefs(this).getBoolean(PREF_RING, false)) ringer.ring()
        for (l in detectionListeners) l(e)
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
        const val MAX_RECENT = 50
        const val PREF_RING = "ring"
        const val PREF_MODEL = "model"
        const val PREF_SENSITIVE = "sensitive"
        const val PREF_LICENCE = "licence"
        fun prefs(c: Context) = c.getSharedPreferences("davoice", Context.MODE_PRIVATE)

        /** "coca_cola_model_28_05052025.dm" -> "coca cola" */
        fun label(model: String) = model.removeSuffix(".dm").replace(Regex("_model.*$"), "").replace('_', ' ')
    }
}

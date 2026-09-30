package com.findmyphone.wakeword.demo

import android.app.Application
import android.content.Context
import com.findmyphone.wakeword.RingAction
import com.findmyphone.wakeword.WakeWord

/**
 * Detection → ring wiring lives here, not in the Activity, so it keeps working with the
 * screen off / app closed — the same place your launcher would put it.
 * Also owns the DaVoice engine and one detection history for both engines, for QA comparison.
 */
class DemoApp : Application() {
    enum class Engine(val label: String) { OPEN_VOCAB("Open vocabulary"), DAVOICE("DaVoice"), LIVEKIT("LiveKit") }

    data class Event(val engine: Engine, val phrase: String, val wallTimeMs: Long)

    lateinit var ringer: RingAction
        private set
    lateinit var davoice: DaVoiceEngine
        private set
    lateinit var livekit: LiveKitEngine
        private set
    private val recent = ArrayDeque<Event>()
    val recentDetections: List<Event> get() = synchronized(recent) { recent.toList() }

    override fun onCreate() {
        super.onCreate()
        ringer = RingAction(this)
        davoice = DaVoiceEngine(this)
        livekit = LiveKitEngine(this)
        WakeWord.addListener { d -> onDetected(Engine.OPEN_VOCAB, d.keyword.replace('_', ' ').lowercase()) }
        davoice.detectionListeners.add { model -> onDetected(Engine.DAVOICE, DaVoiceEngine.label(model)) }
        livekit.detectionListeners.add { model -> onDetected(Engine.LIVEKIT, LiveKitEngine.label(model)) }
    }

    private fun onDetected(engine: Engine, phrase: String) {
        synchronized(recent) {
            recent.addLast(Event(engine, phrase, System.currentTimeMillis()))
            while (recent.size > MAX_RECENT) recent.removeFirst()
        }
        if (prefs(this).getBoolean(PREF_RING, false)) ringer.ring()
    }

    companion object {
        const val PREF_RING = "ring"
        const val PREF_KEYWORD = "keyword"
        const val PREF_SENSITIVE = "sensitive"
        const val PREF_ENGINE = "engine"
        const val PREF_DV_MODEL = "davoice_model"
        const val PREF_DV_LICENCE = "davoice_licence"
        const val PREF_LK_MODEL = "livekit_model"
        private const val MAX_RECENT = 50
        fun prefs(c: Context) = c.getSharedPreferences("demo", Context.MODE_PRIVATE)
    }
}

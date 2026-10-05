package com.findmyphone.wakeword.voxrt

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Process-wide state for the VoxRT test app: what [VoxrtService] is doing, the live meter, and the
 * detection history. Detection -> ring lives here so it works with the screen off.
 */
class VoxrtApp : Application() {
    sealed interface State {
        data object Stopped : State
        data class Listening(val threshold: Float, val source: String) : State
        data class Error(val message: String) : State
    }

    /** ~10x per second while listening. [score] is VoxRT's rolling sigmoid score (0..1). */
    data class Meter(val levelDb: Float, val score: Float, val msPerBlock: Float)
    data class Event(val score: Float, val levelDb: Float, val wallTimeMs: Long)

    lateinit var ringer: Ringer
        private set
    @Volatile var state: State = State.Stopped
        private set
    val stateListeners = CopyOnWriteArraySet<(State) -> Unit>()
    val meterListeners = CopyOnWriteArraySet<(Meter) -> Unit>()
    val detectionListeners = CopyOnWriteArraySet<(Event) -> Unit>()
    private val recent = ArrayDeque<Event>()
    val recentDetections: List<Event> get() = synchronized(recent) { recent.toList() }
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        ringer = Ringer(this)
    }

    // Called from the audio thread; listeners run on the main thread.
    internal fun publishState(s: State) {
        state = s
        main.post { for (l in stateListeners) l(s) }
    }

    internal fun publishMeter(m: Meter) {
        if (meterListeners.isNotEmpty()) main.post { for (l in meterListeners) l(m) }
    }

    internal fun publishDetection(e: Event) {
        synchronized(recent) {
            recent.addLast(e)
            while (recent.size > MAX_RECENT) recent.removeFirst()
        }
        main.post {
            if (prefs(this).getBoolean(PREF_RING, false)) ringer.ring()
            for (l in detectionListeners) l(e)
        }
    }

    companion object {
        const val TAG = "VoxrtDemo"
        const val MODEL = "voxrt_wake_word.vxrt"
        const val PHRASE = "hey assistant"
        /** VoxRT's default operating point (precision 0.993 / recall 0.982 on their test set). */
        const val NORMAL = 0.90f
        /** Their documented "a bit more recall, ~5% false-positive rate" point. */
        const val SENSITIVE = 0.85f
        const val PREF_RING = "ring"
        const val PREF_SENSITIVE = "sensitive"
        const val PREF_MIC_SOURCE = "mic_source"
        private const val MAX_RECENT = 50
        fun prefs(c: Context) = c.getSharedPreferences("voxrt", Context.MODE_PRIVATE)
    }
}

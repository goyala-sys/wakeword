package com.findmyphone.wakeword.demo

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.nio.FloatBuffer
import java.util.concurrent.CopyOnWriteArraySet

/**
 * livekit-wakeword's pipeline on ONNX Runtime (the copy bundled in the DaVoice AAR), a port of its
 * Python `WakeWordModel.predict` / Swift `WakeWordModel`: 2 s of 16 kHz audio -> mel spectrogram
 * -> 76-frame windows at stride 8 -> speech embedding (96-d) -> last 16 embeddings -> classifier
 * head -> score in [0, 1]. The mel and embedding models are frozen and shared by every phrase;
 * only the small classifier (`<phrase>.onnx`) is per phrase.
 */
class LiveKitDetector(context: Context, classifierAsset: String) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
    private val assets = context.applicationContext.assets
    private val mel = session("$DIR/melspectrogram.onnx")
    private val embedding = session("$DIR/embedding_model.onnx")
    private val classifier = session("$DIR/$classifierAsset")

    private fun session(asset: String): OrtSession =
        env.createSession(assets.open(asset).use { it.readBytes() }, opts)

    /** [audio]: [WINDOW_SAMPLES] mono 16 kHz samples in [-1, 1]. Returns the classifier score. */
    fun score(audio: FloatArray): Float {
        // 1. mel: (1, n) -> (1, 1, frames, 32), then openWakeWord's x/10 + 2 normalisation
        val melOut = OnnxTensor.createTensor(env, FloatBuffer.wrap(audio), longArrayOf(1, audio.size.toLong())).use { input ->
            mel.run(mapOf(mel.inputNames.first() to input)).use { r ->
                val fb = (r[0] as OnnxTensor).floatBuffer
                FloatArray(fb.remaining()).also { fb.get(it) }
            }
        }
        for (i in melOut.indices) melOut[i] = melOut[i] / 10f + 2f
        val frames = melOut.size / MEL_BINS
        if (frames < EMB_WINDOW) return 0f

        // 2. embeddings of the last 16 windows (stride 8), as one batch
        val starts = (0..frames - EMB_WINDOW step EMB_STRIDE).toList().takeLast(CLASSIFIER_EMBEDDINGS)
        if (starts.size < CLASSIFIER_EMBEDDINGS) return 0f
        val windows = FloatArray(starts.size * EMB_WINDOW * MEL_BINS)
        starts.forEachIndexed { b, s ->
            System.arraycopy(melOut, s * MEL_BINS, windows, b * EMB_WINDOW * MEL_BINS, EMB_WINDOW * MEL_BINS)
        }
        val emb = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(windows), longArrayOf(starts.size.toLong(), EMB_WINDOW.toLong(), MEL_BINS.toLong(), 1),
        ).use { input ->
            embedding.run(mapOf(embedding.inputNames.first() to input)).use { r ->
                val fb = (r[0] as OnnxTensor).floatBuffer
                FloatArray(fb.remaining()).also { fb.get(it) }
            }
        }
        check(emb.size == CLASSIFIER_EMBEDDINGS * EMB_DIM) { "embedding output ${emb.size}, expected ${CLASSIFIER_EMBEDDINGS * EMB_DIM}" }

        // 3. classifier: (1, 16, 96) -> (1, 1)
        return OnnxTensor.createTensor(
            env, FloatBuffer.wrap(emb), longArrayOf(1, CLASSIFIER_EMBEDDINGS.toLong(), EMB_DIM.toLong()),
        ).use { input ->
            classifier.run(mapOf(classifier.inputNames.first() to input)).use { r ->
                (r[0] as OnnxTensor).floatBuffer.get(0)
            }
        }
    }

    override fun close() {
        runCatching { classifier.close() }
        runCatching { embedding.close() }
        runCatching { mel.close() }
        runCatching { opts.close() }
    }

    companion object {
        const val DIR = "livekit"
        const val WINDOW_SAMPLES = 32000 // 2 s at 16 kHz -> exactly 16 embeddings
        private const val MEL_BINS = 32
        private const val EMB_WINDOW = 76
        private const val EMB_STRIDE = 8
        private const val EMB_DIM = 96
        private const val CLASSIFIER_EMBEDDINGS = 16
    }
}

/**
 * Third QA engine: livekit-wakeword. Same shape as [DaVoiceEngine] (start/stop, state and
 * detection listeners) plus a live score feed, so distance tests can see how close a miss was.
 * Owns its AudioRecord; [LiveKitService] only holds the microphone foreground service so it
 * keeps listening with the screen off.
 */
class LiveKitEngine(private val context: Context) {
    sealed interface State {
        data object Stopped : State
        data object Starting : State
        data class Listening(val model: String, val threshold: Float) : State
        data class Error(val message: String) : State
    }

    /** [score] now, [peak] = highest score since the last detection/start, [inferMs] = average cost of one pass, [levelDb] = mic level of the latest 80 ms. */
    data class Score(val score: Float, val peak: Float, val inferMs: Float, val levelDb: Float)

    @Volatile var state: State = State.Stopped
        private set
    val stateListeners = CopyOnWriteArraySet<(State) -> Unit>()
    val detectionListeners = CopyOnWriteArraySet<(String) -> Unit>()
    val scoreListeners = CopyOnWriteArraySet<(Score) -> Unit>()

    private val main = Handler(Looper.getMainLooper())
    private var worker: Thread? = null
    @Volatile private var running = false

    val isActive get() = state is State.Listening || state is State.Starting

    /** Classifier files bundled in the APK (everything in assets/livekit except the two frozen front-end models). */
    fun models(): List<String> =
        (context.assets.list(LiveKitDetector.DIR) ?: emptyArray())
            .filter { it.endsWith(".onnx") && it != "melspectrogram.onnx" && it != "embedding_model.onnx" }.sorted()

    /** Call from the main thread while the app is visible (a microphone foreground service can't start from the background). */
    fun start(model: String, threshold: Float) {
        stop()
        publish(State.Starting)
        try {
            context.startForegroundService(Intent(context, LiveKitService::class.java).putExtra(LiveKitService.EXTRA_PHRASE, label(model)))
        } catch (e: RuntimeException) {
            publish(State.Error("start blocked: ${e.javaClass.simpleName}"))
            return
        }
        running = true
        worker = Thread({ loop(model, threshold) }, "livekit-audio").also { it.start() }
    }

    fun stop() {
        running = false
        worker?.let { if (it !== Thread.currentThread()) it.join(2000) }
        worker = null
        context.stopService(Intent(context, LiveKitService::class.java))
        if (state !is State.Error) publish(State.Stopped)
    }

    private fun loop(model: String, threshold: Float) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val detector = try {
            LiveKitDetector(context, model)
        } catch (e: Throwable) {
            Log.e(TAG, "model load failed: $model", e)
            fail("couldn't load $model: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            // VOICE_RECOGNITION: no platform AGC / noise suppression, same as the other engine.
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, FRAME * 8),
            )
        } catch (e: SecurityException) { null }
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release(); detector.close()
            fail("microphone unavailable")
            return
        }
        val ring = FloatArray(LiveKitDetector.WINDOW_SAMPLES) // newest audio at the end
        val frame = ShortArray(FRAME)
        var filled = 0
        var peak = 0f
        var lastDetectMs = 0L
        var passes = 0
        var inferTotalMs = 0L
        var lastPublish = 0L
        try {
            record.startRecording()
            Log.i(TAG, "listening for $model at threshold $threshold")
            publish(State.Listening(model, threshold))
            while (running) {
                val n = record.read(frame, 0, FRAME)
                if (n <= 0) {
                    if (n < 0) { Log.w(TAG, "AudioRecord.read error $n"); Thread.sleep(100) }
                    continue
                }
                System.arraycopy(ring, n, ring, 0, ring.size - n) // slide the 2 s window by one frame
                for (i in 0 until n) ring[ring.size - n + i] = frame[i] / 32768f
                filled = minOf(filled + n, ring.size)
                if (filled < ring.size) continue // wait for a full 2 s window, as livekit's listener does

                val t0 = SystemClock.elapsedRealtime()
                val score = detector.score(ring)
                inferTotalMs += SystemClock.elapsedRealtime() - t0
                passes++
                if (score > peak) peak = score
                val now = SystemClock.elapsedRealtime()
                if (now - lastPublish > 200) {
                    lastPublish = now
                    var sum = 0.0
                    for (i in 0 until n) { val v = frame[i] / 32768.0; sum += v * v }
                    val levelDb = (20 * kotlin.math.log10(kotlin.math.sqrt(sum / n) + 1e-9)).toFloat().coerceAtLeast(-90f)
                    val s = Score(score, peak, inferTotalMs.toFloat() / passes, levelDb)
                    Log.d(TAG, "score %.3f peak %.3f level %.0f dB".format(score, peak, levelDb)) // for distance tests
                    main.post { for (l in scoreListeners) l(s) }
                }
                if (score >= threshold && now - lastDetectMs > DEBOUNCE_MS) {
                    lastDetectMs = now
                    Log.i(TAG, "wake word ${label(model)} (score %.2f, %.0f ms/pass)".format(score, inferTotalMs.toFloat() / passes))
                    main.post { for (l in detectionListeners) l(model) }
                    peak = 0f
                    ring.fill(0f); filled = 0 // don't re-fire on the same audio
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "audio loop crashed", e)
            fail("audio loop: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            runCatching { record.stop() }
            record.release()
            detector.close()
            if (passes > 0) Log.i(TAG, "stopped; %.0f ms per inference pass over $passes passes".format(inferTotalMs.toFloat() / passes))
        }
    }

    private fun fail(message: String) {
        running = false
        publish(State.Error(message))
        main.post { context.stopService(Intent(context, LiveKitService::class.java)) }
    }

    private fun publish(s: State) {
        state = s
        main.post { for (l in stateListeners) l(s) }
    }

    companion object {
        const val TAG = "LiveKitDemo"
        const val RATE = 16000
        const val FRAME = 1280 // 80 ms, like livekit's listener: one inference pass per frame
        const val DEBOUNCE_MS = 2000L
        /** livekit's listener defaults to 0.5. */
        const val NORMAL = 0.5f
        const val SENSITIVE = 0.3f

        /** "hey_livekit.onnx" -> "hey livekit" */
        fun label(model: String) = model.removeSuffix(".onnx").replace('_', ' ')
    }
}

/** Holds the microphone foreground service (type=microphone) for [LiveKitEngine]; does no audio itself. */
class LiveKitService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "LiveKit wake word", NotificationManager.IMPORTANCE_LOW))
        }
        val phrase = intent?.getStringExtra(EXTRA_PHRASE) ?: "wake word"
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Listening for \"$phrase\" (LiveKit)")
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(ID, n)
        } catch (e: RuntimeException) {
            Log.w(LiveKitEngine.TAG, "cannot start mic FGS", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "livekit"
        private const val ID = 0x4c4b4954
        const val EXTRA_PHRASE = "phrase"
    }
}

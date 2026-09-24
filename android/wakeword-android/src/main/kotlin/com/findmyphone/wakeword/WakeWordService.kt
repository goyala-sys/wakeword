package com.findmyphone.wakeword

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import com.findmyphone.wakeword.core.Detection
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Foreground service (type=microphone) that owns an AudioRecord and runs [WakeWordEngine]
 * on a dedicated thread. Start it through [WakeWord.start] / [WakeWord.ensureRunning].
 *
 * Android 14+ only allows a microphone FGS to start while the app is visible — for a
 * launcher that is "whenever the home screen is showing", so [WakeWord.ensureRunning] from
 * the home activity's onResume keeps it alive across process death and updates.
 */
class WakeWordService : Service() {

    private var worker: Thread? = null
    @Volatile private var running = false
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopListening()
            stopSelf()
            return START_NOT_STICKY
        }
        val keywords = WakeWord.loadKeywords(this)
        if (keywords.isEmpty() || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            WakeWord.publishState(WakeWord.State.Error("no keywords or RECORD_AUDIO not granted"))
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val n = notification(keywords.joinToString { "\"${it.text}\"" })
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
        } catch (e: RuntimeException) {
            // ForegroundServiceStartNotAllowedException (31+) / SecurityException (34+) when
            // started from the background. The launcher will retry on its next onResume.
            Log.w(TAG, "cannot start mic FGS now", e)
            WakeWord.publishState(WakeWord.State.Error("start blocked: ${e.javaClass.simpleName}"))
            stopSelf()
            return START_NOT_STICKY
        }
        // Restart the loop so new keywords take effect.
        stopListening()
        startListening()
        // Not STICKY: a system restart would be a background start and is refused on 14+.
        return START_NOT_STICKY
    }

    private fun startListening() {
        running = true
        worker = Thread({ loop() }, "wakeword-audio").also { it.start() }
    }

    private fun stopListening() {
        running = false
        worker?.let { if (it !== Thread.currentThread()) it.join(1000) }
        worker = null
    }

    override fun onDestroy() {
        stopListening()
        WakeWord.publishState(WakeWord.State.Stopped)
        super.onDestroy()
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val engine = try {
            WakeWordEngine.create(this, WakeWord.loadKeywords(this))
        } catch (e: Exception) {
            Log.e(TAG, "engine init failed", e)
            WakeWord.publishState(WakeWord.State.Error("engine init failed: ${e.message}"))
            main.post { stopSelf() }
            return
        }
        engine.utteranceListener = { u ->
            Log.i(TAG, "utterance %.1fs: peak %.0f dBFS, gain %+.0f dB, %s".format(
                u.durationS, u.peakDb, u.gainDb, if (u.detected.isEmpty()) "no detection" else "DETECTED ${u.detected.joinToString()}"))
        }
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            // VOICE_RECOGNITION: no platform AGC/noise suppression on most devices (they distort what
            // the model hears). Level is handled by the engine's own gain stage (core/Agc.kt) instead.
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, FRAME * 4),
            )
        } catch (e: SecurityException) {
            null
        }
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            WakeWord.publishState(WakeWord.State.Error("microphone unavailable"))
            record?.release(); engine.release()
            main.post { stopSelf() }
            return
        }
        val buf = ShortArray(FRAME)
        try {
            record.startRecording()
            WakeWord.publishState(WakeWord.State.Listening)
            while (running) {
                val n = record.read(buf, 0, FRAME)
                if (n <= 0) {
                    if (n < 0) { Log.w(TAG, "AudioRecord.read error $n"); Thread.sleep(100) }
                    continue
                }
                for (l in frameListeners) l.onFrame(buf, n)
                for (d in engine.accept(buf, n)) dispatch(d)
                if (WakeWord.meterListeners.isNotEmpty()) {
                    val m = WakeWord.Meter(levelDb(buf, n), engine.isSpotting)
                    main.post { WakeWord.publishMeter(m) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "audio loop crashed", e)
            WakeWord.publishState(WakeWord.State.Error("audio loop: ${e.message}"))
        } finally {
            runCatching { record.stop() }
            record.release()
            Log.i(TAG, "stopped; spotter duty cycle ${"%.1f".format(engine.stats.dutyCycle * 100)}%")
            engine.release()
        }
    }

    private fun dispatch(d: Detection) {
        Log.i(TAG, "wake word ${d.keyword} at ${d.timeS}s")
        main.post {
            WakeWord.dispatchDetection(this, d)
            notifyDetection(d)
        }
    }

    /** Heads-up notification per detection, so detections are visible with the screen locked. */
    private fun notifyDetection(d: Detection) {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_DETECTIONS) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_DETECTIONS, "Wake word detections", NotificationManager.IMPORTANCE_HIGH),
            )
        }
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 1, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val time = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date())
        val n = Notification.Builder(this, CHANNEL_DETECTIONS)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Heard \"${d.keyword.replace('_', ' ').lowercase()}\"")
            .setContentText("at $time")
            .setAutoCancel(true)
            .apply { if (open != null) setContentIntent(open) }
            .build()
        runCatching { nm.notify(DETECTION_NOTIFICATION_ID, n) } // no-op without POST_NOTIFICATIONS
    }

    private fun notification(what: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Wake word", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while listening for your wake word"
                    setShowBadge(false)
                },
            )
        }
        val stop = PendingIntent.getService(
            this, 0, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Listening for $what")
            .setContentText("Say it to find your phone")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    /** Receives every raw mic frame (16 kHz mono PCM16) on the audio thread. Keep it fast. */
    fun interface FrameListener { fun onFrame(pcm: ShortArray, count: Int) }

    companion object {
        private const val TAG = "WakeWordService"
        const val SAMPLE_RATE = 16000
        const val FRAME = 1600 // 100 ms
        private const val CHANNEL = "wakeword"
        private const val CHANNEL_DETECTIONS = "wakeword_detections"
        private const val NOTIFICATION_ID = 0x57414b45
        private const val DETECTION_NOTIFICATION_ID = NOTIFICATION_ID + 1

        private fun levelDb(pcm: ShortArray, n: Int): Float {
            var sum = 0.0
            for (i in 0 until n) { val v = pcm[i] / 32768.0; sum += v * v }
            return (20 * log10(sqrt(sum / n) + 1e-9)).toFloat().coerceAtLeast(-90f)
        }
        internal const val ACTION_STOP = "com.findmyphone.wakeword.STOP"

        /** Share this service's mic with e.g. the clap/whistle detector instead of opening a second AudioRecord. */
        val frameListeners: MutableSet<FrameListener> = CopyOnWriteArraySet()

        internal fun intent(context: Context) = Intent(context, WakeWordService::class.java)
    }
}

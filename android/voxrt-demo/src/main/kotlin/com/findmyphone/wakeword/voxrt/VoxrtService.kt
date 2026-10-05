package com.findmyphone.wakeword.voxrt

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
import android.os.IBinder
import android.os.Process
import android.util.Log
import com.voxrt.sdk.wakeword.VoxrtWakeWordEngine
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Foreground service (type=microphone) that owns the AudioRecord and drives VoxRT's engine, which
 * is a synchronous stateful function with no thread of its own (see VoxRT's README).
 *
 * Logs for distance tests (tag VoxrtDemo):
 *  - every detection with its score and the mic level;
 *  - once per second while there's sound (mic > -60 dBFS): peak score and peak level, so a
 *    near-miss (score 0.6) can be told apart from not hearing anything (score 0.0);
 *  - every 30 s: inference time per 100 ms block.
 */
class VoxrtService : Service() {
    @Volatile private var running = false
    private var worker: Thread? = null
    private val app get() = application as VoxrtApp

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopLoop(); stopSelf(); return START_NOT_STICKY
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            app.publishState(VoxrtApp.State.Error("RECORD_AUDIO not granted")); stopSelf(); return START_NOT_STICKY
        }
        val threshold = intent?.getFloatExtra(EXTRA_THRESHOLD, VoxrtApp.NORMAL) ?: VoxrtApp.NORMAL
        val source = intent?.getStringExtra(EXTRA_SOURCE) ?: SOURCE_VOICE_RECOGNITION
        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: RuntimeException) { // started from the background on Android 14+
            Log.w(VoxrtApp.TAG, "cannot start mic FGS now", e)
            app.publishState(VoxrtApp.State.Error("start blocked: ${e.javaClass.simpleName}"))
            stopSelf(); return START_NOT_STICKY
        }
        stopLoop()
        running = true
        worker = Thread({ loop(threshold, source) }, "voxrt-audio").also { it.start() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopLoop()
        app.publishState(VoxrtApp.State.Stopped)
        super.onDestroy()
    }

    private fun stopLoop() {
        running = false
        worker?.let { if (it !== Thread.currentThread()) it.join(1000) }
        worker = null
    }

    private fun loop(threshold: Float, source: String) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val engine = try {
            VoxrtWakeWordEngine.fromAssetBytes(assets, VoxrtApp.MODEL).apply {
                setThreshold(threshold)
                setCooldownFrames(COOLDOWN_FRAMES)
            }
        } catch (e: Throwable) {
            Log.e(VoxrtApp.TAG, "engine init failed", e)
            app.publishState(VoxrtApp.State.Error("engine init failed: ${e.message ?: e.javaClass.simpleName}"))
            running = false
            return
        }
        val audioSource = if (source == SOURCE_MIC) MediaRecorder.AudioSource.MIC else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord(audioSource, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, BLOCK * 2 * 4))
        } catch (e: SecurityException) { null }
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            app.publishState(VoxrtApp.State.Error("microphone unavailable"))
            record?.release(); engine.close(); running = false
            return
        }
        Log.i(VoxrtApp.TAG, "listening for \"${VoxrtApp.PHRASE}\" threshold=$threshold source=$source runtime=${VoxrtWakeWordEngine.nativeVersion()}")
        app.publishState(VoxrtApp.State.Listening(threshold, source))

        val buf = ShortArray(BLOCK)
        var winScore = 0f; var winLevel = -120f; var winBlocks = 0
        var perfNs = 0L; var perfBlocks = 0
        try {
            record.startRecording()
            while (running) {
                val n = record.read(buf, 0, BLOCK, AudioRecord.READ_BLOCKING)
                if (n <= 0) { if (n < 0) { Log.w(VoxrtApp.TAG, "AudioRecord.read error $n"); Thread.sleep(100) }; continue }
                val block = if (n < BLOCK) buf.copyOf(n) else buf
                val t0 = System.nanoTime()
                val dets = engine.processPcm(block)
                val dt = System.nanoTime() - t0
                val score = engine.currentScore()
                val level = levelDb(block)

                for (d in dets) {
                    Log.i(VoxrtApp.TAG, "wake word ${VoxrtApp.PHRASE} score=%.3f mic %.0f dBFS".format(d.score, level))
                    app.publishDetection(VoxrtApp.Event(d.score, level, System.currentTimeMillis()))
                }
                winScore = max(winScore, score); winLevel = max(winLevel, level); winBlocks++
                if (winBlocks == BLOCKS_PER_SECOND) {
                    if (winLevel > LOG_LEVEL_FLOOR_DB) Log.i(VoxrtApp.TAG, "1s: peak score %.3f, peak mic %.0f dBFS".format(winScore, winLevel))
                    winScore = 0f; winLevel = -120f; winBlocks = 0
                }
                perfNs += dt; perfBlocks++
                if (perfBlocks == PERF_BLOCKS) {
                    val ms = perfNs / 1e6f / perfBlocks
                    Log.i(VoxrtApp.TAG, "perf: %.2f ms per 100 ms block (RTF %.3f)".format(ms, ms / 100f))
                    perfNs = 0L; perfBlocks = 0
                }
                app.publishMeter(VoxrtApp.Meter(level, score, dt / 1e6f))
            }
        } catch (e: Exception) {
            Log.e(VoxrtApp.TAG, "audio loop crashed", e)
            app.publishState(VoxrtApp.State.Error("audio loop: ${e.message}"))
        } finally {
            runCatching { record.stop() }
            record.release()
            engine.close()
            Log.i(VoxrtApp.TAG, "stopped")
        }
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Wake word", NotificationManager.IMPORTANCE_LOW))
        }
        val stop = PendingIntent.getService(this, 0, Intent(this, VoxrtService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Listening for \"${VoxrtApp.PHRASE}\" (VoxRT)")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val BLOCK = 1600 // 100 ms, VoxRT's recommended pace
        private const val BLOCKS_PER_SECOND = 10
        private const val PERF_BLOCKS = 300 // 30 s
        private const val LOG_LEVEL_FLOOR_DB = -60f
        /** 2 s, same as the other engines (VoxRT's default is 1 s). */
        private const val COOLDOWN_FRAMES = 200
        private const val CHANNEL = "voxrt"
        private const val NOTIFICATION_ID = 0x56585254
        private const val ACTION_STOP = "com.findmyphone.wakeword.voxrt.STOP"
        const val EXTRA_THRESHOLD = "threshold"
        const val EXTRA_SOURCE = "source"
        const val SOURCE_VOICE_RECOGNITION = "VOICE_RECOGNITION"
        const val SOURCE_MIC = "MIC"

        fun start(c: Context, threshold: Float, source: String) {
            c.startForegroundService(Intent(c, VoxrtService::class.java).putExtra(EXTRA_THRESHOLD, threshold).putExtra(EXTRA_SOURCE, source))
        }

        fun stop(c: Context) { c.stopService(Intent(c, VoxrtService::class.java)) }

        private fun levelDb(pcm: ShortArray): Float {
            var sum = 0.0
            for (v in pcm) { val f = v / 32768.0; sum += f * f }
            return (20 * log10(sqrt(sum / pcm.size) + 1e-9)).toFloat().coerceAtLeast(-90f)
        }
    }
}

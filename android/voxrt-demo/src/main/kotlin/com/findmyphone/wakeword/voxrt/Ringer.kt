package com.findmyphone.wakeword.voxrt

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator

/** Alarm tone at full alarm volume + vibration. Same as :wakeword-android's RingAction (not a dependency here). */
class Ringer(private val context: Context, private val durationMs: Long = 15_000) {
    private var ringtone: Ringtone? = null
    private var savedVolume = -1
    private val main = Handler(Looper.getMainLooper())

    fun ring() {
        stop()
        val am = context.getSystemService(AudioManager::class.java)
        savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
        am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ringtone = RingtoneManager.getRingtone(context, uri)?.apply {
            audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            play()
        }
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0))
        main.postDelayed({ stop() }, durationMs)
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        ringtone?.stop()
        ringtone = null
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)?.cancel()
        if (savedVolume >= 0) {
            context.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
            savedVolume = -1
        }
    }
}

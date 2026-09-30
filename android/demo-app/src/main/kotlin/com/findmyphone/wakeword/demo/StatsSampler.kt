package com.findmyphone.wakeword.demo

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * Once a second, samples what the running engine costs this app: CPU (from /proc/self/stat,
 * all threads, 100 = one full core), memory, and battery temperature / current. Whole-process
 * numbers: only one engine runs at a time, so they are that engine's cost plus the UI's.
 * Battery current while plugged in is the *charging* current, so unplug (wireless adb) to read drain.
 */
class StatsSampler(private val context: Context, private val onSample: (Sample) -> Unit) {
    data class Sample(
        val cpuPct: Float,
        val pssMb: Float,
        val tempC: Float,
        val currentMa: Float,
        val plugged: Boolean,
        val batteryPct: Int,
    )

    private val main = Handler(Looper.getMainLooper())
    private val ticksPerSec = Os.sysconf(OsConstants._SC_CLK_TCK).toFloat()
    private val battery = context.getSystemService(BatteryManager::class.java)
    private var lastTicks = -1L
    private var lastWall = 0L

    private val tick = object : Runnable {
        override fun run() {
            onSample(sample())
            main.postDelayed(this, 1000)
        }
    }

    fun start() {
        lastTicks = -1L
        main.removeCallbacks(tick)
        main.post(tick)
    }

    fun stop() = main.removeCallbacks(tick)

    private fun cpuTicks(): Long = try {
        val s = File("/proc/self/stat").readText()
        val f = s.substring(s.lastIndexOf(')') + 2).split(' ') // fields after "(comm)": state is f[0]
        f[11].toLong() + f[12].toLong() // utime + stime
    } catch (e: Exception) { -1L }

    private fun sample(): Sample {
        val now = SystemClock.elapsedRealtime()
        val ticks = cpuTicks()
        val cpu = if (lastTicks >= 0 && ticks >= 0 && now > lastWall) {
            (ticks - lastTicks) / ticksPerSec / ((now - lastWall) / 1000f) * 100f
        } else 0f
        lastTicks = ticks
        lastWall = now

        val mem = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val b = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val ua = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        return Sample(
            cpuPct = cpu,
            pssMb = mem.totalPss / 1024f,
            tempC = (b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f,
            currentMa = ua / 1000f,
            plugged = (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0,
            batteryPct = if (level >= 0) level * 100 / scale else -1,
        )
    }
}

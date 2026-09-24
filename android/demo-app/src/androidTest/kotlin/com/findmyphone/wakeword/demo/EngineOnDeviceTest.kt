package com.findmyphone.wakeword.demo

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.findmyphone.wakeword.WakeWord
import com.findmyphone.wakeword.WakeWordEngine
import com.findmyphone.wakeword.core.Detection
import com.findmyphone.wakeword.core.KeywordSpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Runs on a real Android runtime (CI emulator or a phone): real sherpa-onnx JNI, real
 * models from APK assets, the same WAV fixtures the Python tests use.
 *
 *   ./gradlew :demo-app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class EngineOnDeviceTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val app = instr.targetContext          // has the model assets
    private val testAssets = instr.context.assets  // has the WAV fixtures

    private fun wav(name: String): ShortArray {
        val bytes = testAssets.open("$name.wav").use { it.readBytes() }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        // walk RIFF chunks to "data" (don't assume a 44-byte header)
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = bb.getInt(pos + 4)
            if (id == "data") {
                val out = ShortArray(size / 2)
                bb.position(pos + 8)
                bb.asShortBuffer().get(out)
                return out
            }
            pos += 8 + size + (size and 1)
        }
        error("no data chunk in $name.wav")
    }

    private fun silence(s: Double) = ShortArray((s * 16000).toInt())

    private fun run(engine: WakeWordEngine, vararg parts: ShortArray): List<Detection> {
        val all = parts.reduce { a, b -> a + b }
        val out = ArrayList<Detection>()
        var i = 0
        while (i < all.size) {
            val n = minOf(1600, all.size - i)
            out += engine.accept(all.copyOfRange(i, i + n))
            i += n
        }
        return out + engine.flush()
    }

    private fun engine(vararg kw: String) = WakeWordEngine.create(app, kw.map { KeywordSpec(it) })

    @Test fun detectsKeywordOnDevice() {
        val e = engine("hey buddy")
        try {
            for (name in listOf("hey_buddy_a", "hey_buddy_b")) {
                val d = run(e, silence(1.0), wav(name), silence(1.5))
                assertEquals("$name: $d", listOf("HEY_BUDDY"), d.map { it.keyword })
            }
        } finally {
            e.release()
        }
    }

    @Test fun ignoresOtherSpeech() {
        val e = engine("hey buddy")
        try {
            val d = run(e, silence(0.5), wav("neg_my_buddy"), silence(1.0), wav("neg_hey_there"), silence(1.0))
            assertEquals(emptyList<Detection>(), d)
        } finally {
            e.release()
        }
    }

    @Test fun multipleKeywords() {
        val e = engine("hey buddy", "find my phone")
        try {
            val d = run(e, silence(1.0), wav("find_my_phone"), silence(1.5), wav("hey_buddy_a"), silence(1.5))
            assertEquals(listOf("FIND_MY_PHONE", "HEY_BUDDY"), d.map { it.keyword })
        } finally {
            e.release()
        }
    }

    @Test fun agcRecoversQuietSpeech() {
        // -32 dB (~-47 dBFS peak): a normal voice a few metres away. Missed without the AGC.
        val quiet = wav("hey_buddy_a").let { s -> ShortArray(s.size) { (s[it] * 0.025f).roundToInt().toShort() } }
        val e = engine("hey buddy")
        val utterances = ArrayList<WakeWordEngine.Utterance>()
        e.utteranceListener = { utterances += it }
        try {
            assertEquals(listOf("HEY_BUDDY"), run(e, silence(1.0), quiet, silence(1.5)).map { it.keyword })
            assertEquals(1, utterances.size)
            val u = utterances[0]
            assertEquals(listOf("HEY_BUDDY"), u.detected)
            assertTrue("peak ${u.peakDb}", u.peakDb in -52f..-42f)
            assertTrue("gain ${u.gainDb}", u.gainDb > 12f)
        } finally {
            e.release()
        }
    }

    @Test fun resamples48kInput() {
        // 16 kHz fixture upsampled 3x (sample repeat) must still be detected via inputSampleRate=48000
        val e = WakeWordEngine.create(app, listOf(KeywordSpec("hey buddy")), inputSampleRate = 48000)
        try {
            val x = silence(1.0) + wav("hey_buddy_a") + silence(1.5)
            val up = ShortArray(x.size * 3) { x[it / 3] }
            val out = ArrayList<Detection>()
            var i = 0
            while (i < up.size) {
                val n = minOf(4800, up.size - i); out += e.accept(up.copyOfRange(i, i + n)); i += n
            }
            out += e.flush()
            assertEquals(listOf("HEY_BUDDY"), out.map { it.keyword })
        } finally {
            e.release()
        }
    }

    @Test fun serviceStartsListening() {
        val ui = instr.uiAutomation
        ui.grantRuntimePermission(app.packageName, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) ui.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        // Mic FGS may only start while the app is visible (Android 14+), like the launcher case.
        instr.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

        val listening = CountDownLatch(1)
        val errors = ArrayList<String>()
        val l = WakeWord.StateListener { s ->
            if (s == WakeWord.State.Listening) listening.countDown()
            if (s is WakeWord.State.Error) errors += s.message
        }
        instr.runOnMainSync { WakeWord.addStateListener(l) }
        instr.runOnMainSync { WakeWord.start(app, listOf(KeywordSpec("hey buddy"))) }
        assertTrue("service never reached Listening; errors=$errors", listening.await(20, TimeUnit.SECONDS))
        instr.runOnMainSync { WakeWord.removeStateListener(l) }
    }

    @After fun stopService() {
        instr.runOnMainSync { WakeWord.stop(app) }
    }
}

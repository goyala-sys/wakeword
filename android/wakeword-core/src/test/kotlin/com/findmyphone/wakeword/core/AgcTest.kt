package com.findmyphone.wakeword.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun tone(db: Float, n: Int = 1600) = FloatArray(n) { dbToAmp(db + 3.01f) * sin(2 * PI * 300 * it / 16000).toFloat() }

class AgcTest {
    @Test fun `quiet speech is raised to the target`() {
        val agc = Agc()
        var out = FloatArray(0)
        repeat(200) { out = agc.process(tone(-48f)) } // 20 s: the level tracker releases over seconds
        assertEquals(-22f, ampToDb(rms(out)), 0.5f)
        assertEquals(26f, agc.gainDb, 0.5f)
    }

    @Test fun `gain is capped and never attenuates`() {
        val agc = Agc()
        repeat(100) { agc.process(tone(-80f)) }
        assertEquals(30f, agc.gainDb, 0.1f)
        val loud = Agc()
        repeat(10) { loud.process(tone(-6f)) }
        assertEquals(0f, loud.gainDb, 0.01f)
    }

    @Test fun `loud onset after silence is not clipped`() {
        val agc = Agc()
        repeat(50) { agc.process(FloatArray(1600)) } // gain climbs to the cap
        val out = agc.process(tone(-12f))
        assertTrue(out.maxOf { abs(it) } < 0.99f, "clipped")
        assertEquals(-12f, ampToDb(rms(out)), 0.5f)
    }

    @Test fun `gain rises gradually`() {
        val agc = Agc()
        repeat(10) { agc.process(tone(-22f)) }
        assertEquals(0f, agc.gainDb, 0.1f)
        agc.process(tone(-60f))
        assertTrue(agc.gainDb < 10f, "jumped to ${agc.gainDb} dB in one chunk")
    }
}

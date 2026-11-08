package com.voyadio.automotive.speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnounceGateTest {
    @Test fun `the first station after the radio starts is not announced`() {
        val gate = AnnounceGate()
        gate.onTransition("a")
        assertFalse(gate.onPlaying("a"))
    }

    @Test fun `a voice command can tell whether the radio will name the new station itself`() {
        val gate = AnnounceGate()
        assertFalse("nothing has played yet: the first station stays unannounced", gate.announcesNext)
        gate.onPlaying("a")
        assertTrue(gate.announcesNext)
    }

    @Test fun `a new station is announced once, when its sound starts`() {
        val gate = AnnounceGate()
        gate.onPlaying("a")
        gate.onTransition("b")
        assertTrue(gate.onPlaying("b"))
        assertFalse("pausing and resuming the same station stays quiet", gate.onPlaying("b"))
    }

    @Test fun `a dead station skipped on the way is not announced, the one that plays is`() {
        val gate = AnnounceGate()
        gate.onPlaying("a")
        gate.onTransition("dead")
        gate.onTransition("c")
        assertTrue(gate.onPlaying("c"))
    }

    @Test fun `moving back to the station already playing says nothing`() {
        val gate = AnnounceGate()
        gate.onPlaying("a")
        gate.onTransition("a")
        assertFalse(gate.onPlaying("a"))
    }

    @Test fun `no station, no voice`() {
        val gate = AnnounceGate()
        gate.onPlaying("a")
        gate.onTransition(null)
        assertFalse(gate.onPlaying(null))
    }
}

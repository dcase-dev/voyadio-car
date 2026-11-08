package com.voyadio.automotive

import org.junit.Assert.*
import org.junit.Test

class ParkedAccessTest {
    @Test fun `unknown and driving states close the screen`() {
        var allowed = true
        val access = ParkedAccess { allowed = it }
        val session = access.begin()
        assertFalse(allowed)
        val source = access.observe(session)!!
        access.report(session, source, false)
        assertTrue(allowed)
        access.report(session, source, true)
        assertFalse(allowed)
        access.report(session, source, null)
        assertFalse(allowed)
    }

    @Test fun `late parked signal from disconnected source cannot reopen the screen`() {
        var allowed = false
        val access = ParkedAccess { allowed = it }
        val session = access.begin()
        val oldSource = access.observe(session)!!
        access.report(session, oldSource, false)
        assertTrue(allowed)
        access.observe(session) // connection loss immediately invalidates its old listener
        assertFalse(allowed)
        access.report(session, oldSource, false)
        assertFalse(allowed)
    }

    @Test fun `old activity callback cannot change a restarted screen`() {
        var allowed = false
        val access = ParkedAccess { allowed = it }
        val oldSession = access.begin()
        val oldSource = access.observe(oldSession)!!
        access.end()
        val session = access.begin()
        val source = access.observe(session)!!
        access.report(session, source, true)
        assertNull(access.observe(oldSession))
        access.report(oldSession, oldSource, false)
        assertFalse(allowed)
    }

    @Test fun `only new connection can permit the screen after reconnect`() {
        var allowed = false
        val access = ParkedAccess { allowed = it }
        val session = access.begin()
        val oldSource = access.observe(session)!!
        val source = access.observe(session)!!
        access.report(session, source, false)
        assertTrue(allowed)
        access.report(session, oldSource, true)
        assertTrue(allowed)
        access.report(session, source, true)
        assertFalse(allowed)
    }

    @Test fun `stopping the activity closes access and ignores late callbacks`() {
        var allowed = false
        val access = ParkedAccess { allowed = it }
        val session = access.begin()
        val source = access.observe(session)!!
        access.report(session, source, false)
        access.end()
        assertFalse(allowed)
        access.report(session, source, false)
        assertFalse(allowed)
    }
}

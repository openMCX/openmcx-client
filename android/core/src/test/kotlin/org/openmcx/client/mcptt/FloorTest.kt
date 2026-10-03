package org.openmcx.client.mcptt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Floor control packets from the lab capture in docs/android/wire/phase2-mcptt.md §5.3. */
class FloorTest {
    private fun hex(text: String) = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

    private val client = 0xBCB7D9C1L
    private val server = 0xD741F472L

    @Test
    fun idleAndItsAck() {
        val idle = FloorMessage.decode(hex("95 CC 00 04  BC B7 D9 C1  4D 43 50 54  0D 02 80 00  08 02 00 01"))
        assertEquals(FloorType.IDLE, idle.type)
        assertTrue(idle.ackRequired)
        assertEquals(client, idle.ssrc)
        assertEquals(1, idle.sequence)
        assertEquals(FloorIndicator.NORMAL, idle.floorIndicator)
        assertArrayEquals(hex("8A CC 00 04  D7 41 F4 72  4D 43 50 54  0A 02 00 00  0C 02 05 00"),
            FloorMessage.ack(server, FloorType.IDLE).encode())
    }

    @Test
    fun requestsAndReleases() {
        assertArrayEquals(hex("80 CC 00 03  D7 41 F4 72  4D 43 50 54  0D 02 80 00"), FloorMessage.request(server).encode())
        assertArrayEquals(hex("80 CC 00 04  D7 41 F4 72  4D 43 50 54  00 02 00 00  0D 02 80 00"), FloorMessage.request(server, priority = 0).encode())
        assertArrayEquals(hex("84 CC 00 03  D7 41 F4 72  4D 43 50 54  0D 02 80 00"), FloorMessage.release(server).encode())
        assertArrayEquals(hex("94 CC 00 03  D7 41 F4 72  4D 43 50 54  0D 02 80 00"), FloorMessage.release(server, ackRequired = true).encode())
    }

    @Test
    fun granted() {
        val bytes = hex("91 CC 00 07  BC B7 D9 C1  4D 43 50 54  0D 02 80 00  01 02 00 1E  0E 06 93 A1 93 7F 00 00  00 02 00 00")
        val g = FloorMessage.decode(bytes)
        assertEquals(FloorType.GRANTED, g.type)
        assertEquals(30, g.durationSeconds)
        assertEquals(0x93A1937FL, g.mediaSsrc)
        assertEquals(0, g.priority)
        assertArrayEquals(bytes, g.encode())
    }

    @Test
    fun takenWithGrantedParty() {
        val bytes = hex("92 CC 00 0F  01 02 03 04  4D 43 50 54  0D 02 80 00  08 02 00 02 " +
            "04 18 73 69 70 3A 61 6C 69 63 65 2E 6D 63 70 74 74 40 6D 63 78 2E 74 65 73 74 00 00 " +
            "05 02 00 01  13 01 00 00  0E 06 93 A1 93 7F 00 00")
        val t = FloorMessage.decode(bytes)
        assertEquals(FloorType.TAKEN, t.type)
        assertEquals("sip:alice.mcptt@mcx.test", t.grantedParty)
        assertEquals(2, t.sequence)
        assertEquals(1, t.permissionToRequest)
        assertEquals(0x93A1937FL, t.mediaSsrc)
        assertArrayEquals(bytes, t.encode())
    }

    @Test
    fun denyRevokeAndServerAck() {
        assertEquals(1, FloorMessage.decode(hex("93 CC 00 04  BC B7 D9 C1  4D 43 50 54  0D 02 80 00  02 02 00 01")).rejectCause)
        val revoke = FloorMessage.decode(hex("86 CC 00 04  BC B7 D9 C1  4D 43 50 54  0D 02 80 00  02 02 00 02"))
        assertEquals(FloorType.REVOKE, revoke.type); assertEquals(2, revoke.rejectCause)
        val ack = FloorMessage.decode(hex("8A CC 00 04  BC B7 D9 C1  4D 43 50 54  0A 02 00 02  0C 02 04 00"))
        assertEquals(FloorType.RELEASE, ack.acknowledgedType); assertEquals(2, ack.source)
    }

    @Test
    fun rejectsMalformed() {
        assertThrows(FloorCodecError::class.java) { FloorMessage.decode(hex("80 CC 00 04  D7 41 F4 72  4D 43 50 54  0D 02 80 00")) }
        assertThrows(FloorCodecError::class.java) { FloorMessage.decode(hex("80 CC 00 03  D7 41 F4 72  4D 43 50 43  0D 02 80 00")) }
        assertThrows(FloorCodecError::class.java) { FloorMessage.decode(hex("80 CC 00 03  D7 41 F4 72  4D 43 50 54  0D 09 80 00")) }
    }
}

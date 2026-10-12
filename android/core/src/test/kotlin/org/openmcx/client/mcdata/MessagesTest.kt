//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcdata

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

/** The worked examples of docs/android/wire/phase3-mcdata.md §2.6 (TS 24.282 clause 15). */
class MessagesTest {
    private fun hex(text: String): ByteArray =
        text.lines().joinToString(" ") { it.trim().substringAfter("  ", it.trim()) }.split(Regex("\\s+"))
            .filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

    private val time = 1790337600L
    private val conversation = UUID.fromString("9a323217-17bd-4a01-bd07-5d02558695b1")
    private val message = UUID.fromString("6402178c-a86b-498e-92e8-0a0dd96b5911")
    private val reply = UUID.fromString("0f3c2b1a-5d4e-4f60-8a7b-9c0d1e2f3a4b")

    private val exampleA = hex("""
        01 00 6a b6 62 40 9a 32 32 17 17 bd 4a 01 bd 07
        5d 02 55 86 95 b1 64 02 17 8c a8 6b 49 8e 92 e8
        0a 0d d9 6b 59 11 83""")

    @Test
    fun exampleA() {
        val sds = SdsSignalling(time, conversation, message, disposition = DispositionRequest.DELIVERY_AND_READ)
        assertArrayEquals(exampleA, sds.encode())
        assertEquals(sds, SdsSignalling.decode(exampleA))
    }

    @Test
    fun exampleB() {
        val bytes = hex("03 01 78 00 0a 01 48 65 6c 6c 6f 20 42 6f 62")
        val payload = DataPayload(listOf(Payload.text("Hello Bob")))
        assertArrayEquals(bytes, payload.encode())
        assertEquals(payload, DataPayload.decode(bytes))
        assertEquals(9, payload.size)
    }

    @Test
    fun exampleC() {
        val bytes = hex("""
            01 00 6a b6 62 45 9a 32 32 17 17 bd 4a 01 bd 07
            5d 02 55 86 95 b1 0f 3c 2b 1a 5d 4e 4f 60 8a 7b
            9c 0d 1e 2f 3a 4b 21 64 02 17 8c a8 6b 49 8e 92
            e8 0a 0d d9 6b 59 11 22 07 81""")
        val sds = SdsSignalling(time + 5, conversation, reply, inReplyTo = message, applicationId = 7,
            disposition = DispositionRequest.DELIVERY)
        assertArrayEquals(bytes, sds.encode())
        assertEquals(sds, SdsSignalling.decode(bytes))
    }

    @Test
    fun exampleF() {
        val bytes = hex("""
            05 04 00 6a b6 62 42 9a 32 32 17 17 bd 4a 01 bd
            07 5d 02 55 86 95 b1 64 02 17 8c a8 6b 49 8e 92
            e8 0a 0d d9 6b 59 11 51 00 17 73 69 70 3a 62 6f
            62 2e 6d 63 64 61 74 61 40 6d 63 78 2e 74 65 73
            74""")
        val n = SdsNotification(DispositionNotification.DELIVERED_AND_READ, time + 2, conversation, message,
            sender = "sip:bob.mcdata@mcx.test")
        assertArrayEquals(bytes, n.encode())
        assertEquals(n, SdsNotification.decode(bytes))
        assertEquals(MessageType.SDS_NOTIFICATION, MessageType.of(bytes))
    }

    @Test
    fun rejectsReservedDuplicateAndUnknown() {
        val reserved = exampleA.copyOf().also { it[it.lastIndex] = 0x84.toByte() }
        assertThrows(McdataCodecError::class.java) { SdsSignalling.decode(reserved) }
        assertThrows(McdataCodecError::class.java) { SdsSignalling.decode(exampleA + byteArrayOf(0x81.toByte())) }
        assertThrows(McdataCodecError::class.java) { SdsSignalling.decode(exampleA + byteArrayOf(0x33)) }
        assertThrows(McdataCodecError::class.java) { DataPayload.decode(hex("03 02 78 00 02 01 41")) }
        assertThrows(McdataCodecError::class.java) { SdsSignalling.decode(byteArrayOf(0x41) + exampleA.copyOfRange(1, exampleA.size)) }
    }
}

class McdataInfoTest {
    @Test
    fun groupRequestMatchesTheContract() {
        val xml = McdataInfo(requestType = "group-sds", requestUri = "sip:operations@mcx.test",
            clientId = "urn:uuid:8034b521-39ae-47bd-b7bf-aaa7a6a316ef").encode()
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?><mcdatainfo xmlns=\"urn:3gpp:ns:mcdataInfo:1.0\"><mcdata-Params>" +
            "<request-type>group-sds</request-type><mcdata-request-uri type=\"Normal\"><mcdataURI>sip:operations@mcx.test</mcdataURI>" +
            "</mcdata-request-uri><mcdata-client-id type=\"Normal\"><mcdataString>urn:uuid:8034b521-39ae-47bd-b7bf-aaa7a6a316ef" +
            "</mcdataString></mcdata-client-id></mcdata-Params></mcdatainfo>", xml)
    }

    @Test
    fun parsesDownlink() {
        val info = McdataInfo.parse("<?xml version=\"1.0\" encoding=\"UTF-8\"?><mcdatainfo xmlns=\"urn:3gpp:ns:mcdataInfo:1.0\">" +
            "<mcdata-Params><request-type>group-sds</request-type><mcdata-request-uri type=\"Normal\"><mcdataURI>sip:bob.mcdata@mcx.test" +
            "</mcdataURI></mcdata-request-uri><mcdata-calling-user-id type=\"Normal\"><mcdataURI>sip:alice.mcdata@mcx.test</mcdataURI>" +
            "</mcdata-calling-user-id><mcdata-calling-group-id type=\"Normal\"><mcdataURI>sip:operations@mcx.test</mcdataURI>" +
            "</mcdata-calling-group-id><anyExt><used-priority>5</used-priority></anyExt></mcdata-Params></mcdatainfo>")
        assertEquals("group-sds", info.requestType)
        assertEquals("sip:alice.mcdata@mcx.test", info.callingUserId)
        assertEquals("sip:operations@mcx.test", info.callingGroupId)
        assertEquals(5, info.usedPriority)
    }

    @Test
    fun resourceListsRoundTrip() {
        val xml = org.openmcx.client.body.ResourceLists.encode(listOf("sip:bob.mcdata@mcx.test"))
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?><resource-lists xmlns=\"urn:ietf:params:xml:ns:resource-lists\">" +
            "<list><entry uri=\"sip:bob.mcdata@mcx.test\"/></list></resource-lists>", xml)
        assertEquals(listOf("sip:bob.mcdata@mcx.test"), org.openmcx.client.body.ResourceLists.parse(xml))
    }
}

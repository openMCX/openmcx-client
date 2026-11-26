//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.body

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MultipartTest {
    @Test
    fun roundTripsBinaryAndXml() {
        val binary = byteArrayOf(0, 13, 10, 45, 45, 1, -1)
        val parts = listOf(Part("application/sdp", "v=0\r\n".toByteArray()), Part("application/vnd.3gpp.mcdata-signalling", binary))
        val (type, body) = Multipart.encode(parts, boundary = "b1")
        assertEquals("multipart/mixed; boundary=b1", type)
        val back = Multipart.parse(type, body)
        assertEquals(listOf("application/sdp", "application/vnd.3gpp.mcdata-signalling"), back.map { it.mediaType })
        assertArrayEquals("v=0\r\n".toByteArray(), back[0].content)
        assertArrayEquals(binary, back[1].content)
    }

    @Test
    fun parsesQuotedBoundaryAndPreamble() {
        val body = "preamble\r\n--xyz\r\nContent-Type: text/plain\r\nContent-ID: <a>\r\n\r\nhello\r\n--xyz--\r\n".toByteArray()
        val parts = Multipart.parse("multipart/mixed; boundary=\"xyz\"", body)
        assertEquals(1, parts.size)
        assertEquals("hello", parts[0].text)
        assertEquals(listOf("Content-ID" to "<a>"), parts[0].headers)
    }

    @Test
    fun singleBodyIsOnePart() {
        assertEquals("application/sdp", Multipart.parse("application/sdp", "v=0".toByteArray()).single().mediaType)
    }

    /**
     * TS 24.379 §6.5 (TS 24.282 §6.4): one MIME body is sent as itself, with its own type as the Content-Type; several
     * as multipart/mixed with a boundary delimiter before each (RFC 2046 §5.1.1). A lone part with its own headers
     * stays multipart.
     */
    @Test
    fun oneBodyGoesAsItself() {
        val info = Part("application/vnd.3gpp.mcptt-info+xml", "<mcpttinfo/>".toByteArray())
        val (type, body) = Multipart.body(listOf(info))
        assertEquals("application/vnd.3gpp.mcptt-info+xml", type)
        assertArrayEquals("<mcpttinfo/>".toByteArray(), body)
        val (two, both) = Multipart.body(listOf(info, Part("application/vnd.3gpp.mcptt-location-info+xml", "<l/>".toByteArray())), "b2")
        assertEquals("multipart/mixed; boundary=b2", two)
        assertEquals(2, Regex("--b2\r\n").findAll(String(both)).count())
        val (withId, _) = Multipart.body(listOf(info.copy(headers = listOf("Content-ID" to "<x>"))), "b3")
        assertEquals("multipart/mixed; boundary=b3", withId)
    }
}

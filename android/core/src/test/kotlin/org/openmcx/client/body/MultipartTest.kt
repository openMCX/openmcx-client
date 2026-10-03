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
}

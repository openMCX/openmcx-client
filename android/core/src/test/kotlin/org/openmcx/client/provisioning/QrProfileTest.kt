package org.openmcx.client.provisioning

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.URL
import java.util.Base64
import java.util.zip.Deflater

class QrProfileTest {
    private val profile = """{"format":"mcx-provisioning/1","bundle":{}}"""

    private fun inline(text: String): String {
        val deflater = Deflater(9).apply { setInput(text.toByteArray()); finish() }
        val buffer = ByteArray(4096)
        val n = deflater.deflate(buffer)
        return QrProfile.INLINE + Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.copyOf(n))
    }

    @Test fun inlineProfile() = assertEquals(profile, QrProfile.load(inline(profile)))

    @Test fun pinnedLink() {
        val pin = ByteArray(32) { it.toByte() }
        var asked: URL? = null
        var pinned: ByteArray? = null
        val text = QrProfile.load("https://192.0.2.10:8450/p/abc?x=1#pin=" + Base64.getUrlEncoder().withoutPadding().encodeToString(pin)) { url, p ->
            asked = url; pinned = p; profile
        }
        assertEquals(profile, text)
        assertEquals("https://192.0.2.10:8450/p/abc?x=1", asked.toString())
        assertArrayEquals(pin, pinned)
    }

    private fun refused(code: String, reason: String) {
        try {
            QrProfile.load(code) { _, _ -> fail("fetched"); "" }
            fail("accepted $code")
        } catch (e: IllegalArgumentException) {
            assertTrue("${e.message}", reason in (e.message ?: ""))
        }
    }

    @Test fun refusals() {
        refused("hello", "not an openMCX profile")
        refused("http://host/p#pin=AAAA", "not an openMCX profile")
        refused("https://mcxadmin:8443/activate/abcdef", "MCX Admin activation code without the certificate pin")
        refused("https://host/p", "names no server certificate")
        refused("https://host/p#pin=AAAA", "not a SHA-256")
        refused(QrProfile.INLINE + "AAAA", "damaged")
    }
}

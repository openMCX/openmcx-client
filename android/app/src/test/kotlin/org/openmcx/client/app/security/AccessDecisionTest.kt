//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.app.security

import org.junit.Assert.assertEquals
import org.junit.Test
import org.openmcx.client.sip.tlsVersion

/** The protection as Settings names it: the algorithms of TS 33.203 annexes H and I, the TLS version. */
class AccessDecisionTest {
    @Test
    fun aesGcmIsEncryptionAndIntegrity() {
        // With AES-GCM (AEAD) the integrity algorithm is "null" (TS 33.203 annex H): not "no protection".
        assertEquals("IPsec ESP, AES-128-GCM", AccessDecision.espName("null", "aes-gcm-us"))
        assertEquals("IPsec ESP, AES-128-GCM", AccessDecision.espName("null", "aes-gcm"))
    }

    @Test
    fun theOtherPairs() {
        assertEquals("IPsec ESP, AES-128-CBC + HMAC-SHA-256", AccessDecision.espName("hmac-sha2-256-128", "aes-cbc"))
        assertEquals("IPsec ESP, HMAC-SHA-256, no encryption", AccessDecision.espName("hmac-sha2-256-128", "null"))
        assertEquals("IPsec ESP, AES-128-CBC + HMAC-SHA-1", AccessDecision.espName("hmac-sha-1-96", "aes-cbc"))
    }

    @Test
    fun tlsVersionReadsAsWords() {
        assertEquals("TLS 1.3", tlsVersion("TLSv1.3"))
        assertEquals("TLS 1.2", tlsVersion("TLSv1.2"))
    }
}

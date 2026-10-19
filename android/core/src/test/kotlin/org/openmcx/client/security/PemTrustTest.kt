//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.security

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.CertificateException

/** IdMS trust (Pem.trustWithSystem): the provisioned CA and the system's, nothing else. */
class PemTrustTest {
    private val own = Pem.certificates(OWN).single()
    private val other = Pem.certificates(OTHER).single()

    @Test
    fun provisionedCaAndSystemCas() {
        val trust = Pem.trustWithSystem(listOf(own))
        trust.checkServerTrusted(arrayOf(own), "ECDHE_ECDSA")
        // A certificate neither the provisioned CA nor a system CA issued.
        assertThrows(CertificateException::class.java) { trust.checkServerTrusted(arrayOf(other), "ECDHE_ECDSA") }
        // The system's CAs are anchors too (the JVM's cacerts here, Android's system store on a phone).
        assertTrue(trust.acceptedIssuers.size > 1)
    }

    private companion object {
        // Self-signed P-256 test CAs (openssl req -x509), 100 years.
        const val OWN = """-----BEGIN CERTIFICATE-----
MIIBjDCCATOgAwIBAgIUbBpvPKOLc9jMO8eAGoV5HnJC8/EwCgYIKoZIzj0EAwIw
GzEZMBcGA1UEAwwQb3Blbm1jeCB0ZXN0IG93bjAgFw0yNjEwMTkwNTAxNDhaGA8y
MTI2MDkyNTA1MDE0OFowGzEZMBcGA1UEAwwQb3Blbm1jeCB0ZXN0IG93bjBZMBMG
ByqGSM49AgEGCCqGSM49AwEHA0IABMkmi3C+ER0ZBW4Km+6+Yjf+K6KsxYrtZ/ei
aX9YjRWTa+aJe/A9GR6B692g4qOvENbC05TLh7TWYAkzDD7bhwGjUzBRMB0GA1Ud
DgQWBBSFwyULhDd8R/iIWORF3rYlTcNfVDAfBgNVHSMEGDAWgBSFwyULhDd8R/iI
WORF3rYlTcNfVDAPBgNVHRMBAf8EBTADAQH/MAoGCCqGSM49BAMCA0cAMEQCIDVC
h3EIj71EfiDnVcxsYdVxAqJvCQaFJ3+J4wvhtt2uAiAchwwrlIUYRychzji35TJn
v/lzCKmCWU55/HecvjUJZQ==
-----END CERTIFICATE-----
"""
        const val OTHER = """-----BEGIN CERTIFICATE-----
MIIBkjCCATegAwIBAgIUQZmJGhVRGb9Yza/ZIthMHKpJjxwwCgYIKoZIzj0EAwIw
HTEbMBkGA1UEAwwSb3Blbm1jeCB0ZXN0IG90aGVyMCAXDTI2MTAxOTA1MDE0OFoY
DzIxMjYwOTI1MDUwMTQ4WjAdMRswGQYDVQQDDBJvcGVubWN4IHRlc3Qgb3RoZXIw
WTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAAQBQpfHbU18rZ2lWyPtcbSx7sB2azdz
+UwIATsYwZGV6fa7rVdLR819Y0FLcLOq91D2py2Tadus6tI5Z2PWHgWRo1MwUTAd
BgNVHQ4EFgQU924qd8C3rCP3K+EIbinz8IWQARQwHwYDVR0jBBgwFoAU924qd8C3
rCP3K+EIbinz8IWQARQwDwYDVR0TAQH/BAUwAwEB/zAKBggqhkjOPQQDAgNJADBG
AiEAgAMd1jceGp5HF5DSIzUAOPw1ByKJd53oRKA+6NrFcNsCIQDFpviIGTv4qbuB
YY2oGDWr34N+GZhSdsD+iVrNH7UmWg==
-----END CERTIFICATE-----
"""
    }
}

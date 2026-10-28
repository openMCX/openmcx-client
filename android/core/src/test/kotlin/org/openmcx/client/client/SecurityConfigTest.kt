//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.openmcx.client.security.Mikey

/** RFC 3830 §5.4: the MIKEY clock skew is "set by the local policy": security.mikey_clock_skew_s, 300 s without it. */
class SecurityConfigTest {
    private fun profile(skew: String = "") = ClientConfig.parse("""{
      "name":"ue","domain":"x","client_id":"urn:uuid:30313233-3435-3637-3839-3a3b3c3d3e3f",
      "sip":{"public_user_id":"sip:a@x","private_user_id":"a","password":"test","proxy":"sip-core","proxy_port":5061,"transport":"TLS"},
      "idms":{"issuer":"https://idms:8443","client_id":"ue","redirect_uri":"http://localhost:8888"},
      "cms":"https://proxy:8443/cms","gms":"https://proxy:8443/gms","media":{},
      "security":{"kms_uri":"https://kms:8443","key_parameter_profile":{}$skew}
    }""")

    @Test fun theClockSkewIsConfigurable() {
        assertEquals(Mikey.DEFAULT_CLOCK_SKEW_S, profile().security!!.mikeyClockSkewS)
        assertEquals(60L, profile(""","mikey_clock_skew_s":60""").security!!.mikeyClockSkewS)
        assertThrows(ConfigError::class.java) { profile(""","mikey_clock_skew_s":-1""") }
        assertThrows(ConfigError::class.java) { profile(""","mikey_clock_skew_s":"60"""") }
    }
}

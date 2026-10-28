//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * TS 33.180 annex B.5.1 and §5.1.1 leave the token renewal time and the retry delays to the client: configurable,
 * with today's values when absent (renewal 300 s ahead, refresh at start below 600 s, retries 2 s doubling to 60 s).
 */
class TimingConfigTest {
    private fun profile(idms: String = "", recovery: String = "") = ClientConfig.parse("""{
      "name":"ue","domain":"x","client_id":"urn:uuid:30313233-3435-3637-3839-3a3b3c3d3e3f",
      "sip":{"public_user_id":"sip:a@x","private_user_id":"a","password":"test","proxy":"sip-core","proxy_port":5061,"transport":"TLS"},
      "idms":{"issuer":"https://idms:8443","client_id":"ue","redirect_uri":"http://localhost:8888"$idms},
      "cms":"https://proxy:8443/cms","gms":"https://proxy:8443/gms","media":{}$recovery
    }""")

    @Test fun theDefaultsAreUnchanged() {
        val config = profile()
        assertEquals(300L, config.idms.tokenRenewalLeadS)
        assertEquals(600L, config.idms.tokenRefreshBeforeS)
        assertEquals(2_000L, config.recovery.firstRetryMs)
        assertEquals(60_000L, config.recovery.lastRetryMs)
        assertEquals(ClientConfig.Recovery(), profile(recovery = ""","recovery":{}""").recovery)
    }

    @Test fun theValuesAreRead() {
        val config = profile(""","token_renewal_lead_s":120,"token_refresh_before_s":900""",
            ""","recovery":{"first_retry_ms":500,"last_retry_ms":8000}""")
        assertEquals(120L, config.idms.tokenRenewalLeadS)
        assertEquals(900L, config.idms.tokenRefreshBeforeS)
        assertEquals(ClientConfig.Recovery(500, 8000), config.recovery)
        assertEquals(ClientConfig.Recovery(90_000, 90_000), profile(recovery = ""","recovery":{"first_retry_ms":90000}""").recovery)
    }

    @Test fun badValuesAreRefused() {
        assertThrows(ConfigError::class.java) { profile(""","token_renewal_lead_s":-1""") }
        assertThrows(ConfigError::class.java) { profile(""","token_refresh_before_s":"600"""") }
        assertThrows(ConfigError::class.java) { profile(recovery = ""","recovery":{"first_retry_ms":0}""") }
        assertThrows(ConfigError::class.java) { profile(recovery = ""","recovery":{"first_retry_ms":5000,"last_retry_ms":1000}""") }
    }
}

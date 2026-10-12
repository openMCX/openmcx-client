//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcdata

import org.junit.Assert.*
import org.junit.Test
import org.openmcx.client.client.ClientConfig

class FdPolicyTest {
    @Test fun effectiveLimitCombinesApplicableBounds() {
        val config = FdConfig(serviceMaxBytes = 100_000_000, userMaxBytes = 80_000_000)
        assertEquals(20_000_000L, FdPolicy.effectiveLimit(config, GroupFdPolicy(true, true, 20_000_000)))
        assertEquals(80_000_000L, FdPolicy.effectiveLimit(config, null))
        assertEquals(90_000_000L, FdPolicy.effectiveLimit(config, GroupFdPolicy(true, true, 90_000_000)))
        assertNull(FdPolicy.effectiveLimit(FdConfig(serviceMaxBytes = 0), null))
        assertNull(FdPolicy.effectiveLimit(FdConfig(), null))
    }

    @Test fun exactLimitAllowedAndNextByteRejected() {
        val config = FdConfig(serviceMaxBytes = 100_000_000)
        FdPolicy.requireAllowed(config, null, 100_000_000)
        assertThrows(IllegalArgumentException::class.java) { FdPolicy.requireAllowed(config, null, 100_000_001) }
        assertThrows(IllegalArgumentException::class.java) { FdPolicy.requireAllowed(config, GroupFdPolicy(false, true, 0), 1) }
        assertThrows(IllegalArgumentException::class.java) { FdPolicy.requireAllowed(config, null, -1) }
    }

    private fun provisioning(fd: String = "") = """{
      "name":"ue","domain":"x","client_id":"urn:uuid:30313233-3435-3637-3839-3a3b3c3d3e3f",
      "sip":{"public_user_id":"sip:a@x","private_user_id":"a","password":"test","proxy":"sip-core","proxy_port":5061,"transport":"TLS"},
      "idms":{"issuer":"https://idms:8443","client_id":"ue","redirect_uri":"http://localhost:8888"},
      "cms":"https://proxy:8443/cms","gms":"https://proxy:8443/gms","media":{},
      "mcdata":{"user_id":"sip:a.data@x","psi":"sip:mcdata@x"$fd}
    }"""

    @Test fun legacyProvisioningStillParses() {
        assertNull(ClientConfig.parse(provisioning()).mcdata!!.fd.serviceMaxBytes)
        val config = ClientConfig.parse(provisioning(",\"fd\":{\"service_max_bytes\":5000000000,\"content_server\":\"https://proxy:8443/content\"}"))
        assertEquals(5_000_000_000L, config.mcdata!!.fd.serviceMaxBytes)
    }

    @Test fun invalidLimitsAndTimersAreRejected() {
        for (fd in listOf("\"service_max_bytes\":-1", "\"tdu2_seconds\":0", "\"transfer_timeout_ms\":-1"))
            assertThrows(IllegalArgumentException::class.java) { ClientConfig.parse(provisioning(",\"fd\":{$fd}")) }
    }
}

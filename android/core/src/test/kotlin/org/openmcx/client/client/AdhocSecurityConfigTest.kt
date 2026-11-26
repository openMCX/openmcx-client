//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * TS 24.379 §17.1 and TS 24.281 §22.1 (TS 33.180 §5.7): the preconfigured groups of end-to-end protected ad hoc group
 * calls, `mcptt.adhoc_security_groups` (the Linux client's key) and `mcvideo.adhoc_security_groups`, whose GMKs are
 * subscribed to from the start; none by default.
 */
class AdhocSecurityConfigTest {
    private fun profile(mcptt: String = "", mcvideo: String = "") = ClientConfig.parse("""{
      "name":"ue","domain":"x","client_id":"urn:uuid:30313233-3435-3637-3839-3a3b3c3d3e3f",
      "sip":{"public_user_id":"sip:a@x","private_user_id":"a","password":"test","proxy":"sip-core","proxy_port":5061,"transport":"TLS"},
      "idms":{"issuer":"https://idms:8443","client_id":"ue","redirect_uri":"http://localhost:8888"},
      "cms":"https://proxy:8443/cms","gms":"https://proxy:8443/gms","media":{},
      "mcptt":{"user_id":"sip:a.mcptt@x","psi":"sip:mcptt@x","groups":["sip:g@x"]$mcptt},
      "mcvideo":{"user_id":"sip:a.mcvideo@x","psi":"sip:mcvideo@x","groups":["sip:g@x"]$mcvideo}
    }""")

    @Test fun noneByDefault() {
        val config = profile()
        assertEquals(emptyList<String>(), config.mcptt!!.adhocSecurityGroups)
        assertEquals(emptyList<String>(), config.mcvideo!!.adhocSecurityGroups)
    }

    @Test fun theGroupsAreRead() {
        val config = profile(""","adhoc_security_groups":["sip:adhoc-security@x"]""", ""","adhoc_security_groups":["sip:video-security@x"]""")
        assertEquals(listOf("sip:adhoc-security@x"), config.mcptt!!.adhocSecurityGroups)
        assertEquals(listOf("sip:video-security@x"), config.mcvideo!!.adhocSecurityGroups)
    }

    @Test fun aGroupThatIsNoSipUriIsRefused() {
        assertThrows(ConfigError::class.java) { profile(""","adhoc_security_groups":["tel:+1"]""") }
    }
}

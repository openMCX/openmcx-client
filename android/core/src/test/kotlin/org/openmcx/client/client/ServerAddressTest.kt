//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAddressTest {
    private fun profile(endpoints: String = "") = ClientConfig.parse("""{
      "name":"ue","domain":"x","client_id":"urn:uuid:30313233-3435-3637-3839-3a3b3c3d3e3f",
      "sip":{"public_user_id":"sip:a@x","private_user_id":"a","password":"test","proxy":"sip-core","proxy_port":5061,"transport":"TLS"},
      "idms":{"issuer":"https://idms:8443","client_id":"ue","redirect_uri":"http://localhost:8888"},
      "cms":"https://proxy:8443/cms","gms":"https://proxy:8443/gms","media":{}$endpoints
    }""")

    private val lab = profile(""","endpoints":{"sip-core:5061":"192.0.2.39:5061","idms:8443":"192.0.2.39:8443","http-proxy:8443":"192.0.2.39:8444"}""")

    @Test fun onlyTheAddressChangesEachServerKeepsItsPort() {
        val moved = ServerAddress.apply(lab, "10.0.0.7")
        assertEquals(mapOf("sip-core:5061" to "10.0.0.7:5061", "idms:8443" to "10.0.0.7:8443", "http-proxy:8443" to "10.0.0.7:8444"), moved.endpoints)
        assertEquals(lab.copy(endpoints = moved.endpoints), moved)
        assertEquals("10.0.0.7", ServerAddress.of(moved))
        assertEquals("192.0.2.39", ServerAddress.of(lab))
    }

    @Test fun aProfileWithoutEndpointsGetsThemForTheSipCoreAndTheIdms() {
        val moved = ServerAddress.apply(profile(), "mcx.example.org")
        assertEquals(mapOf("sip-core:5061" to "mcx.example.org:5061", "idms:8443" to "mcx.example.org:8443"), moved.endpoints)
        assertNull(ServerAddress.of(profile()))
    }

    @Test fun addressesAreChecked() {
        for (ok in listOf("192.0.2.10", "10.0.0.1", "mcx-server", "mcx.example.org")) assertTrue(ok, ServerAddress.valid(ok))
        for (bad in listOf("", "192.0.2.300", "192.0.2", "http://10.0.0.1", "10.0.0.1:5061", "a b", "-x.org"))
            assertFalse(bad, ServerAddress.valid(bad))
        assertThrows(IllegalArgumentException::class.java) { ServerAddress.apply(lab, "10.0.0.1:5061") }
    }
}

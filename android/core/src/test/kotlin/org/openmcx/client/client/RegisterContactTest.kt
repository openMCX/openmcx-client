//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MC feature tags and ICSIs of the REGISTER Contact: TS 24.379 §7.2.1 (MCPTT), TS 24.282 §7.2.1 1)-4) (MCData and
 * its SDS, FD and IPCONN services, each when supported) and TS 24.281 §7.2.1 (MCVideo).
 */
class RegisterContactTest {
    private fun config(mcdata: String? = null, mcvideo: Boolean = false) = ClientConfig.parse("""{
        "name": "client-a", "domain": "mcx.test", "client_id": "urn:uuid:1",
        "sip": {"public_user_id": "sip:alice@mcx.test", "private_user_id": "alice", "password": "s",
                "proxy": "sip-core", "proxy_port": 5061, "transport": "TLS", "register_expires": 3600},
        "idms": {"issuer": "https://idms", "client_id": "mcx-client", "redirect_uri": "http://127.0.0.1:8901/callback"},
        "cms": "https://cms", "gms": "https://gms",
        "mcptt": {"user_id": "sip:alice.mcptt@mcx.test", "psi": "sip:mcptt@mcx.test", "groups": [],
                  "answer_mode": "automatic", "session_expires": 1800, "manual_answer_timeout": 20},""" +
        (mcdata?.let { """"mcdata": {"user_id": "sip:alice.mcdata@mcx.test", "psi": "sip:mcdata@mcx.test", "groups": []$it},""" } ?: "") +
        (if (mcvideo) """"mcvideo": {"user_id": "sip:alice.mcvideo@mcx.test", "psi": "sip:mcvideo@mcx.test", "groups": []},""" else "") +
        """"media": {"port_range": [20000, 20400], "amr_wb_mode": 2}}""")

    private fun tags(params: String) = params.split(';').filter { it.isNotBlank() }.map { it.substringBefore('=') }
    private fun icsis(params: String) =
        Regex("""\+g\.3gpp\.icsi-ref="([^"]*)"""").find(params)!!.groupValues[1].split(',').map { it.replace("%3A", ":") }

    @Test
    fun ipconnRegisteredWithAnAddress() {
        val p = registerContactParams(config(""", "ipconn": {"address": "10.77.0.1"}"""))
        assertEquals(listOf("+sip.instance", "reg-id", "+g.3gpp.mcptt", "+g.3gpp.mcdata.sds", "+g.3gpp.mcdata.fd",
            "+g.3gpp.mcdata.ipconn", "+g.3gpp.icsi-ref"), tags(p))
        assertEquals(listOf(Icsi.MCPTT, Icsi.MCDATA, Icsi.MCDATA_SDS, Icsi.MCDATA_FD,
            "urn:urn-7:3gpp-service.ims.icsi.mcdata.ipconn"), icsis(p))
        assertTrue(p, p.contains("+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.mcptt,"))
        assertTrue(p, p.startsWith(";+sip.instance=\"<urn:uuid:1>\";reg-id=1;"))
    }

    @Test
    fun noIpconnWithoutAnAddress() {
        for (extra in listOf("", """, "ipconn": {}""", """, "ipconn": {"address": ""}""")) {
            val p = registerContactParams(config(extra))
            assertFalse(p, p.contains("ipconn"))
            assertEquals(listOf(Icsi.MCPTT, Icsi.MCDATA, Icsi.MCDATA_SDS, Icsi.MCDATA_FD), icsis(p))
        }
    }

    @Test
    fun noMcdataTagsWithoutMcdata() {
        val p = registerContactParams(config())
        assertEquals(listOf("+sip.instance", "reg-id", "+g.3gpp.mcptt", "+g.3gpp.icsi-ref"), tags(p))
        assertEquals(listOf(Icsi.MCPTT), icsis(p))
    }

    @Test
    fun mcvideoLast() {
        val p = registerContactParams(config(""", "ipconn": {"address": "10.77.0.1"}""", mcvideo = true))
        assertEquals("+g.3gpp.mcvideo", tags(p)[6])
        assertEquals(Icsi.MCVIDEO, icsis(p).last())
        assertEquals(6, icsis(p).size)
    }
}

//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.openmcx.client.body.McService
import org.openmcx.client.sip.Headers
import org.openmcx.client.sip.SipRequest
import org.openmcx.client.sip.UaIdentity
import org.openmcx.client.sip.UserAgent

/** Which MC service a request belongs to, and the gate that keeps a service that is not started off the wire. */
class ServiceGateTest {
    private fun request(method: String = "MESSAGE", vararg headers: Pair<String, String>) =
        SipRequest(method, "sip:mcdata@mcx.test", Headers().also { h -> headers.forEach { (n, v) -> h.add(n, v) } })

    @Test
    fun classifiesByIcsi() {
        assertEquals(McService.MCDATA, mcServiceOf(request(headers = arrayOf("P-Preferred-Service" to Icsi.MCDATA_SDS))))
        assertEquals(McService.MCDATA, mcServiceOf(request(headers = arrayOf("P-Preferred-Service" to Icsi.MCDATA_FD))))
        assertEquals(McService.MCPTT, mcServiceOf(request("INVITE", "P-Preferred-Service" to Icsi.MCPTT)))
        assertEquals(McService.MCVIDEO, mcServiceOf(request("PUBLISH", "P-Preferred-Service" to Icsi.MCVIDEO)))
        assertEquals(McService.MCDATA, mcServiceOf(request(headers = arrayOf("P-Asserted-Service" to Icsi.MCDATA_SDS))))
    }

    @Test
    fun classifiesByAcceptContact() {
        assertEquals(McService.MCDATA, mcServiceOf(request(headers = arrayOf(
            "Accept-Contact" to "*;+g.3gpp.mcdata.sds;require;explicit"))))
        assertEquals(McService.MCPTT, mcServiceOf(request("INVITE",
            "Accept-Contact" to "*;+g.3gpp.icsi-ref=\"${Icsi.encoded(Icsi.MCPTT)}\";require;explicit")))
        assertEquals(McService.MCVIDEO, mcServiceOf(request("INVITE", "Accept-Contact" to "*;+g.3gpp.mcvideo;require;explicit")))
    }

    @Test
    fun noServiceForRegisterOrInDialog() {
        assertNull(mcServiceOf(request("REGISTER")))
        assertNull(mcServiceOf(request("BYE")))
    }

    @Test
    fun gateStopsTheRequestBeforeTheWire() {
        val ua = UserAgent(CoroutineScope(SupervisorJob() + Dispatchers.Default),
            UaIdentity("sip:alice@mcx.test", "alice", "s", "mcx.test", "urn:uuid:1"))
        ua.outboundGate = { if (mcServiceOf(it) == McService.MCDATA) throw IllegalStateException("MCDATA not started") }
        val sds = request(headers = arrayOf("P-Preferred-Service" to Icsi.MCDATA_SDS))
        // No flow is set: the gate answers before the transaction layer would look for one.
        val error = assertThrows(IllegalStateException::class.java) { runBlocking { ua.send(sds) } }
        assertEquals("MCDATA not started", error.message)
        val other = assertThrows(IllegalStateException::class.java) {
            runBlocking { ua.send(request("INVITE", "P-Preferred-Service" to Icsi.MCPTT)) }
        }
        assertEquals("no flow", other.message)
    }
}

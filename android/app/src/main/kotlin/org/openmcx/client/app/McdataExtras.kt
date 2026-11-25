//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.app

import kotlinx.coroutines.CoroutineScope
import org.openmcx.client.client.McxClient
import org.openmcx.client.http.HttpClient
import org.openmcx.client.mcdata.ClientMediaPeer
import org.openmcx.client.mcdata.EnhancedStatusService
import org.openmcx.client.mcdata.IpconService
import org.openmcx.client.security.KeyClient

/**
 * The MCData procedures beyond SDS and FD that the engine runs (parity phase 4b): IP connectivity (TS 24.282 §20),
 * enhanced status (§14), the message store and its notification channel (§21). Kept apart from [Engine], which starts
 * them with the MCData service ([start]) and forgets them on a restart ([stop]).
 */
object McdataExtras {
    @Volatile var ipcon: IpconService? = null
        private set
    @Volatile var enhancedStatus: EnhancedStatusService? = null
        private set

    fun start(client: McxClient, scope: CoroutineScope, http: HttpClient) {
        val mcdata = client.config.mcdata ?: return
        val sds = Engine.mcdata ?: return
        // TS 24.282 §20.2.2: ahead of the media plane and MCPTT, which would take any INVITE they do not recognise.
        val ipconn = IpconService(scope, ClientMediaPeer(client), { client.ua.contactUri() }, mcdata.ipconnAddress)
        client.handlers.add(0, ipconn)
        ipcon = ipconn
        // TS 24.282 §14.2.1: the operational values come from the group document at the GMS (TS 24.481 §6.3.3).
        enhancedStatus = EnhancedStatusService(sds) { group ->
            val token = client.tokens?.accessToken ?: throw IllegalStateException("not signed in")
            val r = http.get("${client.config.gms}/org.openmobilealliance.groups/global/byGroupID/${KeyClient.xcapSegment(group)}",
                mapOf("Authorization" to "Bearer $token"))
            if (r.status != 200) throw IllegalStateException("group document of $group: HTTP ${r.status}")
            r.text
        }.also { es -> es.event = { client.event("mcdata: $it") } }
    }

    fun stop() {
        ipcon = null; enhancedStatus = null
    }
}

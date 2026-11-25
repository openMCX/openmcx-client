//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.app

import kotlinx.coroutines.CoroutineScope
import org.openmcx.client.client.McxClient
import org.openmcx.client.http.HttpClient
import org.openmcx.client.mcdata.ClientMediaPeer
import org.openmcx.client.mcdata.IpconService

/**
 * The MCData procedures beyond SDS and FD that the engine runs (parity phase 4b): IP connectivity (TS 24.282 §20),
 * enhanced status (§14), the message store and its notification channel (§21). Kept apart from [Engine], which starts
 * them with the MCData service ([start]) and forgets them on a restart ([stop]).
 */
object McdataExtras {
    @Volatile var ipcon: IpconService? = null
        private set

    fun start(client: McxClient, scope: CoroutineScope, @Suppress("UNUSED_PARAMETER") http: HttpClient) {
        val mcdata = client.config.mcdata ?: return
        // TS 24.282 §20.2.2: ahead of the media plane and MCPTT, which would take any INVITE they do not recognise.
        val ipconn = IpconService(scope, ClientMediaPeer(client), { client.ua.contactUri() }, mcdata.ipconnAddress)
        client.handlers.add(0, ipconn)
        ipcon = ipconn
    }

    fun stop() {
        ipcon = null
    }
}

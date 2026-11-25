//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcdata

import org.openmcx.client.body.Part

/** FD over the media plane (TS 24.282 §10.2.5): not yet; an offered file is refused. */
internal object FdMedia {
    suspend fun offer(service: McdataMediaService, c: MediaCommunication, tx: MediaInvite, parts: List<Part>, offer: MsrpMedia): Boolean {
        tx.respond(488); return false
    }
    suspend fun received(service: McdataMediaService, c: MediaCommunication, body: ByteArray) {
        service.internals.event("communication ${c.id}: unexpected file")
    }
}

//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcdata

/** MCData communication release (TS 24.282 §13.2): not yet; the INFO is answered. */
internal object Release {
    suspend fun received(service: McdataMediaService, c: MediaCommunication, tx: MediaInvite) { tx.respond(200) }
}

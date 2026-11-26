//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcptt

/**
 * End-to-end protected ad hoc group calls, MCPTT (TS 24.379 V19.7.0 §17) and MCVideo (TS 24.281 V19.6.0 §22), with
 * TS 33.180 V19.4.0 §5.7 and §7.3: an ad hoc group has no GMK of its own; "the security related information is used
 * from this preconfigured group" (§17.1), the one the controlling function names in <preconfigured-group-id>. Pure
 * functions, so that each rule has its unit test; the services do the keying.
 */
object AdhocSecurity {
    /**
     * §17.2.2.1.1 step 10 c) iii) (TS 24.281 §22.2.2.1.1 step 10 d) iii)): "if end-to-end security needs to be
     * established", <end-to-end-security> "true". A client that protects media requests it (as the Linux client); null
     * leaves the element out.
     */
    fun requested(protectedMedia: Boolean): Boolean? = if (protectedMedia) true else null

    /** How the media of an ad hoc group call are keyed. */
    sealed class Keying {
        /** In the clear: no protected media. */
        data object Clear : Keying()
        /** Under the GMK of [group], the preconfigured group named. */
        data class Group(val group: String) : Keying()
        /** Not keyable: refused (terminating: 488) or ended (originating). */
        data class Refused(val reason: String) : Keying()
    }

    /**
     * The originator on the 2xx (§17.2.2.1.1 step 3, TS 24.281 §22.2.2.1.1 step 3) and the invitee on the INVITE
     * (§17.2.2.1.2 step 7, TS 24.281 §22.2.2.1.2 step 8): "if the <preconfigured-group-id> element received ..., shall use
     * the security related information from the group configuration associated with the received preconfigured group
     * identity". Protected media ([protectedMedia], the negotiated or offered RTP/SAVP) without the element have no key.
     * Clear media are clear whatever the element says.
     */
    fun keying(protectedMedia: Boolean, preconfiguredGroup: String?): Keying = when {
        !protectedMedia -> Keying.Clear
        preconfiguredGroup.isNullOrBlank() -> Keying.Refused("no <preconfigured-group-id> for the protected ad hoc call")
        !preconfiguredGroup.trim().let { it.startsWith("sip:") || it.startsWith("sips:") } ->
            Keying.Refused("<preconfigured-group-id> is not a SIP URI: $preconfiguredGroup")
        else -> Keying.Group(preconfiguredGroup.trim())
    }

    /**
     * TS 33.180 §7.3 (E.3.2): a received packet's sender is the one whose GUK-ID its MKI carries. In an ad hoc call the
     * candidates are the talker (the floor's or transmission's, first), the initiator, the listed participants and the
     * members of the preconfigured group (only they hold its GMK); without repeats, never this user.
     */
    fun senders(talker: String?, initiator: String?, participants: Collection<String>, members: Collection<String>, self: String): List<String> =
        (listOfNotNull(talker, initiator) + participants + members).filter { it.isNotBlank() && it != self }.distinct()

    /**
     * TS 24.481 §6.3.13.2.1: the groups whose GMK subscriptions (GKTPs) the client makes: its groups, then the
     * configured preconfigured groups of ad hoc calls (§17.1), each once.
     */
    fun keyGroups(groups: List<String>, securityGroups: List<String>): List<String> = (groups + securityGroups).distinct()
}

//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcptt

/**
 * The client rules of the MCPTT call types beyond the private, prearranged and ad hoc calls (TS 24.379 V19.7.0,
 * TS 24.380 V19.2.0): first-to-answer, chat and broadcast group calls, ad hoc participant criteria and modification,
 * and the floor priority. Pure functions, so that each rule has its unit test.
 */
object GroupCallRules {
    /** TS 24.379 annex F.1.2 `releaseReasonType`: the BYE to a first-to-answer invitee another one beat. */
    const val NOT_SELECTED = "not selected for call"
    const val PCK_FAILED = "authentication of the MIKEY-SAKE I_MESSAGE failed"

    /**
     * TS 24.379 §11.1.1.1: "For a first-to-answer call, the MCPTT client shall initiate the call to a list of two or more
     * MCPTT users" (step 10 a)), never the caller itself. The targets in order, without repeats.
     */
    fun firstToAnswerTargets(targets: List<String>, self: String?): List<String> {
        val list = targets.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (self != null && self in list) throw IllegalArgumentException("a first-to-answer call cannot invite the caller")
        if (list.size < 2) throw IllegalArgumentException("a first-to-answer call needs two or more MCPTT users")
        return list
    }

    /**
     * TS 24.379 §11.1.1.2.1.1 step 2: a first-to-answer call needs <allow-request-first-to-answer-call> "true" in the
     * user profile. A profile that could not be read leaves the decision to the server.
     */
    fun mayRequestFirstToAnswer(profileRead: Boolean, allowed: Boolean): Boolean = !profileRead || allowed

    /**
     * TS 24.379 §11.1.1.2.1.2, SIP BYE step 1 a): the server released a first-to-answer invitee because another one was
     * selected (or its PCK failed): the reason the user is told.
     */
    fun firstToAnswerRelease(firstToAnswer: Boolean, releaseReason: String?): String? =
        releaseReason?.takeIf { firstToAnswer && (it == NOT_SELECTED || it == PCK_FAILED) }

    /**
     * The "mc_priority" this client offers for a group call (TS 24.380 §14.3.3, the highest floor priority it asks to use;
     * §4.1.1.4 the priority hierarchy): the configured one (`mcptt.floor_priority`, or set by the user), else the user's
     * <user-priority> in the group document's member entry (TS 24.481 §7.2.2), else the <Priority> of the user profile's
     * <MCPTT-group-call> (TS 24.484 §8.3.2.1 item 8 e) v)). None of them: no "mc_priority", the normal priority.
     */
    fun floorPriorityOffer(configured: Int?, groupUserPriority: Int?, profilePriority: Int?): Int? =
        (configured ?: groupUserPriority ?: profilePriority)?.coerceIn(0, 255)

    /**
     * TS 24.380 §14.3.3 with §14.4: the floor priority negotiated is the answer's "mc_priority", which the server may
     * lower and must not raise; without an offered one there is none (an answer's parameter not offered is discarded).
     * The Floor Request's Floor Priority field (§6.2.4.3.5 1 a)) is "not higher than negotiated".
     */
    fun negotiatedFloorPriority(offered: Int?, answered: Int?): Int? =
        if (offered == null || answered == null) null else minOf(offered, answered)

    /**
     * TS 24.379 §17.2.2.1.1 steps 11, 12 and NOTE 6: an ad hoc group call names its participants or the criteria for
     * them, not both; the criteria a comma separated list without empty items.
     */
    fun adhocCriteria(criteria: String): String {
        val items = criteria.split(',').map { it.trim() }
        if (items.isEmpty() || items.any { it.isEmpty() }) throw IllegalArgumentException("empty participant criteria")
        return items.joinToString(",")
    }

    /**
     * TS 24.379 §17.2.6.1.1 step 2: the <entry> URIs of a modification of the participant list: each user to invite
     * with the "method" SIP URI parameter "INVITE", each one to remove with "BYE". NOTE: a request that changes the
     * list and the criteria at once is refused (here, as nothing to change is).
     */
    fun adhocModificationEntries(invite: List<String>, remove: List<String>, criteria: String?): List<String> {
        if ((invite.isNotEmpty() || remove.isNotEmpty()) == (criteria != null))
            throw IllegalArgumentException("modify either the participant list or the criteria")
        if (invite.any { it in remove }) throw IllegalArgumentException("a participant is both added and removed")
        return invite.distinct().map { "$it;method=INVITE" } + remove.distinct().map { "$it;method=BYE" }
    }

    /**
     * TS 24.379 §10.1.1.2.1.1 step 14 d) and §10.1.2.2.1.1 step 13 d): a call on a temporary group or a group regroup
     * names in <associated-group-id> a constituent group the client is a member of: the one it was using (selected)
     * when it is a constituent, else the first constituent it is affiliated to. Null for a group that is not a regroup.
     */
    fun callingConstituent(constituents: List<String>, selected: String?, affiliated: Collection<String>): String? {
        if (constituents.isEmpty()) return null
        return selected?.takeIf { it in constituents } ?: constituents.firstOrNull { it in affiliated } ?: constituents.first()
    }

    /**
     * TS 24.379 §10.1.1.2.1.1 and §10.1.2.2.1.1 (the first paragraph): a group whose document has
     * <preconfigured-group-use-only> "true" is not called: the user is told, nothing is sent. A regroup has no document of
     * its own (§16.1); a document not read leaves the decision to the server.
     */
    fun callsAllowed(preconfiguredUseOnly: Boolean?): Boolean = preconfiguredUseOnly != true
}

//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcdata

// The MCData emergency and imminent peril state machines of the MCData client (TS 24.282 V19.7.0 §6.2.8, annex G.4), as
// the Linux client keeps them (linux/src/client/mcdata/priority.cpp, client_rules.hpp McdataPriorityStates):
//   MDEG  (G.4.4)  the emergency state of the group:          1 no-emergency, 2 in-progress, 3 cancel-pending, 4 confirm-pending
//   MDEGC (G.4.5)  the emergency group communication state:  1 capable, 2 requested, 3 granted
//   MDEA  (G.4.6)  the emergency alert state:                1 no-alert, 2 confirm-pending, 3 initiated, 4 cancel-pending
//   MDIG  (G.4.7)  the imminent peril state of the group:     1 no-imminent-peril, 2 in-progress, 3 cancel-pending, 4 confirm-pending
//   MDIGC (G.4.8)  the imminent peril group communication:    1 capable, 2 requested, 3 granted
//   MDEPC (G.4.11) the emergency private communication:       1 capable, 2 requested, 3 granted
//   MDEPP (G.4.10) the emergency private priority state:      1 no-emergency, 2 in-progress, 3 cancel-pending
// The group states are those of the one group [McdataPriority.States.group] the last priority communication named (the
// Linux client's mcdata_priority_group_): a plain communication on that group (or with none named) carries the
// Resource-Priority of its in-progress state (TS 24.282 §9.2.4.2.3 8)e)-f), §9.2.5.3.1.1 3B), 3D), §10.2.5.2.3 9)e)-f)).

/** The MCData priority states (TS 24.282 annex G.4); a pure state holder, the procedures are in [McdataMediaService]. */
class McdataPriority {
    data class States(
        val mdeg: Int = 1, val mdegc: Int = 1, val mdea: Int = 1, val mdig: Int = 1, val mdigc: Int = 1,
        val mdepc: Int = 1, val mdepp: Int = 1,
        /** The group the priority states are of. */
        val group: String? = null,
        /** TS 24.282 §6.2.7.4: the user whose emergency alert is in progress on the group (another user). */
        val groupAlertOriginator: String? = null,
        /** TS 24.282 §6.2.8.3.8: the peer whose emergency alert came with a one-to-one emergency communication. */
        val privateAlertOriginator: String? = null,
    ) {
        fun names() = mapOf("mdeg" to mdegName(mdeg), "mdegc" to mdegcName(mdegc), "mdea" to mdeaName(mdea),
            "mdig" to mdigName(mdig), "mdigc" to mdigcName(mdigc), "mdepc" to mdepcName(mdepc), "mdepp" to mdeppName(mdepp))
    }

    @Volatile var states = States()
        private set

    @Synchronized fun restore(before: States) { states = before }
    @Synchronized private fun update(change: (States) -> States): States { states = change(states); return states }

    /**
     * The Resource-Priority level ("emergency", "imminent" or null) of a new request: its own priority (TS 24.282
     * §6.2.8.3.3, §6.2.8.1.2, §6.2.8.1.12), else on a group in the in-progress emergency (MDEG 2) or imminent peril
     * (MDIG 2) state that group's (§9.2.4.2.3 8)e)-f), §9.2.5.3.1.1 3B), 3D), §10.2.5.2.3 9)e)-f)).
     */
    fun level(group: Boolean, adhoc: Boolean, target: String, emergency: Boolean, imminent: Boolean): String? {
        if (emergency) return "emergency"
        if (imminent) return "imminent"
        val s = states
        if (!group || adhoc || (s.group != null && s.group != target)) return null
        return if (s.mdeg == 2) "emergency" else if (s.mdig == 2) "imminent" else null
    }

    /**
     * TS 24.282 §6.2.8.3.2 (one-to-one emergency: MDEPC 2, MDEPP 3 unless 2), §6.2.8.1.1 (emergency group: MDEGC 2, MDEG
     * 4 unless 2, with an alert MDEA 2) and §6.2.8.1.9 (imminent peril group: MDIGC 2, MDIG 4 unless 2): the states a
     * priority request moves before it is sent.
     */
    fun requested(group: Boolean, target: String, emergency: Boolean, imminent: Boolean, alert: Boolean) = update { s ->
        when {
            emergency && !group -> s.copy(mdepc = 2, mdepp = if (s.mdepp != 2) 3 else 2)
            emergency -> s.copy(group = target, mdegc = 2, mdeg = if (s.mdeg != 2) 4 else 2, mdea = if (alert) 2 else s.mdea)
            imminent -> s.copy(group = target, mdigc = 2, mdig = if (s.mdig != 2) 4 else 2)
            else -> s
        }
    }

    /**
     * TS 24.282 §6.2.8.1.4 (2xx) and §6.2.8.1.5 (4xx, 5xx, 6xx), with §6.2.8.3.4 / §6.2.8.3.5 for a one-to-one
     * communication: the response to a priority request grants it or returns the states it moved. A 2xx with warning 149
     * ("SIP INFO request pending") leaves an imminent peril request to the SIP INFO of §6.2.8.1.13 ([info]).
     */
    fun answered(status: Int, warning: String?) = update { s0 ->
        var s = s0
        if (status >= 300) {
            if (s.mdepc == 2 || s.mdepc == 3) s = s.copy(mdepc = 1, mdepp = if (s.mdepp == 3) 1 else s.mdepp)
            if (s.mdegc != 1) s = s.copy(mdegc = 1, mdeg = if (s.mdeg == 4) 1 else s.mdeg, mdea = if (s.mdea == 2) 1 else s.mdea)
            if (s.mdigc != 1) s = s.copy(mdigc = 1, mdig = if (s.mdig == 4) 1 else s.mdig)
            return@update s
        }
        val pendingInfo = warning?.let { Regex("\"149\\b").containsMatchIn(it) } == true
        if (s.mdepc == 2) s = s.copy(mdepc = 3, mdepp = 2)
        if (s.mdegc != 1) {
            s = s.copy(mdeg = 2, mdegc = 3, mdigc = 1, mdig = 1, mdea = if (s.mdea == 2 && !pendingInfo) 3 else s.mdea)
        } else if (s.mdigc != 1 && !pendingInfo) {
            s = s.copy(mdigc = 3, mdig = 2)
        }
        s
    }

    /**
     * TS 24.282 §6.2.8.1.13: the controlling function's SIP INFO in the dialog corrects the priority: 2) the alert of a
     * granted emergency communication; 3) an imminent peril request on a group in the in-progress emergency state carried
     * at the emergency level; 4) a pending alert cancellation settled.
     */
    fun info(info: McdataInfo) = update { s0 ->
        var s = s0
        if (s.mdegc == 3 && s.mdea == 2 && info.alert != null) s = s.copy(mdea = if (info.alert) 3 else 1)
        if ((s.mdigc == 2 || s.mdigc == 3) && info.imminentPeril == false && info.emergency == true)
            s = s.copy(mdig = 1, mdigc = 1, mdeg = 2)
        if (s.mdea == 4 && info.alert != null) s = s.copy(mdea = if (info.alert) 3 else 1)
        s
    }

    /**
     * TS 24.282 §9.2.4.2.4 steps 4A and 4B, §6.2.7.4 and §6.3.7.1.2: the in-progress states of the group a received
     * INVITE, re-INVITE or notification carries ([callingUser] another user's alert noted for a cancel, §6.2.7.4 1), 3)).
     */
    fun groupReceived(info: McdataInfo, self: String?) = update { s0 ->
        var s = s0
        if (info.emergency != null || info.imminentPeril != null) s = s.copy(group = info.callingGroupId ?: info.requestUri ?: s.group)
        s = if (info.emergency == true && info.alert == true && info.callingUserId != self) s.copy(groupAlertOriginator = info.callingUserId)
            else if (info.emergency == false || info.alert == false) s.copy(groupAlertOriginator = null) else s
        if (info.emergency == true) s = s.copy(mdeg = 2, mdig = 1, mdigc = 1)
        else if (info.emergency == false) s = s.copy(mdeg = 1, mdegc = 1)
        if (info.imminentPeril == true) s = s.copy(mdig = 2)
        else if (info.imminentPeril == false && info.emergency != true) s = s.copy(mdig = 1, mdigc = 1)
        s
    }

    /**
     * TS 24.282 §6.2.8.4.2: the other party upgraded (MDEPP 2, its alert's originator kept for a cancel, §6.2.8.3.8) or
     * cancelled (MDEPP 1, MDEPC 1) the one-to-one communication; and §9.2.4.2.4 for an emergency invitation (MDEPP 2).
     */
    fun privateReceived(emergency: Boolean, alert: Boolean?, caller: String?) = update { s ->
        s.copy(mdepp = if (emergency) 2 else 1, mdepc = if (emergency && s.mdepc == 3) 3 else 1,
            privateAlertOriginator = if (emergency && alert == true) caller else if (!emergency) null else s.privateAlertOriginator)
    }

    /**
     * TS 24.282 §9.2.5.3.1.1 0)a) -> §6.2.8.1.16 (group) and §9.2.5.2.1.1 3)a) (one-to-one): the grant of the priority
     * communication the REFER asked for, by the participating function's re-INVITE "establish-success":
     *   1) MDEGC 2: a) no <emergency-ind> or "true", and no <imminentperil-ind>: MDEG 2, MDEGC 3; b) MDEA 2: <alert-ind>
     *      "true" or absent MDEA 3, "false" MDEA 1;
     *   2) MDIGC 2: a) <imminentperil-ind> "true" or absent: MDIGC 3, MDIG 2; b) "false" with <emergency-ind> "true":
     *      MDEG 2, and the denied request's MDIGC 1, MDIG 4 -> 1 (as §6.2.8.1.13 3));
     *   one-to-one: MDEPC 2 or 3: MDEPP 2, MDEPC 3.
     */
    fun preGranted(group: Boolean, emergency: Boolean?, imminent: Boolean?, alert: Boolean?) = update { s0 ->
        var s = s0
        if (!group) return@update if (s.mdepc == 2 || s.mdepc == 3) s.copy(mdepp = 2, mdepc = 3) else s
        if (s.mdegc == 2) {
            if (emergency != false && imminent == null) s = s.copy(mdeg = 2, mdegc = 3)
            if (s.mdea == 2) s = s.copy(mdea = if (alert == false) 1 else 3)
        }
        if (s.mdigc == 2) {
            if (imminent != false) s = s.copy(mdigc = 3, mdig = 2)
            else if (emergency == true) s = s.copy(mdeg = 2, mdigc = 1, mdig = if (s.mdig == 4) 1 else s.mdig)
        }
        s
    }

    /**
     * The release of a communication, granted or still being established: TS 24.282 §6.2.8.1.17 (group: MDEGC 2 -> 1,
     * MDEG 4 -> 1 (the clause's "MDEG 3: confirm-pending" read as MDEG 4), MDEA 2 -> 1; MDIGC 2 -> 1, MDIG 4 -> 1) and
     * §6.2.8.1.18 (one-to-one: MDEPC 2 -> 1, MDEPP 3 -> 1). As the Linux client, a granted communication's states return
     * to "capable" too (the next communication is a new request) and MDEPP, the state of the private communication, to
     * 1; the in-progress states of the group (MDEG 2, MDIG 2) last until cancelled.
     */
    fun released() = update { s0 ->
        var s = s0
        if (s.mdegc == 2) s = s.copy(mdeg = if (s.mdeg == 4) 1 else s.mdeg, mdea = if (s.mdea == 2) 1 else s.mdea)
        if (s.mdigc == 2 && s.mdig == 4) s = s.copy(mdig = 1)
        s.copy(mdegc = 1, mdigc = 1, mdepc = 1, mdepp = 1, mdeg = if (s.mdeg == 4) 1 else s.mdeg, mdig = if (s.mdig == 4) 1 else s.mdig)
    }

    /**
     * The user changes the priority of an open group communication (TS 24.282 §6.2.8.1.1 upgrade, §6.2.8.1.3 cancel,
     * §6.2.7.2 3) -> §6.2.8.1.14 another user's emergency cancelled, §6.2.8.1.9 / §6.2.8.1.11 imminent peril): checks
     * the state, moves it, and returns the <alert-ind> and <originated-by> of the request.
     */
    fun groupChange(emergency: Boolean, enable: Boolean, cancelAlert: Boolean): Pair<Boolean?, String?> {
        val s = states
        if (emergency && enable && s.mdeg == 2) throw SdsError("the group is already in an emergency state")
        if (emergency && !enable && s.mdeg != 2) throw SdsError("the group is not in an emergency state")
        if (!emergency && enable && s.mdig == 2) throw SdsError("the group is already in an imminent peril state")
        if (!emergency && !enable && s.mdig != 2) throw SdsError("the group is not in an imminent peril state")
        var alert: Boolean? = null; var by: String? = null
        update {
            if (emergency) {
                alert = false
                when {
                    enable -> it.copy(mdegc = 2, mdeg = if (it.mdeg != 2) 4 else 2)
                    it.mdegc != 3 && it.groupAlertOriginator != null -> {
                        alert = if (cancelAlert) false else null
                        by = if (cancelAlert) it.groupAlertOriginator else null
                        it.copy(mdeg = 3)
                    }
                    else -> it.copy(mdeg = 3)
                }
            } else if (enable) it.copy(mdigc = 2, mdig = if (it.mdig != 2) 4 else 2) else it.copy(mdig = 3)
        }
        return alert to by
    }

    /**
     * The answer to [groupChange]: an upgrade as [answered]; a cancel granted MDEG 1 / MDIG 1 (and their communication
     * states 1), refused (§6.2.7.2, §6.2.7.3 4xx) the in-progress state again unless the answer says otherwise.
     */
    fun groupChangeAnswered(emergency: Boolean, enable: Boolean, status: Int, warning: String?, body: McdataInfo?) {
        if (enable) { answered(status, warning); return }
        update { s ->
            if (status >= 300) {
                if (emergency) s.copy(mdeg = 2, mdea = if (s.mdea == 4) 3 else s.mdea)
                else if (body == null || body.imminentPeril == true) s.copy(mdig = 2) else s
            } else if (emergency) s.copy(mdeg = 1, mdegc = 1, groupAlertOriginator = null) else s.copy(mdig = 1, mdigc = 1)
        }
    }

    /**
     * The user upgrades (§6.2.8.4.4 -> §6.2.8.3.2: MDEPC 2) or cancels (§6.2.8.4.3 -> §6.2.8.3.6: MDEPP 3) the emergency
     * of a one-to-one communication; the answer: granted MDEPC 3 / MDEPP 2, or cancelled MDEPC 1 / MDEPP 1 (§6.2.8.3.4
     * 1)-2), §6.2.8.4.3); refused (§6.2.8.3.5) the communication's own state again.
     */
    fun privateChange(enable: Boolean) = update { if (enable) it.copy(mdepc = 2) else it.copy(mdepp = 3) }

    fun privateChangeAnswered(enable: Boolean, success: Boolean, wasEmergency: Boolean) = update { s ->
        if (!success) s.copy(mdepc = if (wasEmergency) 3 else 1, mdepp = if (s.mdepp == 3) (if (wasEmergency) 2 else 1) else s.mdepp)
        else s.copy(mdepc = if (enable) 3 else 1, mdepp = if (enable) 2 else 1, privateAlertOriginator = if (enable) s.privateAlertOriginator else null)
    }

    companion object {
        fun mdegName(n: Int) = when (n) { 2 -> "MDEG 2: in-progress"; 3 -> "MDEG 3: cancel-pending"; 4 -> "MDEG 4: confirm-pending"; else -> "MDEG 1: no-emergency" }
        fun mdegcName(n: Int) = when (n) { 2 -> "MDEGC 2: emergency-communication-requested"; 3 -> "MDEGC 3: emergency-communication-granted"; else -> "MDEGC 1: emergency-gc-capable" }
        fun mdeaName(n: Int) = when (n) { 2 -> "MDEA 2: emergency-alert-confirm-pending"; 3 -> "MDEA 3: emergency-alert-initiated"; 4 -> "MDEA 4: emergency-alert-cancel-pending"; else -> "MDEA 1: no-alert" }
        fun mdigName(n: Int) = when (n) { 2 -> "MDIG 2: in-progress"; 3 -> "MDIG 3: cancel-pending"; 4 -> "MDIG 4: confirm-pending"; else -> "MDIG 1: no-imminent-peril" }
        fun mdigcName(n: Int) = when (n) { 2 -> "MDIGC 2: imminent-peril-communication-requested"; 3 -> "MDIGC 3: imminent-peril-communication-granted"; else -> "MDIGC 1: imminent-peril-gc-capable" }
        fun mdepcName(n: Int) = when (n) { 2 -> "MDEPC 2: emergency-pc-requested"; 3 -> "MDEPC 3: emergency-pc-granted"; else -> "MDEPC 1: emergency-pc-capable" }
        fun mdeppName(n: Int) = when (n) { 2 -> "MDEPP 2: in-progress"; 3 -> "MDEPP 3: cancel-pending"; else -> "MDEPP 1: no-emergency" }
    }
}

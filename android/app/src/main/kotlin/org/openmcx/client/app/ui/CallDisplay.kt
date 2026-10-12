//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.app.ui

/**
 * How a call names its parties when functional aliases are involved (TS 24.379 §9A, TS 24.281 §20): the role comes
 * first, the user behind it second, so that a call to a role the user holds and a caller presenting a role are seen at
 * a glance, on the call screens as in the system's call notification.
 */
object CallDisplay {
    /** The title and the line under it on a call screen. */
    data class Shown(val title: String, val subtitle: String?)

    /**
     * A functional alias as a role: the user part of its URI without the lab's `.alias` or `.fa` suffix, words split at
     * `-`, `_` and `.`, the first letter capital ("sip:duty-officer.alias@mcx.test" is "Duty officer").
     */
    fun role(alias: String): String = alias.short().removeSuffix(".alias").removeSuffix(".fa")
        .split('-', '_', '.').filter { it.isNotEmpty() }.joinToString(" ")
        .replaceFirstChar { it.titlecase() }.ifEmpty { alias }

    /**
     * A one-to-one call: an incoming call to a role this user holds is "Call to <role>" from the caller; a caller
     * presenting a role is "<role> (<caller>)"; a call this user made to a role is that role, reaching the user the
     * server selected; this user's own presented role is "as <role>".
     */
    fun privateCall(outgoing: Boolean, peer: String?, callingAlias: String?, calledAlias: String?): Shown {
        val name = peer?.short().orEmpty()
        val caller = callingAlias?.let { "${role(it)} ($name)" } ?: name
        if (outgoing) {
            val presented = callingAlias?.let { "as ${role(it)}" }
            if (calledAlias == null) return Shown(name, presented)
            val reached = name.takeIf { it.isNotEmpty() && it != calledAlias.short() }?.let { "reached $it" }
            return Shown(role(calledAlias), listOfNotNull(reached, presented).joinToString(" · ").ifEmpty { null })
        }
        if (calledAlias != null) return Shown("Call to ${role(calledAlias)}", "from $caller")
        return Shown(caller, null)
    }

    /** A group call: the group as title, and the role this user presents or the caller presented under it. */
    fun groupCall(title: String, outgoing: Boolean, callingAlias: String?): Shown =
        Shown(title, callingAlias?.let { if (outgoing) "as ${role(it)}" else "from ${role(it)}" })

    /** The name of the other party in the system's call notification and telecom: the role first when there is one. */
    fun caller(peer: String?, callingAlias: String?, calledAlias: String?, fallback: String): String {
        if (peer == null) return fallback
        val shown = privateCall(false, peer, callingAlias, calledAlias)
        return if (shown.subtitle != null) "${shown.title} ${shown.subtitle}" else shown.title
    }
}

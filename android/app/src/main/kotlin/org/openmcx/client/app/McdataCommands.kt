//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.app

import org.json.JSONArray
import org.json.JSONObject
import org.openmcx.client.mcdata.IpconService
import org.openmcx.client.mcdata.IpconSession

/**
 * The debug control commands of the MCData procedures of [McdataExtras] (parity phase 4b), one per procedure, as the
 * reference client's: [DebugControl] hands them every command named in [NAMES].
 */
internal object McdataCommands {
    val NAMES = setOf("ipcon", "ipcon-ping", "ipcon-status")

    suspend fun execute(cmd: String, args: List<String>): Any = when (cmd) {
        // TS 24.282 §20.2.1: ipcon <MCData ID> | ipcon fa=<functional alias> | ipcon close
        "ipcon" -> when {
            args.firstOrNull() == "close" -> { ipcon().close(); ipconJson(ipcon().session.value) }
            args.firstOrNull()?.startsWith("fa=") == true -> ipconJson(ipcon().open(args[0].removePrefix("fa="), toAlias = true))
            args.size == 1 -> ipconJson(ipcon().open(args[0]))
            else -> throw IllegalArgumentException("usage: ipcon <MCData ID> | ipcon fa=<alias> | ipcon close")
        }
        // TS 24.582 §13.1: ipcon-ping <IPv4 address> [count] [size]: ICMP echo requests through the tunnel
        "ipcon-ping" -> JSONObject().put("identifier", ipcon().ping(args[0], args.getOrNull(1)?.toInt() ?: 3, args.getOrNull(2)?.toInt() ?: 56))
        "ipcon-status" -> ipconJson(McdataExtras.ipcon?.session?.value).put("address", McdataExtras.ipcon?.address)
        else -> throw IllegalArgumentException("unknown command $cmd")
    }

    private fun ipcon(): IpconService = McdataExtras.ipcon ?: throw IllegalStateException("MCData not provisioned")

    fun ipconJson(s: IpconSession?): JSONObject {
        s ?: return JSONObject().put("state", "none")
        return JSONObject().put("state", s.state).put("originating", s.originating).put("target", s.target).put("from", s.from)
            .put("status", s.status).put("warning", s.warning).put("error", s.error)
            .put("peer", s.peer?.let { "${it.address}:${it.port}" }).put("local", s.local?.let { "${it.address}:${it.port}" })
            .put("functional_alias", s.functionalAlias).put("called_alias", s.calledAlias).put("selected", s.selected)
            .put("sent", s.sent.get()).put("received", s.received.get()).put("dropped", s.dropped.get())
            .put("echoes_answered", s.echoesAnswered.get())
            .put("pings", JSONArray(s.pings.map { JSONObject(it) }))
    }
}

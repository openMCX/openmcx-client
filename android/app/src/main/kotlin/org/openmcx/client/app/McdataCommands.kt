//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.app

import org.json.JSONArray
import org.json.JSONObject
import org.openmcx.client.mcdata.DispositionRequest
import org.openmcx.client.mcdata.IpconService
import org.openmcx.client.mcdata.IpconSession

/**
 * The debug control commands of the MCData procedures of [McdataExtras] (parity phase 4b), one per procedure, as the
 * reference client's: [DebugControl] hands them every command named in [NAMES].
 */
internal object McdataCommands {
    val NAMES = setOf("ipcon", "ipcon-ping", "ipcon-status", "sds-status", "sds-status-values", "sds-status-received")

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
        // TS 24.282 §14.2.1.1: sds-status [delivery|read|both] <group> <operational value id> [force]
        "sds-status" -> {
            val d = disposition(args.firstOrNull())
            val rest = if (d != null) args.drop(1) else args
            require(rest.size >= 2) { "usage: sds-status [delivery|read|both] <group> <id> [force]" }
            val sent = enhanced().send(rest[0], rest[1], force = rest.getOrNull(2) == "force", disposition = d)
            Engine.sent(sent)
            JSONObject().put("message_id", sent.messageId.toString()).put("status", sent.status)
        }
        // sds-status-values <group>: the group's <mcdata-allow-enhanced-status> and operational values (TS 24.481 §7.2.4.2)
        "sds-status-values" -> enhanced().values(args.single(), refresh = true).let { v ->
            JSONObject().put("allowed", v.allowed).put("values", JSONArray(v.values.map { JSONObject().put("id", it.first).put("value", it.second) }))
        }
        // §14.2.1.2: the enhanced statuses rendered, and how many messages were discarded
        "sds-status-received" -> JSONObject().put("discarded", enhanced().discarded).put("received", JSONArray(enhanced().received.value.map {
            JSONObject().put("message_id", it.messageId.toString()).put("from", it.from).put("group", it.group).put("id", it.id)
                .put("value", it.value).put("at", it.at) }))
        else -> throw IllegalArgumentException("unknown command $cmd")
    }

    private fun enhanced() = McdataExtras.enhancedStatus ?: throw IllegalStateException("MCData not provisioned")

    private fun disposition(word: String?) = when (word) {
        "delivery" -> DispositionRequest.DELIVERY; "read" -> DispositionRequest.READ
        "both", "delivery-read" -> DispositionRequest.DELIVERY_AND_READ; else -> null
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

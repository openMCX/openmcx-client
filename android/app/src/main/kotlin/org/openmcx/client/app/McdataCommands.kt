//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.app

import org.json.JSONArray
import org.json.JSONObject
import org.openmcx.client.mcdata.DispositionRequest
import org.openmcx.client.mcdata.IpconService
import org.openmcx.client.mcdata.IpconSession
import org.openmcx.client.mcdata.sendAdhoc

/**
 * The debug control commands of the MCData procedures of [McdataExtras] (parity phase 4b), one per procedure, as the
 * reference client's: [DebugControl] hands them every command named in [NAMES].
 */
internal object McdataCommands {
    val NAMES = setOf("ipcon", "ipcon-ping", "ipcon-status", "sds-status", "sds-status-values", "sds-status-received",
        "msgstore-ctrl", "msgstore", "sds-adhoc")

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
        // TS 24.282 §21.3.2.2: msgstore-ctrl enable|disable [all-private] [all-group] [private=<ID>,...] [group=<ID>,...]
        "msgstore-ctrl" -> {
            require(args.firstOrNull() in setOf("enable", "disable")) { "usage: msgstore-ctrl enable|disable [all-private] [all-group] [private=..] [group=..]" }
            fun list(prefix: String) = args.filter { it.startsWith(prefix) }.flatMap { it.removePrefix(prefix).split(',') }.filter { it.isNotBlank() }
            val r = McdataExtras.controlStorage(args[0] == "enable", "all-private" in args, "all-group" in args, list("private="), list("group="))
            JSONObject().put("action", r.action).put("status", r.status).put("warning", r.warning)
        }
        // TS 24.282 §21.2: msgstore <operation> ..., one message store or notification client procedure per operation
        "msgstore" -> msgstore(args)
        // TS 24.282 §9.2.2.2.1 step 3A: sds-adhoc <ID,ID,...|criteria=<criteria>|group=<ad hoc group>> <text…> [--disposition d]
        // (the media plane takes the same target as adhoc=<...> in sds-media, sds-session and fd-media; fa=<alias> there
        // names a functional alias, TS 24.282 §22)
        "sds-adhoc" -> {
            val words = args.toMutableList()
            val flag = words.indexOf("--disposition")
            val d = if (flag >= 0) disposition(words.getOrNull(flag + 1)).also { words.subList(flag, minOf(flag + 2, words.size)).clear() } else null
            require(words.size >= 2) { "usage: sds-adhoc <ID,ID,...|criteria=..|group=..> <text>" }
            val sds = Engine.mcdata ?: throw IllegalStateException("MCData not provisioned")
            val sent = sds.sendAdhoc(org.openmcx.client.mcdata.AdhocTarget.parse(words[0]), words.drop(1).joinToString(" "), d)
            Engine.sent(sent)
            JSONObject().put("message_id", sent.messageId.toString()).put("status", sent.status).put("group", sent.target).put("plane", sent.plane)
        }
        else -> throw IllegalArgumentException("unknown command $cmd")
    }

    private fun store() = McdataExtras.store ?: throw IllegalStateException("no MCData message store is configured")

    private suspend fun msgstore(args: List<String>): Any {
        val s = store()
        val rest = args.drop(1)
        fun need(n: Int) = require(rest.size >= n) { "msgstore ${args.firstOrNull()}: missing arguments" }
        return when (args.firstOrNull()) {
            "sync" -> JSONObject().put("objects", s.sync())                                   // §21.2.17.1
            "get" -> { need(1); storedJson(s.get(rest[0])) }                                  // §21.2.1.1
            "payload" -> { need(1); JSONObject(s.payload(rest[0], rest.getOrNull(1))) }       // §21.2.1.1
            "search" -> JSONArray(s.search(rest))                                             // §21.2.2.1
            "flag", "unflag" -> { need(2); JSONObject().put("status", s.flag(rest[0], rest[1], args[0] == "flag")) } // §21.2.3.1
            "delete" -> { need(1); JSONObject().put("status", s.delete(rest)) }               // §21.2.4.1
            "folders" -> JSONObject(s.folders())                                              // §21.2.23.1
            "folder-create" -> { need(1); JSONObject().put("status", s.createFolder(rest[0], rest.getOrNull(1))) } // §21.2.8.1
            "folder-delete" -> { need(1); JSONObject().put("status", s.deleteFolder(rest[0])) }                   // §21.2.7.1
            "move", "copy" -> { need(2); JSONObject().put("status", s.moveOrCopy(args[0] == "move", rest[0], rest.drop(1))) } // §21.2.6.1, §21.2.10.1
            "subscribe" -> { s.subscribe(); storeJson(s) }                                    // §21.2.19.1, §21.2.12A.1, §21.2.22.1
            "unsubscribe" -> { s.unsubscribe(); storeJson(s) }                                // §21.2.13A.1, §21.2.20.1
            "subscription-extend" -> { need(1); JSONObject().put("status", s.extendSubscription(rest[0].toLong())) } // §21.2.14A.1
            "channel-lifetime" -> { need(1); JSONObject().put("status", s.channelLifetime(rest[0].toLong())) }       // §21.2.21.1
            null, "status" -> storeJson(s)
            else -> throw IllegalArgumentException("msgstore sync|get|payload|search|flag|unflag|delete|folders|folder-create|" +
                "folder-delete|move|copy|subscribe|unsubscribe|subscription-extend|channel-lifetime|status")
        }
    }

    private fun storedJson(o: org.openmcx.client.mcdata.StoredObject?): Any = o?.let {
        JSONObject().put("id", it.id).put("folder", it.folder).put("flags", JSONArray(it.flags)).put("mod_seq", it.modSeq)
            .put("text", it.text).put("from", it.from).put("direction", it.direction).put("group", it.group).put("date", it.date)
            .put("parts", it.parts).put("notified", it.notified).put("attributes", JSONObject(it.attributes))
    } ?: JSONObject.NULL

    private fun storeJson(s: org.openmcx.client.mcdata.MessageStoreClient): JSONObject = JSONObject()
        .put("box", s.boxUrl).put("subscribed", s.subscribed).put("channel", s.channel).put("subscription", s.subscription)
        .put("events", s.events).put("cursor", s.creationCursor).put("mod_seq", s.modSeq)
        .put("last", s.last?.let { JSONObject().put("method", it.first).put("url", it.second).put("status", it.third) })
        .put("folders", JSONObject(s.folders))
        .put("objects", JSONArray(s.objects.value.values.map(::storedJson)))
        .put("control", McdataExtras.storageControl.value?.let { JSONObject().put("action", it.action).put("status", it.status).put("warning", it.warning) })

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

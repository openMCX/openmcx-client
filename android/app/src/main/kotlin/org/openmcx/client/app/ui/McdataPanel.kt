//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.openmcx.client.app.Engine
import org.openmcx.client.app.McdataExtras
import org.openmcx.client.mcdata.EnhancedStatusValues

/**
 * The Messages screen's row for the MCData procedures of parity phase 4b: the message store (TS 24.282 §21: stored
 * messages retrieved, marked read, deleted; storage switched on or off, §21.3.2.2; the notification channel, §21.2.19),
 * enhanced status (§14.2: the latest one received; a group's operational values to send), and IP connectivity (§20:
 * the open session and its end). Hidden while MCData is not running.
 */
@Composable
internal fun McdataPanel(target: String, isGroup: Boolean, enabled: Boolean) {
    val store = McdataExtras.store
    val status = McdataExtras.enhancedStatus
    val ipcon = McdataExtras.ipcon
    if (store == null && status == null && ipcon == null) return
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<String?>(null) }
    var outcome by remember { mutableStateOf<String?>(null) }
    fun run(what: String, block: suspend () -> Any?) = scope.launch {
        outcome = runCatching { block() }.fold({ "$what: done" }, { "$what: ${it.message}" })
    }
    val latest = (status?.received ?: MutableStateFlow(emptyList())).collectAsState().value.lastOrNull()
    val session = (ipcon?.session ?: MutableStateFlow(null)).collectAsState().value
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        if (latest != null) Text("Status of ${latest.from.short()}${latest.group?.let { " on ${it.short()}" } ?: ""}: ${latest.value}",
            style = MaterialTheme.typography.labelMedium, modifier = Modifier.background(Mcx.Strip, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp).testTag(TAG_STATUS))
        if (session != null && session.state == "open") Row(Modifier.fillMaxWidth().padding(top = 4.dp)
            .background(Mcx.Strip, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp).testTag(TAG_IPCON), verticalAlignment = Alignment.CenterVertically) {
            Text("IP link with ${(session.target ?: session.from ?: "").short()} · ${session.sent.get()} sent, ${session.received.get()} received",
                style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { dialog = "ping" }) { Text("Ping") }
            TextButton(onClick = { run("IP link") { ipcon!!.close() } }) { Text("End") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            if (store != null) TextButton(onClick = { dialog = "store"; run("Stored messages") { store.sync() } }, enabled = enabled,
                modifier = Modifier.testTag(TAG_STORE)) { Text("Stored") }
            if (status != null) TextButton(onClick = { dialog = "status" }, enabled = enabled && isGroup,
                modifier = Modifier.testTag(TAG_SEND_STATUS)) { Text("Status") }
            if (Engine.mcdata != null) TextButton(onClick = { dialog = "adhoc" }, enabled = enabled, modifier = Modifier.testTag(TAG_ADHOC)) { Text("Ad hoc") }
            if (ipcon?.address != null) TextButton(onClick = { run("IP link") { ipcon.open(target) } },
                enabled = enabled && !isGroup && target.isNotBlank() && session?.state != "open", modifier = Modifier.testTag(TAG_IPCON_OPEN)) { Text("IP link") }
        }
        outcome?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Mcx.Muted) }
    }
    when (dialog) {
        "store" -> if (store != null) StoreDialog(store, ::run) { dialog = null }
        "status" -> if (status != null) StatusDialog(target, ::run) { dialog = null }
        "adhoc" -> AdhocDialog(::run) { dialog = null }
        "ping" -> if (ipcon != null) PingDialog(session, { address -> run("Ping $address") { ipcon.ping(address) } }) { dialog = null }
    }
}

@Composable
private fun StoreDialog(store: org.openmcx.client.mcdata.MessageStoreClient, run: (String, suspend () -> Any?) -> Unit, close: () -> Unit) {
    val objects = store.objects.collectAsState().value.values.sortedByDescending { it.date ?: "" }
    val control = McdataExtras.storageControl.collectAsState().value
    AlertDialog(onDismissRequest = close, title = { Text("Message store") }, text = {
        Column {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = { run("Synchronise") { store.sync() } }) { Text("Sync") }
                TextButton(onClick = { run("Notifications") { if (store.subscribed) store.unsubscribe() else store.subscribe() } }) {
                    Text(if (store.subscribed) "Stop updates" else "Live updates")
                }
                TextButton(onClick = { run("Storage on") { McdataExtras.controlStorage(true, allPrivate = true, allGroup = true) } }) { Text("Store all") }
                TextButton(onClick = { run("Storage off") { McdataExtras.controlStorage(false, allPrivate = true, allGroup = true) } }) { Text("Store none") }
            }
            control?.let { Text("Storage ${it.action}: ${it.status}${it.warning?.let { w -> " ($w)" } ?: ""}", style = MaterialTheme.typography.labelSmall) }
            if (objects.isEmpty()) Text("Nothing stored", color = Mcx.Muted)
            LazyColumn(Modifier.heightIn(max = 320.dp).testTag(TAG_STORED)) {
                items(objects, key = { it.id }) { o ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(o.text.ifBlank { "(no text)" }, style = MaterialTheme.typography.bodyMedium)
                            Text("${if (o.direction == "Out") "to" else "from"} ${(if (o.direction == "Out") o.attributes["To"] else o.from)?.short() ?: "?"}" +
                                (o.group?.let { " on ${it.short()}" } ?: "") + (if (o.seen) "" else " · new"),
                                style = MaterialTheme.typography.labelSmall, color = Mcx.Muted)
                        }
                        if (!o.seen) TextButton(onClick = { run("Read") { store.flag(o.id, "\\Seen") } }) { Text("Read") }
                        TextButton(onClick = { run("Delete") { store.delete(listOf(o.id)) } }) { Text("Delete") }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = close) { Text("Close") } })
}

@Composable
private fun StatusDialog(group: String, run: (String, suspend () -> Any?) -> Unit, close: () -> Unit) {
    val service = McdataExtras.enhancedStatus ?: return
    var values by remember { mutableStateOf<EnhancedStatusValues?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    androidx.compose.runtime.LaunchedEffect(group) {
        scope.launch { runCatching { service.values(group, refresh = true) }.onSuccess { values = it }.onFailure { error = it.message } }
    }
    AlertDialog(onDismissRequest = close, title = { Text("Status to ${group.short()}") }, text = {
        Column {
            error?.let { Text(it, color = Mcx.Muted) }
            val v = values
            if (v != null && !v.allowed) Text("This group does not take an enhanced status", color = Mcx.Muted)
            if (v != null && v.allowed) for ((id, text) in v.values)
                TextButton(onClick = { run("Status $text") { service.send(group, id) }; close() }, modifier = Modifier.testTag("status-$id")) { Text(text) }
        }
    }, confirmButton = { TextButton(onClick = close) { Text("Close") } })
}

/**
 * TS 24.282 §9.2.2.2.1 step 3A and §24.2.2.1.1: an ad hoc group of the MCData IDs or functional aliases typed (or
 * participant criteria): a message (over the media plane when it is long), or an SDS session.
 */
@Composable
private fun AdhocDialog(run: (String, suspend () -> Any?) -> Unit, close: () -> Unit) {
    var who by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var criteria by remember { mutableStateOf(false) }
    val spec = if (criteria) "criteria=${who.trim()}" else who.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        .joinToString(",") { if (it.startsWith("sip:")) it else "sip:$it" }
    AlertDialog(onDismissRequest = close, title = { Text("Ad hoc group") }, text = {
        Column {
            androidx.compose.material3.OutlinedTextField(who, { who = it }, label = { Text(if (criteria) "Participant criteria" else "MCData IDs or aliases") },
                modifier = Modifier.testTag(TAG_ADHOC_WHO))
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(criteria, { criteria = it }); Text("By criteria", style = MaterialTheme.typography.labelMedium)
            }
            androidx.compose.material3.OutlinedTextField(text, { text = it }, label = { Text("Message") }, modifier = Modifier.testTag(TAG_ADHOC_TEXT))
        }
    }, confirmButton = {
        Row {
            TextButton(onClick = { run("Ad hoc session") { McdataExtras.adhocSession(spec) }; close() }, enabled = who.isNotBlank()) { Text("Session") }
            TextButton(onClick = { run("Ad hoc message") { McdataExtras.adhocSend(spec, text) }; close() },
                enabled = who.isNotBlank() && text.isNotBlank(), modifier = Modifier.testTag(TAG_ADHOC_SEND)) { Text("Send") }
        }
    }, dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
}

/** TS 24.582 §13.1: the IP application's echo requests to an address in the tunnel, and the replies so far. */
@Composable
private fun PingDialog(session: org.openmcx.client.mcdata.IpconSession?, ping: (String) -> Unit, close: () -> Unit) {
    var address by remember { mutableStateOf(session?.pings?.lastOrNull()?.get("from") as? String ?: "") }
    AlertDialog(onDismissRequest = close, title = { Text("Ping through the IP link") }, text = {
        Column {
            androidx.compose.material3.OutlinedTextField(address, { address = it }, label = { Text("IPv4 address") }, singleLine = true)
            session?.pings?.takeLast(5)?.forEach { p ->
                Text("${p["from"]} seq ${p["sequence"]}: ${p["rtt_ms"]} ms", style = MaterialTheme.typography.labelSmall)
            }
        }
    }, confirmButton = { TextButton(onClick = { ping(address.trim()) }, enabled = address.isNotBlank()) { Text("Ping") } },
        dismissButton = { TextButton(onClick = close) { Text("Close") } })
}

internal const val TAG_ADHOC = "adhoc-data"
internal const val TAG_ADHOC_WHO = "adhoc-data-who"
internal const val TAG_ADHOC_TEXT = "adhoc-data-text"
internal const val TAG_ADHOC_SEND = "adhoc-data-send"
internal const val TAG_STATUS = "enhanced-status"
internal const val TAG_SEND_STATUS = "send-status"
internal const val TAG_IPCON = "ipcon"
internal const val TAG_IPCON_OPEN = "ipcon-open"
internal const val TAG_STORE = "message-store"
internal const val TAG_STORED = "stored-messages"

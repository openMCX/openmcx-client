//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.app.ui

import org.openmcx.client.body.McService

import kotlinx.coroutines.launch
import org.openmcx.client.app.Engine
import org.openmcx.client.app.alert.AlertCenter
import org.openmcx.client.app.attachments.AttachmentFiles
import org.openmcx.client.app.attachments.AttachmentKind
import org.openmcx.client.app.data.HistoryEntry
import org.openmcx.client.mcdata.DispositionRequest
import org.openmcx.client.mcdata.FdPolicy
import org.openmcx.client.mcdata.FdRecord
import org.openmcx.client.mcdata.FdState
import org.openmcx.client.mcptt.Call
import org.openmcx.client.mcptt.CallType
import org.openmcx.client.mcptt.Commencement
import org.openmcx.client.mcptt.FloorState
import org.openmcx.client.mcvideo.TxState
import java.util.UUID

data class GroupItem(val id: String, val affiliated: Boolean, val selected: Boolean)

data class CallItem(
    val id: Int,
    val title: String,
    val type: CallType,
    val state: String,
    /**
     * idle, requesting, queued (our request waits in the floor queue), offered (a queued grant awaits our acceptance),
     * talking (us), taken (someone else), releasing.
     */
    val floor: String,
    val talker: String?,
    val lastFloorEvent: String?,
    /** The talk time the last floor grant allows (TS 24.380 Floor Granted duration), if given. */
    val talkLimitSeconds: Int? = null,
    val startedAt: Long = 0,
    /** Who is in the call, as far as the client knows: the group's members, or both parties. */
    val members: List<String> = emptyList(),
    /** False for a private call without floor control (TS 24.379 §11.1.2): both talk at once, Mute instead of PTT. */
    val floorControl: Boolean = true,
    val muted: Boolean = false,
    /** `normal`, `emergency` or `imminent` (peril), as the server last said (TS 24.379 §6.2.8). */
    val priority: String = "normal",
    /** Our place in the floor request queue while queued (TS 24.380 §8.2.3.5); 255: queued, position not told. */
    val queuePosition: Int? = null,
    /** The functional alias of the call (TS 24.379 §9A): "as <alias>" for a caller's alias, "to <alias>" for a called one. */
    val alias: String? = null,
)

/**
 * One of this user's functional aliases (TS 24.379 §9A.2.1): `active`, `take-over-possible` (held by someone else,
 * this user may take it over) or `inactive`; [inUse] when it is presented on this user's outgoing calls.
 */
/** A functional alias of this user: its status, whether its calls present it, and the outcome of the last change of it. */
data class AliasItem(val id: String, val status: String, val inUse: Boolean, val service: String = "mcptt", val outcome: String? = null)

/** Another user's location, as their report gave it. */
data class LocatedItem(val name: String, val latitude: Double?, val longitude: Double?, val cell: String?, val at: Long)

data class MessageItem(
    val id: String,
    val incoming: Boolean,
    val peer: String,
    val group: String?,
    val text: String,
    val status: String,
    val at: Long,
    val displayed: Boolean,
    /** Set when this row is a file (image, video or document) rather than text. */
    val file: FileItem? = null,
)

/** An attachment in the message list. */
data class FileItem(
    val name: String,
    val type: String,
    val size: Long,
    /** offered, deferred, downloading, completed, sending, sent, failed… */
    val state: String,
    val transferred: Long = 0,
    val error: String? = null,
    val canDownload: Boolean = false,
    val canReject: Boolean = false,
    val canOpen: Boolean = false,
    val canCancel: Boolean = false,
)

/** Someone's active emergency alert. */
/** Another person's emergency alert, once however many services it came through. */
data class AlertItem(val person: String, val what: String, val via: String, val latitude: Double?, val longitude: Double?,
                     /** A group alert, which an authorised user may cancel (an ad hoc one only its sender). */
                     val cancellable: Boolean = true)

/** A ready-made audience for an ad hoc emergency alert: what the user sees, and the participant criteria. */
data class AlertChoice(val label: String, val criteria: String)

/** The MCVideo call on screen. */
data class VideoCallItem(
    val id: Int,
    val peer: String,
    /** setup, ringing, established. */
    val state: String,
    /** idle, requesting, transmitting, releasing, or receiving (someone else transmits). */
    val transmission: String,
    val transmitter: String?,
    val transmissionControl: Boolean,
    /** The group of a group call. */
    val group: String? = null,
    /** Called by the other side (a ringing incoming call can be answered or declined). */
    val incoming: Boolean = false,
    /** A chat group's call (joined, not invited). */
    val chat: Boolean = false,
    /** `normal`, `emergency` or `imminent` (peril), as the call is now (§6.2.8). */
    val priority: String = "normal",
    /** The session type: private, prearranged, chat, or a video push or pull (§12.2, §13.2). */
    val mode: String = "private",
    /** A push to the server: where it was recorded, once known. */
    val recordedAt: String? = null,
    /** Group calls: the other participants' MC IDs, for remote transmission. */
    val participantIds: List<String> = emptyList(),
    /** Group calls: who is in the call (short names), from the conference event package. */
    val participants: List<String> = emptyList(),
    /** Width over height of the received picture, once known (the screen keeps its shape). */
    val videoAspect: Float? = null,
    val picturesSent: Int = 0,
    val picturesShown: Int = 0,
    val lastEvent: String? = null,
    /** An ambient viewing call: `viewing` or `viewed-to` (§15). */
    val ambientRole: String? = null,
    /** Whether End may release the call (an ambient viewing call needs the release capability). */
    val mayRelease: Boolean = true,
)

/** The attachment picked for sending: [ready] once staged, or why it cannot be sent. */
data class PendingItem(val name: String, val type: String? = null, val size: Long? = null, val ready: Boolean = false, val error: String? = null)

/** Everything the screens show, as plain data. */
data class UiState(
    val user: String = "",
    val phase: String = "not provisioned",
    val registered: Boolean = false,
    /** No service since (seconds since the epoch), the next attempt, and why IdMS signed the user out. */
    val offlineSince: Long? = null,
    val nextRetryAt: Long? = null,
    val signedOut: String? = null,
    /** The user logged off the MC services (TS 24.379 §7.2.1A step 4), until they log on again. */
    val loggedOff: Boolean = false,
    val groups: List<GroupItem> = emptyList(),
    val privateTargets: List<String> = emptyList(),
    val call: CallItem? = null,
    val incoming: CallItem? = null,
    val messages: List<MessageItem> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
    val error: String? = null,
    val provisioned: Boolean = false,
    val events: List<String> = emptyList(),
    val batteryExempt: Boolean = true,
    val endpoints: Map<String, String> = emptyMap(),
    /** The address the servers are reached at, the imported profile's, and whether the user changed it. */
    val serverAddress: String? = null,
    val profileServerAddress: String? = null,
    val serverAddressEdited: Boolean = false,
    /** Ordinary private calls to this phone ring first (manual answer). */
    val ringFirst: Boolean = false,
    /** Why the last call attempt failed, until the next attempt or a dismiss. */
    val callFailure: String? = null,
    /** The provisioned identities and servers, as label and value, for the Logs tab. */
    val identities: List<Pair<String, String>> = emptyList(),
    /** This user's functional aliases, and the outcome of the last change, for the Settings tab. */
    val aliases: List<AliasItem> = emptyList(),
    val aliasNote: String? = null,
    /** The call's audio is on the loudspeaker; null when there is no call to route. */
    val speaker: Boolean? = null,
    /** File distribution is provisioned. */
    val filesEnabled: Boolean = false,
    val attachment: PendingItem? = null,
    /** MCVideo groups (video recordings are offered when there are any). */
    val videoGroups: List<String> = emptyList(),
    /** The emergency alert: the group it goes to (the selected one), the services it can go through, in order. */
    val alertGroup: String? = null,
    val alertServices: List<String> = emptyList(),
    /** The services on which this user's own alert is up, and their ad hoc alert (its audience and services). */
    val ownAlert: List<String> = emptyList(),
    val ownAdhoc: String? = null,
    val adhocChoices: List<AlertChoice> = emptyList(),
    /** The outcome of the last alert request, or the last area notice. */
    val alertResult: String? = null,
    /** Other people's active emergency alerts, one each. */
    val receivedAlerts: List<AlertItem> = emptyList(),
    /** Other users' locations, from the reports answering this user's requests. */
    val located: List<LocatedItem> = emptyList(),
    /** Whether and when this phone's location is reported, for Settings; null when no service asks for it. */
    val locationSharing: String? = null,
    /** This user's recordings on the MCVideo server (from pushes to it), to play back. */
    val videoRecordings: List<String> = emptyList(),
    /** MCVideo chat groups this user may join. */
    val videoChatGroups: List<String> = emptyList(),
    /** The video call in progress, shown over the tabs. */
    val videoCall: VideoCallItem? = null,
    /** Why the last video call attempt failed. */
    val videoFailure: String? = null,
    /** Known file size limits: by group ID, and for one-to-one files under [ONE_TO_ONE]. */
    val fileLimits: Map<String, Long> = emptyMap(),
) {
    fun fileLimit(target: String, group: Boolean): Long? = fileLimits[if (group) target else ONE_TO_ONE]

    companion object { const val ONE_TO_ONE = "" }
}

/** What the screens can ask for. */
interface UiActions {
    fun selectGroup(group: String) {}
    fun callGroup(group: String) {}
    fun callPrivate(target: String) {}
    /** A private call that rings at the other end (manual commencement). */
    fun callPrivateRinging(target: String) {}
    /** A private call without floor control: both talk at once, like a telephone call. */
    fun callPrivateNoFloor(target: String) {}
    /** An emergency or imminent peril ([priority] `emergency`, `imminent`) group call, or an emergency private call. */
    fun callGroupPriority(group: String, priority: String) {}
    fun callPrivateEmergency(target: String) {}
    /** Raise or cancel the emergency ([emergency] true) or imminent peril state of the call in progress. */
    fun callPriority(emergency: Boolean, enabled: Boolean) {}
    /** Functional aliases (TS 24.379 §9A): [action] `activate`, `takeover` or `deactivate`; present one on calls; call one. */
    fun aliasChange(action: String, alias: String, service: String = "mcptt") {}
    fun aliasUse(alias: String?, service: String = "mcptt") {}
    fun callAlias(alias: String, service: String = "mcptt") {}
    fun setMuted(muted: Boolean) {}
    fun setRingFirst(on: Boolean) {}
    fun callAdhoc(targets: List<String>) {}
    fun answer() {}
    fun reject() {}
    fun hangup() {}
    fun ptt(pressed: Boolean) {}
    fun send(target: String, group: Boolean, text: String) {}
    fun displayed(messageId: String) {}
    fun importProvisioning() {}
    /** Scan a QR code that carries the MCX profile or a one-time link to it. */
    fun scanProvisioning() {}
    /** Reach the servers at [host] instead of the profile's address; null resets it. */
    fun setServerAddress(host: String?) {}
    /** Log off every MC service and de-register (TS 24.379 §7.2.1A step 4); [logOn] brings them back. */
    fun logOff() {}
    fun logOn() {}
    /** No service: try to reach the servers now. Signed out by IdMS: sign in again (IdMS's page opens). */
    fun retryNow() {}
    fun signIn() {}
    fun requestBatteryExemption() {}
    fun setSpeaker(on: Boolean) {}
    fun clearLogs() {}
    fun clearMessages() {}
    fun clearHistory() {}
    fun dismissCallFailure() {}
    /** Video call placeholder: MCVideo is not integrated yet. */
    /** A video call to [target]: with transmission control (voice too), or two-way video without it. */
    fun videoCall(target: String, transmissionControl: Boolean = true) {}
    /** A video call that rings at the other end (manual commencement): they answer first. */
    fun videoCallRinging(target: String) {}
    /** An emergency private video call (§6.2.8.3). */
    fun videoCallEmergency(target: String) {}
    /** Raise or cancel the emergency ([emergency] true) or imminent peril state of the video call in progress. */
    fun videoPriority(emergency: Boolean, enabled: Boolean) {}
    fun videoAnswer() {}
    fun videoDecline() {}
    /** A prearranged group video call to [group] (always with transmission control). */
    fun videoGroupCall(group: String) {}
    /** Join the video call of chat group [group] (whoever else has joined is in it). */
    fun videoJoinChat(group: String) {}
    /** Push this phone's camera to [target], or pull (watch) theirs. */
    fun videoPush(target: String) {}
    fun videoPull(target: String) {}
    /** An ambient viewing call: view [target]'s camera, or with [local] let them view ours. */
    fun videoAmbient(target: String, local: Boolean) {}
    /** Record this phone's video on the MCVideo server, or play a recording back. */
    fun videoPushToServer() {}
    fun videoPullFromServer(url: String) {}
    /** In a call: ask the server to start or end [user]'s transmission. */
    fun videoRemote(user: String, start: Boolean) {}
    /**
     * Raise (with this phone's location) or cancel the emergency alert on the selected group: through [only]
     * ("MCPTT", "MCData" or "Video"), or every service (MCPTT, then MCData, then video).
     */
    fun emergencyAlert(on: Boolean, only: String? = null) {}
    /** An ad hoc group emergency alert to the users [criteria] selects (TS 24.379 §12.1A). */
    fun adhocAlert(criteria: String) {}
    /** Cancel [person]'s alert (if this user is authorised to). */
    fun cancelAlertOf(person: String) {}
    /** Show where an alert was raised, in a map app. */
    fun showOnMap(latitude: Double, longitude: Double) {}
    /** Ask where [target] is (an MCPTT location request, TS 24.379 §13.3.3.2). */
    fun locate(target: String) {}
    /** Press (true) or release (false) Transmit in a video call. */
    fun videoTransmit(pressed: Boolean) {}
    fun videoHangup() {}
    fun switchCamera() {}
    fun dismissVideoFailure() {}
    /** The call screen's surfaces: received video, and our camera's preview. */
    fun remoteSurface(surface: android.view.Surface?) {}
    fun previewSurface(surface: android.view.Surface?) {}
    /** Open the document picker for an image, a video or any document. */
    fun pickAttachment(kind: AttachmentKind) {}
    fun sendAttachment(target: String, group: Boolean) {}
    fun clearAttachment() {}
    fun downloadAttachment(id: String) {}
    fun rejectAttachment(id: String) {}
    fun cancelAttachment(id: String) {}
    /** Show a completed (or sent) attachment in a viewer app. */
    fun openAttachment(id: String) {}
}

fun fileItem(r: FdRecord): FileItem {
    val state = r.state.name.lowercase()
    return FileItem(r.metadata.name, r.metadata.mediaType, r.metadata.size, state, r.transferred, r.error,
        canDownload = r.downloadable, canReject = r.downloadable && r.state != FdState.FAILED && r.state != FdState.INTERRUPTED,
        canOpen = AttachmentFiles.openable(r) != null, canCancel = r.incoming && r.state == FdState.DOWNLOADING)
}

private fun fileStatus(r: FdRecord): String = when {
    r.incoming -> r.state.name.lowercase()
    r.state == FdState.SENT && r.notifications.isNotEmpty() ->
        r.notifications.entries.joinToString { "${it.key.short()} ${it.value.name.lowercase()}" }
    else -> r.state.name.lowercase()
}

/** A user's or group's name as shown: `sip:alice.mcptt@mcx.test` → `Alice`. */
/** The ready-made audiences of an ad hoc emergency alert, from the user's groups (TS 24.379 §12.1A criteria). */
object AlertChoices {
    fun of(groups: List<String>): List<AlertChoice> = groups.flatMap { g ->
        listOf(AlertChoice("Everyone affiliated to ${g.short()}", "affiliated:$g"), AlertChoice("All members of ${g.short()}", "member:$g"))
    }

    fun describe(criteria: String): String = criteria.split(',').joinToString(" and ") { c ->
        val (kind, group) = c.trim().substringBefore(':') to c.trim().substringAfter(':')
        when (kind) { "affiliated" -> "affiliated to ${group.short()}"; "member" -> "members of ${group.short()}"; else -> c.trim() }
    }
}

fun String.short(): String = removePrefix("sip:").substringBefore('@').removeSuffix(".mcptt").removeSuffix(".mcdata").removeSuffix(".mcvideo")
    .replaceFirstChar { it.titlecase() }

/** A snapshot of the engine for the screens. */
fun snapshot(batteryExempt: Boolean = true): UiState {
    val client = Engine.client ?: return UiState(events = Engine.log.last(30).reversed(), batteryExempt = batteryExempt)
    val status = client.status.value
    val mcptt = Engine.mcptt
    val profile = client.profile
    val groups = (client.config.mcptt?.groups ?: emptyList()).map {
        GroupItem(it, status.mcptt.affiliations[it] == "affiliated", Engine.selectedGroup.value == it)
    }
    fun item(call: Call): CallItem {
        val floor = call.media?.takeIf { it.isStarted }?.floor
        val state = when (floor?.state?.value) {
            FloorState.HAS_PERMISSION -> "talking"
            FloorState.PENDING_REQUEST -> "requesting"
            FloorState.PENDING_RELEASE -> "releasing"
            FloorState.QUEUED -> if (floor.offered.value) "offered" else "queued"
            FloorState.NO_PERMISSION -> if (floor.talker != null) "taken" else "idle"
            else -> "idle"
        }
        val title = when (call.type) {
            CallType.PRIVATE -> call.peer?.short() ?: "private call"
            CallType.PREARRANGED -> call.groupId?.short() ?: "group"
            CallType.ADHOC -> "ad hoc: ${call.groupId?.short() ?: ""}"
        }
        val limit = call.floorEvents.lastOrNull { it.startsWith("granted") }?.let { Regex("""(\d+)s$""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val self = client.config.mcptt?.userId
        val members = when (call.type) {
            CallType.PRIVATE -> listOfNotNull(self, call.peer)
            else -> call.groupId?.let { client.groupDocuments[it]?.members } ?: emptyList()
        }
        // The role first when a functional alias is involved (TS 24.379 §9A): "Call to <role>", "<role> (<caller>)", "as <role>".
        val shown = if (call.type == CallType.PRIVATE) CallDisplay.privateCall(call.outgoing, call.peer, call.callingAlias, call.calledAlias)
            else CallDisplay.groupCall(title, call.outgoing, call.callingAlias)
        return CallItem(call.id, shown.title, call.type, call.state, state, floor?.talker, call.floorEvents.lastOrNull(), limit, call.startedAt, members,
            call.floorControl, call.media?.muted == true, call.priority, floor?.queuePosition?.value, shown.subtitle)
    }
    val calls = mcptt?.calls?.value ?: emptyList()
    val incoming = calls.firstOrNull { !it.outgoing && it.state == "ringing" }
    val active = calls.firstOrNull { it.state != "released" && it != incoming }
    val mcdata = Engine.mcdata
    val messages = buildList {
        mcdata?.inbox?.value?.forEach {
            add(MessageItem(it.messageId.toString(), true, it.sender, it.group, it.text,
                if (it.displayed) "read" else "new", it.at, it.displayed))
        }
        mcdata?.outbox?.value?.forEach { m ->
            val status = if (m.notifications.isEmpty()) "sent" else m.notifications.entries.joinToString { "${it.key.short()} ${it.value.name.lowercase().replace('_', ' ')}" }
            add(MessageItem(m.messageId.toString(), false, m.target, if (m.group) m.target else null, m.text, status, m.at, true))
        }
        Engine.fd?.records?.value?.forEach { r ->
            add(MessageItem(r.id, r.incoming, r.peer, r.group, r.metadata.name, fileStatus(r), r.at, true, fileItem(r)))
        }
    }.sortedBy { it.at }
    val fdConfig = client.config.mcdata?.fd
    val fileLimits = buildMap {
        if (fdConfig != null) {
            FdPolicy.effectiveLimit(fdConfig, null)?.let { put(UiState.ONE_TO_ONE, it) }
            client.config.mcdata?.groups?.forEach { g ->
                val policy = client.groupDocuments[g]?.fd
                (if (policy != null) FdPolicy.effectiveLimit(fdConfig, policy) else FdPolicy.effectiveLimit(fdConfig.copy(userMaxBytes = null), null))
                    ?.let { put(g, it) }
            }
        }
    }
    val pending = Engine.pendingAttachment.value?.let {
        PendingItem(it.name, it.metadata?.mediaType, it.metadata?.size, it.file != null && it.error == null, it.error)
    }
    val aliasState = client.aliases.value
    val aliases = listOf(McService.MCPTT, McService.MCDATA, McService.MCVIDEO).flatMap { service ->
        val state = client.aliasesOf(service).value
        state.available.map { a ->
            AliasItem(a, when (a) { in state.active -> "active"; in state.takeOverPossible -> "take-over-possible"; else -> "inactive" },
                a == state.inUse, service.name.lowercase(), state.lastOutcomes[a])
        }
    }
    val aliasNote = aliasState.lastAction?.let { action ->
        val outcomes = aliasState.lastOutcomes.entries.joinToString { "${it.key.short()} ${it.value}" }
        when {
            aliasState.lastStatus == null -> "Asking the server to $action…"
            aliasState.lastStatus !in 200..299 -> "Refused: ${aliasState.lastStatus}${aliasState.lastWarning?.let { " $it" } ?: ""}"
            outcomes.isNotEmpty() -> outcomes
            else -> null
        }
    }
    return UiState(
        user = client.config.sip.publicUserId.short(),
        aliases = aliases,
        aliasNote = aliasNote,
        phase = status.phase,
        registered = status.registered,
        loggedOff = status.loggedOff,
        offlineSince = status.offlineSince,
        nextRetryAt = status.nextRetryAt,
        signedOut = status.signedOut,
        groups = groups,
        privateTargets = profile?.privateCallTargets ?: emptyList(),
        call = active?.let(::item),
        incoming = incoming?.let(::item),
        messages = messages,
        history = Engine.history?.recent(50) ?: emptyList(),
        error = status.lastError,
        provisioned = true,
        events = Engine.log.last(30).reversed(),
        batteryExempt = batteryExempt,
        endpoints = client.config.endpoints,
        serverAddress = Engine.serverAddress.current,
        profileServerAddress = Engine.serverAddress.profile,
        serverAddressEdited = Engine.serverAddress.edited,
        identities = client.config.let { c ->
            listOfNotNull(
                "SIP identity" to c.sip.publicUserId,
                // The MC service IDs: from the provisioning (or the activation package), the MCPTT ID else from the
                // MCPTT user profile the CMS served (TS 24.484 <MCPTTUserID>).
                "MCPTT ID" to (c.mcptt?.userId ?: profile?.mcpttId ?: "not provisioned"),
                "MCData ID" to (c.mcdata?.userId ?: "not provisioned"),
                "MCVideo ID" to (c.mcvideo?.userId ?: "not provisioned"),
                c.mcptt?.groups?.takeIf { it.isNotEmpty() }?.let { "MCPTT groups" to it.joinToString() },
                c.mcdata?.groups?.takeIf { it.isNotEmpty() }?.let { "MCData groups" to it.joinToString() },
                c.mcvideo?.groups?.takeIf { it.isNotEmpty() }?.let { "MCVideo groups" to it.joinToString() },
                "Functional aliases" to (client.aliases.value.active.takeIf { it.isNotEmpty() }?.joinToString { CallDisplay.role(it) } ?: "none active"),
                "SIP core" to "${c.sip.proxy}:${c.sip.proxyPort} (${c.sip.transport})",
                "IdMS" to c.idms.issuer,
                "IdMS sign-in" to (c.idms.username?.let { "automatic as $it" } ?: "interactive (browser)"),
                // Which IdMS signed this user in (several vendors' IdMSs at a Plugtest), and until when the access token
                // holds; it is renewed before then.
                "Signed in at" to (status.tokenExpiresAt.takeIf { it > 0 }?.let {
                    "${java.net.URI(c.idms.issuer).host}, token until " +
                        java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
                            .format(java.time.Instant.ofEpochSecond(it).atZone(java.time.ZoneId.systemDefault()))
                } ?: "not signed in"),
            )
        },
        speaker = Engine.telecom?.speaker?.value,
        callFailure = Engine.callFailure.value,
        ringFirst = mcptt?.answerMode == "manual",
        filesEnabled = Engine.fd != null,
        videoChatGroups = client.config.mcvideo?.chatGroups ?: emptyList(),
        videoRecordings = Engine.mcvideo?.recordings?.value ?: emptyList(),
        videoGroups = client.config.mcvideo?.groups ?: emptyList(),
        alertGroup = Engine.selectedGroup.value,
        alertServices = Engine.alerts?.services()?.map(AlertCenter::label) ?: emptyList(),
        ownAlert = Engine.alerts?.own()?.keys?.sortedBy { it.ordinal }?.map(AlertCenter::label) ?: emptyList(),
        ownAdhoc = Engine.alerts?.ownAdhoc()?.let { (criteria, via) -> "${AlertChoices.describe(criteria)} (${via.joinToString(", ", transform = AlertCenter::label)})" },
        adhocChoices = if (Engine.alerts?.adhocServices().isNullOrEmpty()) emptyList() else AlertChoices.of(groups.map { it.id }),
        alertResult = Engine.alertResult.value,
        receivedAlerts = Engine.alerts?.received()?.map { a ->
            AlertItem(a.person.replaceFirstChar { it.uppercase() },
                if (a.adhoc) "ad hoc: ${a.criteria?.let(AlertChoices::describe) ?: ""}" else a.group?.short() ?: "",
                a.services.joinToString(", ", transform = AlertCenter::label), a.latitude, a.longitude, a.cancellable.isNotEmpty())
        } ?: emptyList(),
        located = Engine.locations.flatMap { it.located.value.values }.groupBy { it.user.short() }
            .map { (name, all) -> all.maxBy { it.receivedAt }.let { LocatedItem(name, it.latitude, it.longitude, it.servingCell, it.receivedAt) } }
            .sortedByDescending { it.at },
        locationSharing = Engine.locations.filter { it.reporter.configured }.takeIf { it.isNotEmpty() }?.let { configured ->
            val last = configured.flatMap { it.sent }.mapNotNull { it.location.timestamp }.maxOrNull()
            "Reported to the MC service for ${configured.joinToString { it.service.name }}" +
                (last?.let { ", last at ${it.substringAfter('T').removeSuffix("Z")} UTC" } ?: "")
        },
        // The viewed-to user of a remotely initiated ambient viewing call is shown nothing (§15.2.1.2 NOTE 4).
        videoCall = Engine.mcvideo?.active?.takeUnless { it.hidden }?.let { v ->
            val media = v.media?.takeIf { it.isStarted }
            val tx = when (media?.control?.state?.value) {
                TxState.HAS_PERMISSION -> "transmitting"
                TxState.PENDING_REQUEST -> "requesting"
                TxState.PENDING_RELEASE -> "releasing"
                else -> if (media?.control?.transmitter != null) "receiving" else "idle"
            }
            VideoCallItem(v.id, v.groupId?.short() ?: v.peer?.let { CallDisplay.caller(it, v.callingAlias, v.calledAlias, it.short()) } ?: "video call", v.state, if (v.transmissionControl) tx else "two-way",
                media?.control?.transmitter?.short(), v.transmissionControl, v.groupId, !v.outgoing, v.sessionType == "chat", v.priority,
                v.sessionType, v.pushUrl?.takeIf { v.sessionType == org.openmcx.client.mcvideo.McvideoService.PUSH_TO_SERVER },
                v.participants.keys.filter { it != client.config.mcvideo?.userId },
                v.participants.keys.map { it.short() },
                Engine.video?.remoteSize?.value?.let { size -> size.width.toFloat() / size.height },
                media?.stats?.picturesSent?.get() ?: 0,
                media?.stats?.picturesReceived?.get() ?: 0, v.events.lastOrNull(), v.ambientRole, v.mayRelease)
        },
        videoFailure = Engine.videoFailure.value,
        attachment = pending,
        fileLimits = fileLimits,
    )
}

/** The actions against the engine. */
object EngineActions : UiActions {
    private fun launch(block: suspend () -> Unit) {
        Engine.scope.launch { runCatching { block() }.onFailure { Engine.log.add("ui action failed: ${it.message}") } }
    }

    override fun selectGroup(group: String) { Engine.selectedGroup.value = group }
    /** Place a call; a failure is logged and shown on the Call tab. */
    private fun place(target: String, block: suspend () -> Unit) {
        Engine.callFailure.value = null
        Engine.scope.launch {
            runCatching { block() }.onFailure {
                Engine.log.add("ui action failed: ${it.message}")
                Engine.callFailure.value = Engine.callFailureText(target, it)
            }
        }
    }

    override fun callGroup(group: String) = place(group) { Engine.mcptt?.groupCall(group) }
    override fun callPrivate(target: String) = place(target) { Engine.mcptt?.privateCall(target, Commencement.AUTOMATIC) }
    override fun callPrivateRinging(target: String) = place(target) { Engine.mcptt?.privateCall(target, Commencement.MANUAL) }
    override fun locate(target: String) {
        Engine.scope.launch {
            runCatching { Engine.location(org.openmcx.client.location.LocationService.MCPTT)?.request(listOf(target)) ?: error("location not provisioned") }
                .onFailure { Engine.callFailure.value = "Location of ${target.short()}: ${it.message}" }
        }
    }
    override fun callPrivateNoFloor(target: String) = place(target) { Engine.mcptt?.privateCall(target, Commencement.AUTOMATIC, floorControl = false) }
    override fun callGroupPriority(group: String, priority: String) = place(group) { Engine.mcptt?.groupCall(group, priority) }
    override fun callPrivateEmergency(target: String) = place(target) { Engine.mcptt?.privateCall(target, Commencement.AUTOMATIC, emergency = true) }
    override fun aliasChange(action: String, alias: String, service: String) {
        Engine.scope.launch {
            runCatching { Engine.client?.aliasRequest(action, listOf(alias), McService.valueOf(service.uppercase())) }
                .onFailure { Engine.callFailure.value = "Functional alias ${alias.short()}: ${it.message}" }
        }
    }
    override fun aliasUse(alias: String?, service: String) {
        runCatching { Engine.client?.useAlias(alias, McService.valueOf(service.uppercase())) }
            .onFailure { Engine.callFailure.value = "Functional alias: ${it.message}" }
    }
    override fun callAlias(alias: String, service: String) = place(alias) {
        when (service) {
            "mcvideo" -> Engine.mcvideo?.privateCallToAlias(alias)
            else -> Engine.mcptt?.privateCallToAlias(alias)
        }
    }
    override fun callPriority(emergency: Boolean, enabled: Boolean) = launch {
        runCatching { Engine.mcptt?.setPriority(emergency, enabled) }.onFailure {
            Engine.log.add("call priority change failed: ${it.message}")
            Engine.callFailure.value = "${if (emergency) "Emergency" else "Imminent peril"} not ${if (enabled) "raised" else "cancelled"}: " +
                (if ((it as? org.openmcx.client.mcptt.CallError)?.status == 403) "not allowed" else it.message ?: "failed")
        }
    }
    override fun setMuted(muted: Boolean) { Engine.mcptt?.setMuted(muted) }
    override fun setRingFirst(on: Boolean) = Engine.setRingFirst(on)
    override fun logOff() = launch { runCatching { Engine.client?.logOff() }.onFailure { Engine.log.add("log-off: ${it.message}") } }
    override fun logOn() { runCatching { Engine.client?.logOn() }.onFailure { Engine.log.add("log-on: ${it.message}") } }
    override fun retryNow() { Engine.client?.retryNow() }
    override fun signIn() { runCatching { Engine.client?.signIn() }.onFailure { Engine.log.add("sign-in: ${it.message}") } }
    override fun callAdhoc(targets: List<String>) = place("the ad hoc group") { Engine.mcptt?.adhocCall(targets) }
    override fun answer() { Engine.mcptt?.answer() }
    override fun reject() { Engine.mcptt?.reject() }
    override fun hangup() = launch { Engine.mcptt?.hangup() }
    override fun ptt(pressed: Boolean) = Engine.ptt(pressed, "screen")
    override fun send(target: String, group: Boolean, text: String) = launch {
        Engine.mcdata?.send(target, text, group, DispositionRequest.DELIVERY_AND_READ)?.let(Engine::sent)
    }
    override fun displayed(messageId: String) { Engine.mcdata?.display(UUID.fromString(messageId)) }
    override fun setSpeaker(on: Boolean) { Engine.telecom?.setSpeaker(on) }
    override fun clearLogs() = Engine.clearLogs()
    override fun clearMessages() = Engine.clearMessages()
    override fun clearHistory() = Engine.clearHistory()
    override fun dismissCallFailure() { Engine.callFailure.value = null }
    /** A video call to [target] (an MCPTT ID from the Call tab; the MCVideo ID follows the same naming). */
    override fun videoCall(target: String, transmissionControl: Boolean) {
        val service = Engine.mcvideo ?: run { Engine.videoFailure.value = "Video calls are not provisioned on this phone"; return }
        val mcvideoId = Engine.mcvideoId(target)
        Engine.videoFailure.value = null
        Engine.scope.launch {
            runCatching { service.privateCall(mcvideoId, transmissionControl = transmissionControl) }.onFailure {
                Engine.log.add("video call failed: ${it.message}")
                Engine.videoFailure.value = Engine.callFailureText(mcvideoId, it).replace("Call to", "Video call to")
            }
        }
    }
    override fun videoGroupCall(group: String) {
        val service = Engine.mcvideo ?: run { Engine.videoFailure.value = "Video calls are not provisioned on this phone"; return }
        Engine.videoFailure.value = null
        Engine.scope.launch {
            runCatching { service.groupCall(group) }.onFailure {
                Engine.log.add("group video call failed: ${it.message}")
                Engine.videoFailure.value = Engine.callFailureText(group, it).replace("Call to", "Video call to")
            }
        }
    }
    override fun videoCallRinging(target: String) {
        val service = Engine.mcvideo ?: run { Engine.videoFailure.value = "Video calls are not provisioned on this phone"; return }
        val mcvideoId = Engine.mcvideoId(target)
        Engine.videoFailure.value = null
        Engine.scope.launch {
            runCatching { service.privateCall(mcvideoId, Commencement.MANUAL) }.onFailure {
                Engine.log.add("video call failed: ${it.message}")
                Engine.videoFailure.value = Engine.callFailureText(mcvideoId, it).replace("Call to", "Video call to")
            }
        }
    }
    override fun videoCallEmergency(target: String) {
        val service = Engine.mcvideo ?: run { Engine.videoFailure.value = "Video calls are not provisioned on this phone"; return }
        val mcvideoId = Engine.mcvideoId(target)
        Engine.videoFailure.value = null
        Engine.scope.launch {
            runCatching { service.privateCall(mcvideoId, emergency = true) }.onFailure {
                Engine.log.add("emergency video call failed: ${it.message}")
                Engine.videoFailure.value = Engine.callFailureText(mcvideoId, it).replace("Call to", "Emergency video call to")
            }
        }
    }
    override fun videoPriority(emergency: Boolean, enabled: Boolean) {
        Engine.scope.launch {
            runCatching { Engine.mcvideo?.setPriority(emergency, enabled) }.onFailure {
                Engine.log.add("video ${if (emergency) "emergency" else "imminent peril"} change failed: ${it.message}")
                Engine.videoFailure.value = "${if (emergency) "Emergency" else "Imminent peril"} ${if (enabled) "not raised" else "not cancelled"}: " +
                    (if ((it as? org.openmcx.client.mcptt.CallError)?.status == 403) "not allowed" else it.message ?: "failed")
            }
        }
    }
    override fun videoAnswer() { Engine.mcvideo?.answer() }
    private fun videoStart(what: String, block: suspend (org.openmcx.client.mcvideo.McvideoService) -> Unit) {
        val service = Engine.mcvideo ?: run { Engine.videoFailure.value = "Video calls are not provisioned on this phone"; return }
        Engine.videoFailure.value = null
        Engine.scope.launch {
            runCatching { block(service) }.onFailure {
                Engine.log.add("$what failed: ${it.message}")
                Engine.videoFailure.value = "$what failed: " + when ((it as? org.openmcx.client.mcptt.CallError)?.status) {
                    403 -> "not allowed"; 404 -> "not found"; 480 -> "not available"; else -> it.message ?: "failed" }
            }
        }
    }
    override fun videoPush(target: String) = videoStart("Video push to ${target.short()}") { it.push(Engine.mcvideoId(target)) }
    override fun videoPull(target: String) = videoStart("Video pull from ${target.short()}") { it.pull(Engine.mcvideoId(target)) }
    override fun videoAmbient(target: String, local: Boolean) =
        videoStart(if (local) "Ambient viewing by ${target.short()}" else "Ambient viewing of ${target.short()}") { it.ambient(Engine.mcvideoId(target), local) }
    override fun videoPushToServer() = videoStart("Recording on the server") { it.pushToServer() }
    override fun videoPullFromServer(url: String) = videoStart("Playing the recording") { it.pullFromServer(url) }
    override fun videoRemote(user: String, start: Boolean) = videoStart(if (start) "Starting ${user.short()}'s video" else "Stopping ${user.short()}'s video") {
        it.remoteTransmission(user, start)
    }
    override fun videoDecline() { Engine.mcvideo?.reject() }
    override fun videoJoinChat(group: String) {
        val service = Engine.mcvideo ?: run { Engine.videoFailure.value = "Video calls are not provisioned on this phone"; return }
        Engine.videoFailure.value = null
        Engine.scope.launch {
            runCatching { service.joinChat(group) }.onFailure {
                Engine.log.add("joining video chat failed: ${it.message}")
                Engine.videoFailure.value = Engine.callFailureText(group, it).replace("Call to", "Joining the video chat")
            }
        }
    }
    override fun videoTransmit(pressed: Boolean) = launch { if (pressed) Engine.mcvideo?.transmit() else Engine.mcvideo?.stopTransmit() }
    override fun videoHangup() = launch { Engine.mcvideo?.hangup() }
    override fun switchCamera() { Engine.video?.flip() }
    override fun dismissVideoFailure() { Engine.videoFailure.value = null }
    override fun remoteSurface(surface: android.view.Surface?) { Engine.video?.remoteSurface = surface }
    override fun previewSurface(surface: android.view.Surface?) { Engine.video?.previewSurface = surface }
    override fun emergencyAlert(on: Boolean, only: String?) = launch { Engine.emergencyAlert(on, only) }
    override fun adhocAlert(criteria: String) = launch { Engine.adhocAlert(criteria) }
    override fun cancelAlertOf(person: String) = launch { Engine.cancelAlertOf(person.lowercase()) }
    override fun sendAttachment(target: String, group: Boolean) = Engine.sendAttachment(target, group)
    override fun clearAttachment() = Engine.clearAttachment()
    override fun downloadAttachment(id: String) = launch { Engine.fd?.download(id) }
    override fun rejectAttachment(id: String) = launch { Engine.fd?.reject(id) }
    override fun cancelAttachment(id: String) { Engine.fd?.cancel(id) }
}

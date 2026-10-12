package org.openmcx.client.app

import org.openmcx.client.app.security.ClientCredentials

import android.content.Context
import org.openmcx.client.app.identity.customTabBrowser
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.openmcx.client.app.attachments.AttachmentFiles
import org.openmcx.client.app.data.AttachmentStore
import org.openmcx.client.app.data.HistoryEntry
import org.openmcx.client.app.data.HistoryStore
import org.openmcx.client.app.media.AndroidVideo
import org.openmcx.client.app.media.FileAudio
import org.openmcx.client.app.media.MediaCodecAmrWb
import org.openmcx.client.app.media.PlatformAudio
import org.openmcx.client.app.telecom.TelecomBridge
import org.openmcx.client.client.EventLog
import org.openmcx.client.client.McxClient
import org.openmcx.client.http.Endpoints
import org.openmcx.client.http.SocketFileHttpClient
import org.openmcx.client.http.SocketHttpClient
import org.openmcx.client.mcdata.ClientFdPeer
import org.openmcx.client.mcdata.FdPolicy
import org.openmcx.client.mcdata.FdRecord
import org.openmcx.client.mcdata.FdService
import org.openmcx.client.mcdata.FileMetadata
import org.openmcx.client.mcdata.DispositionNotification
import org.openmcx.client.mcdata.McdataService
import org.openmcx.client.mcdata.ReceivedSds
import org.openmcx.client.mcdata.SdsListener
import org.openmcx.client.mcdata.SentSds
import org.openmcx.client.mcptt.Call
import org.openmcx.client.mcptt.CallError
import org.openmcx.client.mcptt.CallListener
import org.openmcx.client.mcptt.FloorState
import org.openmcx.client.mcptt.McpttService
import org.openmcx.client.mcvideo.McvideoService
import org.openmcx.client.mcvideo.VideoCall
import org.openmcx.client.mcvideo.VideoCallListener
import org.openmcx.client.sip.SocketConnector
import java.io.File

/**
 * The process-wide protocol engine: one [McxClient] built from the provisioning bundle,
 * with its MCPTT and MCData services. The foreground service starts it; the UI, the
 * notifications, telecom and the debug endpoint all read it.
 */
object Engine {
    private const val TAG = "mcx"
    val log = EventLog()
    // A failure in one coroutine (a call's media, say) is logged; it must not end the process.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "uncaught", e)
        log.add("error: ${e.javaClass.simpleName}: ${e.message}")
    })

    @Volatile var client: McxClient? = null
        private set
    @Volatile var mcptt: McpttService? = null
        private set
    @Volatile var mcdata: McdataService? = null
        private set
    /** MCVideo private calls, and the camera and decoder they use. */
    @Volatile var mcvideo: McvideoService? = null
        private set
    @Volatile var video: AndroidVideo? = null
        private set
    private val _inVideoCall = MutableStateFlow(false)
    /** True while a video call is up: the service then also declares the camera type. */
    val inVideoCall: StateFlow<Boolean> = _inVideoCall
    /** Why the last video call attempt failed, for the Call tab. */
    val videoFailure = MutableStateFlow<String?>(null)
    /** File distribution (attachments in Messages). */
    @Volatile var fd: FdService? = null
        private set
    /** Test audio in debug builds: WAV files in place of the microphone, received speech to files. */
    @Volatile var fileAudio: FileAudio? = null
        private set
    @Volatile var telecom: TelecomBridge? = null
    /** Location reporting (TS 24.379 clause 13 and its MCData and MCVideo counterparts), one client per service. */
    @Volatile var locations: List<org.openmcx.client.location.LocationClient> = emptyList()
        private set
    @Volatile var deviceLocation: org.openmcx.client.app.location.DeviceLocation? = null
        private set
    /** Emergency alerts of MCPTT, MCData and MCVideo (ad hoc), and the app's single alert over them. */
    @Volatile var alertHandlers: List<org.openmcx.client.alert.EmergencyAlerts> = emptyList()
        private set
    @Volatile var alerts: org.openmcx.client.app.alert.AlertCenter? = null
        private set
    /** The outcome of the last alert request, or the last area notice, for the Call tab. */
    val alertResult = MutableStateFlow<String?>(null)
    @Volatile private var appContext: Context? = null
    /** A position set from the debug endpoint, in place of the phone's (lab tests of areas). */
    @Volatile var positionOverride: org.openmcx.client.mcvideo.EmergencyLocation? = null
    /** The location client of [service] (MCPTT, MCData or MCVideo), if provisioned. */
    fun location(service: org.openmcx.client.location.LocationService) = locations.firstOrNull { it.service == service }
    private val signalledCalls = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    @Volatile private var traceFile: File? = null
        private set
    @Volatile var history: HistoryStore? = null
        private set
    private var notifications: Notifications? = null

    /** The attachment picked in Messages: being staged, ready to send, or refused. */
    data class PendingAttachment(val name: String, val file: File? = null, val metadata: FileMetadata? = null, val error: String? = null)
    val pendingAttachment = MutableStateFlow<PendingAttachment?>(null)

    /** The group a PTT press calls when no call is up (a radio's selected channel). */
    val selectedGroup = MutableStateFlow<String?>(null)
    /** Why the last call attempt failed, for the Call tab; cleared by the next attempt or a dismiss. */
    val callFailure = MutableStateFlow<String?>(null)
    @Volatile private var prefs: android.content.SharedPreferences? = null

    /**
     * "Ring me first": this phone answers ordinary private calls manually, so they ring
     * (TS 24.379 §11.1.1.2.1.2 steps 9-10: the called client may apply manual commencement
     * to an automatic request; a forced call still connects). Kept across restarts.
     */
    fun setRingFirst(on: Boolean) {
        prefs?.edit()?.putBoolean(RING_FIRST, on)?.apply()
        mcptt?.let { it.answerMode = if (on) "manual" else (client?.config?.mcptt?.answerMode ?: "automatic") }
        mcvideo?.let { it.answerMode = if (on) "manual" else (client?.config?.mcvideo?.answerMode ?: "automatic") }
        log.add("ring first for private calls: ${if (on) "on" else "off"}")
    }

    private const val RING_FIRST = "ring_first"
    private val _inCall = MutableStateFlow(false)
    /** True while a call is up: the service then declares the phoneCall and microphone types. */
    val inCall: StateFlow<Boolean> = _inCall

    @Synchronized
    fun start(context: Context): McxClient? {
        client?.let { return it }
        val provisioning = Provisioning(context)
        if (!provisioning.present) { log.add("not provisioned"); return null }
        val config = provisioning.config()
        serverAddress = ServerAddress(Provisioning.hostOf(config), Provisioning.hostOf(provisioning.importedConfig()),
            provisioning.serverAddress != null)
        appContext = context.applicationContext
        val ssl = provisioning.sslContext()
        val trace = File(context.filesDir, "sip-trace.log").also { traceFile = it }
        log.tracer = { direction, message ->
            runCatching { trace.appendText("----- ${java.time.Instant.now()} $direction\n$message\n") }
        }
        val endpoints = Endpoints.of(config.endpoints)
        if (config.endpoints.isNotEmpty()) log.add("endpoints: ${config.endpoints}")
        val c = McxClient(scope, config, SocketHttpClient(ssl, endpoints), SocketConnector(config.sip.transport, ssl, endpoints = endpoints),
            log, browser = customTabBrowser(context.applicationContext))
        client = c
        val store = HistoryStore(context).also { history = it }
        val notify = Notifications(context).also { notifications = it }
        // Debug builds keep received speech as files for the lab checks; on a real phone (not a redroid
        // container, which has no audio device) they also play it.
        val audio = if (BuildConfig.DEBUG) FileAudio(File(context.filesDir, "received"), PlatformAudio,
            play = !android.os.Build.PRODUCT.startsWith("redroid")).also { fileAudio = it } else PlatformAudio
        config.mcptt?.let { mcpttConfig ->
            selectedGroup.value = mcpttConfig.groups.firstOrNull()
            val service = McpttService(c, MediaCodecAmrWb, audio)
            prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            if (prefs?.getBoolean(RING_FIRST, false) == true) service.answerMode = "manual"
            val bridge = TelecomBridge(context, scope, service).also { telecom = it }
            service.listener = object : CallListener {
                override fun incoming(call: Call) { bridge.track(call); notify.incomingCall(call) }
                override fun changed(call: Call) {
                    if (call.outgoing) bridge.track(call)
                    if (call.state != "ringing") notify.cancelIncoming(call)
                    notify.callChanged(call)
                    // Location triggers of a call's start (Annex F.3.3: GroupCallNonEmergency, PrivateCallNonEmergency).
                    alertsChanged()
                    if (call.state == "established" && call.priority == "normal" && signalledCalls.add("mcptt-${call.id}"))
                        location(org.openmcx.client.location.LocationService.MCPTT)?.signal(if (call.type == org.openmcx.client.mcptt.CallType.PRIVATE)
                            org.openmcx.client.location.Event.PrivateCallNonEmergency else org.openmcx.client.location.Event.GroupCallNonEmergency)
                    _inCall.value = service.calls.value.any { it.state != "released" }
                    if (call.state == "released") store.put(HistoryEntry("call", "${call.startedAt}-${call.id}",
                        if (call.outgoing) "out" else "in", call.peer, call.groupId,
                        "${call.type.sessionType}: ${call.releaseReason ?: "ended"}", call.startedAt))
                }
            }
            mcptt = service
            c.handlers += service
        }
        config.mcdata?.let {
            val service = McdataService(c)
            service.listener = object : SdsListener {
                override fun received(message: ReceivedSds) {
                    store.put(HistoryEntry("sds", message.messageId.toString(), "in", message.sender, message.group, message.text, message.at))
                    notify.message(message)
                }
                override fun notification(message: SentSds, from: String, type: DispositionNotification) {
                    store.put(HistoryEntry("sds", message.messageId.toString(), "out", message.target, if (message.group) message.target else null,
                        "${message.text} (${message.notifications.entries.joinToString { "${it.key.substringBefore('@')}: ${it.value.name.lowercase()}" }})",
                        message.at))
                }
            }
            mcdata = service
            c.handlers += service
            val files = FdService(scope, ClientFdPeer(c), it.fd, SocketFileHttpClient(ssl, endpoints, it.fd.transferTimeoutMs),
                AttachmentFiles.fdDirectory(context), AttachmentStore(context))
            files.onOffer = { record ->
                store.put(fileEntry(record))
                notify.attachment(record)
            }
            fd = files
            c.handlers += files
        }
        config.mcvideo?.let {
            val devices = AndroidVideo(context.applicationContext).also { v -> video = v }
            val service = McvideoService(c, devices, MediaCodecAmrWb, audio)
            val settings = prefs ?: context.getSharedPreferences("settings", Context.MODE_PRIVATE).also { p -> prefs = p }
            if (settings.getBoolean(RING_FIRST, false)) service.answerMode = "manual"
            service.listener = object : VideoCallListener {
                override fun incoming(call: VideoCall) { notify.incomingVideoCall(call) }
                override fun alert(alert: org.openmcx.client.mcvideo.VideoAlert) { alertsChanged() }
                override fun changed(call: VideoCall) {
                    if (call.state != "ringing") notify.cancelIncomingVideo(call)
                    notify.videoCallChanged(call)
                    location(org.openmcx.client.location.LocationService.MCVIDEO)?.let { l ->
                        // In an emergency the emergency information and triggers apply (§18.3).
                        l.setEmergency(service.calls.value.any { it.state != "released" && it.priority != "normal" } || service.alerting.value.isNotEmpty())
                        if (call.state == "established" && call.priority == "normal" && signalledCalls.add("mcvideo-${call.id}"))
                            l.signal(if (call.isGroup) org.openmcx.client.location.Event.GroupCallNonEmergency else org.openmcx.client.location.Event.PrivateCallNonEmergency)
                    }
                    _inVideoCall.value = service.calls.value.any { v -> v.state != "released" }
                    if (call.state == "released" && !call.hidden) store.put(HistoryEntry("video", "${call.startedAt}-${call.id}",
                        if (call.outgoing) "out" else "in", call.peer, null, "video call: ${call.releaseReason ?: "ended"}", call.startedAt))
                }
            }
            mcvideo = service
            // Before MCPTT, which would take any INVITE it does not recognise.
            c.handlers.add(0, service)
        }
        // Location reporting for each provisioned service, ahead of the services, which take any MESSAGE in a call.
        val services = buildList {
            config.mcptt?.let { add(org.openmcx.client.location.LocationClient(c, org.openmcx.client.location.LocationService.MCPTT, it.psi)) }
            config.mcdata?.let { add(org.openmcx.client.location.LocationClient(c, org.openmcx.client.location.LocationService.MCDATA, it.psi)) }
            config.mcvideo?.let { add(org.openmcx.client.location.LocationClient(c, org.openmcx.client.location.LocationService.MCVIDEO, it.psi)) }
        }
        services.forEach { c.handlers.add(0, it); it.start() }
        locations = services
        deviceLocation = org.openmcx.client.app.location.DeviceLocation(context.applicationContext, scope) { locations }.also { it.start() }
        // Emergency alerts, ahead of the services too.
        val alertListener = object : org.openmcx.client.alert.AlertListener {
            override fun alert(alert: org.openmcx.client.alert.EmergencyAlert, active: Boolean) = alertsChanged()
            override fun ownCancelled(service: org.openmcx.client.location.LocationService, group: String, by: String?) {
                log.add("your ${service.name} emergency alert on $group was cancelled by ${by ?: "the server"}")
                alertsChanged()
            }
            override fun area(notice: org.openmcx.client.alert.AreaNotice) {
                notify.areaNotice(notice)
                if (notice.alertArea) alertResult.value = if (!notice.entered) "You left the emergency alert area" else
                    "You entered an emergency alert area" + (notice.alerted?.let { ": alert raised on ${it.removePrefix("sip:").substringBefore('@')}" } ?: "")
                alertsChanged()
            }
        }
        alertHandlers = buildList {
            config.mcptt?.let { add(org.openmcx.client.alert.EmergencyAlerts(c, org.openmcx.client.location.LocationService.MCPTT, it.psi, it.userId)) }
            config.mcdata?.let { add(org.openmcx.client.alert.EmergencyAlerts(c, org.openmcx.client.location.LocationService.MCDATA, it.psi, it.userId)) }
            config.mcvideo?.let { add(org.openmcx.client.alert.EmergencyAlerts(c, org.openmcx.client.location.LocationService.MCVIDEO, it.psi, it.userId)) }
        }
        val center = org.openmcx.client.app.alert.AlertCenter(alertHandlers, { mcvideo }) { service -> groupsOf(service) }
        alerts = center
        alertHandlers.forEach { h ->
            h.listener = alertListener
            h.position = { lastPosition() }
            h.defaultGroup = { center.groupFor(h.service, selectedGroup.value) }
            c.handlers.add(0, h)
        }
        c.start()
        Log.i(TAG, "engine started for ${config.sip.publicUserId}")
        return c
    }

    /** The server address in use, the imported profile's, and whether the user changed it. */
    data class ServerAddress(val current: String?, val profile: String?, val edited: Boolean)
    @Volatile var serverAddress = ServerAddress(null, null, false)
        private set

    /**
     * Reach every server at [host] (null: the profile's address again) and restart: the profile's certificates,
     * identities and the rest stay as imported. Returns what happened, in a line.
     */
    fun setServerAddress(context: Context, host: String?): String {
        val provisioning = Provisioning(context)
        if (!provisioning.present) return "Import an MCX profile first"
        val text = runCatching {
            provisioning.setServerAddress(host)
            restart(context)
            if (host == null) "Server address reset to the profile's" else "Server address set to ${host.trim()}; reconnecting"
        }.getOrElse { "Server address not changed: ${it.message}" }
        log.add(text)
        return text
    }

    /**
     * Activate from an MCX Admin activation code (slice F2): sign in with [password], get the
     * SIP credential and a certificate for a key made in the Keystore, install the profile and
     * start. The key is made after sign-in, so a wrong password leaves the old profile working.
     */
    suspend fun activate(context: Context, link: String, password: String): McxClient? {
        val result = org.openmcx.client.provisioning.Activation.activate(link, password, key = { ClientCredentials.generate() })
        ClientCredentials.installCertificate(result.certificatePem)
        Provisioning(context).installActivation(result)
        return restart(context)
    }

    /** Stop the engine and start it again from the (new) provisioning. */
    @Synchronized
    fun restart(context: Context): McxClient? {
        client?.stop()
        deviceLocation?.stop(); deviceLocation = null; locations = emptyList()
        alertHandlers = emptyList(); alerts = null; notifications?.emergencyAlerts(emptyList())
        client = null; mcptt = null; mcdata = null; fd = null; mcvideo = null; video = null; telecom = null; fileAudio = null
        _inVideoCall.value = false
        history?.close(); history = null
        return start(context)
    }

    /** Record an SDS this user sent (the UI and the debug endpoint send through here or the service). */
    fun sent(message: SentSds) {
        history?.put(HistoryEntry("sds", message.messageId.toString(), "out", message.target, if (message.group) message.target else null,
            message.text, message.at))
    }

    private fun fileEntry(record: FdRecord) = HistoryEntry("file", record.id, if (record.incoming) "in" else "out", record.peer,
        record.group, "${record.metadata.name} (${AttachmentFiles.readableSize(record.metadata.size)})", record.at)

    /** Stage a picked file for sending, within the service limit (the target's own limit applies at sending). */
    fun stageAttachment(context: Context, uri: android.net.Uri) {
        clearAttachment()
        pendingAttachment.value = PendingAttachment("…")
        scope.launch {
            val limit = client?.config?.mcdata?.fd?.let { FdPolicy.effectiveLimit(it.copy(userMaxBytes = null), null) }
            pendingAttachment.value = runCatching { AttachmentFiles.stage(context, uri, limit) }.fold(
                { (file, metadata) -> PendingAttachment(metadata.name, file, metadata) },
                { PendingAttachment("attachment", error = it.message ?: "cannot read the file") })
        }
    }

    /** Drop the picked attachment (and its staged copy, which was not sent). */
    fun clearAttachment() {
        pendingAttachment.value?.file?.delete()
        pendingAttachment.value = null
    }

    /** Send the picked attachment; the staged copy stays as the sent file. */
    fun sendAttachment(target: String, group: Boolean) {
        val pending = pendingAttachment.value ?: return
        val file = pending.file ?: return
        val metadata = pending.metadata ?: return
        val service = fd ?: return
        pendingAttachment.value = null
        scope.launch {
            runCatching { service.send(target, group, file, metadata) }.fold(
                { history?.put(fileEntry(it)) },
                { e ->
                    log.add("attachment not sent: ${e.message}")
                    pendingAttachment.value = pending.copy(error = e.message ?: "not sent")
                })
        }
    }

    /**
     * Where this phone is, for an emergency alert: the platform's current fix (fused, GPS or network,
     * within [timeoutMs]), else the most recent known one; null when there is none or no permission.
     */
    @android.annotation.SuppressLint("MissingPermission") // checked by the caller (the alert asks for it)
    suspend fun currentLocation(context: Context, timeoutMs: Long = 10_000): org.openmcx.client.mcvideo.EmergencyLocation? {
        positionOverride?.let { return it }
        val manager = context.getSystemService(android.location.LocationManager::class.java) ?: return null
        val providers = listOf("fused", android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        val fix = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            kotlinx.coroutines.suspendCancellableCoroutine<android.location.Location?> { cont ->
                val provider = providers.firstOrNull() ?: run { cont.resume(null) {}; return@suspendCancellableCoroutine }
                val cancel = android.os.CancellationSignal()
                cont.invokeOnCancellation { cancel.cancel() }
                manager.getCurrentLocation(provider, cancel, context.mainExecutor) { cont.resume(it) {} }
            }
        } ?: providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        return fix?.let { org.openmcx.client.mcvideo.EmergencyLocation(it.latitude, it.longitude) }
    }

    /** The groups of [service], as provisioned (MCPTT: or from the user profile). */
    fun groupsOf(service: org.openmcx.client.location.LocationService): List<String> {
        val config = client?.config ?: return emptyList()
        return when (service) {
            org.openmcx.client.location.LocationService.MCPTT -> config.mcptt?.groups?.ifEmpty { client?.profile?.groups ?: emptyList() } ?: emptyList()
            org.openmcx.client.location.LocationService.MCDATA -> config.mcdata?.groups ?: emptyList()
            org.openmcx.client.location.LocationService.MCVIDEO -> config.mcvideo?.groups ?: emptyList()
        }
    }

    /** The last position known without waiting: the debug override, else the phone's last fix. */
    fun lastPosition(): org.openmcx.client.mcvideo.EmergencyLocation? = positionOverride
        ?: deviceLocation?.lastFix?.let { org.openmcx.client.mcvideo.EmergencyLocation(it.latitude, it.longitude) }

    /**
     * An alert was raised or cancelled, ours or someone else's: the notifications show each person's once, and the
     * location reporting of a service whose own alert is up uses its emergency triggers (TS 24.379 §13.3).
     */
    fun alertsChanged() {
        val center = alerts ?: return
        notifications?.emergencyAlerts(center.received())
        val own = center.own().keys + (center.ownAdhoc()?.second ?: emptyList())
        val emergencyCall = mcptt?.calls?.value?.any { it.state != "released" && it.priority != "normal" } == true
        for (l in locations) when (l.service) {
            org.openmcx.client.location.LocationService.MCPTT -> l.setEmergency(l.service in own || emergencyCall)
            org.openmcx.client.location.LocationService.MCDATA -> l.setEmergency(l.service in own)
            else -> Unit
        }
    }

    private fun alertService(label: String?) = label?.let { l ->
        org.openmcx.client.location.LocationService.entries.firstOrNull {
            org.openmcx.client.app.alert.AlertCenter.label(it).equals(l, true) || it.name.equals(l, true)
        } ?: throw IllegalArgumentException("no service $l")
    }

    private fun alertDone(text: String): String {
        log.add(text)
        alertResult.value = text
        alertsChanged()
        return text
    }

    /**
     * Raise (with where the phone is) or cancel the emergency alert on the selected group, through [only] ("MCPTT",
     * "MCData", "Video") or every service in turn; the result, in a line, is also [alertResult].
     */
    suspend fun emergencyAlert(on: Boolean, only: String? = null): String {
        val center = alerts ?: return alertDone("Emergency alert: not started")
        val service = alertService(only)
        if (!on) return alertDone(org.openmcx.client.app.alert.AlertCenter.summary(center.cancel(service), "cancel"))
        val location = appContext?.let { runCatching { currentLocation(it) }.getOrNull() } ?: lastPosition()
            ?: return alertDone("Emergency alert not sent: this phone's location is not known yet")
        return alertDone(org.openmcx.client.app.alert.AlertCenter.summary(center.raise(selectedGroup.value, service, location), "send"))
    }

    /** An ad hoc group emergency alert to the users [criteria] selects, through [only] or MCPTT and MCVideo. */
    suspend fun adhocAlert(criteria: String, only: String? = null): String {
        val center = alerts ?: return alertDone("Ad hoc alert: not started")
        val location = appContext?.let { runCatching { currentLocation(it) }.getOrNull() } ?: lastPosition()
        return alertDone(org.openmcx.client.app.alert.AlertCenter.summary(center.adhoc(criteria, alertService(only), location), "send")
            .replace("Emergency alert", "Ad hoc alert"))
    }

    /** Cancel [person]'s group alerts wherever they came through (if this user is authorised to). */
    suspend fun cancelAlertOf(person: String): String {
        val center = alerts ?: return alertDone("Emergency alert: not started")
        val outcomes = center.cancelOf(person)
        if (outcomes.isEmpty()) return alertDone("No alert of $person to cancel")
        return alertDone(org.openmcx.client.app.alert.AlertCenter.summary(outcomes, "cancel").replace("Emergency alert", "${person.replaceFirstChar { it.uppercase() }}'s alert"))
    }

    /**
     * The MCVideo ID for a user picked on the Call tab by MCPTT ID: the lab and the OpenMCX
     * provisioning name them alike (`sip:bob.mcptt@…`, `sip:bob.mcvideo@…`).
     */
    fun mcvideoId(target: String): String = when {
        ".mcvideo@" in target -> target
        ".mcptt@" in target -> target.replace(".mcptt@", ".mcvideo@")
        else -> target.substringBefore('@') + ".mcvideo@" + target.substringAfter('@')
    }

    /** Empties the event log and the SIP trace (the Logs tab's Clear logs). */
    /** Clear the Message tab: short data and files (not the History tab). */
    fun clearMessages() { mcdata?.clear(); fd?.clear() }

    /** Clear the History tab. */
    fun clearHistory() { history?.clear() }

    fun clearLogs() {
        log.clear()
        traceFile?.let { runCatching { it.writeText("") } }
    }

    /**
     * PTT from any source (screen, headset, hardware key). With a call up, request or release
     * the floor; with none, a press calls the selected group and then asks for the floor.
     */
    fun ptt(pressed: Boolean, source: String) {
        val service = mcptt ?: return
        log.add("ptt ${if (pressed) "pressed" else "released"} ($source)")
        scope.launch {
            val call = service.active
            when {
                call != null && pressed -> service.pttPress(call)
                call != null -> service.pttRelease(call)
                pressed -> {
                    val group = selectedGroup.value ?: return@launch
                    callFailure.value = null
                    val placed = runCatching { service.groupCall(group) }.getOrElse {
                        log.add("ptt: group call failed: ${it.message}")
                        callFailure.value = callFailureText(group, it)
                        return@launch
                    }
                    service.pttPress(placed)
                }
            }
        }
    }

    /** "Call to bob failed: busy (in another call)", from a failed call attempt. */
    fun callFailureText(target: String, error: Throwable): String {
        val message = error.message ?: ""
        // The MCPTT warning text (TS 24.379 §4.4, table 4.4.2-2) says more than the status code: a declined
        // manual-answer call is 480 with warning 110.
        val reason = when {
            "110 user declined" in message -> "declined"
            "175 call is forwarded" in message || "191 call forwarding" in message -> "forwarded"
            else -> null
        } ?: when ((error as? CallError)?.status) {
            486 -> "busy (in another call)"
            480 -> "not available (not registered)"
            403 -> "not allowed"
            404 -> "unknown user or group"
            408 -> "no answer"
            603 -> "declined"
            else -> error.message ?: "failed"
        }
        val who = target.removePrefix("sip:").substringBefore('@').removeSuffix(".mcptt")
        return "Call to $who failed: $reason"
    }

    /** Wait until the active call's floor is in [state] (for callers that need the result). */
    suspend fun awaitFloor(state: FloorState, timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { mcptt?.active?.media?.floor?.state?.first { it == state } } != null
}

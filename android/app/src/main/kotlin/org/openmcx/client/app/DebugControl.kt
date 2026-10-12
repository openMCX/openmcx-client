//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.app

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.openmcx.client.app.attachments.AttachmentFiles
import org.openmcx.client.mcdata.DispositionRequest
import org.openmcx.client.mcdata.FdPolicy
import org.openmcx.client.mcdata.FdRecord
import org.openmcx.client.mcdata.FdService
import org.openmcx.client.mcptt.Call
import org.openmcx.client.mcptt.Commencement
import org.openmcx.client.mcptt.FloorState
import org.openmcx.client.mcptt.McpttService
import org.openmcx.client.mcvideo.McvideoService
import org.openmcx.client.mcvideo.TxState
import org.openmcx.client.mcvideo.VideoCall
import org.openmcx.client.sip.Framed
import org.openmcx.client.sip.NameAddr
import org.openmcx.client.sip.SocketConnector
import org.openmcx.client.sip.StreamFramer
import org.openmcx.client.sip.UaIdentity
import org.openmcx.client.sip.UserAgent
import org.json.JSONObject
import java.net.InetAddress

/**
 * Debug-build control endpoint for the lab tests: a local abstract socket taking one JSON
 * request per line, reachable from the host with `adb forward tcp:N localabstract:mcxclient-control`.
 *
 * Request:  {"cmd": "status", "args": []}
 * Response: {"ok": true, "result": ...} or {"ok": false, "error": "..."}
 */
class DebugControl(private val context: Context) {

    suspend fun serve() = withContext(Dispatchers.IO) {
        val server = LocalServerSocket(SOCKET)
        Log.i(TAG, "control listening on @$SOCKET")
        server.use {
            while (true) {
                val socket = server.accept()
                launch { handle(socket) }
            }
        }
    }

    private suspend fun handle(socket: LocalSocket) = socket.use {
        val reader = socket.inputStream.bufferedReader()
        val writer = socket.outputStream.bufferedWriter()
        while (true) {
            val line = reader.readLine() ?: break
            val response = try {
                val request = JSONObject(line)
                val args = request.optJSONArray("args") ?: JSONArray()
                JSONObject().put("ok", true).put("result", execute(request.getString("cmd"), List(args.length()) { args.getString(it) }))
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("error", e.message ?: e.toString())
            }
            writer.write(response.toString())
            writer.newLine()
            writer.flush()
        }
    }

    private suspend fun execute(cmd: String, args: List<String>): Any = when (cmd) {
        "ping" -> "pong"
        "sip-probe" -> sipProbe()
        "resolve" -> JSONArray(InetAddress.getAllByName(args.single()).map { it.hostAddress })
        "status" -> status()
        // The process ends as on a crash (SIGKILL, as kill -9) just after the reply, so that the tests can check that
        // Android restarts the sticky service without root on the phone.
        "die" -> "dying".also { Thread { Thread.sleep(300); android.os.Process.killProcess(android.os.Process.myPid()) }.start() }
        // private <target> [auto|manual|force] [no-floor]
        "private" -> call { it.privateCall(args[0], when (args.getOrNull(1) ?: "auto") {
            "manual" -> Commencement.MANUAL; "force" -> Commencement.FORCED; else -> Commencement.AUTOMATIC }, floorControl = "no-floor" !in args,
            emergency = "emergency" in args) }
        // Functional aliases (TS 24.379 §9A), as the reference client: fa activate|takeover|deactivate <alias>..., fa use <alias>|none
        "fa" -> functionalAlias(args, org.openmcx.client.body.McService.MCPTT)
        // The same for MCData (TS 24.282 §22) and MCVideo (TS 24.281 §20), as the reference client's mcdata-fa and mcvideo-fa.
        "mcdata-fa" -> functionalAlias(args, org.openmcx.client.body.McService.MCDATA)
        "mcvideo-fa" -> functionalAlias(args, org.openmcx.client.body.McService.MCVIDEO)
        // sds-fa <alias> <text> [--disposition ...]: a one-to-one SDS to a functional alias (TS 24.282 §9.2.2.2.1 step 2b ii A)
        "sds-fa" -> sds(args, group = false, toAlias = true)
        // video-call-fa <alias> [manual] [notc]: a private video call to a functional alias (TS 24.281 §10.2.2.2.1, §20)
        "video-call-fa" -> videoJson(mcvideo().privateCallToAlias(args[0],
            if ("manual" in args) Commencement.MANUAL else Commencement.AUTOMATIC, transmissionControl = "notc" !in args))
        // private-fa <alias> [auto|manual|force] [no-floor]: a private call to a functional alias (§11.1.1.2.1.1 step 14c)
        "private-fa" -> call { it.privateCallToAlias(args[0], when (args.getOrNull(1) ?: "auto") {
            "manual" -> Commencement.MANUAL; "force" -> Commencement.FORCED; else -> Commencement.AUTOMATIC }, floorControl = "no-floor" !in args) }
        "mute" -> { mcptt().setMuted(args.firstOrNull() != "off"); "ok" }
        "group" -> call { it.groupCall(args.firstOrNull { a -> a.startsWith("sip:") } ?: Engine.client!!.config.mcptt!!.groups.first(),
            when { "emergency" in args -> "emergency"; "imminent" in args -> "imminent"; else -> "normal" }) }
        // priority emergency|imminent on|off: the state of the call in progress (§6.2.8)
        "priority" -> { mcptt().setPriority(args[0] == "emergency", args.getOrNull(1) != "off"); mcptt().active?.priority ?: "no call" }
        "adhoc" -> call { it.adhocCall(args) }
        "answer" -> mcptt().answer()
        "reject" -> mcptt().reject()
        "hangup" -> { mcptt().hangup(); "released" }
        // floor [release|queue] [wav]: press or release PTT; `queue` requests without holding PTT, so a queued grant waits
        // for floor-confirm (TS 24.380 §6.2.4.9.12) and lapses after T132 without it (§6.2.4.9.13). A WAV file (a path on
        // the device) is what the burst sends, as for ptt.
        "floor" -> {
            args.firstOrNull { it.endsWith(".wav") }?.let { Engine.fileAudio?.nextSource = java.io.File(it) }
            when (args.firstOrNull()) { "release" -> mcptt().pttRelease(); "queue" -> mcptt().pttPress(hold = false)
                else -> mcptt().pttPress() }
            "ok"
        }
        "floor-confirm" -> if (mcptt().confirmFloor()) "ok" else throw IllegalStateException("no queued floor grant awaiting confirmation")
        "queue-status" -> if (mcptt().requestQueuePosition()) "ok" else throw IllegalStateException("no queued floor request")
        "ptt" -> ptt(args[0], args.getOrNull(1)?.toDouble() ?: 5.0)
        "answer-mode" -> { mcptt().answerMode = args[0]; args[0] }
        "import" -> { Provisioning(context).import(java.io.File(args[0]).readText()); Engine.restart(context); "imported" }
        // What the QR code scanner gives the app, as text: the same loading path without the camera.
        // An MCX Admin activation code and the account's password (slice F2), without the camera.
        "activate" -> { Engine.activate(context, args[0], args[1]); "activated" }
        "import-qr" -> { Provisioning(context).import(org.openmcx.client.provisioning.QrProfile.load(args.joinToString(" "))); Engine.restart(context); "imported" }
        "audio-next" -> { Engine.fileAudio?.nextSource = java.io.File(args[0]); args[0] }
        "history" -> JSONArray(Engine.history?.recent(args.firstOrNull()?.toInt() ?: 20)?.map {
            JSONObject().put("kind", it.kind).put("id", it.id).put("direction", it.direction).put("peer", it.peer)
                .put("group", it.group).put("summary", it.summary).put("at", it.at)
        } ?: emptyList<JSONObject>())
        "telecom" -> JSONObject().put("error", Engine.telecom?.lastError).put("speaker", Engine.telecom?.speaker?.value)
            .put("endpoints", JSONArray(Engine.telecom?.endpointNames ?: emptyList<String>())).put("last_change", Engine.telecom?.lastEndpointResult)
            .put("audio", audioState())
        "location" -> JSONArray(Engine.locations.map { l ->
            JSONObject().put("service", l.service.lower).put("configured", l.reporter.configured).put("emergency", l.reporter.emergency)
                .put("triggers", JSONArray(l.reporter.configuration.triggers.map { "${it.id}:${it.kind}" }))
                .put("pending", JSONArray(l.reporter.pending.toList()))
                .put("sent", JSONArray(l.sent.map { r -> JSONObject().put("id", r.id).put("triggers", JSONArray(r.triggers))
                    .put("emergency", r.emergency).put("coordinate", r.location.coordinate != null)
                    .put("lat", r.location.coordinate?.lat).put("lon", r.location.coordinate?.lon)
                    .put("ecgi", r.location.servingEcgi).put("ncgi", r.location.servingNcgi).put("timestamp", r.location.timestamp) }))
                .put("events", JSONArray(l.events.toList())).put("last_request", l.lastRequestResult)
        })
        // location-request <mcptt|mcdata|mcvideo> <target MC ID>
        "location-request" -> {
            val l = Engine.locations.firstOrNull { it.service.lower == args[0] } ?: throw IllegalStateException("no ${args[0]} location client")
            JSONObject().put("request_id", l.request(listOf(args[1]))).put("result", l.lastRequestResult)
        }
        "located" -> JSONObject(Engine.locations.associate { l -> l.service.lower to JSONObject(l.located.value.mapValues { (_, u) ->
            JSONObject().put("lat", u.latitude).put("lon", u.longitude).put("cell", u.servingCell).put("timestamp", u.timestamp) }) })
        "device-location" -> JSONObject().put("fix", Engine.deviceLocation?.lastFix?.let {
                JSONObject().put("lat", it.latitude).put("lon", it.longitude).put("accuracy", it.accuracy).put("provider", it.provider).put("time", it.time) })
            .put("radio", JSONObject(Engine.deviceLocation?.radio?.mapKeys { it.key.name } ?: emptyMap<String, String>()))
            .put("error", Engine.deviceLocation?.lastError)
        "mic-test" -> micTest(args.getOrNull(0) ?: "voice", args.getOrNull(1)?.toDouble() ?: 3.0, args.getOrNull(2) == "ns")
        "speaker" -> { Engine.telecom?.setSpeaker(args.firstOrNull() != "off"); "requested" }
        "refresh" -> { Engine.client!!.refreshNow(args.firstOrNull() ?: "register"); "ok" }
        "drop-flow" -> { Engine.client!!.reconnect("dropped by the debug endpoint"); "ok" }
        "sds" -> sds(args, group = false)
        "sds-group" -> sds(args, group = true)
        "display" -> (Engine.mcdata ?: throw IllegalStateException("MCData not provisioned")).display(java.util.UUID.fromString(args[0]))
        "events" -> JSONArray(Engine.log.last(args.firstOrNull()?.toInt() ?: 50))
        // File distribution. Paths are on the device (adb push to /data/local/tmp, then chmod 644).
        "fd-send" -> fdSend(args, group = false)
        "fd-send-group" -> fdSend(args, group = true)
        "fd-download" -> fdJson(fd().download(args[0]))
        "fd-reject" -> fdJson(fd().reject(args[0]))
        "fd-cancel" -> { fd().cancel(args[0]); "ok" }
        "fd-files" -> JSONArray(fd().records.value.map(::fdJson))
        "fd-discover" -> fd().discover(args.firstOrNull())
        "fd-config" -> Engine.client!!.config.mcdata!!.fd.let { c ->
            JSONObject().put("content_server", c.contentServer).put("service_max_bytes", c.serviceMaxBytes)
                .put("user_max_bytes", c.userMaxBytes).put("device_max_bytes", c.deviceMaxBytes)
                .put("tdu2_seconds", c.tdu2Seconds).put("transfer_timeout_ms", c.transferTimeoutMs)
        }
        // Open a file in the phone's viewer, as the Open button does (for screenshots).
        "fd-open" -> {
            val record = fd().record(args[0]) ?: throw IllegalArgumentException("no file ${args[0]}")
            val intent = AttachmentFiles.viewIntent(context, record) ?: throw IllegalStateException("${record.metadata.name} is not openable")
            withContext(Dispatchers.Main) { context.startActivity(intent) }
            intent.type ?: ""
        }
        // MCVideo private calls.
        "video-call" -> videoJson(mcvideo().privateCall(Engine.mcvideoId(args[0]),
            if ("manual" in args) Commencement.MANUAL else Commencement.AUTOMATIC, transmissionControl = "notc" !in args,
            emergency = "emergency" in args))
        "video-emergency" -> { mcvideo().setPriority(emergency = true, enabled = args.firstOrNull() != "off"); mcvideo().active?.priority ?: "none" }
        "video-imminent" -> { mcvideo().setPriority(emergency = false, enabled = args.firstOrNull() != "off"); mcvideo().active?.priority ?: "none" }
        "video-chat" -> videoJson(mcvideo().joinChat(args.firstOrNull() ?: Engine.client!!.config.mcvideo!!.chatGroups.first()))
        // video-alert on|off [latitude longitude] [group]: without coordinates the phone's own location is used.
        "video-alert" -> {
            val on = args.firstOrNull() != "off"
            val numbers = args.drop(1).mapNotNull { it.toDoubleOrNull() }
            val group = args.firstOrNull { it.startsWith("sip:") && !it.contains(".mcvideo@") } ?: Engine.client!!.config.mcvideo!!.groups.first()
            // "for <MCVideo ID>": cancel that user's alert (an authorised user).
            val of = args.indexOf("for").takeIf { it >= 0 }?.let { args[it + 1] }
            val location = if (!on) null else if (numbers.size == 2) org.openmcx.client.mcvideo.EmergencyLocation(numbers[0], numbers[1])
                else Engine.currentLocation(context) ?: throw IllegalStateException("this phone's location is not known")
            mcvideo().sendAlert(group, on, location, of)
            JSONObject().put("alerting", JSONArray(mcvideo().alerting.value)).put("latitude", location?.latitude).put("longitude", location?.longitude)
        }
        // server-address <ip or host>   or   server-address reset
        "server-address" -> JSONObject().put("result", Engine.setServerAddress(context, args.firstOrNull()?.takeIf { it != "reset" }))
            .put("current", Engine.serverAddress.current).put("profile", Engine.serverAddress.profile).put("edited", Engine.serverAddress.edited)
        // The emergency alert of the Call tab: alert on|off [mcptt|mcdata|video]
        "alert" -> JSONObject().put("result", Engine.emergencyAlert(args.firstOrNull() != "off", args.getOrNull(1))).put("state", alertState())
        // adhoc-alert <criteria> [mcptt|video]   or   adhoc-alert cancel
        "adhoc-alert" -> if (args.firstOrNull() == "cancel") {
            val done = Engine.alertHandlers.filter { it.adhoc.value != null }.map { h -> h.service.lower to runCatching { h.cancelAdhoc() }.exceptionOrNull()?.message }
            Engine.alertsChanged()
            JSONObject().put("cancelled", JSONObject(done.toMap())).put("state", alertState())
        } else JSONObject().put("result", Engine.adhocAlert(args[0], args.getOrNull(1))).put("state", alertState())
        "alert-cancel-of" -> JSONObject().put("result", Engine.cancelAlertOf(args[0])).put("state", alertState())
        "alerts" -> alertState()
        // location-position <lat> <lon>   or   location-position clear: stand in for the phone's position.
        "location-position" -> {
            val point = if (args.firstOrNull() == "clear") null else org.openmcx.client.location.Point.at(args[0].toDouble(), args[1].toDouble(), null, 10.0)
            Engine.positionOverride = point?.let { org.openmcx.client.mcvideo.EmergencyLocation(args[0].toDouble(), args[1].toDouble()) }
            Engine.deviceLocation?.override = point
            JSONObject().put("position", point?.let { JSONObject().put("lat", it.lat).put("lon", it.lon) })
        }
        "video-alerts" -> JSONObject(mcvideo().alerts.value.mapValues { (_, a) ->
            JSONObject().put("group", a.group).put("latitude", a.location?.latitude).put("longitude", a.location?.longitude)
        })
        "video-tamper-pck" -> { mcvideo().tamperNextPck = true; "next PCK tampered" }
        "video-keys" -> JSONObject().put("kms", Engine.client?.videoKeys?.kms?.kmsUri).put("csk_id", Engine.client?.videoCsk?.csbId)
            .put("identity", Engine.client?.videoKeys?.identity).put("group_keys", JSONObject(mcvideo().groupKeyIds))
        "video-push" -> videoJson(mcvideo().push(Engine.mcvideoId(args[0])))
        "video-pull" -> videoJson(mcvideo().pull(Engine.mcvideoId(args[0])))
        "video-ambient" -> videoJson(mcvideo().ambient(Engine.mcvideoId(args[0]), local = args.getOrNull(1) == "local"))
        "video-push-server" -> videoJson(mcvideo().pushToServer())
        "video-pull-server" -> videoJson(mcvideo().pullFromServer(args[0]))
        "video-remote" -> { mcvideo().remoteTransmission(args[0], args.getOrNull(1) != "stop"); "requested" }
        "video-recordings" -> JSONArray(mcvideo().recordings.value)
        "video-group" -> videoJson(mcvideo().groupCall(args.firstOrNull { it.startsWith("sip:") } ?: Engine.client!!.config.mcvideo!!.groups.first(),
            when { "emergency" in args -> "emergency"; "imminent" in args -> "imminent"; else -> "normal" }))
        "video-transmit" -> videoTransmit(args.getOrNull(0)?.toDouble() ?: 5.0)
        "video-release" -> { mcvideo().stopTransmit(); "ok" }
        "video-hangup" -> { mcvideo().hangup(); "released" }
        "video-answer" -> mcvideo().answer()
        "video-decline" -> mcvideo().reject()
        "video-answer-mode" -> { mcvideo().answerMode = args[0]; args[0] }
        "video-camera" -> {
            // Choose the camera; during a transmission the switch happens at once.
            val video = Engine.video!!
            if ((args.firstOrNull() == "front") != video.useFrontCamera) video.flip()
            JSONObject().put("selected", if (video.useFrontCamera) "front" else "back").put("active", video.activeCamera)
        }
        "video-status" -> JSONObject().put("active", mcvideo().active?.let(::videoJson)).put("alerting", JSONArray(mcvideo().alerting.value)).put("decoded", Engine.video?.decoded?.get())
            .put("camera", Engine.video?.activeCamera).put("camera_frames", Engine.video?.activeCameraFrames).put("camera_switches", Engine.video?.switches?.get())
            .put("sending", Engine.video?.sending).put("device_orientation", Engine.video?.deviceOrientation)
            .put("remote_size", Engine.video?.remoteSize?.value?.let { "${it.width}x${it.height}" })
            .put("error", Engine.video?.lastError).put("history", JSONArray(mcvideo().history.map(::videoJson)))
        "video-hashes" -> (mcvideo().active ?: mcvideo().history.lastOrNull())?.media?.stats?.let {
            JSONObject().put("sent", JSONObject(it.sent.toMap())).put("received", JSONObject(it.received.toMap()))
        } ?: JSONObject()
        "fd-hash" -> fd().record(args[0])?.localPath?.let { FdService.sha256(java.io.File(it)) }
            ?: throw IllegalStateException("no local file for ${args[0]}")
        else -> throw IllegalArgumentException("unknown command: $cmd")
    }

    /** Open a flow to the SIP core with the provisioned TLS identity and send one unauthenticated REGISTER. */
    private suspend fun sipProbe(): Any {
        val provisioning = Provisioning(context)
        val config = provisioning.config()
        val ssl = if (config.sip.transport == "TLS") provisioning.sslContext() else null
        val connection = SocketConnector(config.sip.transport, ssl).connect(config.sip.proxy, config.sip.proxyPort)
        connection.use {
            val user = UserAgent(CoroutineScope(Dispatchers.IO), UaIdentity(config.sip.publicUserId, config.sip.privateUserId,
                config.sip.password, config.domain, "urn:uuid:00000000-0000-0000-0000-000000000000"))
            val request = user.newRequest("REGISTER", "sip:${config.domain}", NameAddr(config.sip.publicUserId))
            request.headers.add("Via", "SIP/2.0/${connection.transport} ${connection.localHost}:${connection.localPort};branch=${user.newBranch()};rport")
            request.headers.add("Expires", "0")
            connection.output.write(request.encode()); connection.output.flush()
            val framer = StreamFramer()
            val buffer = ByteArray(8192)
            while (true) {
                val n = connection.input.read(buffer)
                if (n < 0) throw IllegalStateException("closed by the SIP core")
                val message = framer.feed(buffer, n).filterIsInstance<Framed.Message>().firstOrNull()?.message ?: continue
                return JSONObject().put("local", "${connection.localHost}:${connection.localPort}")
                    .put("response", message.startLine()).put("challenge", message.headers["WWW-Authenticate"] ?: message.headers["Proxy-Authenticate"])
            }
        }
    }

    private fun mcptt(): McpttService = Engine.mcptt ?: throw IllegalStateException("MCPTT not provisioned")

    private suspend fun functionalAlias(args: List<String>, service: org.openmcx.client.body.McService): Any {
        val client = Engine.client ?: throw IllegalStateException("not started")
        val action = args.firstOrNull() ?: throw IllegalArgumentException("usage: activate|takeover|deactivate <alias>... | use <alias>|none")
        if (action == "use") client.useAlias(args.getOrNull(1)?.takeIf { it != "none" }, service)
        else client.aliasRequest(action, args.drop(1), service)
        return aliasJson(service)
    }

    private fun aliasJson(service: org.openmcx.client.body.McService = org.openmcx.client.body.McService.MCPTT): JSONObject {
        val a = Engine.client?.aliasesOf(service)?.value ?: return JSONObject()
        return JSONObject().put("available", JSONArray(a.available)).put("active", JSONArray(a.active.toList()))
            .put("take_over_possible", JSONArray(a.takeOverPossible.toList())).put("in_use", a.inUse ?: JSONObject.NULL)
            .put("subscribed", a.subscribed).put("last_action", a.lastAction ?: JSONObject.NULL)
            .put("last_status", a.lastStatus ?: JSONObject.NULL).put("last_warning", a.lastWarning ?: JSONObject.NULL)
            .put("last_outcomes", JSONObject(a.lastOutcomes))
    }

    private suspend fun call(start: suspend (McpttService) -> Call): Any = callJson(start(mcptt()))

    /**
     * Talk [wav] (a path on the device) in the active call: request the floor, send the file
     * for at most [seconds], release. Returns once the floor is released.
     */
    private suspend fun ptt(wav: String, seconds: Double): Any {
        val service = mcptt()
        val call = service.active ?: throw IllegalStateException("no call")
        val media = call.media ?: throw IllegalStateException("no media")
        val file = java.io.File(wav)
        if (!file.canRead()) throw IllegalArgumentException("cannot read $wav")
        Engine.fileAudio?.nextSource = file
        service.pttPress(call)
        val granted = withTimeoutOrNull(5_000) { media.floor.state.first { it == FloorState.HAS_PERMISSION } }
        if (granted == null) {
            Engine.fileAudio?.nextSource = null
            service.pttRelease(call)
            return JSONObject().put("granted", false).put("floor", call.floorEvents.lastOrNull())
        }
        withTimeoutOrNull((seconds * 1000).toLong()) { media.floor.state.first { it != FloorState.HAS_PERMISSION } }
        service.pttRelease(call)
        withTimeoutOrNull(3_000) { media.floor.state.first { it == FloorState.NO_PERMISSION } }
        val burst = media.bursts.lastOrNull { it.direction == "sent" }
        return JSONObject().put("granted", true).put("frames", burst?.payloadHashes?.size ?: 0)
    }

    /** `sds <target> <text…> [--disposition none|delivery|read|delivery-read]` */
    private suspend fun sds(args: List<String>, group: Boolean, toAlias: Boolean = false): Any {
        val service = Engine.mcdata ?: throw IllegalStateException("MCData not provisioned")
        val words = args.toMutableList()
        var disposition: DispositionRequest? = null
        val flag = words.indexOf("--disposition")
        if (flag >= 0) {
            disposition = when (words.getOrNull(flag + 1)) {
                "delivery" -> DispositionRequest.DELIVERY; "read" -> DispositionRequest.READ
                "delivery-read" -> DispositionRequest.DELIVERY_AND_READ; else -> null
            }
            words.subList(flag, minOf(flag + 2, words.size)).clear()
        }
        val sent = service.send(words.first(), words.drop(1).joinToString(" "), group, disposition, toAlias = toAlias)
        Engine.sent(sent)
        return JSONObject().put("message_id", sent.messageId.toString()).put("status", sent.status)
    }

    private fun mcvideo(): McvideoService = Engine.mcvideo ?: throw IllegalStateException("MCVideo not provisioned")

    /** Hold Transmit for [seconds]: request, wait for the grant, send, release; returns the counters. */
    private suspend fun videoTransmit(seconds: Double): Any {
        val service = mcvideo()
        val call = service.active ?: throw IllegalStateException("no video call")
        val media = call.media ?: throw IllegalStateException("no media")
        val before = media.stats.picturesSent.get()
        service.transmit(call)
        // Decided either way: granted, or rejected back to no permission.
        val granted = withTimeoutOrNull(5_000) { media.control.state.first { it != TxState.PENDING_REQUEST } } == TxState.HAS_PERMISSION
        if (!granted) { service.stopTransmit(call); return JSONObject().put("granted", false).put("events", JSONArray(call.events)) }
        kotlinx.coroutines.delay((seconds * 1000).toLong())
        service.stopTransmit(call)
        withTimeoutOrNull(3_000) { media.control.state.first { it == TxState.NO_PERMISSION } }
        return JSONObject().put("granted", true).put("pictures", media.stats.picturesSent.get() - before)
            .put("audio", media.stats.audioSent.get())
    }

    /** Record [seconds] from the microphone with an audio [source] (voice, mic, recognition, camcorder) and report its level. */
    @android.annotation.SuppressLint("MissingPermission")
    private fun micTest(source: String, seconds: Double, ns: Boolean): JSONObject {
        val src = when (source) {
            "mic" -> android.media.MediaRecorder.AudioSource.MIC
            "recognition" -> android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION
            "camcorder" -> android.media.MediaRecorder.AudioSource.CAMCORDER
            else -> android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION
        }
        val rate = 16000
        val record = android.media.AudioRecord(src, rate, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT,
            maxOf(android.media.AudioRecord.getMinBufferSize(rate, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT), 6400))
        val suppressor = if (ns && android.media.audiofx.NoiseSuppressor.isAvailable()) android.media.audiofx.NoiseSuppressor.create(record.audioSessionId) else null
        val buf = ShortArray((rate * seconds).toInt())
        record.startRecording()
        var filled = 0
        while (filled < buf.size) { val n = record.read(buf, filled, buf.size - filled); if (n <= 0) break; filled += n }
        record.stop(); suppressor?.release(); record.release()
        var sum = 0.0; var peak = 0
        for (i in 0 until filled) { sum += buf[i].toDouble() * buf[i]; peak = maxOf(peak, kotlin.math.abs(buf[i].toInt())) }
        return JSONObject().put("source", source).put("ns", suppressor != null).put("samples", filled)
            .put("rms", kotlin.math.sqrt(sum / maxOf(1, filled)).toInt()).put("peak", peak)
    }

    /** What the platform does with call audio: mode, the device it goes to, and the voice call volume. */
    private fun audioState(): JSONObject {
        val am = context.getSystemService(android.media.AudioManager::class.java)
        val device = if (android.os.Build.VERSION.SDK_INT >= 31) am.communicationDevice?.let { "${it.type}:${it.productName}" } else null
        return JSONObject().put("mode", am.mode).put("communication_device", device)
            .put("speakerphone", @Suppress("DEPRECATION") am.isSpeakerphoneOn)
            .put("voice_volume", am.getStreamVolume(android.media.AudioManager.STREAM_VOICE_CALL))
            .put("voice_volume_max", am.getStreamMaxVolume(android.media.AudioManager.STREAM_VOICE_CALL))
    }

    private fun alertState(): JSONObject {
        val center = Engine.alerts ?: return JSONObject()
        return JSONObject()
            .put("own", JSONObject(center.own().entries.associate { (s, g) -> s.lower to JSONArray(g.toList()) }))
            .put("adhoc", JSONObject(Engine.alertHandlers.mapNotNull { h -> h.adhoc.value?.let { h.service.lower to JSONObject().put("group", it.group).put("criteria", it.criteria) } }.toMap()))
            .put("received", JSONArray(center.received().map { a ->
                JSONObject().put("person", a.person).put("services", JSONArray(a.services.map { it.lower })).put("group", a.group)
                    .put("adhoc", a.adhoc).put("criteria", a.criteria).put("lat", a.latitude).put("lon", a.longitude)
            }))
            .put("notices", JSONArray(Engine.alertHandlers.flatMap { h -> h.notices.map { n ->
                JSONObject().put("service", n.service.lower).put("alert_area", n.alertArea).put("entered", n.entered).put("group", n.group).put("alerted", n.alerted)
            } }))
            .put("result", Engine.alertResult.value)
            .put("events", JSONArray(Engine.alertHandlers.flatMap { h -> h.events.toList() }))
    }

    private fun videoJson(call: VideoCall): JSONObject {
        val media = call.media
        return JSONObject().put("id", call.id).put("peer", call.peer).put("group", call.groupId).put("session_type", call.sessionType)
            .put("calling_alias", call.callingAlias).put("called_alias", call.calledAlias)
            .put("outgoing", call.outgoing).put("state", call.state).put("priority", call.priority).put("push_url", call.pushUrl)
            .put("protected", call.media?.local?.protected == true).put("pck_id", call.pckId)
            .put("control_protected", call.media?.protection?.protectsControl == true)
            .put("gmk_id", (call.media?.protection as? org.openmcx.client.mcvideo.CallProtection.Group)?.gmkId)
            .put("protection_failures", call.media?.protection?.failures)
            .put("ambient_type", call.ambientType).put("ambient_role", call.ambientRole).put("may_release", call.mayRelease)
            .put("reason", call.releaseReason).put("transmission_control", call.transmissionControl)
            .put("transmission", media?.takeIf { it.isStarted }?.control?.state?.value?.name)
            .put("transmitter", media?.takeIf { it.isStarted }?.control?.transmitter)
            .put("receiving", media?.takeIf { it.isStarted }?.control?.receiving)
            .put("events", JSONArray(call.events)).put("participants", JSONObject(call.participants))
            .put("pictures_sent", media?.stats?.picturesSent?.get()).put("pictures_received", media?.stats?.picturesReceived?.get())
            .put("undecoded", media?.stats?.undecoded?.get()).put("skipped_before_key", media?.stats?.skippedBeforeKey?.get())
            .put("lost", media?.stats?.lost).put("audio_sent", media?.stats?.audioSent?.get()).put("audio_received", media?.stats?.audioReceived?.get())
    }

    private fun fd(): FdService = Engine.fd ?: throw IllegalStateException("MCData not provisioned")

    /** `fd-send <target> <path on the device> [name] [type]`: stage the file as the picker would, then send it. */
    private suspend fun fdSend(args: List<String>, group: Boolean): Any {
        val service = fd()
        val source = java.io.File(args[1])
        if (!source.canRead()) throw IllegalArgumentException("cannot read ${args[1]}")
        val name = args.getOrNull(2) ?: source.name
        val limit = Engine.client!!.config.mcdata!!.fd.let { FdPolicy.effectiveLimit(it.copy(userMaxBytes = null), null) }
        val (file, metadata) = source.inputStream().use {
            AttachmentFiles.stage(AttachmentFiles.stagingDirectory(context), name,
                args.getOrNull(3) ?: AttachmentFiles.mediaType(null, name), source.length(), it, limit)
        }
        return fdJson(service.send(args[0], group, file, metadata))
    }

    private fun fdJson(r: FdRecord): JSONObject = JSONObject()
        .put("id", r.id).put("conversation_id", r.conversationId).put("direction", r.direction.name.lowercase())
        .put("peer", r.peer).put("group", r.group).put("name", r.metadata.name).put("type", r.metadata.mediaType)
        .put("size", r.metadata.size).put("sha256", r.metadata.sha256).put("url", r.url).put("state", r.state.name.lowercase())
        .put("transferred", r.transferred).put("error", r.error).put("mandatory", r.mandatory)
        .put("notifications", JSONObject(r.notifications.mapValues { it.value.name.lowercase() }))
        .put("openable", AttachmentFiles.openable(r) != null)

    private fun callJson(call: Call): JSONObject = JSONObject()
        .put("id", call.id).put("type", call.type.sessionType).put("outgoing", call.outgoing)
        .put("peer", call.peer).put("group", call.groupId).put("state", call.state).put("reason", call.releaseReason)
        .put("floor", call.media?.let { if (it.isStarted) it.floor.state.value.name else null })
        .put("floor_control", call.floorControl).put("muted", call.media?.muted == true).put("priority", call.priority)
        .put("calling_alias", call.callingAlias).put("called_alias", call.calledAlias)
        .put("talker", call.media?.let { if (it.isStarted) it.floor.talker else null })
        .put("queue_position", call.media?.takeIf { it.isStarted }?.floor?.queuePosition?.value)
        .put("pending_floor_confirmation", call.media?.takeIf { it.isStarted }?.floor?.offered?.value == true)
        .put("floor_events", JSONArray(call.floorEvents))
        .put("bursts", JSONArray(call.media?.bursts?.map { b ->
            JSONObject().put("direction", b.direction).put("ssrc", b.ssrc).put("frames", b.payloadHashes.size)
                .put("hashes", JSONArray(b.payloadHashes)).put("started_at", b.startedAt)
                .put("requested_at", b.requestedAt).put("granted_at", b.grantedAt)
                .put("first_playout_at", b.firstPlayoutAt).put("concealed", b.concealed).put("late", b.late)
        } ?: emptyList<JSONObject>()))

    private fun status(): JSONObject {
        val client = Engine.client ?: return JSONObject().put("phase", "not-started")
        val s = client.status.value
        fun service(state: org.openmcx.client.client.ServiceState) =
            JSONObject().put("authorised", state.authorised).put("affiliations", JSONObject(state.affiliations))
        return JSONObject().put("phase", s.phase).put("registered", s.registered)
            .put("user", client.config.sip.publicUserId)
            .put("mcptt", service(s.mcptt)).put("mcdata", service(s.mcdata)).put("mcvideo", service(s.mcvideo))
            .put("token_expires_at", s.tokenExpiresAt).put("token_refreshes", s.tokenRefreshes).put("password_logins", s.passwordLogins)
            .put("last_error", s.lastError)
            .put("flow", client.ua.flow?.connection?.let { "${it.localHost}:${it.localPort}" })
            .put("pid", android.os.Process.myPid())
            // The service's foreground types as the app sees them: Android 13's dumpsys does not print them.
            .put("fgs_types", (context as? android.app.Service)?.takeIf { android.os.Build.VERSION.SDK_INT >= 29 }?.foregroundServiceType ?: JSONObject.NULL)
            .put("calls", JSONArray(Engine.mcptt?.calls?.value?.map(::callJson) ?: emptyList<JSONObject>()))
            .put("functional_aliases", aliasJson())
            .put("mcdata_functional_aliases", aliasJson(org.openmcx.client.body.McService.MCDATA))
            .put("mcvideo_functional_aliases", aliasJson(org.openmcx.client.body.McService.MCVIDEO))
            .put("history", JSONArray(Engine.mcptt?.history?.map(::callJson) ?: emptyList<JSONObject>()))
            .put("inbox", JSONArray(Engine.mcdata?.inbox?.value?.map { m ->
                JSONObject().put("message_id", m.messageId.toString()).put("conversation_id", m.conversationId.toString())
                    .put("sender", m.sender).put("group", m.group).put("text", m.text)
                    .put("sender_alias", m.senderAlias).put("called_alias", m.calledAlias)
                    .put("disposition", m.disposition?.name).put("displayed", m.displayed).put("notified", m.notified?.name)
            } ?: emptyList<JSONObject>()))
            .put("files", JSONArray(Engine.fd?.records?.value?.map(::fdJson) ?: emptyList<JSONObject>()))
            .put("outbox", JSONArray(Engine.mcdata?.outbox?.value?.map { m ->
                JSONObject().put("message_id", m.messageId.toString()).put("target", m.target).put("group", m.group)
                    .put("text", m.text).put("status", m.status)
                    .put("notifications", JSONObject(m.notifications.mapValues { it.value.name }))
            } ?: emptyList<JSONObject>()))
    }

    companion object {
        const val SOCKET = "mcxclient-control"
        private const val TAG = "mcx.control"
    }
}

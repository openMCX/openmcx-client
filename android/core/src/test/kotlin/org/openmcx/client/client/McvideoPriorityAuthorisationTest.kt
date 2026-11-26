//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.openmcx.client.alert.EmergencyAlerts
import org.openmcx.client.body.Multipart
import org.openmcx.client.http.HttpClient
import org.openmcx.client.http.HttpResponse
import org.openmcx.client.idms.Tokens
import org.openmcx.client.location.LocationService
import org.openmcx.client.media.AmrWb
import org.openmcx.client.media.AudioDevices
import org.openmcx.client.media.AudioSink
import org.openmcx.client.media.AudioSource
import org.openmcx.client.media.EncodedPicture
import org.openmcx.client.media.SpeechCodecFactory
import org.openmcx.client.media.SpeechDecoder
import org.openmcx.client.media.SpeechEncoder
import org.openmcx.client.media.VideoCapture
import org.openmcx.client.media.VideoDevices
import org.openmcx.client.media.VideoFormat
import org.openmcx.client.media.VideoRenderer
import org.openmcx.client.mcvideo.EmergencyLocation
import org.openmcx.client.mcvideo.McvideoAuthorisation
import org.openmcx.client.mcvideo.McvideoAuthorisationTest
import org.openmcx.client.mcvideo.McvideoInfo
import org.openmcx.client.mcvideo.McvideoService
import org.openmcx.client.sip.SipRequest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * TS 24.281 §6.2.8.1.6 to §6.2.8.3.1 (S.8.1, S.12.2, S.5.13 of the suite): a priority request the MCVideo user profile
 * does not authorise is refused by the client and never reaches the wire; an authorised one is sent. Also the emergency
 * alert area notice of MCVideo (§11.2.1.5) raising the alert, and the ad hoc alert's location and rights (§12.1A.1.1).
 */
class McvideoPriorityAuthorisationTest {
    private val alice = "sip:alice.mcvideo@mcx.test"
    private val a = "sip:video-a@mcx.test"
    private val b = "sip:video-b@mcx.test"

    private fun config() = ClientConfig.parse("""{
        "name": "client-a", "domain": "mcx.test", "client_id": "urn:uuid:1",
        "sip": {"public_user_id": "sip:alice@mcx.test", "private_user_id": "alice", "password": "s",
                "proxy": "sip-core", "proxy_port": 5061, "transport": "TLS", "register_expires": 3600},
        "idms": {"issuer": "https://idms", "client_id": "mcx-client", "redirect_uri": "http://127.0.0.1:8904/callback"},
        "cms": "https://cms", "gms": "https://gms",
        "mcptt": {"user_id": "sip:alice.mcptt@mcx.test", "psi": "sip:mcptt@mcx.test", "groups": [],
                  "answer_mode": "automatic", "session_expires": 1800, "manual_answer_timeout": 20,
                  "subscribe_configuration": false, "subscribe_group_documents": false},
        "mcvideo": {"user_id": "$alice", "psi": "sip:mcvideo@mcx.test", "groups": []},
        "media": {"port_range": [20000, 20400], "amr_wb_mode": 2}}""")

    private class Documents(var profile: String, var group: String) : HttpClient {
        override suspend fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?) = when {
            url.endsWith("mcvideo-user-profile-1.xml") -> HttpResponse(200, emptyMap(), profile.toByteArray())
            "byGroupID" in url -> HttpResponse(200, emptyMap(), group.toByteArray())
            else -> HttpResponse(404, emptyMap(), ByteArray(0))
        }
    }

    private fun tokens() = Tokens(accessToken = "AT", idToken = "ID", expiresAt = System.currentTimeMillis() / 1000 + 3600,
        mcpttId = "sip:alice.mcptt@mcx.test", mcdataId = null, mcvideoId = alice, sipUri = null, clientId = null,
        refreshToken = "RT", nonce = "n")

    private fun waitFor(what: String, seconds: Int = 10, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + seconds * 1000L
        while (!condition()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what")
            Thread.sleep(50)
        }
    }

    private val devices = object : VideoDevices {
        override fun capture(format: VideoFormat, output: (EncodedPicture) -> Unit) = object : VideoCapture {
            override fun requestKeyFrame() {}; override fun close() {} }
        override fun renderer() = object : VideoRenderer {
            override fun render(nals: List<ByteArray>, presentationUs: Long) = true; override fun close() {} }
    }
    private val silence = object : AudioDevices {
        override fun source(duplex: Boolean) = object : AudioSource { override fun read(frame: ShortArray) = false; override fun close() {} }
        override fun sink() = object : AudioSink { override fun write(frame: ShortArray) {}; override fun close() {} }
    }
    private val codecs = object : SpeechCodecFactory {
        override fun encoder(mode: Int) = object : SpeechEncoder {
            override fun encode(pcm: ShortArray) = AmrWb.Frame(2, true, ByteArray((AmrWb.frameBits(2) + 7) / 8)); override fun close() {} }
        override fun decoder() = object : SpeechDecoder { override fun decode(frame: AmrWb.Frame?) = ShortArray(AmrWb.FRAME_SAMPLES); override fun close() {} }
    }

    private class Lab(val client: McxClient, val core: FakeSipCore, val video: McvideoService)

    private fun start(profile: String, group: String = McvideoAuthorisationTest.groupDocument(a, listOf(alice), listOf(alice))): Lab {
        val cores = CopyOnWriteArrayList<FakeSipCore>()
        val log = EventLog()
        val client = McxClient(CoroutineScope(SupervisorJob() + Dispatchers.Default), config(), Documents(profile, group), log = log,
            connector = { _, _ -> FakeSipCore(statusFor = { if (it.method == "INVITE") 403 else null }).also { cores += it } })
        client.tokens = tokens()
        client.serviceProtection = mapOf("mcptt" to (false to false), "mcvideo" to (false to false))
        client.serviceResourcePriorities = mapOf("mcvideo" to mapOf("emergency" to "mcpttp.15", "imminent" to "mcpttp.14", "normal" to "mcpttp.4"))
        client.start()
        try { waitFor("ready") { client.status.value.phase == "ready" && client.status.value.mcvideo.authorised } }
        catch (e: AssertionError) { throw AssertionError("not ready: ${client.status.value} ${log.last(20)}") }
        val video = McvideoService(client, devices, codecs, silence)
        waitFor("profile") { video.profile != null }
        return Lab(client, cores[0], video)
    }

    private fun Lab.sent(method: String) = core.requests.filter { it.method == method && it.uri == "sip:mcvideo@mcx.test" }
    private fun info(request: SipRequest) =
        McvideoInfo.parse(Multipart.parse(request.contentType, request.body).first { it.mediaType == McvideoInfo.CONTENT_TYPE }.text)

    /** Runs [block], which the profile must refuse with [action]; nothing is sent. */
    private fun Lab.refused(action: String, block: suspend () -> Unit) = runBlocking {
        val before = core.requests.size
        try { block(); fail("not refused: $action") } catch (e: McvideoAuthorisation.NotAuthorised) { assertEquals(action, e.action) }
        assertEquals("nothing sent for $action", before, core.requests.size)
        assertTrue(video.lastRefusal!!.contains(action))
    }

    /** Suite S.8.1: the rights "false" (or absent): no emergency or imminent peril INVITE, no alert MESSAGE. */
    @Test fun unauthorisedRightsSendNothing() {
        val lab = start(McvideoAuthorisationTest.profile(alice, mapOf("allow-emergency-group-call" to false,
            "allow-imminent-peril-call" to false, "allow-activate-emergency-alert" to false, "allow-emergency-private-call" to false,
            "allow-cancel-group-emergency" to false, "allow-cancel-imminent-peril" to false)))
        lab.refused("allow-emergency-group-call") { lab.video.groupCall(a, priority = "emergency") }
        lab.refused("allow-imminent-peril-call") { lab.video.groupCall(a, priority = "imminent") }
        lab.refused("allow-activate-emergency-alert") { lab.video.sendAlert(a, on = true, location = EmergencyLocation(48.0, 2.0)) }
        lab.refused("allow-emergency-private-call") { lab.video.privateCall("sip:bob.mcvideo@mcx.test", emergency = true) }
        lab.refused("allow-cancel-imminent-peril") { lab.video.cancelImminent(a) }
        // Suite S.5.13: no ad hoc rights in the profile.
        lab.refused("allow-emergency-adhoc-group-call") { lab.video.adhocCall(listOf("sip:bob.mcvideo@mcx.test"), priority = "emergency") }
        lab.refused("allow-imminent-peril-adhoc-group-call") { lab.video.adhocCall(listOf("sip:bob.mcvideo@mcx.test"), priority = "imminent") }
        lab.client.stop()
    }

    /** Suite S.12.2: the rights granted, the entries "DedicatedGroup" B: group A refused, group B sent. */
    @Test fun dedicatedGroupEntries() {
        val lab = start(McvideoAuthorisationTest.profile(alice, mapOf("allow-emergency-group-call" to true,
            "allow-imminent-peril-call" to true, "allow-activate-emergency-alert" to true),
            emergency = "DedicatedGroup", emergencyUri = b, imminent = "DedicatedGroup", imminentUri = b, alert = "DedicatedGroup", alertUri = b))
        lab.refused("EmergencyCall/MCVideoGroupInitiation") { lab.video.groupCall(a, priority = "emergency") }
        lab.refused("ImminentPerilCall/MCVideoGroupInitiation") { lab.video.groupCall(a, priority = "imminent") }
        lab.refused("EmergencyAlert") { lab.video.sendAlert(a, on = true, location = EmergencyLocation(48.0, 2.0)) }
        // Authorised: the INVITE goes out (the fake core refuses it with 403).
        val error = runCatching { runBlocking { lab.video.groupCall(b, priority = "emergency") } }.exceptionOrNull()
        assertTrue("$error", error !is McvideoAuthorisation.NotAuthorised)
        val invite = lab.sent("INVITE").single()
        assertEquals(true, info(invite).emergency)
        assertEquals(b, info(invite).requestUri)
        runBlocking { lab.video.sendAlert(b, on = true, location = EmergencyLocation(48.0, 2.0)) }
        assertEquals(true, info(lab.sent("MESSAGE").single()).alert)
        lab.client.stop()
    }

    /** §6.2.8.1.6 2) b): "UseCurrentlySelectedGroup" alerts follow the group document's <mcvideo-allow-emergency-alert>. */
    @Test fun currentlySelectedGroupAlertFollowsTheGroupDocument() {
        val rights = mapOf("allow-activate-emergency-alert" to true)
        val denied = start(McvideoAuthorisationTest.profile(alice, rights), McvideoAuthorisationTest.groupDocument(a, listOf(alice), listOf("sip:bob.mcvideo@mcx.test")))
        denied.refused("EmergencyAlert") { denied.video.sendAlert(a, on = true, location = EmergencyLocation(48.0, 2.0)) }
        denied.client.stop()
        val granted = start(McvideoAuthorisationTest.profile(alice, rights))
        runBlocking { granted.video.sendAlert(a, on = true, location = EmergencyLocation(48.0, 2.0)) }
        assertEquals(1, granted.sent("MESSAGE").size)
        granted.client.stop()
    }

    /**
     * TS 24.281 §11.2.1.5 1) a) ii): the MCVideo emergency alert area notice raises the alert on the selected group,
     * through the McvideoService, unless the user's alert is up; §11.2.1.5 2) the 200 (OK). §12.1A.1.1 5): an ad hoc
     * alert without the phone's location is not sent; §6.2.8.1.27: nor without the right.
     */
    @Test fun alertAreaNoticeAndAdhocAlert() {
        val lab = start(McvideoAuthorisationTest.profile(alice, mapOf("allow-activate-emergency-alert" to true)))
        val alerts = EmergencyAlerts(lab.client, LocationService.MCVIDEO, "sip:mcvideo@mcx.test", alice)
        val raised = CopyOnWriteArrayList<String>()
        alerts.defaultGroup = { a }
        alerts.position = { EmergencyLocation(48.05, 2.05) }
        alerts.serviceGroupAlert = object : EmergencyAlerts.ServiceGroupAlert {
            override fun alerting() = lab.video.alerting.value
            override suspend fun raise(group: String, location: EmergencyLocation) { raised += group; lab.video.sendAlert(group, true, location) }
        }
        alerts.authoriseAdhoc = { cancel -> lab.video.authoriseAdhocAlert(cancel) }
        lab.client.handlers.add(0, alerts)
        val notice = "<?xml version=\"1.0\"?><mcvideoinfo xmlns=\"urn:3gpp:ns:mcvideoInfo:1.0\"><mcvideo-Params>" +
            "<mcvideo-request-uri type=\"Normal\"><mcvideoURI>$alice</mcvideoURI></mcvideo-request-uri>" +
            "<anyExt><emergency-alert-area-ind>true</emergency-alert-area-ind></anyExt></mcvideo-Params></mcvideoinfo>"
        lab.core.message(McvideoInfo.CONTENT_TYPE, notice, from = "sip:mcvideo@mcx.test")
        assertEquals(200, lab.core.answer().status)
        waitFor("the alert") { lab.sent("MESSAGE").isNotEmpty() }
        assertEquals(listOf(a), raised.toList())
        val alert = lab.sent("MESSAGE").single()
        assertEquals(true, info(alert).alert)
        assertNotNull(Multipart.parse(alert.contentType, alert.body).firstOrNull { it.mediaType == LocationService.MCVIDEO.mime })
        waitFor("alerting") { lab.video.alerting.value == setOf(a) }
        // A second notice while the alert is up raises nothing.
        lab.core.message(McvideoInfo.CONTENT_TYPE, notice, from = "sip:mcvideo@mcx.test")
        assertEquals(200, lab.core.answer().status)
        Thread.sleep(300)
        assertEquals(1, lab.sent("MESSAGE").size)
        assertEquals(listOf(a, null), alerts.notices.filter { it.alertArea && it.entered }.map { it.alerted })
        // The ad hoc alert: without the phone's location nothing is sent.
        val before = lab.core.requests.size
        assertTrue(runCatching { runBlocking { alerts.sendAdhoc("affiliated:$a", null) } }.exceptionOrNull()?.message!!.contains("location"))
        assertEquals(before, lab.core.requests.size)
        lab.client.stop()
    }
}

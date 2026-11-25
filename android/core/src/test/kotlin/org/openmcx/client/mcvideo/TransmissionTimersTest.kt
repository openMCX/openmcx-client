//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.mcvideo

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TS 24.581 §6.2.4, §6.2.5, §11.1.1 and §11.2.1: the participant's timers T100 to T104 and counters C100 to C104. */
@OptIn(ExperimentalCoroutinesApi::class)
class TransmissionTimersTest {
    private val sent = mutableListOf<TransmissionMessage>()
    private val events = mutableListOf<String>()
    private val listener = object : TransmissionListener {
        override fun granted(audioSsrc: Long?, videoSsrc: Long?, durationSeconds: Int?) { events += "granted $audioSsrc $videoSsrc" }
        override suspend fun stopTransmitting() { events += "stop" }
        override fun requestFailed() { events += "failed" }
        override fun timeout(what: String) { events += what }
        override fun queued(position: Int?) { events += "queued $position" }
        override fun ended(byServer: Boolean) { events += "ended $byServer" }
        override fun reception(receiving: Boolean, rejectCause: Int?) { events += "reception $receiving" }
    }
    private val timers = TransmissionTimers(t100Ms = 400, t101Ms = 500, t102Ms = 600, t103Ms = 700, t104Ms = 800,
        c100 = 3, c101 = 2, c102 = 2, c103 = 3, c104 = 2)

    private fun TestScope.participant(priority: Int? = null, receptionPriority: Int? = null) =
        TransmissionParticipant(backgroundScope, 42, { sent += it }, listener, priority, timers = timers, receptionPriority = receptionPriority)
    private fun of(type: TxType) = sent.count { it.type == type }
    private fun server(type: TxType, vararg fields: Pair<Int, ByteArray>) = TransmissionMessage(type, 42, fields = fields.toList())

    /** §6.2.4.3.2, §6.2.4.4.3, §6.2.4.4.4: sent, then again at each T100 expiry up to C100, then no permission. */
    @Test fun t100RetransmitsUpToC100() = runTest {
        val p = participant()
        p.start(); p.request(); runCurrent()
        assertEquals(1, of(TxType.REQUEST))
        advanceTimeBy(401); runCurrent(); assertEquals(2, of(TxType.REQUEST))
        advanceTimeBy(400); runCurrent(); assertEquals(3, of(TxType.REQUEST))
        assertEquals(TxState.PENDING_REQUEST, p.state.value)
        advanceTimeBy(400); runCurrent()
        assertEquals(3, of(TxType.REQUEST))
        assertEquals(TxState.NO_PERMISSION, p.state.value)
        assertEquals(listOf("transmission request timeout", "failed"), events)
    }

    /** §6.2.4.4.6 step 4: the grant stops T100. */
    @Test fun grantStopsT100() = runTest {
        val p = participant()
        p.start(); p.request(); runCurrent()
        p.receive(server(TxType.GRANTED))
        advanceTimeBy(5000); runCurrent()
        assertEquals(1, of(TxType.REQUEST))
        assertEquals(TxState.HAS_PERMISSION, p.state.value)
    }

    /** §6.2.4.2.2 step 4: an implicit request starts T100 without sending; its expiry sends the Transmission Request. */
    @Test fun implicitRequestStartsT100WithoutSending() = runTest {
        val p = participant()
        p.start(implicit = true); runCurrent()
        assertEquals(0, of(TxType.REQUEST))
        advanceTimeBy(401); runCurrent()
        assertEquals(1, of(TxType.REQUEST))
        p.receive(server(TxType.GRANTED))
        assertEquals(TxState.HAS_PERMISSION, p.state.value)
    }

    /**
     * §6.2.4.5.3, §6.2.4.6.2-6.2.4.6.4: the end of a transmission is a Transmission end request (never a Transmission
     * Release on-network), sent again at T101 expiry, and the response stops T101.
     */
    @Test fun endRequestWithT101AndResponse() = runTest {
        val p = participant()
        p.start(); p.request(); runCurrent(); p.receive(server(TxType.GRANTED))
        p.release(); runCurrent()
        assertEquals(1, of(TxType.END_REQUEST)); assertEquals(0, of(TxType.RELEASE))
        assertEquals(TxState.PENDING_RELEASE, p.state.value)
        advanceTimeBy(501); runCurrent(); assertEquals(2, of(TxType.END_REQUEST))
        p.receive(server(TxType.END_RESPONSE))
        assertEquals(TxState.NO_PERMISSION, p.state.value)
        advanceTimeBy(5000); runCurrent(); assertEquals(2, of(TxType.END_REQUEST))
        assertTrue("ended false" in events)
    }

    /** §6.2.4.6.3: T101 expired C101 times: no permission, with the timeout reported. */
    @Test fun t101ExpiresC101Times() = runTest {
        val p = participant()
        p.start(); p.request(); runCurrent(); p.receive(server(TxType.GRANTED))
        p.release(); runCurrent()
        advanceTimeBy(1001); runCurrent()
        assertEquals(2, of(TxType.END_REQUEST))
        assertEquals(TxState.NO_PERMISSION, p.state.value)
        assertTrue("transmission end request timeout" in events)
    }

    /** §6.2.4.5.5 step 4a: a revoke is answered by a Transmission end request with T101. */
    @Test fun revokeSendsEndRequest() = runTest {
        val p = participant()
        p.start(); p.request(); runCurrent(); p.receive(server(TxType.GRANTED))
        p.receive(server(TxType.REVOKED, TxField.REJECT_CAUSE to TransmissionMessage.u16(1))); runCurrent()
        assertEquals(1, of(TxType.END_REQUEST))
        assertEquals(TxState.PENDING_RELEASE, p.state.value)
    }

    /** §6.2.4.5.7: the server's Transmission End Request is answered with the response, and the permission ends. */
    @Test fun serverEndRequestIsAnswered() = runTest {
        val p = participant()
        p.start(); p.request(); runCurrent(); p.receive(server(TxType.GRANTED))
        p.receive(server(TxType.END_REQUEST))
        assertEquals(1, of(TxType.END_RESPONSE))
        assertEquals(TxState.NO_PERMISSION, p.state.value)
        assertTrue(events.containsAll(listOf("stop", "ended true")))
    }

    /** §6.2.4.4.5, §6.2.4.9.3, §6.2.4.9.7, §6.2.4.9.8: queued, Queue Position Request with T102, then the end request. */
    @Test fun queuedWithT102() = runTest {
        val p = participant()
        p.start(); p.request(); runCurrent()
        p.receive(server(TxType.QUEUE_POSITION_INFO, TxField.QUEUE_INFO to byteArrayOf(2, 0)))
        assertEquals(TxState.QUEUED, p.state.value); assertEquals(2, p.queuePosition)
        advanceTimeBy(5000); runCurrent(); assertEquals(1, of(TxType.REQUEST)) // T100 stopped
        assertTrue(p.queuePositionRequest()); runCurrent()
        assertEquals(1, of(TxType.QUEUE_POSITION_REQUEST))
        advanceTimeBy(601); runCurrent(); assertEquals(2, of(TxType.QUEUE_POSITION_REQUEST))
        advanceTimeBy(600); runCurrent()
        assertTrue("queued timeout" in events)
        assertEquals(1, of(TxType.END_REQUEST))
        assertEquals(TxState.PENDING_RELEASE, p.state.value)
    }

    /** §6.2.4.9.9: granted while queued; §6.2.4.9.6: a cancel notify while queued ends the request. */
    @Test fun queuedThenGrantedOrCancelled() = runTest {
        val p = participant()
        p.start(); p.request(); runCurrent()
        p.receive(server(TxType.QUEUE_POSITION_INFO, TxField.QUEUE_INFO to byteArrayOf(1, 0)))
        p.receive(server(TxType.GRANTED))
        assertEquals(TxState.HAS_PERMISSION, p.state.value); assertNull(p.queuePosition)
        val q = participant()
        q.start(); q.request(); runCurrent()
        q.receive(server(TxType.QUEUE_POSITION_INFO, TxField.QUEUE_INFO to byteArrayOf(1, 0)))
        q.receive(server(TxType.CANCEL_REQUEST_NOTIFY))
        assertEquals(TxState.NO_PERMISSION, q.state.value)
        assertFalse(q.queuePositionRequest())
    }

    /** §6.2.5.3.3, §6.2.5.4.3, §6.2.5.4.4: the Receive Media Request with T103 up to C103, then 'terminated'. */
    @Test fun t103RetransmitsUpToC103() = runTest {
        val p = participant()
        p.start()
        p.receive(server(TxType.MEDIA_RECEPTION_NOTIFICATION)); runCurrent()
        assertEquals(1, of(TxType.RECEIVE_MEDIA_REQUEST)); assertEquals(RxState.PENDING_REQUEST, p.reception.value)
        advanceTimeBy(701); runCurrent(); advanceTimeBy(700); runCurrent()
        assertEquals(3, of(TxType.RECEIVE_MEDIA_REQUEST))
        advanceTimeBy(700); runCurrent()
        assertEquals(RxState.TERMINATED, p.reception.value)
        assertTrue("receive media request timeout" in events)
    }

    /** §6.2.5.4.5 then §6.2.5.5.3 and §6.2.5.6.4: the response stops T103; the reception end request runs T104. */
    @Test fun receptionEndWithT104() = runTest {
        val p = participant()
        p.start()
        p.receive(server(TxType.MEDIA_RECEPTION_NOTIFICATION)); runCurrent()
        p.receive(server(TxType.RECEIVE_MEDIA_RESPONSE))
        assertEquals(RxState.HAS_PERMISSION, p.reception.value)
        advanceTimeBy(5000); runCurrent(); assertEquals(1, of(TxType.RECEIVE_MEDIA_REQUEST))
        assertTrue(p.endReception()); runCurrent()
        assertEquals(1, of(TxType.RECEPTION_END_REQUEST))
        advanceTimeBy(801); runCurrent(); assertEquals(2, of(TxType.RECEPTION_END_REQUEST))
        p.receive(server(TxType.RECEPTION_END_RESPONSE))
        assertEquals(RxState.TERMINATED, p.reception.value)
        advanceTimeBy(5000); runCurrent(); assertEquals(2, of(TxType.RECEPTION_END_REQUEST))
    }

    /** §6.2.5.6.3: T104 expired C104 times: 'terminated'. */
    @Test fun t104ExpiresC104Times() = runTest {
        val p = participant()
        p.start()
        p.receive(server(TxType.MEDIA_RECEPTION_NOTIFICATION)); runCurrent()
        p.receive(server(TxType.RECEIVE_MEDIA_RESPONSE))
        p.endReception(); runCurrent()
        advanceTimeBy(1601); runCurrent()
        assertEquals(RxState.TERMINATED, p.reception.value)
        assertTrue("receive media release timeout" in events)
    }

    /** §6.2.5.5.5: the server's Media reception end request is answered. */
    @Test fun serverReceptionEndIsAnswered() = runTest {
        val p = participant()
        p.start()
        p.receive(server(TxType.MEDIA_RECEPTION_NOTIFICATION)); runCurrent()
        p.receive(server(TxType.RECEIVE_MEDIA_RESPONSE))
        p.receive(server(TxType.RECEPTION_END_REQUEST))
        assertEquals(1, of(TxType.RECEPTION_END_RESPONSE))
        assertEquals(RxState.TERMINATED, p.reception.value)
    }

    /** §6.2.4.7.2: the call's release stops every timer. */
    @Test fun stopEndsTheTimers() = runTest {
        val p = participant()
        p.start(); p.request(); runCurrent()
        p.stop()
        advanceTimeBy(5000); runCurrent()
        assertEquals(1, of(TxType.REQUEST))
    }

    /** §6.2.4.3.2 step 2a and §6.2.5.3.3 step 1a: the priority fields of the requests. */
    @Test fun requestsCarryThePriorities() = runTest {
        val p = participant(priority = 5, receptionPriority = 4)
        p.start(); p.request(); runCurrent()
        assertEquals(5, sent.first { it.type == TxType.REQUEST }.field(TxField.PRIORITY)!![0].toInt())
        p.receive(server(TxType.MEDIA_RECEPTION_NOTIFICATION)); runCurrent()
        assertEquals(4, sent.first { it.type == TxType.RECEIVE_MEDIA_REQUEST }.field(TxField.RECEPTION_PRIORITY)!![0].toInt())
        val q = participant(); q.start(); q.request(); runCurrent()
        assertNull(sent.last { it.type == TxType.REQUEST }.field(TxField.PRIORITY))
    }

    /** §12.1.2.2 NOTE 3, §14.2.4: the 200 (OK)'s "mc_granted" grants; the later Transmission Granted is not a second grant. */
    @Test fun grantInTheAnswer() = runTest {
        val p = participant()
        p.start(implicit = true)
        p.grantedInAnswer(0x11, 0x22)
        assertEquals(TxState.HAS_PERMISSION, p.state.value)
        p.receive(server(TxType.GRANTED))
        assertEquals(listOf("granted 17 34"), events)
        advanceTimeBy(5000); runCurrent(); assertEquals(0, of(TxType.REQUEST))
    }

    @Test fun zeroTimersAreRefused() {
        assertTrue(runCatching { TransmissionTimers(t100Ms = 0) }.isFailure)
        assertTrue(runCatching { TransmissionTimers(c104 = 0) }.isFailure)
    }
}

/** TS 24.581 §14.2.3, §14.2.6, §14.3.3, §14.4, §12.1.2: the priorities offered, negotiated and requested. */
class TransmissionPriorityTest {
    @Test fun offeredOnlyWhenNotTheDefaultAndAtMostTwoDigits() {
        assertNull(TransmissionPriority.offered(0)); assertEquals(7, TransmissionPriority.offered(7)); assertEquals(99, TransmissionPriority.offered(200))
    }

    @Test fun negotiated() {
        assertEquals(3, TransmissionPriority.negotiated(true, null, 3)) // the answerer returns the offer's
        assertEquals(3, TransmissionPriority.negotiated(false, 7, 3))
        assertEquals(7, TransmissionPriority.negotiated(false, 7, 9)) // at most the offered value
        assertNull(TransmissionPriority.negotiated(false, null, 3)) // not offered: discarded
        assertNull(TransmissionPriority.negotiated(false, 7, null))
    }

    @Test fun requested() {
        assertEquals(3, TransmissionPriority.request(7, 3)); assertEquals(2, TransmissionPriority.request(2, 3))
        assertNull(TransmissionPriority.request(0, 3)); assertNull(TransmissionPriority.request(7, null))
    }

    private val local = LocalVideoMedia("10.0.0.2", 20000, 20004, 20002, 111, 222, 333)

    /** §14.2.2-14.2.4: the offer's parameters; mc_granted only with an implicit request. */
    @Test fun offerParameters() {
        val offer = McvideoSdp.offer(local.copy(priority = 7, receptionPriority = 7, implicitRequest = true, granted = true, queueing = true))
        assertTrue(offer, offer.contains("a=fmtp:MCVideo mc_transmission_ssrc=111;mc_queueing;mc_priority=7;mc_reception_priority=7;mc_implicit_request;mc_granted\r\n"))
        assertFalse(McvideoSdp.offer(local.copy(granted = true)).contains("mc_granted"))
        val parsed = McvideoSdp.remote(offer)
        assertEquals(7, parsed.priority); assertEquals(7, parsed.receptionPriority)
        assertTrue(parsed.granted && parsed.implicitRequest && parsed.queueing)
    }

    /** §14.3.3, §14.3.1: the answer returns the offered priorities and queueing only if both have it, never mc_granted. */
    @Test fun answerParameters() {
        val offered = McvideoSdp.remote(McvideoSdp.offer(local.copy(priority = 5, receptionPriority = 4, queueing = true, implicitRequest = true, granted = true)))
        val answer = McvideoSdp.answer(offered, local.copy(controlSsrc = 9))
        assertTrue(answer, answer.contains("a=fmtp:MCVideo mc_transmission_ssrc=9;mc_priority=5;mc_reception_priority=4\r\n"))
        assertTrue(McvideoSdp.answer(offered, local.copy(controlSsrc = 9, queueing = true)).contains("mc_queueing"))
    }

    /** §14.4: the server's answer granting the implicit request with its SSRCs. */
    @Test fun grantedAnswerIsRead() {
        val answer = "v=0\r\no=- 5 1 IN IP4 172.20.0.9\r\ns=MCVideo\r\nc=IN IP4 172.20.0.9\r\nt=0 0\r\n" +
            "m=audio 4200 RTP/AVP 100\r\na=rtpmap:100 AMR-WB/16000/1\r\na=rtcp:4201\r\n" +
            "m=video 6200 RTP/AVP 101\r\na=rtpmap:101 H264/90000\r\na=fmtp:101 profile-level-id=42e01f;packetization-mode=1\r\na=rtcp:6201\r\n" +
            "m=application 5200 udp MCVideo\r\na=fmtp:MCVideo mc_transmission_ssrc=4242;mc_implicit_request;mc_granted;mc_audio_ssrc=17;mc_video_ssrc=34\r\n"
        val r = McvideoSdp.remote(answer)
        assertTrue(r.granted && r.implicitRequest)
        assertEquals(17L, r.grantedAudioSsrc); assertEquals(34L, r.grantedVideoSsrc)
    }
}

/** RFC 5373 §2 and the MCVideo user profile (TS 24.484 §9.3.2.1). */
class McvideoRulesTest {
    @Test fun answerModeIsComparedByToken() {
        assertEquals("auto", McvideoService.answerModeToken("Auto;require"))
        assertEquals("manual", McvideoService.answerModeToken(" Manual ; require"))
        assertEquals("auto", McvideoService.answerModeToken("auto"))
        assertNull(McvideoService.answerModeToken(null)); assertNull(McvideoService.answerModeToken(""))
    }

    @Test fun profilePriorityAndPrivateCallKms() {
        val xml = """<?xml version="1.0"?><mcvideo-user-profile xmlns="urn:3gpp:ns:mcvideo:user-profile:1.0" XUI-URI="sip:alice.mcvideo@mcx.test" profile-index="1">
            <Common><MCVideoUserID><uri-entry>sip:alice.mcvideo@mcx.test</uri-entry></MCVideoUserID>
            <PrivateCall><PrivateCallList><PrivateCallOnNetwork><PrivateCallURI><uri-entry>sip:bob.mcvideo@mcx.test</uri-entry></PrivateCallURI>
            <PrivateCallKMSURI><PrivateCallKMSURI><uri-entry>https://kms2.mcx.test</uri-entry></PrivateCallKMSURI></PrivateCallKMSURI></PrivateCallOnNetwork>
            <PrivateCallOnNetwork><PrivateCallURI><uri-entry>sip:carol.mcvideo@mcx.test</uri-entry></PrivateCallURI></PrivateCallOnNetwork></PrivateCallList></PrivateCall>
            <MCVideo-group-call><Priority>7</Priority></MCVideo-group-call></Common>
            <OnNetwork><MCVideoGroupInfo><MCVideo-Group-ID><uri-entry>sip:operations@mcx.test</uri-entry></MCVideo-Group-ID></MCVideoGroupInfo></OnNetwork>
            </mcvideo-user-profile>"""
        val p = McvideoProfile.parse(xml)
        assertEquals("sip:alice.mcvideo@mcx.test", p.userId)
        assertEquals(7, p.priority)
        assertEquals(listOf("sip:bob.mcvideo@mcx.test", "sip:carol.mcvideo@mcx.test"), p.privateCallTargets)
        assertEquals(mapOf("sip:bob.mcvideo@mcx.test" to "https://kms2.mcx.test"), p.privateCallKms)
        assertEquals(listOf("sip:operations@mcx.test"), p.groups)
        assertEquals(0, McvideoProfile.parse(xml.replace("<MCVideo-group-call><Priority>7</Priority></MCVideo-group-call>", "")).priority)
        assertTrue(runCatching { McvideoProfile.parse(xml.replace(">7<", ">300<")) }.isFailure)
    }
}

/** TS 24.281 §22.2.2.1.1, §6.2.8.1.19, §6.2.8.1.23: the ad hoc group call's mcvideo-info and the service configuration. */
class McvideoAdhocTest {
    @Test fun adhocEmergencyIndicationRoundTrips() {
        val info = McvideoInfo(sessionType = "adhoc", clientId = "urn:uuid:1", participantCriteria = "c1,c2", adhocEmergency = true)
        val xml = info.encode()
        assertTrue(xml, xml.contains("<anyExt><call-participants-criteria>c1,c2</call-participants-criteria><adhoc-emergency-ind>true</adhoc-emergency-ind>"))
        val back = McvideoInfo.parse(xml)
        assertEquals(true, back.adhocEmergency); assertEquals("c1,c2", back.participantCriteria); assertEquals("adhoc", back.sessionType)
        assertNull(McvideoInfo.parse(McvideoInfo(sessionType = "adhoc").encode()).adhocEmergency)
    }

    @Test fun adhocSupportFromTheServiceConfiguration() {
        fun doc(inner: String) = """<?xml version="1.0"?><service-configuration-info xmlns="urn:3gpp:mcvideo:mcvideoServiceConfig:1.0">
            <service-configuration-params><on-network><anyExt>$inner</anyExt></on-network></service-configuration-params></service-configuration-info>"""
        assertTrue(McvideoService.adhocSupport(doc("<adhoc-group-call><allow-adhoc-group-call-support>true</allow-adhoc-group-call-support></adhoc-group-call>")))
        assertFalse(McvideoService.adhocSupport(doc("<adhoc-group-call><allow-adhoc-group-call-support>false</allow-adhoc-group-call-support></adhoc-group-call>")))
        assertFalse(McvideoService.adhocSupport(doc(""))) // absent: not supported
    }

    @Test fun cancellationOfAnotherUsersAlertAndImminentCancel() {
        val cancel = McvideoInfo.parse(McvideoInfo(requestUri = "sip:g@mcx.test", emergency = false, alert = false,
            originatedBy = "sip:bob.mcvideo@mcx.test").encode())
        assertEquals(false, cancel.alert); assertEquals("sip:bob.mcvideo@mcx.test", cancel.originatedBy)
        val imminent = McvideoInfo(requestUri = "sip:g@mcx.test", imminentPeril = false, clientId = "urn:uuid:1").encode()
        assertTrue(imminent.contains("<imminentperil-ind type=\"Normal\"><mcvideoBoolean>false</mcvideoBoolean></imminentperil-ind>"))
    }
}

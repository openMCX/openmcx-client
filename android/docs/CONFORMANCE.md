# Android client: conformance

For each requirement this lists the clause, the code that implements it, and the evidence that it works. The evidence
is a JVM unit test (`core/src/test`), an instrumented test (`app/src/androidTest`) or a check in the Android-only lab
scenarios (`tests/scenarios.py`, S1–S24). Paths are relative to `android/`. Clause numbers are from ETSI TS 124 379 and TS 124 282 V19.7.0. Clauses of TS 24.380, 24.482 and 24.484 are cited from
the published specifications and not re-checked against a local copy; they are marked *(not checked locally)*.

## Start-up

| Requirement | Clause | Code | Evidence |
|---|---|---|---|
| OIDC authorization code flow with PKCE S256, state and nonce | TS 24.482 §6 *(not checked locally)*, RFC 7636 | `core/…/idms/Idms.kt` | `IdmsTest`; S1 |
| ID token checks: RS256 signature by `kid`, `iss`, `aud`, `nonce`, `exp` | OIDC Core §3.1.3.7 | `Idms.kt` `verifyIdToken`, `security/Jwt.kt` | `JwtTest`, `IdmsTest` |
| Token identities (`client_id`, `mcptt_id`) must match provisioning | TS 24.379 §7.2.2 | `McxClient.login` | S1 |
| Interactive login in an external user agent, loopback redirect | RFC 8252 §7.3, §8.12 | `idms/LoopbackRedirect.kt`, `app/…/identity/CustomTabBrowser.kt` | `LoopbackRedirectTest`; `BrowserLoginTest` (Chrome on an AVD) |
| MCPTT user profile (TS 24.484 8.3.2.3 schema, rights as boolean `<cp:actions>` elements) and group documents, with the access token and ETags | TS 24.484 *(not checked locally)*, TS 24.481 §6.3.3 | `documents/Documents.kt` | `DocumentsTest`; S1 event log |
| Mutual TLS to the SIP core, client key held in the Android Keystore | TS 33.180, TS 24.379 §7.1 | `sip/Flow.kt` `SocketConnector`, `app/…/security/ClientCredentials.kt` | S1 (every client) |
| REGISTER with `+sip.instance`, `reg-id`, `ob`, MCPTT and MCData feature tags and ICSIs | TS 24.379 §7.2.1, TS 24.282 §7.2.1, RFC 5626 §5 | `McxClient.register` | S1 |
| Digest authentication for every request, in-dialog ones included (MD5/SHA-256, with or without qop) | RFC 3261 §22, RFC 7616 | `sip/Digest.kt`, `UserAgent.send` | `SipTest` (RFC 2617 and RFC 7616 vectors); S1, S7 |
| MCPTT service authorisation: PUBLISH poc-settings with the token and client ID, the current answer mode and the selected user profile's index | TS 24.379 §7.2.2, §7.2.3 4) | `McxClient.authorise`, `McxClient.pocSettings` | S1 |
| A refused service authorisation retried after the Retry-After of a 500 or 503 (1 s to 10 min), else the back-off | TS 24.379 §7.2.2, RFC 3261 §20.33 | `McxClient.retryService`, `ClientRules.authorisationRetryDelayMs` | `ClientRulesTest`, `ServiceRefusalTest.retryAfterOfA503GivesTheDelay` |
| Log-off (Settings › Log off, after a confirmation; while logged off the status beside Settings reads "logged off" in red, and Settings offers Log on): the calls end, the subscriptions end (SUBSCRIBE Expires 0; the MCVideo group key subscriptions too, and their renewal stops), each service gets a PUBLISH poc-settings with Expires 0, SIP-If-Match and no body (the MC log-off), the MCVideo CSK goes, then the SIP de-REGISTER (REGISTER Expires 0), all dialogs released before it; nothing starts again until Log on. A refused authorisation shows its Warning (486 with 164, 166 or 228: the user is on another device) and is retried with the start-up back-off | TS 24.379, TS 24.281, TS 24.282 §7.2.1A step 4 and NOTE 3, §7.3.3 steps 3a and 3b; RFC 3903 §4.5; RFC 6665 §4.1.2.3; TS 24.229 §5.1.1.6.1 | `McxClient.logOff`, `McxClient.logOn`, `McvideoService.logOff` | phone test (`log-off`, `log-on` debug commands) |
| No service and sign-out: without service (the SIP core, a server or IdMS unreachable) the client keeps trying with a back-off of 2 s to 60 s, as a mission critical client recovers by itself; the front page shows since when, why and the next attempt, with Retry now and Log off. IdMS refusing the refresh token (account suspended, devices revoked, a system that no longer knows the device) with no password configured signs the user out: the client stops trying and shows Sign in, which opens IdMS's page once | TS 33.180 annex B.5; TS 24.379 §7.2.1A | `McxClient.start`, `retryNow`, `signIn`; `RecoveryTest` | unit test; phone test (SIP core stopped and restarted, `retry` debug command) |
| MCData service authorisation | TS 24.282 §7.2.2 | `McxClient.authorise` | S1 |
| Affiliation status subscription (SUBSCRIBE presence with mcptt-info and simple-filter) | TS 24.379 §9.2.1.3, TS 24.282 §8.2.3 | `McxClient.subscribe` | S1 |
| Affiliation by PUBLISH presence (full state), confirmed by NOTIFY | TS 24.379 §9.2.1.2, TS 24.282 §8.2.2 | `McxClient.affiliate`, `body/Presence.kt` | `PresenceTest`; S1 |
| The document unsubscribe (Expires 0) of the CMS and GMS subscriptions carries Accept application/xcap-diff+xml | TS 37.579-1 table 5.5.2.14-1, RFC 6665 §7.2 | `McxClient.endDocumentSubscriptions` | `DocumentSubscriptionClientTest` |

## Service settings and affiliation

The procedures of `client/ServiceProcedures.kt`, one instance per service: MCPTT's (`McxClient.procedures`) and
MCVideo's (`McxClient.videoProcedures`, see the MCVideo table), each with its debug command
([README](../README.md#debug-control-endpoint)).

| Requirement | Clause | Code | Evidence |
|---|---|---|---|
| Settings-only PUBLISH: the PSI, Event poc-settings, Expires 4294967295, the info body with this user's ID and the client ID (no access token), the poc-settings with the answer mode, profile index and multiplex-support; SIP-If-Match of the authorisation's publication, a 412 published anew; the outcome shown with its Warning. On the answer mode change (Settings, `answer-mode`) | TS 24.379 §7.2.3, §7.2.1A, RFC 3903 | `ServiceProcedures.settingsPublish`, `Engine.setRingFirst` | `ServiceProceduresTest.settingsPublishAndRemove`, `settingsPublishRefused`; S21 |
| Settings removal (Expires 0): the client is logged off the service | TS 24.379 §7.2.1A NOTE 3 | `ServiceProcedures.settingsPublish(remove)`, `McxClient.settingsRemoved` | `ServiceProceduresTest`; S21 |
| Settings subscription (Event poc-settings, Accept, Expires 4294967295 or 0 to fetch), re-subscribe and de-subscribe in the dialog; the NOTIFY's answer mode and profile index cached per client ID | TS 24.379 §7.2.4 | `ServiceProcedures.settingsSubscribe`, `settingsResubscribe` | `ServiceProceduresTest.settingsSubscription`, `ProceduresBodiesTest`; S21 |
| Monitored groups: groups of the user profile affiliated besides the provisioned ones (at most 16), every PUBLISH naming all; shown in the Call tab's group list with their notified status | TS 24.379 §9.2.1.2 | `McxClient.monitor`, `wantedGroups`; `ui/Screens.kt` `TalkScreen` | `ServiceProceduresTest.monitorNeedsAProfileGroup`; S22 |
| Another user's affiliation: SUBSCRIBE naming the target (the per-client filter only for the user's own), Accept application/pidf+xml, fetch; the NOTIFY read for every client | TS 24.379 §9.2.1.3 | `ServiceProcedures.affiliationSubscribe` | `ServiceProceduresTest.remoteAffiliation`; S23 (the server refuses another user's with 403, §9.2.2.2.4) |
| Mandatory mode: the PUBLISH for the targeted client with all its groups of interest, no status or expires, a new p-id; Expires 0 and no PIDF without any group | TS 24.379 §9.2.1.2 steps 4, 5 | `ServiceProcedures.affiliateRemote` | `ServiceProceduresTest.remoteAffiliation`; S23 (targeted at alice's own client) |
| Negotiated mode, sent: MESSAGE to the PSI with the info body naming the target and the annex F.4 affiliation command | TS 24.379 §9.2.1.4, annex F.4 | `ServiceProcedures.affiliateRemote`, `body/Presence.kt` `AffiliationCommand` | `ServiceProceduresTest`, `ProceduresBodiesTest`; S23 (answered; the OpenMCX servers do not relay it) |
| Negotiated mode, received: 200 at once, the user prompted (Accept, Decline; `affiliation-accept [<group>...]`, `affiliation-decline`); accepted, affiliation to the groups not affiliated and de-affiliation from those affiliated, as §9.2.1.2 | TS 24.379 §9.2.1.5 | `ServiceProcedures.handle`, `answerAffiliationRequest`, `McxClient.changeAffiliation`; `ui/Screens.kt` `AffiliationRequestPrompt` | `ServiceProceduresTest.negotiatedRequestReceived`. Not in the lab: the OpenMCX servers do not send it |
| Group dynamic data: SUBSCRIBE naming the group with the per-group filter of §9.3.2.2 (the items asked for), fetch, re-subscribe, de-subscribe; the §9.3.1.2 document (status, aliases, call ongoing, members) | TS 24.379 §9.2.1.6, §9.3.1.2, §9.3.2.2 | `ServiceProcedures.gddSubscribe`, `body/Presence.kt` `GroupDynamicData` | `ServiceProceduresTest.groupDynamicDataSubscription`, `ProceduresBodiesTest`; S24 |

## Keeping registered

| Requirement | Clause | Code | Evidence |
|---|---|---|---|
| One outbound flow. Requests to the UE arrive on it; the UE never listens | RFC 5626 §4, §5 | `sip/Flow.kt`, `UserAgent` (`.invalid` sent-by) | S1–S11 (the lab overlay never dials a client) |
| CRLF keepalive; a missing pong fails the flow | RFC 5626 §4.4.1 | `SipFlow.keepalives` | `SipTest.framesSplitAndKeepalives`; S7 (outage detected) |
| Re-register on a new flow with the same instance and reg-id, then subscribe and affiliate again | RFC 5626 §4.2 | `McxClient.run`, `reconnect` | S7 (dropped flow, coverage loss) |
| Registration, subscription and token refresh; re-authorisation with SIP-If-Match | RFC 3261 §10.2.4, RFC 3903 §4.4, TS 24.379 §7.2.2 | `McxClient.housekeeping`, `refreshNow` | S7 |
| Reregistration 600 s before the expiry of a registration of more than 1200 s, else at half time | TS 24.229 §5.1.1.4.1 | `McxClient.register`, `client/RegEvent.kt` `RegEventRules.refreshDelayS` | `RegEventRulesTest`, `RegEventClientTest.subscribesAfterTheRegistration` |
| Reg event subscription after the initial registration: Request-URI, From and To the public user identity, Event reg, Expires 600 000; refreshed 600 s before its expiry (half time for 1200 s or less), the NOTIFY's expiry before the 2xx's; 481 starts a new one, other errors keep it until its expiry; ended with the de-REGISTER | TS 24.229 §5.1.1.3, §5.1.1.6.1; RFC 6665 | `McxClient.regSubscribe`, `regRefresh`, `regCancel` | `RegEventClientTest`; S19 (TLS and TCP, wire check of the SUBSCRIBE) |
| reginfo documents: RFC 3680 §5.4 types (version without upper bound), version order and refresh on a gap | RFC 3680 §5.1, §5.2, §5.4 | `body/RegInfo.kt`, `RegEventRules.versionNew`, `versionGap` | `RegInfoTest`, `RegEventRulesTest` |
| Shortened registration renewed at the §5.1.1.4.1 time for the adjusted expiry | TS 24.229 §5.1.1.5A | `McxClient.regDocument` | `RegEventClientTest.shortenedRegistrationIsRenewed`; S19 |
| Network-initiated deregistration of the own contact (unregistered, rejected, deactivated): registration details removed, the TLS flow closed after the NOTIFY's transaction; rejected: dialogs released, off until Log on (Settings shows "Deregistered by the network"); deactivated: initial registration on a new flow | TS 24.229 §5.1.1.7 | `McxClient.networkDeregistration`, `dropServiceState`; `app/…/ui/Screens.kt` | `RegEventClientTest` (deactivated, rejected); S19 |
| The final NOTIFY of a subscription the client ended (log-off, network deregistration) is answered 200 | RFC 6665 §4.1.2.3 | `McxClient.dispatch` (`endingSubscriptions`) | `RegEventClientTest.finalNotifyAfterTheUnsubscribeIsAnswered`; S19 wire check |
| Reconnect when the default network changes | RFC 5626 §4.4 | `app/…/McxService.watchNetwork` | Not exercised: redroid cannot change networks |
| Always on: sticky foreground service, start at boot | TS 23.280 §10.2 | `McxService`, `BootReceiver` | S8 (Doze, process killed) |

## MCPTT

| Requirement | Clause | Code | Evidence |
|---|---|---|---|
| Private call, originating: INVITE to the PSI with mcptt-info (`private`, client ID, `<forwarding-sequence-number>` 0), resource-lists, Answer-Mode or Priv-Answer-Mode | TS 24.379 §11.1.1.2.1.1 (14B a)) | `McpttService.originate` | `McpttInfoTest.forwardingSequenceNumber`; S2, S3 |
| Private call, terminating: commencement mode from Priv-Answer-Mode, Answer-Mode (each by its token: `Auto;require` is Auto) or the local setting; 180, then 200 or 480 with warning 110 | TS 24.379 §11.1.1.2.1.2, §6.2.3, RFC 5373 §2 | `McpttService.incoming`, `ClientRules.manualCommencement` | `ClientRulesTest`; S2, S3 |
| Prearranged group call, originating and terminating | TS 24.379 §10.1.1.2.1 | `McpttService` | S4 |
| Ad hoc group call; the group identity comes from `mcptt-calling-group-id` | TS 24.379 §17.2.2.1 | `McpttService` | S5 |
| Emergency and imminent-peril group and private calls: the indications in mcptt-info, and the call state the server announces by re-INVITE | TS 24.379 §6.2.8.1, §10.1.1.2.1, §11.1.1.2.1 | `McpttService.originate`, `McpttService.setPriority` | `lab/androidlab.py --priority` |
| Release: BYE (with Reason for ad hoc), or CANCEL before the answer | TS 24.379 §6.2.4, §17.2.3.1.1 | `McpttService.hangup` | S2–S5 |
| Session timer; refresh by UPDATE (as UAS we are the refresher) | RFC 4028, TS 24.379 §6.2.3.1.1 step 5 | `McpttService.startRefresh` | S7 (an 80 s private call; bob's UPDATE refresh gets 200 and the call outlives the server's roughly 60 s session timer) |
| SDP: one AMR-WB stream and `m=application … udp MCPTT` with `mc_floor_ssrc`; an offered `mc_priority` is echoed; one session ID per call and the version one higher in each subsequent offer or answer | TS 24.379 §6.2.1, §6.2.2; TS 24.380 §14 *(not checked locally)*; RFC 3264 §8 | `mcptt/McpttSdp.kt` (`Origin`) | `McpttSdpTest`; S2–S5 |
| Implicit floor request: `mc_implicit_request` in the initial INVITE's offer only (`mcptt.implicit_floor_request`, or per call), the accepted request entering 'U: pending Request' with T101 | TS 24.379 §6.4; TS 24.380 §14.2.5, §14.5, §6.2.4.2.2 | `McpttService.originate`, `FloorParticipant.startImplicit` | `FloorParticipantTest`, `McpttSdpTest`; S20 |
| `mc_granted` offered only next to `mc_implicit_request` in the initial offer (`mcptt.offer_granted`, default false, or per call); its grant in the 200 (OK) taken as a Floor Granted with `mc_ssrc`, the Floor Granted that may follow not a second grant; answer parameters not offered discarded | TS 24.380 §14.2.4, §14.3.4, §14.3.6, §14.4, §6.2.4.4.2 | `McpttService.originate`, `FloorParticipant.startImplicit`, `ClientRules.answerParameterKept` | `FloorParticipantTest.grantInTheAnswerIsTheOnlyGrant`, `ClientRulesTest`; S20 (offer only: the OpenMCX server grants by Floor Granted) |
| Floor control packets (RTCP APP `MCPT`) | TS 24.380 §8 *(not checked locally)* | `mcptt/Floor.kt` | `FloorTest` (captured bytes) |
| Floor participant: request, grant, deny, taken, idle, revoke, release, acknowledgements, T100/T101 | TS 24.380 §6.2.4 *(not checked locally)* | `mcptt/FloorParticipant.kt` | `FloorParticipantTest`; S2–S5, S4 (deny), S9 (retransmission) |
| Floor Indicator of the Floor Request and Floor Release by call type (D emergency, E imminent peril, else A), with bit F when queueing was negotiated | TS 24.380 §6.2.4.3.5 1 b), §8.2.3.15, §14.6.2 NOTE; TS 37.579-1 tables 5.5.6.2-1, 5.5.6.5-1 | `ClientRules.floorIndicator`, `McpttService.indicatorOf` | `ClientRulesTest.floorIndicatorByCallType`, `FloorParticipantTest.floorIndicatorOfTheCall` |
| Location field in every Floor Request: Location Type '0' (Not provided), as the client reports no location in floor control | TS 24.380 §6.2.4.3.5 1 c) i), table 8.2.3.21-3 | `FloorMessage.request` | `FloorTest` |
| Queued floor requests: `mc_queueing` offered when the MCPTT service configuration has `<floor-control-queue>`; 'U: queued' with the position (Floor Queue Position Info and Request, T104/C104), leaving the queue, a queued grant accepted by the held PTT or by the user within T132, or given back when T132 runs out | TS 24.380 §6.2.4.9, §14.2.2; TS 24.484 §8.4.2.1 | `mcptt/FloorParticipant.kt`, `mcptt/McpttSdp.kt`, `documents/Documents.kt` (`ServiceConfig.floorQueue`) | `FloorParticipantTest`, `SdpTest`, `ServiceConfigTest`; S18 (lab made with `--floor-queue`) |
| RTP with the SSRC from Floor Granted; AMR-WB in bandwidth-efficient or octet-aligned form, CMR 15 | RFC 3550, RFC 4867 §4.3–4.4 | `media/Rtp.kt`, `media/AmrWb.kt`, `MediaSession.talk` | `MediaTest`; S2–S11 (payloads identical end to end) |
| AMR-WB encoding and decoding | TS 26.171, 26.201 | `app/…/media/MediaCodecAmrWb.kt` | `MediaCodecAmrWbTest` (redroid and AVD) |
| Playout with a jitter buffer and loss concealment | — | `media/JitterBuffer.kt`, `MediaSession` | `JitterBufferTest`; S9 |

## MCData

| Requirement | Clause | Code | Evidence |
|---|---|---|---|
| SDS SIGNALLING PAYLOAD, DATA PAYLOAD and SDS NOTIFICATION codecs | TS 24.282 §15.1.2, §15.1.4, §15.1.5, §15.2 | `mcdata/Messages.kt` | `MessagesTest` (the contract's worked hex examples) |
| mcdata-info in schema order | TS 24.282 Annex D.1 | `mcdata/McdataInfo.kt` | `McdataInfoTest` |
| One-to-one standalone SDS: MESSAGE to the PSI with mcdata-info, resource-lists, signalling and payload | TS 24.282 §9.2.2.2.1 | `McdataService.send` | S6 |
| Group standalone SDS, with the client ID | TS 24.282 §9.2.2.2.1 step 3 | `McdataService.send` | S6 |
| Payload limit on the signalling plane | TS 24.282 §9.2.1.1 | `McdataService.send` | Not exercised (local refusal) |
| Receiving: sender from `mcdata-calling-user-id`, group from `mcdata-calling-group-id`, 200 OK, deduplication by message ID | TS 24.282 §9.2.2.2.2, §9.2.1.2 | `McdataService.received` | S6 |
| Disposition: DELIVERED, READ, DELIVERED AND READ with TDU1; Sender IE always present | TS 24.282 §9.2.1.3, §12.2.1.1 | `McdataService.notify`, `display` | S6 |
| Receiving notifications, several per MESSAGE (aggregated) | TS 24.282 §12.2.1.2 | `McdataService.handle` | S6 (the group case) |

## MCVideo (phase 7)

| Requirement | Clause | Code | Evidence |
|---|---|---|---|
| Answer-Mode and Priv-Answer-Mode compared by token | RFC 5373 §2, TS 24.281 §6.2.3 | `McvideoService.answerModeToken` | `McvideoRulesTest` |
| `mc_granted` offered with an implicit request in the initial INVITE only (`mcvideo.offer_granted`, default false); the 200 (OK)'s grant is the transmission grant | TS 24.581 §14.2.4, §14.5, §12.1.2.2 NOTE 3, §14.4 | `McvideoSdp`, `TransmissionParticipant.grantedInAnswer` | `TransmissionTimersTest.grantInTheAnswer`, `TransmissionPriorityTest` |
| Transmission end request and response, never Transmission Release; the server's end request answered | TS 24.581 §6.2.4.5.3, §6.2.4.5.7, §6.2.4.6.4, §9.2.7 | `TransmissionParticipant` | `TransmissionTimersTest`; V1 |
| T100 to T104, C100 to C104 (`mcvideo.transmission_timers`), with the implicit request's T100 | TS 24.581 §6.2.4.2.2 step 4, §6.2.4.4.3-4, §6.2.4.6.2-3, §6.2.4.9.7-8, §6.2.5.4.3-4, §6.2.5.6.2-3, §11.1.1, §11.2.1 | `TransmissionParticipant`, `TransmissionTimers` | `TransmissionTimersTest` (virtual time); V1 |
| Queueing: Queue Position Info, Queue Position Request, Cancel Request Notify (`mcvideo.queueing`) | TS 24.581 §6.2.4.4.5, §6.2.4.9 | `TransmissionParticipant` | `TransmissionTimersTest`; not in a lab |
| Reception end request and the server's reception end | TS 24.581 §6.2.5.5.3, §6.2.5.5.5, §6.2.5.6.4 | `TransmissionParticipant.endReception` | `TransmissionTimersTest`; V1 |
| Transmission and reception priority from the MCVideo user profile's `<Priority>`: offered, negotiated, requested | TS 24.581 §14.2.3, §14.2.6, §14.3.3, §14.4, §6.2.4.3.2 step 2a, §6.2.5.3.3 step 1a; TS 24.484 §9.3.2.1 item 8 e) v) | `TransmissionPriority`, `McvideoProfile` | `TransmissionPriorityTest`, `McvideoRulesTest`; V1 (7 offered, 4 negotiated and requested) |
| XML confidentiality and integrity of MCVideo signalling; 403 with warning 139 or 140 | TS 24.281 §4.8, §6.6; TS 33.180 §9.3 | `client/SignallingProtection.kt` | `SignallingProtectionTest` (MCVideo tests); V1 on a protected lab |
| Ad hoc group calls: listed users or criteria, the support check, the ad hoc group from the 2xx; emergency and imminent peril ad hoc calls | TS 24.281 §22.2.2.1.1, §22.2.2.2.1, §6.2.8.1.19, §6.2.8.1.23 | `McvideoService.adhocCall` | `McvideoAdhocTest`; V2 |
| Leaving and re-joining an ad hoc (or group) session; release for everyone with Reason | TS 24.281 §22.2.3.1.1, §22.2.4.1.1, §22.2.5.1.1, §9.2.1.2.4.1 | `McvideoService.hangup`, `rejoin` | V2 |
| Cancelling another user's emergency alert with the emergency; a group's imminent peril without a call | TS 24.281 §6.2.8.1.14 step 3, §6.2.8.3.8 step 3, §9.2.5.2.1 | `McvideoService.setPriority`, `cancelImminent` | `McvideoAdhocTest`; V3 |
| Ad hoc group emergency alert criteria modification | TS 24.281 §12.1A.1.4 | `EmergencyAlerts.modifyAdhoc` | debug command only (no lab check) |
| Settings-only PUBLISH | TS 24.281 §7.2.3, §7.2.1A; RFC 3903 §5 | `McvideoService.publishSettings` | V4 |
| Group dynamic data subscription | TS 24.281 §8.2.1.6, §8.3.1.2, §8.3.2.2 | `McvideoService.subscribeGdd`, `GroupDynamicData` | `GroupDynamicDataTest`; V4 |
| Target KMS of a private call from `<PrivateCallKMSURI>`; KMS redirect (488 with a KRR) | TS 24.281 §6.2.8.3.9, §10.2.2.2.1 step 8 d); TS 33.180 §5.2.8.2.5, annex D.4 | `McvideoService.originate`, `KmsRedirect`, `KeyClient.makePck` | `KmsRedirectTest`; V5 |
| Negotiated-mode affiliation request received: 200 at once, the user's confirmation sought (a dialog, or `video-affiliation-accept [<group>...]` / `video-affiliation-decline`); accepted for the groups named only, each applied as in §8.2.1.2 | TS 24.281 §8.2.1.5 | `McxClient.videoProcedures` (`ServiceProcedures` for MCVideo), `McxClient.changeAffiliation`; `ui/UiModel.kt` | `McvideoProceduresTest.negotiatedRequestAcceptedPerGroup`, `deaffiliationFromOneGroup`; V6 (the OpenMCX servers do not relay the request, so only its MESSAGE is checked there) |
| De-affiliation from one group (`video-deaffiliate <group>`) and affiliation to one (`video-affiliate <group>`): the PUBLISH lists the groups still wanted, Expires 0 when none is left; the change confirmed by the next NOTIFY; kept across a new registration | TS 24.281 §8.2.1.2 steps 4 and 5, §8.2.1.3 | `McxClient.changeAffiliation`, `affiliate`, `wantedGroups` | `McvideoProceduresTest.deaffiliationFromOneGroup`; V6 |
| Another user's MCVideo affiliation (subscription), and the change for them in mandatory or negotiated mode, sent with the mcvideo-info, the MCVideo PSI and `application/vnd.3gpp.mcvideo-affiliation-command+xml` | TS 24.281 §8.2.1.2, §8.2.1.3, §8.2.1.4 | `ServiceProcedures.affiliationSubscribe`, `affiliateRemote` | `McvideoProceduresTest.requestsForAnotherUser`; V6 |
| **Not implemented:** other KMSs' certificates (KMS CertCache); ad hoc participant modification; protected ad hoc calls | | | |

## Android integration

| Requirement | Code | Evidence |
|---|---|---|
| Self-managed telecom calls (audio routing, headset answer and end) | `app/…/telecom/TelecomBridge.kt` | `TelecomTest` on an AVD (incoming and outgoing go ACTIVE). Redroid's Telecom has no telephony service, so S11 only records the telecom state |
| Foreground service types: special use when idle; phone call and microphone during a call | `McxService.foreground` | S11 (`0x40000000` → `0x40000084` → `0x40000000`) |
| Incoming-call CallStyle notification with a full-screen intent; message notifications | `Notifications.kt` | S11 (message notification) |
| PTT from the screen, headset and handset PTT key broadcasts; with no call, PTT calls the selected group | `Engine.ptt`, `input/PttKeyReceiver.kt`, `MainActivity` | `ScreensTest.pttIsPressAndHold`; S11 (key broadcast) |
| History that survives process death | `data/HistoryStore.kt` | S11 |
| Display indication when a message is shown | `ui/Screens.kt` `MessagesScreen` | `ScreensTest.messagesAreSentAndNewOnesMarkedDisplayed` |

## Deviations and limits

- **Faults found on the Android labs** (the server ones not changed here; openmcx-server `mcdpre-800c657`):
  - **MCData on a protected lab after an app restart:** the MCData server opens every request of a user it holds a
    binding for with the CSK of that binding (`src/mcdata/sip_dispatch.cpp`, `DataServer::incoming` ->
    `open_signalling`) before the service authorisation sees the request. A new service authorisation uploads a new CSK
    and encrypts its mcdata-info under it (TS 33.180 §9.2.1.3, TS 24.282 §7.2.2 steps 3 and 4), so it is refused 403
    with warning 140 ("XML content encrypted under another XPK") until the old binding ends; `authorise_csk`, which would
    take the uploaded CSK, is never reached. Any restart of the app without a log-off (a reinstall, a crash, `die`)
    shows it; MCPTT and MCVideo authorise with their new CSK.
  - **A group call made while the last one is being released joins it:** when the initiator leaves, the MCPTT server
    BYEs the others (`end_when_initiator_leaves`) but keeps the session in `sessions_` until each BYE is answered
    (`McServer::release_departed_leg`). A new group call INVITE in that time is a "call_joined" of that session
    (warning 123), which then ends with its last participant's answer, and the caller gets a BYE. TS 24.379
    §10.1.1.4.2 has the controlling function create a prearranged group session when none is ongoing, and a session in
    release is no longer one. S16 met it on MCVideo labs, where client-c answers its BYE later; S16 now waits for every
    member to be out of the call.
  - **The in-progress imminent peril notification is answered 488:** the joining INVITE above made the server send
    bob a MESSAGE with the group's imminent peril state (TS 24.379 §6.3.3.1.11); the Android MCPTT client handles
    such MESSAGEs only for emergency alerts and answers this one 488 (client gap, TS 24.379 §12.1.1.3, not fixed here).
  - **MCPTT ad hoc calls on a protected lab:** in S5 the Floor Idle of the ad hoc call fails its authentication at the
    clients ("packet failed authentication, dropped"), so no floor is granted (S5 4/6 there, 12/12 unprotected).
    Protected floor control of an ad hoc call is not done (no MCPTT floor or key code changed in phase 7).
- **Server-driven choices** (see the wire contracts):
  - An explicit floor request by default; the implicit one (`mcptt.implicit_floor_request`) is accepted by the server,
    which grants the floor by Floor Granted and does not use `mc_granted`.
  - `mc_queueing` only when the MCPTT service configuration has a floor request queue (`<floor-control-queue>`); the
    lab enables it with `androidlab.py create --floor-queue`.
  - One AMR-WB frame per RTP packet.
  - UPDATE rather than re-INVITE for session refresh.
  - `mcptt-client-id` in every INVITE.
  - SIP-If-Match on every re-publication.
  - The IdMS login is the lab's credential POST.
- **Functional aliases (TS 24.379 §9A, TS 24.282 §22, TS 24.281 §20):** in each service, the status subscription,
  activation, take-over once offered and deactivation (answered 200, the outcome by the status NOTIFY; each PUBLISH lists
  every alias wanted active, §9A.2.1.2 step 6 a: those notified "activated" or "activating", with the change applied;
  Expires 0 only when none is), and the alias
  presented. MCPTT: private and ad hoc calls present it, and a private call can go to an alias (§11.1.1.2.1.1 step 14c,
  the server's 300 then the call). MCVideo: calls present it, and a private video call can go to an alias. MCData: SDS
  present it, and a one-to-one SDS can go to an alias (§9.2.2.2.1 step 2b ii). The aliases come from the provisioning,
  else from the MCPTT user profile's <FunctionalAliasList> (TS 24.484 §8.3.2.1). Not implemented: location-based
  activation, the per-alias user status subscription, and MCData file distribution to an alias.
- **Not implemented:**
  - pre-established sessions
  - broadcast group calls
  - MCData on the media plane (MSRP), file distribution and data streaming
  - end-to-end media and floor protection (TS 33.180 keys)
  - off-network operation
  - **P-Access-Network-Info (planned).** The client never sends the header. TS 24.229 §5.1.2A.1.1 requires it "if
    available to the UE" in every request and response except CANCEL (optional in ACK). On a 3GPP access, annexes L
    (EPS) and U (5GS) say the UE "shall always include" it. The plan:
    - **When:** the default network is cellular. `NetworkStatus` already tracks the transport through
      `NetworkCapabilities`.
    - **Value:** built from the serving cell, `TelephonyManager.getAllCellInfo()`, which needs location permission. It
      is encoded per TS 24.229 §7.2A.4:
      - `CellIdentityLte` gives `3GPP-E-UTRAN-FDD|TDD; utran-cell-id-3gpp=<MCC><MNC><TAC 4 hex><ECI 7 hex>`;
      - `CellIdentityNr` gives `3GPP-NR-FDD|TDD; utran-cell-id-3gpp=<MCC><MNC><TAC 6 hex><NCI 9 hex>`.
    - **Updates:** refreshed when the cell changes (NOTE 13).
    - **Otherwise:** on Wi-Fi without EPC/5GC access, a VPN or plain IP, the header is left out, since no annex
      defines a value there.
    - **Linux:** the Linux client only has a fixed value from its configuration (`sip.access_network_info`).
- **Fixed in phase 0** (formerly "To fix"; see the rows above): Answer-Mode and Priv-Answer-Mode by token, Accept on
  the document unsubscribe, the Floor Indicator by call type with bit F, `mc_granted` with the implicit floor request,
  and the Retry-After of a refused authorisation; with them, from the TS 37.579-2 suite driver's first Android runs,
  `<forwarding-sequence-number>` (6.2.3, 6.2.5, 6.2.7), the Location field of the Floor Request (6.1.1.2, 6.1.1.21,
  6.2.2) and the SDP version of a subsequent answer (6.2.2). The MCVideo halves (the Answer-Mode token, the conference
  unsubscribe's Accept, `mc_granted` of TS 24.581 §14.2.4) are done in phase 7 (`McvideoService.answerModeToken`; the conference unsubscribe also
  carries the mcvideo-info body of TS 24.281 §9.2.3.2, which the server requires; `mcvideo.offer_granted`).
- **Plan changes:**
  - The interactive login follows RFC 8252 directly (Custom Tab and loopback redirect), not through the AppAuth library.
    The core already had the OIDC logic, so the library would have added nothing.
  - History uses SQLite, not Room, to avoid an annotation processor in the build.
- **NAT:** the MCPTT server does not latch media and checks the SDP address, so the UE must be directly reachable for
  UDP. The lab puts every Android client on the server network. Clients behind NAT need server support that OpenMCX
  lacks.
- **Flow reuse at the SIP core** is a lab overlay (`lab/androidlab.py`), not part of the OpenMCX configuration.

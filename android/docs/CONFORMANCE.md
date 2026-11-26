# Android client: conformance

For each requirement this lists the clause, the code that implements it, and the evidence that it works. The evidence
is a JVM unit test (`core/src/test`), an instrumented test (`app/src/androidTest`) or a check in the Android-only lab
scenarios (`tests/scenarios.py`, S1–S85). Paths are relative to `android/`. Clause numbers are from ETSI TS 124 379 and TS 124 282 V19.7.0. Clauses of TS 24.380, 24.482 and 24.484 are cited from
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
| REGISTER with `+sip.instance`, `reg-id`, `ob`, the MCPTT tag and ICSI; with MCData the MCData ICSI and the SDS and FD tags and ICSIs, the IPCONN tag and ICSI when `mcdata.ipconn.address` is set; the MCVideo tag and ICSI with MCVideo | TS 24.379 §7.2.1, TS 24.282 §7.2.1 1)-4), TS 24.281 §7.2.1, RFC 5626 §5 | `registerContactParams` (`McxClient.register`) | S1, `RegisterContactTest`; suite MCData 5.1 |
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
| Another user's affiliation: SUBSCRIBE naming the target (the per-client filter only for the user's own), Accept application/pidf+xml, fetch; the NOTIFY read for every client | TS 24.379 §9.2.1.3 | `ServiceProcedures.affiliationSubscribe` | `ServiceProceduresTest.remoteAffiliation`; S23 (granted with `--fixture-arg=--remote-affiliation`, else 403, §9.2.2.2.4) |
| Mandatory mode: the PUBLISH for the targeted client with all its groups of interest, no status or expires, a new p-id; Expires 0 and no PIDF without any group | TS 24.379 §9.2.1.2 steps 4, 5 | `ServiceProcedures.affiliateRemote` | `ServiceProceduresTest.remoteAffiliation`; S23 (targeted at alice's own client) |
| Negotiated mode, sent: MESSAGE to the PSI with the info body naming the target and the annex F.4 affiliation command | TS 24.379 §9.2.1.4, annex F.4 | `ServiceProcedures.affiliateRemote`, `body/Presence.kt` `AffiliationCommand` | `ServiceProceduresTest`, `ProceduresBodiesTest`; S23 (relayed to bob with `--remote-affiliation`, §9.2.2.2.8) |
| Negotiated mode, received: 200 at once, the user prompted (Accept, Decline; `affiliation-accept [<group>...]`, `affiliation-decline`); accepted, affiliation to the groups not affiliated and de-affiliation from those affiliated, as §9.2.1.2 | TS 24.379 §9.2.1.5 | `ServiceProcedures.handle`, `answerAffiliationRequest`, `McxClient.changeAffiliation`; `ui/Screens.kt` `AffiliationRequestPrompt` | `ServiceProceduresTest.negotiatedRequestReceived`, `negotiatedRequestNamesTheRequester` (the requester from `<mcptt-calling-user-id>`, not the PSI in From); S23 with `--remote-affiliation` |
| Group dynamic data: SUBSCRIBE naming the group with the per-group filter of §9.3.2.2 (the items asked for), fetch, re-subscribe, de-subscribe; the §9.3.1.2 document (status, aliases, call ongoing, members) | TS 24.379 §9.2.1.6, §9.3.1.2, §9.3.2.2 | `ServiceProcedures.gddSubscribe`, `body/Presence.kt` `GroupDynamicData` | `ServiceProceduresTest.groupDynamicDataSubscription`, `ProceduresBodiesTest`; S24 |

## Location rules, group geographic areas, SEAL location

Driven by the phone's position: the fused fix, or the debug override `location-position` (`client/LocationRuleProcedures.kt`,
`location/LocationRules.kt`, `location/SealLocation.kt`, `client/SealLocationClient.kt`; LOCATION.md).

| Requirement | Clause | Code | Evidence |
|---|---|---|---|
| Location based functional alias status change: entering or leaving an area of `<LocationCriteriaForActivation>` / `<LocationCriteriaForDeactivation>` starts the status change procedure; an alias already in that state is left; the first position only places the client | TS 24.379 §9A.2.1.4, TS 24.282 §22.2.1.4, TS 24.281 §20.2.1.4; TS 24.484 §8.3.2.1 item 10 | `LocationRuleEngine.position`, `LocationRuleProcedures` | `LocationRulesTest`, `LocationRuleProceduresTest.profileRulesByPosition`; S40 (MCPTT and MCData) |
| A manual deactivation of an alias a met location criterion holds is suppressed when `<manual-deactivation-not-allowed-if-location-criteria-met>` is true | TS 24.379 §9A.2.1.2, TS 24.282 §22.2.1.2, TS 24.281 §20.2.1.2 | `LocationRuleEngine.manualDeactivationRefusal`; `DebugControl` `fa deactivate` | `LocationRulesTest`; S40 |
| Rules based affiliation: a `<RulesForAffiliation>` / `<RulesForDeaffiliation>` rule is fulfilled when a location criterion (an area entered or left) and a functional alias criterion are met; evaluated on each position and when an alias becomes active; an area with `<Speed>` or `<Heading>` is never met; both rules at once for a group: nothing | TS 24.379 §9.2.1.7.2, TS 24.282 §8.2.6.2; TS 24.484 §8.3.2.1 items 10 e), f), §10.3.2.1 item 9 b vii | `LocationRuleEngine.position`, `aliasesChanged`; `McxClient.changeAffiliation` | `LocationRulesTest`, `LocationRuleProceduresTest`; S41 (MCPTT and MCData) |
| A manual de-affiliation from a group a fulfilled rule affiliated is suppressed when `<manual-deaffiliation-not-allowed-if-affiliation-rules-are-met>` is true | TS 24.379 §9.2.1.2, TS 24.282 §8.2.2 | `LocationRuleEngine.manualDeaffiliationRefusal`; `DebugControl` `deaffiliate` | `LocationRulesTest`, `LocationRuleProceduresTest`; S41 |
| Group configuration rules: outside the group's `<mandatory-geographic-area>` de-affiliate, back inside affiliate again; no affiliation outside the `<permitted-geographic-area>` or the mandatory one (MCPTT only, as the Linux client) | TS 24.379 §9.2.1.7.3; TS 24.481 §7.2.4.2 | `LocationRuleEngine.allowedHere`, `groupAreasAt`; `GroupAreas` | `LocationRulesTest.groupConfigurationRules`; S42 |
| Group geographic area notice: 200, then affiliate to (`<group-geo-area-ind>` true) or de-affiliate from (false) the `<associated-group-id>` group only, by the affiliation status change procedure | TS 24.379 §12.1.1.4, TS 24.282 §16.2.1.4 | `EmergencyAlerts.area` | `LocationRuleProceduresTest.groupGeographicAreaNotice`; S43 (MCPTT and MCData) |
| Emergency alert area notice: the alert raised by the client itself, unless the user is in the emergency state (an alert this client raised, not cancelled; an own alert the server only reports, e.g. kept across a restart of the app, does not count); the notice of leaving leaves it up | TS 24.379 §12.1.1.6 1)a)ii), TS 24.282 §16.2.1.5 | `EmergencyAlerts.area`, `EmergencyAlerts.inEmergencyState` | `PrivateServicesTest.alertAreaRaisesDespiteAnOwnAlertOnlyReported`; S14, S44 |
| Ad hoc group emergency alert (MCPTT): refused here, nothing sent, without `<allow-activate-adhoc-group-emergency-alert>` in the user profile; its cancellation without `<allow-cancel-adhoc-group-emergency-alert>`; the alert carries the location (an Emergency `<Report>` with the `<CurrentCoordinate>`), so without a position it is not sent | TS 24.379 §6.2.8.1.19, §12.1A.1.1 1), 2), 5) -> §6.2.9.1 2), §12.1A.1.2 | `EmergencyAlerts.sendAdhoc`, `cancelAdhoc`, `adhocAuthorised` | `PrivateServicesTest.adhocAlertAuthorisationLocationAndCancellation`; S14; suite P.5.1 |
| One MIME body is sent as itself (its type the Content-Type), several as multipart/mixed: the alert and area MESSAGEs (a cancellation has one body) | TS 24.379 §6.5, TS 24.282 §6.4 | `Multipart.body`, `EmergencyAlerts.message` | `MultipartTest.oneBodyGoesAsItself`, `PrivateServicesTest.adhocAlertAuthorisationLocationAndCancellation`; suite P.10.2 |
| SEAL location management client: the configuration read from the location management server, periodic reports, positions stored while offline and sent in a history location result report; the access token carries the location management scope | TS 24.545 §6.2.2.2.1, §6.2.2.2.2, §6.2.20.2; TS 33.180 table B.4.2.2-1 | `SealLocationClient`, `SealLocation`, `IdmsClient.scope` | `SealLocationTest`, `IdmsTest`; S45 |

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
| Network-initiated deregistration of the own contact (unregistered, rejected, deactivated): registration details removed, the TLS flow closed after the NOTIFY's transaction; rejected: dialogs released, off until Log on (Settings shows "Deregistered by the network"); deactivated: initial registration on a new flow. A contact whose `callid` (RFC 3680 §5.1) is not the current registration's Call-ID is another binding's, so a stale terminated contact does not deregister a new registration at the same address | TS 24.229 §5.1.1.7 | `McxClient.networkDeregistration`, `dropServiceState`, `RegEventRules.action`; `app/…/ui/Screens.kt` | `RegEventClientTest` (deactivated, rejected), `RegEventRulesTest.aTerminatedContactOfAnEarlierRegistrationIsNotThisOne`; S19 |
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
| First-to-answer call, terminating: the INVITE of session type `first-to-answer` is a private call (with or without floor control) that rings and waits for the user (step 8 a), the server's Priv-Answer-Mode Manual, step 10 c)); 486 when busy (8 c)), 480 when not answered (8 d)), 487 to a CANCEL; a BYE with `<release-reason>` "not selected for call" ends it with that reason; a protected one is answered with the invitee's own PCK (step 5) | TS 24.379 §11.1.1.2.1.2 | `McpttService.incoming`, `GroupCallRules.firstToAnswerRelease`, `ClientRules.manualCommencement` | `McpttCallTypesTest.firstToAnswerInviteRingsAndIsAnswered` (the TS 37.579-2 6.2.21 INVITE, answered 400 before), `firstToAnswerCancelledBeforeTheAnswer`, `GroupCallRulesTest`; S25 |
| First-to-answer call, originating: two or more targets in the resource list, session type `first-to-answer`, no Answer-Mode or `<forwarding-sequence-number>`; the right `<allow-request-first-to-answer-call>` (step 2); the answerer from `<mcptt-called-party-id>` | TS 24.379 §11.1.1.2.1.1 steps 2, 10 a), 15; 200 step 4 | `McpttService.firstToAnswerCall`, `GroupCallRules.firstToAnswerTargets` | `McpttCallTypesTest.firstToAnswerOriginated`; S25 (either invitee wins, the other is cancelled; bob's call refused) |
| Chat group call: initiate and join with session type `chat` and the group, leave by BYE, join again; a group whose document says chat (`<on-network-invite-members>` false) is joined, not called | TS 24.379 §10.1.2.2.1.1, §10.1.2.2.3.1; TS 24.481 §7.2.4.2 | `McpttService.chatCall`, `GroupDocument.type` | `McpttCallTypesTest.chatCallJoin`, `GroupCallRulesTest.groupDocumentPhase2Parts`; S26 |
| Broadcast group call: `<broadcast-ind>` true in mcptt-info (a plain boolean after `<imminentperil-ind>`), received too; the Floor Indicator's bit B | TS 24.379 §6.2.8.2, annex F.1; TS 24.380 §6.2.4.3.5 1 b) | `McpttService.broadcastCall`, `McpttInfo`, `ClientRules.floorIndicator` | `McpttCallTypesTest.broadcastCall`, `GroupCallRulesTest.mcpttInfoBroadcastAndReleaseReason`; S27 (a receiver denied, cause 5; the call ends with the initiator's burst) |
| Ad hoc call by participant criteria (`<call-participants-criterias>`, no resource list), also broadcast | TS 24.379 §17.2.2.1.1 steps 9, 12 | `McpttService.adhocCall`, `GroupCallRules.adhocCriteria` | `McpttCallTypesTest.adhocByCriteriaAndModified`; S28 |
| Ad hoc call modification: a re-INVITE with the mcptt-info as established and either the users with "method" INVITE or BYE or new criteria, never both (NOTE) | TS 24.379 §17.2.6.1.1 | `McpttService.modifyAdhoc`, `GroupCallRules.adhocModificationEntries` | `McpttCallTypesTest.adhocByCriteriaAndModified`, `GroupCallRulesTest`; S28 (client-c removed and added, criteria changed) |
| Ad hoc call leave (a BYE without the Reason of a release) and rejoin to the MCPTT session identity | TS 24.379 §17.2.4.1.1, §17.2.5.1.1 | `McpttService.hangup(releaseAll = false)`, `rejoinAdhoc` | `McpttCallTypesTest.adhocByCriteriaAndModified`; S28 (bob leaves and rejoins) |
| End-to-end protected ad hoc call: with protected media the offer is RTP/SAVP and carries `<end-to-end-security>` "true" (a rejoin too); the 2xx's `<preconfigured-group-id>` names the group whose GMK keys the speech (TS 33.180 §5.7, §7.3), subscribed to on the spot when it is not among `mcptt.adhoc_security_groups` (subscribed from the start); without the element or the GMK the call ends. An invitee keys the call by the INVITE's `<preconfigured-group-id>`, else answers 488; the senders are the talker, the initiator, the listed users and the group's members. As the Linux client (`use_adhoc_security`) | TS 24.379 §17.1, §17.2.2.1.1 step 10 c) iii) and 2xx step 3, §17.2.2.1.2 step 7 | `McpttService.originate`, `incoming`, `adhocGroupKey`, `protection`; `AdhocSecurity` | `AdhocSecurityTest`, `AdhocSecurityConfigTest`, `McpttCallTypesTest.adhocByCriteriaAndModified` (clear call unchanged); S5 on a protected lab |
| Calls on a group whose document has `<preconfigured-group-use-only>` true are not made (the user is told, nothing is sent); the document of a group not provisioned (one affiliated to later) is read when the call is asked for, up to 3 s, so that such a group is refused here too | TS 24.379 §10.1.1.2.1.1, §10.1.2.2.1.1 (first paragraph 1), 2)); TS 24.481 §6.3.14 | `McpttService.originate`, `GroupCallRules.callsAllowed`, `McxClient.refreshGroupDocument` | `GroupCallRulesTest`, `PrivateServicesTest.preconfiguredUseOnlyGroupReadOnDemandAndNotCalled`, `groupDocumentReadOnDemandAllowsTheCall`; TS 37.579-2 6.1.1.22 |
| A functional alias chosen for use while its activation is awaited (the PUBLISH answered, the NOTIFY not in, or listed `activating`) is kept pending and used once a NOTIFY lists it active; an FD request over HTTP waits for it (up to 5 s) so that it carries `<functional-alias-URI>`; one status subscription at a time (a change asked for during the one after the service authorisation uses it) | TS 24.282 §22.2.1.2, §22.2.1.3, §10.2.4.2.1 1)b)ii) | `FunctionalAliasRules.use`, `notified`, `McxClient.useAlias`, `awaitAlias`, `FdPeer.awaitAlias` | `FunctionalAliasTest.anAliasChosenWhileBeingActivatedIsUsedOnceActive`; TS 37.579-7 6.2.14 |
| The active functional alias in use presented in `<functional-alias-URI>` on prearranged and chat group calls (the INVITE, and the REFER over a pre-established session) as on private and ad hoc calls; only while the server has it active; none on an ambient listening call | TS 24.379 §10.1.1.2.1.1 14) e), §10.1.2.2.1.1 13) e), §11.1.1.2.1.1 14) c) | `FunctionalAliasRules.presented`, `McpttService.originate` | `FunctionalAliasTest.theAliasPresentedOnCalls`; S17 (alice's group call INVITE); TS 37.579-2 6.1.1.19, 6.1.2.13, 6.1.2.14, P.8.12 |
| Floor priority: group calls offer `mc_priority` (`mcptt.floor_priority`, else the group document's `<user-priority>` of the user, else the user profile's `<MCPTT-group-call>`/`<Priority>`); the Floor Request carries the priority negotiated (not higher than the answer's) | TS 24.380 §14.3.3, §6.2.4.3.5 1 a); TS 24.481 §7.2.2; TS 24.484 §8.3.2.1 8 e) v) | `GroupCallRules.floorPriorityOffer`, `negotiatedFloorPriority`, `McpttSdp` | `McpttCallTypesTest.floorPriorityOfferedAndNegotiated`, `privateCallOffersNoFloorPriority`, `GroupCallRulesTest.floorPriority`; S29 |
| Pre-emption, the revoked side: the Floor Revoke stops the media, tells the user the cause, and the Floor Release repeats its G-bit; then 'U: pending Release' and the Floor Taken of the new talker | TS 24.380 §6.2.4.5.4, §4.1.1.4 | `FloorParticipant.receive` | `FloorParticipantTest.preemptedTalkerReleasesWithTheDualFloorBit`; S29 (bob at 90 pre-empts alice; client-c at 10 does not) |
| Ambient listening call, originating: session type `ambient-listening`, `<ambient-listening-type>` remote-init or local-init, one user listed, Priv-Answer-Mode Auto, the speech `recvonly` (listening) or `sendonly` (listened-to), the offer an implicit request (the listened-to user's own floor request, or the request to grant the floor to the invited user); the rights `<allow-request-remote-initiated-ambient-listening>`, `<allow-request-locally-initiated-ambient-listening>` | TS 24.379 §11.1.6.2.1.1 steps 7-14, §6.2.1 item 2c, §6.4 | `McpttService.ambientCall`, `originate` | `PrivateServicesTest.ambientListeningRemoteInit`, `ambientListeningLocalInitWithoutReleaseCapability`; S32, S33 |
| Ambient listening call the listened-to user initiated (local-init): no indication of its establishment, progress or release (no call screen, notification, Telecom call or history: `Call.hidden`), yet it stays the user's call (`active`), released from the contact menu's "Stop letting them listen" when the server gave the release capability | TS 24.379 §11.1.6.2.0, §11.1.6.2.1.1 (200 (OK)) 3), §11.1.6.2.1.3 step 2), §11.1.6.2.1.4 step 2) | `Call.hidden`, `Call.concealed`, `McpttService.active`; `app/…/McxService.kt`, `ui/UiModel.ambientStop` | `PrivateServicesTest.ambientListeningLocalInitHiddenAndReleasable`; S33; TS 37.579-2 6.2.16 |
| Ambient listening call, terminating: answered at once (no 180), 400 without Priv-Answer-Mode Auto, 488 with a group, emergency, imminent peril, alert, broadcast or another type; the listened-to user of a remote-init call is given no indication (no call shown, no Telecom call, notification or history) and takes the Floor Granted that comes without a request; a call of the user's own ends the hidden call | TS 24.379 §11.1.6.2.1.2, §6.4; TS 23.379 §10.14.3.2, §10.14.3.3 | `McpttService.incoming`, `PrivateServiceRules.ambientRefusal`, `Call.hidden`, `Call.concealed`, `FloorParticipant.grantExpected`; `app/…/Engine.kt`, `ui/UiModel.kt` | `PrivateServicesTest.ambientListenedToIsHidden`, `ambientListeningRefusals`, `FloorParticipantTest.expectedGrantWithoutRequest`; S32 (bob's screen off, his speech reaches alice unchanged) |
| Ambient listening release only with the release capability (Feature-Caps `g.3gpp.mcptt.ambient-listening-call-release`) | TS 24.379 §11.1.6.2.1.3 step 1, annex D.3 | `McpttService.hangup`, `Call.mayRelease` | `PrivateServicesTest`; S32, S33 |
| The foreground service keeps the microphone type from its start, so that a hidden ambient listening call can capture with the screen off (Android 14 refuses to add a while-in-use type from the background) | TS 24.379 §11.1.6.2.1.2 | `app/…/McxService.foreground` | S32 (`fgs_types` with the screen off), S11 |
| Private call call-back: the request (`<urgency-ind>`, `<time-of-request>` "YYYY-MM-DDThh:mm:ss" UTC) and cancel to the PSI with the target listed; PCCB-I2/I3/I4; the received request stored and confirmed; fulfilment by a call with manual commencement; a call from the user asked fulfils it | TS 24.379 §11.1.5.2.1, §11.1.5.2.2, §11.1.5.2.3; annex F.1.3 | `mcptt/PrivateServices.kt` (`requestCallBack`, `cancelCallBack`, `fulfilCallBack`, `calledBy`) | `PrivateServicesTest.callBackRequested`, `callBackReceivedAndFulfilled`; S34 |
| Remotely initiated private call: the request (`<mcptt-called-party-id>`, `<notify-remote-user>`, `<ric-commencement-mode>`, `<ric-app-level-priority>`); the remote client's call with the commencement mode, Resource-Priority and `<remotely-initiated-call-request-ind>`, told to the user only with `<notify-remote-user>` true, then the outcome report (none when the requester is the one called) | TS 24.379 §11.1.7.2.1, §11.1.7.2.2, §11.1.1.2.1.1 14A) D) | `PrivateServices.remotePrivateCall`, `placeFollowUp`, `PrivateServiceRules.followUp` | `PrivateServicesTest.remoteCallRequested`, `remoteCallPlaced`; S35 |
| Remotely initiated group call: the request (the group in `<mcptt-request-uri>`, not a `<preconfigured-group-use-only>` group); the remote client calls the group when affiliated and idle, and reports with the group | TS 24.379 §10.1.5.2.1 step 1A, §10.1.5.2.2 | `PrivateServices.remoteGroupCall`, `remoteGroup` | `PrivateServicesTest.remoteGroupCallNotAffiliated`; S36 |
| Private call transfer (unannounced): the request to the other party; the transferred user keeps its call and calls the target with `<call-transfer-ind>` and the Priv-Answer-Mode of the call transferred, then reports; on success the requester ends its call | TS 24.379 §11.1.8.2.1, §11.1.8.2.2, §11.1.1.2.1.1 14A) a) | `PrivateServices.transfer`, `placeFollowUp`, `outcome`; `ServiceCall.alongside` | `PrivateServicesTest.transferRequested`, `transferPlaced`; S37 |
| Private call forwarding, manual input: a ringing private call (not first-to-answer); the request to the caller with "manual-input", `<call-to-functional-alias-ind>` "true" for an alias target and "false" otherwise, `<forwarded-by-mcptt-id>` the MCPTT ID called, `<forwarding-target-orig-id>`; then 480 with warning 175; `<allow-call-fwd-manual-any>` (or the OpenMCX servers' `<allow-call-forward-manual-input>`) | TS 24.379 §11.1.9.2.1 steps 1, 2 d), NOTE 1 | `PrivateServices.forward`, `PrivateServiceRules.forwardRequest`, `Call.declineWith` | `PrivateServicesTest.forwardRinging`; S38 |
| Private call forwarding request received (by the user, or by the server on no answer or not registered): the call to the target with `<call-forwarding-ind>`, the forwarded call's `<forwarding-sequence-number>` plus one and its answer mode in Priv-Answer-Mode; the outcome (success on 18x, 2xx, or 480 with warning 175/191) to the forwarding user | TS 24.379 §11.1.9.2.2, §11.1.1.2.1.1 14C) a), a1); §6.3.2.5, §11.1.1.3.2 steps 6, 11 | `PrivateServices.placeFollowUp`, `PrivateServiceRules.forwarded` | `PrivateServicesTest.forwardedByTheServer`; S38, S39 |
| Private call service MESSAGEs not from the participating function's PSI, or for another user, are refused (403) | TS 24.379 §11.1.5.3, §11.1.7.3 | `PrivateServices.handle` | `PrivateServicesTest.serviceRequestFromElsewhereRefused` |
| The `<anyExt>` service elements of the private call services, in schema order, `<ambient-listening-type>`, `<remotely-initiated-call-request-ind>` | TS 24.379 annex F.1.2, F.1.3 | `McpttInfo` | `PrivateServicesTest.serviceElementsRoundTrip` |
| Emergency and imminent peril notification without `<alert-ind>` (an imminent peril group call, another user in the emergency call, either state cancelled): answered 200 (it was 488), the group's MEG and MIG state kept | TS 24.379 §12.1.1.3 steps 2 c), 3-8 | `alert/EmergencyAlerts.groupState` | `PrivateServicesTest.imminentPerilNotificationAnswered`; S36 (client-c, out of the call) |
| The UI: per user, ask to call back, listen in, let them listen, have them call the selected group; for two selected users, have the first call the second; the call-back requests received (Call back) and sent (Withdraw); Transfer on a private call's screen; Forward on a ringing private call, with the target set in Settings first; an ambient listening call labelled, Hang up off without the release capability | §11.1.5-§11.1.9, §10.1.5 | `ui/Screens.kt`, `ui/UiModel.kt` | `ServiceTextTest` (app); through the debug control in S32-S39 |
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
| Payload limit on the signalling plane: above the `<max-payload-size-sds-cplane-bytes>` of the MCData service configuration (`max_payload_size_sds_cplane_bytes` of the provisioning until the document is read) the SDS goes over the media plane; the size is the Payload IE's contents less the content type octet | TS 24.282 §9.2.1.1 2), §9.2.2.3.1 NOTE 4; TS 24.484 §10.4.2.1 2b) | `McdataService.send`, `McdataTxLimits`, `McxClient.mcdataTxLimits` | `McdataRulesTest`, `McdataMediaServiceTest.theSignallingPlaneLimitIsTheServiceConfigurations`; S60 |
| Transmission control: a one-to-one SDS above `<max-data-size-sds-bytes>` and a one-to-one FD above `<max-data-size-fd-bytes>` of the service configuration are refused locally; a group SDS or FD is checked against the group document read from the GMS for the request: `<mcdata-allow-transmit-data-in-this-group>` for the user (the `<cp:ruleset>`), `<mcdata-allow-short-data-service>`, `<mcdata-on-network-max-data-size-for-SDS>` / `-for-FD>`, `<mcdata-allow-file-distribution>`, and `<preconfigured-group-use-only>` (no SDS over the media plane, no SDS session, no FD; also a REFER over the pre-established session) | TS 24.282 §11.1 2)-6), §9.2.2.2.1 3)a), §9.2.3.2.3, §9.2.4.2.3 8)a), a1), §10.2.1.1 1), §10.2.4.2.1, §10.2.5.2.3 9)a), a1); TS 24.481 §7.2.2 | `McdataGroupRules`, `McdataTransmission`; `McdataService.send`, `McdataMediaService.openSession`, `sendStandalone`, `FdMedia.send`, `FdService.send` | `McdataRulesTest`, `FdServiceTest.aFileTheTransmissionControlRefusesIsNotUploaded`; suite D.3.4, D.4.5, 6.1.23 |
| Group SDS to a temporary group: `<associated-group-id>` the constituent group of the user (its `<on-network-temporary>`) | TS 24.282 §9.2.2.2.1 3)b)iii); TS 24.481 §7.2.4.2 | `McdataService.associatedGroup`, `McdataGroupRules.associatedGroup` | `McdataRulesTest`; suite D.3.5 |
| One MIME body goes as it is, several in a multipart/mixed (the media storage discovery request, an alert cancel) | TS 24.282 §6.4 | `ClientFdPeer.send`, `EmergencyAlerts.message` | suite D.13.5 |
| Receiving: sender from `mcdata-calling-user-id`, group from `mcdata-calling-group-id`, 200 OK, deduplication by message ID | TS 24.282 §9.2.2.2.2, §9.2.1.2 | `McdataService.received` | S6 |
| Disposition: DELIVERED, READ, DELIVERED AND READ with TDU1; Sender IE always present | TS 24.282 §9.2.1.3, §12.2.1.1 | `McdataService.notify`, `display` | S6 |
| Receiving notifications, several per MESSAGE (aggregated) | TS 24.282 §12.2.1.2 | `McdataService.handle` | S6 (the group case) |

## Regroups and temporary groups

`mcptt/Regroups.kt`, one per MCPTT service, as the Linux client's `mcptt/regroup.cpp` and `core/group_management.cpp`.

| Requirement | Clause | Code | Evidence |
|---|---|---|---|
| Regroup request: MESSAGE to the PSI with the client ID and the regroup document (annex F.7.2: `<preconfig-group-id>`, `<mcptt-regroup-uri>`, groups or users, `<regroup-action>`); a removal names only the identity | TS 24.379 §16.2.1.1, §16.2.1.2, §16.3.1.1 | `Regroups.request`, `Regroup.encode` | `McpttCallTypesTest.regroupRequestNoticeAndCall`, `GroupCallRulesTest.regroupDocument`; S30 (bob refused with 160) |
| Regroup notices from the participating function: stored or forgotten, the preconfigured group's document read for its configuration; a notice from elsewhere refused | TS 24.379 §16.2.1.3, §16.2.1.4, §16.1 | `Regroups.handle`, `McpttService.handle` | `McpttCallTypesTest.regroupNoticeFromAnotherSourceIsRefused`; S30 |
| A call on a regroup or temporary group names the constituent group it is placed from in `<associated-group-id>`; a chat regroup carries a chat call | TS 24.379 §10.1.1.2.1.1 step 14 d), §10.1.2.2.1.1 step 13 d) | `Regroups.callingConstituent`, `configurationOf` | `McpttCallTypesTest.regroupRequestNoticeAndCall`, `GroupCallRulesTest`; S30, S31 |
| Temporary group formation (POST of the GMOP group regroup creation request to the user's tree, `<on-network-temporary>`) and tear down (DELETE); the OpenMCX servers notify it as a regroup of its own preconfigured group | TS 24.481 §6.3.14.2, §6.3.15.2, §7.3.4.3 | `Regroups.temporaryCreate`, `temporaryDelete`, `TemporaryGroups` | `GroupCallRulesTest.temporaryGroupRequest`; S31 (bob refused with 403) |
| Group documents: the type (chat, from `<on-network-invite-members>` or the OpenMCX extension), `<preconfigured-group-use-only>`, the members' `<user-priority>`, `<on-network-temporary>`, `<on-network-regrouped>` | TS 24.481 §7.2.2, §7.2.4.2 | `GroupDocument.parse` | `GroupCallRulesTest.groupDocumentPhase2Parts` |
| The Call tab lists the regroups and temporary groups with their constituent groups, called like groups | — | `ui/UiModel.kt` `snapshot`, `ui/Screens.kt` `TalkScreen` | S30, S31 (through the debug control) |

## MCData media plane (phase 4)

MSRP (RFC 4975) in `core/…/msrp/`; the procedures in `core/…/mcdata/McdataMediaService.kt`, `FdMedia.kt`, `Release.kt`.

**The phone is the MSRP active endpoint.** It never listens (one outbound flow), so it always connects:
- **Offers.** Its offers carry `a=setup:actpass` (TS 24.282 §9.2.3.2.1 1g). The MCData server always answers `passive`:
  it listens on both legs (openmcx-server `media_offer`), and it answers 488 to an offer that would make it connect.
  The phone then connects and binds the connection (TS 24.582 §6.1.1.2.1 step 2a, RFC 6135 §4).
- **Answers.** Offered a stream, the phone answers `active`, port 9 (RFC 4145) and connects.
- **Limit.** A peer that answers or offers `active` would need the phone to listen. That communication fails, or is
  refused with 488. So a test that wants the UE as the passive endpoint (the suite's D.12.3) cannot pass.
- **Transport.** The OpenMCX server's MSRP is TCP (`msrp:`). `msrps:` is supported with the flow's TLS context, but
  untested.

| Requirement | Clause | Code | Evidence |
|---|---|---|---|
| MSRP SEND, REPORT and responses; Byte-Range; continuation flags; incremental decoding of binary bodies | RFC 4975 §6, §7, §9 | `msrp/Msrp.kt` | `MsrpTest` |
| Active endpoint: connect, bind with an empty SEND and wait for its 200 | RFC 6135 §4; TS 24.582 §6.1.1.2.1 steps 1-4 | `MsrpSession.connect` | `MsrpTest`; S60 |
| Chunking (2048 octets), abort (`#`) on a refused chunk, the reassembly of received chunks | RFC 4975 §5.1, §7.1.1 | `MsrpSession.send`, `receivedSend` | `MsrpTest`; S60, S64 |
| Transaction responses (Failure-Report no or partial), the success REPORT (Success-Report yes), 481 for another session | RFC 4975 §7.1.2, §7.3.1 | `MsrpSession` | `MsrpTest` |
| Several MCData bodies in one multipart/mixed SEND | TS 24.582 §6.4.1 | `MsrpSession.sendParts` | `McdataMediaServiceTest`; S60-S63 |
| SDP: `m=message` TCP/MSRP `*`, `a=path`, `a=setup`, `a=accept-types`, direction, `a=key-mgmt`, the RFC 5547 file attributes | TS 24.282 §9.2.3.2.1, §9.2.3.2.2, §10.2.5.2.1 | `mcdata/MsrpSdp.kt` | `McdataMediaServiceTest` |
| Standalone SDS over the media plane, one-to-one and group: INVITE (`a=sendonly`), one SEND of signalling and payload, BYE with Reason cause 200 "transmission succeeded" | TS 24.282 §9.2.3.2.3; TS 24.582 §6.1.1.2 | `McdataMediaService.sendStandalone`; `sds-media` | `McdataMediaServiceTest`; S60, S61, S67 |
| Receiving it: answer `active`, `a=recvonly`, Require timer, the SDS tag and ICSI in Contact; the message delivered as on the signalling plane, its notification by MESSAGE | TS 24.282 §9.2.3.2.2, §9.2.3.2.4; TS 24.582 §6.1.1.3.2 | `McdataMediaService.handle`, `answer` | `McdataMediaServiceTest`; S60, S61 |
| SDS sessions, one-to-one and group (`a=sendrecv`): opening, sending with the Sender IE, release by BYE; received ones joined | TS 24.282 §9.2.4.2.3, §9.2.4.2.4, §6.2.8.1.17; TS 24.582 §6.1.2.4 | `openSession`, `sessionSend`, `close`; `sds-session` | `McdataMediaServiceTest`; S62, S63, S67 |
| Notifications of a message received in a session go in the session while it lasts | TS 24.582 §6.1.2.5 | `ReceivedSds.inBand`, `McdataService.notify` | `McdataMediaServiceTest`; S62 |
| Emergency and imminent peril sessions: `<emergency-ind>`/`<imminentperil-ind>` (and `<alert-ind>` "false" without an alert) and the Resource-Priority in the INVITE; raised or cancelled by re-INVITE (a group cancel with the normal Resource-Priority, a one-to-one one with the emergency one, as Linux); the server's re-INVITE followed, its 200 (OK) with `+g.3gpp.mcdata` and the MCData ICSI for a group | TS 24.282 §6.2.8.1.1-§6.2.8.1.3, §6.2.8.1.9, §6.2.8.1.11, §6.2.8.1.12, §6.2.8.3.2, §6.2.8.3.3, §6.2.7.4 7)-8), §6.2.8.4.2, §6.2.8.4.3 | `openSession`, `setPriority`, `reinvited`, `priorityFromServer`; `sds-session-priority` | `McdataMediaServiceTest`; S65 |
| The priority state machines: MDEG, MDEGC, MDEA, MDIG, MDIGC, MDEPC, MDEPP (as the Linux client's priority.cpp), moved by the request, its answer (granted; refused: the states back; 2xx with warning 149: the imminent peril request waits), the SIP INFO of the g.3gpp.mcdata-info package, the received INVITE, re-INVITE or in-progress notification, the pre-established session's grant and release; a plain group communication on a group in the in-progress emergency or imminent peril state carries its Resource-Priority; an FD invitation cancelled unanswered restores the states | TS 24.282 §6.2.8.1.4, §6.2.8.1.5, §6.2.8.1.13, §6.2.8.1.16-§6.2.8.1.18, §6.2.8.3.4, §6.2.8.3.5, §6.2.8.4.1, §6.3.7.1.2, §9.2.4.2.3 8)e)-f), §9.2.4.2.4 4A), 4B), §9.2.5.3.1.1 3B), 3D), §10.2.5.2.3 9)e)-f); annex G.4 | `McdataPriority`, `McdataMediaService.priority`, `FdMedia.send`, `EmergencyAlerts.onGroupState` | `McdataPriorityTest`, `McdataMediaServiceTest`, `PreEstablishedTest` |
| FD over the media plane, sending and receiving (see [ATTACHMENTS.md](ATTACHMENTS.md#fd-over-the-media-plane-phase-4)) | TS 24.282 §10.2.5.2; TS 24.582 §7 | `FdMedia`; `fd-media`, `fd-media-answer` | `McdataMediaServiceTest`; S64, S67 |
| Communication release: received intent to release (the remaining data query answered), extension request and response; sent extension request, the authorised user's release, intent to release, extension response | TS 24.282 §13.2.4 to §13.2.6, §15.1.10.1 | `Release.kt`; `mcdata-release` | `McdataMediaServiceTest`; S66 |
| FD over HTTP: release and extension (FD HTTP TERMINATION) | TS 24.282 §6.2.2.4, §13.2.4.5, §13.2.5.3, §13.2.6.3, §15.1.13.1 | `FdService.terminate`; `fd-terminate`, `fd-release`, `fd-extend`, `fd-release-intent`, `fd-extension-response` | `FdServiceTest`; S64 |
| PCK in `a=key-mgmt` (one-to-one) and GMK (group) for media-plane payloads and files | TS 33.180 §8.3, §8.4, §8.5.4 | `McdataMediaService.protect` | S60-S64 on a protected lab (`p4b-p`, `fin-p`); a received protected file is reported with the clear file's size and hash, the series transferred apart (`transferred_size`, `transferred_sha256`) |
| MCData signalling protection of MSRP bodies: the signalling of a SEND to the server sealed under the CSK, the server's opened; clear signalling while protected refused, the SEND dropped; nothing sealed past the CSK's Expiry time | TS 24.282 §6.6.3.2; TS 33.180 §8.5.1, §8.5.4, §9.5.3, annex E.6.10 | `SignallingProtection.sealMsrp`, `openMsrp`; `McdataMediaPeer` | `MsrpSignallingProtectionTest`; S83, S60-S63 on a protected lab |
| Ad hoc group standalone SDS on the signalling plane: `ad-hoc-group-sds`, client ID, the participants in a resource list or `<comn-participants-criteria>`, or the ad hoc group already created; the ad hoc group of the 2xx kept | TS 24.282 §9.2.2.2.1 step 3A | `Adhoc.kt` `sendAdhoc`; `sds-adhoc` | S84 |
| Ad hoc group SDS (standalone `a=sendonly`, session `a=sendrecv`) and FD over the media plane: `adhoc-group-sds-session`, `adhoc-group-fd-session`; the ad hoc group learned from the 2xx; received, a standalone SDS and a session told apart by the offer; in the clear (no group key) | TS 24.282 §24.2.2.1.1 steps 4-7, §24.4.2.1.1 step 10 | `AdhocTarget`, `McdataMediaService.requestInfo`, `targetParts`, `establish`, `handle`; `adhoc=` targets of `sds-media`, `sds-session`, `fd-media` | `AdhocMediaTest`; S84 (also on a protected lab) |
| Media-plane communications to a functional alias: `fa=<alias>` one-to-one with `<call-to-functional-alias-ind>` and a 300 followed to the selected user, the alias then called; an alias among ad hoc participants; the sender's and the called alias of a received communication kept | TS 24.282 §22, §9.2.2.2.1 step 3A b ii, §24.4.2.2 step 15b iv | `AliasTarget`, `MediaCommunication.calledAlias` | `AdhocMediaTest`; S84 (the alias in an ad hoc list reaches client-c; one-to-one, the 300 is followed to client-c, told the alias called) |
| **Not implemented:** the emergency alert with a session is sent with the phone's last or current position, without one with no location (the alert states, MDEA, are kept, the alert MESSAGEs of §16.2 are `EmergencyAlerts`'); the in-progress emergency cancel by MESSAGE outside a communication (§6.2.7.5); ad hoc group emergency and imminent peril communications (refused locally, as the server does) | | | |

## Pre-established sessions (phase 5)

MCData in `core/…/mcdata/PreEstablished.kt` (`McdataPreSession`), MCPTT in `core/…/mcptt/McpttPreSession.kt` and
`PreEstablishedControl.kt`.

**Outbound only, checked against the server.** The Linux client keeps an MSRP listener open for its MCData session
(`keep_listening`) only in case the participating function answers `active`. The OpenMCX participating function never
does: `pre_session_invite` (openmcx-server `src/mcdata/pre_established.cpp`) answers `a=setup:passive`, refuses an offer
that would listen with 488, and keeps *its own* listener (`keep_listening`) for the session's lifetime; the client
connects to it anew for each communication (TS 24.582 §12.1, TS 37.579-1 table 5.3C.12.3-1 step 8). So the phone keeps
no listener and no idle connection: its offer is `actpass` with one host candidate, TCP active on port 9 (RFC 6544
§4.5), and it connects as for an on-demand communication. A server answering `active` would leave the session unused
(communications on demand). No server change is needed. MCPTT media are UDP from the session's own ports, as every
Android call; the server sends to the SDP addresses.

| Requirement | Clause | Code | Evidence |
|---|---|---|---|
| MCData session: INVITE with `<pre-established-session-ind>`, SDS tags, Session-Expires without refresher, `actpass` offer with the host candidate; the session URI of the 2xx's Contact; release by BYE either way | TS 24.282 §18.3.2.1, §18.3.3.1.1, §18.3.3.1.2, §18.3.1.1 | `McdataPreSession.establish`, `release`, `handle` | `PreEstablishedTest`; S70, S73 |
| Standalone SDS and SDS sessions, one-to-one and group, by REFER: cid Refer-To, the entry's hname `body` (SDP and MCData-Info protected as the request's own), Target-Dialog, Refer-Sub false, norefersub, multiple-refer; `establish-success`/`establish-fail`; left by REFER `BYE`, `terminated` | §9.2.5.2.1.1, §9.2.5.3.1.1, §9.2.5.4.1.1, §6.5.2.3.1 | `originate`, `leave`; `McdataMediaService.establishOverSession` | `PreEstablishedTest`; S70, S71 |
| A communication offered over the session (`establish-request`, 486 when busy), its release (`terminate-request`) | §9.2.5.2.1.2, §9.2.5.3.1.2, §9.2.5.4.1.2 | `handle`, `McdataMediaService.offeredOverSession` | `PreEstablishedTest`; S71 |
| Emergency and imminent peril over the session: the REFER's own MCData-Info, Resource-Priority for a group, the grant of §6.2.8.1.16 in `establish-success`, cancel by re-INVITE in the session's dialog with its media; the alert's location in the REFER's body (as Linux d44fc8b) and in the on-demand INVITE | §9.2.5.2.1.1 1a), §9.2.5.3.1.1 3A)-3D), §6.2.8.1.16, §9.2.5.2.1.3, §6.2.8.1.1 3)b) | `McdataMediaService.openSession`, `granted`, `setPriority` | `PreEstablishedTest`; S72 |
| The priority states over the session: the states before the REFER restored on `establish-fail` (§9.2.5.2.1.1 2)i)), the REFER's 4xx as §6.2.8.1.5 / §6.2.8.3.5, the grant of §6.2.8.1.16 and §9.2.5.2.1.1 3)a), the release of a request still being established (§6.2.8.1.17, §6.2.8.1.18); a plain group SDS session over the session carries the Resource-Priority of the group's in-progress state (§9.2.5.3.1.1 3B), 3D)); the 200 (OK) to the participating function's group priority re-INVITE with `+g.3gpp.mcdata` and the MCData ICSI (§6.2.7.4 7)-8)) | TS 24.282 §9.2.5.2.1.1, §9.2.5.3.1.1, §6.2.8.1.16-§6.2.8.1.18 | `McdataMediaService.establishOverSession`, `granted`; `McdataPreSession.handle` | `McdataPriorityTest`; suite D.5.12 |
| The server's BYE ends the session and a communication over it; the next SDS goes on demand. Each communication keeps its own group, peer and key, so Linux ce06d65's fault (a stale group after the server's BYE) cannot arise | §18.3.3.1.2 | `McdataPreSession.ended`; `MediaCommunication` | `PreEstablishedTest.theServersByeEndsTheSessionAndCommunicationsGoOnDemand` |
| MCPTT session: INVITE to `<psi>;mcptt-pre-established` with an SDP offer only (Accept application/sdp), host candidates for speech RTP, RTCP and floor; the session's sockets bound for its lifetime | TS 24.379 §8.2.1; TS 37.579-1 table 5.3.3.4-1; RFC 8839 | `McpttPreSession.establish`, `withIce` | `McpttPreSessionTest`; S75 |
| Modification by the user (re-INVITE or UPDATE, implicit floor request) and by the function (the offered `mc_priority` returned); release by BYE either way | §8.3.1.1, §8.3.1.2, §8.4.1.1, §8.4.1.2; TS 24.380 §14.3.3 | `modify`, `handle`, `release` | `McpttPreSessionTest`; S78 |
| Calls by REFER: group, chat, broadcast, private, first-to-answer (one entry per invitee), ambient listening; the entry's Accept-Contact or Answer-Mode/Priv-Answer-Mode, MCPTT-Info under the CSK; the Connect (Acknowledge), a Disconnect in its place; left by REFER `BYE` | §10.1.1.2.2.1, §10.1.2.2.2.1, §11.1.1.2.2.1, §11.1.6.2.2.1, §6.2.4.2; TS 24.380 §9.2.2.3.5, §9.2.2.4.2, §9.2.2.4.6 | `originate`, `leave`; `McpttService.originate` | `McpttPreSessionTest`; S75, S77, S79, S85 (chat, broadcast, first-to-answer and ambient: unit tests of the entry only, the lab has no such rights) |
| Calls received by Connect (automatic, Busy, Not Accepted for protected ones), by re-INVITE (manual: 180 for a private call, the session's media; `mc_no_floor_ctrl` answered as Linux 9eaa3ac), by an on-demand INVITE with Replaces naming the session (the session ended once the call is accepted, made again after) | TS 24.380 §9.2.2.3.2, §9.2.2.4.5, §14.2.6, §14.3.7; TS 24.379 §10.1.1.2.2.2, §6.3.2.2.5.3 step 1; RFC 3891 | `control`, `offeredByConnect`, `offeredByReinvite`, `replacedBy` | `McpttPreSessionTest`; S76, S77, S85 |
| Answers within the session during a call: the offered `mc_priority` returned, the call's priority followed | TS 24.380 §14.3.3; TS 24.379 §6.2.8.1.16 | `handle` | S85 (emergency over the session) |
| The MCPC messages Connect, Disconnect, Acknowledge and the client's state machine; one reader of the control channel, floor control handed to the call | TS 24.380 §8.3, §9.2.2, §4.1.2.1 | `PreEstablishedControl.kt`; `MediaSession.externalFloor`, `keepPorts` | `McpttPreSessionTest.mcpcMessagesRoundTrip`, `theClientsStateMachine` |
| Lifecycle: the refresh timed on the wall clock (a refresh Doze held back goes at the next wake-up); on a lost flow the dialog is kept and, at the next authorisation of the service, refreshed with the new Contact, or released and made anew | RFC 4028 §10; RFC 3261 §12.2.1.1 | `timer`, `flowLost`, `resume`; `ServiceState.authorisations` | `PreEstablishedTest.afterAFlowLossTheSessionIsRefreshedOrMadeAnew`; S74, S79 |
| A priority re-INVITE crossing the server's (491) is sent again after 2.1 to 4 s | RFC 3261 §14.1 | `McpttService.setPriority` | S85 |
| **Not implemented:** ICE connectivity checks (the phone answers no STUN; the OpenMCX server records ICE as failed and uses the default addresses); protected calls over the MCPTT session (a PCK in the Connect or the first-to-answer key re-INVITE: such calls are refused 'Not Accepted' or go on demand); FD over the MCData session (TS 24.282 has none); MCVideo pre-established sessions (out of scope) | | | |

## IP connectivity, enhanced status, message store (phase 4b)

Code in `core/…/mcdata/IpConnectivity.kt`, `EnhancedStatus.kt`, `MessageStore.kt`; the app's `McdataExtras.kt`,
`McdataCommands.kt` and `ui/McdataPanel.kt` (the Stored, Status, Ad hoc and IP link actions of Messages).

| Requirement | Clause | Code | Evidence |
|---|---|---|---|
| IP connectivity, originating: INVITE with the ipconn feature tag and ICSI in Contact and Accept-Contact, P-Preferred-Service, Supported timer, the target in a resource list, `one-to-one-ipconn`, the alias indication and the alias in use; a 300 followed | TS 24.282 §20.2.1 steps 1-12 | `IpconService.open`; `ipcon <ID>`, `ipcon fa=<alias>` | `IpConnectivityTest`; S80 |
| IP connectivity, terminating: accepted when free (486 busy, 480 without an address, 488 for a bad offer), Require timer and the ipconn Contact in the 200, the caller and its aliases kept; refresh keeps the tunnel; BYE | TS 24.282 §20.2.2 steps 3-8 | `IpconService.handle` | `IpConnectivityTest`; S80 |
| The tunnel: `m=application <port> udp MCDATA` with `mcdata-ipconn-s-port`; GRE (no key, no checksum) in UDP to the server's relay; datagrams from another source dropped; the client's own IPv4/ICMP echo endpoint as the IP application (`ipcon-ping`) | TS 24.582 §13.1.2, §13.1.3, §13.4, §13.5.2.1, §13.6.2.3; RFC 8086, RFC 2784, RFC 791, RFC 792 | `IpConn`, `IpconService.receivedDatagram`, `ping` | `IpConnectivityTest`; S80 (three pings each way through the server's relay) |
| Enhanced status, sending: the id of an operational value of a group with `<mcdata-allow-enhanced-status>` "true" as a group standalone SDS of payload type ENHANCED STATUS; refused locally otherwise | TS 24.282 §14.2.1.1, §15.2.13; TS 24.481 §7.2.4.2 | `EnhancedStatusService.send`; `sds-status`, `sds-status-values` | `EnhancedStatusTest`; S81 |
| Enhanced status, receiving: matched with the group's operational values and rendered (Messages: "Status: <value>"), else the message discarded | TS 24.282 §14.2.1.2 steps 1-3 | `EnhancedStatusService.render` (the `McdataService.accept` filter) | `EnhancedStatusTest`; S81 |
| Control of communications storage: `store-comms-in-msgstore-ctrl-req`, all private / all group, or the users and groups of a msgstore-ctrl-request body | TS 24.282 §21.3.2.2; Annex D.1, D.7 | `StorageControl`; `msgstore-ctrl` | `MessageStoreTest`; S82 (200; 403 warning 234 for client-c) |
| Message store client: the box `{root}/nms/v1/{store}/{MCData ID}` with the access token; search-based synchronisation, object and payload, search, flags, delete and bulk delete, folders, move and copy | TS 24.282 §21.1, §21.2.1.1, §21.2.2.1, §21.2.3.1, §21.2.4.1, §21.2.6.1, §21.2.7.1, §21.2.8.1, §21.2.10.1, §21.2.17.1, §21.2.23.1 | `MessageStoreClient`; `msgstore <op>`; Messages' Stored dialog | `MessageStoreTest`; S82 |
| Notification client: LongPolling channel, the box subscription naming its callback, the long poll with the current token, extension and lifetime, deletion; notified changes keep the local copy | TS 24.282 §21.2.12A.1, §21.2.13A.1, §21.2.14A.1, §21.2.19.1, §21.2.20.1, §21.2.21.1, §21.2.22.1 | `MessageStoreClient.subscribe`, `notified` | `MessageStoreTest`; S82 |
| **Not implemented:** IP connectivity to the phone's own IP stack (no `VpnService`/TUN: only the client's ICMP endpoint uses the tunnel), the implicit target by IP information (§20.2.1, the One-to-One list's `<IPInformation>`), IPv6 in the tunnel; the message store upload, retrieve, folder copy/move and notification restart (§21.2.15.1, §21.2.24.1, §21.2.14A.1 restart), FD by reference to a stored object (§10.2.2.1 NOTE 3) | | | |

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
| Negotiated-mode affiliation request received: 200 at once, the user's confirmation sought (a dialog, or `video-affiliation-accept [<group>...]` / `video-affiliation-decline`); accepted for the groups named only, each applied as in §8.2.1.2 | TS 24.281 §8.2.1.5 | `McxClient.videoProcedures` (`ServiceProcedures` for MCVideo), `McxClient.changeAffiliation`; `ui/UiModel.kt` | `McvideoProceduresTest.negotiatedRequestAcceptedPerGroup`, `deaffiliationFromOneGroup`; V6 (relayed with `--remote-affiliation`; bob accepts it for the group) |
| De-affiliation from one group (`video-deaffiliate <group>`) and affiliation to one (`video-affiliate <group>`): the PUBLISH lists the groups still wanted, Expires 0 when none is left; the change confirmed by the next NOTIFY; kept across a new registration | TS 24.281 §8.2.1.2 steps 4 and 5, §8.2.1.3 | `McxClient.changeAffiliation`, `affiliate`, `wantedGroups` | `McvideoProceduresTest.deaffiliationFromOneGroup`; V6 |
| Another user's MCVideo affiliation (subscription), and the change for them in mandatory or negotiated mode, sent with the mcvideo-info, the MCVideo PSI and `application/vnd.3gpp.mcvideo-affiliation-command+xml` | TS 24.281 §8.2.1.2, §8.2.1.3, §8.2.1.4 | `ServiceProcedures.affiliationSubscribe`, `affiliateRemote` | `McvideoProceduresTest.requestsForAnotherUser`; V6 |
| An ad hoc group call's transmission control under the MCVideo CSK when floor control is protected | TS 33.180 §9.4.2 | `McvideoService.originateOnce`, `protection` | V2 on a protected lab (`fz-p`, `ae-p`) |
| End-to-end protected MCVideo ad hoc call: `<end-to-end-security>` "true" with protected media; the media under the GMK of the `<preconfigured-group-id>` of the 2xx (originator) or of the INVITE (invitee, else 488); `mcvideo.adhoc_security_groups` subscribed from the start | TS 24.281 §22.1, §22.2.2.1.1 step 10 d) iii) and 2xx step 3, §22.2.2.1.2 step 8; TS 33.180 §5.7, §7.3 | `McvideoService.originateOnce`, `incoming`, `adhocGroupKey`; `AdhocSecurity` | `AdhocSecurityTest`; V2 on a protected lab (`ae-p`) |
| One `o=` per call: the same session ID, the version one higher in every offer and answer of the call (re-INVITE offers to change the priority, answers to the server's re-INVITEs) | RFC 3264 §8, RFC 4566 §5.2 | `McvideoSdp.offer`/`answer` with `VideoCall.origin` (MCPTT's `McpttSdp.Origin`) | `McvideoSdpTest.originKeepsTheSessionAndIncrementsTheVersion`; suite 6.1.1.1, 6.1.1.2, 6.2.1, S.8.2 |
| Media reception end request (and response) with the User ID, Audio SSRC and Video SSRC of the transmitting user; the reception override answered with one | TS 24.581 §6.2.5.5.3 step 1b, §6.2.5.5.4, §6.2.5.5.5, §9.2.26, §9.2.27 | `TransmissionParticipant.sendReceptionEnd`, `TransmissionMessage.receptionEnd` | `TransmissionTimersTest.receptionEndNamesTheTransmitter`, `receptionOverrideEndsTheReception`, `serverReceptionEndResponseNamesTheStream`; suite 6.1.1.11, 6.1.1.13, S.9.6 |
| No fmtp parameter in an answer that the offer lacked: `mc_implicit_request` only when offered (a pulled user's request otherwise follows at T100 unless granted first) | TS 24.581 §14.3.1; TS 24.281 §12.2.2.2 | `McvideoSdp.answer` | `McvideoSdpTest.answerCarriesTheImplicitRequestOnlyWhenOffered`; suite 6.4.2 |
| A remotely initiated ambient viewing offer has `mc_implicit_request` (the grant asked for the viewed-to user), without `mc_granted` and without a pending request of its own | TS 24.281 §15.2.1.1 step 13, §6.4; TS 24.581 §14.2.4 | `LocalVideoMedia.implicitForPeer`, `McvideoService.originateOnce` | `McvideoSdpTest.remoteInitAmbientOfferRequestsTheGrantForThePeer`; suite 6.7.1 |
| The called user of a video push answers `recvonly` (a `sendrecv` offer) | RFC 3264 §6.1; TS 24.281 §13.2.2.2 | `McvideoSdp.answerDirection(receiveOnly)` | `McvideoSdpTest.pushIsAnsweredReceiveOnly`; suite S.1.1, S.1.3 |
| Priority requests authorised by the MCVideo user profile, refused locally (nothing sent): the `<ruleset>` rights and the target entries (`<MCVideoGroupInitiation>` of `<EmergencyCall>`/`<ImminentPerilCall>`, `<EmergencyAlert>` with the group document's `<mcvideo-allow-emergency-alert>` for "UseCurrentlySelectedGroup", `<MCVideoPrivateRecipient>`); ad hoc rights; cancellations. Not checked before the profile is read | TS 24.281 §6.2.8.1.6, §6.2.8.1.7, §6.2.8.1.8, §6.2.8.1.10, §6.2.8.1.22, §6.2.8.1.27, §6.2.8.3.1.1, §9.2.1.2.1.4 1) b), §11.2.1.1; TS 24.484 §9.3.2.1, §9.3.2.3; TS 24.481 §7.2.2 | `McvideoAuthorisation`, `McvideoProfile`, `McvideoService.authorise` | `McvideoAuthorisationTest`, `McvideoPriorityAuthorisationTest`; V3; suite S.8.1, S.12.2, S.5.13 |
| Emergency alert area notice (entering raises the alert on the selected group unless the user is in the MCVideo emergency state: `McvideoService.emergencyState`, set by this client's alert only) and group geographic area notice (affiliation change), for MCVideo | TS 24.281 §11.2.1.4, §11.2.1.5 1)a)ii) | `EmergencyAlerts.area` with `serviceGroupAlert`, `McvideoInfo` | `McvideoPriorityAuthorisationTest.alertAreaNoticeAndAdhocAlert`, `AlertInfoTest.mcvideoAreaNotices` |
| An MCVideo ad hoc group emergency alert always carries the location (refused locally without one) and needs the profile right | TS 24.281 §12.1A.1.1 5), §12.1A.1.4 5), §6.2.9.1, §6.2.8.1.27 | `EmergencyAlerts.sendAdhoc`, `modifyAdhoc`, `cancelAdhoc` | `McvideoPriorityAuthorisationTest.alertAreaNoticeAndAdhocAlert` |
| **Not implemented:** other KMSs' certificates (KMS CertCache); ad hoc participant modification | | | |

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

- **Server faults found on the Android labs, fixed on openmcx-server branch `android-server-faults`** (not yet on
  the server's main; images `openmcx-all:srvfix-a-a3db6b4` MCData, `srvfix-b-be65d35` MCPTT, `srvfix-c-c29be0a`
  affiliation with SIP core `openmcx-kamailio:6.1.4-13`, all of them in `srvfix-d-bb306f0` with SIP core
  `openmcx-kamailio:6.1.4-14`; TEST_REPORT.md, "Server fixes" and "Final regression on the server fixes"). Faults of
  `mcdpre-800c657`:
  - **A one-to-one media-plane communication to a functional alias** was taken as a user (403 warning 229); the
    server now answers 300 naming the user who activated it (TS 24.282 §9.2.3.4.4 step 6a1), and the client invites
    that user (S84). The target's INVITE carries the `<called-functional-alias-URI>` alice sent (TS 24.282
    §9.2.3.2.3), so client-c is told the alias called (S84 checks it).
  - **MCData on a protected lab after an app restart** was refused 403 warning 140 (the new service authorisation
    opened under the old binding's CSK); the server now opens it with the CSK it uploads (TS 33.180 §9.2.1.3).
  - **Notifications of a standalone SDS over the media plane** were refused 403 warning 216; they are now correlated
    and answered 202 (TS 24.282 §12.2.3), and reach the sender (S60, S61). A declined media-plane FD is answered with
    the recipient's 480 and warning, not 487 (S64); an SDS above the group's limit gets MSRP 403. A one-to-one SDS
    over the media plane is now held to `<max-data-size-sds-bytes>` too (TS 24.582 §6.3.1.3 step 1: MSRP 403, not
    forwarded); the lab fixture's limit is 4096 octets, above the 1000-octet signalling-plane limit, and no scenario
    sends a longer one-to-one SDS (the longest, S60's, is about 3000).
  - **A stale reg event document after "rejected":** the SIP core's first NOTIFY on a new reg event subscription is
    now the current registration's. The client still ignores a contact whose `callid` is another registration's
    (harmless, kept; S19).
  - **A group call made while the last one is being released** joined it and its caller got a BYE; the server now
    creates a new session (TS 24.379 §10.1.1.4.2 steps 9 and 14) and invites the remaining members once they are out of
    the old one. S16 checks a quick re-call: a new session, no BYE.
  - **Affiliation of another user:** one status subscription per user (a second replaced the first) and every
    subscription to another user refused 403; now one per dialog, granted with `<allow-request-affiliated-groups>` or
    `<allow-request-to-affiliate-other-users>` (§9.2.2.2.4; the fixture's `--remote-affiliation`). The negotiated-mode
    request (§9.2.1.4) was not relayed; it now reaches the target with the requester in `<mcptt-calling-user-id>`
    (§9.2.2.2.8), which the client shows as the sender (S23, V6).
  - **MCPTT ad hoc calls on a protected lab:** floor control is now protected under each user's CSK when the server's
    `security.protect_floor` is set (TS 33.180 §9.4.2); S5 passes on the protected lab.
  - **A third-party deregistration after a quick new registration:** the late deregistration of the old binding
    (bob, "deactivated", registered again within ms) removed the new one at the MCPTT server (calls 480, his
    affiliation 404), and the SIP core's first reg event NOTIFY could still be the old binding's. The servers now end
    a binding only if the deregistration names its registration's Call-ID (TS 24.229 §5.4.1.7), and the SIP core's
    first NOTIFY is the registrar's current state whenever it has a contact (Kamailio patch 0010,
    `openmcx-kamailio:6.1.4-14`). S19 is checked repeatedly.
  - **Private call services:** `<call-transfer-ind>` and `<call-forwarding-ind>` are passed on to the target
    (TS 24.379 §6.3.3.1.2 step 11), and a forwarded call's Priv-Answer-Mode Manual becomes Answer-Mode Manual towards it
    (§11.1.1.3.1.1 step 18): the target rings (S37-S39).
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
  presented. MCPTT: private, ad hoc, prearranged and chat group calls present it, and a private call can go to an alias (§11.1.1.2.1.1 step 14c,
  the server's 300 then the call). MCVideo: calls present it, and a private video call can go to an alias. MCData: SDS
  present it, and a one-to-one SDS can go to an alias (§9.2.2.2.1 step 2b ii). The aliases come from the provisioning,
  else from the MCPTT user profile's <FunctionalAliasList> (TS 24.484 §8.3.2.1). Location based activation is in
  "Location rules" above. Not implemented: the per-alias user status subscription, and MCData file distribution to an alias.
- **Location rules (limits):** the group configuration rules of §9.2.1.7.3 are evaluated for MCPTT only (TS 24.282
  §8.2.6.3 is not, as in the Linux client); MCVideo evaluates its aliases' location criteria but no affiliation rules
  and does not act on the MCVideo group geographic area notice (TS 24.281 §11.2.1.4: the McvideoInfo fields are
  phase 7's); no hysteresis (TS 24.282 §8.2.6.2 NOTE).
- **MCPTT call services (phase 3, limits):** the announced transfer (§11.1.8.2.3: hold, the call to the target and
  Replaces) is not implemented, only the unannounced one; the step 1A check of manual forwarding (`<forwarding-other-list>`)
  is not made; a remotely initiated group call goes to the group only when this client is affiliated to it and idle;
  the server-side forwarding settings (immediate, no answer, not reachable) are the user profile's, set by the
  administrator: the app's Settings only choose the target its Forward action offers first. The OpenMCX server's user
  profile names the manual forwarding right `<allow-call-forward-manual-input>` rather than `<allow-call-fwd-manual-any>`.
  A forwarding request that arrives before the 480 of the call it forwards (ms apart) takes that call's answer mode and
  sequence number once it has ended (`PrivateServicesTest.forwardingRequestBeforeThe480`).
- **Not implemented:**
  - group-broadcast and user-broadcast groups as their own call kind (a broadcast call on them is made as on any
    group); originating protected first-to-answer calls (§11.1.1.2.1.1, the 200 step 2 PCK)
  - MCData data streaming (IP connectivity, enhanced status and the message store: phase 4b above)
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
- **Fixed from the suite's Android MCPTT run p7** (branch `android-q1-mcptt`; TEST_REPORT.md "MCPTT conformance
  fixes"): the alias in use on group calls (6.1.1.19, 6.1.2.13, 6.1.2.14, P.8.12), `<preconfigured-group-use-only>` of
  a group affiliated later (6.1.1.22), the hidden local-init ambient listening call (6.2.16), `<call-to-functional-alias-ind>`
  "false" (P.4.6), the ad hoc alert rights and location (P.5.1), a single body sent as itself (P.10.2). The rest of that
  run's MCPTT failures are bench or suite faults, listed there.
- **Plan changes:**
  - The interactive login follows RFC 8252 directly (Custom Tab and loopback redirect), not through the AppAuth library.
    The core already had the OIDC logic, so the library would have added nothing.
  - History uses SQLite, not Room, to avoid an annotation processor in the build.
- **NAT:** the MCPTT server does not latch media and checks the SDP address, so the UE must be directly reachable for
  UDP. The lab puts every Android client on the server network. Clients behind NAT need server support that OpenMCX
  lacks.
- **Flow reuse at the SIP core** is a lab overlay (`lab/androidlab.py`), not part of the OpenMCX configuration.

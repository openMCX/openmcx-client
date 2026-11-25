# Plan: the Android client at par with the Linux client

Baseline: Linux client `main` 5bda79f (2026-11-25), as [linux/docs/FEATURES.md](../../linux/docs/FEATURES.md) lists it.
Android: `main` at the same commit; its own state is in [CONFORMANCE.md](CONFORMANCE.md) and the feature documents.
The two share no code, so each item is written again in Kotlin from the clause, with the Linux client as the reference
behaviour and its server lab scenario as the cross-check.

"At par" means the same procedures against the OpenMCX servers. The operator surface stays Android's: the screen, PTT
keys and notifications, and a `DebugControl` command for every procedure so the labs can drive it as they drive
`mcx-client --command`.

## Where the two stand

| Area | Linux | Android | Gap |
|---|---|---|---|
| Start-up, registration, reg event, log-off, recovery | yes | yes | Retry-After on a refused authorisation, `authorise_by_register`, P-Access-Network-Info (planned in CONFORMANCE.md) |
| Service settings (TS 24.379 7.2.3, 7.2.4) | settings-only PUBLISH, settings subscription | poc-settings with the authorisation only | settings PUBLISH and subscription |
| Affiliation | own groups, `monitor` groups, remote affiliation (9.2.1.2-9.2.1.5), GDD subscription (9.2.1.6), rules based affiliation (9.2.1.7) | own groups | monitor, remote, GDD, rules based |
| MCPTT private calls | automatic, manual, forced, with and without floor control, first-to-answer | the same (first-to-answer in phase 2) | none |
| MCPTT group calls | prearranged, chat, broadcast, ad hoc (participants or criteria, modification) | the same (phase 2) | none |
| Floor control | priority, queueing, pre-emption, `mc_granted`, Floor Indicator by call type and bit F | the same (Floor Indicator and `mc_granted` in phase 0, priority and pre-emption in phase 2) | none |
| MCPTT call services | ambient listening, remotely initiated calls, call-back, transfer, forwarding | none | all five |
| Groups | regroups, temporary groups | both (phase 2) | none |
| Emergency, imminent peril, alerts | all three services, ad hoc group alerts | MCPTT and MCVideo calls, alerts of the three services | MCData emergency and imminent-peril communications |
| Functional aliases | all, location based activation | all but location based activation, per-alias status, FD to an alias | location based, FD to an alias |
| Pre-established sessions | MCPTT (TS 24.379 8, ICE), MCData (TS 24.282 18.3, emergency included) | none | both |
| MCData SDS | signalling plane and media plane (MSRP), SDS sessions, ad hoc | signalling plane, one-to-one and group | MSRP, SDS sessions, ad hoc |
| MCData FD | HTTP and media plane, deferral, extension, release | HTTP | FD over MSRP, release, extension |
| MCData other | IP connectivity, enhanced status, communication release, message store, notification channels | none | all |
| Location | the three services, location rules, group geographic areas, SEAL (TS 24.545) | reporting and requests, alert areas | rules, group geographic areas, SEAL |
| MCVideo | see FEATURES.md | calls, push and pull, ambient viewing, remote transmission, protected video | XML signalling protection, timers T100-T104, priority, rejoin, ad hoc emergency, settings and remote affiliation, GDD, KMS redirect |
| End-to-end protection | GMK, PCK, XML signalling for the three services | GMK and PCK media, XML signalling for MCPTT and MCData, protected MCData payloads | MCVideo XML signalling, protected FD |

## Phases

Each phase ends with Android lab scenarios (`tests/scenarios.py`, S20 onwards) and JVM unit tests, the CONFORMANCE.md
rows updated, and a develop APK. Sizes: S up to 2 days, M up to a week, L longer.

### Phase 0: the known fixes (S)

Done for MCPTT on `android-p01` (CONFORMANCE.md, TEST_REPORT.md "Parity phases 0 and 1"); the MCVideo halves are phase 7's.

These are already listed under "To fix" in CONFORMANCE.md:
- Answer-Mode and Priv-Answer-Mode compared by token (RFC 5373 section 2), for MCPTT and MCVideo.
- `Accept: application/xcap-diff+xml` on the document unsubscribe and the MCVideo conference unsubscribe.
- The Floor Indicator by call type (TS 24.380 6.2.4.3.5) and bit F when queueing was negotiated.
- `mc_granted` in the initial offer with an implicit request, and the grant in the 200 (OK) (TS 24.380 14.2.4), for
  MCPTT and MCVideo.

Also:
- The Retry-After of a 500 or 503 on a refused service authorisation.

### Phase 1: settings and affiliation (M)

Done for MCPTT on `android-p01`, S20-S24; the MCVideo settings, remote affiliation and GDD are phase 7's.

- **Service settings:** the settings-only PUBLISH and the settings subscription (TS 24.379 7.2.3, 7.2.4), with the
  answer mode in the Settings screen. MCVideo too.
- **Affiliation:**
  - monitor affiliation to other groups of the user profile;
  - affiliation of other users: subscription, remote affiliation, and the negotiated-mode request accepted or declined
    by the user (9.2.1.2-9.2.1.5);
  - GDD subscription (9.2.1.6).
- **UI:** a group list that shows the affiliation state and the monitored groups.

### Phase 2: MCPTT call types and floor (M-L)

Done on `android-p2`, S25-S31 (CONFORMANCE.md "MCPTT", "Regroups and temporary groups"; TEST_REPORT.md "Parity phase
2"). Not done: group-broadcast and user-broadcast groups as their own call kind, and originating protected
first-to-answer calls.

- Chat group calls (TS 24.379 10.1.2) and broadcast group calls (10.1.1.2.1, the B bit).
- Ad hoc calls by participant criteria, and modification of an ad hoc call (17.2).
- First-to-answer private calls (11.1.7).
- Floor pre-emption and floor priority from the user profile.
- Regroups (preconfigured and dynamic) and temporary groups: the GMS documents and the calls on them.

### Phase 3: MCPTT call services (L)

- Ambient listening, locally and remotely initiated (TS 24.379 11.1.6).
- Remotely initiated private and group calls (11.1.7, 10.1.5).
- Call-back request (11.1.5), call transfer (11.1.8) and call forwarding (11.1.9). (The clause numbers are those of
  V19.7.0.)

Done on branch `android-p3`, except the announced transfer (11.1.8.2.3); see [CONFORMANCE.md](CONFORMANCE.md) and
[TEST_REPORT.md](TEST_REPORT.md), S32-S39.

Each needs a UI decision. Remotely initiated calls and the listened-to side of ambient listening give the user no
indication: the foreground service and microphone permission must allow that without a screen.

### Phase 4: the MCData media plane (L, the largest)

- **An MSRP stack** (RFC 4975, TS 24.582).

  **Decision:** Android never listens (one outbound flow), so the phone has to be the MSRP active side
  (`a=setup:active`, RFC 6135) and connect to the server. The Linux client listens. The server's support for an active
  UE must be checked first: if it only connects out to the UE, it needs a change.

  **Found (branch `android-p4`):** no server change is needed. The MCData server listens on both legs
  (`media_offer`, `a=setup:passive`) and refuses a passive offer with 488. The phone offers `actpass` (TS 24.282
  §9.2.3.2.1 1g), as the Linux client does, and it answers an offer with `active`. In both cases it connects.
- **SDS on the MSRP stack:** standalone SDS over the media plane above the signalling-plane limit (`McdataService.send`
  refuses those today), and SDS sessions, one-to-one and group.
- **FD over the media plane.** Also, over HTTP, FD release and extension.
- **MCData emergency and imminent-peril communications,** and the communication release.
- **IP connectivity and enhanced status.**
- **Message store and notification channels:** storage control, and retrieval in the Messages screen.

### Phase 5: pre-established sessions (L)

- **MCPTT** (TS 24.379 8, TS 24.380 9): establish, modify, release; calls over the session by REFER, received by
  Connect, re-INVITE or INVITE with Replaces.

  **Decision:** the Linux client does ICE. Android's media is UDP to the server network (see NAT in CONFORMANCE.md), so
  the first version can offer host candidates only.
- **MCData** (TS 24.282 18.3, 9.2.5), after phase 4: SDS and SDS sessions over the session, emergency and imminent
  peril included. The outbound-only point of phase 4 applies here too, because the Linux client keeps a listener open
  for the session.
- **Lifecycle:** a pre-established session survives Doze and flow loss. It is re-established after a re-registration.

### Phase 6: location rules (M)

- Location based functional aliases and rules based affiliation (TS 24.379 9A.2.1.4 and 9.2.1.7, TS 24.282 22.2.1.4
  and 8.2.6.2).
- Group geographic areas (TS 24.282 16.2.1.4).
- SEAL location reporting (TS 24.545).

All of these work from Android's fused location, which `LOCATION.md` already uses.

### Phase 7: MCVideo remainder and protection (M)

- MCVideo XML signalling protection (VIDEO.md "Not yet", TS 33.180 9.3).
- Transmission timers T100-T104 and counters C100-C104.
- Transmission and reception priority.
- Leaving and rejoining an ad hoc call.
- Ad hoc emergency and imminent-peril calls, and their alert with criteria modification.
- Cancelling another user's emergency or imminent peril.
- The settings PUBLISH, and remote affiliation per group.
- GDD subscription.
- KMS redirect (488 with a KRR) and the target KMS of a private call.
- Protected FD (PCK).

## Verification

- **Per phase:** unit tests in `core/src/test`, and Android lab scenarios on redroid. A Linux `mcx-client` is the peer
  where the feature needs a second UE that Android does not yet have. This is the `--reference-client` the video labs
  already use.
- **Conformance:**
  - **Problem:** the TS 37.579 suite (openmcx-conformance-tests) drives only the Linux client today.
  - **Proposal:** a suite driver for Android, the same test cases with the UE actions sent as `DebugControl` commands to
    a redroid container.
  - **Benefit:** this would measure parity in the dashboard, as a run set per client, rather than by this table.
  - **When:** worth doing during phase 1, so that every later phase is measured the same way.
- **Real phones:** the two-phone labs (`PHONE.md`, `video.py`, `attachments.py`) at the end of each phase.

## Order and effort

Decided 2026-11-25: phases 0 and 1, then 2, 3 and 6, then 4 and 5, then 7. Phases that touch different code run in
parallel on their own branches, merged into `android-parity`. The suite driver for Android is built during phase 1.

Rough total: 10 to 14 weeks of work for one developer. Phases 4 and 5 are half of it.

## Out of scope

The same as for the Linux client:
- off-network;
- interconnection and migration;
- MCVideo pre-established sessions.

## Future work

- The gateway UE usage indications (`<gw-mcptt-usage>`, `<gw-mcvideo-usage>`; the Linux client's `via_gateway_ue`).

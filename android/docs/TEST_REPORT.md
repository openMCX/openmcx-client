# Android client: test report

Results recorded for the Android client in the full edition, before it was imported here; they have not all been
re-run in this repository. Every end-to-end test runs between Android clients only (the optional reference client
of `androidlab.py` is the exception). Test scripts: `tests/`; lab: `lab/androidlab.py` ([README](../README.md)).

| Suite | Where | Result |
|---|---|---|
| JVM unit tests (`tools/gradle-docker.sh :core:test`) | Docker, JDK 17 | 175 passed, 0 failed (re-run on import into this repository; 56 at the time of the original report) |
| Instrumented tests (`tools/instrumented-test.sh`) | AVD: Android 16 (API 36), google_apis, x86_64 | 11 passed (codec 2, UI 6, telecom 2, browser login 1) |
| Instrumented tests, without telecom and browser login | redroid 15 | 8 passed. Redroid's Telecom has no telephony service, so its self-managed calls stall at random |
| Lab scenarios S1-S11 (`tests/scenarios.py a1`) | Three redroid 15 clients on the OpenMCX lab, TLS | 144 of 144 checks passed, repeatedly, against several server builds |
| JVM unit tests, phase 7 (MCVideo) | Docker, JDK 17 | 424 passed, 0 failed (2026-11-25, branch `android-p7`) |
| MCVideo signalling scenarios V1-V5 (`tests/scenarios.py LAB V1 V2 V3 V4 V5`), phase 7 | Two redroid 15 clients and the Linux reference client, server `openmcx-all:mcdpre-800c657`; protected lab with XML confidentiality and integrity | 35 of 35, with S2, S3, S12 and S16 in the same run (79 of 79). See [VIDEO.md](VIDEO.md#signalling-on-redroid-phase-7) |
| JVM unit tests, after phase 7 (MCVideo affiliation) | Docker, JDK 17 | 454 passed, 0 failed (2026-11-25, branch `android-p7b`; `McvideoProceduresTest` new) |
| Scenarios S1-S6, S16, V1-V6, after phase 7 | Three redroid 15 clients, server `openmcx-all:mcdpre-800c657`; a protected lab (XML confidentiality and integrity) and an unprotected one, both `--mcvideo --priority` | Protected 134 of 136 (two S5 floor checks: MCPTT ad hoc floor control is not protected); unprotected 135 of 135. See [VIDEO.md](VIDEO.md#affiliation-and-the-re-runs-after-phase-7) |

In S9, 91-97% of the payloads arrived at each listener, all unchanged, with 52-57 reordered. In S10 the access time was
4-10 ms and mouth-to-ear 47-72 ms. Some S10 mouth-to-ear samples are large negative numbers: a listener reported no
first playout time for that burst. The check takes the maximum only, so it does not catch this; it is a gap in the
client's reporting or in the check, not a server fault.

**S16 on MCVideo labs.** S16 stopped at its imminent peril group call on `--mcvideo --priority` labs, with the APK
from before phase 7 too. Cause, from the MCPTT server's events: alice's emergency call ends with her BYE; the server
BYEs bob and client-c but keeps the session until both answer; alice's imminent peril INVITE, 40 ms later, arrives
before client-c's answer and is a "call_joined" of that session, which ends with client-c's answer, so the server
BYEs alice and bob is never invited. A server fault, fixed on openmcx-server branch `android-server-faults` (a new
session; see "Server fixes" below). S16 waited for every member of the group to be out of the call before the next
one; it now re-calls the group as soon as alice is out, and checks the new session and that alice gets no BYE.

## Environment

- **Host:** Linux, Docker.
- **Kernel modules loaded by hand:** `binder_linux` (redroid) and `sch_netem` (S9).
- **Android build:** see `gradle/libs.versions.toml` and `app/build.gradle.kts` (AGP, Kotlin, compileSdk 37, minSdk 30).
- **AMR-WB codecs:** `c2.android.amrwb.encoder` and `.decoder` on both redroid and the AVD.
- **Speech:** a deterministic synthetic speech signal (`androidlab.py speech_wav`), played from a WAV file because the
  devices have no microphone.

## Scenarios

"Unchanged" means the listener's sequence of AMR-WB RTP payload hashes equals the talker's.

| # | Scenario | Checks | Result |
|---|---|---|---|
| S1 | Start-up on all three clients | 15 | Each is registered, authorised for MCPTT and MCData, and affiliated to `operations` in both services |
| S2 | Private call, automatic commencement | 10 | Speech arrives unchanged in both directions (149 and 150 payloads). The server releases bob's leg after alice's BYE |
| S3 | Private call, manual commencement | 7 | Decline arrives as 480 with warning 110. Answer, then speech unchanged. Forced commencement is refused with 403 warning 143 (the a1 policy) |
| S4 | Prearranged group call | 17 | All three talk in turn and each listener gets the speech unchanged (150 payloads each). client-c is denied while bob holds the floor |
| S5 | Ad hoc group call | 12 | The ad hoc group identity is learned. Two talkers, and speech arrives unchanged at both listeners |
| S6 | SDS | 15 | One-to-one both ways and group. DELIVERED AND READ within TDU1; DELIVERED; per-member group DELIVERED; READ after the display indication. The sender gets no copy of its own group message. client-c's group SDS is refused with 403 warning 201 (policy) |
| S7 | Refresh and recovery | 35 | Forced REGISTER, subscription and token refreshes keep the flow and the affiliations. An 80 s private call is kept up by bob's UPDATE refreshes. A dropped flow leads to a new flow and new registration, and calls reach the client again. Coverage loss is detected by keepalive in 29.4 s; the client is ready 5.9 s after coverage returns |
| S8 | Lifecycle | 16 | Deep Doze for 30 s, then a call reaches the device and speech flows both ways. A process killed with `kill -9` is ready again in 10.8 s and joins a group call |
| S9 | 5% loss and 40±20 ms jitter on the talker (netem) | 7 | The floor is granted despite loss. 95.6–97.6% of payloads arrive (over two runs) and all of them are unchanged; 52–57 arrived out of order, and the lost frames were concealed |
| S10 | Latency | 2 | Access time (PTT press to Floor Granted) 6–9 ms. Mouth-to-ear (first RTP sent to first frame played, plus 20 ms of capture) 47–72 ms over two full runs. The audio device's own latency is not included |
| S11 | Product | 8 | A PTT key broadcast calls the selected group and takes the floor, and the speech arrives unchanged. The service types change during the call. A message notification is shown. History survives `kill -9`. Telecom state is recorded only (see above) |

## Not covered by these tests

- The real microphone, speaker and Bluetooth audio path. The platform classes are used unchanged, but a device is
  needed to judge audio quality.
- Changing networks (Wi-Fi to mobile data). Redroid cannot change its network: detaching it from Docker fails because
  of Android's policy routing. Coverage loss (S7) exercises the same reconnection path.
- Battery cost of the 25 s keepalive.
- Vendor PTT keys on real hardware. The broadcast actions are the documented ones, but the tests send them with
  `am broadcast`.
- The interactive login against the lab IdMS, which has no login page. It is tested against an in-process IdMS through
  Chrome on the AVD.

## Not covered by these tests

- The real microphone, speaker and Bluetooth audio path. The platform classes are used unchanged, but a device is
  needed to judge audio quality.
- Changing networks (Wi-Fi to mobile data). Redroid cannot change its network: detaching it from Docker fails because
  of Android's policy routing. Coverage loss (S7) exercises the same reconnection path.
- Battery cost of the 25 s keepalive.
- Vendor PTT keys on real hardware. The broadcast actions are the documented ones, but the tests send them with
  `am broadcast`.
- The interactive login against the lab IdMS, which has no login page. It is tested against an in-process IdMS through
  Chrome on the AVD.

## Phone mode

`lab/androidlab.py create --name p1 --phone client-a --phone-ip <LAN address>` ([PHONE.md](PHONE.md)). The Android 16
emulator stood in for the phone as alice, reaching the lab only through the host's published ports and the endpoint
map. bob and client-c were redroid containers.

| Check | Result |
|---|---|
| The phone reaches `ready`: IdMS, documents and SIP through the host's published ports, with the endpoint map and TLS name checks | Passed |
| S1 (all three) and S6 (SDS between the phone and the containers, both directions, with notifications) | 30 of 30 |
| A group call between bob and client-c with the server's media on the host address and the published UDP ports (hairpin NAT) | 7 of 7, speech unchanged in both directions |
| The phone's MCPTT leg | Refused by the server, `SDP address is not the authenticated client`. Expected: the emulator is behind QEMU's NAT, and a LAN phone is not |
| S1-S6 on the ordinary lab with the same APK | 76 of 76 |

The SIP core must reuse the client's flow for requests to it; see [SIP_CORE_FLOW_REUSE.md](SIP_CORE_FLOW_REUSE.md).
Media and NAT: the MCPTT server does not latch RTP to the source address, so the lab puts every Android client directly
on the lab network (no NAT).

## Real phones (2026-10-03)

Two phones on one Wi-Fi network with the host, against the `openmcx-server` images: a Xiaomi 2109119DI (Android 13)
as alice and a moto g67 (Android 16) as bob, redroid 15 as client-c (`androidlab.py create --phone-device
client-a:SERIAL:IP --phone-device client-b:SERIAL:IP`, without `--phones-only` so that client-c joins).

| Run | Result |
|---|---|
| S1-S6, S10, S12 | 90 of 94. The four: one Wi-Fi frame lost in S2; S11's two service-type checks and its process kill (below, since fixed in the tests) |
| S1, S4, S8, S11 after the test fixes | S8 and S11 pass in full; S4 lost bursts of 5-10 frames at bob, not repeated on the re-run (35 of 35 for S2, S4, S11) |
| MCX Admin lab: activation from MCX Admin codes, private, group and SDS | 16 of 16 |
| MCX Admin lab: MCVideo calls both ways | 13 of 13, every picture received identical to the one sent |
| Tutorial 1 (`openmcx-server` `tutorial/01-mcx-over-wlan-android`): users from the console's `phone-user` template, activation, private, group, SDS, video | 28 of 28 |

Fixed in the tests for phones:

- **S11, foreground service types**: Android 13's `dumpsys activity services` does not print `types=`; the app reports
  its own (`status` `fgs_types`). The idle "special use" type exists from Android 14 only, so on Android 13 the idle
  service has no type and a call adds phoneCall and microphone.
- **S8 and S11, process death without root**: the app ends its own process on the debug control's `die` (SIGKILL).
  `run-as ... kill` is not enough: MIUI's `run-as` does not see the app's process.
- **A lab without client-c** (`--phones-only`): the scenarios that need a third client (S4 onwards) are skipped with
  a message instead of failing with `KeyError`.

Open: at the end of a burst a listener phone sometimes misses the last 1-6 frames (S2, three times in about ten
bursts), although the talker sends its Floor Release 40 ms after its last frame. Whether the server stops relaying
early or the frames are lost on the Wi-Fi needs a capture on the server; it is part of the floor loss item of the load
plan.

## Android 11 and 12 (2026-10-27)

A user report: activation failed on Android 12 with "No virtual method readNBytes(I)[B in class Ljava/io/InputStream;".
`InputStream.readNBytes(int)` is API level 33 (Android 13), while the app's minSdk is 30 (Android 11). The core module
is a plain JVM module, which Android lint does not check against minSdk, so nothing had caught it.

Fixed:

- `SocketHttpClient` reads bodies with `readUpTo`, a loop on `read(byte[], int, int)` with the same result.
- The RTP parser skipped a header extension through the JDK 9 covariant `ByteBuffer.position(int)`, which the Android
  SDK has at no level. It now calls it through `Buffer`.
- `AndroidApiLevelTest` (core) checks every `java.*` method the core's classes call against the SDK's
  `api-versions.xml` at the app's minSdk, supertypes included. On the old code it fails with
  `InputStream.readNBytes(I)[B (API 33)`.

| Device | Result |
|---|---|
| JVM unit tests | 227 passed (with the API check and tests for `readUpTo` and the RTP header extension); app unit tests 10 passed |
| Android 11 (API 30): emulator, google_apis x86_64 in Docker with KVM, phone mode | Activation from an MCX Admin code, sign-in, registration, MCPTT and MCData authorisation and affiliation, SDS both ways, MC log-off and SIP de-REGISTER (200). No `NoSuchMethodError` in the log. MCPTT calls not checked: the emulator is behind QEMU's NAT (see [PHONE.md](PHONE.md)) |
| Android 12 (API 31): redroid 12.0.0 | Activation from MCX Admin codes, private call with speech (209 frames, all unchanged), SDS, MC log-off and SIP de-REGISTER (200) |
| Android 13 (Xiaomi 2109119DI) and Android 16 (moto g67), on Wi-Fi | Activation from MCX Admin codes, a group call and private calls both ways with speech, SDS both ways, MC log-off |

Not checked: Android 12L (API 32), which has no redroid image. The redroid 11 image does not boot on a host whose
`binder_linux` creates no `/dev/binder` nodes; the emulator was used instead.

## Parity phases 0 and 1 (2026-11-25)

[PARITY_PLAN.md](PARITY_PLAN.md) phases 0 and 1 (branch `android-p01`). The lab: `lab/androidlab.py create --name p01-a
--server-image openmcx-all:mcdpre-800c657 --monitor-group` (the server images of `openmcx-server` main bc650a2), three
redroid 15 clients (`redroid/redroid:15.0.0_64only-latest`), TLS.

| Suite | Result |
|---|---|
| JVM unit tests (`tools/gradle-docker.sh :core:test`) | 416 passed, 0 failed (389 before these phases) |
| `:app:assembleDebug` | Built |
| S1-S6 (regression) | 76 of 76 |
| S20-S24 | 67 of 67 |
| One run of S1-S6 and S20-S24 after the last fix | 143 of 143 |

| # | Scenario | Checks | Result |
|---|---|---|---|
| S20 | Implicit floor request (TS 24.380 §14.2.5, §6.2.4.2.2) | 19 | The INVITE's offer has `mc_implicit_request` (and `mc_granted` only next to it, when asked, §14.2.4); the server accepts the request and grants the floor by Floor Granted, once; alice's burst reaches bob and client-c unchanged but for at most its first half second, which goes out while their legs are still being set up. An explicit call's offer has neither parameter. The OpenMCX controlling function does not use `mc_granted` (§14.3.4), so the grant in the 200 (OK) is covered by `FloorParticipantTest` only |
| S21 | Service settings (TS 24.379 §7.2.3, §7.2.4) | 13 | The settings subscription is accepted and its NOTIFY names alice's client and answer mode; `answer-mode manual` sends the settings-only PUBLISH (no access token, the client ID, SIP-If-Match) and the subscription notifies the change; a private call then rings and is declined; fetch, re-subscribe and de-subscribe answered 200; bob's settings removal (Expires 0) logs him off MCPTT, and log-off and log-on bring him back |
| S22 | Monitored group (TS 24.379 §9.2.1.2) | 15 | alice and bob monitor `sip:monitor@mcx.test` and are affiliated to both groups; bob's call on it reaches alice, not client-c, with the speech unchanged; `monitor none` leaves it, `operations` stays |
| S23 | Affiliation of other users (§9.2.1.2 to §9.2.1.4) | 13 | The subscription to bob's affiliation is refused by the server with 403 (§9.2.2.2.4: no user is authorised for another's); alice's own is accepted and notified; a mandatory-mode de-affiliation of her client (Expires 0, no PIDF) and affiliation (the targeted client, no status, a p-id) are applied by the server; the negotiated-mode MESSAGE with the affiliation command gets a final answer, and the server does not relay it to bob |
| S24 | Group dynamic data (§9.2.1.6) | 7 | The subscription with the per-group filter is accepted; the NOTIFY lists alice and bob as affiliated members, and a call on the group is notified as ongoing; re-subscribe, de-subscribe and a fetch with an item filter answered 200 |

Not exercised in the lab, unit tests only: a received negotiated-mode request (§9.2.1.5; the OpenMCX servers do not
pass the MESSAGE on), the grant in the 200 (OK), the Retry-After of a 500 or 503 to the service authorisation (the lab
server does not refuse it), Answer-Mode with parameters (the server sends a plain `Auto`), the Floor Indicator's call
type and bit F, the Location field of the Floor Request, `<forwarding-sequence-number>` and the SDP version of a
subsequent answer.

## Phase 6: location rules, group geographic areas, SEAL location

Lab `p6-a`: `androidlab.py create --location --functional-alias --priority --mcvideo --location-rules`, server image
`openmcx-all:mcdpre-800c657`, three redroid 15 clients. With `--location`, every client now gets a fused fix at the
fixture's position (Android's test provider). Without it, redroid has no fix, so S13's log-on reports carried no
coordinate and bob's S14 alert had no position.

| Suite | Result |
|---|---|
| JVM unit tests (`tools/gradle-docker.sh :core:test`) | 430 passed, 0 failed (416 before this phase). New: `LocationRulesTest` (10), `LocationRuleProceduresTest` (2), `SealLocationTest` (2), and an `IdmsTest` assertion |
| `:app:assembleDebug` | Built |
| One run of S1-S6, S13, S14 and S40-S45 | 139 of 139. In an earlier run S5 lost the last 6 of 100 frames at both listeners; it then passed twice on its own, 12 of 12 each time |

| # | Scenario | Checks | Result |
|---|---|---|---|
| S40 | Location based functional aliases (TS 24.379 §9A.2.1.4, TS 24.282 §22.2.1.4) | 5 | Entering the area activates incident-commander on MCPTT and MCData. The app says it was activated by location. The user's deactivation is refused (§9A.2.1.2 lock). Leaving the area deactivates it on both services |
| S41 | Rules based affiliation (§9.2.1.7.2, TS 24.282 §8.2.6.2) | 6 | Entering the rule area de-affiliates alice from `operations` on MCPTT and MCData, and bob's group call does not reach her. Leaving it affiliates her again. Her de-affiliation is then refused (§9.2.1.2 lock) |
| S42 | Group mandatory area (§9.2.1.7.3) | 4 | The group's area is read from the GMS. Outside it, alice de-affiliates and an affiliation is refused. Back inside, she affiliates again |
| S43 | Group geographic area notices (§12.1.1.4, TS 24.282 §16.2.1.4) | 5 | The server's notices name the group on both services. Leaving de-affiliates her, and entering again affiliates her again |
| S44 | Emergency alert area (§12.1.1.6, TS 37.579-2 6.6.4) | 4 | The notice raises alice's MCPTT alert with no user request. Bob receives it at the area's position. Leaving the area leaves the alert up |
| S45 | SEAL location (TS 24.545 §6.2.2.2, §6.2.20.2) | 4 | The configuration is read from the LMS. Periodic reports are accepted with 200. Positions are stored offline, then sent in a history location result report |

Not exercised in the lab, unit tests only:
- the permitted geographic area;
- a rule's functional alias criterion, when the alias is activated;
- `<Speed>` and `<Heading>` areas;
- conflicting rules;
- MCVideo aliases with location criteria (the lab has none).

Two problems were found in the lab and fixed:
- The LMS refused the SEAL configuration with 403. The token had no `3gpp:mc:location_management_service` scope.
- Area notices were indexed by position in a list capped at 20 entries. The scenarios now use each notice's time.

## Parity phase 2 (2026-11-25)

[PARITY_PLAN.md](PARITY_PLAN.md) phase 2 (branch `android-p2`): MCPTT call types, floor priority and pre-emption,
regroups and temporary groups. Three labs, each three redroid 15 clients (`redroid/redroid:15.0.0_64only-latest`) on
`openmcx-all:mcdpre-800c657`, TLS, run one after the other:

- `p2-a`: `lab/androidlab.py create --name p2-a` (the default fixture), the regression and S28;
- `p2-f`: `--priority --chat --broadcast` (the private call policy: client-a may make first-to-answer calls; bob may
  pre-empt the floor at priority 75 or more, his floor priority 90, the others' 10);
- `p2-g`: `--regroup` (constituent groups north with client-a and client-b, south with client-c; client-a may regroup
  and form temporary groups).

| Suite | Result |
|---|---|
| JVM unit tests (`tools/gradle-docker.sh :core:test`) | 439 passed, 0 failed (416 before this phase) |
| App unit tests (`:app:testDebugUnitTest`) | 13 passed |
| `:app:assembleDebug` | Built |
| S1-S6 (regression, `p2-a`) | 76 of 76 |
| S28 on `p2-a` | 19 of 19 |
| S1, S25-S29 on `p2-f` | 90 of 90 |
| S30, S31 on `p2-g` | 41 of 41 |

| # | Scenario | Checks | Result |
|---|---|---|---|
| S25 | First-to-answer (TS 24.379 §11.1.1.2.1.1, §11.1.1.2.1.2) | 23 | alice's INVITE names bob and client-c with session type first-to-answer, no Answer-Mode or forwarding sequence number; both ring (before, the app answered such an INVITE with 400, TS 37.579-2 6.2.21); bob, then client-c in a second round, answers first and takes the call, the other's invitation ends; speech both ways unchanged; bob, without the right, is refused |
| S26 | Chat group call (§10.1.2.2.1.1, §10.1.2.2.3.1) | 16 | alice joins with session type chat and nobody is invited; bob and client-c join the same call; bob leaves and joins again while the others stay; speech unchanged; the call ends when one participant remains (the server's §6.3.8.1 item 2) |
| S27 | Broadcast group call (§6.2.8.2) | 10 | `<broadcast-ind>` true in alice's INVITE; bob and client-c receive a broadcast call; bob is denied the floor (cause 5, receive only); alice's burst reaches both, and the server ends the call with it |
| S28 | Ad hoc call by criteria and its modification (§17.2.2.1.1, §17.2.6.1.1, §17.2.4.1.1, §17.2.5.1.1) | 19 | the criteria `affiliated:sip:operations@mcx.test` and no resource list; bob and client-c are called; client-c removed (`;method=BYE`) and added again; the criteria changed; bob leaves and rejoins by the session identity and talks |
| S29 | Floor priority and pre-emption (TS 24.380 §14.3.3, §6.2.4.5.4) | 7 | bob's offer has `mc_priority=90`, negotiated 90; while alice (10) talks, bob's request revokes her and he is granted; alice sees the revoke, then bob take the floor; client-c (10) does not pre-empt alice |
| S30 | Regroup (§16.2.1.1-§16.2.1.4, §10.1.1.2.1.1 step 14 d)) | 24 | bob is refused (160); alice's regroup of north and south is created and all three are told, with both groups; a call on north is refused (148); alice's call on the regroup names north in `<associated-group-id>` and reaches bob and client-c; speech both ways; the removal is told to all and north carries a call again |
| S31 | Temporary group (TS 24.481 §6.3.14.2, §6.3.15.2) | 17 | bob is refused (403); alice's temporary group of north and south is formed (200), the three are told; its document lists both constituents; a call on it reaches bob and client-c with the speech unchanged; the tear down (200) is told to all |

Not exercised in the lab, unit tests only: a received BYE with "not selected for call" (the OpenMCX server sends it only
when two answers cross), a protected first-to-answer call, the Floor Release's G-bit after a dual-floor revoke, the
floor priority from the group document or the user profile (the lab's comes from the provisioning), and
`<preconfigured-group-use-only>` refused locally (the regroup lab's preconfigured group is never called directly).

## Phase 4: the MCData media plane

Lab `p4-a`: `androidlab.py create --priority --reference-client client-d --client-image openmcx-client:mcdemerg-ce06d65`,
server image `openmcx-all:mcdpre-800c657`, three redroid 15 clients and the Linux client as client-d. The fixture's
`--mcdata` policy applies:
- alice may send one-to-one up to 300 bytes, and bob without a limit;
- bob receives from alice only;
- the group takes SDS up to 400 bytes and files up to 2000 bytes, and client-c may not transmit on it;
- client-d may not transmit at all;
- bob may release any user's communication.

| Suite | Result |
|---|---|
| JVM unit tests (`tools/gradle-docker.sh :core:test`) | 494 passed, 0 failed. New: `MsrpTest` (9), `McdataMediaServiceTest` (17), 3 in `FdServiceTest` |
| `:app:assembleDebug` | Built |
| One run of S1, S6 and S60-S67 | 93 of 93 (S64 with its HTTP release part) |

| # | Scenario | Checks | Result |
|---|---|---|---|
| S60 | Long one-to-one SDS (3000 octets, above the 1000 octet signalling limit; TS 24.282 §9.2.1.1, §9.2.3) | 7 | Bob's SDS goes over MSRP in two chunks, with the BYE cause 200. Alice gets it whole, marked as media plane. The server releases her leg. She sends DELIVERED by MESSAGE (the server answered 403 216, see below; 202 since the server fix, and bob gets it) |
| S61 | Group standalone SDS over the media plane | 6 | Bob and client-c receive it on `operations`, and both send DELIVERED. One above the group's 400 byte limit is discarded by the server (TS 24.582 §6.3.1.3) |
| S62 | One-to-one SDS session (§9.2.4) | 7 | Alice and bob each send in the session. Bob's DELIVERED goes in the session (TS 24.582 §6.1.2.5.1) and reaches alice. Alice's BYE ends bob's leg |
| S63 | Group SDS session | 8 | Bob and client-c join. Alice's and bob's messages reach the other two, bob named by the Sender IE. The originator leaving ends the session for everyone |
| S64 | FD over the media plane (§10.2.5), and over HTTP the release (§6.2.2.4) | 14 | A 3500 octet mandatory file arrives with its hash and is stored. Decline and Later are sent as 480 with warning 110 and 231. The group file reaches bob and client-c. Bob's FD over HTTP is terminated, the server answering success |
| S65 | Emergency and imminent peril sessions (§6.2.8.1.1, §6.2.8.1.3, §6.2.8.1.9, §6.2.8.3.2) | 10 | The members learn the group's emergency, then its cancel by re-INVITE. Imminent peril is raised. A one-to-one emergency session is set up. Imminent peril one-to-one is refused locally |
| S66 | Communication release (§13.2.4 to §13.2.6) | 8 | Bob's intent to release reaches alice with his reason, and alice's remaining data (0) reaches bob. Alice's extension request goes to bob, who grants it. Client-c's release is refused 403. Bob's release ends every leg |
| S67 | The Linux reference client as a recipient | 3 | client-d receives alice's media-plane group SDS, her group session message and a group file whose hash checks |

Server gaps found (openmcx-server, not changed here; all three fixed on branch `android-server-faults`, see "Server fixes"):
- **Notifications of a standalone SDS sent over the media plane.** The server refuses them with 403 warning 216
  ("unable to correlate"). It records the relayed message only on the signalling plane (`dispatched_`, `sds.cpp`), so
  TS 24.282 §12.2.3 step 5 finds nothing. Notifications sent inside an SDS session are relayed (S62).
- **A declined media-plane FD.** The server takes the recipient's 480 with warning 110 or 231, then ends the
  originator's INVITE with 487, without the warning.
- **An SDS above the group's limit.** The server ends the originator's leg before answering the SEND.

## Parity phase 3 (2026-11-25)

[PARITY_PLAN.md](PARITY_PLAN.md) phase 3 (branch `android-p3`): the MCPTT call services. One lab, `p3-a`:
`lab/androidlab.py create --name p3-a --server-image openmcx-all:mcdpre-800c657 --private-services --priority` (host ports
24061, 24443-24447), three redroid 15 clients (`redroid/redroid:15.0.0_64only-latest`), TLS. `--private-services` gives
the fixture's `--private-call --private-services` rights for three clients: client-a listens (remote-init and
local-init), asks for call-backs, remote calls and transfers and forwards by hand; bob forwards by hand and forces
automatic commencement; client-c takes calls from anyone and is forwarded to bob after 4 s without an answer; the
never-registered "offline" is forwarded to bob at once.

| Suite | Result |
|---|---|
| JVM unit tests (`tools/gradle-docker.sh :core:test`) | 505 passed, 0 failed (488 before this phase). New: `PrivateServicesTest` (16), `FloorParticipantTest.expectedGrantWithoutRequest` |
| App unit tests (`:app:testDebugUnitTest`) | 15 passed (new: `ServiceTextTest`, 2) |
| `:app:assembleDebug` | Built |
| One run of S1-S6 (regression) and S32-S39 on `p3-a` | 152 of 152 (S1-S6 76, S32-S39 76) |
| S11 on `p3-a` (its idle service type check now allows the microphone type) | 8 of 8 |

| # | Scenario | Checks | Result |
|---|---|---|---|
| S32 | Ambient listening, remote-init (TS 24.379 §11.1.6.2.1.1, §11.1.6.2.1.2) | 12 | With bob's screen off, alice's INVITE (ambient-listening, remote-init, Priv-Answer-Mode Auto, recvonly) reaches bob with a null alert; bob's app answers 200 at once, sendonly, shows no call and no notification, and keeps the microphone service type; the server grants bob the floor without a request and alice gets bob's speech unchanged; alice holds the release capability and ends the call; bob's own remote-init request is refused |
| S33 | Ambient listening, local-init | 5 | bob's offer is sendonly with his implicit floor request; alice is the listening user and is shown the call; bob's speech arrives unchanged; alice may release it |
| S34 | Call-back (§11.1.5.2) | 11 | alice's request (urgency high, time of request) is stored and confirmed (PCCB-I3); bob calls back with manual commencement, alice answers, speech flows, both entries go; a second request to client-c is withdrawn at both ends; bob has no right to ask |
| S35 | Remotely initiated private call (§11.1.7.2) | 10 | alice has bob's phone call client-c silently: bob's INVITE carries `<remotely-initiated-call-request-ind>` and Answer-Mode Auto, bob is not told, alice gets the success; with alice the target, no report; bob may not ask |
| S36 | Remotely initiated group call (§10.1.5.2), imminent peril notification (§12.1.1.3) | 9 | bob's phone calls the group, alice and client-c join, alice gets the success with the group; with client-c out of the call, alice's imminent peril reaches client-c as a MESSAGE, answered 200 (it was 488), and its cancellation clears the state |
| S37 | Transfer (§11.1.8.2) | 11 | alice transfers bob to client-c: bob's INVITE has `<call-transfer-ind>` and Priv-Answer-Mode Auto while his call with alice goes on; alice gets the success and her call ends; bob and client-c talk both ways; bob may not transfer |
| S38 | Manual forwarding (§11.1.9.2.1) | 9 | bob forwards alice's ringing call to client-c (manual-input) and declines it with 480 and warning 175; alice's app calls client-c with `<call-forwarding-ind>`, sequence number 1 and Priv-Answer-Mode Manual; speech; bob gets the success |
| S39 | Forwarding by the server (§6.3.2.5, §11.1.1.3.2) | 9 | alice's call to client-c is not answered in 4 s and ends with 480 and warning 175, and her app calls bob; her call to the unregistered "offline" is forwarded at once (Priv-Answer-Mode Auto, sequence number 1) |

Found in the lab, and allowed for in the scenarios then: the OpenMCX server passes neither `<call-transfer-ind>` nor
`<call-forwarding-ind>` on to the target, and gives the invitee of a forwarded call Answer-Mode Auto although the caller
asked for Manual (so the forwarded call may connect at once). Fixed on server branch `android-server-faults`; S37-S39
now check both at the target and that it rings ("Server fixes"). Not exercised in the lab: the announced transfer
(not implemented), `<ric-app-level-priority>` (unit test only), a protected ambient listening call.

## Phase 4b: IP connectivity, enhanced status, message store, ad hoc

Branch `android-p4b`, server image `openmcx-all:mcdpre-800c657`, three redroid 15 clients.
- Lab `p4b-a`: `androidlab.py create --priority --functional-alias --reference-client client-d --client-image
  openmcx-client:mcdemerg-ce06d65 --fixture-arg=--msgstore`.
- Lab `p4b-p`: `androidlab.py create --priority --protected` (payloads under PCK/GMK, MCData signalling under the CSK).

The fixture's IP connectivity addresses are 10.77.0.1 (alice), .2 (bob), .3 (client-c). `--msgstore` lets alice store
private and group communications and client-c none. client-c (and client-d) may activate the MCData alias
`patrol-car-12.fa`.

| Suite | Result |
|---|---|
| JVM unit tests (`tools/gradle-docker.sh :core:test`) | 537 passed, 0 failed. New: `IpConnectivityTest` (5), `EnhancedStatusTest` (3), `MessageStoreTest` (3), `MsrpSignallingProtectionTest` (2), `AdhocMediaTest` (4) |
| `:app:testDebugUnitTest` | 13 passed |
| `:app:assembleDebug` | Built |
| `p4b-a`: S1, S6, S60-S67, S80-S82, S84 | 131 of 131; S84 and S64 again after the last fix, 28 of 28 |
| `p4b-p`: S1, S6, S60-S63, S65, S66, S80, S81, S83, S84 | All pass: 107 of 108 in the first run, then S84 after the ad hoc file fix (S1, S83, S84: all pass) |
| `p4b-p`: S64 | 9 of 14: the protected file arrives and is opened, but the scenario compares the selector's size and hash (of the protected series) with the clear file's (fixed after the merge: the client reports the clear file, and S64 checks both lab kinds; 15 of 15 on `fin-p`) |

| # | Scenario | Checks | Result |
|---|---|---|---|
| S80 | IP connectivity (TS 24.282 §20.2.1, §20.2.2; TS 24.582 §13) | 8 | Alice opens a session with bob. Both tunnel ends are the MCData server's relay. Alice's three pings of 120 octets reach bob's endpoint, and its replies come back; bob's two pings are answered too. Alice's BYE ends bob's leg. client-c's session with bob is refused 403 (bob takes one-to-one communications from alice only) |
| S81 | Enhanced status (§14.2.1) | 6 | The group document allows it and lists "On scene", "En route", "Available". Alice's "On scene" is rendered by bob and client-c. An id the group does not list (sent with `force`) is discarded by bob. A group without `<mcdata-allow-enhanced-status>` is refused locally |
| S82 | Message store and notification channel (§21.2, §21.3.2.2) | 10 | Alice enables storage (200); client-c is refused 403 warning 234. Alice's channel and subscription exist. Bob's SDS stored in her box is notified, reads From bob, Direction In, and its payload has the text. It is marked \Seen, found by a search on From, deleted, and gone after a synchronisation. Storage is disabled again |
| S83 | Protected MSRP bodies (§6.6.3.2), `p4b-p` only | 7 | Both phones protect their signalling. Alice's message in an SDS session reaches bob; bob's in-session DELIVERED reaches alice; bob's long standalone SDS reaches alice over MSRP. Each side's counters show the SENDs sealed and opened |
| S84 | Ad hoc SDS and FD, functional aliases over the media plane (§9.2.2.2.1 3A, §24.2.2.1.1, §22) | 14 | Alice's ad hoc SDS to bob and client-c (signalling plane) arrives on the server's ad hoc group. So do her long ad hoc SDS over MSRP, her ad hoc session message and her ad hoc file (900 octets, whole). With `patrol-car-12.fa` among the participants, client-c receives the message, told the alias called. Alice's one-to-one SDS to the alias is sent with it named; the server refuses it (see below) |

Found:
- **Client fault, fixed here:** on a protected lab, the receiver of an ad hoc file tried to open it as a protected
  series ("DPPK-ID is neither a PCK-ID nor a GMK-ID"); ad hoc files are in the clear.
- **Server, not changed then (fixed on branch `android-server-faults`: 300, S84):** a one-to-one media-plane communication to a functional alias is refused. The server treats
  the alias as a user (403 warning 229 for alice, whose one-to-one list does not hold it). On the signalling plane and
  for IP connectivity it answers 300.
- **Server, then still there (fixed on branch `android-server-faults`):** an app restart on a protected lab makes MCData refuse every request with 403 warning 140
  (CONFORMANCE.md, "Deviations and limits"). The protected lab was brought down and up again after each new APK.

## Parity phase 5: pre-established sessions (2026-11-26)

[PARITY_PLAN.md](PARITY_PLAN.md) phase 5 (branch `android-p5`, with `android-parity` and phase 3 merged). One lab,
`p5-a`: `lab/androidlab.py create --name p5-a --server-image openmcx-all:mcdpre-800c657 --priority
--mcdata-pre-established --pre-established --reference-client client-d --client-image openmcx-client:mcdemerg-ce06d65`
(host ports 27061, 27443-27445), three redroid 15 clients and the Linux client as client-d, TLS. The new options pass
the fixture's `--mcdata-pre-established` (`mcdata.resource_sharing` "rx") and `--pre-established`
(`mcptt.resource_sharing` "rx").

| Suite | Result |
|---|---|
| JVM unit tests (`tools/gradle-docker.sh :core:test`) | 563 passed, 0 failed. New: `PreEstablishedTest` (14, MCData), `McpttPreSessionTest` (12, MCPTT) |
| App unit tests (`:app:testDebugUnitTest`) | 15 passed |
| `:app:assembleDebug` | Built |
| One run of S1, S6, S60-S67, S70-S79 and S85 | 193 of 196. The three failures were S76's: the group was left in emergency by an earlier S85 run that failed before the 491 retry (below), so alice's call reached bob as an emergency call by INVITE with Replaces. After cancelling it, S76 and S85 again: 23 of 23 |
| MCPTT regression S2-S5, S16 | 64 of 64 |

(Phase 5's S80 is S85 since the phase 4b merge, which took S80-S84.)

| # | Scenario | Checks | Result |
|---|---|---|---|
| S70 | MCData session and SDS over it (TS 24.282 §18.3.2.1, §9.2.5.2.1.1, §9.2.5.3.1.1) | 8 | The server answers passive. Alice's one-to-one and group standalone SDS go by REFER, each with its own MSRP connection to the session's listener; she leaves by REFER BYE and the session is free again |
| S71 | Communications offered over the session (§9.2.5.2.1.2, §9.2.5.4.1.2), a group session by REFER | 11 | Alice's on-demand SDS session reaches bob by `establish-request`; messages both ways; her close gives bob `terminate-request`. Bob's group session over his session reaches alice and client-c |
| S72 | Emergency and imminent peril over the session (§9.2.5.3.1.1 3A)-3D), §6.2.8.1.16) | 8 | An emergency group session with an alert (the server records the alert and Resource-Priority), its cancel by re-INVITE in the session, imminent peril, an emergency one-to-one session |
| S73 | Release (§18.3.3.1.1) | 5 | The BYE ends it; the next SDS goes on demand; a new session has a new dialog |
| S74 | Flow loss | 3 | After `drop-flow` the session is kept by a re-INVITE with the new Contact (`resumed` 1), and an SDS goes over it |
| S75 | MCPTT session and group calls over it (TS 24.379 §8.2.1, §10.1.1.2.2.1) | 19 | REFER, Connect and Acknowledge; speech both ways on the session's ports; left by REFER BYE; a second call on the same session |
| S76 | A call offered by Connect (TS 24.380 §9.2.2.3.2) | 13 | Bob's leg of alice's on-demand group call comes by Connect; speech both ways; its end by Disconnect |
| S77 | Private calls (§11.1.1.2.2.1, §11.1.1.2.2.2) | 15 | Alice's automatic private call over her session; her manual one reaches bob, who holds a session, by re-INVITE: he rings, answers, and the Connect follows |
| S78 | Modification and release (§8.3.1.1, §8.4.1.1) | 5 | Implicit floor request on by re-INVITE, off by UPDATE; the BYE; the next call on demand |
| S79 | Flow loss | 6 | The MCPTT session is kept on the new registration and carries a call |
| S85 | INVITE with Replaces (§6.3.2.2.5.3 step 1), an emergency call over the session | 10 | Alice's emergency call reaches bob on demand with Replaces naming his session: he ends it and makes it again after the call. Alice's own emergency group call goes over her session |

Faults found and fixed here:
- **A cancelled ringing call answered 480** (`McpttCallTypesTest.firstToAnswerCancelledBeforeTheAnswer`, flaky): the
  user agent woke the INVITE's handler before it sent the 487. The 487 now goes first, and a server transaction claims
  its one final response atomically.
- **491 on the cancel of an emergency over the MCPTT session**: the server's priority re-INVITE of TS 24.379
  §6.3.2.1.8.5 crossed the client's. `setPriority` now sends it again after 2.1 to 4 s (RFC 3261 §14.1).

Server notes (openmcx-server `mcdpre-800c657`, not changed): the MCData participating function listens for the session
and the client connects for each communication, which suits an outbound-only phone; no change is needed. The MCPTT
function runs ICE as the controlled agent and records it as failed for a phone that answers no STUN, then uses the SDP
addresses, so calls work; with NAT (a real phone) they would need the phone's address from latching, as on demand.

## Final regression (2026-11-26)

Branch `android-final`: `android-parity` (phases 0-7 but 4b) with `android-p4b` merged. Server image
`openmcx-all:mcdpre-800c657` (fixture from the `openmcx-server` checkout at bc650a2), three redroid 15 clients
(`redroid/redroid:15.0.0_64only-latest`) per lab, TLS, the Linux reference client `openmcx-client:mcdemerg-ce06d65`
as client-d on `fin-pm`. Each lab was created with `--server-image openmcx-all:mcdpre-800c657` and its own host ports
in 21000-21999 (`--sip-port`, `--idms-port`, `--proxy-port`, `--kms-port`, `--admin-port`, `--credentials-port`), at
most two labs up at a time, all brought down at the end. On the protected lab the APK was installed by `up` only.

| Suite | Result |
|---|---|
| JVM unit tests (`tools/gradle-docker.sh :core:test`) | 581 passed, 0 failed, in each of the two last runs. An earlier run failed `McpttPreSessionTest.aModificationByTheFunctionTakesTheOfferedFloorPriority` once (fixed below) |
| App unit tests (`:app:testDebugUnitTest`) | 15 passed |
| `:app:assembleDebug` | Built |
| `tests/test_androidlab.py` | 5 passed |

| Lab | Options | Scenarios | Checks |
|---|---|---|---|
| `fin-base` | `--monitor-group` | S1-S12 | 151 of 153 (S4 15/17: client-b and client-c missed alice's last 2 of 150 frames; 17/17 on the re-run) |
| `fin-base` | the same | S19-S24, S28 (re-run with the S19 fixes), S1, S4 | 131 of 131 |
| `fin-pm` | `--priority --functional-alias --mcdata-pre-established --pre-established --reference-client client-d --client-image openmcx-client:mcdemerg-ce06d65 --fixture-arg=--msgstore` | S1, S6, S60-S67, S80-S82, S84, S70-S79, S85 | 235 of 235 |
| `fin-f` | `--priority --chat --broadcast` | S1, S16, S25-S27, S29 | 89 of 89 |
| `fin-g` | `--regroup` (and `--floor-queue`) | S30, S31 | 41 of 41 |
| `fin-g` | the same | S1, S18 | 12 of 16: the regroup fixture affiliates nobody to `operations` (S1's three MCPTT affiliation checks), so S18's first group call is refused 403 warning 120 (a lab choice: S18 re-run on `fin-q`) |
| `fin-q` | `--floor-queue` | S1, S18 | 24 of 24 |
| `fin-loc` | `--location --functional-alias --location-rules --priority --mcvideo --mcvideo-priority client-a=7:4 --mcvideo-priority client-b=5` | S1, V1-V4, V6, S13, S14, S17, S40-S45 | 131 of 131 |
| `fin-ps` | `--private-services --priority` | S1, S32-S39 | 91 of 91 |
| `fin-p` | `--protected --xml-protection --integrity-protection --priority --mcvideo --mcvideo-priority client-a=7:4 --mcvideo-priority client-b=5` | S1-S6, S12, S16, V1-V6, S60-S66, S80, S81, S83, S84 | 239 of 241 (S5 4/6: the server's clear ad hoc floor control, a server fault since fixed, "Server fixes") |

Per scenario: S1 15 (each lab), S2 10, S3 7, S4 17, S5 12 (4/6 protected), S6 15, S7 35, S8 16, S9 7, S10 2, S11 8,
S12 9, S13 18, S14 17, S16 18, S17 11, S18 9, S19 13, S20 19, S21 13, S22 15, S23 13, S24 7, S25 23, S26 16, S27 10,
S28 19, S29 7, S30 24, S31 17, S32 12, S33 5, S34 11, S35 10, S36 9, S37 11, S38 9, S39 9, S40 5, S41 6, S42 4, S43 5,
S44 4, S45 4, S60 7, S61 6, S62 7, S63 8, S64 15 (both lab kinds), S65 10, S66 8, S67 3, S70 8, S71 11, S72 8, S73 5,
S74 3, S75 19, S76 13, S77 15, S78 5, S79 6, S80 8, S81 6, S82 10, S83 7, S84 14, S85 10, V1 9 (11 protected), V2 11,
V3 3, V4 6, V5 4 (protected only), V6 13.

Not run: **S15** (the server address edited in Settings) needs phone mode: a phone profile from `lab/run/<lab>/phone`
and the servers on the host's published ports, MCPTT media on 4000-4199 and 5000-5199, outside this run's port range.
S7-S10 ran (the `mcx-nettools:1` image built from `lab/nettools`, `sch_netem` loaded): S9 kept the floor under 5% loss
and 40±20 ms jitter, S10 measured access time and mouth-to-ear on redroid.

Fixed in this run:
- **Client, S19:** after alice's "rejected" deregistration and log-on, the SIP core's first NOTIFY on her new reg
  event subscription named her old binding as terminated, "rejected"; the client took it as its own and stayed off,
  and S20-S28 failed behind it (158 of 169 in the first `fin-base` run). The client now ignores a contact whose
  `callid` is not its registration's (`RegEventRulesTest`). Server fault noted in CONFORMANCE.md; the SIP core now
  sends the current binding first (`openmcx-kamailio:6.1.4-13`), and the client's check stays (harmless).
- **Lab, S19:** the SIP core's `reginfo.shorten` takes the private user identity as well; the scenario passes it.
- **Client, S64 on a protected lab:** media-plane files are reported with the clear file's size and hash (the
  selector's describe the protected series); S64 checks both lab kinds.
- **Client, flaky unit test:** the pre-established MCPTT session recorded the function's modification after sending
  its 200.
- **Scenario ids:** phases 4b and 5 both added an S80; phase 5's (INVITE with Replaces) is now S85.


## Server fixes (2026-11-26)

The server faults found on the Android labs (CONFORMANCE.md, "Deviations and limits") are fixed on openmcx-server
branch `android-server-faults` (not yet on the server's main): srvfix-a (MCData, a3db6b4), srvfix-b (MCPTT, be65d35),
srvfix-c (affiliation and the SIP core's reg event, c29be0a with `openmcx-kamailio:6.1.4-13`), merged at 0c5e503.
Branch `android-srvfix` (from `android-parity` 6a29006) updates the client and the scenarios to expect the fixed
behaviour. Each change was checked first on the image with its fix, then everything on the combined image
`openmcx-all:srvfix-0c5e503` (fixture from the server's 0c5e503 checkout), three redroid 15 clients per lab, TLS.

| Suite | Result |
|---|---|
| JVM unit tests (`tools/gradle-docker.sh :core:test`) | 583 passed, 0 failed. New: `PrivateServicesTest.forwardingRequestBeforeThe480` (fails before the fix: Priv-Answer-Mode null), `ServiceProceduresTest.negotiatedRequestNamesTheRequester` |
| App unit tests (`:app:testDebugUnitTest`) | 15 passed |
| `:app:assembleDebug` | Built |
| `tests/test_androidlab.py` | 6 passed (new: `down` removes `lab/run/NAME`) |

Client and lab changes:
- **Client, forwarding race:** when the server's forwarding MESSAGE arrived before the 480 of the call it forwards
  (ms apart), `placeFollowUp` looked for that call in the history before it had ended, found nothing and sent
  Priv-Answer-Mode Auto instead of the forwarded call's Manual. It now takes the call once it has ended.
- **Client, negotiated-mode affiliation request received:** the requester is the service information's
  `<mcptt-calling-user-id>` (`<mcvideo-calling-user-id>`), not the From header (the server's PSI).
- **Lab:** `androidlab.py down` left `lab/run/NAME` behind (with root-owned server artifacts), so the name could not
  be created again, although the README said `down` removes the lab. It now removes the directory (the root-owned
  files through a container of the lab's server image); `down --keep` keeps it, with the scenario results.

Scenario changes: S60 and S61 accept the server's 202 for a media-plane SDS's notification and check that the
sender gets it; S64 checks the declined and deferred file's 480 with warning 110 or 231; S84's one-to-one media-plane
SDS to `patrol-car-12.fa` is redirected (300) to client-c and delivered; S16 re-calls the group as soon as alice is
out of the last call and checks a new session and no BYE (the MCPTT server's `group_call_replaced`, awaiting
client-c, showed the case was met); S37 checks `<call-transfer-ind>` in client-c's INVITE; S38 and S39 check
`<call-forwarding-ind>` at the target and that it rings for a Manual forwarded call (auto-answer was tolerated
before); S23 and V6 (with `--fixture-arg=--remote-affiliation`) check bob's affiliation subscribed to and notified,
and the negotiated-mode request reaching bob from alice, who is named in `<mcptt-calling-user-id>`; the refresh that
took back the one subscription per user is gone.

On the image with each fix: `srvfix-a-a3db6b4` S1, S6, S60, S61, S84 60/60 (S84 after a check fix, 15/15);
`srvfix-b-be65d35` `--private-services --priority` S1, S37, S38 three times, S39 73/73, and the protected lab
S1-S6, S16 96/96 (S5 12/12); `srvfix-c-c29be0a` `--mcvideo --monitor-group --fixture-arg=--remote-affiliation` S1,
S19, S23, V6 59/59.

On the combined image `openmcx-all:srvfix-0c5e503`:

| Lab | Options | Scenarios | Checks |
|---|---|---|---|
| `sfx-fa` | `--functional-alias --priority` | S1, S6, S60, S61, S64, S84 | 75 of 75 (S1 15, S6 15, S60 8, S61 7, S64 15, S84 15) |
| `sfx-fps` | `--private-services --priority` | S1, S37, S38 three times, S39 | 73 of 73 (S37 12, S38 11 each run, S39 13) |
| `sfx-fp` | `--protected --xml-protection --integrity-protection --priority --mcvideo --mcvideo-priority client-a=7:4 --mcvideo-priority client-b=5` | S1-S6, S16, S60, S61, S64, S84 | 141 of 141 (S5 12 of 12, S16 20) |
| `sfx-fp` | the same, after `androidlab.py install` (an app restart without log-off) | S1, S6, S60 | 38 of 38: MCData authorised under the new CSK (it was 403 warning 140) |
| `sfx-fc` | `--mcvideo --priority --monitor-group --fixture-arg=--remote-affiliation` | S1, S16, S19, S23, V6 | 68 of 73 (S19 11/13, S23 8/11: see below); V6 14 of 14, S16 20 of 20 |
| `sfx-fc` | the same, after bob logged off and on | S1, S19, S23, twice | 45 of 45 each time (S19 13, S23 17) |

Then open (server), both fixed since on `android-server-faults` at 02f11ee ("Final regression on the server fixes"):
- **A third-party deregistration after the new registration (S19, once in four runs):** bob, deregistered by the
  network with "deactivated", registered again 15 ms later on a new flow. The SIP core's first NOTIFY on his new reg
  event subscription was still the old binding's (terminated, "deactivated", the old `callid`), ending the
  subscription (the client ignored the contact by its `callid`), and the MCPTT server's `service_binding_removed`
  (reason deregistration) for the old binding came after bob's new service authorisation and removed it: his MCPTT
  calls then got 480 and alice's subscription to his affiliation 404 until he logged on again.
- **The alias called, one-to-one over the media plane (S84):** after the 300, client-c's INVITE does not carry the
  `<called-functional-alias-URI>` alice's INVITE has, so client-c is not told the alias called (it is printed, not
  checked).


## Final regression on the server fixes (2026-11-26)

Branch `android-srvfix` against the completed server fixes: openmcx-server branch `android-server-faults` at bb306f0
(not yet on the server's main), image `openmcx-all:srvfix-d-bb306f0`, SIP core `openmcx-kamailio:6.1.4-14` (Kamailio
patch 0010), fixture from that checkout. Each lab was created with `lab/androidlab.py create --openmcx <srvfix
checkout> --server-image openmcx-all:srvfix-d-bb306f0` and its own host ports in 22000-22089, three redroid 15 clients
(`redroid/redroid:15.0.0_64only-latest`) per lab, TLS, the Linux reference client `openmcx-client:mcdemerg-ce06d65` as
client-d on `fz-pm`; up to four labs at a time (about 3.5 GB each), all brought down at the end. The APK was rebuilt
first (it has `sds-adhoc`).

Fixed in the server since "Server fixes" (02f11ee, 5652b27, bb306f0):
- **The deregistration race (S19):** the MCPTT, MCVideo and MCData servers end a binding on a third-party
  deregistration only if it names the binding's registration Call-ID (TS 24.229 §5.4.1.7), and the SIP core's first
  reg event NOTIFY of a subscription is the registrar's current state whenever it has a contact (Kamailio patch 0010).
  S19 ran seven times (three on `fz-base`, four on `fz-c`), 13 of 13 each; S23 seven times too.
- **The alias called (S84):** after the 300 for a one-to-one media-plane SDS to `patrol-car-12.fa`, client-c's INVITE
  carries alice's `<called-functional-alias-URI>`. S84 now checks that client-c is told the alias called (15 checks,
  passed on `fz-pm` and `fz-p`).
- **The one-to-one media-plane SDS limit:** the server now holds a one-to-one SDS over the media plane to
  `<max-data-size-sds-bytes>` (MSRP 403), 4096 octets in the lab fixture. No scenario sends a longer one: the longest,
  S60's, is about 3000 octets (S83's 1560, S84's about 1130).

| Suite | Result |
|---|---|
| JVM unit tests (`tools/gradle-docker.sh :core:test`, `--rerun-tasks`, and again after the fix below) | 583 passed, 0 failed |
| App unit tests (`:app:testDebugUnitTest`) | 15 passed |
| `:app:assembleDebug` | Built |
| `tests/test_androidlab.py` | 6 passed |

| Lab | Options | Scenarios | Checks |
|---|---|---|---|
| `fz-base` | `--monitor-group` | S1-S12 | 153 of 153 |
| `fz-base` | the same | S19-S24, S28; then S19, S23 twice | 98 of 98; 25 of 25 each time (S19 13, S23 12 without the rights) |
| `fz-pm` | `--priority --functional-alias --mcdata-pre-established --pre-established --reference-client client-d --client-image openmcx-client:mcdemerg-ce06d65 --fixture-arg=--msgstore` | S1, S6, S60-S67, S80-S82, S84, S70-S79, S85 | 238 of 238 (S84 15) |
| `fz-f` | `--priority --chat --broadcast` | S1, S16, S25-S27, S29 | 91 of 91 (S16 20) |
| `fz-g` | `--regroup --floor-queue` | S30, S31 | 41 of 41 |
| `fz-g` | the same | S1, S18 | 12 of 16, as before: the regroup fixture affiliates nobody to `operations` (a lab choice; S18 on `fz-q`) |
| `fz-q` | `--floor-queue` | S1, S18 | 24 of 24 |
| `fz-loc` | `--location --functional-alias --location-rules --priority --mcvideo --mcvideo-priority client-a=7:4 --mcvideo-priority client-b=5` | S1, V1-V4, V6, S13, S14, S17, S40-S45 | 131 of 131 |
| `fz-ps` | `--private-services --priority` | S1, S32-S39; then S38 twice | 98 of 98 (S37 12, S38 11, S39 13); 11 of 11 each time |
| `fz-p` | `--protected --xml-protection --integrity-protection --priority --mcvideo --mcvideo-priority client-a=7:4 --mcvideo-priority client-b=5` | S1-S6, S12, S16, V1-V6, S60-S66, S80, S81, S83, S84 | 251 of 252 first (V2, below); with the fix, 252 of 252 |
| `fz-p` | the same, after `androidlab.py install` (an app restart without log-off) | S1, S6, S60 | 38 of 38 |
| `fz-c` | `--mcvideo --priority --monitor-group --fixture-arg=--remote-affiliation` | S1, S16, S19, S23, V6; then S19, S23 three times | 79 of 79 (S19 13, S23 17, V6 14); 30 of 30 each time |

Per scenario as in "Final regression", but S16 20, S23 17 with `--remote-affiliation` (12 without), S37 12, S38 11,
S39 13 and S84 15. Not run, as before: S15 (phone mode).

Failures and causes:
- **Client, V2 on the protected lab:** alice's transmission request in the ad hoc MCVideo call timed out. The server
  now protects an ad hoc call's floor and transmission control under each user's CSK when floor control is protected
  (TS 33.180 §9.4.2; `adhoc_call_policy` `protect_floor`, the fix that made S5 pass), for MCVideo as for MCPTT, while
  the client still sent MCVideo ad hoc transmission control in the clear ("Server fixes" did not run V1-V6 on the
  protected lab). `McvideoService` now protects it under the MCVideo CSK, originating and terminating, the media
  still in the clear (no group key); V2 11 of 11 on `fz-p`. No change on labs without protection (`protection`
  returns none without a CSK): `fz-base`, `fz-c`, `fz-pm`, `fz-f` and `fz-loc` ran the APK before the fix, `fz-p`
  (the table's run), `fz-ps`, `fz-q` and `fz-g` the one with it.
- **Not a fault, `fz-ps` first run:** client-b's control endpoint did not answer when the run began (the APK was being
  rebuilt and four labs were starting), so S1-S39 failed behind it; client-b was ready a minute later and the re-run
  after `install` is the one in the table. Likewise, V1-V6 run straight after an `install` without S1 (which waits for
  the services) found MCVideo not yet authorised and left one refused message in V1's absolute `refused` count; the
  table's `fz-p` run follows a fresh install with S1 first.

No server fault found in this run.

## Protected ad hoc calls (2026-11-26)

Branch `android-adhoc-e2e` against openmcx-server branch `adhoc-e2e` (6c4ddd3, 7ef0488): end-to-end protected MCPTT and
MCVideo ad hoc group calls (TS 24.379 §17.2.2.1.1, §17.2.2.1.2, §17.4.2.2 step 11; TS 24.281 §22.2.2.1.1, §22.2.2.1.2,
§22.4.2.2; TS 33.180 §5.7, §7.3). Image `openmcx-all:adhoc-e2e-6c4ddd3` (`openmcx-all:srvfix-d-bb306f0` with the
MCPTT and MCVideo servers of 6c4ddd3), fixture from that checkout: on a protected lab it adds the preconfigured group
`sip:adhoc-security@mcx.test` (all four users, media protected) that the servers name in `<preconfigured-group-id>`
and that the clients subscribe to from the start (`adhoc_security_groups`). Three redroid 15 clients per lab, TLS.

| Suite | Result |
|---|---|
| JVM unit tests (`tools/gradle-docker.sh :core:test`) | 593 passed, 0 failed. New: `AdhocSecurityTest` (6), `AdhocSecurityConfigTest` (3), `GroupKeySubscriptionsTest` (+1); `McpttCallTypesTest.adhocByCriteriaAndModified` checks a clear call is unchanged |
| App unit tests (`:app:testDebugUnitTest`) | 15 passed |
| `:app:assembleDebug` | Built |
| `tests/test_androidlab.py` | 8 passed (new: `AdhocSecurityGroups`, 2) |

| Lab | Options | Scenarios | Checks |
|---|---|---|---|
| `ae-p` | `--protected --xml-protection --integrity-protection --priority --mcvideo` | S1-S5, S12, V1-V6 | 114 of 115; the one failure was V2's new check that bob decodes alice's pictures, which cannot pass on redroid (no camera: its transmissions carry no picture, as in V1). The check now runs only when pictures are sent; V2 again: 13 of 13 |
| `ae-u` | `--mcvideo --priority` | S1, S4, S5, V2 | 55 of 55 (clear ad hoc calls unchanged) |

On `ae-p`, S5: alice, bob and client-c each key the ad hoc speech with the GMK of `sip:adhoc-security@mcx.test`, and
the speech of alice and of client-c arrives unchanged (the frame hashes); V2: the MCVideo ad hoc call is protected
under the same group's GMK on alice and bob, bob's leave and protected re-join, emergency and imminent peril ad hoc calls.
The Linux reference client was not a participant: it subscribes to `mcptt.adhoc_security_groups` but has no MCVideo
counterpart (an invitation to a protected MCVideo ad hoc call is refused 488 until its first try subscribes to the GMK).

## MCData suite failures (branch `android-q2-mcdata`, 2026-11-26)

The MCData failures of the suite run `android-mcdata-real-p7` (suite worktree `suite-android`, real servers), fixed on
client main 5d431a0. Server image `openmcx-all:adhoc-e2e-6c4ddd3` (server main f4a6c3c).

What changed (CONFORMANCE.md "MCData", "MCData media plane", "Pre-established sessions"):
- The `<tx-and-rx-control>` of the MCData service configuration (TS 24.484 §10.4.2.1 2)): the signalling-plane limit
  `<max-payload-size-sds-cplane-bytes>` (the provisioning's value only until the document is read; it had been the
  provisioning's alone, so the suite's value 0 never sent an SDS over the media plane or by REFER), and
  `<max-data-size-sds-bytes>` / `<max-data-size-fd-bytes>` for one-to-one SDS and FD (TS 24.282 §11.1 3), 4)).
- Group transmission control from the group document read from the GMS for each request (TS 24.282 §11.1 2), 5), 6),
  §9.2.2.2.1 3)a), §9.2.3.2.3, §9.2.4.2.3 8)a1), §10.2.4.2.1, §10.2.5.2.3 9)a1)): the user's
  `<mcdata-allow-transmit-data-in-this-group>`, AllowedSDS, AllowedFD, the group's size limits and
  `<preconfigured-group-use-only>` (no media-plane SDS, session or FD; a REFER over the pre-established session too).
- `<associated-group-id>` in a group SDS to a temporary group (§9.2.2.2.1 3)b)iii)).
- The priority state machines of TS 24.282 §6.2.8 (MDEG, MDEGC, MDEA, MDIG, MDIGC, MDEPC, MDEPP, `McdataPriority`, as
  Linux priority.cpp): the Resource-Priority of a plain group communication on a group in an in-progress state, the
  imminent peril value (it was looked up as "imminent_peril", which the service configuration does not have), the
  normal one on a group cancel, `<alert-ind>` "false" in an emergency request, the states on refusal, grant (also by
  the pre-established session's re-INVITE), release, the SIP INFO of §6.2.8.1.13 and the in-progress notification; the
  200 (OK) to the server's group priority re-INVITE with `+g.3gpp.mcdata` and the MCData ICSI (§6.2.7.4 7)-8)).
- The functional alias in use and the client ID in an FD request over HTTP (§10.2.4.2.1 1)b)ii)).
- One MIME body sent as it is, not in a one-part multipart (§6.4): the media storage discovery request, an alert cancel.

| Suite | Result |
|---|---|
| JVM unit tests (`:core:test`) | 616 passed, 0 failed (593 before: 23 new in `McdataPriorityTest`, `McdataRulesTest`, `McdataMediaServiceTest`, `FdServiceTest`) |
| App unit tests (`:app:testDebugUnitTest`) | 15 passed |
| `:app:assembleDebug` | Built |
| `tests/test_androidlab.py` | passed |

| Lab | Options | Scenarios | Checks |
|---|---|---|---|
| `q2pm` | `--priority --functional-alias --mcdata-pre-established --pre-established --reference-client client-d --client-image openmcx-client:mcdemerg-ce06d65 --fixture-arg=--msgstore` | S1, S6, S60-S67, S80-S82, S84, S70-S79, S85 | 238 of 238 (S6's policy check now accepts the client's own refusal: client-c has no transmit right on `operations`, refused before the MESSAGE, §11.1 2)) |
| `q2p` | `--protected --xml-protection --integrity-protection --priority` | S1, S6, S60-S66, S80, S81, S83, S84 | 129 of 129 |

Suite (`--dut android --ss openmcx`, lab `drv-q2` from `tools/android-lab-up.py`, run `results/android-q2-r1`, D.3.4
and D.4.5 again in `android-q2-r2`): the 25 failing test cases and the 12 that passed in p7.

| Test case | p7 | now | Remaining cause |
|---|---|---|---|
| 6.1.5, 6.1.7, 6.1.13, 6.1.17, 6.1.22 | Fail | Pass | |
| 6.1.23 | Fail | Pass | |
| D.3.5, D.5.3, D.5.4, D.5.13 | Fail | Pass | |
| D.3.4, D.4.5 | Fail | Pass (r2) | in r1 the SS's group document edits were not made (`update_document group_mcdata` returned false: a bench transient), the group checks so untested; both pass in r2 |
| D.12.3 | Fail | Inconclusive | step 4 passes (the SDS's BYE Reason; p7 judged the FD's BYE as the SDS went on the signalling plane); `msrp_answer_active` and the FD notification are SS stimuli the real servers do not play |
| D.13.5 | Fail | Inconclusive | every body check passes; step 6 needs a peer session the bench did not make |
| 6.2.14 | Fail | Fail | step 2 (the alias) passes; step 5 needs `fd_notify`, not available in the openmcx SS adapter |
| 6.4.3, 6.4.4 | Fail | Fail | the area notices (`send_message group_geo_area` / `emergency_alert_area`) are not available in the openmcx SS adapter: never sent, so the judge takes the postamble's PUBLISH (6.4.3) and the user's alert (6.4.4); the client's handling is `EmergencyAlerts.area` (unit tested, S43) |
| D.5.1, D.5.8 | Fail | Fail | the alert's location body: the driver sets no position before `sds-session ... alert` (redroid has no fix); D.5.8 also needs `refuse_alert`, `peer_emergency`, `priority_reinvite` |
| D.5.2, D.5.7, D.5.14, D.6.3 | Fail | Fail | SS stimuli not available on the real servers (`priority_reinvite`, `priority_refuse`, `fd_media`, `peer_cancel`, `fd_release_intent`); D.5.7's "wrong dialog" follows from the missing refusal: the first session stays, the server ends the second |
| D.5.6 | Fail | Fail | the server answers the emergency and imminent peril FD INVITEs 403 (no Warning), so MDEG stays 1 and the plain FD has no emergency priority |
| D.5.12 | Fail | Fail | the client's rows pass (steps 2, 4, 6, 12, 20, 30, 38, 42, 48, 56-60, 66, 70, 72; 31 of 38 steps pass or are inconclusive); the rest need `priority_reinvite`, `pre_hold`, `peer_emergency`, `priority_refuse`, which the openmcx SS adapter does not play, and the states then follow what was granted |
| the 12 p7 passes | Pass | Pass | |

## MCPTT conformance fixes (2026-11-26)

Branch `android-q1-mcptt` (from client main 5d431a0): the MCPTT failures of the suite's Android run
`android-mcptt-real-p7` (openmcx-conformance-tests worktree `suite-android`, branch `feat/android-driver`). Each fix is
checked against TS 24.379 V19.7.0; CONFORMANCE.md has the rows.

| Test case | Cause | Fix | Clause |
|---|---|---|---|
| 6.1.1.19, 6.1.2.13, 6.1.2.14, P.8.12 | The alias in use was presented on private and ad hoc calls only, not on prearranged and chat group calls (INVITE, and REFER over the pre-established session) | `FunctionalAliasRules.presented`: every call but an ambient listening call presents the alias in use while it is active | §10.1.1.2.1.1 14) e), §10.1.2.2.1.1 13) e) |
| 6.1.1.22 | The documents of the provisioned groups only were read; the group of the test (`operations-preconf`) is affiliated later, so its `<preconfigured-group-use-only>` was unknown and the INVITE went out (the server refused it, 403 warning 167) | A group call on a group without a document reads it first (up to 3 s), then refuses it locally | §10.1.1.2.1.1, §10.1.2.2.1.1 (first paragraph 1), 2)) |
| 6.2.16 | Only the listened-to side of a remote-init call was hidden; the listened-to user's own local-init call was shown | `Call.hidden` for the listened-to role of both types; `Call.concealed` (remote-init) keeps it out of `active`, so the user can still release a local-init call (contact menu "Stop letting them listen") | §11.1.6.2.0, §11.1.6.2.1.1 (200 (OK)) 3), §11.1.6.2.1.3 2), §11.1.6.2.1.4 2) |
| P.4.6 | `<call-to-functional-alias-ind>` left out of a forwarding to an MCPTT ID | Always present: "true" to an alias, else "false" | §11.1.9.2.1 2) d) i) B) |
| P.5.1 | The ad hoc alert rights were left to the server (an unauthorised alert was sent); without a position the alert went without its location body; the refused alert left nothing to cancel | `EmergencyAlerts.sendAdhoc` checks `<allow-activate-adhoc-group-emergency-alert>`, `cancelAdhoc` `<allow-cancel-adhoc-group-emergency-alert>`; the MCPTT alert is not sent without a position | §6.2.8.1.19, §12.1A.1.1 1), 2), 5) -> §6.2.9.1 2), §12.1A.1.2 |
| P.10.2 | The alert cancellation (one body) went as multipart/mixed with one boundary delimiter | `Multipart.body`: one body as itself (its MIME type the Content-Type), used by the alert MESSAGEs | §6.5 (one MIME body 1)) |

Shared code touched: `body/Multipart.kt` (a new `Multipart.body`; `encode` unchanged, so other callers are untouched)
and `alert/EmergencyAlerts.kt` (its MESSAGEs, for MCPTT, MCData and MCVideo ad hoc alerts: a single body now goes
plain; the rights and location checks apply to MCPTT only).

Not client faults (the evidence is in the run directories):
- **P.3.2, P.3.9, P.4.5 (suite):** the Floor Acks "after the UE left" are those of the next call of the test, on a new
  floor port and Call-ID (P.3.2 p7: BYE #543 on the call with floor port 20314; the Floor Acks #634 on 20098, after the
  next INVITE #626). `no_floor_after` (`_lib_p3.py`) and `media_stopped` (`_lib_p4.py`) take every UE floor message up
  to the window end plus 5 s grace, and the Android DUT starts the next call within a second. With the checks limited
  to the released call's UE floor port: P.3.9 and P.4.5 Pass, P.3.2 Inconclusive (step 7: the real server sends no SS
  BYE on cue); re-verifying the p7 captures gives the same.
- **P.10.4 (bench):** the lab server's private call timer (`maximum_duration_ms`, 30 s in the fixture) releases user
  B's call 30 s after the 200 (OK), before the refresh at half the 90 s interval (45 s, RFC 4028 §10); the group call
  is released by T4 inactivity at 60 s, and the server answers Session-Expires 1800 anyway. The client's refresh is
  unchanged (S7: an 80 s call refreshed by UPDATE).
- **6.6.4 (bench):** the suite lab is made without `--location`, and the real server sends the emergency alert area
  notice only when the UE reports a position inside an area (`hazard`, 48.05 N 2.05 E). Step 1's
  `send_message kind=emergency_alert_area` is not mapped on the real-server SS. The client raises the alert on the
  notice by itself (S14, S44 above: the alert with no user request).
- **P.5.1 (suite):** the driver's `adhoc_alert` sets no position before the alert (its group alert does), and the
  criterion `organization=mcx.test` is not one the OpenMCX servers take (`affiliated:<group>` or `member:<group>`;
  403 warning 187). With both changed, P.5.1 Pass.
- **Pre-established sessions left at the server (server or suite):** 6.1.1.5, 6.1.2.14 and 6.2.18 pre-establish a
  session and do not release it; the next test's `switch_on` force-stops the app, and the MCPTT server keeps the old
  session and offers the next incoming call over it (MCPCC Connect, no acknowledgement, 408 to the caller). Every
  test with an incoming call that runs after them is Inconclusive (6.2.4, 6.2.7, 6.2.8, 6.2.12, P.4.6, P.10.2 step 6
  in the first runs). Run on a fresh lab before any pre-establishment, they pass.

Unit tests: JVM 599 passed, 0 failed (was 593; new `PrivateServicesTest.ambientListeningLocalInitHiddenAndReleasable`,
`preconfiguredUseOnlyGroupReadOnDemandAndNotCalled`, `groupDocumentReadOnDemandAllowsTheCall`,
`adhocAlertAuthorisationLocationAndCancellation`, `FunctionalAliasTest.theAliasPresentedOnCalls`,
`MultipartTest.oneBodyGoesAsItself`; `forwardRinging` and `ambientListeningLocalInitWithoutReleaseCapability`
extended). App unit tests 15 passed, `:app:assembleDebug` built, `tests/test_androidlab.py` 8 passed.

| Lab | Options | Scenarios | Checks |
|---|---|---|---|
| `q1loc` | `--location --functional-alias --location-rules --priority --mcvideo --mcvideo-priority client-a=7:4 --mcvideo-priority client-b=5` | S1, V1-V4, V6, S13, S14, S17, S40-S45 | 132 of 132 (S17 now checks alice's group call INVITE presents her alias) |
| `q1ps` | `--private-services --priority` | S1, S32-S39 | 98 of 98 (S33 now expects bob's local-init call hidden) |

Server image `openmcx-all:adhoc-e2e-6c4ddd3` for the labs, fixture from openmcx-server main.

Suite (Android DUT, `--ss openmcx`, lab `drv-q1` from `tools/android-lab-up.py`, image
`openmcx-all:answer-mode-e7a4a79` for the final runs, results in the suite worktree):

| Test case | p7 | After | Run |
|---|---|---|---|
| 6.1.1.19, 6.1.2.13, 6.1.2.14, 6.1.1.22, P.10.2 | Fail | Pass | `android-q1-mcptt-b` |
| P.8.12 | Fail | Inconclusive: the alias check passes; the SS floor stimuli (Floor Taken, Deny, Idle) are not sent on cue | b |
| 6.2.16 | Fail | Inconclusive: hidden as required; step 19, the SS's `<release-reason>` administrator-action, not sent | b |
| P.4.6 | Fail | Inconclusive: `<call-to-functional-alias-ind>` "false" passes; the SS forwarding response and request are not sent | `android-q1-mcptt-d` |
| P.5.1 | Fail | Fail with the suite as is (step 2 now passes; step 5 refused locally without a position); Pass with the suite changes | b; `android-q1-mcptt-patched3` (suite copy) |
| P.3.9, P.4.5 | Fail | Fail as is; Pass with the floor checks per call | b; `patched`, `patched2` |
| P.3.2 | Fail | Fail as is; Inconclusive with them (SS BYE stimulus) | b; `patched` |
| P.10.4, 6.6.4 | Fail | Fail (bench) | b |
| The 20 that passed in p7 (5.5, 5.7, 6.1.1.3, 6.1.1.5, 6.1.1.11, 6.1.1.13, 6.1.1.15, 6.1.1.21, 6.1.5.1, 6.2.3-6.2.8, 6.2.12, 6.2.18, P.1.3, P.9.1, P.10.3) | Pass | Pass (6.2.4, 6.2.7, 6.2.8, 6.2.12 on run d, before any pre-establishment) | b, d |
| 6.1.1.4, 6.1.1.9, 6.1.1.12, 6.1.1.14 (the server's answer-mode fix) | Fail | Pass | b |

## MCVideo suite fixes (2026-11-26)

Branch `android-q3-mcvideo` (from main 5d431a0): the MCVideo failures of the suite run
`suite-android/results/android-mcvideo-real-p7` (CONFORMANCE.md, MCVideo; VIDEO.md, "Suite fixes"). Server image
`openmcx-all:adhoc-e2e-6c4ddd3` (server main f4a6c3c), three redroid 15 clients per lab, TLS; one lab at a time, each
brought down after its run.

| Suite | Result |
|---|---|
| JVM unit tests (`tools/gradle-docker.sh :core:test`) | 612 passed, 0 failed (593 before). New: `McvideoAuthorisationTest` (5), `McvideoPriorityAuthorisationTest` (6); `TransmissionTimersTest` +3 (reception end fields, override), `McvideoSdpTest` +4 (origin, implicit request in answers, remote-init ambient offer, push direction), `AlertInfoTest` +1 (MCVideo area notices) |
| App unit tests (`:app:testDebugUnitTest`) | 15 passed |
| `:app:assembleDebug` | Built |
| `tests/test_androidlab.py` | 8 passed |

| Lab | Options | Scenarios | Checks |
|---|---|---|---|
| `q3-u` | `--mcvideo --priority --mcvideo-priority client-a=7:4 --mcvideo-priority client-b=5` | S1, V1-V4, V6 (V5 needs protection) | 57 of 57 |
| `q3-p` | `--protected --xml-protection --integrity-protection --priority --mcvideo --mcvideo-priority client-a=7:4 --mcvideo-priority client-b=5` | S1, V1-V6 | 65 of 65 |

On `q3-u` a push, a pull and a remote-init ambient viewing call were also made by hand: the push callee answers
`recvonly` and the server relays alice's grant; the pulled bob is granted at once; in the ambient call bob (viewed-to)
is granted and alice receives without a pending request of her own.

**Suite** (`suite-android`, lab `drv-q3` from `tools/android-lab-up.py up --mcvideo`, PIXIT
`bench/openmcx-lab-android-mcvideo.toml`, `--dut android --ss openmcx`): the 17 failed test cases of p7 (5.8 is TBI)
and the 28 that passed there. Run `results/android-q3-mcvideo-b` (APK e214691c…, commit 38ebc06); run
`android-q3-mcvideo-a` was the commit before (the profile read on demand missing, see S.5.13).

| Test case | p7 | q3-b | Note |
|---|---|---|---|
| 6.1.1.1, 6.2.1, S.8.2 | Fail | Inconclusive, Inconclusive, Pass | the re-INVITE offer's `o=` now passes; Inconclusive for SS stimuli (Transmission Granted) the real server does not send on cue |
| 6.1.1.2 | Fail | Inconclusive | the answers' `o=` and the reception end request's fields pass; SS stimuli not in the trace |
| 6.1.1.11 | Fail | Pass | |
| 6.1.1.13, S.9.6 | Fail | Inconclusive | the reception end request's fields pass; the override notification and the unanswered request (S.9.6) are SS stimuli the bench cannot make |
| 6.4.2 | Fail | Pass | |
| 6.7.1 | Fail | Inconclusive | the offer's `mc_implicit_request` passes; step 8's Media Transmission Notification is not in the trace |
| S.1.1, S.1.3 | Fail | Inconclusive, Pass | the answer is `recvonly`; S.1.1 step 3 is an SS stimulus |
| S.8.1, S.12.2 | Fail | Pass | |
| 6.3.4 | Fail | Fail | suite: no area notice is sent (below) |
| S.5.13 | Fail | Fail | suite: the lab's profile grants the rights (below) |
| S.5.5, S.5.16 | Fail | Fail | suite: no location and criteria the server does not take (below) |
| the 28 passed in p7 | Pass | 26 Pass, S.17.4 and S.18.1 Inconclusive | S.17.4 and S.18.1: the server's 403 "Resource-Priority not authorized by call state" (below) |

Not client faults:
- **6.3.4:** step 1's `ss.send_message(kind='emergency_alert_area')` is "not available in the openmcx adapter", so no
  notice reaches the phone and the only alert is the user's (as MCPTT's 6.6.4, ALERTS.md). The client now raises the
  MCVideo alert on the notice (`McvideoPriorityAuthorisationTest.alertAreaNoticeAndAdhocAlert`). The adapter has to
  move the DUT into the lab's emergency alert area instead.
- **S.5.13:** the test expects a profile without `<allow-emergency-adhoc-group-call>` and
  `<allow-imminent-peril-adhoc-group-call>` "as provisioned", but the bench's `site()` rights grant both (alice's
  `mcvideo.adhoc_call.emergency`/`imminent` true), so the requests are authorised and sent. A preamble that sets them
  false (as S.8.1 does with `update_document`) is needed. With the rights false the client refuses them
  (`McvideoPriorityAuthorisationTest.unauthorisedRightsSendNothing`, `requestBeforeTheProfileIsReadReadsIt`).
- **S.5.5, S.5.16:** the driver's `adhoc_alert` sets no position (the group alert's `_video_alert` sends
  `location-position <PIXIT initial>` first); redroid has no fix, so the client now refuses the alert without a
  location (TS 24.281 §12.1A.1.1 5)) instead of sending it without the location body. With a position the server
  still refuses the bench's criterion `organization=mcx.test` (403, warning 187 "can't determine the adhoc group
  participants": it takes `affiliated:<group>` and `member:<group>`), so no alert is raised and there is nothing to
  cancel. By hand on `drv-q3`, `location-position 45 10` then `adhoc-alert affiliated:sip:operations@mcx.test video`
  sent the alert with the location (the server's `adhoc_alert_started`, three recipients) and `adhoc-alert cancel`
  cancelled it.
- **S.17.4 (and S.18.1 in run a):** the INVITE right after the app restart was refused 403 "Resource-Priority not
  authorized by call state" while the bench was restoring a service configuration that another test had changed (the
  MCVideo server read `mcpttq.*` values and then the `mcpttp.*` ones again); the same calls passed in the other run.

## Regression on merged main (2026-11-26)

Client main deca57d (the merges of `android-q2-mcdata`, `android-q1-mcptt` and `android-q3-mcvideo`), branch
`android-main-verify`. Server image `openmcx-all:answer-mode-e7a4a79` (server main 19a5250), fixture from that
checkout; three redroid 15 clients per lab, TLS, the Linux reference client `openmcx-client:mcdemerg-ce06d65` as
client-d on `mv-pm`. Each lab was created with `lab/androidlab.py create --openmcx <server checkout> --server-image
openmcx-all:answer-mode-e7a4a79` and its own host ports in 24000-24109; up to three labs at a time, all brought down at
the end. Debug APK sha256 23bf1789… (built from deca57d; the commit below changes a scenario only).

| Suite | Result |
|---|---|
| JVM unit tests (`:core:test`) | 641 passed (before the verification) |
| App unit tests (`:app:testDebugUnitTest`) | 15 passed |
| `:app:assembleDebug` | Built |
| `tests/test_androidlab.py` | passed (again after the S14 change) |

| Lab | Options | Scenarios | Checks |
|---|---|---|---|
| `mv-base` | `--monitor-group` | S1-S12 | 153 of 153 |
| `mv-base` | the same | S19-S24, S28; then S19, S23 twice | 98 of 98; 25 of 25 each time |
| `mv-pm2` | `--priority --functional-alias --mcdata-pre-established --pre-established --reference-client client-d --client-image openmcx-client:mcdemerg-ce06d65 --fixture-arg=--msgstore` | S1, S6, S60-S67, S80-S82, S84, S70-S79, S85 | 238 of 238 |
| `mv-f` | `--priority --chat --broadcast` | S1, S16, S25-S27, S29 | 91 of 91 |
| `mv-g` | `--regroup --floor-queue` | S30, S31; S1, S18 | 41 of 41; 12 of 16, as before (the regroup fixture affiliates nobody to `operations`) |
| `mv-q` | `--floor-queue` | S1, S18 | 24 of 24 |
| `mv-loc2` | `--location --functional-alias --location-rules --priority --mcvideo --mcvideo-priority client-a=7:4 --mcvideo-priority client-b=5` | S1, V1-V4, V6, S13, S14, S17, S40-S45 | 132 of 132 (with the S14 change below; 129 of 131 before it on `mv-loc`) |
| `mv-ps` | `--private-services --priority` | S1, S32-S39; then S38 twice | 98 of 98; 11 of 11 each time |
| `mv-p` | `--protected --xml-protection --integrity-protection --priority --mcvideo --mcvideo-priority client-a=7:4 --mcvideo-priority client-b=5` | S1-S6, S12, S16, V1-V6, S60-S66, S80, S81, S83, S84 | 257 of 257 |
| `mv-p` | the same, after `androidlab.py install` | S1, S6, S60 | 38 of 38 |
| `mv-c` | `--mcvideo --priority --monitor-group --fixture-arg=--remote-affiliation` | S1, S16, S19, S23, V6; then S19, S23 three times | 79 of 79; 30 of 30 each time |

Found by the merge, fixed here:
- **S14 (the merge of q1 and q3):** `android-q3-mcvideo` makes the client raise its MCVideo alert on an emergency alert
  area notice too (TS 24.281 §11.2.1.5, as TS 24.379 §12.1.1.6), so on the location lab (`--mcvideo`) the area raises
  the alert on MCPTT, MCData and MCVideo and bob receives it on three services. S14 still expected two (q1 ran S14
  before q3; q3 did not run S14). The scenario now expects every service; 132 of 132 on `mv-loc2`.

Not counted in the table (the first labs and re-runs on the same lab):
- `mv-pm` (the first pm lab): 227 of 229, client-c's control endpoint did not answer when S1 began (three labs were
  starting); the runner now waits for each client's `phase: ready`. Its re-run on the same lab, and S66, S76 after a
  full run on `mv-pm2`: 3 checks fail on a re-run only. **S76** (server, not merge-caused): S85 ends alice's emergency
  group call over her pre-established session with the cancelling re-INVITE in that session (TS 24.379 §10.1.1.2.1.4
  allows either session); the MCPTT server answers it as a pre-established session re-INVITE
  (`pre_established_session_answered`, no `priority_changed` to normal), so the group's in-progress emergency stays,
  the next plain call of alice is an emergency call, and bob's leg comes by INVITE with Replaces instead of Connect.
  The same with the `android-q2-mcdata` APK (before the merges): S1, S76 28 of 28, S85 10 of 10, then S76 11 of 13.
  **S66** ("alice's session is extended", MCData): the server grants the extension (`mcdata_release_extension
  accepted`); passes on a fresh lab, fails on a re-run only (probably the first run's session records in alice's
  status). Both pass on a fresh lab, as in every earlier regression.
- `mv-loc` re-run on the same lab after the S14 change: 97 of 104 (alice not affiliated to the MCVideo group after
  the first run's V6); on the fresh `mv-loc2`, 132 of 132.

### Suite against Android

Suite worktree `suite-android` (79902b0), `--dut android --ss openmcx`, a fresh lab per service from
`tools/android-lab-up.py up` with `--androidlab` this worktree's `androidlab.py` (the APK above) and
`--server-image openmcx-all:answer-mode-e7a4a79`: `drv-mvp`, `drv-mvd`, `drv-mvv` (`--mcvideo`). Runs
`results/android-main-mcptt`, `android-main-mcdata`, `android-main-mcvideo`; MCPTT ran 6.1.1.5, 6.1.2.14 and 6.2.18
last. Re-runs on fresh labs: `android-main-mcptt-fresh` (`drv-mvp2`), `android-main-mcdata-fresh` (`drv-mvd2`,
D.3.4), `android-main-mcvideo-rerun` (S.18.1).

| Service | p7 Pass / Fail / Inconclusive (+ unsupported) | Now | With the fresh re-runs |
|---|---|---|---|
| MCPTT (141) | 20 / 20 / 57 + 41, N/A 3 | 24 / 7 / 66 + 42, N/A 2 | 29 / 7 / 61 + 42, N/A 2 |
| MCData (137) | 12 / 30 / 50 + 43, N/A 2 | 23 / 19 / 50 + 43, N/A 2 | the same (D.3.4 fails again) |
| MCVideo (149) | 28 / 17 / 59 + 33, N/A 11, TBI 1 | 33 / 4 / 67 + 33, N/A 11, TBI 1 | 34 / 4 / 66 + 33 |

MCPTT: the q1 fixes hold (6.1.1.4, 6.1.1.9, 6.1.1.12, 6.1.1.14, 6.1.1.19, 6.1.1.22, 6.1.2.13, 6.1.2.14 Pass; 6.2.16,
P.4.6, P.8.12, P.10.4 Inconclusive). 6.1.5.1, 6.2.4, 6.2.8, 6.2.12 were Inconclusive in the full run (the incoming
INVITE never reached the app: 6.1.1.6, 6.1.1.7 and 6.1.1.18 also pre-establish sessions they do not release, so
running the three of the q1 note last is not enough) and Pass on the fresh `drv-mvp2`, with 6.2.7 and P.10.2 (Pass).
6.1.1.17 is now "not supported" (a broadcast call with an implicit floor request) instead of N/A.

Every Fail:

| Service | Test case | Class | Evidence |
|---|---|---|---|
| MCPTT | 6.6.4 | bench | the suite lab has no `--location`; `send_message kind=emergency_alert_area` is not mapped on the real-server SS (as q1) |
| MCPTT | P.1.4 | bench | the suite lab has no XML protection: the affiliation PUBLISH bodies are `type="Normal"` (as p7) |
| MCPTT | P.1.9 | suite | step 4 judges the cached answer modes of a step-3 SS NOTIFY that was never sent ("the SS stimulus is not in the trace"); should be Inconclusive (as p7) |
| MCPTT | P.3.2, P.3.9, P.4.5 | suite | `no_floor_after` / `media_stopped` take the next call's Floor Acks (another floor port and Call-ID) as the left call's (q1) |
| MCPTT | P.5.1 | driver | `adhoc_alert` sets no position and uses the criterion `organization=mcx.test`, which the server refuses (q1: Pass with both changed) |
| MCData | 5.1 | client, not merge-caused | REGISTER #42's Contact has no `+g.3gpp.mcdata.ipconn` nor the `...icsi.mcdata.ipconn` ICSI while the client supports IPCONN (TS 24.282 §7.2.1 4); PICS `pc_mcdata_ipconn`). `IpConnectivity.FEATURE`/`ICSI` (phase 4b, 7d08078) are never used in the REGISTER; p7 had the row `na` |
| MCData | 6.2.1, 6.2.3 | suite | the media storage discovery MESSAGE has one body and goes as `application/vnd.3gpp.mcdata-info+xml` (TS 24.282 §6.4 one MIME body: q2's change); `msf_discovery` (`_lib_fd.py`) calls `message_headers` without `single=`, so it requires multipart/mixed. 6.2.1 steps 21, 30 also judge the user notification of FD notifications the SS never sent (as p7) |
| MCData | 6.2.11; D.1.6, D.1.7, D.1.8 | bench | no protection on the suite lab: the file / DATA PAYLOAD goes in clear (as p7) |
| MCData | 6.2.14 | suite | step 5 needs `fd_notify`, not in the openmcx SS adapter (q2) |
| MCData | 6.4.3, 6.4.4 | suite | the area notices are not available in the openmcx SS adapter (q2) |
| MCData | D.3.4 | suite | the SS's `update_document group_mcdata` edits the GMS's stored copy of the group document, and a fresh lab's GMS has none (`/data/groups` empty), so no edit is made (no "stored group document" in `ss-commands.log`) and steps 6-12 judge the client against the unchanged document; same on the fresh `drv-mvd2`. q2-r2 passed on a lab where an earlier test had stored the document |
| MCData | D.5.1, D.5.8 | driver | no position before the alert (redroid has no fix); D.5.8 also SS stimuli (q2) |
| MCData | D.5.2, D.5.7, D.5.12, D.5.14, D.6.3 | suite/bench | SS stimuli the real servers do not play (`priority_reinvite`, `priority_refuse`, `pre_hold`, `peer_emergency`, `fd_media`, `peer_cancel`, `fd_release_intent`) (q2) |
| MCData | D.5.6 | server | the server answers the emergency and imminent peril FD INVITEs 403 (q2) |
| MCVideo | 6.3.4 | suite | no area notice is sent (`emergency_alert_area` not in the openmcx adapter) (q3) |
| MCVideo | S.5.13 | suite | the bench's rights grant the ad hoc emergency / imminent peril calls the test expects refused (q3) |
| MCVideo | S.5.5, S.5.16 | driver | `adhoc_alert` without a position and with `organization=mcx.test` (q3) |

Every Fail row of MCData but 5.1, 6.2.1 step 14 and 6.2.3 is the same as in q2's runs (or p7's); no Fail is caused by
the merge. MCVideo S.18.1 was Inconclusive in the full run (the transmission request answered after the driver's
2.5 s, so step 3 counted as not performed) and Pass on the re-run.

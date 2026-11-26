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

Still open (server, not changed here):
- **A third-party deregistration after the new registration (S19, once in four runs):** bob, deregistered by the
  network with "deactivated", registered again 15 ms later on a new flow. The SIP core's first NOTIFY on his new reg
  event subscription was still the old binding's (terminated, "deactivated", the old `callid`), ending the
  subscription (the client ignored the contact by its `callid`), and the MCPTT server's `service_binding_removed`
  (reason deregistration) for the old binding came after bob's new service authorisation and removed it: his MCPTT
  calls then got 480 and alice's subscription to his affiliation 404 until he logged on again.
- **The alias called, one-to-one over the media plane (S84):** after the 300, client-c's INVITE does not carry the
  `<called-functional-alias-URI>` alice's INVITE has, so client-c is not told the alias called (it is printed, not
  checked).

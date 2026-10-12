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

In S9, 91-97% of the payloads arrived at each listener, all unchanged, with 52-57 reordered. In S10 the access time was
4-10 ms and mouth-to-ear 47-72 ms. Some S10 mouth-to-ear samples are large negative numbers: a listener reported no
first playout time for that burst. The check takes the maximum only, so it does not catch this; it is a gap in the
client's reporting or in the check, not a server fault.

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

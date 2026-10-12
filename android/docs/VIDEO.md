# Video calls (MCVideo)

Video calls between MC users, as MCVideo (TS 24.281) with the transmission control of TS 24.581, against the
OpenMCX `mcvideo-server`: private calls (§10.2) and prearranged group calls (§9.2.1). Three kinds of call:

| Call | What happens | Voice |
|---|---|---|
| **Hold to transmit** (transmission control) | Like PTT: hold **Transmit** and your camera and microphone go to the other phone; one side at a time. The other side sees who transmits and its video, and hears it. | Yes |
| **Two-way video** (no transmission control) | Both cameras stream at once as soon as the call connects. | No: the server relays no audio when nobody holds the transmission |
| **Group video** (transmission control) | The group's affiliated members are invited and join automatically; whoever holds **Transmit** is seen and heard by all the others. The call screen lists who is in the call. | Yes |
| **Push** (one-to-one video push, §13.2.2) | You send your camera to someone without them asking: the video starts on their screen at once, nobody holds Transmit. | No |
| **Pull** (one-to-one video pull, §12.2.1) | You watch someone's camera: their phone answers and sends at once, nobody holds Transmit. | No |
| **Record** and **Play** (one-to-server push §13.2.3, one-from-server pull §12.2.2) | **Record my video on the server** sends your camera to the MCVideo server, which saves it and tells the phone where (a recording URL); **Play** under "Video recordings" plays a recording back. | No |
| **Ambient viewing** (§15) | **View their camera (ambient)**: their phone answers and sends its camera by itself, and shows nothing (no call screen, no notification; Android's own camera indicator still lights). Only you may end it. **Let them view my camera (ambient)**: the reverse, and either side may end it. Needs the provisioned right (`<allow-request-remote-initiated-ambient-viewing>`, `…-locally-initiated-…`); otherwise refused (403). | Yes (the viewed phone's microphone) |
| **Video chat** (chat group, transmission control) | Nobody is invited: each member joins the chat group's call when they want (**Join video** under "Video chat groups" on the Call tab) and leaves when they want; the call lasts while anyone is in it. A late joiner sees the video from the next key picture. | Yes |

All are started from the Call tab: the camera icon next to a name places a hold-to-transmit call, the one next to a
group a group video call; the ▾ menu next to a name also offers **Video call and ring (they answer)** and **Two-way
video (no voice)**, **Push my video to them** and **Watch their camera (pull)**. In a group call, the ▾ next to a
participant offers **Start their video** and **Stop their video** (remote transmission, TS 23.281 §7.7.1.3.6–7): the
server starts or ends their transmission if you are authorised, and the call screen says whether it was done.

**Ringing (manual commencement, TS 24.281 §10.2.2.2.2).** A call placed with "ring" rings at the other phone: the
call screen shows **Answer** and **Decline** and a full-screen incoming video call notification (with the same two
buttons) appears. Decline answers 480 with warning 110, and the caller sees "declined"; after 20 s without an answer
the phone stops ringing and the caller sees "no answer". With the Logs tab's "Ring me first" on, every private video
call rings, even one placed as automatic. Group video calls always join automatically. The caller's screen shows
"Ringing…", and **End** cancels. The call screen shows the other side's video, the local preview, who is
transmitting, the **Transmit** button (hold), **Flip** (back or front camera: at once while you transmit, the transmission going on, else for the next one) and **End**.

## How it works

| Part | Where |
|---|---|
| Transmission control messages (MCV0/MCV1/MCV2 RTCP APP, Audio and Video SSRC fields), sharing the RTCP APP codec with MCPTT floor control | `core/…/mcvideo/Transmission.kt`, `media/RtcpApp.kt` |
| H.264 over RTP (RFC 6184 mode 1: single NAL and FU-A at 1200 octets, STAP-A accepted; loss discards only the damaged picture) | `core/…/mcvideo/H264Rtp.kt` |
| SDP (AMR-WB, H.264 `42e01f` packetization mode 1, `a=ssrc`, `m=application udp MCVideo`) and mcvideo-info | `McvideoSdp.kt`, `McvideoInfo.kt` |
| Chat group calls: INVITE with `session-type` chat and the group, nobody invited, implicit affiliation by the server; the chat groups come from provisioning (`mcvideo.chat_groups`) | `McvideoService.joinChat`, `client/Config.kt` |
| Emergency and imminent peril (§6.2.8): `<emergency-ind>` (with `<alert-ind>` false) or `<imminentperil-ind>` in the INVITE with the provisioned Resource-Priority (`mcvideo.resource_priority`); raising and cancelling in the call by re-INVITE; the state the server announces (INVITE, re-INVITE, INFO) followed; a red EMERGENCY or amber IMMINENT PERIL banner | `McvideoService.setPriority`, `McvideoInfo.kt`, `VideoCallScreen.kt` |
| Emergency alerts (§11.2): a MESSAGE to the MCVideo PSI with `<alert-ind>` and the UE's location (`location-info`, Annex F.3, TS 23.032 three-octet coordinates); cancellation, also of another member's alert by an authorised user (`<originated-by>`); received alerts (keyed by their originator) shown as a banner with a map link and a notification; the phone's location from the platform (fused, GPS or network) | `McvideoService.sendAlert`, `EmergencyLocation.kt`, `Engine.currentLocation`, `Screens.kt` |
| Participant list of a group call: SUBSCRIBE `conference` to the session's focus URI with an mcvideo-info naming the group; full and partial conference-info NOTIFYs (RFC 4575) update the list; Expires 0 when leaving | `McvideoService.kt`, `ConferenceInfo.kt` |
| Push and pull: `session-type` "one-to-one video push" / "one-to-one video pull" / "one-to-server video push" / "one-from-server video pull" (the pull naming the recording in `<video-pull-url>`); the sending side asks for the transmission implicitly (`mc_implicit_request` in the SDP) and is granted without a Transmission Request; the recording URL of a push to the server arrives in an INFO (`<video-push-url>`) and is kept under "Video recordings" | `McvideoService.push/pull/pushToServer/pullFromServer`, `McvideoSdp.kt`, `McvideoInfo.kt` |
| Ambient viewing: `session-type` "ambient-viewing" with `<ambient-viewing-type>` (remote-init or local-init) in `<anyExt>`, `Priv-Answer-Mode: Auto`, `recvonly` for the viewing side and `sendonly` for the viewed-to side (a one-way offer answered with the other direction), the viewed-to side's implicit transmission request; released only with the release capability (`Feature-Caps: g.3gpp.mcvideo.ambient-viewing-call-release`, annex D.3); the server's release reason (`<release-reason>` in the BYE) kept as the call's end reason; the viewed-to user of a remote-init call sees nothing (no screen, notification or history entry), and a call of their own ends it | `McvideoService.ambient`, `McvideoSdp.kt`, `McvideoInfo.kt`, `UiModel.kt` |
| Remote transmission: Remote Transmission (cancel) Request (MCV0 subtypes 7, 8) naming the user, the response's Result field; an unasked grant starts our own transmission, a revoke (cause 5) ends it | `TransmissionParticipant.remote`, `Transmission.kt` |
| Transmission participant: request, grant (switching to the granted Audio and Video SSRCs), taken, idle, revoke, release; reception control: the Media Transmission Notification (TS 24.581 §6.2.5.3.2: the transmitter's user ID and SSRCs, Reception Mode '0' receives at once, '1' sends a Receive Media Request) and the Media Reception Notification, both accepted automatically | `TransmissionParticipant.kt` |
| Media session: camera video and speech while transmitting, the transmitter's video decoded from its next key picture, key pictures paced | `VideoMediaSession.kt` |
| Calls: INVITE to the MCVideo PSI with the MCVideo ICSI and feature tag (private: `session-type` private and a resource-lists target; group: `prearranged` and the group), answer (a group call's invitation too), UPDATE refresh, BYE | `McvideoService.kt` |
| Registration, service authorisation (PUBLISH poc-settings with the same access token), affiliation | `client/McxClient.kt` |
| Camera switch during a transmission: the camera is closed and the other one opened onto the same GL stage (its frame size, rotation and mirroring), the encoder and the RTP stream going on (same SSRC, sequence and timestamps), a key picture sent at once so receivers get a clean picture from the new camera | `AndroidVideo.flip`, `CameraGl.switchSource` |
| Camera (Camera2) into the hardware H.264 encoder's input surface; MediaCodec decoder onto the call screen | `app/…/media/AndroidVideo.kt` |
| Upright pictures: an OpenGL stage turns each camera frame by the sensor orientation and the way the phone is held (the JPEG_ORIENTATION rule) before it reaches the encoder and the preview (mirrored for the front camera); the receiver shows the picture at its own shape | `app/…/media/CameraGl.kt`, `ui/VideoCallScreen.kt` |
| Call screen, Call tab entries, camera permission, camera foreground-service type during a call | `app/…/ui/VideoCallScreen.kt`, `Screens.kt`, `MainActivity.kt`, `McxService.kt` |
| Debug commands `video-call <target> [notc] [manual] [emergency]`, `video-group [group] [emergency\|imminent]`, `video-emergency on\|off`, `video-imminent on\|off`, `video-alert on\|off [lat lon] [for <MCVideo ID>]`, `video-alerts`, `video-chat [group]`, `video-transmit <s>`, `video-release`, `video-status`, `video-hashes`, `video-hangup`, `video-answer`, `video-camera front\|back` | `app/…/DebugControl.kt` |

Video is 640×480 (or the nearest camera size) turned upright, so 480×640 from a phone held upright and 640×480 from
one held sideways (chosen when the transmission starts; turning the phone during it keeps the picture upright,
letterboxed in the same frame), at 15 frames per second, 500 kbit/s, Constrained Baseline level 3.1,
an IDR every second with the parameter sets in-band. No CSK or KMS is needed: the lab's MCVideo is unprotected.

## Phone lab

```bash
lab/androidlab.py create --name mv1 --phones-only --mcvideo --fd-max-bytes 100000000 \
    --phone-device client-a:SERIAL_A:<ip-a> --phone-device client-b:SERIAL_B:<ip-b>
lab/androidlab.py up mv1
tests/video.py mv1 --screenshots
lab/androidlab.py down mv1
```

With `--reference-client client-c --client-image openmcx-client:<tag>` the lab keeps the fixture's C++ `mcx-client`
as `client-c`, a third MCVideo member beside the phones (its media to the host's published ports is sent straight
to the servers, as for the redroid clients). `tests/video.py` then adds it to the group call.

`--mcvideo` also gives the MCVideo server 30 s for a private call to be answered (the fixture's 5 s suits its automated
tests but not a person). `--mcvideo` adds the `mcvideo-server`, provisions each phone's MCVideo ID (`sip:alice.mcvideo@mcx.test`, …), moves
the MCVideo server's audio, transmission control and video ports to 7000, 7200 and 7400 (the MCPTT server keeps 4000
and 5000), publishes them, advertises the host address and maps each phone's MCVideo ID to its address.

## Results

Two phones (alice and bob) on the lab Wi-Fi, recorded in the full edition. `tests/video.py`: **26/26 checks passed** in
consecutive runs.

| Call | Direction | Pictures sent | Decoded | Identical to sent | Speech packets |
|---|---|---:|---:|---|---:|
| Hold to transmit | alice → bob | 84 | 84 | 84/84 | 284 |
| Hold to transmit | bob → alice | 83 | 83 | 83/83 | 274 |
| Two-way | bob → alice | | 136 | 138/138 | none (as specified) |
| Two-way | alice → bob | | 138 | 139/139 | none (as specified) |

Each receiver is told who transmits and accepts reception; a request while the other side transmits is rejected;
the calls release on both sides. "Identical" compares a SHA-256 of each picture's NAL units at the sender and the
receiver, keyed by SSRC and RTP timestamp: the server relays the H.264 payload unchanged. The receiving screen shows alice watching bob's camera. In the same lab MCPTT (S2, 10/10) and
attachments (55/55) still pass. Core unit tests 111/111, including loopback tests of the media session against a fake
MCVideo server; the video screen tests pass on the moto.

The first phone runs found and fixed: the decoder's off-screen surface needed a looper thread (no video was decoded);
key pictures sent in one burst were lost on the Wi-Fi (now paced over half a frame interval, with an IDR a second);
two pictures could share an RTP timestamp; and a loss at the start of a picture was attributed to the previous one.

### Group video calls

Lab `mv2` (as above plus `--reference-client client-c --client-image openmcx-client:0.1`): alice's phone
calls the group `operations`; bob's phone and the C++ reference client are invited. `tests/video.py`:
**39/39 checks passed** (private, two-way and group).

| Transmitter | Pictures sent | bob / alice (phones, MediaCodec) | client-c (reference, OpenH264) |
|---|---:|---|---:|
| alice's phone | 83 | bob 82, identical 82/82 | 83 |
| bob's phone | 84 | alice 82, identical 82/82 | 62 |
| client-c (test pattern) | 93 | alice 63, bob 63, none undecodable | – |

Every member is told who transmits; one transmission reaches every other member. The reference client decodes the
phones' camera video, and the phones its video: the two H.264 implementations interoperate through the server. The
phones decode the reference client from its second IDR (it sends one at the grant, before the receivers' reception
is accepted, then every 2 s); the phones send one every second.

### Ringing

`tests/video.py` on `mv2`: **49/49 checks passed** . The
called phone rings with the incoming call notification; answered, the call connects and video flows, and the
notification goes; declined, the caller gets 480 with warning 110; unanswered, the caller is told after about 20 s;
with ring first on, an automatic call rings too.

### Participant list

Every member of a group video call subscribes to the conference event package (TS 24.281 §9.2.3) and shows
"In call (3): client-c, alice, bob". `tests/video.py` on `mv2`: **53/53 checks passed** in two consecutive
runs : both phones and the reference client list the
same three members as connected, and when bob leaves, alice's list drops him while the call goes on.

The same runs led to a stricter depacketizer: after any sequence gap the next picture is discarded too, because the
lost packets may have been its first slice (the moto's encoder can split a picture into slices, each a whole NAL
unit); one picture in 53 had been shown without its first slice before.

### Picture rotation

The picture is turned upright at the sender, so every receiver, the reference client included, shows it the right way
up without any orientation signalling (the server rewrites RTP headers, so the RTP video orientation extension of
TS 26.114 would not reach the receivers). With both phones lying flat (orientation unknown, taken as upright) each
sends 480×640 turned 90° for its sensor and the other decodes 480×640. `tests/video.py`: **53/53**
; the reference client decodes the portrait pictures too.

### Video chat

The lab provisions each phone with the chat groups it belongs to (`sip:video-chat@mcx.test`). `tests/video.py`
on `mv2`: **62/62 checks passed** in two consecutive runs : alice
joins and bob is not invited; bob and the reference client join and all three list each other; alice's video reaches
bob (all identical) and the reference client; bob leaves (the call goes on), rejoins during alice's next transmission
and decodes it after skipping the pictures before the next key picture (4 to 5).

### Emergency and imminent peril

In a video call the screen has **Emergency** (and, in a group, **Imminent peril**); the ▾ menu next to a name offers
**Emergency video call**. The lab provisions the Resource-Priority values of the fixture (`mcptt.0`, `mcptt.10`,
`mcptt.15`); alice and bob may raise and cancel, the reference client may not. `tests/video.py` on a freshly
created `mv2`: **73/73 checks passed** : alice's emergency
group call puts bob and the reference client in the emergency state; the reference client's cancellation is refused
(403, "emergency cancellation not authorized") and the emergency stays; while bob transmits, alice's request (as the
emergency user) pre-empts him: bob is revoked and alice granted; alice cancels and everyone returns to normal;
imminent peril is raised and ended with bob following; an emergency private call puts both phones in the emergency
state.

Restarting single lab containers (the MCVideo server, the reference client) leaves stale state behind (lost
authorisations, an address the server no longer matches); recreate the lab (`down`, `up`) instead.

### Emergency alerts

Since 2026-09-29 the Call tab has one **Emergency alert** for all services, video last ([ALERTS.md](ALERTS.md)); the
per-group video rows described here are gone, and the `video-alert` debug commands remain.

The Call tab had **Emergency video alert** with **Send alert** / **Cancel alert** per MCVideo group; the alert carries the
phone's location (the app asks for the location permission; with no fix it says so, as the server requires one).
Others' alerts show as a red banner (who, group, coordinates, **Map**, **Cancel** for authorised users) and a
notification. In the lab alice and bob may raise alerts, only alice may cancel (hers and others'), the reference client
may not alert. `tests/video.py` on a freshly created `mv2`: **82/82 checks passed**
, with fixed coordinates: alice's alert reaches bob (within
about a metre, the precision of the coding) and the reference client (same three-octet codes); the reference client's
alert is refused (403); bob's alert reaches alice; bob's own cancellation is refused (403) and the alert stays; alice
cancels bob's (bob learns it ended) and then hers, and both disappear at bob and the reference client. An alert with
the moto's own location (network fix in 0.3 s) was also delivered.

Run the scenario on a freshly created lab: some time after a run the reference client can lose its MCVideo
affiliation at the server while still believing it has it (a reference client and server matter, not the phones);
the scenario then stops at once and asks for the lab to be recreated.

### Push, pull, recordings and remote transmission

`tests/video.py` on `mv2` (recreated just before): **94/94 checks passed**
. alice pushes her camera to bob (bob's call is a
"one-to-one video push", 31/31 pictures identical, nobody pressed Transmit); alice pulls bob's camera (bob's phone
answers and sends at once, 32/32 identical); alice records on the server and gets the recording URL, bob plays it
back and every picture matches alice's (80/80, same SSRC and timestamps, as the server replays the stored RTP); in a
group call with the reference client alice starts bob's video remotely (bob transmits, alice receives it, "done"),
then stops it (bob revoked with cause 5, "done"); bob, not authorised, asks to start alice's and is refused ("not
done").

### Ambient viewing

`tests/video.py` on a freshly created `mv2`: **111/111 checks passed**
. alice views bob's camera: bob's phone answers by itself as
the viewed-to user, transmits without asking, and shows its normal Call tab with no call notification; alice's 33/33
pictures are identical to bob's and she sends none; bob's release is refused (no release capability) and the call
stays up; alice ends it. alice asks bob to view her camera: she transmits by herself, bob's 32/32 pictures are
identical and his screen says "Viewing alice's camera (ambient)", and bob ends it. bob, not authorised, is refused
(403). While alice views bob, the reference client calls bob: alice's call ends with the release reason
`call-request-for-viewed-to-client` and the reference client's call to bob goes through.

### Camera switch during a transmission

`tests/video.py` on a freshly created `mv2`: **120/120 checks passed**
. During one transmission (within the server's 15 s
limit) alice switches from the back camera to the front one and back: each new camera delivers frames within a few
seconds, the transmission is never interrupted (one grant, no new request), and bob decodes all 169 pictures sent,
169/169 identical across both switches; his screen shows the table (back camera, phone lying flat), then the room
above the phone (front camera), then the table again.

The run also found a race in the video service: a call left the active list before it reached the history, so a
query in between got the previous call (seen as a two-way fingerprint mismatch). The call now enters the history
the moment it is released.

## Protection (TS 33.180)

In a lab created with `androidlab.py create --protected`, video calls are protected end to end:

- **Keys.** At start-up the phone fetches, with its access token (which now also asks for
  `3gpp:mc:ptt_key_management_service` and `3gpp:mc:video_key_management_service`), the KMS certificate and
  its MCVideo ID's keys for the current period (Annex D). It checks them before use: the ECCSI signing pair
  (RFC 6507) and the SAKKE receiver key (RFC 6508, by the pairing). It then makes its CSK and sends it to the
  MCVideo server with the MCVideo service authorisation (`application/mikey`, TS 24.281 7.2.5).
- **Transmission control** of every call with other users is SRTCP under the CSK (CS-ID 8, TS 33.180 9.4).
- **One-to-one calls** (private with or without transmission control, push, pull, ambient viewing): the
  caller makes a PCK for the called MCVideo ID and offers it as a MIKEY-SAKKE I_MESSAGE in `a=key-mgmt`,
  with audio and video as RTP/SAVP. The called phone authenticates it as from the calling MCVideo ID
  (ECCSI), opens it (SAKKE), and refuses a call whose PCK fails with 488 and warning 136. Both then send
  SRTP (AEAD_AES_128_GCM, RFC 7714) under the PCK: the initiator's streams on CS-ID 2, the receiver's on 3,
  MKI the PCK-ID.
- **Recording and playback** (push to and pull from the server) stay in the clear, as the server requires.
- **Group and chat calls**: once authorised, the phone subscribes at the MCVideo server to each of its groups'
  and chat groups' GMK (SUBSCRIBE `xcap-diff` with the GKTP selector, TS 33.180 5.7). The GMS's I_MESSAGE in the
  NOTIFY is authenticated as from the GMS (`gms_uri`) and must name the group and be active. Its CSB-ID, the
  user's GUK-ID, gives the GMK-ID (F.1.3). A group call's audio and video are RTP/SAVP under the GMK:
  - Each sender's streams are keyed by its GUK-ID (CS-ID 5), with MKI GMK-ID ‖ GUK-ID and the ROC in every packet.
  - A receiver finds the sender by matching the MKI's GUK-ID against the transmitter and the call's participants.

| Part | Where |
|---|---|
| ECCSI, SAKKE (parameter set 1), elliptic curve arithmetic | `core/…/security/Eccsi.kt`, `Sakke.kt`, `Curve.kt` |
| MIKEY-SAKKE I_MESSAGEs, UIDs, key properties, Protected Payloads (Annex E, F) | `security/Mikey.kt` |
| KMS documents and the key client (Annex D) | `security/Kms.kt` |
| SRTP/SRTCP with AES-GCM and the E.3 crypto sessions | `security/Srtp.kt` |
| GKTP subscription and xcap-diff NOTIFYs; group media keyed by the sender's GUK-ID | `McvideoService.subscribeGroupKeys`, `KmsDocuments.parseGktpXcapDiff`, `CallProtection.Group` |
| A call's keys; SAVP and `a=key-mgmt` in SDP | `mcvideo/CallProtection.kt`, `McvideoSdp.kt`, `McvideoService.kt` |

Tests: the RFC 6507 and 6508 test data (the SAKKE encapsulation byte for byte, and the pairing giving
g = <P,P>), the RFC 7714 SRTP and SRTCP packets, and the server's known answers for the key derivation. They
also run both ways against the server's own implementation (`mcx-keytool`, wolfCrypt): the phone code reads
its KMS documents and opens a GMK it sent, and the server code opens a GMK the phone code made.

### Protected one-to-one calls

`tests/video.py` on `pv1` (`--protected`): **87/87 checks passed**
. These checks:

- Both phones are keyed as their MCVideo IDs and have CSKs.
- In a private call, both phones hold the same PCK (purpose tag 1), transmission control runs under the CSKs, and
  bob's 40 of 40 pictures are identical.
- A capture at the MCVideo server has no transmission control message in the clear.
- A PCK with one bit changed in its signature is refused with 488 and warning 136.
- A protected two-way call decodes 78 of 78 and 82 of 82 pictures.

The rest of the scenario also runs protected: calls with transmission control (80/80 each way), push and pull,
ambient viewing both ways, and the camera switch (173/173). Recording and playback on the server pass in the
clear (87/87).

The first runs found three problems, now fixed:
- **Push and pull taken as group calls.** An incoming push or pull was taken as a group call when its
  mcvideo-info carried a request URI. This was an old bug, which the protection check exposed.
- **Server calls wrongly protected.** Calls with the server were protected, but the server keeps them clear.
- **Per-packet cost.** A new AES-GCM cipher object was made for every packet; it is now one per stream.

### Protected group and chat calls

`tests/video.py` on a freshly created `pv2` (`--protected`, with the reference client client-c): **129/130
checks passed** . Every part runs protected.

**The phones:**
- Both phones hold the GMK of `operations` and of `video-chat`, with the same GMK-ID at each.
- Group calls pass: 81/81 and 82/82 identical between the phones.
- The protection part's group call under the GMK passes (54/54).
- Emergency, remote transmission, chat with a late joiner, push, pull, ambient viewing and the camera switch
  all pass protected.

**The reference client (C++)** is in the group calls too, with the same GMK:
- It decodes the phones' protected video: 82 and 71 pictures.
- The phones decode its video: 60 pictures each.
- It subscribes only to its configured group's GMK, not to the chat group's, so it cannot decode the protected
  chat call. The report notes this instead of counting it as a failure.

**The one failure** was in the chat call: bob decoded 41 of 82 pictures. No packet failed to decrypt; bob lost 4
packets and skipped 13 pictures while waiting for a key picture. Each full run on this lab had one such failure,
each time a different check, and each time on the Xiaomi phone's receiving side:
- a late start of the first transmission: fixed by warming the ciphers up at call setup;
- a server grant for bob's remotely started transmission that never reached the phone (the server sends it once,
  without asking for an acknowledgement);
- this chat call.

All are packet loss on the phone's Wi-Fi, never a decryption failure. Each part passed when run again.

The unprotected lab `mv2`, freshly created, still passes the full scenario: **120/120**.

The runs also found three test problems, now fixed:
- **Recording playback cut short.** The playback of a recording is now watched until it ends, rather than for
  a fixed time.
- **Cleanup hang-ups.** A hang-up during cleanup now allows for the server having ended the call already.
- **First cipher slow on the phone.** Setting up the first AES-GCM cipher could take most of a second, while
  the first packets piled up. The ciphers are now warmed up when a call's keys are set.

## Not yet

Protection of MCVideo signalling (XML confidentiality and integrity, TS 33.180 9.3; not in the agreed scope).

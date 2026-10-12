# The OpenMCX client for Android

A native Android MC UE: an MCPTT client and an MCData client (with MCVideo, alerts and location, see the feature
documents) sharing one SIP registration, on-network, written from the 3GPP Release 19 specifications, for the
[openmcx-server](https://github.com/openMCX/openmcx-server) servers. It shares no code with the Linux client
(`../linux`) and is not tested against it, except as an optional reference client in video labs. It is not an
interoperability-certified implementation.

| Feature | Procedure |
|---|---|
| MCPTT private call | TS 24.379 §11.1.1.2.1; automatic and manual commencement, and forced commencement where policy allows it |
| MCPTT prearranged group call | TS 24.379 §10.1.1.2.1 |
| MCPTT ad hoc group call | TS 24.379 §17.2 |
| MCData short data service | TS 24.282 §9.2.2, one-to-one and group, with delivery and read notifications (§9.2.1.3, §12.2) |
| MCData file distribution, MCVideo, emergency alerts, location | [ATTACHMENTS.md](docs/ATTACHMENTS.md), [VIDEO.md](docs/VIDEO.md), [ALERTS.md](docs/ALERTS.md), [LOCATION.md](docs/LOCATION.md) |
| Start-up | OIDC with PKCE (TS 24.482), CMS user profile and GMS group documents, REGISTER, service authorisation by PUBLISH poc-settings, affiliation |
| Floor control | TS 24.380 floor participant; AMR-WB speech (RFC 4867) through the platform codec |
| Transport | One outbound TLS flow with mutual authentication and CRLF keepalives (RFC 5626). The phone never listens for SIP |

What the servers expect on the wire, clause by clause, is in the wire contracts: [start-up](docs/wire/phase1-startup.md),
[MCPTT](docs/wire/phase2-mcptt.md) and [MCData](docs/wire/phase3-mcdata.md).
[CONFORMANCE.md](docs/CONFORMANCE.md) maps requirements to code and evidence. [TEST_REPORT.md](docs/TEST_REPORT.md) has the
results. [SIP_CORE_FLOW_REUSE.md](docs/SIP_CORE_FLOW_REUSE.md) explains why the SIP core must reuse this client's flow, and
how OpenMCX does it. [PHONE.md](docs/PHONE.md) sets up a real phone on your Wi-Fi against the lab.

## Design

- **Outbound-only connectivity.** The phone never listens for SIP. It opens one TLS flow to the SIP core, registers with
  `+sip.instance` and `reg-id` (RFC 5626), and keeps the flow alive with CRLF keepalives. Every request to the phone,
  in-dialog ones included, comes back over that flow. If the flow fails, the client re-registers on a new one. Media
  stays UDP.
- **Android is the platform, not a host.** A foreground service owns the protocol engine. Calls go through Android's
  telecom stack (Jetpack `core-telecom`). Network changes, Doze, process death and permissions are handled.
- **A testable core.** `core` is pure Kotlin on the JVM with no Android imports. Everything that touches the platform
  (sockets and TLS material, codec, audio, storage, clock) sits behind interfaces that `app` implements.
- **Real identity and provisioning.** OIDC authorization code with PKCE in a Custom Tab (RFC 8252, loopback redirect).
  The lab's direct credential post is kept only as a debug-build login for automated tests. The client key lives in
  the Android Keystore. Configuration is bootstrapped from a provisioning bundle and then from the CMS.

## Layout

This directory is `android/` of the openmcx-client repository; the openmcx-server checkout is expected beside it
(`../../openmcx-server`).

| Path | Contents |
|---|---|
| `core` | The protocol engine, in pure Kotlin on the JVM with no Android imports: SIP (`sip/`), bodies (`body/`), SDP (`sdp/`), HTTP, IdMS and documents, MCPTT (`mcptt/`: SDP, floor codec and participant, media session, calls), MCData (`mcdata/`: clause 15 codec, SDS and file distribution), media (`media/`: RTP, AMR-WB packing, jitter buffer, WAV) and the client (`client/`: start-up, refreshes and recovery) |
| `app` | The Android app. It holds the foreground service and engine, MediaCodec AMR-WB and audio devices, the Keystore client certificate, telecom (core-telecom), notifications, PTT inputs, SQLite history, the Compose UI, a Custom Tab login and a debug-only control endpoint |
| `tools` | `gradle-docker.sh` (Dockerised Gradle), `build-image/` (the build image), `instrumented-test.sh` |
| `lab/androidlab.py` | Builds a lab: the OpenMCX servers with redroid (Android 15) containers as the MC UEs, or real phones |
| `lab/nettools`, `lab/capture` | Helper images: nftables and tc for coverage loss and impairment in a client's network namespace; packet capture |
| `tests/` | `scenarios.py` (end-to-end S1-S16 and S18), `video.py`, `attachments.py` (phone labs) and `test_androidlab.py` (unit tests of the lab tool) |
| `docs/` | Feature documents, wire contracts, conformance matrix and test report |

## Build

Builds run in Docker; nothing is installed on the host. `tools/gradle-docker.sh` builds the image from
`tools/build-image/Dockerfile` (Temurin JDK 17, Android SDK platform 37, build-tools 37) on first use, keeps the
Gradle cache in the `mcx-android-gradle` volume, and signs debug APKs with the host's `~/.android/debug.keystore`
so they install over existing builds with `adb install -r` (no key is stored in this repository).

```bash
tools/gradle-docker.sh :core:test              # JVM unit tests
tools/gradle-docker.sh :app:assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
tools/instrumented-test.sh <adb-serial>   # instrumented tests (codec, UI, telecom, browser login); leaves the app installed
```

## Run against the OpenMCX servers

Build the server images in `../../openmcx-server` first (`tools/build-images`, see its `docs/BUILD.md`; the lab uses the
images `openmcx-all:0.1`, Kamailio and the user database named by its compose file, or pass `--server-image`). For a
reference client (`--reference-client`), build the client image with `../linux/tools/build-image --server ../../openmcx-server`
and pass it with `--client-image`.

Host prerequisites for redroid and the impairment tests (the lab tools cannot do these without root):

```bash
sudo modprobe binder_linux devices=binder,hwbinder,vndbinder
sudo modprobe sch_netem
DOCKER_BUILDKIT=0 docker build -t mcx-nettools:1 lab/nettools
```

Then:

```bash
lab/androidlab.py create --name a1          # the OpenMCX fixture, provisioning bundles
lab/androidlab.py up a1                     # servers and three redroid clients; installs and provisions the APK
lab/androidlab.py exec a1 client-a status
tests/scenarios.py a1               # S1-S11; results in lab/run/a1/android-scenarios.json
lab/androidlab.py down a1
```

In the lab, `client-a` is alice, `client-b` is bob and `client-c` is client-c. All three are members of
`sip:operations@mcx.test`. Each redroid container is on the lab network under the reference client's hostname, with its
own address. That matters because the MCPTT server checks the SDP address against the resolved host and does not handle
NAT.

### The SIP core and flow reuse

An outbound-only client needs requests sent back over the flow it registered on. The OpenMCX Kamailio configuration does
this for RFC 5626 clients (a registration with `+sip.instance` or `reg-id`, Contacts with `ob`), with
`fix_nated_register`, `add_contact_alias`, `handle_ruri_alias` and `set_forward_no_connect`; listening clients are
still reached at their Contact. `androidlab.py` uses that configuration unchanged, so `--openmcx` names the openmcx-server
checkout (default `../../openmcx-server`) whose configuration is used. The
client uses a random `.invalid` host in Via and Contact, so the SIP core always adds the alias.

### Debug control endpoint

Debug builds listen on the abstract socket `@mcxclient-control` for one JSON request per line
(`{"cmd": "...", "args": [...]}`). `androidlab.py exec` forwards to it over adb. Release builds have no endpoint.

| Command | Action |
|---|---|
| `status` | Phase, registration, authorisation and affiliation per service, calls (with floor events and payload hashes per talk burst), SDS inbox and outbox |
| `events [N]` | The last N client events, including the SIP start lines |
| `private <mcptt-id> [auto\|manual\|force]`, `group [<group>]`, `adhoc <id> <id>…` | Place a call |
| `answer`, `reject`, `hangup`, `answer-mode automatic\|manual` | Call control |
| `ptt <wav> [seconds]`, `floor request\|release`, `audio-next <wav>` | Talk a file (the devices have no microphone) |
| `sds <mcdata-id> <text> [--disposition delivery\|read\|delivery-read]`, `sds-group <group> <text> …`, `display <message-id>` | Short data |
| `refresh register\|subscription\|token`, `drop-flow` | Force a refresh or a new flow |
| `history [N]`, `telecom`, `resolve <host>`, `sip-probe` | Diagnostics |
| `import <path>` | Install a `.mcxprov` provisioning file from the device and restart the engine |

## Using it on a phone

For a phone on your Wi-Fi against the lab, see [PHONE.md](docs/PHONE.md). Provisioning is a bundle in the app's files
(`provisioning/bundle.json`, `ca.crt`, `client.crt`, `client.key`). `androidlab.py` pushes it over adb, or the user
imports a single `.mcxprov` file on the **Logs** tab. An optional `endpoints` map tells the client where to connect for
a server name, while TLS still checks the name. On first start the client key is imported into the Android Keystore and the PEM file is
deleted. If the bundle has no IdMS username, sign-in is interactive: the IdMS login page opens in a Custom Tab and the app
receives the redirect on its loopback redirect URI (RFC 8252 §7.3).

Speech uses the microphone through `AudioRecord` (`VOICE_COMMUNICATION`) and plays through `AudioTrack` (voice
communication usage). Calls are registered with Android telecom as a self-managed account. PTT comes from:

- the on-screen button (press and hold)
- a headset button while the app is in front
- the PTT key broadcasts of rugged handsets (`android.intent.action.PTT.down`/`up`, `com.sonim.intent.action.PTT_KEY_DOWN`/`UP`)
- the app's own `org.openmcx.client.action.PTT_DOWN`/`UP`

With no call up, PTT calls the selected group.

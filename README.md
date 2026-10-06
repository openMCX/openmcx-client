```
                            __  __   ____ __  __
  ___   _ __    ___  _ __  |  \/  | / ___|\ \/ /
 / _ \ | '_ \  / _ \| '_ \ | |\/| || |     \  /
| (_) || |_) ||  __/| | | || |  | || |___  /  \
 \___/ | .__/  \___||_| |_||_|  |_| \____|/_/\_\
       |_|
```

# The openMCX clients — 3GPP Release 19 MCx clients

The project **openMCX** implements **3GPP Release 19 mission-critical services (MCx)** for an IP network. This
repository holds its **clients**: an Android app for phones, a dispatcher position in the browser, and `mcx-client`,
a headless Linux MC UE that the server's lab scenarios drive. All three speak **MCPTT** (mission-critical push to
talk), **MCData** (mission-critical data) and **MCVideo** (mission-critical video) on-network, written from the
3GPP Release 19 specifications. Off-network (ProSe) operation is outside this build.

This is the **standalone edition**: the clients work in one MC system. Interconnection between MC systems and
migration to a partner system are not part of it. The servers are in
[openmcx-server](https://github.com/openMCX/openmcx-server) and MCX Admin, which provisions and activates the
clients, in [openmcx-admin](https://github.com/openMCX/openmcx-admin).

**Containerised** Linux client and dispatcher (Docker); the **Android** app for Android 11 and later (tested on Android 13 and 16 phones), built in Docker
too ([building](#build)).

**Try it**: [Tutorial 1: MCX over WLAN with the Android app](https://github.com/openMCX/openmcx-server/blob/main/tutorials/01-mcx-over-wlan-android/README.md)
puts the app on two phones against the system from the Docker Hub images, and
[Tutorial 2](https://github.com/openMCX/openmcx-server/blob/main/tutorials/02-dispatcher-with-android/README.md) adds the
dispatcher position ([all tutorials](https://github.com/openMCX/openmcx-server/blob/main/tutorials/README.md)). The
ready-built app is [`android/apk/openmcx-client-v1.apk`](android/apk).

It is not an interoperability-certified implementation: see the limits in each client's documentation.

## Clients

### Android app ([`android/`](android))

- Start-up as TS 24.482 and TS 24.484 describe: OIDC with PKCE, activation from an MCX Admin link or QR code, the
  CMS user profiles and service configuration, the GMS group documents, registration and service authorisation
- MCPTT private calls (automatic, manual and forced commencement), prearranged and ad hoc group calls, floor control
  with AMR-WB speech
- Emergency and imminent-peril calls, emergency alerts and their cancellation
- MCData short data (one-to-one and group, with delivery and read notifications) and file distribution
- MCVideo private, group and chat calls, push and pull, ambient viewing, camera switching, protected video
- Location reporting; calls through Android's telecom stack; one outbound TLS flow (RFC 5626), the phone never listens

### Dispatcher ([`dispatcher/`](dispatcher))

- A position in the browser, bound to MCX Admin: activation from a link, operator sign-in and sign-out
- Group calls (normal, imminent peril, emergency) with hold to talk on the browser microphone, private calls
- Emergency alerts, SDS one-to-one and group, file distribution
- Runs `mcx-client` as its engine; on the operator's computer or another one on the network

### Linux client `mcx-client` ([`linux/`](linux))

- Every MCPTT, MCData and MCVideo procedure the openMCX servers support, driven by commands: the client of the
  server's automated lab scenarios
- MCPTT group, private, ad hoc, broadcast and remotely initiated calls, ambient listening, call-back, transfer and
  forwarding, functional aliases, regroups, pre-established sessions
- MCData SDS over the signalling and media planes, file distribution, IP connectivity, enhanced status, message store
  and notification channels
- MCVideo with transmission and reception control, video push and pull, ambient viewing, remote transmission
- Emergency and imminent-peril communications, alerts, location, end-to-end protection (GMK, PCK, XML signalling)

## Build

Check out openmcx-server beside this repository (`../openmcx-server`). Docker is the only build requirement.

```bash
linux/tools/build-image -t openmcx-client:0.1          # the client image; --server <path> if openmcx-server is elsewhere
docker build -t openmcx-dispatcher:0.1 --build-arg CLIENT_IMAGE=openmcx-client:0.1 dispatcher
android/tools/gradle-docker.sh :app:assembleDebug      # the Android app (see android/README.md)
```

The client image builds on `openmcx-dev:0.1` (from openmcx-server's `tools/mcx build`), builds `mcx-client` against
the server's protocol libraries and runs the binary test; the dispatcher image runs `go vet` and `go test` while it
builds. See [`linux/README.md`](linux/README.md) and [`dispatcher/README.md`](dispatcher/README.md) for the unit
tests, the settings and the lab scenarios.

## Documentation

- **[Android app](android/README.md)**: features, build, labs and tests; [wire contracts](android/docs/wire),
  [conformance](android/docs/CONFORMANCE.md), [test report](android/docs/TEST_REPORT.md)
- **[Dispatcher](dispatcher/README.md)**: scope, limits, API and labs
- **[Linux client feature list](linux/docs/FEATURES.md)** and [coding style](linux/docs/CODING_STYLE.md)

## Licence

The openMCX clients are licensed under the [GNU Affero General Public License v3.0 or later](LICENSE); a commercial
licence is available from the openMCX authors. Third-party software and its licences: [NOTICE](NOTICE).

## 3GPP specification correction candidates

> [!NOTE]
> The openMCX clients follow the same Release 19 specifications as openmcx-server. Defects and inconsistencies found in
> them are listed with proposed corrections in the openmcx-server repository:
> [3GPP correction candidates](https://github.com/openMCX/openmcx-server/blob/main/docs/3GPP_CORRECTION_CANDIDATES.pdf).
>
> This is a desk review of the specification text only. It is not an approved ETSI/3GPP change request, and none of it
> has been confirmed by interoperability testing with other implementations. Where the text is ambiguous, the clients
> follow the same reading as the server (for example the 16-octet protected-payload IV of TS 33.180), which other
> implementations may not share; confirm the behaviour with each peer.

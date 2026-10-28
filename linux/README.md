```
  ___                   __  __  ______  __
 / _ \ _ __   ___ _ __ |  \/  |/ ___\ \/ /
| | | | '_ \ / _ \ '_ \| |\/| | |    \  /
| |_| | |_) |  __/ | | | |  | | |___ /  \
 \___/| .__/ \___|_| |_|_|  |_|\____/_/\_\
      |_|
```

# The OpenMCX Linux client

`mcx-client`, the headless on-network MC service client of the project **OpenMCX**, for the
[OpenMCX servers](https://github.com/openMCX/openmcx-server): **MCPTT** (mission-critical push to talk), **MCData**
(mission-critical data) and **MCVideo** (mission-critical video) in one MC system. It is a headless MC UE that runs in
a container, driven by commands; the OpenMCX lab scenarios use it for every feature the servers support. It is
written from the 3GPP Release 19 specifications and is not an interoperability-certified implementation: see what it
supports, and what not, in the [feature list](docs/FEATURES.md).

## What it does

### MCPTT

- Prearranged, chat, broadcast and ad hoc group calls, with floor control and queueing
- Private calls (automatic, manual and forced commencement, with or without floor control), first-to-answer, ambient listening and remotely initiated calls
- Emergency and imminent-peril calls, emergency alerts, ad hoc group emergency alerts
- Call-back, transfer and forwarding; functional aliases; regroups and temporary groups
- Location reporting and requests, SEAL location, emergency alert and group geographic areas
- End-to-end protected media (GMK and PCK) and protected XML signalling

### MCData

- Short data (SDS) over the signalling and media planes: one-to-one, group, ad hoc and sessions
- File distribution over HTTP and over the media plane, with disposition notifications
- Emergency alerts, IP connectivity, enhanced status, communication release, functional aliases
- Message store, location

### MCVideo

- Prearranged, chat and ad hoc group calls, with transmission and reception control; private calls
- Emergency and imminent-peril calls, emergency alerts, ad hoc group emergency alerts
- Video pull and push, to and from the server with its recording; ambient viewing; remote transmission
- Functional aliases, regroups, remote group selection, location
- End-to-end protected video (GMK and PCK) and protected signalling

### Settings for the dispatcher

The [dispatcher](../dispatcher) uses the client as its engine, through settings that are off unless configured:
`media.live` (live audio over UDP), `sip.register_expires`, `mcptt.group_from_profile`, `oauth.forget_password`, and
`oauth.authorization` with `--authorization-request` (sign-in on IdMS's page).
Without them the client behaves as a plain client.

### Settings for an agent

[mcx-agent](../agent) uses the client as the engine of a voice or camera agent, through settings that are off unless
configured:

- `media.speech_dir`: each talk burst received in an MCPTT call is written there as a WAV file (16 kHz, 16-bit,
  mono) when it ends (the floor passes on or goes idle; without floor control after `media.speech_gap_ms`, 1500 ms;
  at `media.speech_max_s`, 120 s), with a `speech_received` event naming the file, the talker, the call type and its
  group or peer.
- `mcvideo.live` `{fifo, width, height, frame_rate, bitrate, key_frame_interval}`: while the client has the
  permission to transmit, its MCVideo pictures are the latest raw I420 picture written to the FIFO (created if
  missing) instead of the test pattern, encoded at that size (default 320 x 240, even, at most 1280 x 720) and rate
  (15, at most 30); black before the first. The status file's `mcvideo_live` counts the pictures taken.
- `mcvideo.live_receive` `{send_host, send_port}` (for the [client page](../dispatcher)): each received MCVideo picture,
  from an IDR picture on, goes in one UDP datagram to that port: a byte 1 for an IDR picture (else 0), the RTP
  timestamp (4 bytes, big-endian), then the picture's NAL units in H.264 annex B.

### Subscription durations

The xcap-diff subscriptions (RFC 5875) ask for durations the specifications leave to the subscriber (RFC 5875
section 4.5, RFC 6665 section 3.1.1): `subscriptions.group_documents_expires_s` (600 s by default),
`subscriptions.configuration_documents_expires_s` and `subscriptions.group_keys_expires_s` (3600 s each).

### IMS AKA (3GPP access)

On 3GPP access (TS 33.180 clause 6.2.1) the client authenticates to the SIP core with IMS AKA instead of digest over
TLS (TS 33.203, TS 24.229 clause 5.1.1.2.2): `"sip": {"auth": "aka", "isim": {"impi": "<user>@<domain>", "k": "<32
hex>", "opc": "<32 hex>", "amf": "8000"}, "pcscf": {"address": "<P-CSCF>", "port": 5060}, "port": <protected server
port>}`. The SIP signalling then runs over TCP inside IPsec ESP security associations that the client sets up in the
kernel, so the container needs `cap_add: NET_ADMIN` (without it the client stops with "IMS AKA needs CAP_NET_ADMIN").
The ISIM keeps its sequence numbers in `isim-sqn.json` next to the status file (or `sip.isim.sqn_file`). Activation
skips the SIP credential for such an identity, the registration asks for 600 000 s unless `sip.register_expires` says
otherwise, and the status file's `sip_security` shows the security associations in use. With `log_level` debug the
ESP keys of each SA are logged, for decrypting a capture. Without `sip.auth` the client stays on digest.
For a conformance test system, `"isim": {"algorithm": "test", "k": "<32 hex>", "res_length": 16}` puts the ISIM on the
test algorithm of TS 34.108 clause 8.1.2 instead of MILENAGE: no `opc`, no sequence number check, AUTS only when the
challenge's AMF is FFFF (AMF_RESYNCH), and a RES of `res_length` bytes (4 to 16, default 16). MILENAGE stays the
default.
`"sip": {"transport": "udp"}` with IMS AKA sends SIP over UDP (TS 24.229 clause 4.2A, TS 33.203 clause 7.1, UDP case):
requests and responses from the protected client port to the P-CSCF's protected server port, everything received on
the protected server port, and requests of 1300 bytes or more over TCP from the protected client port (RFC 3261
section 18.1.1); it needs the development image 0.3 (pjproject patch 0002). TCP stays the default; SIP outbound
(`sip.outbound`) is not available over UDP.

### Debug log

Off by default. `"log_level": "debug"` in the client's configuration, or the environment variable `MCX_LOG=debug`,
writes a line on stderr per SIP message sent and received, starting with the date, time and `OpenMCX DEBUG [client]`
(`SIP <-` or `SIP ->`, method or status, From, To, Call-ID, CSeq, peer; never the body). The JSON events on stdout are unchanged. See the server's [using the lab](https://github.com/openMCX/openmcx-server/blob/main/docs/USAGE.md#7-troubleshooting-logs-and-readable-sip),
which also describes the SIP core's TLS key file, a separate switch.

## Layout

| Path | Contents |
|---|---|
| `src/client/` | The client: `core/` (start-up, tokens, SIP dispatch, commands), `mcptt/`, `mcvideo/`, `mcdata/` |
| `tests/` | Unit tests of the client's stateless rules and a binary test |
| `docker/`, `tools/build-image` | The image build |
| `tools/` | `format`, `lint` and `check-names.py` ([coding style](docs/CODING_STYLE.md)) |

## Build and test

Check out openmcx-server beside the openmcx-client repository (`../../openmcx-server` from this directory), then
build the image:

```bash
tools/build-image -t openmcx-client:0.1                    # --server <path> if it is elsewhere
```

The image is based on `openmcx-dev:0.4` from openmcx-server (`tools/mcx build`) and holds `/usr/local/bin/mcx-client`
and the lab health check. It builds against the server repository's protocol libraries (`mcx_sip`, `mcx_core`,
`mcx_keying` and `include/mcx`), so build it from the same openmcx-server commit as the server image under test. The
build runs the binary test. A local build and the unit tests inside the dev image, from this directory (the mount holds both repositories):

```bash
docker run --rm --user "$(id -u):$(id -g)" -v "$PWD/../..:/work" -w /work/openmcx-client/linux openmcx-dev:0.4 \
  bash -lc 'cmake -S . -B build && cmake --build build -j4 && build/test_client_rules && tests/test_binary.py'
```

The behavioural
tests are openmcx-server's lab scenarios, which run this client in every lab:

```bash
cd ../../openmcx-server
tools/regress.py --image openmcx:<tag> --client-image openmcx-client:<tag> --all --jobs 4
```

## Documentation

- **[Developer guide](../docs/DEVELOPER_GUIDE.md)**: architecture, request flow, state machines and development workflow
- **[Feature list](docs/FEATURES.md)**: what the client supports, how it is verified, and what it does not do
- **[Coding style](docs/CODING_STYLE.md)**

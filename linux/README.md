```
                            __  __   ____ __  __
  ___   _ __    ___  _ __  |  \/  | / ___|\ \/ /
 / _ \ | '_ \  / _ \| '_ \ | |\/| || |     \  /
| (_) || |_) ||  __/| | | || |  | || |___  /  \
 \___/ | .__/  \___||_| |_||_|  |_| \____|/_/\_\
       |_|
```

# The openMCX Linux client

`mcx-client`, the headless on-network MC service client of the project **openMCX**, for the
[openMCX servers](https://github.com/openMCX/openmcx-server): **MCPTT** (mission-critical push to talk), **MCData**
(mission-critical data) and **MCVideo** (mission-critical video) in one MC system. It is a headless MC UE that runs in
a container, driven by commands; the openMCX lab scenarios use it for every feature the servers support. It is
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
`media.live` (live audio over UDP), `sip.register_expires`, `mcptt.group_from_profile` and `oauth.forget_password`.
Without them the client behaves as a plain client.

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

The image is based on `openmcx-dev:0.1` from openmcx-server (`tools/mcx build`) and holds `/usr/local/bin/mcx-client`
and the lab health check. It builds against the server repository's protocol libraries (`mcx_sip`, `mcx_core`,
`mcx_keying` and `include/mcx`), so build it from the same openmcx-server commit as the server image under test. The
build runs the binary test. A local build and the unit tests inside the dev image, from this directory (the mount holds both repositories):

```bash
docker run --rm --user "$(id -u):$(id -g)" -v "$PWD/../..:/work" -w /work/openmcx-client/linux openmcx-dev:0.1 \
  bash -lc 'cmake -S . -B build && cmake --build build -j4 && build/test_client_rules && tests/test_binary.py'
```

The behavioural
tests are openmcx-server's lab scenarios, which run this client in every lab:

```bash
cd ../../openmcx-server
tools/regress.py --image openmcx:<tag> --client-image openmcx-client:<tag> --all --jobs 4
```

## Documentation

- **[Feature list](docs/FEATURES.md)**: what the client supports, how it is verified, and what it does not do
- **[Coding style](docs/CODING_STYLE.md)**

# openMCX client

The clients of **openMCX**, the standalone edition of an on-network mission-critical (MCPTT, MCData, MCVideo) system
written from the 3GPP Release 19 specifications. They work with the [openmcx-server](https://github.com/openMCX/openmcx-server)
and are administered with openmcx-admin. One MC system: there is no interconnection or migration between systems.

| Directory | Contents |
|---|---|
| [`linux/`](linux) | `mcx-client`, the C++ headless MC UE for MCPTT, MCData and MCVideo; runs in a container, driven by commands. The lab scenarios of openmcx-server use it. |
| [`dispatcher/`](dispatcher) | A dispatcher position in the browser: a Go web service and UI that runs `mcx-client` as its engine. |
| [`android/`](android) | The Android app (Kotlin): MCPTT, MCData and MCVideo for phones, with its lab tooling (real phones and redroid containers), tests and documents. |

## Build

Check out openmcx-server beside this repository (`../openmcx-server`). Docker is the only build requirement.

```bash
linux/tools/build-image -t openmcx-client:0.1          # the client image; --server <path> if openmcx-server is elsewhere
docker build -t openmcx-dispatcher:0.1 --build-arg CLIENT_IMAGE=openmcx-client:0.1 dispatcher
```

The Android app builds in Docker too (`android/tools/gradle-docker.sh :app:assembleDebug`; see [`android/README.md`](android/README.md)).

The client image builds on `openmcx-dev:0.1` (from openmcx-server's `tools/mcx build`), builds `mcx-client` against
the server's protocol libraries and runs the binary test; the dispatcher image runs `go vet` and `go test` while it
builds. See [`linux/README.md`](linux/README.md) and [`dispatcher/README.md`](dispatcher/README.md) for the unit
tests, the settings and the lab scenarios.

## Documentation

- [Linux client feature list](linux/docs/FEATURES.md)
- [Dispatcher: scope, limits, API and labs](dispatcher/README.md)
- [Coding style](linux/docs/CODING_STYLE.md)
- [Android client: features, build, labs and tests](android/README.md); [wire contracts](android/docs/wire), [conformance](android/docs/CONFORMANCE.md), [test report](android/docs/TEST_REPORT.md)

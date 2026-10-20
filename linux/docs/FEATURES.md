# The OpenMCX Linux client features

What the Linux client `mcx-client` supports, and how it is verified. It is an on-network MC service client for the
[OpenMCX servers](https://github.com/openMCX/openmcx-server), written from the 3GPP Release 19 specifications: a
headless MC UE in a container, driven by commands. This page is a map, not a conformance claim: see
[what it does not do](#what-is-not-supported).

| | |
|---|---|
| **Form** | Container image `openmcx-client`, one MC UE per container |
| **Driven by** | Commands (`mcx-client --command ...`) and a status file |
| **Services** | MCPTT, MCData, MCVideo, in one MC system |
| **Verified by** | The lab scenarios of openmcx-server, which run this client in every lab |

## Common

- **Identity** (TS 24.482): OIDC authorization code with PKCE at the IdMS.
- **Documents** (TS 24.484, TS 24.481): the MCPTT, MCData and MCVideo user profiles from the CMS; group documents from
  the GMS.
- **Registration and service authorisation** (TS 24.379 clause 7.2, TS 24.282 clause 7.2, TS 24.281 clause 7.2): one
  SIP registration for all services, over mutual TLS, then service authorisation and affiliation (TS 24.379
  clause 9.2.1). Besides the selected group, MCPTT can affiliate to other groups of the user profile (`monitor`):
  their group calls, priority and emergency alerts are received, one call at a time, without protected media. A refused service authorisation that can be transient (403, 408, 480, 500, 503) is retried after a
  growing delay (5, 10, 20, then every 60 s, or the Retry-After of a 500 or 503).
- **Media**: AMR-WB speech over RTP (RFC 4867), H.264 video (RFC 6184); floor control (TS 24.380) and transmission and
  reception control (TS 24.581). Another participant's transmission is announced by the Media Transmission
  Notification (clause 6.2.5.3.2): with automatic reception the client receives at once, with manual reception it
  sends Receive Media Request (unless `mcvideo.auto_receive` is false).
- **Location** (TS 24.379 clause 13, TS 24.282 clause 17, TS 24.281 clause 18): configurations, triggers, reports and
  requests, for each service.
- **Key management** (TS 33.180): the KMS, key material and group keys from the GMS.

Everything the OpenMCX servers offer, as their [feature list](https://github.com/openMCX/openmcx-server/blob/main/docs/FEATURES.md)
has it: the lab scenarios run each server feature through this client.

### MCPTT (TS 24.379, TS 24.380)

- Group calls: prearranged, chat and broadcast, with floor control, floor priority, queueing and pre-emption; ad hoc
  group calls with participants or criteria, and their modification
- Private calls: automatic, manual and forced commencement, with and without floor control; first-to-answer
- Ambient listening (locally and remotely initiated); remotely initiated private and group calls
- Emergency and imminent-peril calls (group and private), emergency alerts and their cancellation by an authorised
  user, ad hoc group emergency alerts
- Call-back, call transfer and call forwarding
- Functional aliases (activation, takeover, calls to an alias); regroups and temporary groups
- Pre-established sessions: not in present edition (the `pre-establish` test aid checks the server's refusal)
- Location reporting and requests, emergency alert areas and group geographic areas, SEAL location reporting
  (TS 24.545)
- End-to-end protection: media and floor control (GMK, PCK), XML signalling confidentiality and integrity

### MCData (TS 24.282, TS 24.582)

- Short data service over the signalling plane and the media plane (MSRP): one-to-one, group and ad hoc, and SDS
  sessions; delivery and read notifications
- File distribution over HTTP and over the media plane: offers, acceptance, rejection, deferral, extension and release
- Emergency alerts, emergency and imminent-peril communications
- IP connectivity, enhanced status, communication release
- Functional aliases (status subscription, outcome by NOTIFY, take-over once offered), message store (storage control and notification channels), location
- End-to-end protected payloads and signalling

### MCVideo (TS 24.281, TS 24.581)

- Prearranged and chat group calls with transmission control (queueing, revocation, implicit request) and reception
  control; ad hoc group calls; private calls with and without transmission control
- Emergency and imminent-peril calls with pre-emptive transmission, emergency alerts, ad hoc group emergency alerts
- Video pull and push, one-to-one and to or from the server with its recording; ambient viewing; remote transmission
- Functional aliases, regroups, remote group selection, location
- End-to-end protected video (GMK, PCK), protected transmission control and XML signalling

## What is not supported

- Off-network (ProSe) operation
- Interconnection between MC systems and migration to a partner system: the client works in one MC system
- Whatever the OpenMCX servers do not support either: see their
  [limitations](https://github.com/openMCX/openmcx-server/blob/main/docs/LIMITATIONS.md)

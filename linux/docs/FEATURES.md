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
  growing delay (5, 10, 20, then every 60 s, or the Retry-After of a 500 or 503). With `mcptt.authorise_by_register`
  each service's authorisation is carried in a REGISTER of its own (clause 7.2.1) instead of the poc-settings PUBLISH.
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
- End-to-end protected ad hoc group calls (TS 24.379 clauses 17.2.2.1.1 step 10 c) iii) and 17.2.2.1.2 step 7, TS 33.180
  clause 5.7): with protected media the offer carries `<end-to-end-security>` "true"; the speech is keyed with the GMK
  of the preconfigured group the controlling function names in `<preconfigured-group-id>` (the 2xx, or the
  invitation), whose GMK is subscribed to from the start when listed in `mcptt.adhoc_security_groups`; without the
  element or the GMK the call ends (originator) or is refused (invitee)
- Private calls: automatic, manual and forced commencement, with and without floor control; first-to-answer
- Ambient listening (locally and remotely initiated); remotely initiated private and group calls
- Emergency and imminent-peril calls (group and private), emergency alerts and their cancellation by an authorised
  user, ad hoc group emergency alerts
- Call-back, call transfer and call forwarding
- Functional aliases (activation, takeover, calls to an alias); regroups and temporary groups
- Pre-established sessions (TS 24.379 clause 8, TS 24.380 clause 9): `pre-establish`, `pre-session-modify`,
  `pre-session-release`, with ICE; group, broadcast, chat (emergency, imminent peril, functional alias), private,
  first-to-answer and ambient listening calls over the session by REFER, received by Connect, re-INVITE or INVITE with
  Replaces. In a call over the session, the user's emergency or imminent peril upgrade or cancel (group and private
  calls, TS 24.379 clauses 10.1.1.2.1.3 to 10.1.1.2.1.5, 10.1.2.2.1.3 to 10.1.2.2.1.5, 11.1.1.2.1.4, 11.1.1.2.1.5),
  the ad hoc call's modification, the private call's hold and resume for an announced transfer and
  `refresh-reinvite` go as a re-INVITE in the session's dialog (to the URI identifying the session) with the session's
  negotiated SDP; the participating function's priority re-INVITE in that dialog (clause 6.3.3.1.6) is answered with
  the session's media and sets the group's or, in a private call, the MEPP priority state
- Location reporting and requests, emergency alert areas and group geographic areas, SEAL location reporting
  (TS 24.545)
- End-to-end protection: media and floor control (GMK, PCK), XML signalling confidentiality and integrity
- Service settings (TS 24.379 clauses 7.2.3, 7.2.4): the poc-settings with the answer mode and the selected user
  profile's index, the settings-only PUBLISH (`settings-publish [remove]`, also on `answer-mode`), the settings
  subscription (`settings-subscribe|resubscribe|unsubscribe [fetch]`, status `service_settings`)
- Affiliation of other users (clauses 9.2.1.2 to 9.2.1.5): `affiliation-subscribe|unsubscribe <MCPTT ID>`,
  `affiliate-remote <MCPTT ID> <group> affiliate|deaffiliate mandatory|negotiated`, a received negotiated-mode request
  answered with `affiliation-accept [<group>...]` or `affiliation-decline` (status `affiliation_command`)
- Subscription to group dynamic data (`gdd-subscribe|resubscribe|unsubscribe <group>`, clause 9.2.1.6)
- `<gw-mcptt-usage>` in the service authorisation when configured (`mcptt.via_gateway_ue`, clause 5.6)
- `mc_granted` in the initial offer with an implicit floor request (TS 24.380 clause 14.2.4; `mcptt.offer_granted`,
  default false): the floor granted in the SIP 200 (OK) is taken as a Floor Granted (clause 6.2.4.4.2)

### MCData (TS 24.282, TS 24.582)

- Short data service over the signalling plane and the media plane (MSRP): one-to-one, group and ad hoc, and SDS
  sessions; delivery and read notifications
- File distribution over HTTP and over the media plane: offers, acceptance, rejection, deferral, extension and release
- Emergency alerts, emergency and imminent-peril communications
- IP connectivity, enhanced status, communication release
- Pre-established session for SDS (TS 24.282 clauses 18.3 and 9.2.5, TS 24.582 clause 12): `mcdata-pre-establish`,
  `mcdata-pre-release`; while it is established, standalone SDS over the media plane and SDS sessions (one-to-one and
  group, normal priority) go over it by REFER, the participating function's re-INVITE reporting or offering the
  communication; released by REFER "BYE" or the server's re-INVITE. Not built: ICE connectivity checks (the offer
  carries the MSRP listener's host candidate), emergency and imminent peril communications over the session
- Functional aliases (status subscription, outcome by NOTIFY, take-over once offered), message store (storage control and notification channels), location
- End-to-end protected payloads and signalling

### MCVideo (TS 24.281, TS 24.581)

- Prearranged and chat group calls with transmission control (queueing with Queue Position Request, revocation,
  implicit request) and reception control, with the participant timers T100 to T104 and counters C100 to C104
  (`mcvideo.transmission_timers`); automatic and manual commencement (`Answer-Mode`, `mcvideo.answer_mode`) for
  group and private calls; private calls with and without transmission control
- Ad hoc group calls, also emergency and imminent peril (`mcvideo-adhoc emergency|imminent`), with participant list or
  criteria modification (`mcvideo-adhoc-modify`)
- End-to-end protected ad hoc group calls (TS 24.281 clauses 22.2.2.1.1 step 10 d) iii) and 2xx step 3, 22.2.2.1.2
  step 8; TS 33.180 clause 5.7): with protected media the offer (a re-join's too) is RTP/SAVP with
  `<end-to-end-security>` "true"; the video is keyed with the GMK of the preconfigured group named in
  `<preconfigured-group-id>` of the 2xx (originator; without it or its GMK the call ends with a BYE) or of the INVITE
  (invitee; else 488). The GMKs of `mcvideo.adhoc_security_groups` (absent: `mcptt.adhoc_security_groups`) and of
  every group a `<preconfigured-group-id>` named are subscribed to from the start; status `mcvideo_call_protected`,
  `mcvideo_security_group`, `mcvideo_adhoc_security_groups`
- Emergency and imminent-peril calls with pre-emptive transmission, emergency alerts, ad hoc group emergency alerts
  with participant criteria modification (`mcvideo-adhoc-alert modify`); cancelling another user's emergency and
  emergency alert (`mcvideo-emergency off alert=<MCVideo ID>`); cancelling a group's imminent peril state without a
  call (`mcvideo-imminent-cancel`); the user profile's `<ruleset>` authorisations and `<MCVideoGroupInitiation>`,
  `<EmergencyAlert>` and `<MCVideoPrivateRecipient>` target entries checked before each of them
- Received emergency and imminent peril ad hoc group calls (per-group emergency states, the group's security
  information); ad hoc call redirection notifications
- Transmission cancel request notify while queued
- Emergency private calls with an emergency alert (`mcvideo-private <id> <mode> emergency alert`,
  `mcvideo-emergency on|off alert`) and the controlling function's SIP INFO on the alert's outcome
- Media plane control messages with unknown subtypes, names or fields ignored (TS 24.581 9.1.4); `mc_granted` in the
  initial offer with an implicit request (`mcvideo.offer_granted`) and the SSRCs of the SDP answer
- XML integrity protection: a body with a failed or unverifiable signature is refused (403, warning 139)
- XML confidentiality protection of every element and attribute of TS 24.281 clause 4.8; a body that cannot be
  decrypted is refused (403, warning 140)
- The KMS of a private call's target from the user profile (`<PrivateCallKMSURI>`), other KMSs' certificates from
  the KMS CertCache, and KMS redirect (488 with a KRR)
- Leaving and rejoining an ad hoc group call (`mcvideo-rejoin`)
- Transmission and reception priority (`mc_priority`, `mc_reception_priority`) offered from the user profile's
  `<Priority>`, negotiated, and used in Transmission and Receive Media Requests
- Settings-only PUBLISH (`mcvideo-settings-publish [remove]`) and the settings of each client from the poc-settings
  NOTIFY; remote affiliation requests accepted per group (`mcvideo-affiliation-accept [<group>...]`),
  de-affiliation from one group (`mcvideo-deaffiliate <group>`)
- MCVideo group documents in group management (`group-create ... mcvideo`, `temporary-create ... mcvideo`)
- Subscription to group dynamic data (`mcvideo-gdd-subscribe`, TS 24.281 clause 8.2.1.6)
- `<gw-mcvideo-usage>` in the service authorisation when configured (`mcvideo.via_gateway_ue`)
- Video pull and push, one-to-one and to or from the server with its recording; ambient viewing; remote transmission
- Functional aliases, regroups, remote group selection, location
- End-to-end protected video (GMK, PCK), protected transmission control and XML signalling

## What is not supported

- Off-network (ProSe) operation
- Interconnection between MC systems and migration to a partner system: the client works in one MC system
- MCVideo pre-established sessions (and ambient viewing over them)
- Whatever the OpenMCX servers do not support either: see their
  [limitations](https://github.com/openMCX/openmcx-server/blob/main/docs/LIMITATIONS.md)

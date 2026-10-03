# Phase 2 wire contract: MCPTT calls (on-network, on-demand)

The native Android client implements this contract for private, prearranged group and ad hoc group calls against the
OpenMCX MCPTT server. It is derived from the specifications and checked against the server source and lab captures.

**Sources.** ETSI TS 124 379 V19.7.0 (3GPP TS 24.379 Rel-19), cited as "24.379 §x". TS 24.380 clauses are not checked against a local copy.
Its clause numbers below come from knowledge of Rel-19 and are marked *(24.380, unverified)*. The byte layouts were
confirmed against `src/floor.cpp` and the capture. Server pointers are `src/<file>:<line>` of openmcx-server, from an earlier layout of the
sources (they may have moved). The examples come from a lab capture of clear SIP over TCP (`create --debug-sip`).
The default lab fixture uses TLS on 5061. The bodies and headers below are the
same over either transport.

**Scope.** Phase 1 covers registration, service authorisation (PUBLISH poc-settings carrying the client ID) and
affiliation. Every call below assumes that phase 1 has succeeded, because the server rejects calls from unauthorised
users. Pre-established sessions are out of scope: the server refuses them with 403 "100 function not allowed due to
pre-established session not supported" (`server.cpp:3886`). End-to-end or protected media is also out of scope: the
lab groups have `protect_media` set to false, and the server rejects protected private or ad hoc SDP with 488.

Identities in the a1 lab:

| Item | Value |
|---|---|
| Participating PSI (Request-URI of every originating INVITE) | `sip:mcptt@mcx.test` (`mcx-server.json` `mcptt.service_uri`) |
| SIP public user identity | `sip:alice@mcx.test` (IMS level: From, To, P-Asserted-Identity) |
| MCPTT ID | `sip:alice.mcptt@mcx.test` (appears only in bodies and floor messages) |
| MCPTT client ID | `urn:uuid:…` (`client-*.json` `mcptt.client_id`). It must be the one sent in poc-settings |
| Group | `sip:operations@mcx.test` (members alice, bob, client-c, client-d) |
| MCPTT session identity | `sip:mcptt@mcx.test;mcptt-session=<43-char token>`, returned in the server's `isfocus` Contact |

---

## 0. MIME types, namespaces, tags (verbatim)

| Use | String |
|---|---|
| ICSI | `urn:urn-7:3gpp-service.ims.icsi.mcptt` |
| Feature tags (Contact) | `+g.3gpp.mcptt` and `+g.3gpp.icsi-ref="urn%3Aurn-7%3A3gpp-service.ims.icsi.mcptt"` |
| Accept-Contact (two headers, as sent) | `Accept-Contact: *;+g.3gpp.mcptt;require;explicit` and `Accept-Contact: *;+g.3gpp.icsi-ref="urn%3Aurn-7%3A3gpp-service.ims.icsi.mcptt";require;explicit`. The server also accepts both tags in one header (`admission.hpp:11`) |
| P-Preferred-Service | `urn:urn-7:3gpp-service.ims.icsi.mcptt` |
| Multipart body | `multipart/mixed;boundary=<token>` |
| SDP | `application/sdp` |
| MCPTT info | `application/vnd.3gpp.mcptt-info+xml`, namespace `urn:3gpp:ns:mcpttInfo:1.0` (24.379 F.1) |
| Resource list | `application/resource-lists+xml`, namespace `urn:ietf:params:xml:ns:resource-lists` (RFC 5366/4826) |
| Conference event (optional, §10.1.3.1) | `application/conference-info+xml`, namespaces `urn:ietf:params:xml:ns:conference-info` and `urn:3gpp:ns:mcpttConfInfo:1.0` |
| Location (not needed) | `application/vnd.3gpp.mcptt-location-info+xml`, `urn:3gpp:ns:mcpttLocationInfo:1.0` |
| Floor control | RTCP APP (PT 204), name `MCPT` (ASCII 4D 43 50 54). `MCPC` is only for pre-established session call control and is not used here |
| SDP floor media | `m=application <port> udp MCPTT` + `a=fmtp:MCPTT mc_floor_ssrc=<u32>[;mc_priority=<0-99>][;mc_queueing][;mc_implicit_request]` |
| Resource-Priority (normal call) | `mcptt.0`. The server always sends it. Imminent peril is `mcptt.10` and emergency is `mcptt.15` |
| Warning format (24.379 §4.4) | `Warning: 399 <host> "<3-digit code> <text>"`. For example, the server sends `399 mcx "145 unable to determine called party"` |

`mcpttinfo` element order (F.1.2 `mcptt-ParamsType` sequence) is `mcptt-access-token`, `session-type`,
`mcptt-request-uri`, `mcptt-calling-user-id`, `mcptt-called-party-id`, `mcptt-calling-group-id`, `required`,
`emergency-ind`, `alert-ind`, `imminentperil-ind`, `broadcast-ind`, `mc-org`, `floor-state`, `associated-group-id`,
`originated-by`, `MKFC-GKTPs`, `mcptt-client-id`, `alert-ind-rcvd`, `gw-mcptt-usage`, (other), `anyExt`. URI and string
values are wrapped as `<x type="Normal"><mcpttURI>…</mcpttURI></x>` or `<mcpttString>`. The server's parser checks the
namespace and rejects duplicates. It does not check order and accepts `mcpttURI` or `mcpttString` for any wrapped value
(`protocol.cpp:437`, `:93`). The client should still emit schema order.

**Multipart framing is strict** (`protocol.cpp:848`). Lines end in CRLF. The body must begin with `--boundary`, with no
preamble other than a single leading CRLF. The boundary is at most 70 characters and a body has at most 8 parts. Each
part has a `Content-Type` header, and the epilogue is only whitespace. The server's own multipart bodies begin with CRLF,
and each part carries a `Content-Length` header. The server prints `Content-Length` with padding spaces
(`Content-Length:   942`). The client's parser must accept all three.

---

## 1. Private call (24.379 §11.1.1.2.1)

### 1.1 Originating INVITE, client to server

The clauses are 24.379 §11.1.1.2.1.1 steps 1–17, §6.2.1 for the SDP, and RFC 5373 for Answer-Mode. The server handles it
in `private_invite`, `server.cpp:2470`.

```
INVITE sip:mcptt@mcx.test SIP/2.0
Via: SIP/2.0/TLS <host>:<port>;rport;branch=z9hG4bK…
Max-Forwards: 70
From: <sip:alice@mcx.test>;tag=…
To: <sip:mcptt@mcx.test>
Call-ID: …
CSeq: 1 INVITE
Route: <sip:sip-core:5061;transport=tls;lr>
Contact: <sip:alice@…>;+g.3gpp.mcptt;+g.3gpp.icsi-ref="urn%3Aurn-7%3A3gpp-service.ims.icsi.mcptt"
Allow: INVITE, ACK, CANCEL, BYE, UPDATE, INFO, MESSAGE, NOTIFY, OPTIONS
Supported: timer
Session-Expires: 1800
Min-SE: 90
Accept-Contact: *;+g.3gpp.mcptt;require;explicit
Accept-Contact: *;+g.3gpp.icsi-ref="urn%3Aurn-7%3A3gpp-service.ims.icsi.mcptt";require;explicit
P-Preferred-Service: urn:urn-7:3gpp-service.ims.icsi.mcptt
Answer-Mode: Auto
Content-Type: multipart/mixed;boundary=mcx0a4ac9ce
Content-Length: …

--mcx0a4ac9ce
Content-Type: application/sdp

v=0
o=- 242466858 1 IN IP4 172.21.0.15
s=-
c=IN IP4 172.21.0.15
t=0 0
m=audio 20124 RTP/AVP 96
i=speech
a=rtpmap:96 AMR-WB/16000/1
a=fmtp:96 octet-align=0;mode-change-capability=2
a=ptime:20
a=maxptime:240
a=rtcp:20125
a=sendrecv
m=application 20126 udp MCPTT
a=fmtp:MCPTT mc_floor_ssrc=3166165441

--mcx0a4ac9ce
Content-Type: application/vnd.3gpp.mcptt-info+xml

<?xml version="1.0" encoding="UTF-8"?><mcpttinfo xmlns="urn:3gpp:ns:mcpttInfo:1.0"><mcptt-Params><session-type>private</session-type><mcptt-client-id type="Normal"><mcpttString>urn:uuid:8034b521-…</mcpttString></mcptt-client-id></mcptt-Params></mcpttinfo>
--mcx0a4ac9ce
Content-Type: application/resource-lists+xml

<?xml version="1.0" encoding="UTF-8"?><resource-lists xmlns="urn:ietf:params:xml:ns:resource-lists"><list><entry uri="sip:bob.mcptt@mcx.test"/></list></resource-lists>
--mcx0a4ac9ce--
```

Header rules (the step numbers are from §11.1.1.2.1.1):

- **Request-URI** is the PSI (step 1). Any other value gets 404 (`server.cpp:2486`).
- **Contact, Accept-Contact and P-Preferred-Service** follow steps 5–8. If the Accept-Contact tags are missing, the
  server answers 403 with no Warning (`server.cpp:2482`, `accepts_mcptt` `:1243`).
- **Commencement** follows step 14. Use exactly one of:
  - `Answer-Mode: Auto`, which requires `automatic_commencement`. Without it the server returns 403 with warning 125.
  - `Answer-Mode: Manual`, which requires `manual_commencement`. Without it the server returns 403 with warning 126.
  - `Priv-Answer-Mode: Auto` to force commencement, which requires `force_auto_answer`. Without it the server returns
    403 with warning 143. Alice in a1 does not have it, so a forced call from her is rejected.

  The values are case-sensitive. Any other value, including `Priv-Answer-Mode: Manual`, gets 400
  (`private_call.hpp:45-55`). If the header is omitted, the server skips the commencement authorisation and forwards
  nothing, and the callee applies its local setting.
- **Session timer** (RFC 4028; §10.1.1.2.1.1 steps 8–9 give the same advice for group calls) is `Supported: timer` plus
  `Session-Expires` without `refresher`. The value must be at least 90, the server's Min-SE (PJSIP default), or the
  server answers 422. Section 7 covers refresh.
- **Resource-Priority** can be omitted for a normal call. The server accepts only `mcptt.0` there. `mcptt.15` is
  accepted only for an emergency request (`server.cpp:2769`).
- **Bodies.** `session-type` is `private` (step 14c). The resource list has exactly one entry: the called MCPTT ID, not
  the SIP URI (step 9). The server returns 403 with warning 145 if the list is missing, has more than one entry or names
  the caller.
- **Deviation D1: the server requires `<mcptt-client-id>`.** §11.1.1.2.1.1 step 14c does not list it (only the group and
  ad hoc procedures do), but the server rejects any private INVITE whose `mcptt-client-id` differs from the client ID
  registered by poc-settings. The rejection is a bare 403 (`server.cpp:2482`). Always send it. Do not send
  `mcptt-calling-user-id` or `mcptt-request-uri`, which the participating function inserts (NOTE 2 of §10.1.1.2.1.1). If
  `mcptt-calling-user-id` is present it must equal the caller. Any `mcptt-calling-group-id` gets 403.

### 1.2 Server responses to the caller

Captured frames 256 and 272 (Via and Record-Route trimmed):

```
SIP/2.0 180 Ringing
To: <sip:mcptt@mcx.test>;tag=3bc50c8d-…
Resource-Priority: mcptt.0
Contact: <sip:mcptt@mcx.test;mcptt-session=DvKOO8DIm3-NLwLMmXPDkLH4hC2viBr1hOBGjXNZl58>;+g.3gpp.mcptt;+g.3gpp.icsi-ref="urn%3Aurn-7%3A3gpp-service.ims.icsi.mcptt";isfocus
Allow: INVITE, ACK, BYE, CANCEL, UPDATE, PRACK
Content-Length: 0

SIP/2.0 200 OK
To: <sip:mcptt@mcx.test>;tag=3bc50c8d-…            (same tag as the 180)
Resource-Priority: mcptt.0
Allow: INVITE, ACK, BYE, CANCEL, UPDATE
Supported: timer
Session-Expires: 1800;refresher=uac
Require: timer
Contact: <sip:mcptt@mcx.test;mcptt-session=DvKO…>;+g.3gpp.mcptt;+g.3gpp.icsi-ref="…mcptt";isfocus
Resource-Priority: mcptt.0                        (duplicated: deviation D7)
Content-Type: multipart/mixed;boundary=6e622228-…

--6e622228-…
Content-Type: application/sdp
Content-Length:   337

v=0
o=- 1 1 IN IP4 172.21.0.13
s=MCPTT
c=IN IP4 172.21.0.13
t=0 0
m=audio 4000 RTP/AVP 96
i=speech
a=rtpmap:96 AMR-WB/16000/1
a=fmtp:96 octet-align=0;mode-change-capability=2
a=ptime:20
a=maxptime:240
a=sendrecv
a=rtcp:4001
a=ssrc:4264912492 cname:mcptt
m=application 5000 udp MCPTT
a=fmtp:MCPTT mc_floor_ssrc=3611423858

--6e622228-…
Content-Type: application/vnd.3gpp.mcptt-info+xml
Content-Length:   345

<?xml version="1.0"?><mcpttinfo xmlns="urn:3gpp:ns:mcpttInfo:1.0"><mcptt-Params><session-type>private</session-type><mcptt-request-uri type="Normal"><mcpttURI>sip:bob.mcptt@mcx.test</mcpttURI></mcptt-request-uri><mcptt-calling-user-id type="Normal"><mcpttURI>sip:alice.mcptt@mcx.test</mcpttURI></mcptt-calling-user-id></mcptt-Params></mcpttinfo>
--6e622228-…--
```

- The server sends 180 immediately after admission, in every commencement mode (`server.cpp:2703`). The 180 already
  carries the To-tag and the session URI, which creates an early dialog. The server sends 200 only after the callee
  answers. The caller ACKs it, and the Record-Route set is `sip-core`.
- **The Contact of the 200 is the MCPTT session identity.** It is the remote target, so BYE and UPDATE use it as their
  Request-URI (§6.2.4.1 step 3).
- The 200's mcptt-info has `mcptt-request-uri` set to the called MCPTT ID and `mcptt-calling-user-id` set to the caller.
- **Failure responses relayed to the caller:**

  | Response | Cause |
  |---|---|
  | 403 with warnings 107, 125, 126, 143, 144, 145, 127 or 159 | Authorisation (`PRIVATE_CALL_TEST.md`) |
  | 480 with no Warning | Callee unregistered |
  | 480 with the callee's Warning `110 user declined the call invitation` | Callee declined |
  | 486 with warning 124 | Busy. The a1 limit is 1 private call per user (`max_calls` default 1) |
  | 488 | SDP rejected |
  | 408 | Callee did not answer within `setup_timeout_ms` (default 30 s). The callee's leg is cancelled |

- **CANCEL** from the caller while the call is ringing gets 487, and the server cancels the callee's leg.

### 1.3 Terminating side, server to client

The clauses are §11.1.1.2.1.2, §6.2.2 and §6.2.3. The server sends this INVITE from `invite_private_target`,
`server.cpp:2717`. Captured frame 262 (Via and Record-Route trimmed):

```
INVITE sip:bob@client-b:5060;transport=tcp SIP/2.0
From: sip:mcptt@mcx.test;tag=de5f7192-…          (addr-spec form: ;tag is a header parameter)
To: sip:bob@mcx.test                             (SIP public user identity, not the MCPTT ID)
Call-ID: ec3e6355-…
CSeq: 12501 INVITE
Allow: INVITE, ACK, BYE, CANCEL, UPDATE
Supported: timer
Require: timer
Session-Expires: 90
Min-SE: 90
P-Preferred-Service: urn:urn-7:3gpp-service.ims.icsi.mcptt
Accept-Contact: *;+g.3gpp.icsi-ref="urn%3Aurn-7%3A3gpp-service.ims.icsi.mcptt";+g.3gpp.mcptt;require;explicit
Answer-Mode: Auto                                (or Answer-Mode: Manual / Priv-Answer-Mode: Auto, forwarded from the caller)
Resource-Priority: mcptt.0
Contact: <sip:mcptt@mcx.test;mcptt-session=DvKO…>;isfocus;+g.3gpp.mcptt;+g.3gpp.icsi-ref="urn%3Aurn-7%3A3gpp-service.ims.icsi.mcptt"
P-Asserted-Identity: <sip:mcptt@mcx.test>        (added by Kamailio)
Content-Type: multipart/mixed;boundary=03208a66-…

SDP offer: as in 1.2, but on server ports 4002/4003/5002 and with its own a=ssrc and mc_floor_ssrc
mcptt-info: <session-type>private</session-type>, <mcptt-request-uri>=sip:bob.mcptt@mcx.test, <mcptt-calling-user-id>=sip:alice.mcptt@mcx.test
```

There is no resource-list part. The client identifies the call type from `session-type`, the caller from
`mcptt-calling-user-id`, and itself from `mcptt-request-uri`.

**Choosing the commencement mode** (§11.1.1.2.1.2 steps 9–10):

| Incoming header | Commencement |
|---|---|
| `Priv-Answer-Mode: Auto` | Automatic |
| `Answer-Mode: Auto` | Automatic, unless the local setting is manual and the client is unwilling to auto-answer (then manual, which lab test PC-06 exercises) |
| `Answer-Mode: Manual` | Manual |
| No header | Local setting |

**Automatic commencement** (§6.2.3.1.1) answers 200 at once. Captured frame 264:

```
SIP/2.0 200 OK
(Via/Record-Route/From/Call-ID/CSeq copied; To gets a tag)
Contact: <sip:bob@…>;+g.3gpp.mcptt;+g.3gpp.icsi-ref="urn%3Aurn-7%3A3gpp-service.ims.icsi.mcptt"   (step 3-4)
Require: timer                                     (step 2)
Supported: timer
Session-Expires: 90;refresher=uas                  (step 5: refresher MUST be uas; the client then refreshes, §7)
Allow: INVITE, ACK, CANCEL, BYE, UPDATE, INFO, MESSAGE, NOTIFY, OPTIONS
Content-Type: application/sdp

v=0
o=- 2031432046 1 IN IP4 172.21.0.16
s=-
c=IN IP4 172.21.0.16
t=0 0
m=audio 20344 RTP/AVP 96
i=speech
a=rtpmap:96 AMR-WB/16000/1
a=fmtp:96 octet-align=0;mode-change-capability=2
a=ptime:20
a=maxptime:240
a=rtcp:20345
a=sendrecv
m=application 20346 udp MCPTT
a=fmtp:MCPTT mc_floor_ssrc=3887874777
```

The body can be SDP alone. The server does not need an mcptt-info body in the answer.

**Manual commencement** (§6.2.3.2.1):

1. Send `180 Ringing` with `Require: timer` and the same Contact feature tags, and no SDP. Captured frame 312.
2. When the user accepts, send the 200 above.
3. To decline, send `480 Temporarily Unavailable` with the Warning
   `Warning: 399 <host> "110 user declined the call invitation"`. Captured frame 316. The server relays the 480 and its
   Warning to the caller.

The server gives up at `setup_timeout_ms`: it CANCELs the callee's leg, and the client answers 200 to the CANCEL and 487
to the INVITE.

**Busy** (§11.1.1.2.1.2 step 1): the client may answer `486 Busy Here`. The server itself refuses callees that are
already in a call, with 480 (`server.cpp:2353`).

### 1.4 Release (§6.2.5.1, §6.2.6, §11.1.3.1.1)

- **Client release.** If the call is not yet established, send CANCEL. Otherwise send BYE with the Request-URI set to the
  session URI and the Route taken from Record-Route. A private BYE needs no Reason header and no body. The server answers
  200 and BYEs the other leg. Captured frames 280 and 286.

  ```
  BYE sip:mcptt@mcx.test;mcptt-session=DvKOO8DIm3-NLwLMmXPDkLH4hC2viBr1hOBGjXNZl58 SIP/2.0
  From: <sip:alice@mcx.test>;tag=5bfe…   To: <sip:mcptt@mcx.test>;tag=3bc5…   CSeq: 2 BYE
  Route: <sip:sip-core;transport=tcp;lr;ftag=5bfe…;did=95.b4e1>
  Content-Length: 0
  ```

- **Server release.** The server sends a bare BYE (captured frame 373) when the other party leaves, when the call timer
  or inactivity timer fires (§7), or when authorisation is withdrawn (rechecked every 2 s). The client answers 200 and
  stops media and floor.
- If the client holds the floor, it should send a Floor Release before the BYE (24.380 "call release"). The server
  releases the floor on BYE anyway (`server.cpp:4259`).

---

## 2. Prearranged group call (24.379 §10.1.1.2.1)

**Preconditions.** The client must be affiliated to the group, or the server answers 403 with warning
`120 user is not affiliated to this group` (`server.cpp:3961`). The caller must be a member (otherwise 403 with warning
121). Enough members must be affiliated, `minimum_participants` = 2, or the server answers 480 with warning 112. The
server handles the INVITE at `server.cpp:3882-4250`.

The INVITE carries the same headers as section 1.1, with no Answer-Mode or Priv-Answer-Mode and no resource list. The
mcptt-info body is (§10.1.1.2.1.1 step 14; captured frame 379):

```xml
<mcpttinfo xmlns="urn:3gpp:ns:mcpttInfo:1.0"><mcptt-Params>
  <session-type>prearranged</session-type>
  <mcptt-request-uri type="Normal"><mcpttURI>sip:operations@mcx.test</mcpttURI></mcptt-request-uri>
  <mcptt-client-id type="Normal"><mcpttString>urn:uuid:…</mcpttString></mcptt-client-id>
</mcptt-Params></mcpttinfo>
```

If the client ID is missing or wrong, the server answers 403 (`server.cpp:3938`). A group of type chat returns 403 with
warning 118.

**Server to the caller.** The server sends 180 at once, then 200 when the first invited member answers. In a1,
`call_policy.setup_timeout_ms` is 3000. The 200 has the same header shape as in section 1.2, and its mcptt-info is
(captured frame 407):

```xml
<session-type>prearranged</session-type><mcptt-request-uri>sip:operations@mcx.test</mcptt-request-uri>
<mcptt-calling-user-id>sip:alice.mcptt@mcx.test</mcptt-calling-user-id>
<mcptt-calling-group-id>sip:operations@mcx.test</mcptt-calling-group-id><mcptt-client-id>urn:uuid:…</mcptt-client-id>
```

**Server to the other members.** The server sends each affiliated member an INVITE with `Answer-Mode: Auto` (taken from
group policy `answer_mode`, `server.cpp:1502`), `Resource-Priority: mcptt.0` and an isfocus Contact. Captured frame 392:

```xml
<session-type>prearranged</session-type><mcptt-request-uri>sip:bob.mcptt@mcx.test</mcptt-request-uri>
<mcptt-calling-user-id>sip:alice.mcptt@mcx.test</mcptt-calling-user-id>
<mcptt-calling-group-id>sip:operations@mcx.test</mcptt-calling-group-id>
```

The server's SDP offer includes the floor priority: `a=fmtp:MCPTT mc_floor_ssrc=915615479;mc_priority=0`.

**Deviation D5.** The member's SDP answer must echo `mc_priority` exactly as offered. TS 24.380 §14 lets the answer carry
a value up to the offered one, but any other value makes the server send BYE ("floor priority answer differs from
offer", `server.cpp:4796`). Answer with `mc_floor_ssrc=<own>;mc_priority=0`.

The member answers under §10.1.1.2.1.2 and §6.2.3.1.2 (the same as §6.2.3.1.1: 200 with `Session-Expires: 90;refresher=uas`
and `Require: timer`). For manual commencement a member may send 183, then 200, or 480 with warning 110 to decline.

**Leave or release.** The client sends BYE to the session URI (§6.2.4.1). In a1 `end_when_initiator_leaves` is true, so
the initiator's BYE makes the server BYE everyone. When a non-initiator leaves, the call continues while at least 2
participants remain.

**Optional.** The client may SUBSCRIBE to the conference event package (Event `conference`) at the session URI
(§10.1.3.1). The call works without it.

---

## 3. Ad hoc group call (24.379 §17.2.2.1)

The INVITE carries the same headers as section 1.1, with no Answer-Mode. It carries mcptt-info and a resource list
(§17.2.2.1.1 steps 10–11; captured frame 443):

```xml
<mcpttinfo xmlns="urn:3gpp:ns:mcpttInfo:1.0"><mcptt-Params><session-type>adhoc</session-type>
  <mcptt-client-id type="Normal"><mcpttString>urn:uuid:…</mcpttString></mcptt-client-id></mcptt-Params></mcpttinfo>

<resource-lists xmlns="urn:ietf:params:xml:ns:resource-lists"><list>
  <entry uri="sip:bob.mcptt@mcx.test"/><entry uri="sip:client-c.mcptt@mcx.test"/></list></resource-lists>
```

No `mcptt-request-uri` is sent: the server generates the ad hoc group identity. The server requires
`mcptt-client-id` (`server.cpp:2990`).

The server checks, in order:

- The caller's `adhoc_call.allow`. Without it: 403 with warning 185.
- That the service is enabled. Otherwise: 403 with warning 186.
- That the list is not empty after removing the caller. Otherwise: 403 with warning 187.
- That `max_participants` is not exceeded. The a1 limit is 3. Otherwise: 403 with warning 189.

**Server to the caller.** The server sends 180, then 200 after the first invitee answers. If nobody answers it sends 408
or 480. Captured frame 471 shows the 200's mcptt-info:

```xml
<session-type>adhoc</session-type><mcptt-request-uri>sip:alice.mcptt@mcx.test</mcptt-request-uri>
<mcptt-calling-user-id>sip:alice.mcptt@mcx.test</mcptt-calling-user-id>
<mcptt-calling-group-id>sip:adhoc-n0irMQQ7vgtcc7G6cMBaMA1rYnjpEYodDGOt02lWAzA@mcx.test</mcptt-calling-group-id>
```

**Deviation D8.** In this 200, `mcptt-request-uri` is the caller's own ID. The ad hoc group identity is in
`mcptt-calling-group-id`, which is where §17.2.2.1.1 says the client reads it.

**Invited members** receive the same INVITE shape as in section 2, with `session-type` `adhoc`, `mcptt-request-uri` set
to the invitee, `mcptt-calling-user-id` set to the initiator, `mcptt-calling-group-id` set to `sip:adhoc-…@mcx.test`,
`Answer-Mode: Auto`, and no `mc_priority` in the SDP. Captured frame 457. They answer as in section 1.3.

**Release.** The initiator's BYE should carry `Reason: SIP;cause=200;text="User requested release"`
(§17.2.3.1.1 step 1). The call ends when the initiator leaves or when `maximum_duration_ms` (TNG3) expires. **In a1 that
timer is 30000 ms, so an ad hoc test must finish within 30 s.**

---

## 4. SDP offer/answer (24.379 §6.2.1 and §6.2.2; 24.380 §12 and §14)

**What the server accepts** (`parse_sdp`, `protocol.cpp:1030`). Anything else gets 488:

- IPv4 only: `c=IN IP4 <addr>`, at session or media level.
- `m=audio <port> RTP/AVP <pt>` with exactly one payload type. `RTP/SAVP` is only for protected groups. The payload type
  must be 96–127. Do not add telephone-event or a second codec, because four tokens are required.
- `a=rtpmap:<pt> AMR-WB/16000/1` (or `AMR-WB/16000`).
- `a=fmtp:<pt>` with `octet-align=0|1` and optionally `mode-change-capability=2`. `interleaving`, `crc` and
  `robust-sorting` must be absent or 0. The server ignores `mode-set`, so any mode 0–8 may be sent and must be decodable.
- `a=rtcp:<port>` with one token. If it is absent, the RTCP port is taken as audio+1. It must differ from the floor port.
- `a=sendrecv` (the default).
- Exactly one `m=application <port> udp MCPTT`, with `a=fmtp:MCPTT mc_floor_ssrc=<non-zero u32>` required.
- No other m-lines. `mc_no_floor_ctrl` is rejected.
- **`mc_implicit_request` gets 488** outside ambient listening (`validate_remote`, `server.cpp:1410`). This is
  **deviation D2**: §6.4 allows an implicit floor request. Never offer it. Request the floor explicitly after the 200
  (section 5.5).
- **`mc_queueing` gets 488** unless the server has `mcptt.floor_queue` set to true. a1 has it false. This is
  **deviation D3**: an answerer should normally just omit the parameter.
- **The c= address must equal** the IPv4 address that the server resolves for the MCPTT ID in `mcx-server.json`
  `peers` (for example `"sip:alice.mcptt@mcx.test": "client-a"`). The floor address must equal the audio address.
  Otherwise the server answers 488 ("SDP address is not the authenticated client"). Advertise the container or device
  address. **NAT is not supported.**

**What the server offers or answers** (`make_sdp`, `protocol.cpp:916`; `local_media`, `runtime.cpp:108`):

- Payload type and `octet-align` mirror the client's offer (`server.cpp:2684`). On legs the server originates, it offers
  PT 96 with `octet-align=0`.
- `a=ptime:20`, `a=maxptime:240` and `a=rtcp:<audio+1>`.
- `a=ssrc:<random> cname:mcptt`. This is informational only. Talk-burst media carries the talker's granted SSRC
  (section 6).
- `m=application <5000+n> udp MCPTT`, with `mc_floor_ssrc` and, in a group call, `;mc_priority=0`.
- Server ports are 4000+2k (RTP), 4001+2k (RTCP, unused) and 5000+2k (floor), one set per leg.

**SSRC semantics** (§6.2.1 step 3b and NOTE 3; §6.2.2 step 4b). The client's `mc_floor_ssrc` is the SSRC the server puts
in floor messages it sends to the client. The server's `mc_floor_ssrc` is the SSRC the client must put in every floor
message it sends. The two must differ, or the server answers 488 (`server.cpp:2678`).

**The client listens on two UDP ports, both symmetric** (send from the same socket you receive on):

1. The audio port, for RTP in both directions.
2. The floor port, for RTCP APP in both directions.

An RTCP port (audio+1) must be advertised but can stay silent. The server never sends RTCP SR/RR and never listens on
4001.

**The server does not latch.** It sends to the SDP address and port. It silently drops RTP whose source is not exactly
`<c=>:<m=audio port>`, and floor packets whose source is not exactly `<c=>:<m=application port>` (`server.cpp:4400`,
`:4503`). This is deviation D6 in practice: it rules out NAT and ephemeral source ports.

**Answer recipe (terminating).** Accept PT 96 with the same `octet-align` as offered (0). An asymmetric `octet-align` is
tolerated because the server repacks, but it violates RFC 4867. Use the client's own ports and `mc_floor_ssrc`. Copy
`mc_priority` exactly if it was offered. Never add `mc_queueing` or `mc_implicit_request` that the server did not offer.
The server BYEs the leg if the answer adds `mc_queueing` (`server.cpp:4798`).

---

## 5. Floor control (TS 24.380, on-network floor participant)

### 5.1 Packet format (24.380 §8.1 and §8.2, unverified; `floor.cpp:172` encode, `:219` decode)

```
 0                   1                   2                   3
|V=2|P|  subtype  |   PT=204      |          length (words-1)     |
|                   SSRC of sender (see 4, "SSRC semantics")      |
|        'M'    |      'C'      |      'P'      |      'T'      |
| field ID (1)  | field len (1) |  value … padded to 32 bits ...  |
```

- The 5-bit subtype is `A tttt`, where A (0x10) means "acknowledgement required". Byte 0 is `0x80 | subtype`.
- P must be 0. The packet length must be a multiple of 4 and match the length field.
- **Send each floor message as a standalone APP packet**, not in a compound RTCP packet. The server rejects anything
  else (`floor.cpp:219-235`).
- Field IDs 192 and above use a 2-octet length. None are used here.
- The server skips unknown field IDs, so the client should skip them too.

**Message types**

| Type | Name | Direction here | A bit |
|---:|---|---|---|
| 0 | Floor Request | client to server | never (a set bit is rejected) |
| 1 | Floor Granted | server to client | set (0x91) |
| 2 | Floor Taken | server to client | set (0x92) |
| 3 | Floor Deny | server to client | set (0x93) |
| 4 | Floor Release | client to server | optional (0x94 makes the server ACK) |
| 5 | Floor Idle | server to client | set (0x95) |
| 6 | Floor Revoke | server to client | never (0x86) |
| 8 | Floor Queue Position Request | client to server | never; only with queueing |
| 9 | Floor Queue Position Info | server to client | set (0x99); only with queueing |
| 10 | Floor Ack | both | never (0x8A) |

The server sets the A bit whenever `mcptt.floor_ack` is true, which is the default (`server.cpp:4365`).

**Fields used** (24.380 §8.2.3.x, unverified). All values are big-endian.

| ID | Field | Len | Encoding |
|---:|---|---:|---|
| 0 | Floor Priority | 2 | priority (0–255; the server caps it at 99 and the negotiated value), then spare 00 |
| 1 | Duration | 2 | seconds; the server sends `max_talk_ms/1000` = 30 |
| 2 | Reject Cause | 2+n | u16 cause, then optional text (the server sends none) |
| 3 | Queue Info | 2 | position (254 means not queued), priority |
| 4 | Granted Party's Identity | n | UTF-8 MCPTT ID, for example `sip:alice.mcptt@mcx.test` |
| 5 | Permission to Request the Floor | 2 | 0 or 1; 0 in broadcast and ambient listening calls |
| 6 | User ID | n | off-network only. If the client sends it, it must equal its own MCPTT ID. Omit it |
| 8 | Message Sequence Number | 2 | u16, per leg, shared by Idle and Taken, starting at 1 |
| 10 | Source | 2 | 0 = floor participant; the server sends 2 |
| 12 | Message Type | 2 | the acknowledged type without the A bit (for example 0x05 for Idle), then spare 00 |
| 13 | Floor Indicator | 2 | bitmap: A 0x8000 normal, B 0x4000 broadcast, C 0x2000 system, D 0x1000 emergency, E 0x0800 imminent peril, F 0x0400 queueing supported, G 0x0200 dual floor, H 0x0100 temporary group, I 0x0080 multi-talker. Bits 0x007F are reserved and rejected |
| 14 | SSRC | 6 | u32 SSRC followed by 00 00; the length includes the spare octets (`floor.cpp:316`) |
| 19 | Location | 1+ | the server sends type 0x00, "not provided" |

**Reject and revoke causes** (24.380 §8.2.3.4, unverified):

| Cause | Meaning |
|---:|---|
| 1 | another MCPTT client has permission |
| 2 | internal floor control server error; in a Revoke, media burst too long (T2) |
| 4 | Revoke: pre-empted |
| 5 | receive only |
| 7 | queue full |
| 255 | other; the server uses it for unauthorised requests |

### 5.2 What the server sends and expects (`send_notice` `server.cpp:4302`, `floor_packet` `:4398`)

The server accepts a floor packet only if all of these hold:

- The leg is active, which means after ACK or 200 on that dialog. Earlier requests are dropped silently.
- The source is the SDP floor address and port.
- The SSRC is the server's `mc_floor_ssrc`.
- The packet passes `validate_on_network_floor`: no duplicate fields with ID 14 or lower.

**The Floor Ack from the client** must carry exactly one Source field (value 0) and one Message Type field. The server
matches the ACK to its pending message by message type only.

**Retransmission.** If a message with the A bit is not ACKed, the server resends it every 1 s, three transmissions in
total, then logs `floor_ack_timeout` and gives up. The call continues. ACK every copy, including duplicates.

**Server floor timers** (`floor_options`, `server.cpp:1129`; FloorArbitrator in `floor.cpp`):

| Timer | Value | Server action |
|---|---|---|
| T1 (end of RTP media) | 4 s | Measured from the grant and from each RTP packet. On expiry the floor goes Idle for everyone. **Start RTP within 4 s of the grant and never pause for 4 s mid-burst** |
| T2 (stop talking) | 30 s from the first RTP packet | Floor Revoke with cause 2, then a Revoke every 1 s (T8) for 3 s of grace (T3), then Idle |
| Queued grant (T20) | 1 s, 3 transmissions | Resends the grant. Only applies with queueing |
| Floor Idle retransmission (T7) | disabled | Nothing |

Other server behaviour:

- A **Floor Request** while another participant holds the floor gets a Deny with cause 1 when queueing is off (the a1
  setting).
- A Floor Request from the current holder gets a fresh Granted, or a Revoke if a revoke is pending.
- A **Floor Release** from a participant that does not hold the floor gets that leg's current Taken or Idle.
- A Floor Release from the holder gets Idle for everyone, the releaser included. If the Release has the A bit set, the
  server first sends an ACK with Source 2.
- **Initial state:** as soon as the leg becomes active, the server sends Floor Idle (sequence 1). If someone is already
  talking it sends Floor Taken instead (`server.cpp:4244-4252`).

### 5.3 Byte-level examples

These come from the capture. `Sc` is the client's `mc_floor_ssrc` (0xBCB7D9C1 in the capture) and `Ss` is the server's
(0xD741F472).

```
Server→client  Floor Idle (seq 1), A bit:
95 CC 00 04  BC B7 D9 C1  4D 43 50 54  0D 02 80 00  08 02 00 01
Client→server  Floor Ack for Idle:
8A CC 00 04  D7 41 F4 72  4D 43 50 54  0A 02 00 00  0C 02 05 00
Client→server  Floor Request (Floor Indicator optional; Floor Priority optional):
80 CC 00 03  D7 41 F4 72  4D 43 50 54  0D 02 80 00
80 CC 00 04  D7 41 F4 72  4D 43 50 54  00 02 00 00  0D 02 80 00          (with priority 0)
Server→client  Floor Granted: Duration 30 s, SSRC 0x93A1937F to use for RTP, priority 0:
91 CC 00 07  BC B7 D9 C1  4D 43 50 54  0D 02 80 00  01 02 00 1E  0E 06 93 A1 93 7F 00 00  00 02 00 00
Client→server  Ack for Granted:  8A CC 00 04  D7 41 F4 72  4D 43 50 54  0A 02 00 00  0C 02 01 00
Server→other member  Floor Taken (seq 2, granted party, permission=1, location n/p, talker SSRC):
92 CC 00 0F  <Sc'>  4D 43 50 54  0D 02 80 00  08 02 00 02
             04 18 73 69 70 3A 61 6C 69 63 65 2E 6D 63 70 74 74 40 6D 63 78 2E 74 65 73 74 00 00   ("sip:alice.mcptt@mcx.test"+pad)
             05 02 00 01  13 01 00 00  0E 06 93 A1 93 7F 00 00
Member→server  Ack for Taken:  8A CC 00 04  <Ss'>  4D 43 50 54  0A 02 00 00  0C 02 02 00
Client→server  Floor Release (no A bit / with A bit):
84 CC 00 03  D7 41 F4 72  4D 43 50 54  0D 02 80 00
94 CC 00 03  D7 41 F4 72  4D 43 50 54  0D 02 80 00
   → server Ack: 8A CC 00 04  BC B7 D9 C1  4D 43 50 54  0A 02 00 02  0C 02 04 00
Server→client  Floor Deny cause 1 (private-lab capture):
93 CC 00 04  <Sc>  4D 43 50 54  0D 02 80 00  02 02 00 01      → Ack with 0C 02 03 00
Server→client  Floor Revoke cause 2 (no A bit, constructed from send_notice):
86 CC 00 04  <Sc>  4D 43 50 54  0D 02 80 00  02 02 00 02
```

### 5.4 Floor participant state machine (24.380 §6.2.4, unverified)

States: `Start-stop`, `U: has no permission`, `U: pending Request`, `U: has permission`, `U: pending Release`,
`U: queued` (queueing only) and `Releasing`. The core transitions:

| State | Event | Action, then next state |
|---|---|---|
| Start-stop | Call established (200 or ACK) | has no permission |
| has no permission | Idle or Taken arrives | ACK if the A bit is set; discard stale ones by sequence number; show the talker from field 4; accept RTP from the SSRC in field 14 |
| has no permission | PTT pressed | Send Floor Request, start T101, C101 = 1, go to pending Request |
| pending Request | T101 expires and C101 < limit | Resend the request, C101++ |
| pending Request | T101 expires and C101 = limit | Give up, go to has no permission, tell the user |
| pending Request | Floor Granted | ACK; take the SSRC from field 14; start sending RTP within 4 s; go to has permission |
| pending Request | Floor Deny | ACK; tell the user the cause; go to has no permission |
| pending Request | Floor Taken | ACK; go to has no permission (`floor.cpp:604`) |
| pending Request | Queue Position Info (position not 254) | Go to queued |
| has permission | PTT released | Stop RTP; send Floor Release; start T100, C100 = 1; go to pending Release |
| has permission | Floor Revoke | Stop RTP immediately; send Floor Release; go to pending Release |
| has permission | Talk time reaches the granted Duration | Stop RTP; send Floor Release |
| has permission | Granted retransmission | ACK it; keep the same SSRC |
| pending Release | T100 expires | Resend the Release until C100 reaches its limit |
| pending Release | Floor Idle or Taken | ACK; go to has no permission |
| queued | Granted | Within T132, the user must confirm before RTP is sent, otherwise send Release. Not used in a1 |
| any | BYE or call release | Stop RTP and floor; go to Start-stop |

**Timers and counters.** TS 24.380 Annex F lets them be provisioned through the TS 24.483 MCPTT configuration. Use the
following values, which are what the OpenMCX participant model uses (`include/mcx/floor.hpp:125-133`) and what fits the
server's 1 s retransmission cadence:

| Timer | Value |
|---|---|
| T101 (Floor Request) | 1 s, C101 = 3 |
| T100 (Floor Release) | 1 s, C100 = 3 |
| T103 (end of RTP media, receive side) | 4 s |
| T104 (Queue Position Request) | 1 s, C104 = 3 |
| T132 (queued grant user action) | 2 s |

Check the Annex F defaults when TS 24.380 is available.

### 5.5 Implicit floor request

§6.4 defines the implicit floor request: `mc_implicit_request` in the INVITE's SDP. **The server rejects it with 488
except in ambient listening calls.** So the originator requests the floor explicitly with a Floor Request, as soon as
the dialog is confirmed (200 received and ACK sent), with no need to wait for the first Idle. If the request arrives
before the server marks the leg active, the server drops it and T101 resends it.

---

## 6. RTP and AMR-WB (RFC 3550, RFC 4867; 24.379 §6.2.1)

- **Payload type** is the one negotiated in the client's own SDP (96). The server rejects RTP whose PT differs from the
  PT in the sender's SDP (`server.cpp:4509`).
- **Transmit SSRC** is the value of the SSRC field (ID 14) in Floor Granted (§6.2.1 NOTE 2). The server picks a new
  random SSRC for every grant and **drops RTP with any other SSRC** (`server.cpp:4509`). The `a=ssrc` values in SDP are
  not used for media.
- **RTP header.** V=2, with no padding, extension or CSRC. Set M=1 on the first packet of each talk burst. The sequence
  number increments by 1. The timestamp uses a 16 kHz clock and advances by 320 for each 20 ms frame. Capture example of
  a first packet:
  `80 E0 61 77 3C DE 2F 3C 93 A1 93 7F | F1 5C D1 A8 …` (M=1, PT 96, seq 24951, ts 1021194044, SSRC 0x93A1937F).
- **Payload.**
  - One AMR-WB frame per packet, every 20 ms. **Compound payloads (F=1) are rejected** even though `maxptime:240` is
    advertised (deviation D4, `media.cpp:327`).
  - Bandwidth-efficient when `octet-align=0`: CMR=15 (4 bits), F=0, FT (4 bits), Q=1, the speech bits, then zero padding
    to the byte.
  - Octet-aligned when `octet-align=1`: CMR byte 0xF0, ToC byte `0 FT(4) Q 00`, speech bytes.
  - FT 0–8 is speech, 9 is SID, 14/15 are lost or no-data. Other values are rejected.
  - The capture used FT 2 (12.65 kbit/s): 33-byte payload, 45-byte RTP packet.
  - Avoid DTX pauses of 4 s or more (T1).
- **Relay.** The server forwards media only from the floor holder to every other active leg. It keeps M, sequence,
  timestamp and SSRC. It rewrites the PT to each receiver's PT and re-packs the payload into each receiver's `octet-align`
  (`server.cpp:4526-4535`). Re-packing sets CMR to 15, zeroes the padding bits, and strips RTP padding, extension and
  CSRC. **The payload arrives byte-identical when the sender uses CMR=15 with zero padding and both legs use the same
  `octet-align`.** The lab evidence shows identical payloads for 150–200-frame bursts. Hash the payload bytes, not the
  RTP header, because the PT can differ.
- **Receiving.** The talker's SSRC changes with every burst. The Taken field 14 announces it; the client's own grant
  announces its own. Use a jitter buffer keyed by the current SSRC. T103 is 4 s.
- **RTCP.** None is needed for media, and floor control uses the floor port. Client RTCP SR/RR sent to the server's
  audio+1 port is ignored (ICMP unreachable).

---

## 7. Keeping a call up: session timers and server call timers

**SIP session timer** (RFC 4028; the server uses PJSIP with `session_expires` 90 and Min-SE 90, `sip.cpp:1364`,
`:1724-1732`):

| Leg | Negotiated | Client obligation |
|---|---|---|
| Originating (client is UAC) | Client offers `Session-Expires: 1800` without refresher. The server answers `1800;refresher=uac` with `Require: timer` | Refresh before half the interval (900 s) |
| Terminating (server is UAC) | Server offers `Session-Expires: 90`, `Require: timer`. The client answers `90;refresher=uas` (§6.2.3.1.1 step 5) | **The client is the refresher every 45 s.** PJSIP BYEs a non-refreshed session at about SE − min(32, SE/3) = 60 s |

**Refresh with UPDATE and no body.** Captured frames 656–660: `UPDATE <session URI>` with the Route from Record-Route,
Contact with the feature tags, `Supported: timer` and `Session-Expires: 90;refresher=uac`. The server answers
`200 OK` with `Session-Expires: 90;refresher=uac`, `Require: timer` and `Supported: 100rel, timer`, which keeps the client
as refresher.

**Deviation D9: do not refresh with re-INVITE.** The server passes in-dialog re-INVITEs to its call logic, which treats
them as call modifications. A private-call refresh re-INVITE gets 488 "100 function not allowed due to private call
re-INVITE", and a group re-INVITE without mcptt-info gets 403 (`server.cpp:2794`, `sip.cpp:998`).

The client must also answer UPDATE or re-INVITE from the server with 200, including `Session-Expires` and
`Require: timer`. This happens when the server is the refresher, or for emergency changes in private calls.

**Server call timers.** These end the call with BYE regardless of SIP refreshes (`server.cpp:4605-4615`):

| Timer | Value | Effect |
|---|---|---|
| Inactivity (`inactivity_timeout_ms`, default **60 s**) | 60 s | If no RTP is relayed in the session for 60 s, all legs are released. Floor messages do not count. **A call held for minutes needs a talk burst at least every 60 s**, or a lab config change |
| Call duration (`maximum_duration_ms`) | Private and group: default 1 h. Ad hoc in a1: **30 s** | Call released |
| Setup (`setup_timeout_ms`) | Private 30 s (408). Group 3 s in a1. Ad hoc 5 s in a1 | Setup fails |
| Registration | Registration expiry | A leg whose registration has lapsed stops receiving media and floor messages (`eligible`, `server.cpp:4390`). Keep REGISTER refreshed (phase 1) |

---

## 8. Deviations and gotchas (summary)

| # | Server behaviour | Spec | Client action |
|---|---|---|---|
| D1 | Requires `mcptt-client-id` in private and first-to-answer INVITEs; bare 403 otherwise | §11.1.1.2.1.1 step 14 does not require it | Always include it |
| D2 | `mc_implicit_request` gets 488 | §6.4 allows it | Explicit Floor Request |
| D3 | `mc_queueing` gets 488 when queueing is disabled | Answerer would omit it | Offer queueing only if the lab enables `floor_queue` |
| D4 | Exactly one codec and no other m-lines; IPv4 only; one AMR-WB frame per packet despite `maxptime:240` | 24.229 and RFC 4867 allow more | Single-codec SDP, one frame per packet |
| D5 | Answer `mc_priority` must equal the offer; BYE otherwise | 24.380 §14 allows a value up to the offer | Echo it |
| D6 | No latching; strict source match; SDP c= must be the peers-table address | NAT traversal (§6.2.1 NOTE 1) | Symmetric sockets on the advertised IP |
| D7 | Duplicate `Resource-Priority` in the 200 to the caller | Single header | Tolerant parser |
| D8 | Ad hoc 200 `mcptt-request-uri` is the caller | §17.2.2.1.1: read the group from `mcptt-calling-group-id` | Use `mcptt-calling-group-id` |
| D9 | re-INVITE refresh rejected | RFC 4028 allows re-INVITE | Refresh with UPDATE |
| D10 | `Priv-Answer-Mode: Manual` from the client gets 400 | RFC 5373 value | Send only Auto |
| D11 | Floor Ack Source is 2 (controlling) on every leg | Participating function would use 1 | Accept any Source |
| — | Floor Request before the leg is active is dropped | — | Rely on T101 retransmission |
| — | Server's own multipart and From/To forms (`sip:x;tag=` without angle brackets, part `Content-Length`, leading CRLF) | RFC 3261 and RFC 2046 | Tolerant parsers |

**a1 fixture limits** (`lab/run/a1/fixture/generated`):

- Alice lacks `force_auto_answer`, so the forced-commencement part of S3 gets 403 with warning 143 until `site.json`
  grants it.
- client-c and client-d have no private-call permission. Bob can call only alice, and alice can call only bob.
- `floor_queue` is false, so the queueing part of S4 needs a fixture change.
- The ad hoc maximum is 3 participants and the ad hoc call timer is 30 s.
- The group setup timer is 3 s, so manual-answer group members have 3 s to answer.

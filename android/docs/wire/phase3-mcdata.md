# Phase 3 wire contract: MCData short data service over SIP MESSAGE

Scope: on-network standalone SDS over the signalling control plane (one-to-one and
prearranged group) with disposition notifications, as the OpenMCX MCData server
accepts it. This document does not cover the media plane (MSRP), SDS sessions, FD,
ad hoc groups, emergency alerts or payload protection.

Baseline: TS 24.282 **V19.7.0** (ETSI TS 124 282), TS 23.282 V19.10.0 (§7.4.2.1,
§7.4.2.2), MONP coding rules of TS 24.379 Annex I. Server references (openmcx-server, from an earlier layout of the sources) are
`src/data_server.cpp` (**DS**), `src/mcdata.cpp` (**MC**),
`include/mcx/mcdata.hpp` (**MH**), `src/protocol.cpp` (**PR**) and the lab
Kamailio config `lab/run/a1/fixture/generated/kamailio.cfg` (**KC**).

Prerequisites from earlier phases: the client is SIP-registered, MCData service
authorisation has succeeded (PUBLISH `poc-settings` to the MCData PSI, TS 24.282
§7.2.2), and for group SDS the user is MCData-affiliated to the group (§8.2.2). If
the binding is missing or expired, every MESSAGE gets `404` with Warning `141`.

---

## 0. Lab constants (fixture `lab/run/a1/fixture/generated/`)

| Item | Value | Source |
|---|---|---|
| MCData PSI (Request-URI of every uplink MESSAGE) | `sip:mcdata@mcx.test` | mcdata-server.json `mcdata.service_uri`; client-a.json `mcdata.service_uri` |
| User public identity (From, IMPU) | `sip:alice@mcx.test` | client-a.json `sip.identity` |
| MCData ID | `sip:alice.mcdata@mcx.test` (bob: `sip:bob.mcdata@mcx.test`) | client-a.json `mcdata.user_id`, site.json |
| MCData client ID | `urn:uuid:8034b521-39ae-47bd-b7bf-aaa7a6a316ef` (the value sent at MCData service authorisation) | site.json `client_id` |
| Group | `sip:operations@mcx.test` | client-a.json `mcdata.group_id` |
| `max_payload_size_sds_cplane_bytes` | 1000 | mcdata-server.json |
| `max_data_size_sds_bytes` (one-to-one SDS) | 500 | mcdata-server.json |
| alice `max_data_1to1` | 300; one-to-one targets only bob and client-c | site.json |
| bob incoming one-to-one | only from alice | site.json |
| Group `operations` | 400 bytes per SDS, 800 per request | tests/mcdata/MCDATA_SDS_TEST.md |
| TDP1 / TDC1 (server) | 2 s, max 2 re-deliveries / 3 s, aggregation on | mcdata-server.json |
| TDU1 (client) | lab clients use 3000 ms; spec default 120 ms | client-a.json `tdu1_ms`; TS 24.282 Table F.2.3-1 |

The client cannot learn any size limit from the network in this lab: the CMS does
not serve the MCData service configuration document (TS 24.484 is not supplied). Put
`max_payload_size_sds_cplane_bytes` in client configuration (1000 in the lab) and
treat the server's 403 Warnings as authoritative.

---

## 1. Verbatim strings

| Kind | String |
|---|---|
| MIME, MCData info XML | `application/vnd.3gpp.mcdata-info+xml` (TS 24.282 Annex D.1.4) |
| MIME, signalling TLV | `application/vnd.3gpp.mcdata-signalling` (Annex E.1, encoding "binary") |
| MIME, payload TLV | `application/vnd.3gpp.mcdata-payload` (Annex E.2, binary) |
| MIME, target list | `application/resource-lists+xml` (IETF RFC 4826 / RFC 5366) |
| MIME, container | `multipart/mixed; boundary=<token>` (§6.4) |
| XML namespace, mcdata-info | `urn:3gpp:ns:mcdataInfo:1.0` (Annex D.1.2) |
| XML namespace, resource lists | `urn:ietf:params:xml:ns:resource-lists` |
| SDS ICSI | `urn:urn-7:3gpp-service.ims.icsi.mcdata.sds` |
| SDS ICSI, percent-encoded in `g.3gpp.icsi-ref` | `urn%3Aurn-7%3A3gpp-service.ims.icsi.mcdata.sds` (upper-case `%3A`) |
| MCData ICSI (authorisation and affiliation only) | `urn:urn-7:3gpp-service.ims.icsi.mcdata` |
| SDS media feature tag | `g.3gpp.mcdata.sds` |
| request-type values | `one-to-one-sds`, `group-sds` (also `ad-hoc-group-sds`, not in scope) |
| Accept-Contact 1 | `Accept-Contact: *;+g.3gpp.mcdata.sds;require;explicit` |
| Accept-Contact 2 | `Accept-Contact: *;+g.3gpp.icsi-ref="urn%3Aurn-7%3A3gpp-service.ims.icsi.mcdata.sds";require;explicit` |
| P-Preferred-Service | `P-Preferred-Service: urn:urn-7:3gpp-service.ims.icsi.mcdata.sds` |
| Downlink P-Asserted-Service | `P-Asserted-Service: urn:urn-7:3gpp-service.ims.icsi.mcdata.sds` |
| Warning format sent by the server | `Warning: 399 mcdata "<3-digit code> <text>"` (DS:612-619; TS 24.282 §4.9) |

The server matches the ICSI **only in its percent-encoded form** inside an
Accept-Contact (or compact `a:`) header containing `g.3gpp.icsi-ref`, by a
case-sensitive substring search for `urn%3Aurn-7%3A3gpp-service.ims.icsi.mcdata.sds`
(DS:602-611, `url_encode` in `src/http.cpp:191` emits upper-case hex). A lower-case
`%3a` or an unencoded ICSI is rejected with a bare `403` and no Warning (DS:1933).

---

## 2. Binary encoding (TS 24.282 clause 15, MONP rules of TS 24.379 Annex I)

General (§15.2.1): big-endian (MSB first); an IE appears at most once; spare bits are
sent as 0; the receiver **discards** a message that has an IE set to a reserved value.
IE formats: **V** (value only, fixed position), **TV** (1-octet IEI + value, or
half-octet IEI in bits 8-5 with value in bits 4-1), **TLV-E** (1-octet IEI, 2-octet
big-endian length, value).

### 2.1 Message type octet (§15.2.2, Table 15.2.2-1)

Bits 6-1 carry the type; bit 7 = protected (TS 33.180), bit 8 = authenticated. Always
send bits 8-7 = 00: the server rejects any message with them set (MC:137-139, 268-272).

| Value | Message |
|---|---|
| `0x01` | SDS SIGNALLING PAYLOAD |
| `0x03` | DATA PAYLOAD |
| `0x05` | SDS NOTIFICATION |
| others (`0x02` FD SIGNALLING, `0x06` FD NOTIFICATION, `0x09` FD NETWORK NOTIFICATION, `0x0A`-`0x0D`, `0x11`-`0x14`) | out of scope; all other values reserved |

### 2.2 SDS SIGNALLING PAYLOAD (§15.1.2, Table 15.1.2.1-1)

| Order | IEI | IE | Format | Length | Encoding |
|---|---|---|---|---|---|
| 1 | - | Message type | V | 1 | `0x01` |
| 2 | - | Date and time (§15.2.8) | V | 5 | unsigned 40-bit big-endian, UTC seconds since 1970-01-01, no leap seconds |
| 3 | - | Conversation ID (§15.2.9) | V | 16 | UUID (RFC 9562), the 16 raw octets in network order |
| 4 | - | Message ID (§15.2.10) | V | 16 | UUID, raw octets |
| opt | `0x21` | InReplyTo message ID (§15.2.11) | TV | 17 | `21` + 16-octet UUID |
| opt | `0x22` | Application ID (§15.2.7) | TV | 2 | `22` + 1 octet (value 1 = BROADBAND CALLOUT; others by organisation) |
| opt | `0x8-` | SDS disposition request type (§15.2.3) | TV (half-octet IEI) | 1 | `0x81` DELIVERY, `0x82` READ, `0x83` DELIVERY AND READ; `0x80`, `0x84`-`0x8F` reserved |
| opt | `0x7D` | Extended application ID (§15.2.24) | TLV-E | 4-x | value = content type (`01` TEXT, `02` URI) + data |
| opt | `0x7E` | User location (§15.2.25) | TLV-E | 4-x | |
| opt | `0x51` | Sender MCData user ID (§15.2.15) | TLV-E | 4-x | UTF-8 MCData ID |
| opt | `0x53` | Application metadata container (§15.2.28) | TLV-E | 4-x | |

Send optional IEs in table order. The server decodes them in any order but rejects
any other IEI, a duplicate IE and a reserved disposition value with `400` (MC:298-327,
DS:4015-4018). Omit the disposition IE when no notification is wanted.

### 2.3 DATA PAYLOAD (§15.1.4, Table 15.1.4.1-1)

| Order | IEI | IE | Format | Length |
|---|---|---|---|---|
| 1 | - | Message type `0x03` | V | 1 |
| 2 | - | Number of payloads (§15.2.12), 1-255 | V | 1 |
| 3..n | `0x78` | Payload (§15.2.13), repeated "Number of payloads" times | TLV-E | 4-x |
| - | `0x7A` | Security parameters and Payload (TS 33.180) | TLV-E | not supported by the server: rejected with `400` (MC:348) |

Payload IE value = **Payload content type (1 octet) + Payload data**. The 2-octet
length **includes** the content-type octet (Figure 15.2.13-1: contents start at octet 4).
Maximum payload data length is 65535 octets.

Payload content type (Table 15.2.13-2): `01` TEXT, `02` BINARY, `03` HYPERLINKS,
`04` FILEURL, `05` LOCATION (data exactly 6 octets, TS 23.032 §6.1 lat/long),
`06` ENHANCED STATUS, `07` interworking, `08` LOCATION ALTITUDE (exactly 2 octets),
`09` LOCATION TIMESTAMP (1 length octet + ISO 8601 `yyyy-mm-dd hh:mm:ss.fffff`),
`0A` CODED TEXT (first 2 octets = IANA MIBenum of the charset, big-endian). All other
values are reserved. The server enforces the 6 and 2 octet sizes and a CODED TEXT
minimum of 2 (MC:356-359), and checks that the IE count equals the Number of payloads
(MC:362).

TEXT charset (§6.2.2.1 DATA PAYLOAD step 3a): TEXT uses the charset from client
configuration or the group's `<mcdata-default-charset>`. Neither is provisioned in
the lab. Use UTF-8 for TEXT, and CODED TEXT with MIBenum 106 (`00 6A`) when the charset
must be explicit.

### 2.4 SDS NOTIFICATION (§15.1.5, Table 15.1.5.1-1)

| Order | IEI | IE | Format | Length | Encoding |
|---|---|---|---|---|---|
| 1 | - | Message type | V | 1 | `0x05` |
| 2 | - | SDS disposition notification type (§15.2.5) | V | 1 | `01` UNDELIVERED, `02` DELIVERED, `03` READ, `04` DELIVERED AND READ, `05` DISPOSITION PREVENTED BY SYSTEM (TS 29.582 only) |
| 3 | - | Date and time | V | 5 | as §2.2 |
| 4 | - | Conversation ID | V | 16 | copied from the SDS |
| 5 | - | Message ID | V | 16 | copied from the SDS |
| opt | `0x22` | Application ID | TV | 2 | copied if the SDS had one |
| opt | `0x7D` | Extended application ID | TLV-E | 4-x | copied if the SDS had one |
| opt | `0x51` | Sender MCData user ID | TLV-E | 4-x | own MCData ID (see §5.2) |

Any other IEI is rejected with `400` (MC:379-401).

### 2.5 Identifiers and date-time

- Conversation ID and Message ID are UUIDs (RFC 9562). Generate version 4 (random).
  The server does not check the version or variant. On the wire they are the 16 raw
  octets, **not** text. The text form used for logs is 8-4-4-4-12 lower-case hex.
- New conversation: new Conversation ID. Reply or continuation: reuse the
  conversation's ID. Every message gets a fresh Message ID. A reply adds InReplyTo =
  the Message ID being answered (§6.2.2.1 steps 2-5).
- Date and time: `floor(System.currentTimeMillis()/1000)` in 5 octets big-endian
  (range up to 2^40-1). The server does not validate it.

### 2.6 Worked example bytes

Values: time 2026-09-25T12:00:00Z = 1790337600 = `0x006AB66240`;
Conversation `9a323217-17bd-4a01-bd07-5d02558695b1`;
Message `6402178c-a86b-498e-92e8-0a0dd96b5911`.

**A. SDS SIGNALLING PAYLOAD, new conversation, DELIVERY AND READ (39 octets)**

```
0000  01 00 6a b6 62 40 9a 32 32 17 17 bd 4a 01 bd 07
0010  5d 02 55 86 95 b1 64 02 17 8c a8 6b 49 8e 92 e8
0020  0a 0d d9 6b 59 11 83
```
`01` type | `00 6a b6 62 40` date | `9a32..95b1` conversation | `6402..5911` message |
`83` = IEI 8, value 3 (DELIVERY AND READ).

**B. DATA PAYLOAD, one TEXT payload "Hello Bob" (15 octets)**

```
0000  03 01 78 00 0a 01 48 65 6c 6c 6f 20 42 6f 62
```
`03` type | `01` one payload | `78` Payload IEI | `00 0a` length 10 = 1 (type) + 9 (data) |
`01` TEXT | `48..62` "Hello Bob". "Payload size" for the limits = 9 (§9.2.2.3.1 NOTE 4).

**C. SDS SIGNALLING PAYLOAD, reply in the same conversation, to application 7, DELIVERY (58 octets)**
New Message ID `0f3c2b1a-5d4e-4f60-8a7b-9c0d1e2f3a4b`, time +5 s.

```
0000  01 00 6a b6 62 45 9a 32 32 17 17 bd 4a 01 bd 07
0010  5d 02 55 86 95 b1 0f 3c 2b 1a 5d 4e 4f 60 8a 7b
0020  9c 0d 1e 2f 3a 4b 21 64 02 17 8c a8 6b 49 8e 92
0030  e8 0a 0d d9 6b 59 11 22 07 81
```
`21` + InReplyTo (Message ID of A) | `22 07` Application ID 7 | `81` DELIVERY.

**D. Group SDS SIGNALLING PAYLOAD, DELIVERY (39 octets)**
Conversation `3e1d9c2b-7a6f-4e5d-9c8b-1a2b3c4d5e6f`, Message `0f3c2b1a-…-3a4b`.

```
0000  01 00 6a b6 62 40 3e 1d 9c 2b 7a 6f 4e 5d 9c 8b
0010  1a 2b 3c 4d 5e 6f 0f 3c 2b 1a 5d 4e 4f 60 8a 7b
0020  9c 0d 1e 2f 3a 4b 81
```

**E. Group DATA PAYLOAD "All units: stage at north gate" (36 octets)**

```
0000  03 01 78 00 1f 01 41 6c 6c 20 75 6e 69 74 73 3a
0010  20 73 74 61 67 65 20 61 74 20 6e 6f 72 74 68 20
0020  67 61 74 65
```

**F. SDS NOTIFICATION DELIVERED AND READ for message A, from bob, with Sender IE (65 octets)**

```
0000  05 04 00 6a b6 62 42 9a 32 32 17 17 bd 4a 01 bd
0010  07 5d 02 55 86 95 b1 64 02 17 8c a8 6b 49 8e 92
0020  e8 0a 0d d9 6b 59 11 51 00 17 73 69 70 3a 62 6f
0030  62 2e 6d 63 64 61 74 61 40 6d 63 78 2e 74 65 73
0040  74
```
`05` type | `04` DELIVERED AND READ | date | conversation | message | `51 00 17` Sender,
length 23, `sip:bob.mcdata@mcx.test`.

**G. SDS NOTIFICATION DELIVERED for group message D, from bob (65 octets)**

```
0000  05 02 00 6a b6 62 41 3e 1d 9c 2b 7a 6f 4e 5d 9c
0010  8b 1a 2b 3c 4d 5e 6f 0f 3c 2b 1a 5d 4e 4f 60 8a
0020  7b 9c 0d 1e 2f 3a 4b 51 00 17 73 69 70 3a 62 6f
0030  62 2e 6d 63 64 61 74 61 40 6d 63 78 2e 74 65 73
0040  74
```

---

## 3. mcdata-info XML (Annex D.1)

Root `<mcdatainfo xmlns="urn:3gpp:ns:mcdataInfo:1.0">` > `<mcdata-Params>`. Schema
sequence order inside `<mcdata-Params>` (D.1.2) is: `mcdata-access-token`,
`request-type`, `mcdata-request-uri`, `mcdata-calling-user-id`,
`mcdata-called-party-id`, `mcdata-calling-group-id`, `alert-ind`, `originated-by`,
`mcdata-client-id`, `mcdata-controller-psi`, `gw-mcdata-usage`, (##other), `anyExt`.
Emit in this order. The server looks elements up by name, so order does not matter to
it (MC:192-229).

- `contentType` elements (`mcdata-request-uri`, `mcdata-calling-user-id`,
  `mcdata-calling-group-id`, `mcdata-client-id`, `functional-alias-URI`, …) contain
  exactly one wrapper child: `<mcdataURI>` for URIs, `<mcdataString>` for strings,
  `<mcdataBoolean>` for booleans. Attribute `type="Normal"` is optional. The server
  rejects `type="Encrypted"` with `400` (MC:55-68).
- `<request-type>` is plain text. The server allows only `[a-z0-9-]`, at most 64 chars (MC:88-91).
- `<anyExt>` children (e.g. `<user-requested-priority>`, `<used-priority>`,
  `<functional-alias-URI>`) are in the **same** namespace (global elements of the
  schema), for example `<anyExt><user-requested-priority>5</user-requested-priority></anyExt>`.
- No DOCTYPE or entities, at most 64 KiB, no NUL byte (MC:20-28).

---

## 4. Sending a standalone SDS (uplink)

Normative: TS 24.282 §9.2.1.1, §9.2.2.2.1, §6.2.4.1, §6.2.2.1, §6.4; TS 23.282 §7.4.2.1.1, §7.4.2.1.7, §7.4.2.2.

### 4.1 Common SIP rules

| Element | Rule | Server behaviour |
|---|---|---|
| Method | `MESSAGE` (RFC 3428), out of dialog | |
| Request-URI | MCData PSI `sip:mcdata@mcx.test` (§6.2.4.1 step 4) | Other R-URI: `404` Warning `142` (DS:1912). Kamailio only routes `mcdata@mcx.test` to the MCData server (KC:162-168) |
| To | Not specified by 24.282. Use the PSI `<sip:mcdata@mcx.test>` | Not checked |
| From | Own public user identity `<sip:alice@mcx.test>;tag=…` | Kamailio: From user must equal the digest username and From domain `mcx.test`, otherwise `403 Identity mismatch` (KC:116). Server: P-Asserted-Identity must equal the From URI exactly, otherwise `404` Warning `141` (DS:1906) |
| Authorization | Kamailio challenges every out-of-dialog request (`auth_check`, KC:115), so answer the 407 with digest credentials | |
| P-Asserted-Identity | Do not send. Kamailio removes any PAI and inserts `<sip:<user>@mcx.test>` (KC:125-127) | More than one PAI gives `400` (runtime.cpp:314-327) |
| P-Preferred-Identity | Optional (§6.2.4.1 step 3). Kamailio ignores it | |
| Accept-Contact | Both headers of §1 (§6.2.4.1 step 1a-b) | The icsi-ref one is **required** (DS:1933). The feature-tag one is not checked but is mandatory in the spec |
| P-Preferred-Service | `urn:urn-7:3gpp-service.ims.icsi.mcdata.sds`, one header (§6.2.4.1 step 1c) | Not checked for SDS. Kamailio does not convert it to P-Asserted-Service |
| Resource-Priority | **Omit.** 24.282 uses it only for emergency and imminent-peril communications (§6.2.8), not for standalone SDS | Ignored on MESSAGE |
| Content-Type | `multipart/mixed; boundary=<token>` | Boundary: 1-70 chars. Use only `[A-Za-z0-9_-]` (the server's own generator rule, PR:826-845). At most **8 parts** per message (PR:910) and 64 KiB total (PR:15) |
| Content-Length | Exact **byte** count of the body | |

**Multipart framing** (§6.4; parser PR:850-915): for each part, write
`--<boundary>\r\n` then `Content-Type: <type>\r\n` then `\r\n` then the raw body octets
then `\r\n`. End with `--<boundary>--\r\n`. The server does not use
Content-Transfer-Encoding and does not base64-decode. The TLV bodies go on the wire
as raw octets. The body must never contain `\r\n--<boundary>`, so pick a random
boundary. The server reads only the first `Content-Type` header of each part,
lower-cases it and drops its parameters. Part order does not matter (§6.4 NOTE).

**Android pitfall.** Build the body as `byte[]` and never pass it through a `String`
or charset conversion. Compute Content-Length from the byte array. Many SIP stacks
default to text bodies.

### 4.2 One-to-one (`one-to-one-sds`)

Bodies (§9.2.2.2.1 step 2, step 4):

1. `application/vnd.3gpp.mcdata-info+xml`: `<request-type>one-to-one-sds</request-type>`.
   Optional `<anyExt>` with `<user-requested-priority>`. `mcdata-client-id` and
   `mcdata-request-uri` are not required and the server does not use them.
2. `application/resource-lists+xml` with **exactly one** `<entry uri="<target MCData ID>"/>`.
   The entry is the MCData ID (`sip:bob.mcdata@mcx.test`), not the IMPU.
3. `application/vnd.3gpp.mcdata-signalling`: SDS SIGNALLING PAYLOAD.
4. `application/vnd.3gpp.mcdata-payload`: DATA PAYLOAD.

Example (bodies A and B of §2.6, Content-Length 710):

```
MESSAGE sip:mcdata@mcx.test SIP/2.0
Via: SIP/2.0/TLS client-a:5061;branch=z9hG4bK-4f1c
Max-Forwards: 70
From: <sip:alice@mcx.test>;tag=a91f
To: <sip:mcdata@mcx.test>
Call-ID: 7c1e0b2d@client-a
CSeq: 1 MESSAGE
Accept-Contact: *;+g.3gpp.mcdata.sds;require;explicit
Accept-Contact: *;+g.3gpp.icsi-ref="urn%3Aurn-7%3A3gpp-service.ims.icsi.mcdata.sds";require;explicit
P-Preferred-Service: urn:urn-7:3gpp-service.ims.icsi.mcdata.sds
Content-Type: multipart/mixed; boundary=mcdata-7f3a91c2
Content-Length: 710

--mcdata-7f3a91c2
Content-Type: application/vnd.3gpp.mcdata-info+xml

<?xml version="1.0" encoding="UTF-8"?><mcdatainfo xmlns="urn:3gpp:ns:mcdataInfo:1.0"><mcdata-Params><request-type>one-to-one-sds</request-type></mcdata-Params></mcdatainfo>
--mcdata-7f3a91c2
Content-Type: application/resource-lists+xml

<?xml version="1.0" encoding="UTF-8"?><resource-lists xmlns="urn:ietf:params:xml:ns:resource-lists"><list><entry uri="sip:bob.mcdata@mcx.test"/></list></resource-lists>
--mcdata-7f3a91c2
Content-Type: application/vnd.3gpp.mcdata-signalling

<39 raw octets of example A>
--mcdata-7f3a91c2
Content-Type: application/vnd.3gpp.mcdata-payload

<15 raw octets of example B>
--mcdata-7f3a91c2--
```

(Each part is followed by CRLF before the next delimiter. The XML bodies are single
lines with no trailing newline.)

### 4.3 Group (`group-sds`)

Bodies (§9.2.2.2.1 step 3):

1. mcdata-info with `<request-type>group-sds</request-type>`,
   `<mcdata-request-uri><mcdataURI>GROUP</mcdataURI></mcdata-request-uri>` and
   `<mcdata-client-id><mcdataString>CLIENT-ID</mcdataString></mcdata-client-id>`.
   The client ID is **mandatory for the server**: it must equal the client ID bound
   at service authorisation, otherwise bare `403` (DS:1985).
2. SDS SIGNALLING PAYLOAD. 3. DATA PAYLOAD. **No** resource-lists body.

Client-side precheck (§9.2.2.2.1 step 3a): if the group document's `<AllowedSDS>` is
false, do not send. In the lab the GMS projection is internal, and the server enforces
this with `206`.

Example (bodies D and E, Content-Length 715):

```
MESSAGE sip:mcdata@mcx.test SIP/2.0
...same headers as 4.2...
Content-Type: multipart/mixed; boundary=mcdata-7f3a91c2
Content-Length: 715

--mcdata-7f3a91c2
Content-Type: application/vnd.3gpp.mcdata-info+xml

<?xml version="1.0" encoding="UTF-8"?><mcdatainfo xmlns="urn:3gpp:ns:mcdataInfo:1.0"><mcdata-Params><request-type>group-sds</request-type><mcdata-request-uri type="Normal"><mcdataURI>sip:operations@mcx.test</mcdataURI></mcdata-request-uri><mcdata-client-id type="Normal"><mcdataString>urn:uuid:8034b521-39ae-47bd-b7bf-aaa7a6a316ef</mcdataString></mcdata-client-id></mcdata-Params></mcdatainfo>
--mcdata-7f3a91c2
Content-Type: application/vnd.3gpp.mcdata-signalling

<39 raw octets of example D>
--mcdata-7f3a91c2
Content-Type: application/vnd.3gpp.mcdata-payload

<36 raw octets of example E>
--mcdata-7f3a91c2--
```

Group fan-out (DS:996-1007, TS 24.282 §6.3.4, §9.2.2.4.2 step 6): the message goes
only to members who are MCData-**affiliated** to the group. **The sender is excluded
and gets no copy.** The sender must be affiliated (`120`), and at least one other
member must be affiliated (`198`).

### 4.4 Responses to the uplink SDS

| Response | Meaning | Client action |
|---|---|---|
| `202 Accepted`, no body | Accepted and relayed (DS:1026; §9.2.2.4.2 steps 7-8). The recipients' own 200s end at the server | Mark the message "sent". Delivery is known only from notifications |
| `2xx` with mcdata-info `<used-priority>` | Spec allows it (§9.2.2.2.1 "Upon receiving a SIP 2xx"). OpenMCX sends it only for ad hoc groups | Parse if present |
| `300 Multiple Choices` + mcdata-info `<mcdata-request-uri>` | Functional alias target only | Out of scope |
| `4xx` with `Warning: 399 mcdata "NNN text"` | Rejection | Show the code (table below) |
| `400` without Warning | Malformed XML or TLV, reserved value, unknown IE, protected message (DS:4015-4018) | Bug in the encoder |
| `403` without Warning | Missing icsi-ref Accept-Contact; `mcdata-calling-user-id` not own ID; group `mcdata-client-id` mismatch; unknown request-type (DS:1933-1990) | Bug in the request |

SDS Warning codes the server returns (DS:895-1037, §4.9.2):

| Status | Code | Condition |
|---|---|---|
| 404 | 141 | No MCData binding, binding expired, or PAI different from From |
| 404 | 142 | Request-URI is not the MCData PSI |
| 403 | 199 | mcdata-info, signalling or payload body missing |
| 403 | 200 | User not allowed to transmit data |
| 403 | 202 | Over the user's `max_data_1to1` (alice: 300) |
| 403 | 229 | One-to-one target not in the user's allowed list |
| 403 | 203 | Payload size over `max_payload_size_sds_cplane_bytes` (1000) |
| 403 | 218 | One-to-one payload over `max_data_size_sds_bytes` (500) |
| 403 | 204 | resource-lists missing, more than one entry, or target is self |
| 404 | (none) | One-to-one target not MCData-authorised (not bound) |
| 403 | 230 | Target does not accept one-to-one SDS from this sender |
| 404 | 163 | Group unknown |
| 403 | 167 / 116 / 206 / 148 / 201 / 208 / 217 / 120 / 198 | Preconfigured-only / not an MCData member / SDS not allowed / regrouped / no transmit on group / over group request limit / over group SDS size / sender not affiliated / no other affiliated member |
| 488 | 207 | Group has no SDS enabler |

Check order for one-to-one (DS:921-967): 200, 204, 202, 229, 203, 218, (alias), 404, 230.
Check order for group: 200, 203, then 163 onward in the order of the table.

---

## 5. Limits, size and timers

- **Payload size** (§9.2.2.3.1 step 8 and NOTE 4) is the Payload IE length minus 1,
  i.e. the payload data only. The server **sums the data of all Payload IEs**
  (DS:588-592). Compare that sum with the limits.
- **Client** (§9.2.1.1 step 2): if size ≤ `max-payload-size-sds-cplane-bytes`, use
  SIP MESSAGE. If size is above it, the spec requires the media-plane procedure
  §9.2.3.2.3 (MSRP, TS 24.582). That is out of this phase, so refuse locally with
  "too large". If the client sends it anyway, the server answers `403` `203`.
- In the lab the effective one-to-one ceiling is min(1000, 500, 300 for alice) = 300
  octets. The effective group ceiling is 400 octets.
- Hard wire limits: 64 KiB SIP body envelope, 8 multipart parts, 65535 octets per TLV-E.
- **TDU1** (Table F.2.3-1): client timer, default **120 ms**, configurable. It starts on
  receipt of an SDS with DELIVERY AND READ (§9.2.1.3). It is the only client timer in
  this phase.
- Server timers, for information: TDP1 re-delivery after UNDELIVERED (default 60 s,
  lab 2 s, Table F.2.1-1); TDC1 group notification aggregation (default 5 s, lab 3 s,
  Table F.2.2-1).

---

## 6. Receiving an SDS (downlink)

Normative: §6.2.1.1, §9.2.2.2.2, §9.2.1.2, §9.2.2.4.1.1, §6.3.2.1.

What arrives (built by DS:620-652 via Kamailio `lookup("location")`, KC:170-174):

```
MESSAGE <registered Contact of bob> SIP/2.0
From: <sip:mcdata@mcx.test>;tag=…
To: <sip:bob@mcx.test>
P-Asserted-Identity: <sip:mcdata@mcx.test>
Accept-Contact: *;+g.3gpp.mcdata.sds;require;explicit
Accept-Contact: *;+g.3gpp.icsi-ref="urn%3Aurn-7%3A3gpp-service.ims.icsi.mcdata.sds";require;explicit
P-Asserted-Service: urn:urn-7:3gpp-service.ims.icsi.mcdata.sds
Content-Type: multipart/mixed; boundary=mcdata-<random>

--…  application/vnd.3gpp.mcdata-info+xml
<?xml version="1.0" encoding="UTF-8"?><mcdatainfo xmlns="urn:3gpp:ns:mcdataInfo:1.0"><mcdata-Params><request-type>one-to-one-sds</request-type><mcdata-request-uri type="Normal"><mcdataURI>sip:bob.mcdata@mcx.test</mcdataURI></mcdata-request-uri><mcdata-calling-user-id type="Normal"><mcdataURI>sip:alice.mcdata@mcx.test</mcdataURI></mcdata-calling-user-id></mcdata-Params></mcdatainfo>
--…  application/vnd.3gpp.mcdata-signalling   (sender's octets, unchanged)
--…  application/vnd.3gpp.mcdata-payload      (sender's octets, unchanged)
```

For a group SDS the mcdata-info has `<request-type>group-sds</request-type>` and also
`<mcdata-calling-group-id type="Normal"><mcdataURI>sip:operations@mcx.test</mcdataURI></mcdata-calling-group-id>`.
If the sender requested a priority, `<anyExt><used-priority>N</used-priority></anyExt>`
is present. The server removes `mcdata-client-id` and `mcdata-access-token` (DS:628-631).

Classification (§6.2.1.1): an SDS is a MESSAGE with the SDS icsi-ref Accept-Contact
and P-Asserted-Service. Inside it, the first octet of the signalling body tells a new
SDS (`0x01`, with a payload body) from a notification (`0x05`, no payload body).

**Identifying sender and group** (§12.2.1.1, pre-steps): the sender is
`<mcdata-calling-user-id>`. The group, if any, is `<mcdata-calling-group-id>`. Do
**not** use From or PAI: they are always the MCData PSI. A client may reject a
request whose PAI is not the MCData PSI. `<mcdata-request-uri>` is the client's own
MCData ID.

**Response** (§9.2.2.2.2 steps 4-5): `200 OK` with no body. Use `480 Temporarily
Unavailable` only when there are no resources (steps 1-2). The server records the
status but does not relay it to the sender.

**Processing** (§9.2.1.2): decode both bodies. Thread by Conversation ID and
InReplyTo. Without Application ID or Extended application ID, render to the user.
With a known Application ID, deliver to the application silently. With an unknown
one, discard silently, but still answer 200. Then handle the disposition request
(§7). Deduplicate by Message ID: after an UNDELIVERED report, the server re-delivers
the identical bodies with the same Message ID (DS hold/redelivery,
MCDATA_DISPOSITION_TEST.md RD-02). Answer 200 again and do not render twice.

---

## 7. Disposition notifications

Normative: §9.2.1.3, §12.2.1.1, §12.2.1.2, §6.2.3.1, §12.2.2.1, §12.2.3; TS 23.282 §7.4.2.1.2, §7.4.2.1.9.

### 7.1 When to send (§9.2.1.3)

| Request in SDS | Send |
|---|---|
| none (IE absent) | nothing (UNDELIVERED is still allowed, see below) |
| DELIVERY `0x81` | DELIVERED right after acceptance |
| READ `0x82` | READ when the user views the message (display indication) |
| DELIVERY AND READ `0x83` | start TDU1. Displayed before expiry: stop TDU1 and send DELIVERED AND READ. On expiry: send DELIVERED, then READ when displayed |
| any, cannot deliver (e.g. storage full) | UNDELIVERED to the participating function (§6.2.3.1 step 4, §12.2.1.1). It is not relayed to the sender |

Application-directed SDS: copy the Application ID or Extended application ID IE into
the notification (§6.2.3.1 step 9).

### 7.2 Notification MESSAGE (uplink)

Headers are identical to §4.1: R-URI = MCData PSI, both Accept-Contact headers,
P-Preferred-Service SDS (§12.2.1.1 step 1 → §6.2.4.1). Bodies:

1. `application/vnd.3gpp.mcdata-info+xml`. For a group SDS it contains
   `<mcdata-calling-group-id><mcdataURI>GROUP</mcdataURI></mcdata-calling-group-id>`
   (step 5). For one-to-one, an empty `<mcdata-Params/>` is fine. **This body is
   mandatory for the server**: without it you get `403` `199` (DS:1936), although the
   spec requires it only for group notifications. Do not put `request-type` or a
   `mcdata-calling-user-id` other than your own (DS:1938).
2. `application/resource-lists+xml` with one entry: the **original sender's MCData ID**,
   taken from `<mcdata-calling-user-id>` of the received SDS (step 3).
3. `application/vnd.3gpp.mcdata-signalling`: SDS NOTIFICATION. **Always include the
   Sender MCData user ID IE (`0x51`) = own MCData ID.** The server aggregates group
   notifications only when it is present (DS:1188-1189). If present it must equal
   the notifier's bound MCData ID, otherwise bare `403` (DS:1186).

No payload body. Example: group DELIVERED from bob (body G, Content-Length 717):

```
MESSAGE sip:mcdata@mcx.test SIP/2.0
From: <sip:bob@mcx.test>;tag=…
To: <sip:mcdata@mcx.test>
Accept-Contact: *;+g.3gpp.mcdata.sds;require;explicit
Accept-Contact: *;+g.3gpp.icsi-ref="urn%3Aurn-7%3A3gpp-service.ims.icsi.mcdata.sds";require;explicit
P-Preferred-Service: urn:urn-7:3gpp-service.ims.icsi.mcdata.sds
Content-Type: multipart/mixed; boundary=mcdata-7f3a91c2
Content-Length: 717

--mcdata-7f3a91c2
Content-Type: application/vnd.3gpp.mcdata-info+xml

<?xml version="1.0" encoding="UTF-8"?><mcdatainfo xmlns="urn:3gpp:ns:mcdataInfo:1.0"><mcdata-Params><mcdata-calling-group-id type="Normal"><mcdataURI>sip:operations@mcx.test</mcdataURI></mcdata-calling-group-id></mcdata-Params></mcdatainfo>
--mcdata-7f3a91c2
Content-Type: application/resource-lists+xml

<?xml version="1.0" encoding="UTF-8"?><resource-lists xmlns="urn:ietf:params:xml:ns:resource-lists"><list><entry uri="sip:alice.mcdata@mcx.test"/></list></resource-lists>
--mcdata-7f3a91c2
Content-Type: application/vnd.3gpp.mcdata-signalling

<65 raw octets of example G>
--mcdata-7f3a91c2--
```

Responses (DS:1144-1215): `202 Accepted` when relayed or collected for aggregation.
`200 OK` for UNDELIVERED (stored for re-delivery). `403` without Warning when
resource-lists is missing or has more than one entry, when the notification does not
match the recorded message (wrong originator, or the notifier was not a recipient),
or on a Sender IE mismatch. `403` `116` when the notifier is not a member of the
calling group. `404` `163` for an unknown group. `404` when the original sender is
not bound.

### 7.3 Receiving notifications (§12.2.1.2)

Individually relayed notification (one-to-one, a group with aggregation off, or one
that arrives after the aggregate): the same downlink headers as §6, and the bodies
are:
- mcdata-info: `<mcdata-request-uri>` = own MCData ID, `<mcdata-calling-user-id>` =
  **the notifier**, plus `<mcdata-calling-group-id>` for a group (DS:1209-1210, 626-631);
- one `application/vnd.3gpp.mcdata-signalling` body with the SDS NOTIFICATION,
  byte-identical to what the notifier sent.

**Aggregated group notification** (§12.2.3 step 15c, NOTE 6; DS:1219-1240): one
MESSAGE whose mcdata-info has only `<mcdata-request-uri>` (self) and
`<mcdata-calling-group-id>`, **with no `mcdata-calling-user-id`**, followed by
**N ≥ 1 `application/vnd.3gpp.mcdata-signalling` bodies**, one per notifier. Take
each notifier from that body's Sender IE (`0x51`). A multipart parser that keeps only
one body per content type loses notifications.

Correlate each notification by (Conversation ID, Message ID) with the sent message,
and by notifier for group messages. Answer `200 OK`.

---

## 8. Server deviations and risks

| # | Deviation or risk | Spec | Server | Impact on the client |
|---|---|---|---|---|
| D1 | Accept-Contact ICSI must be percent-encoded with upper-case `%3A` | 24.229 coding, RFC 3841 matching | Case-sensitive substring match (DS:602-611) | Send exactly the string in §1, or get a bare 403 |
| D2 | mcdata-info required on one-to-one notifications | §12.2.1.1 step 5: only for a group | 403 `199` without it (DS:1936) | Always include it (an empty `<mcdata-Params/>` is fine) |
| D3 | Missing or multiple resource-lists entries in a notification | 403 Warning `145` (§12.2.3 step 3) | Bare 403 (DS:1151) | Do not rely on the Warning |
| D4 | Uncorrelated notification (unknown Message ID) | 403 `216` (§12.2.3 step 5) | Relayed anyway; only a mismatch is rejected (DS:1181-1184) | Permissive |
| D5 | Group SDS without `mcdata-client-id` or with a mismatched one | Mandatory to include (§9.2.2.2.1 step 3b-iv); no check defined | Bare 403 (DS:1985) | Use the ID from service authorisation |
| D6 | Aggregation depends on the optional Sender IE | Not in spec | Without the Sender IE, each notification is relayed individually (DS:1188) | Always send the Sender IE |
| D7 | Aggregate size | NOTE 6: split when needed | Split at `max_per_message` (50), but the multipart builder refuses more than 8 parts (PR:833), so an aggregate with more than 7 notifications throws. The notifier whose report completes it gets `400` and the aggregate is lost | Not reachable with the 4-member lab group; a larger group breaks |
| D8 | `used-priority` in the 2xx to the sender | Allowed | Sent only for ad hoc groups | Ignore its absence |
| D9 | Warning agent | Host name of the MCData server (§4.9.1) | Literal `mcdata` | Parse with `399 \S+ "(\d{3}) (.*)"` |
| D10 | Cplane size check sums all payloads | NOTE 4 is written for one Payload IE | Sum of data lengths (DS:588-592) | Enforce the sum locally |
| D11 | No limits delivered to the client | `<max-payload-size-sds-cplane-bytes>` in the TS 24.484 service configuration | Not served | Configure the value statically |
| D12 | UNDELIVERED answered `200` (other notifications get `202`) | §12.2.2.1 step 5 defines no response | 200 | Accept any 2xx |
| D13 | Payload protection, Encrypted XML, protected or authenticated message type bits | TS 33.180 | Rejected with 400 | Never set bits 8-7; no `0x7A` IE |
| D14 | Registration feature tags | §7.2.1: Contact with icsi-ref mcdata, `g.3gpp.mcdata.sds`, icsi-ref mcdata.sds | Kamailio does not apply caller preferences (plain `lookup`) | Include them anyway (spec compliance; the lab wire check tests for them) |

Other client risks:
- **Binary bodies**: see the Android pitfall in §4.1. Content-Length must count octets.
- **Downlink connection reuse**: Kamailio uses `set_forward_no_connect` (KC:172), so it
  sends downlink MESSAGEs only over the client's existing TLS/TCP connection. Keep
  the registration flow alive (keep-alives). If the connection has dropped, the
  relay fails (`480` when there is no registration). The server does not store the message: it
  stores only after an UNDELIVERED report.
- **Sender excluded from group delivery**: show the sent group message locally.

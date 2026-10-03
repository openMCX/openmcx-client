# Why the SIP core needs flow reuse

The Android client never accepts an incoming SIP connection. Every request to it (a call, a NOTIFY, a BYE, an SDS)
has to come back over the TLS connection the client opened to register. A SIP core that dials a client's Contact does not do this. The openMCX SIP
core (Kamailio) reuses the registered flow for RFC 5626 clients only, and the Android lab uses its configuration
unchanged. This document explains why flow reuse is needed, what the configuration changes, and how it is gated.

## How the configuration without flow reuse reaches a client

A Kamailio configuration without flow reuse treats a client's Contact as an address it can dial:

1. **REGISTER.** `save("location")` stores the Contact URI exactly as the client wrote it, for example
   `sip:alice@client-a:5061;transport=tls`. Nothing records where the REGISTER actually came from.
2. **A request from an application server to a client.** When the MCPTT or MCData server sends an INVITE, MESSAGE or
   NOTIFY to `sip:alice@mcx.test`, `lookup("location")` rewrites the Request-URI to that Contact. `t_relay()` then sends
   it to `client-a:5061`, opening a new TLS connection if none exists to that address and port.
3. **In-dialog requests** (BYE, UPDATE, re-SUBSCRIBE NOTIFYs) go to the dialog's remote target, which is the client's
   Contact from its INVITE or 2xx. Again, Kamailio connects there.

This works for the reference clients and the Linux client, because each one is a container that listens on 5061 under a
hostname the SIP core can resolve. Each is a small SIP server.

## Why a phone can't work that way

An Android MC UE has none of those properties, and by design it has no listener:

- **Unreachable.** A phone on a mobile network is behind carrier NAT, and usually behind NAT on Wi-Fi too. Its
  address isn't reachable from the network, and inbound TCP can't reach it.
- **Changing addresses.** Its address changes whenever the network changes (Wi-Fi to mobile, a new cell, a DHCP lease).
  A Contact built from its current address is stale within minutes.
- **Battery.** Holding a listening socket and accepting TLS handshakes from the network costs battery. Android also
  doesn't guarantee that a background process can accept connections under Doze.
- **Identity.** A phone has no DNS name. The lab certificates carry `client-a` as a name, but a real UE's certificate
  identifies the user or device, not a host the core could dial.

RFC 5626 (SIP Outbound) was written for exactly this. The UE opens a *flow* (here one TLS connection), registers over
it with `+sip.instance` and `reg-id`, and keeps it alive with CRLF keepalives. The registrar and edge proxy then send
everything for that UE back over the flow. 3GPP IMS works the same way: the P-CSCF reaches the UE over the connection or
security association the UE set up, not by dialling a host.

The Android client therefore:

- opens one outbound TLS flow to `sip-core:5061` and never listens (`core/…/sip/Flow.kt`);
- registers with `+sip.instance="<urn:uuid:…>";reg-id=1` and `ob`, and sends CRLF keepalives every 25 s
  (`McxClient.register`, `SipFlow.keepalives`);
- names itself with a random `<name>.invalid` host in Via and Contact. The name is not resolvable on purpose: nothing
  should try to reach the UE by it (`UserAgent.sentBy`).

Without flow reuse, the first request towards the client fails. Run without flow reuse, all three clients did this:

1. They logged in, registered, were authorised (PUBLISH poc-settings, 200), and subscribed to affiliation status
   (SUBSCRIBE presence, 200).
2. The MCPTT server's NOTIFY never arrived. Kamailio's log shows it trying to dial the Contact:
   ```
   ERROR: <core> [core/resolve.c:1778]: sip_hostport2su(): could not resolve hostname: "b12aafe201.invalid"
   ERROR: tm [ut.h:303]: uri2dst2(): failed to resolve "b12aafe201.invalid"
   ```
3. The client's affiliation PUBLISH got 200, but no NOTIFY confirmed it, so start-up failed with
   `MCPTT not affiliated to [sip:operations@mcx.test]`. The client retried on a new flow and failed again, over and over.

Incoming calls and SDS fail the same way: any request the core sends to the client takes the same `lookup` path. A
client that put its real address in the Contact would fare no better. The core would dial a phone that doesn't listen
(connection refused), or an address behind NAT (a timeout).

Kamailio's registrar can't do RFC 5626 routing on its own here. The `outbound` module's full mode requires
`Require: outbound`, which this configuration answers with `420 Bad Extension` (`outbound_mode` is 0). So the configuration
applies the standard Kamailio technique for clients behind NAT or without listeners: remember each flow's source
address, and route to it without ever dialling.

## What the configuration changes

The openMCX `docker/kamailio.cfg` makes these changes:

| Change | Where | What it does |
|---|---|---|
| `loadmodule "nathelper.so"`; `modparam("nathelper"/"registrar", "received_avp", "$avp(RECEIVED)")` | module section | The registrar stores a `received` address with each binding, and `lookup()` sets the destination URI (`$du`) to it |
| `fix_nated_register()` before `save("location")` | REGISTER branch | Records the flow's actual source (`ip:port;transport=tls`) as the binding's `received` address. A later request to the AoR is sent to that address, which Kamailio matches to the open TCP/TLS connection from that address and port, so it goes out on the flow |
| `set_forward_no_connect()` after `lookup("location")` | requests from an AS to a client | Kamailio may only use an existing connection. If the flow is gone the request fails at once (the client re-registers on a new flow) rather than trying to dial a phone |
| `add_contact_alias()` on client requests, and in a new `reply_route` on client responses | request route; `reply_route` | Adds `;alias=<ip>~<port>~<proto>` for the flow to the Contact the client sent. That Contact becomes the dialog's remote target at the MCPTT or MCData server, so in-dialog requests (BYE, UPDATE, NOTIFY) return to Kamailio carrying the alias. Kamailio adds it only when the Contact differs from the source address, which is why the client uses a `.invalid` host |
| `handle_ruri_alias()` plus `set_forward_no_connect()` after `loose_route()` | `route[DIALOG_ROUTE]` | Turns the alias back into the destination (`$du`), strips it from the Request-URI, and sends the in-dialog request over the flow |
| `tcp_connection_lifetime=3605` | core parameters | Kamailio's default closes TCP and TLS connections idle for 120 s. The client's keepalive traffic keeps a flow busy, but a long lifetime (just over the maximum registration time) keeps Kamailio from ending a registered flow on its own |

Nothing else in the configuration changed. Authentication, the identity binding of From and PAI to the digest user,
the AS certificate checks, the transaction-peer binding and the PSI-only routing all apply as before. The Kamailio
image already has the `nathelper` module, and the patched configuration passed `kamailio -c`.

## Evidence with flow reuse

- **Without flow reuse:** the lab described above. No client gets past `affiliating-mcptt`.
- **S1:** all three clients reach `ready`. Each client's affiliation is confirmed only by a NOTIFY, which the MCPTT and
  MCData servers send in-dialog through Kamailio, so it arrives over the flow via the alias.
- **S2–S5:** incoming INVITEs (initial requests, routed by `lookup` plus `received`), BYEs from the server and UPDATE
  responses all reach the terminating clients.
- **S6:** MESSAGE requests for SDS and their notifications reach the recipients over their flows.
- **S7:** after a client drops its flow, it registers on a new one with the same instance and reg-id. That binding
  replaces the old one, and the next call reaches the new flow. The old flow's routes fail fast rather than dialling.

## Gated to RFC 5626 clients

Applying the changes to every registration and every client Contact would not suit listening clients
(`mcx-client`, whose Contact `sips:<user>@client-x:5061;transport=tls` has no `+sip.instance`, `reg-id` or `ob`): their
bindings would point at their ephemeral source ports with new connections forbidden, and every dialog Contact of
theirs would be aliased. The configuration therefore applies the six changes only for RFC 5626
clients:

| Change | Applied when |
|---|---|
| `nathelper`, `received_avp` for `nathelper` and `registrar` | Always loaded |
| `fix_nated_register()` (`route[FLOW_REGISTER]`) | The REGISTER's Contact has `+sip.instance` or `reg-id` |
| `set_forward_no_connect()` after `lookup("location")` | `lookup` set a destination (`$du`), that is, the binding has a received address (a flow) |
| `add_contact_alias()` on client requests and, in `reply_route`, on client responses (`route[CLIENT_CONTACT]`) | The Contact URI has `ob`. This client sets `ob` on every Contact it sends (`UserAgent.contactUri`: REGISTER, INVITE and its responses, UPDATE, SUBSCRIBE), so its dialogs are covered |
| `handle_ruri_alias()` plus `set_forward_no_connect()` in `route[DIALOG_ROUTE]` | The in-dialog request comes from an application server and its Request-URI has an `alias` |
| `tcp_connection_lifetime=3605` | Always |

In addition, a client can no longer supply an `alias` itself: a client request whose Contact has one is refused with
400, and a client response with one is dropped (a response can't be refused; the server's transaction then fails as
if the client had not answered). The Android client never sends one.

`lab/androidlab.py` patches nothing: it takes `kamailio.cfg` from the openmcx-server checkout given with `--openmcx`.

S1–S11 ran against it with no patch: 144 of 144 checks passed ([TEST_REPORT.md](TEST_REPORT.md)). openmcx-server's
regression checks the same behaviour with a scripted flow client, and that `mcx-client`'s bindings and dialogs are
unchanged.

A fuller alternative remains RFC 5626 proper: the `outbound` module, `Supported: outbound` with `Require: outbound` in
REGISTER responses, and a `Flow-Timer`. That also tells clients the keepalive interval. Either way, the MCPTT server
still has two limits for clients behind NAT:

- it accepts only an SDP address that equals the address it resolves for the user in its `peers` table;
- it sends media only to the SDP address and drops packets from any other source, so it never learns an address from
  traffic (no latching).

These concern media, not SIP, and are outside the SIP core's flow reuse.

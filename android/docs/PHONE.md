# Using a real phone with the lab

In phone mode, a phone on your Wi-Fi takes one user's role (for example alice, `client-a`), and the other users stay
redroid containers. So you can call and message between your phone and the Android clients in the lab.

## Requirements

- The phone and the host on the **same LAN**, and the phone able to reach the host directly. Guest networks and
  "client isolation" or "AP isolation" on the access point block this. The MCPTT server can't work through NAT (see
  below), so a phone on mobile data or behind another router won't work.
- Android 11 (API 30) or later, with an AMR-WB encoder. Stock Android has `c2.android.amrwb.encoder`.
- Incoming TCP 5061, 8443 and 8444, and UDP 4000–4199 and 5000–5199, allowed by the host's firewall, if it has one
  (for example `sudo ufw allow from <phone-ip>`).
- Everything the redroid lab needs: `binder_linux`, and the `mcx-nettools:1` image (see the [README](../README.md)).

## Setting up

Give the phone a fixed address first: a DHCP reservation, or a static IP in the phone's Wi-Fi settings. The MCPTT server
is configured with it.

```bash
lab/androidlab.py create --name p1 --phone client-a --phone-ip <phone-ip>   # the phone's Wi-Fi address
lab/androidlab.py up p1                                                      # servers and redroid bob and client-c
```

`create` prints the host address it will publish on (the source address of the host's default route). Pass
`--host-ip` to choose another.

Then install on the phone, by either of these routes:

- **Over adb** (USB, or wireless debugging), with the debug APK:
  ```bash
  tools/gradle-docker.sh :app:assembleDebug
  lab/androidlab.py install-phone p1 --serial <serial from 'adb devices'>
  ```
  This installs the APK, provisions it, and records the serial. The scenario runner then drives the phone like the
  containers, for example `tests/scenarios.py p1 S1 S2 S6`. S7–S9 manipulate a container's network namespace
  and don't apply to a phone.
- **By hand:** install `app/build/outputs/apk/debug/app-debug.apk` (`adb install` or a file manager). Copy
  `lab/run/p1/phone/client-a.mcxprov` to the phone, open the app, and choose **Device → Import provisioning file**. The
  file holds the user's credentials and TLS key, so treat it as secret and delete the copy afterwards. The app moves the
  key into the Android Keystore.

On the phone, allow the microphone and notifications, and on the **Logs** tab choose **Keep running in the
background**. Otherwise some vendors' battery managers stop the client between calls. The Logs tab also shows the
provisioning, the endpoints in use and the client's event log.

## What phone mode changes

| Where | Change | Why |
|---|---|---|
| Docker | Publishes `sip-core` 5061, `idms` 8443, `http-proxy` as host port 8444, and the MCPTT server's UDP 4000–4199 (RTP) and 5000–5199 (floor control) | The phone isn't on the Docker network |
| Phone provisioning | `"endpoints": {"sip-core:5061": "<host>:5061", "idms:8443": "<host>:8443", "http-proxy:8443": "<host>:8444"}` | The phone can't resolve the lab names. The client connects to the mapped address but still presents and verifies the names over TLS, so the lab certificates stay valid and the phone needs no DNS changes |
| `mcptt-server.json` | `media.advertise` = the host's LAN address | The server puts this address in its SDP, and the phone sends RTP and floor control to it |
| `mcptt-server.json` | `peers[<phone's MCPTT ID>]` = the phone's address | The server accepts an SDP address only if it equals the address it has for the user |
| Redroid clients | NAT in each container's namespace (`lab/nettools`, iptables-nft in chains of its own). Traffic to `<host>:4000–4199,5000–5199` is DNATed to the MCPTT server, and traffic from the server's media ports is SNATed to appear from `<host>` | From inside the lab network, the published ports go through Docker's userland proxy, which replaces the source address, and the server drops media whose source isn't the SDP address. Both directions are needed: with only the DNAT, a server packet that arrives first leaves a conntrack entry that clashes with the client's reply, and netfilter changes the client's source port |

The SIP core reuses the phone's flow as in every Android lab ([why](SIP_CORE_FLOW_REUSE.md)).
The phone never accepts a connection. Signalling returns over its outbound TLS flow, so the phone's own NAT or firewall
doesn't matter for SIP.

## When the host's address changes

The phone's copy of the host address is in its profile's `endpoints` only. **Settings → Server address** changes it
in the app (debug command `server-address <ip>`, `server-address reset`): the endpoints are rewritten to the new host,
each server keeping its port, and the client reconnects. The certificates name the servers, not their addresses, so
TLS still checks them against the profile's CA. The edit is kept beside the imported profile; importing one clears it.

The lab's server side also holds the host's address: `media.advertise` in `mcptt-server.json` (and the MCVideo
server's), where the phone sends RTP and floor control. After a real change of the host's address, signalling works
with the edited address, but voice and video need the server's `media.advertise` updated as well (for now: recreate
the lab, which also makes new profiles).

Phone scenario S15 (`tests/scenarios.py <lab> S15`): bob imports a copy of his profile with a stale address
(TEST-NET-1) and cannot register; setting the host's address in the app brings registration, service authorisation,
affiliation and a group call back; reset returns to the profile's address; re-importing clears the edit. Result:
8/8, with S1 10/10.

## Why a phone on another network won't work

The MCPTT server identifies a user's media by the SDP address. It checks that address against its `peers` table, and
it drops RTP and floor packets from any other source. It never learns an address from received traffic (no latching),
so a phone behind NAT offers its private address, and the server sends media to an address that can't be reached.
Supporting that needs server changes: latching, or ICE or a media relay. It isn't a client limitation.

## Testing without a phone

The phone path was checked with the Android 16 emulator standing in for the phone, reaching the lab through the host's
published ports and the endpoint map. The emulator sits behind QEMU's NAT (10.0.2.15), so:

- **Checked:** login, documents, registration, authorisation, affiliation and SDS in both directions with a redroid
  client.
- **Not checked:** MCPTT calls, because the server's peer check fails for the NAT address, as it would for any
  NATed phone.

The redroid clients in the same lab make calls with the configuration a LAN phone uses: the server advertises the host
address and media crosses the published UDP ports. Their speech arrives unchanged. See [TEST_REPORT.md](TEST_REPORT.md).

Still unverified until a real phone is used: the phone's own inbound media path. RTP from the LAN reaches the server
through Docker's DNAT for published ports, which keeps the phone's source address. That's Docker's standard behaviour
for traffic from outside the host, but it can only be seen with a device on the LAN. If calls from the phone fail with
floor requests unanswered while SDS works, check `docker logs <lab>-mcptt-server-1` for dropped packets, and check the
host firewall.

## Two phones and attachments

`create` takes one `--phone-device ROLE:SERIAL:IP` per phone; with `--phones-only` no redroid clients are started, and
`up` installs every phone given a serial. `--fd-max-bytes` sets up file distribution for large files. See
[ATTACHMENTS.md](ATTACHMENTS.md) for the commands and the attachment scenario (`tests/attachments.py`).

`--mcvideo` adds MCVideo (video calls) on its own published ports; see [VIDEO.md](VIDEO.md).


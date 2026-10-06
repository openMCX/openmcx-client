# The openMCX dispatcher (v1)

A dispatcher position in the browser: the MCX client engine (`mcx-client`, built from [`../linux`](../linux)) runs as
a child of a small Go web service, and the browser drives it. A dispatcher is an MC user with more rights (TS 23.280);
v1 uses only services the openMCX servers already implement and needs no server change. Options not in v1 are shown
greyed as "coming soon". The commands below run from the root of the openmcx-client repository, with openmcx-server
checked out beside it (`../openmcx-server`).

## How it is built

- **Engine**: `mcx-client` in `linux/`, one per dispatcher position. The dispatcher uses these client settings, all
  off unless configured, so a plain client behaves as without them: `media.live` (live 16 kHz PCM audio in and out
  over UDP instead of WAV files), `sip.register_expires` (a shorter registration expiry), `mcptt.group_from_profile`
  (the user profile's groups, `profile_groups` in the status) and `oauth.forget_password`.
- **Web service** (`service/`, Go, with an embedded UI): runs the engine as a child, sends it whitelisted commands
  through its control FIFO, streams its status as server-sent events and bridges the browser's audio (WebSocket) to
  `media.live`. It includes no client code. Audio is PCM over the WebSocket; the engine encodes AMR-WB.
- **Tests**: Go unit tests with a fake engine (`service/*_test.go`), and the lab scenarios and browser checks in
  `lab/` (below).

## Scope and limits

- One engine, one call at a time: the engine keeps one MCPTT call (and one MCVideo call) in one dialog. Several
  monitored groups, simultaneous group calls with a private or emergency call on top, remote affiliation, video,
  recording, a map, operator handoff and dynamic regrouping are not in v1 (greyed in the UI).
- One operator per position at a time. A sign-out does not remove the SIP registration (the shared SIP library has
  no unregister): it lapses within `register_expires`. Suspension in MCX Admin ends a session within the token
  lifetime, Revoke devices within `register_expires`.
- A dispatcher's rights are provisioning: the servers enforce them as for any MC user. The dispatcher UI creates no
  users and edits no policy; MCX Admin does.
- Server limits that matter to a dispatcher: the server applies none of the simultaneous-call limits of the user
  profile (`<MaxSimultaneousCallsN6>`, `<Max-Simul-Call-N10>`, `<Max-Simul-Call-N4>`, `<Max-Simul-Trans-N5>`), and only
  its own `private_call.max_calls` setting (default 1) limits private calls; negotiated-mode affiliation (changing
  other users' affiliations, TS 24.379 clauses 9.2.1.4 and 9.2.1.5) is not supported.
- The browser microphone and the audio socket need a secure context (below). Audio is 16 kHz mono PCM; WebRTC is not
  used.

| Area | v1 |
|---|---|
| Group call | the configured group: normal, imminent peril, emergency; hold to talk with the browser microphone; received audio played in the browser |
| Private call | call any MCPTT ID (optionally emergency); incoming calls ring and are answered or rejected |
| Emergency alert | send and cancel |
| SDS | one-to-one and group, inbox |
| File distribution | send from the browser (optionally mandatory), accept or reject received files, save them |
| Coming soon | several monitored groups, remote affiliation, video, call recording, map, operator handoff, dynamic regrouping |

## Position mode (M3): bound to MCX Admin

`--device <settings> --data <dir>` instead of `--config`. The device settings are an activation client's local
settings (no identity, no certificate) plus `media.live`, `sip.register_expires` (for example 120) and
`mcptt.group_from_profile: true`.

- **Activation.** On the sign-in page, "Activate an account on this position" takes an MCX Admin activation link and
  the account's password. The engine activates as any client does (TS 24.484 bootstrap, SIP credential and a device
  certificate for a key made here) and the operator is signed in. The account is kept under `<data>/accounts/`
  without its password. Several accounts may be activated on one position.
- **Sign-in.** Login and password; IdMS checks them when the engine signs in. The engine runs only while an operator
  is signed in, with the password in a runtime configuration on the tmpfs. One operator at a time; the browser
  holds an HttpOnly, SameSite=Strict session cookie, which every API call and the audio socket need.
- **The session ends** at sign-out, when IdMS refuses the token renewal (suspended in MCX Admin: within the token
  lifetime), when the SIP core refuses the registration refresh (Revoke devices: within `register_expires`), or when
  no page of the session has been open for `--idle-signout` (2 minutes). The page says why.
- **Groups.** The picker offers the groups of the user profile (`<MCPTTGroupInfo>`, TS 24.484) and follows MCX Admin's
  changes (xcap-diff). The position remembers each operator's last group; one the profile no longer lists gives way
  to the profile's first group.

No server change: the servers treat the position as any activated client.

## Build and run

```
linux/tools/build-image -t openmcx-client:dispatcher   # the engine; --server <path> if openmcx-server is elsewhere
docker build -t openmcx-dispatcher:disp --build-arg CLIENT_IMAGE=openmcx-client:dispatcher dispatcher
```

The engine configuration is an ordinary client configuration plus `media.live`:

```json
"media": { "...": "...", "live": { "listen": "127.0.0.1", "port": 7100, "send_host": "127.0.0.1", "send_port": 7101 } }
```

```
docker run ... openmcx-dispatcher:disp --listen 0.0.0.0:8080 --config /config/dispatcher.json
```

Options: `--listen` (default 127.0.0.1:8080), `--engine`, `--config` (lab mode) or `--device` and `--data` (position
mode), `--engine-args` (for example `--disable-tls`), `--outbox` (where uploaded files are kept for sending),
`--idle-signout`. The browser must reach the page from a secure context
(`https://` or `http://127.0.0.1`/`localhost`) to use the microphone. In lab mode there is no operator sign-in: publish the port on
localhost only. Position mode has sign-in; still serve it over TLS (a proxy) when it is reached from another host.

## API

All POSTs need the header `X-Dispatcher: 1` (a cross-site form cannot send it); the audio socket needs the page's own
origin.

| Method and path | Purpose |
|---|---|
| `GET /api/session` | position mode: signed in or not, the operator, why the last session ended, the activated accounts |
| `POST /api/activate`, `/api/login`, `/api/logout` | position mode: `{url, password}`, `{username, password}` |
| `GET /api/state` | the engine's state: registration, call and floor state, alerts, SDS inbox, files, identity |
| `GET /api/events` | the same, as server-sent events whenever it changes |
| `POST /api/command` | one action, JSON (below) |
| `POST /api/fd` | multipart: `file`, `target`, `group` (true/false), `mandatory` (true/false); sends the file |
| `GET /api/files/{message ID}` | a received file once downloaded |
| `GET /api/audio` | WebSocket, binary frames of 16 kHz 16-bit little-endian mono PCM, both ways |

Actions: `{"action":"call","kind":"normal|imminent|emergency"}`, `{"action":"private","target":"<MCPTT ID>","emergency":bool}`,
`{"action":"group","id":"<group>"}`, `answer`, `reject`, `hangup`, `ptt` (talk from the audio socket), `release`, `{"action":"alert","on":bool}`,
`{"action":"sds","target":"<MCData ID or group>","group":bool,"text":"..."}`, `{"action":"fd-accept|fd-reject|fd-defer","id":"<message ID>"}`.
Nothing else reaches the engine.

## Lab test

```
python3 ../openmcx-server/tools/prepare-prearranged-test.py --directory <lab> --project disp --private-call --mcdata \
  --image openmcx:<tag> --client-image openmcx-client:dispatcher
python3 dispatcher/lab/prepare.py <lab>                       # client-c becomes the dispatcher on 127.0.0.1:18080
../openmcx-server/.cache/deps/docker-compose-linux-x86_64 -p disp -f <lab>/compose.json up -d --wait   # openmcx-server's compose binary
python3 dispatcher/lab/scenario.py <lab> --project disp       # DSP-01..08
docker run --rm --network host -v playwright-py:/py -v <lab>:/lab -v $PWD/dispatcher/lab:/check -w /check \
  mcr.microsoft.com/playwright/python:v1.55.0-noble sh -c \
  '[ -d /py/playwright ] || pip install -q --break-system-packages --target /py playwright==1.55.0; PYTHONPATH=/py python3 browser_check.py /lab'
```

Tear the lab down afterwards (`down -v`).

Position mode, in an MCX Admin lab (client-e's slot becomes the position):

```
python3 ../openmcx-server/tools/prepare-prearranged-test.py --directory <lab> --project m3 --admin \
  --admin-image openmcx-admin:<tag> --token-ttl 60 --image openmcx:<tag> --client-image openmcx-client:<tag>
python3 dispatcher/lab/prepare.py <lab> --position --image openmcx-dispatcher:m3
<compose> -p m3 -f <lab>/compose.json up -d --wait
python3 dispatcher/lab/position_scenario.py <lab> --project m3 --server ../openmcx-server     # DM-01..11
python3 dispatcher/lab/browser_position.py prepare <lab> --project m3 --server ../openmcx-server
docker run ... browser_position.py check /lab                                               # BP-01..07
```

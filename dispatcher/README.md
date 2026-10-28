# The OpenMCX dispatcher (v1)

A dispatcher position in the browser: the MCX client engine (`mcx-client`, built from [`../linux`](../linux)) runs as
a child of a small Go web service, and the browser drives it. A dispatcher is an MC user with more rights (TS 23.280);
v1 uses only services the OpenMCX servers already implement and needs no server change. Options not in v1 are shown
greyed as "coming soon". The commands below run from the root of the openmcx-client repository, with openmcx-server
checked out beside it (`../openmcx-server`).

## How it is built

- **Engine**: `mcx-client` in `linux/`, one per dispatcher position. The dispatcher uses these client settings, all
  off unless configured, so a plain client behaves as without them: `media.live` (live 16 kHz PCM audio in and out
  over UDP instead of WAV files), `sip.register_expires` (a shorter registration expiry), `mcptt.group_from_profile`
  (the user profile's groups, `profile_groups` in the status), `oauth.forget_password`, and `oauth.authorization` with
  `--authorization-request` (sign-in on IdMS's page, below).
- **Web service** (`service/`, Go, with an embedded UI): runs the engine as a child, sends it whitelisted commands
  through its control FIFO, streams its status as server-sent events and bridges the browser's audio (WebSocket) to
  `media.live`. It includes no client code. Audio is PCM over the WebSocket; the engine encodes AMR-WB.
- **Tests**: Go unit tests with a fake engine (`service/*_test.go`), and the lab scenarios and browser checks in
  `lab/` (below).

## Scope and limits

- One engine, one call at a time: the engine keeps one MCPTT call (and one MCVideo call) in one dialog. Several
  monitored groups, simultaneous group calls with a private or emergency call on top, remote affiliation, video,
  recording, a map and operator handoff are not in v1 (greyed in the UI).
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
| Group call | the configured group: normal, imminent peril, emergency, broadcast; a chat group's call joined (or started), normal, imminent peril or emergency; during the call, raise it to emergency or imminent peril and back to normal; hold to talk with the browser microphone; received audio played in the browser |
| Private call | call any MCPTT ID (optionally emergency, optionally without floor control); raise it to emergency and back; incoming calls ring and are answered or rejected; call-back asked of a user (low, normal or high urgency) and cancelled, call-backs asked of the dispatcher listed and fulfilled |
| Call services | first-to-answer call to two or more users (optionally emergency); ambient listening, listening to a user or listened to by one; ask a user to place a private call (commencement mode, optionally without telling them) or a group call; transfer the private call in progress, unannounced or announced (complete or cancel); forward a ringing private call; ad hoc group call to the users listed (normal, broadcast, imminent peril or emergency), users added and removed during it; the group call's participants from its conference events |
| Functional aliases | MCPTT and MCData: activate, take over (where allowed) and deactivate; one presented on the dispatcher's calls, another on its messages and files; private and first-to-answer calls, SDS and files to an alias; the caller's or sender's alias, and the alias called, shown |
| Emergency alert | MCPTT and MCData: send and cancel its own; cancel another user's (with the right to) |
| SDS | one-to-one and group, inbox; SDS sessions (one-to-one, group or ad hoc group, the first two optionally emergency or imminent peril) opened, used (delivery or read notification asked) and closed, an ad hoc one for every participant; sessions offered to the dispatcher joined; enhanced status: a group's operational values sent, received ones listed |
| File distribution | send from the browser (optionally mandatory), accept or reject received files, save them |
| Message store | storage of its private and group communications enabled and disabled; the stored objects synchronised, read, flagged (read, flagged), moved or copied into folders, searched, deleted; folders created, opened and deleted; files from the browser stored; notifications of changes |
| Regroup | group regroup and user regroup using a preconfigured group, created and removed; temporary groups formed and torn down at the group management server; calls on them from the group picker |
| Location | the location of users, or of the user holding a functional alias, asked (optionally a fresh report); the last report of each user listed with its position, linked to a map; users told to report every so many seconds |
| IP connectivity | a session with a user, or with the user holding a functional alias, opened and released; sessions offered to the dispatcher accepted; the built-in IP application pings the other end's tunnel address and answers its pings, with round-trip times and packet counters |
| Monitored groups | other groups of the user profile affiliated besides the selected one; their calls reach the dispatcher, named by their group; each group's affiliation status, priority and emergency alerts shown; a call on one at a time; not with protected media (a call needs its own group key) |
| Coming soon | remote affiliation, video, call recording, map, operator handoff |

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

## Sign-in on IdMS's page (lab mode)

For an IdMS whose login page the dispatcher cannot fill in, Keycloak for example, a lab mode
configuration with `"oauth": {"login": "browser", ...}` and no password signs the operator in on IdMS's own page
(TS 33.180 annex B.4.2, the authorization code grant with PKCE S256 and `acr_values=3gpp:acr:password`):

- The page shows **Sign in with IdMS**. The service runs `mcx-client --authorization-request`, which builds the
  authorization request from the IdMS discovery document, keeps its state, nonce and code verifier, and sends the
  browser to IdMS. A cookie binds the request to that browser (5 minutes, used once).
- IdMS sends the browser back to `oauth.redirect_uri`, which must be this service's `/api/login/callback` at the
  address the browser opens (for example `http://127.0.0.1:8080/api/login/callback`), registered at IdMS. The service
  checks the state and starts the engine with the code in `oauth.authorization`; the engine exchanges it and verifies
  the ID token (signature, issuer, audience, nonce; `mcptt_id`, and `mcdata_id` and `mcvideo_id` where IdMS gives
  them, against the configuration).
- Renewals use the refresh token alone, held in memory. IdMS refusing it, sign-out, the engine ending or no open
  page for `--idle-signout` end the session, as in position mode. An IdMS that issues no refresh token ends the
  session when the first access token expires. A refresh answer may have no ID token, and its ID token no nonce
  (TS 33.180 table B.5.3-1, OpenID Connect Core 1.0 section 12.2), as Keycloak's; the access token lives for
  `expires_in` (annex B.12).
- The scope names the configured services (`3gpp:mc:ptt_service`, `data_service`, `video_service`, their
  configuration and group management scopes, and key management with `security`); `oauth.scope` overrides it.
- The engine trusts the IdMS certificate if `tls.ca_file` or the system's CAs (`/etc/ssl/certs`) issued it: an IdMS
  with a certificate of a public CA needs nothing; for one of a private CA, add that CA to `tls.ca_file`, and
  to the trust store of the operator's browser, which opens the IdMS page. The other servers are trusted through
  `tls.ca_file` only.
- The page names the IdMS (the issuer's host) on the sign-in form and, once signed in, beside the operator.

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
`--idle-signout`, `--ops-agent <functional alias or MCData ID>`, `--ops-agent-name` (default "Ops agent") and
`--ops-agent-alias` (default true; false when `--ops-agent` is an MCData ID). The browser must reach the page from a secure context
(`https://` or `http://127.0.0.1`/`localhost`) to use the microphone. In lab mode there is no operator sign-in: publish the port on
localhost only. Position mode has sign-in; still serve it over TLS (a proxy) when it is reached from another host.

## The client page (`/client/`)

Besides the dispatcher position at `/`, the service serves an MC user's client at `/client/`, drawn as the Android
app (its colours, top strip, tabs, floor states and PTT key, in light and dark): the same engine, API and audio.

- **Voice**: call a user by name (`alice` is `sip:alice.mcptt@<your domain>`) or full MCPTT ID, recent users one tap
  away; the groups of the user profile with their affiliation, a call on one (normal, broadcast, imminent peril,
  emergency), private calls (ring first, without floor control, emergency); the call in progress with its floor
  state, a press-and-hold PTT key (or Space), emergency and imminent peril, speaker and hang up; incoming calls ring.
- **Video**: watch a user's camera (an MCVideo private call, by name or MCVideo ID) or a group's video (a group call
  on a group of the profile); the video received is decoded in the browser (WebCodecs, H.264 Constrained Baseline),
  with a live badge, the picture size and frame rate, full screen, and a note when the call ends. The page sends no
  video. Needs `mcvideo.live_receive` in the engine configuration (e.g. `{"send_host": "127.0.0.1", "send_port":
  7102}`): the pictures reach the page over `/api/video`. A call that starts brings its tab forward, and a dot on the
  Voice or Video tab shows a call in progress.
- **Chat**: conversations over MCData SDS with users, groups and functional aliases, the ops agent first
  (`--ops-agent`): sent messages ask for delivery and read reports, shown as ticks; a received message on screen is
  marked read (`sds-display`); line breaks kept; between the agent's read report and its answer, "working on it".
  Conversations are kept in the browser (the engine keeps its last 50 messages each way).
- **Logs**: the engine's status and the page's actions.

```
docker run ... openmcx-dispatcher:disp --listen 0.0.0.0:8080 --config /config/client.json \
  --ops-agent sip:ops-bot.fa@example.org          # then open http://127.0.0.1:8080/client/
```

The ops agent is any MCData user or alias; [`../agent`](../agent) (`mcx-agent`) is the plumbing to build one.

## API

All POSTs need the header `X-Dispatcher: 1` (a cross-site form cannot send it); the audio socket needs the page's own
origin.

| Method and path | Purpose |
|---|---|
| `GET /api/session` | position mode (`mode` position or browser): signed in or not, the operator, why the last session ended, the activated accounts |
| `GET /api/login/browser`, `GET /api/login/callback` | sign-in on IdMS's page: to IdMS, and IdMS's redirect back (`code`, `state` or `error`) |
| `POST /api/activate`, `/api/login`, `/api/logout` | position mode: `{url, password}`, `{username, password}` |
| `GET /api/state` | the engine's state: registration, call and floor state, alerts, SDS inbox, files, identity |
| `GET /api/events` | the same, as server-sent events whenever it changes |
| `POST /api/command` | one action, JSON (below) |
| `POST /api/fd` | multipart: `file`, `target`, `group` (true/false), `to_alias` (true/false), `mandatory` (true/false); sends the file |
| `GET /api/files/{message ID}` | a received file once downloaded |
| `POST /api/msgstore/upload` | multipart form: `file`, optional `folder` (folder ID); stored in the message store as a new object |
| `GET /api/audio` | WebSocket, binary frames of 16 kHz 16-bit little-endian mono PCM, both ways |

Actions: `{"action":"call","kind":"normal|imminent|emergency|broadcast","chat":bool}` (`chat`: the selected chat
group's call, no broadcast), `{"action":"callback","target":"<MCPTT ID>","urgency":"low|normal|high"}`,
`{"action":"callback-cancel|callback-fulfil","target":"<MCPTT ID>"}`,
`{"action":"private","target":"<MCPTT ID>","emergency":bool,"no_floor":bool,"to_alias":bool}` (`to_alias`: the
target is a functional alias; not with `emergency`),
`{"action":"priority","level":"emergency|imminent","on":bool}` (the call in progress), `{"action":"group","id":"<group>"}`,
`{"action":"monitor","groups":["<group>",...]}` (the groups affiliated besides the selected one; `[]` stops),
`answer`, `reject`, `hangup`, `ptt` (talk from the audio socket), `release`, `{"action":"alert","on":bool,"user":"<MCPTT ID>"}`
(`user`: another user's alert to cancel), `{"action":"mcdata-alert","on":bool,"user":"<MCData ID>","group":"<group>"}`
(`user` and `group`: another user's MCData alert to cancel), `{"action":"first-to-answer","targets":["<MCPTT ID>",...],"emergency":bool}`
(or `"alias":"<functional alias>"` in place of `targets`, not with `emergency`),
`{"action":"fa","op":"activate|takeover|deactivate","aliases":["<functional alias>",...]}`,
`{"action":"fa-use","alias":"<functional alias>"}` (`""`: none), `{"action":"mcdata-fa","op":"activate|takeover|deactivate","aliases":[...]}`,
`{"action":"mcdata-fa-use","alias":"<functional alias>"}` (`""`: none),
`{"action":"location-request","targets":["<MCPTT ID>",...],"refresh":bool}` (or `"alias":"<functional alias>"`),
`{"action":"location-configure","targets":[...],"periodic":<seconds>}`,
`{"action":"regroup","op":"create|users","tgi":"<new group ID>","preconfigured":"<group>","members":["<group or MCPTT ID>",...]}`,
`{"action":"regroup","op":"remove","tgi":"<group ID>"}`,
`{"action":"temporary","op":"create","tgi":"<new group ID>","groups":["<group>",...],"chat":bool,"protected":bool}`,
`{"action":"temporary","op":"delete","tgi":"<group ID>"}` (the document is named after the group ID's user part),
`{"action":"msgstore","op":"sync|folders|subscribe|unsubscribe"}`,
`{"action":"msgstore","op":"get|payload|retrieve|folder|folder-delete","id":"<object or folder ID>"}`,
`{"action":"msgstore","op":"delete","ids":[...]}`, `{"action":"msgstore","op":"flag|unflag|bulk-flag","ids":[...],"flag":"\\Seen"}`,
`{"action":"msgstore","op":"search","criteria":["<name>=<value>","flag=<flag>"]}`,
`{"action":"msgstore","op":"folder-search|folder-create","name":"<name>","parent":"<folder ID>"}`,
`{"action":"msgstore","op":"move|copy|move-folder|copy-folder","folder":"<folder ID>","ids":[...]}`,
`{"action":"msgstore","op":"restart","index":<n>}`,
`{"action":"msgstore-ctrl","op":"enable|disable","all_private":bool,"all_group":bool,"private":[...],"group":[...]}`,
`{"action":"ambient","kind":"remote|local","target":"<MCPTT ID>"}`,
`{"action":"remote-call|remote-group-call","remote":"<MCPTT ID>","target":"<MCPTT ID or group>","mode":"auto|manual|force-auto","silent":bool}`,
`{"action":"transfer","target":"<MCPTT ID>","step":"announced|complete|cancel"}` (no `step`: unannounced),
`{"action":"forward","target":"<MCPTT ID>"}`,
`{"action":"adhoc","targets":["<MCPTT ID>",...],"kind":"normal|broadcast|imminent|emergency"}`,
`{"action":"adhoc-modify","add":[...],"remove":[...]}` (the ad hoc group call in progress),
`{"action":"sds","target":"<MCData ID, group or functional alias>","group":bool,"to_alias":bool,"report":"none|delivery|read|both","text":"..."}`
(1 to 1000 characters; line breaks are kept, sent with the engine's `escaped` option),
`{"action":"sds-display","id":"<received message ID>"}` (the message shown: the read notification its sender asked for),
`{"action":"sds-session","op":"open","target":"<MCData ID or group>","group":bool,"priority":"normal|emergency|imminent"}`
(or `"adhoc":true,"targets":[...]`, normal only), `{"action":"sds-session","op":"send","text":"...","disposition":"delivery|read"}`,
`{"action":"sds-session","op":"close","all":bool}` (`all`: an ad hoc group communication, for every participant),
`{"action":"status-values","group":"<group>"}`, `{"action":"status","group":"<group>","value":"<operational value ID>"}`,
`{"action":"ipcon","target":"<MCData ID>"}` (or `"alias":"<functional alias>"`; `"close":true` releases it),
`{"action":"ipcon-ping","address":"<IPv4 address in the tunnel>","count":1-100,"size":0-1400}`,
`{"action":"fd-accept|fd-reject|fd-defer","id":"<message ID>"}`.
Nothing else reaches the engine.

## Lab test

```
python3 ../openmcx-server/tools/prepare-prearranged-test.py --directory <lab> --project disp --private-call --mcdata \
  --priority --broadcast --chat --functional-alias --msgstore --adhoc \
  --image openmcx:<tag> --client-image openmcx-client:dispatcher
python3 dispatcher/lab/prepare.py <lab>                       # client-c becomes the dispatcher on 127.0.0.1:18080,
                                                              # with the lab's widest MCPTT and MCData rights
                                                              # (and every functional alias, with take-over;
                                                              # ad hoc group calls, changing their participants)
../openmcx-server/.cache/deps/docker-compose-linux-x86_64 -p disp -f <lab>/compose.json up -d --wait   # openmcx-server's compose binary
python3 dispatcher/lab/scenario.py <lab> --project disp       # DSP-01..08
python3 dispatcher/lab/actions_scenario.py <lab> --project disp   # DSP-09..16; the lab with --priority --broadcast --chat
python3 dispatcher/lab/services_scenario.py <lab> --project disp  # DSP-17..24
python3 dispatcher/lab/fa_scenario.py <lab> --project disp        # DSP-25..30; the lab with --functional-alias
python3 dispatcher/lab/store_scenario.py <lab> --project disp     # DSP-36..45; the lab with --msgstore
python3 dispatcher/lab/adhoc_scenario.py <lab> --project disp     # DSP-46..48; the lab with --adhoc
python3 dispatcher/lab/session_scenario.py <lab> --project disp   # DSP-49..52
python3 dispatcher/lab/mcdata_fa_scenario.py <lab> --project disp # DSP-53..56
python3 dispatcher/lab/ipcon_scenario.py <lab> --project disp     # DSP-60..64
python3 dispatcher/lab/monitor_scenario.py <lab> --project disp   # DSP-65..68
docker run --rm --network host -v playwright-py:/py -v <lab>:/lab -v $PWD/dispatcher/lab:/check -w /check \
  mcr.microsoft.com/playwright/python:v1.55.0-noble sh -c \
  '[ -d /py/playwright ] || pip install -q --break-system-packages --target /py playwright==1.55.0; PYTHONPATH=/py python3 browser_check.py /lab'
```

Regroups need their own lab, whose groups are the regroup's constituents: `--private-call --mcdata --regroup`, then
`regroup_scenario.py` (DSP-31..35) and `browser_regroup.py` in place of `browser_check.py`. Location likewise, as its
group areas move affiliations: `--private-call --mcdata --location`, then `location_scenario.py` (DSP-57..59) and
`browser_location.py`.

Sign-in on IdMS's page: `prepare.py <lab> --browser-login` (the lab's IdMS registers the callback), then, with the
browser mapping the issuer host to IdMS's container:

```
docker run --rm --network host --add-host idms:$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' disp-idms-1) \
  -v playwright-py:/py -v <lab>:/lab -v $PWD/dispatcher/lab:/check -w /check mcr.microsoft.com/playwright/python:v1.55.0-noble \
  sh -c 'PYTHONPATH=/py python3 browser_idms.py /lab'                # BL-01..07; with token_ttl_seconds 90 in site.json,
                                                                     # add "http://127.0.0.1:18080/ client-c 120" for BL-05b
```

With Keycloak as IdMS (openmcx-server `prepare-prearranged-test.py --keycloak <image>`, which docs/USAGE.md of
openmcx-server describes), the same check runs on Keycloak's login page: `prepare.py --browser-login` registers the
callback at Keycloak's client too, and the dispatcher is left out of `keycloak-sign-in.py` (`client-a client-b
client-d`). Keycloak keeps the browser on its page after a wrong password (BL-03).

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

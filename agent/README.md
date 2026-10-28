# mcx-agent: MCData, voice and video for AI agents

`mcx-agent` lets an AI agent (or a script) take part in an OpenMCX MC system as an MC user:

- **MCData**: one-to-one short data (SDS) and files, for a chat agent;
- **voice (MCPTT)**: the talk bursts it hears in private and group calls, and speech it says in them, for a voice
  agent users talk to with push to talk;
- **video (MCVideo)**: pictures it streams, transmitted in the private and group calls it is in, for a drone or a robot
  that shows what its camera sees.

It runs the Linux client `mcx-client` as its engine, like the [dispatcher](../dispatcher) does, and offers the same
tools to agents and shells: an **MCP server** (over HTTP, or stdio) and a **CLI**; video pictures go to an HTTP
endpoint beside MCP. The agent itself (speech to text, a model, text to speech, the camera) is yours: mcx-agent is the
plumbing. Group SDS is outside its reach. **[API.md](API.md)** is the contract an agent builds on (version 1.1.0):
the transports, the tools, their results and error codes. **[FIRST_AGENT.md](FIRST_AGENT.md)** builds an MCData agent
on it step by step.

```
agent (other container) ──MCP over HTTP, token──────────────▶ mcx-agent daemon ──control FIFO / status / events──▶ mcx-client
                        ──POST /video, I420 pictures, token──▶   │  ▲          ──pictures FIFO (mcvideo.live)──────▶
agent ──stdio MCP──▶ mcx-agent mcp ──┐                            │  │          ◀──talk bursts as WAV (media.speech_dir)──
shell ─────────────▶ mcx-agent <tool> ┴──────── unix socket ───────┘  │
```

The engine answers nothing on its control FIFO, so the daemon makes each tool a transaction: it writes one command at
a time and waits for what follows it (an engine event, a change in the status file, or a `client_error`). It keeps
every message in its own store (`<data_dir>/store.json`), because the engine keeps only the last 50 of each.

## Tools

| Tool | What it does |
|---|---|
| `status` | Whether the agent can send, its MCData ID, its functional alias, unread count |
| `send_message` | One-to-one SDS (`sds`); asks for delivered/read reports; waits until sent, delivered or read |
| `reply_message` | Reply in a received message's conversation (`sds-reply`); asks for reports and waits like `send_message` |
| `message_status` | Network answer, delivered, read, file state of a sent or received message |
| `inbox` | Received one-to-one messages and file offers; `since` reads on from a sequence number |
| `wait_message` | Waits for the next received message or file offer |
| `mark_read` | Marks a message read and sends the read report when the sender asked for one (`sds-display`); reading the inbox never does |
| `send_file` | File distribution over HTTP (`fd-send`) of a file in the outbox directory |
| `respond_file` | Accept (download), reject or defer a file offer (`fd-accept`, `fd-reject`, `fd-defer`) |
| `call_status` | The MCPTT call: idle, calling or active, its type, group or peer, who has the floor, whether the agent speaks |
| `wait_speech` | Waits for the next talk burst heard in a call: who spoke, in which call, the speech as a WAV (base64) |
| `speak` | Says a WAV in the call: asks for the floor, sends it as a talk burst (`ptt <wav>`), releases the floor at its end |
| `stop_speaking` | Ends the agent's talk burst at once (`release`) |
| `hangup` | Leaves or ends the MCPTT call the agent is in |
| `video_status` | The MCVideo call and transmission, and the feed of pictures: the format `POST /video` takes, what arrived |
| `video_transmit`, `video_stop`, `video_hangup` | Asks for the permission to transmit in the call the agent is in, ends the transmission, leaves the call |

`mcx-agent tools` lists the arguments. In the CLI, `--name value`; tool and argument names take `-` or `_`; the
result is JSON on stdout. Text may have line breaks: the daemon passes it to the engine with its `escaped` option
(`\n`, `\t` and `\\` on the command line).

```bash
mcx-agent send-message --to sip:alice.mcdata@example.org --text "unit 4 report position" --wait delivered
mcx-agent wait-message --timeout-s 120
mcx-agent mark-read --message-id 6f1c…
```

## Voice: MCPTT for a voice agent

With `voice.enabled`, the engine writes the speech of every talk burst it receives as a WAV file (16 kHz, 16-bit,
mono) and reports it with the talker: the floor owner of a group call, the peer of a private call. A burst ends when
the floor passes on or goes idle (without floor control, after 1.5 s without speech). The daemon keeps the last
`voice.keep` (200) with their files in `<data_dir>/speech`; `wait_speech` returns each with its audio inline.
Push to talk is a turn-taking conversation, so the agent works in bursts: hear one, answer it with `speak`.

- Calls to the agent are answered at once (`mcptt.answer_mode` auto unless the engine configuration says otherwise);
  the group calls of the groups the agent is affiliated to reach it as they reach any member. The MC system decides
  who may call the agent (the users' profiles), as for any user.
- `speak` takes a 16-bit PCM WAV of any rate, mono or stereo (base64, or a file in the outbox), converts it to 16 kHz
  mono, waits for another talker to finish (`wait_idle`), asks for the floor, and sends it; the floor is released at
  its end. The outcome says whether it was said: `spoken`, `denied`, `revoked` (a talker of higher priority took the
  floor), `floor_busy`, `no_grant`, `call_ended`. At most `voice.max_speech_s` (60) seconds.
- The agent makes no calls of its own: it answers calls to it and joins its groups' calls, and can leave them
  (`hangup`).

```bash
mcx-agent wait-speech --audio none --timeout-s 120     # who spoke, where; the WAV's file in the container
mcx-agent speak --file answer.wav                      # a WAV in the outbox
```

[`examples/voice-bot.py`](examples/voice-bot.py) is the hello-world voice agent: it plays back every burst it hears,
after a short tone. Its `respond()` is where speech to text, a model and text to speech go.

## Video: MCVideo for a camera agent

With `video.enabled`, the engine transmits the agent's own pictures in place of its test pattern: the agent streams
them to `POST /video` (MCP over HTTP's listener, with its token; or the unix socket, `mcx-agent video-feed`), raw I420
pictures of `video.width` x `video.height` (320 x 240) one after another, and while the agent has the permission to
transmit the engine encodes the latest one at each frame (`video.frame_rate`, 15) as H.264 at `video.bitrate`
(300 kbit/s). Before the first picture it sends black, and when the stream stops its last picture goes on. The
video other users transmit is not received: a drone does not watch.

- The agent makes no calls of its own: a user calls it (a private MCVideo call) or starts a group call of its
  groups, which it joins; calls are answered at once. `video_transmit` asks for the permission to transmit in them
  (or `video.auto_transmit`, as soon as it is in one).
- Any camera works through ffmpeg: `ffmpeg -i rtsp://drone/live -vf scale=320:240 -r 15 -pix_fmt yuv420p -f rawvideo
  - | curl -T - -H "Authorization: Bearer $TOKEN" http://mcx-agent:8765/video`; `?pace=true` plays a recorded file
  at the frame rate.

```bash
ffmpeg -i /dev/video0 -vf scale=320:240 -r 15 -pix_fmt yuv420p -f rawvideo - | mcx-agent video-feed
mcx-agent video-transmit                               # in the call a user made to the agent
```

With `video.receive` the agent also watches: the video others transmit in its calls streams as H.264 from
`GET /video/received` (e.g. `curl -H "Authorization: Bearer $TOKEN" "http://mcx-agent:8765/video/received?seconds=10"
> clip.h264`), for a model that describes it; `video_status.transmitter` says who sends.

[`examples/video-bot.py`](examples/video-bot.py) is the hello-world camera agent: it streams a generated test card
(or, with `--source`, any ffmpeg input) and transmits in every call it is in.

## Errors

A failed tool gives a stable code (`not_allowed`, `alias_inactive`, `rate_limited`, `send_failed`, …) besides its
message, and the CLI an exit status by kind: 2 a usage error, 3 refused by the agent's policy, 4 not ready, 1 any
other failure. The codes and what to do about each: [API.md](API.md#errors).

## Received messages

Only one-to-one SDS and file offers reach the agent; group and ad hoc group SDS are left out. A message sent to the
agent's functional alias shows it in `to_alias`. Text payloads only: other content types arrive with an empty `text`.
Each message also says what the agent may want to decide on: `emergency` and `imminent_peril` (sent as such a
communication), `used_priority` (the priority the server applied), `authenticated` (its signature verified) and
`protected` (end-to-end protected).

## Functional alias

Optional: without `alias`, the agent sends and receives on its own MCData ID. With `alias` set, the daemon activates it once the engine is authorised for MCData (`mcdata-fa activate`), and once it
is active presents it on everything the agent sends (`mcdata-fa use`). It never takes over an alias another user
holds. While the alias is not active (refused, take-over possible, or taken over later), sending is refused, unless
`require_alias` is false; `status` says why. A lost or refused alias is tried again every `alias_retry_s` (30 s), so
the agent gets it back once its holder lets it go.

## Safety

- Who the agent may send to: the `recipients` (MCData IDs with `*` wildcards, `sip:*.mcdata@example.org`), and with
  `allow_senders_hours` set, anyone who wrote to the agent within that many hours. A chat agent needs only the second:
  it answers whoever writes to it and cannot start a conversation. With neither, the agent sends to no one.
- Every ID must be a single-word SIP URI and a message ID a UUID before it reaches a command line; text is printable
  UTF-8 with line breaks and tabs, at most `max_text_bytes` (1000, never more than 3000). The server's own limits on
  the size of an SDS still apply (`send_failed` with its Warning).
- `rate_per_recipient_per_minute` (10) limits sends to one user, `rate_per_minute` (60) all sends and files together.
- Files are sent only from `outbox_dir` (`<data_dir>/outbox`): plain names, no symbolic links out of it.
- The agent never makes a call: no tool starts one. It answers calls to it and joins its groups' calls; who may
  call it is the MC system's decision (the users' profiles).
- No emergency or imminent-peril priority, no alerts, no raw engine commands.
- `<data_dir>/audit.jsonl` records every call of a tool that acts on the network, with its arguments and outcome
  (the audio of `speak` only by its size).
- The socket is owner only (0600); credentials stay in the engine's configuration, out of the agent's reach.

## Configuration

`agent.json` ([example](agent.example.json)):

| Key | Meaning |
|---|---|
| `engine.path`, `engine.config`, `engine.args` | `mcx-client` (default `/usr/local/bin/mcx-client`), its configuration, and further arguments (e.g. `--disable-tls`); the configuration needs `control_fifo` and `status_file`; MCData is used when it has `mcdata.user_id`, video needs its `mcvideo` part |
| `socket` | The daemon's unix socket (default `/run/mcx-agent/agent.sock`; clients also read `MCX_AGENT_SOCKET`) |
| `data_dir` | Store and audit log |
| `outbox_dir` | Where files are sent from |
| `alias`, `require_alias`, `alias_retry_s` | The functional alias to send as; refuse to send without it (default true when an alias is set); how often a lost alias is tried again |
| `recipients`, `allow_senders_hours`, `rate_per_recipient_per_minute`, `rate_per_minute`, `max_text_bytes` | See Safety |
| `mcp_http` | `{"listen", "token_file", "allowed_origins"}`: MCP over HTTP at `/mcp`, with the bearer token of `token_file` ([API.md](API.md#mcp-over-http)); also `/video` |
| `voice` | `{"enabled", "max_speech_s", "keep"}`: MCPTT for a voice agent (off); the longest speech it may say (60 s); the talk bursts kept (200) |
| `video` | `{"enabled", "width", "height", "frame_rate", "bitrate", "auto_transmit", "receive", "receive_port"}`: MCVideo for a camera agent (off); the pictures' size (320 x 240, even, at most 1280 x 720) and rate (15, at most 30), the H.264 bit rate (300000); ask to transmit in every call; also take the video others transmit, on `GET /video/received` (the engine sends it to `receive_port`, 7202, on the loopback) |

With voice or video on, the daemon runs the engine on a copy of its configuration in `<data_dir>/engine.json` with
the agent's media settings: `media.speech_dir`, `mcvideo.live` (a FIFO in `data_dir`), automatic answering, no
call recording, no video reception.

Downloads land where the engine puts them: `downloads/` beside its status file.

## Build, run and connect an agent

```bash
linux/tools/build-image -t openmcx-client:<tag>
docker build -t openmcx-agent:<tag> --build-arg CLIENT_IMAGE=openmcx-client:<tag> agent
docker run -d --name mcx-agent -v $PWD/agent-config:/etc/mcx-agent:ro -v mcx-agent-data:/var/lib/mcx-agent \
  openmcx-agent:<tag>
```

An agent in another container connects to MCP over HTTP (`mcp_http`): **[examples/compose](examples/compose)** is
the template, with the three example bots as the agents on a network of their own. Claude Code, over HTTP or the container's
stdio:

```bash
claude mcp add --transport http mcx http://<host>:8765/mcp --header "Authorization: Bearer $(cat token)"
claude mcp add mcx -- docker exec -i mcx-agent mcx-agent mcp
```

The server speaks MCP (protocol versions 2024-11-05 to 2025-06-18) and offers tools only.

## Hello world: the example bots

In [`examples/`](examples) (Python standard library only, in the image under `/usr/local/share/mcx-agent/examples/`),
one bot per service, each a complete agent:

| Bot | Service | What it does | Transports |
|---|---|---|---|
| [`chat-bot.py`](examples/chat-bot.py) | MCData | Marks each message read and answers `time`, `ping` and `help` in the sender's conversation ([FIRST_AGENT.md](FIRST_AGENT.md) builds it step by step) | MCP over HTTP |
| [`voice-bot.py`](examples/voice-bot.py) | MCPTT | Plays each talk burst back after a tone, in private calls to it and its groups' calls | CLI, MCP on stdio (`--mcp`), MCP over HTTP (`--mcp-url`, `--token-file`) |
| [`video-bot.py`](examples/video-bot.py) | MCVideo | Shows a test card (or any ffmpeg input, `--source`) in every video call it is in | MCP over HTTP, and `POST /video` |

The voice and video bots share [`mcxagent.py`](examples/mcxagent.py), a small client of the three transports. In the
daemon's container, through the CLI:

```bash
docker exec -d mcx-agent python3 /usr/local/share/mcx-agent/examples/voice-bot.py --outbox /var/lib/mcx-agent/outbox
```

## Tests

The lab scenario `mcdata_agent_scenario` in openmcx-server runs the agent against the real servers, with the echo
bot in another client's container over MCP on HTTP (lab `mcdata-agent`, the openmcx-agent image as the client
image):

```bash
docker build -t openmcx-agent:<tag> --build-arg CLIENT_IMAGE=openmcx-client:<tag> agent
cd ../openmcx-server && tools/regress.py --images <tag> --client-image openmcx-agent:<tag> --select =mcdata-agent
```

The lab scenario `voice_video_agent_scenario` (lab `voice-video-agent`) runs a voice and video agent the same way,
with the voice bot and the camera agent in another client's container: private and group calls of users who talk
to the bot and hear its answers, and private and group MCVideo calls of users who decode the agent's pictures:

```bash
cd ../openmcx-server && tools/regress.py --images <tag> --client-image openmcx-agent:<tag> --select =voice-video-agent
```

`go test` checks the tools against the committed contract (`service/testdata/tools_snapshot.json`; after a
deliberate change, raise `version` in `main.go` and run `UPDATE_SNAPSHOT=1 go test -run TestToolsSnapshot ./...`),
the MCP transports, and runs the daemon against a fake engine ([`service/testdata/fake-engine`](service/testdata/fake-engine)) that
writes the MCData status keys and events the daemon reads; the image build runs them.

```bash
docker run --rm --user "$(id -u):$(id -g)" -e HOME=/tmp -v "$PWD/agent/service:/src" -w /src openmcx-dev:0.3 go test -race ./...
```

## Limits

- One command at a time: a send takes the command lock until the engine reports it sent (milliseconds), then waits
  for the answer and reports without it.
- A `client_error` from something else (a token renewal, say) while a command is outstanding is taken for that
  command's failure.
- If more than 50 messages arrive between two status reads (200 ms), the oldest of them are missed.
- One MCPTT call and one MCVideo call at a time, as for the engine; one speech and one stream of pictures at a time.
- Speech is in talk bursts: a burst reaches the agent when it ends, not while the user speaks.
- `mark_read` on a message the engine no longer holds (its last 50) marks it read in the store only; the result says
  the report was not sent.

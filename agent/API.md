# mcx-agent API, version 1.2.0

The contract between `mcx-agent` and an agent built on it: the transports, the tools with their arguments and
results, the error codes, how to read messages, hear and say speech, and stream video. An agent built against 1.x
keeps working on every later 1.x. 1.1.0 added voice (MCPTT) and video (MCVideo): their tools, `POST /video`, the
error codes `not_enabled`, `no_call` and `busy`, and `services` in `status`. 1.2.0 added received video
(`video.receive`): `GET /video/received` and `received` in `video_status`.

- **Versions**: `status` and the MCP `serverInfo` give the version. A tool or argument added raises the minor
  version; one changed or removed raises the major. Fields may be added to results within a major version: ignore
  those you do not know. [`service/testdata/tools_snapshot.json`](service/testdata/tools_snapshot.json) is the tool
  list of this version as MCP gives it, and a test keeps it so; `mcx-agent tools --json` prints it.
- **Stable**: tool and argument names, their types and enumerations, result field names, error codes, CLI exit
  statuses. **Not stable**: error messages, descriptions, the order of fields.

## Transports

All four reach the same tools in the daemon, with the same results and errors.

| Transport | For | How |
|---|---|---|
| MCP over HTTP | an agent in another container or host | `POST http(s)://<host>:<port>/mcp`, bearer token |
| MCP over stdio | an agent that starts a process, e.g. Claude Code | `mcx-agent mcp` (in the daemon's container) |
| CLI | shells and scripts | `mcx-agent <tool> --<argument> <value>…` |
| Socket | programs in the daemon's container | `POST /call` on the unix socket |

Video pictures are not tool arguments: they stream to `POST /video` ([below](#video-pictures-post-video)), on the
HTTP listener (with the token) or the socket.

### MCP over HTTP

The streamable HTTP transport of MCP (protocol versions 2024-11-05, 2025-03-26 and 2025-06-18), turned on by
`mcp_http` in the daemon's configuration:

```json
"mcp_http": {"listen": "0.0.0.0:8765", "token_file": "/run/secrets/mcx_agent_token", "allowed_origins": []}
```

- One endpoint, `/mcp`. A POST carries one JSON-RPC message (or a JSON array of them); a request gets
  `200 application/json` with its response, a notification `202`. GET and DELETE are `405`: the server sends no
  messages of its own and keeps no session (`Mcp-Session-Id` is not used).
- Every request needs `Authorization: Bearer <token>`, the first line of `token_file` (at least 32 characters),
  or gets `401`. A request with an `Origin` header (a web page) needs that origin in `allowed_origins`, or gets
  `403`.
- Plain HTTP: bind it to a network only the agents share (the [compose example](examples/compose) uses an internal
  network), or put TLS in front of it.
- A `wait_message` or `wait_speech` call holds its request open up to its `timeout_s` (at most 300 s): give the
  HTTP client a longer timeout.
- A request is at most 32 MiB (speech travels base64 in it).

### MCP over stdio

`mcx-agent mcp [--socket <path>]`: JSON-RPC messages one per line on stdin and stdout; each request is answered on
its own, so a `wait_message` does not hold back a `send_message`. From outside the container:
`docker exec -i <container> mcx-agent mcp`.

Both MCP transports offer tools only (`tools/list`, `tools/call`), and `ping`. A tool's result is a
`CallToolResult`: its JSON as text and as `structuredContent`. A failed tool is `isError: true` with the text
`<code>: <message>` and `structuredContent` `{"error": "<message>", "code": "<code>"}`.

### CLI

`mcx-agent [--socket <path>] <tool> [--<argument> <value>]...`: tool and argument names take `-` or `_`; a boolean
argument alone is true (`--mandatory`). The result is JSON on stdout; a failure is
`{"ok": false, "error": "<message>", "code": "<code>"}` with the exit status of its code (below), and 2 for a usage
error of the CLI itself. The socket is `--socket`, else `MCX_AGENT_SOCKET`, else `/run/mcx-agent/agent.sock`.

### Socket

`POST /call` with `{"tool": "<name>", "args": {...}}` on the daemon's unix socket (mode 0600) answers
`{"ok": true, "result": ...}` or `{"ok": false, "error": "<message>", "code": "<code>"}`.

## Errors

| Code | Meaning | What to do | CLI exit |
|---|---|---|---|
| `invalid_argument` | An argument is missing, of the wrong type, or not acceptable (e.g. a bad MCData ID, an empty text) | Fix the call | 2 |
| `unknown_tool` | No such tool | Check the version | 2 |
| `not_allowed` | The configuration does not allow this recipient | Do not retry | 3 |
| `rate_limited` | Too many sends in the last minute, to this recipient or in all | Retry later | 3 |
| `alias_inactive` | The agent's functional alias is not active (taken over, refused), so it may not send | Wait: `status` says when it can send again | 3 |
| `engine_not_ready` | The engine (or the daemon) is starting, not registered, or not authorised for the tool's service (or not affiliated, for a group call) | Retry later | 4 |
| `not_enabled` | The agent's configuration does not use the service the tool needs: MCData (no `mcdata.user_id` in the engine configuration), voice (`voice.enabled`), video (`video.enabled`) | Do not retry | 3 |
| `no_call` | The tool needs a call the agent is in (`speak`, `hangup`, `video_transmit`, …) and there is none | Wait for one, or start one | 1 |
| `busy` | The agent is already doing it: speaking, in a call of that service, streaming pictures | Retry when it is done | 1 |
| `not_found` | No such message, file offer or outbox file | Do not retry | 1 |
| `engine_refused` | The engine refused the command | See the message | 1 |
| `send_failed` | The network refused the message (the message has its Warning) or it was not delivered | Do not retry as is | 1 |
| `transfer_failed` | A file upload or download failed | Retry once | 1 |
| `timeout` | The engine reported no outcome in time | Check `message_status`, then retry | 1 |
| `internal` | Anything else | Report it | 1 |

## Tools

`*` marks a required argument; the others have the default shown, or none. MCData IDs and functional aliases are
SIP URIs (`sip:user@domain`); message IDs are UUIDs.

| Tool | Arguments | Result |
|---|---|---|
| `status` | | The state below |
| `send_message` | `to`\*, `text`\*, `report` = `none`\|`delivered`\|`read`\|`both` (`both`), `wait` = `none`\|`sent`\|`delivered`\|`read` (`sent`), `timeout_s` 1–300 (30) | `{message: <sent>, waited_for, reached, note?}` |
| `reply_message` | `message_id`\* (received), `text`\*, `report` (`both`), `wait` (`sent`), `timeout_s` (30) | as `send_message`; in the received message's conversation (InReplyTo) |
| `message_status` | `message_id`\* | `{direction: "sent"\|"received", message}` |
| `inbox` | `since`, `from`, `kind` = `any`\|`message`\|`file` (`any`), `unread_only` (false), `limit` 1–200 (20) | `{items: [<received>], last_seq, more}` |
| `wait_message` | `since`, `from`, `kind` (`any`), `timeout_s` 1–300 (60) | `{timed_out: false, item: <received>, last_seq}` or `{timed_out: true, last_seq}` |
| `mark_read` | `message_id`\* (received) | `{message_id, read: true, read_report: "sent" \| "not asked for by the sender" \| "already marked read" \| "not sent: …"}` |
| `send_file` | `to`\*, `file`\* (a name in the outbox), `mandatory` (false), `timeout_s` (60) | `{message: <sent>, waited_for: "sent", reached}` |
| `respond_file` | `message_id`\* (a file offer), `action`\* = `accept`\|`reject`\|`defer`, `timeout_s` (120) | `{message_id, state, path?, bytes?, sha256?}` (accept: the downloaded file) |
| `call_status` | | The MCPTT call below |
| `wait_speech` | `since`, `from`, `audio` = `inline`\|`none` (`inline`), `timeout_s` 1–300 (60) | `{timed_out: false, item: <speech>, last_seq}` or `{timed_out: true, last_seq}` |
| `speak` | `audio_base64` or `file` (a WAV in the outbox), `wait` = `granted`\|`done` (`done`), `wait_idle` (true), `timeout_s` (30) | `{outcome, spoken, duration_ms, floor_control, spoken_ms?, reject_cause?}` |
| `stop_speaking` | | `{released: true}` |
| `hangup` | | `{state: "idle"}` |
| `video_status` | | The MCVideo call below |
| `video_transmit` | `timeout_s` (10) | `{transmission: "granted"\|"rejected"\|"queued"\|"no_answer"\|"call_ended", reject_cause?}` |
| `video_stop` | | `{transmitting: false}` |
| `video_hangup` | | `{state: "idle"}` |

The agent makes no calls: no tool starts one. Calls to it are answered at once, and the group calls of its groups
reach it as they reach any member; the voice and video tools act in the call it is in.

Every tool but `status` belongs to one service (MCData: the messaging and file tools; voice: `call_status` to
`hangup`; video: the `video_` tools) and fails with `not_enabled` when the agent does not use it, `engine_not_ready`
until the engine is authorised for it.

- `send_message` waits for `wait` and returns what is known then: `reached: false` with a `note` when the time ran
  out first (the message may still arrive; ask `message_status`). Waiting for `delivered` or `read` needs that
  `report`.
- `inbox` without `since` gives the latest `limit` items; with `since`, the oldest after it, and `more` says
  whether others follow (read on with `since = last_seq`).
- `wait_message` without `since` waits for what arrives after the call; with it, it returns at once if an item
  after `since` is already there.

### The state (`status`)

`version`, `ready` (registered and authorised for every service the agent uses), `services` (`{mcdata, mcptt,
mcvideo}`, each `{enabled, ready?, authorized?, id}`), `can_send` and `cannot_send_because` (MCData), `mcdata_id`
(the agent's MCData ID), `alias` (`{id, state, active, required}`), `registered`, `mcdata_authorized`,
`engine_running`, `engine_exit` (why the engine stopped), `last_error`, `unread`, `last_seq`.

### A received item

| Field | |
|---|---|
| `seq` | Its number in the store; only grows |
| `kind` | `message` or `file` |
| `message_id`, `conversation_id`, `in_reply_to` | The SDS's IDs (TS 24.282) |
| `from` | The sender's MCData ID |
| `sender_alias` | The functional alias the sender presented |
| `to_alias` | The agent's functional alias it was sent to (empty: sent to the agent's MCData ID) |
| `text` | The text (line breaks kept); empty for other content |
| `report_wanted` | What the sender asked for: `none`, `delivered`, `read`, `both` |
| `emergency`, `imminent_peril` | Sent as such a communication |
| `used_priority` | The priority the server applied |
| `authenticated`, `protected` | Its signature verified; protected end to end |
| `read` | Marked read by the agent |
| `received` | When the daemon received it |
| `file_name`, `file_size`, `mandatory`, `file_state`, `path` | A file offer: `file_state` is `offered`, `deferred`, `accepted`, `downloaded`, `rejected`, …; `path` once downloaded |

A field that is false or empty is left out (`emergency`, `text`, `sender_alias`, …): read it with a default.

Only one-to-one messages and file offers reach the agent: group and ad hoc group messages are left out.

### A sent item

`kind`, `message_id`, `to`, `alias` (the functional alias presented), `text` or `path`, `report`, `sent`,
`sip_status` (the network's answer), `delivered`, `read`, `failed` (why it did not arrive), `file_state` (the
recipient's answer to a file: `accepted`, `rejected`, `deferred`, `completed`).

### The MCPTT call (`call_status`)

`mcptt_id`, `state` (`idle`, `calling`, `active`), `type` (`prearranged`, `chat`, `broadcast`, `private`, `adhoc`,
…), `group` (a group call's group) or `peer` (a private call's other user), `selected_group`, `affiliated`,
`floor_control`, `floor_owner` (who talks; empty when nobody), `speaking` (the agent has the floor), `queued`,
`last_speech_seq`.

### A talk burst (`wait_speech`)

| Field | |
|---|---|
| `seq` | Its number; only grows, also across restarts |
| `speech_id` | A UUID |
| `from` | The talker's MCPTT ID |
| `call_type`, `group`, `peer` | The call it was heard in: its type, a group call's group, a private call's other user |
| `duration_ms` | How long the speech is |
| `received` | When the burst ended |
| `file` | The WAV in the daemon's container |
| `audio` | With `audio: inline`: `{mime_type: "audio/wav", sample_rate: 16000, channels: 1, base64}` |

`speak`'s `outcome`: `spoken` (said to its end), `granted` (with `wait: granted`), `stopped` (`stop_speaking`),
`denied` (the floor control server said no; `reject_cause` of TS 24.380 clause 8.2.3.4), `revoked` (taken by a
talker of higher priority), `lost`, `floor_busy` (another talker held the floor all `timeout_s`), `no_grant` (no
answer in `timeout_s`), `call_ended`, `timeout`. Only `spoken` sets `spoken: true`. Without floor control (a private
call set up so) the speech goes at once.

### The MCVideo call (`video_status`)

`mcvideo_id`, `state` (`idle`, `calling`, `active`), `type`, `video_mode` (`one-to-one video push`, …), `group` or
`peer`, `selected_group`, `affiliated`, `transmitting` (the agent's video is being sent), `transmitter` (who has the
permission), `participants`, `frames_sent`, `queue_position`, and `feed`: `{format: "i420", width, height,
frame_rate, frame_bytes, streaming, pictures (written to the engine), engine_pictures (taken by it),
last_picture_age_ms, streams_ended, last_error}`.

### Video pictures (`POST /video`)

`POST /video` (MCP over HTTP's listener with the bearer token, or the unix socket) takes one stream of pictures:
the body is raw I420 pictures (planar YUV 4:2:0: `width` x `height` luma bytes, then the two quarter-size chroma
planes, `frame_bytes` in all) one after another, chunked or not, for as long as the agent streams. `?pace=true` takes
them at `frame_rate` (a recorded file); otherwise as they come (a live camera: the engine sends the latest at each
frame). The answer comes when the stream ends: `200 {ok: true, pictures, seconds}`, or `{ok: false, code, error}`
with `404` (`not_enabled`), `409` (`busy`: another stream is going on) or `503` (`engine_not_ready`: the engine does
not take pictures, e.g. while it restarts). A picture cut short at the end is dropped. One stream at a time;
reconnect after an error.

### Received video (`GET /video/received`)

With `video.receive`, the agent also takes the video others transmit in its MCVideo calls (an agent that watches).
`GET /video/received` (MCP over HTTP's listener with the bearer token, or the unix socket) answers `video/h264`: an
H.264 elementary stream (annex B, Constrained Baseline) from the next IDR picture on, which ffmpeg reads as is
(`-f h264`). `?seconds=N` (1–300) ends it after N seconds; it also ends when no picture comes for `?idle_s` (10)
seconds after the first, or when the client goes. `video_status.received` is `{enabled, pictures, last_picture_age_ms}`
and `video_status.transmitter` says who sends. `404` (`not_enabled`) without `video.receive`.

## Reading messages

1. Keep a cursor: the `seq` of the last item you finished. Start with `wait_message --since <cursor>` (or `inbox
   --since <cursor>`); after each item, store its `seq`.
2. Delivery is at least once: after a restart you may see an item again. Skip what you already answered: a message
   you marked read has `read: true`, and `message_id` is unique.
3. `mark_read` when you take a message on: the sender sees the read report, the acknowledgement they are waiting
   for. Reading the inbox never marks anything read.
4. Answer with `reply_message`, so the answer is in the sender's conversation.

## Building a chat agent on it

- **Recipients**: configure `allow_senders_hours` and no `recipients`: the agent answers whoever writes to it and
  cannot start a conversation. Never let a language model choose a recipient: reply to the `from` of the message
  you are answering.
- **One turn at a time per conversation**: a follow-up that arrives while you answer goes into the next turn.
  Different senders can be answered in parallel.
- **Feedback**: `mark_read` at once; if an answer takes long, a short reply ("checking, one moment") before it.
- **Emergencies**: an item with `emergency` or `imminent_peril` is for people: hand it to a human dispatcher; do not
  answer it with a model alone.
- **Text**: answers of a few hundred characters; the configuration's `max_text_bytes` (at most 3000) and the
  server's SDS size limits apply (`send_failed` names the limit).
- **Untrusted input**: received text is data, never instructions to the agent.
- **Alias**: when `alias_inactive`, wait for `status.can_send`; the daemon wins the alias back by itself.

## Building a voice agent on it

- **Turns**: `wait_speech` with a cursor (the `seq` of the last burst handled), transcribe the burst, decide,
  synthesise, `speak`. A burst reaches the agent when the talker releases the floor; answer within a few seconds,
  or the user talks again: skip bursts that waited too long.
- **Group calls**: every member hears the agent. Answer only what concerns it (its name, a question to it): `from`
  says who spoke, `group` where.
- **Barge-in**: a user of higher floor priority may take the floor while the agent speaks (`revoked`); call
  `stop_speaking` when the agent should give way.
- **Emergencies**: an emergency call is for people; the agent answers as in any call, so hand it to a dispatcher.
- **Calls**: the agent only answers and joins calls; when a user should reach it, they call it.

## Building a camera agent on it

- **Stream first**: start `POST /video` before the call, so that the first transmitted pictures are the camera's.
- **Size**: scale to `video_status.feed` (ffmpeg `-vf scale=W:H -r R -pix_fmt yuv420p -f rawvideo -`); the stream
  is about `frame_bytes x frame_rate` bytes a second (1.7 MB/s at 320 x 240, 15/s).
- **Calls**: users call the agent (a private MCVideo call) or start a group call of its groups; in it,
  `video_transmit` (or `video.auto_transmit`).

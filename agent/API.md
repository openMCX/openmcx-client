# mcx-agent API, version 1.0.0

The contract between `mcx-agent` and an agent built on it: the transports, the tools with their arguments and
results, the error codes, and how to read messages. An agent built against 1.x keeps working on every later 1.x.

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
- A `wait_message` call holds its request open up to its `timeout_s` (at most 300 s): give the HTTP client a longer
  timeout.

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
| `engine_not_ready` | The engine (or the daemon) is starting, not registered, or not authorised for MCData | Retry later | 4 |
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

- `send_message` waits for `wait` and returns what is known then: `reached: false` with a `note` when the time ran
  out first (the message may still arrive; ask `message_status`). Waiting for `delivered` or `read` needs that
  `report`.
- `inbox` without `since` gives the latest `limit` items; with `since`, the oldest after it, and `more` says
  whether others follow (read on with `since = last_seq`).
- `wait_message` without `since` waits for what arrives after the call; with it, it returns at once if an item
  after `since` is already there.

### The state (`status`)

`version`, `ready` (registered and authorised for MCData), `can_send` and `cannot_send_because`, `mcdata_id` (the
agent's MCData ID), `alias` (`{id, state, active, required}`), `registered`, `mcdata_authorized`, `engine_running`,
`engine_exit` (why the engine stopped), `last_error`, `unread`, `last_seq`.

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

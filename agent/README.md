# mcx-agent: MCData for AI agents

`mcx-agent` lets an AI agent (or a script) exchange **MCData one-to-one short data (SDS) and files** through an
OpenMCX MC system. It runs the Linux client `mcx-client` as its engine, like the [dispatcher](../dispatcher) does, and
offers the same tools two ways: an **MCP server** for agents and a **CLI** for shells. MCPTT, MCVideo and group SDS are
outside its reach.

```
agent ──stdio MCP──▶ mcx-agent mcp ──┐
shell ─────────────▶ mcx-agent <tool> ┴─ unix socket ─▶ mcx-agent daemon ──control FIFO / status / events──▶ mcx-client
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

`mcx-agent tools` lists the arguments. In the CLI, `--name value`; tool and argument names take `-` or `_`; the
result is JSON on stdout. Text may have line breaks: the daemon passes it to the engine with its `escaped` option
(`\n`, `\t` and `\\` on the command line).

```bash
mcx-agent send-message --to sip:alice.mcdata@example.org --text "unit 4 report position" --wait delivered
mcx-agent wait-message --timeout-s 120
mcx-agent mark-read --message-id 6f1c…
```

## Errors

A failed tool answers `{"ok": false, "error": "<message>", "code": "<code>"}` (CLI and socket) or an MCP result with
`isError`, the text `<code>: <message>` and `{error, code}` as structured content. The codes are stable, the messages
are not:

| Code | Meaning | CLI exit status |
|---|---|---|
| `invalid_argument`, `unknown_tool` | A missing or bad argument, an unknown tool | 2 |
| `not_allowed` | The configuration does not allow this recipient | 3 |
| `rate_limited` | Too many sends in the last minute; try again later | 3 |
| `alias_inactive` | The agent's functional alias is not active, so it may not send | 3 |
| `engine_not_ready` | The engine (or the daemon) is starting, not registered or not authorised for MCData | 4 |
| `not_found` | No such message, file offer or outbox file | 1 |
| `engine_refused` | The engine refused the command | 1 |
| `send_failed` | The network refused the message (the error has its Warning) or it was not delivered | 1 |
| `transfer_failed` | A file upload or download failed | 1 |
| `timeout` | The engine reported no outcome in time | 1 |
| `internal` | Anything else | 1 |

## Received messages

Only one-to-one SDS and file offers reach the agent; group and ad hoc group SDS are left out. A message sent to the
agent's functional alias shows it in `to_alias`. Text payloads only: other content types arrive with an empty `text`.
Each message also says what the agent may want to decide on: `emergency` and `imminent_peril` (sent as such a
communication), `used_priority` (the priority the server applied), `authenticated` (its signature verified) and
`protected` (end-to-end protected).

## Functional alias

With `alias` set, the daemon activates it once the engine is authorised for MCData (`mcdata-fa activate`), and once it
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
- No emergency or imminent-peril priority, no alerts, no raw engine commands.
- `<data_dir>/audit.jsonl` records every call of a tool that acts on the network, with its arguments and outcome.
- The socket is owner only (0600); credentials stay in the engine's configuration, out of the agent's reach.

## Configuration

`agent.json` ([example](agent.example.json)):

| Key | Meaning |
|---|---|
| `engine.path`, `engine.config`, `engine.args` | `mcx-client` (default `/usr/local/bin/mcx-client`), its configuration, and further arguments (e.g. `--disable-tls`); the configuration needs `control_fifo`, `status_file` and `mcdata.user_id` |
| `socket` | The daemon's unix socket (default `/run/mcx-agent/agent.sock`; clients also read `MCX_AGENT_SOCKET`) |
| `data_dir` | Store and audit log |
| `outbox_dir` | Where files are sent from |
| `alias`, `require_alias`, `alias_retry_s` | The functional alias to send as; refuse to send without it (default true when an alias is set); how often a lost alias is tried again |
| `recipients`, `allow_senders_hours`, `rate_per_recipient_per_minute`, `rate_per_minute`, `max_text_bytes` | See Safety |

Downloads land where the engine puts them: `downloads/` beside its status file.

## Build, run and connect an agent

```bash
linux/tools/build-image -t openmcx-client:<tag>
docker build -t openmcx-agent:<tag> --build-arg CLIENT_IMAGE=openmcx-client:<tag> agent
docker run -d --name mcx-agent -v $PWD/agent-config:/etc/mcx-agent:ro -v mcx-agent-data:/var/lib/mcx-agent \
  openmcx-agent:<tag>
```

Claude Code, over the container's stdio:

```bash
claude mcp add mcx -- docker exec -i mcx-agent mcx-agent mcp
```

Any MCP client works the same way: the server speaks MCP over stdio (protocol versions 2024-11-05 to 2025-06-18) and
offers tools only.

## Hello world: the echo bot

[`examples/echo-bot.py`](examples/echo-bot.py) (Python standard library only, in the image under
`/usr/local/share/mcx-agent/examples/`) is the smallest complete agent: it waits for messages, marks each read, answers
"Hello <sender>, you said: <text>" in the sender's conversation, and accepts files. It talks to the daemon through the
CLI, or through MCP with `--mcp`, the two ways an addon can integrate:

```bash
docker exec -d mcx-agent python3 /usr/local/share/mcx-agent/examples/echo-bot.py --cursor /var/lib/mcx-agent/echo.cursor
```

## Tests

The lab scenario `mcdata_agent_scenario` in openmcx-server runs the agent and the echo bot against the real servers
(lab `mcdata-agent`, the openmcx-agent image as the client image):

```bash
docker build -t openmcx-agent:<tag> --build-arg CLIENT_IMAGE=openmcx-client:<tag> agent
cd ../openmcx-server && tools/regress.py --images <tag> --client-image openmcx-agent:<tag> --select =mcdata-agent
```

`go test` runs the daemon against a fake engine ([`service/testdata/fake-engine`](service/testdata/fake-engine)) that
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
- `mark_read` on a message the engine no longer holds (its last 50) marks it read in the store only; the result says
  the report was not sent.

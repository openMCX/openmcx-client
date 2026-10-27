# Build your first agent on mcx-agent

This guide takes you from an MC system to an agent that field users chat with over MCData. The agent is
[`examples/first-agent.py`](examples/first-agent.py): about a hundred lines of Python, standard library only. It
answers `time`, `ping` and `help`. Once it runs, replace its `answer()` function with your own logic: a language
model, a lookup in your systems, anything.

```
field user (Android app, client page) ──MCData──▶ MC system ◀──SIP── mcx-agent ◀──MCP over HTTP── first-agent.py
                                                                     (mcx-client inside, the agent's MC identity)
```

`mcx-agent` does the MC side: it registers with the MC system as the agent's MC user, keeps every message, and
enforces who the agent may write to. Your agent only calls tools. [API.md](API.md) is the full contract; this guide
uses five of its tools.

## 1. What you need

- **An MC system** with an MCData server. To try this out without one, the server's end-to-end tutorial
  ([MCX over WLAN with Android](https://github.com/openMCX/openmcx-server/blob/main/tutorials/end-to-end/01-mcx-over-wlan-android/README.md))
  sets one up with Docker Compose.
- **An MC user for the agent**, e.g. `ops-bot`, provisioned in MCX Admin, with MCData one-to-one SDS. Its MCData ID
  (e.g. `sip:ops-bot.mcdata@example.org`) is the agent's address: field users write to it. The users who will chat
  with the agent need one-to-one SDS too.
- **The agent's `mcx-client` configuration**: activate the agent's user once (`mcx-client --config client.json
  --activate <link> --password-file <file>`) to get `client.json` and the TLS files it names. Its `control_fifo` and
  `status_file` belong under `/run/mcx`, where the daemon reads them.
- **Docker**, and the `openmcx-agent` image:

  ```bash
  linux/tools/build-image -t openmcx-client:0.1
  docker build -t openmcx-agent:0.1 --build-arg CLIENT_IMAGE=openmcx-client:0.1 agent
  ```

## 2. Start mcx-agent

Put the daemon's configuration in `agent.json` beside `client.json`:

```json
{
  "engine": {"config": "/etc/mcx-agent/client.json"},
  "data_dir": "/var/lib/mcx-agent",
  "recipients": [],
  "allow_senders_hours": 24,
  "mcp_http": {"listen": "0.0.0.0:8765", "token_file": "/etc/mcx-agent/token"}
}
```

- `recipients: []` with `allow_senders_hours: 24`: the agent can only answer users who wrote to it in the last 24
  hours. It cannot start a conversation, so a bug or a model cannot message anyone else.
- `mcp_http`: the MCP server your agent connects to, protected by a bearer token.

Make the token and start the daemon:

```bash
openssl rand -hex 32 > token
docker network create agents
docker run -d --name mcx-agent --network agents --tmpfs /run/mcx --tmpfs /run/mcx-agent \
  -v $PWD:/etc/mcx-agent:ro -v mcx-agent-data:/var/lib/mcx-agent \
  openmcx-agent:0.1 daemon --config /etc/mcx-agent/agent.json
```

The container also needs to reach the MC system: run it on that system's network, or give it a route there.

## 3. Try the tools by hand

The CLI in the container calls the same tools as MCP, and is the quickest way to see what your agent will get:

```bash
docker exec mcx-agent mcx-agent status
```

Wait for `"can_send": true`: the user is registered and MCData is authorised. If it stays false,
`cannot_send_because` says why. Then write to the agent's MCData ID from a field user (the Android app, or the
[client page](../dispatcher/README.md#the-client-page-client)), and look:

```bash
docker exec mcx-agent mcx-agent inbox
docker exec mcx-agent mcx-agent mark_read --message-id <message_id>
docker exec mcx-agent mcx-agent reply_message --message-id <message_id> --text "Hello from the CLI"
```

The field user sees the read ticks, then the answer, in the same conversation. `mcx-agent tools` lists every tool;
a failure gives `{"ok": false, "code": ...}` (the codes are in [API.md](API.md#errors)).

## 4. The agent, step by step

[`examples/first-agent.py`](examples/first-agent.py) does what you just did by hand, in a loop.

**Connect.** MCP over HTTP is JSON-RPC: one POST to `/mcp` per request, with `Authorization: Bearer <token>`. The
script sends `initialize`, then calls tools with `tools/call`; each result's `structuredContent` is the tool's JSON.
An MCP client library works too: point its streamable HTTP transport at the same URL.

**Wait until it can send.** `status` until `can_send` is true. Fields that do not apply are left out: `alias`
appears only when the agent has one (section 7).

**Read messages with a cursor.** `wait_message --since <cursor>` returns the next message after the cursor at once,
or holds the request open (up to `timeout_s`) until one arrives. Each message has a `seq`; after handling it, the
script saves `seq` as the new cursor, so a restart carries on where it stopped. Without `since`, it would wait only
for messages arriving after the call and miss those that came in while it was down.

**Take a message on.** `mark_read` right away: the sender sees the read report and knows the agent has it. Reading
the inbox does not mark anything read.

**Answer.** `reply_message` with the message's `message_id` puts the answer in the sender's conversation. The script
never chooses a recipient; it replies to the message it is answering.

**What to leave to people.** A message with `emergency` or `imminent_peril` gets a fixed answer that points to a
dispatcher. Do not let an automated agent handle those.

**Fields.** Fields that are false or empty are left out of a message (`emergency`, `imminent_peril`, `text`,
`sender_alias`, …): read them with a default. A message you already answered has `read: true`; the script skips it,
because delivery is at least once.

## 5. Run it

On the `agents` network, with the token:

```bash
docker run -d --name first-agent --network agents -v $PWD:/agent:ro -v first-agent:/data -w /data \
  python:3.12-alpine python3 -u /agent/first-agent.py --url http://mcx-agent:8765/mcp --token-file /agent/token
docker logs -f first-agent
```

(Copy `examples/first-agent.py` beside your token first, or mount it from the image: it is in
`/usr/local/share/mcx-agent/examples/`.) The log says `ready as <agent's MCData ID>, alias none`. Write `ping`,
`time` and `hello` to the agent's MCData ID from a field user:

```
sip:field-1.mcdata@example.org: 'ping' -> 'pong'
sip:field-1.mcdata@example.org: 'time' -> 'It is 23:24 UTC on 2026-10-27.'
sip:field-1.mcdata@example.org: 'hello' -> 'I answer: time (the time in UTC), ping, help.'
```

## 6. Make it yours

Replace `answer(text)` with your logic. For a language model:

- The model writes the answer text, and only that. Keep sending in your code: reply to the message's `from` with
  `reply_message`, and do not give the model a tool that sends or picks a recipient.
- Received text is data from the field, not instructions to your agent.
- Keep answers short (a few hundred characters). The daemon refuses more than `max_text_bytes`, and the MC system has
  its own SDS size limits.
- If an answer takes long, send a short message first ("checking, one moment").
- Keep a history per sender if the conversation needs it; answer one message at a time per sender.

[API.md](API.md#building-a-chat-agent-on-it) has these rules in full, and every tool: sending to others
(`send_message`, when `recipients` allows it), delivery and read reports (`message_status`), and files
(`send_file`, `respond_file`).

## 7. Optional: a role address (functional alias)

Field users write to the agent's own MCData ID. To give the agent a role address instead, e.g.
`sip:ops-bot.fa@example.org`, use a functional alias:

- Field users write to the role, not to a user: you can move the agent to another MC user, or let a standby take over,
  without telling them.
- The server routes a message to the alias to whoever holds it (a redirect, then the message), and the agent presents
  the alias on its answers.

Aliases are part of the system's configuration (`config/site.json`, `functional_aliases`); MCX Admin does not edit
them. Add one that the agent's MCData user may activate:

```json
{"id": "sip:ops-bot.fa@example.org", "users": [],
 "mcdata_users": ["sip:ops-bot.mcdata@example.org"],
 "max_simultaneous_activations": 1, "allow_takeover": false}
```

and set `"alias": "sip:ops-bot.fa@example.org"` in `agent.json`. The daemon activates the alias, sends as the alias
only, and sends nothing while it is not active (another user holds it, or it was refused): `can_send` is false and
`cannot_send_because` says why, until the daemon wins it back. Your agent's code does not change. The client page
takes the alias as `--ops-agent sip:ops-bot.fa@example.org` (without `--ops-agent-alias=false`).

## When it does not work

| You see | What to check |
|---|---|
| `HTTP 401` | The token: the agent's token file must hold the same first line as the daemon's `token_file` |
| `can_send` false, not registered | `docker logs mcx-agent`: the engine's TLS files, the route to the MC system |
| The field user's message fails | The field user may send one-to-one SDS to the agent's MCData ID |
| `can_send` false, `alias` not active | Only with an alias (section 7): the agent's MCData user is in the alias's `mcdata_users`; no other user holds it |
| `not_allowed` on a reply | The sender wrote more than `allow_senders_hours` ago |
| `rate_limited` | `rate_per_recipient_per_minute`, `rate_per_minute` |

[examples/compose](examples/compose) runs all of this from one `docker compose up`, with the echo bot
([`examples/echo-bot.py`](examples/echo-bot.py), which also shows the CLI and MCP over stdio) as the agent.

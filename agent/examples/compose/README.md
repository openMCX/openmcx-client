# An MCData agent beside an MC system (compose example)

The template for running an agent on `mcx-agent`: copy this directory, replace the echo bot with your agent.

```
            mc network                         agents network (internal)
MC system ◀────────────▶ mcx-agent ◀── MCP over HTTP, bearer token ──▶ echo-bot (your agent)
    ▲                    (mcx-client inside)
    └──── client (profile "ui"): the OpenMCX client page, a user who chats with the agent
```

| Service | Image | What it is |
|---|---|---|
| `mcx-agent` | `openmcx-agent` | The plumbing: the daemon, its engine `mcx-client` as the agent's MC identity, MCP over HTTP on `mcx-agent:8765/mcp` |
| `echo-bot` | `python:3.12-alpine` | A third-party agent: [`../echo-bot.py`](../echo-bot.py) over MCP on HTTP. Nothing of OpenMCX inside, and no route to the MC system: it reaches only `mcx-agent` |
| `client` (profile `ui`) | `openmcx-dispatcher` | The [client page](../../../dispatcher/README.md#the-client-page-client) at `http://127.0.0.1:8080/client/`, with the agent first in its chat |

## Set up

1. **Images**, from the root of openmcx-client (with openmcx-server beside it):

   ```bash
   linux/tools/build-image -t openmcx-client:0.1
   docker build -t openmcx-agent:0.1 --build-arg CLIENT_IMAGE=openmcx-client:0.1 agent
   docker build -t openmcx-dispatcher:0.1 --build-arg CLIENT_IMAGE=openmcx-agent:0.1 dispatcher   # for "ui"
   ```

   Other tags: `OPENMCX_AGENT_IMAGE`, `OPENMCX_DISPATCHER_IMAGE`.

2. **The agent's MC identity**: an MC user with MCData (one-to-one SDS and file distribution) and a functional
   alias it may activate, provisioned in MCX Admin. Put its `mcx-client` configuration in `agent/client/client.json`
   with the TLS files it names, at the paths it names under `/config`: an activated configuration
   (`mcx-client --config client.json --activate <link> --password-file <file>` once, on the device settings), or the
   one a lab generates. Its `control_fifo` and `status_file` belong under `/run/mcx`.

3. **`agent/agent.json`**: set `alias` to the agent's functional alias; the rest is the chat setup of
   [API.md](../../API.md#building-a-chat-agent-on-it) (send only to users who wrote within `allow_senders_hours`,
   rate limits, MCP over HTTP).

4. **The token** the agent and the daemon share:

   ```bash
   openssl rand -hex 32 > secrets/mcx-agent-token
   ```

5. **For "ui"**: another MC user's configuration in `ui/client/client.json`, with `media.live` (see the
   [dispatcher README](../../../dispatcher/README.md#build-and-run)), and `OPS_AGENT_ALIAS` set to the agent's alias.

## Run

```bash
docker compose up -d                       # mcx-agent and the echo bot
docker compose logs -f echo-bot            # "echo bot ready as …", then each answer
docker compose exec mcx-agent mcx-agent status
docker compose --profile ui up -d          # and the client page: http://127.0.0.1:8080/client/
```

Write to the agent's alias from any MC client (the Android app, the client page): the bot marks the message read
and answers in the conversation.

## Your agent instead of the echo bot

Replace the `echo-bot` service with your agent's container on the `agents` network, given the token as a secret,
and point its MCP client at `http://mcx-agent:8765/mcp` with `Authorization: Bearer <token>`. The tools, results and
error codes are in [API.md](../../API.md). An MCP client library's streamable HTTP transport works as it is.

# Agents beside an MC system (compose example)

The template for running agents on `mcx-agent`: copy this directory, replace the bots with your agents.

```
            mc network                         agents network (internal)
MC system ◀────────────▶ mcx-agent ◀── MCP over HTTP, bearer token ──┬─▶ chat-bot  (MCData)
    ▲                    (mcx-client inside)                         ├─▶ voice-bot (MCPTT)
    │                                                                └─▶ video-bot (MCVideo)
    └──── client (profile "ui"): the OpenMCX client page, a user who chats, talks and watches
```

| Service | Image | What it is |
|---|---|---|
| `mcx-agent` | `openmcx-agent` | The plumbing: the daemon, its engine `mcx-client` as the agent's MC identity, MCP over HTTP on `mcx-agent:8765/mcp` |
| `chat-bot` | `python:3.12-alpine` | A third-party agent: [`../chat-bot.py`](../chat-bot.py) answers `time`, `ping` and `help` over MCData |
| `voice-bot` | `python:3.12-alpine` | [`../voice-bot.py`](../voice-bot.py): in each MCPTT call it is in (a private call to it, a group call of its groups), it plays each talk burst back after a tone |
| `video-bot` | `python:3.12-alpine` | [`../video-bot.py`](../video-bot.py): shows a test card in each MCVideo call it is in, as a drone or a robot would show its camera |
| `client` (profile `ui`) | `openmcx-dispatcher` | The [client page](../../../dispatcher/README.md#the-client-page-client) at `http://127.0.0.1:8080/client/`, with the agent first in its chat |

The bots have nothing of OpenMCX inside and no route to the MC system: they reach only `mcx-agent`, over MCP on HTTP
(the voice and video bots with [`../mcxagent.py`](../mcxagent.py), mounted beside them). The agent makes no calls:
users call it, and it joins its groups' calls.

## Set up

1. **Images**, from the root of openmcx-client (with openmcx-server beside it):

   ```bash
   linux/tools/build-image -t openmcx-client:0.1
   docker build -t openmcx-agent:0.1 --build-arg CLIENT_IMAGE=openmcx-client:0.1 agent
   docker build -t openmcx-dispatcher:0.1 --build-arg CLIENT_IMAGE=openmcx-agent:0.1 dispatcher   # for "ui"
   ```

   Other tags: `OPENMCX_AGENT_IMAGE`, `OPENMCX_DISPATCHER_IMAGE`.

2. **The agent's MC identity**: an MC user with MCData (one-to-one SDS and file distribution), MCPTT and MCVideo,
   provisioned in MCX Admin; field users write to its MCData ID and call its MCPTT and MCVideo IDs. Put its `mcx-client` configuration in `agent/client/client.json`
   with the TLS files it names, at the paths it names under `/config`: an activated configuration
   (`mcx-client --config client.json --activate <link> --password-file <file>` once, on the device settings), or the
   one a lab generates. Its `control_fifo` and `status_file` belong under `/run/mcx`.

3. **`agent/agent.json`**: the chat setup of
   [API.md](../../API.md#building-a-chat-agent-on-it) (send only to users who wrote within `allow_senders_hours`,
   rate limits, MCP over HTTP), with `voice` and `video` on (the video bot's picture size and rate).
   Without one of the services, leave it out and remove its bot. For a role address, add `alias`
   ([FIRST_AGENT.md](../../FIRST_AGENT.md#7-optional-a-role-address-functional-alias)).

4. **The token** the agent and the daemon share:

   ```bash
   openssl rand -hex 32 > secrets/mcx-agent-token
   ```

5. **For "ui"**: another MC user's configuration in `ui/client/client.json`, with `media.live` (see the
   [dispatcher README](../../../dispatcher/README.md#build-and-run)), and `OPS_AGENT` set to the agent's MCData ID
   (or to its alias, with `OPS_AGENT_IS_ALIAS=true`).

## Run

```bash
docker compose up -d                       # mcx-agent and the three bots
docker compose logs -f chat-bot voice-bot video-bot   # "ready …", then what each does
docker compose exec mcx-agent mcx-agent status
docker compose --profile ui up -d          # and the client page: http://127.0.0.1:8080/client/
```

From any MC client (the Android app, the client page):

- write `ping`, `time` or `help` to the agent's MCData ID: the chat bot marks the message read and answers in the
  conversation;
- call the agent's MCPTT ID, or make a group call on one of its groups, and talk: the voice bot answers with a tone
  and what it heard;
- make an MCVideo call to the agent's MCVideo ID, or a group video call: the video bot's test card appears.

## Your agent instead of the bots

Replace the bots with your agent's container on the `agents` network, given the token as a secret,
and point its MCP client at `http://mcx-agent:8765/mcp` with `Authorization: Bearer <token>`. The tools, results and
error codes are in [API.md](../../API.md). An MCP client library's streamable HTTP transport works as it is.

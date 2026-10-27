#!/usr/bin/env python3
#
# OpenMCX Licence Information : AGPL-3.0-or-later
#

"""The agent of FIRST_AGENT.md: it answers "time", "ping" and "help" over MCData, through
mcx-agent's MCP server over HTTP. Python standard library only.

    first-agent.py --url http://mcx-agent:8765/mcp --token-file token [--cursor first-agent.cursor]
"""

import argparse
import json
import os
import time
import urllib.request
from datetime import UTC, datetime

HELP = 'I answer: time (the time in UTC), ping, help.'


class Mcx:
    """MCP over HTTP: each JSON-RPC request is one POST to /mcp with the bearer token."""

    def __init__(self, url, token):
        self.url, self.token, self.next_id = url, token, 0
        self.rpc(
            'initialize',
            protocolVersion='2025-06-18',
            capabilities={},
            clientInfo={'name': 'first-agent', 'version': '1'},
        )
        self.post({'jsonrpc': '2.0', 'method': 'notifications/initialized'})

    def post(self, message):
        request = urllib.request.Request(
            self.url,
            data=json.dumps(message).encode(),
            method='POST',
            headers={
                'Content-Type': 'application/json',
                'Accept': 'application/json, text/event-stream',
                'Authorization': 'Bearer ' + self.token,
            },
        )
        with urllib.request.urlopen(request, timeout=330) as response:  # wait_message holds the request open
            body = response.read()
        return json.loads(body) if body else None

    def rpc(self, method, **params):
        self.next_id += 1
        reply = self.post({'jsonrpc': '2.0', 'id': self.next_id, 'method': method, 'params': params})
        if 'error' in reply:
            raise RuntimeError(reply['error']['message'])
        return reply['result']

    def call(self, tool, **args):
        result = self.rpc('tools/call', name=tool, arguments=args)
        if result.get('isError'):
            raise RuntimeError(result['content'][0]['text'])  # "<code>: <message>"
        return result['structuredContent']


def answer(text):
    """The agent's logic: replace it with yours (a model, a lookup, ...)."""
    word = text.strip().lower()
    if word == 'time':
        return datetime.now(UTC).strftime('It is %H:%M UTC on %Y-%m-%d.')
    if word == 'ping':
        return 'pong'
    return HELP


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--url', required=True, help='e.g. http://mcx-agent:8765/mcp')
    parser.add_argument('--token-file', required=True)
    parser.add_argument('--cursor', default='first-agent.cursor', help='keeps the seq of the last message handled')
    args = parser.parse_args()
    with open(args.token_file) as f:
        mcx = Mcx(args.url, f.readline().strip())

    # 1. Wait until the agent's MC client is registered (and its functional alias active, if it has one).
    while not (status := mcx.call('status'))['can_send']:
        print('waiting:', status.get('cannot_send_because'), flush=True)
        time.sleep(2)
    print(f'ready as {status["mcdata_id"]}, alias {status.get("alias", {}).get("id", "none")}', flush=True)

    # 2. Read messages from where the last run stopped.
    cursor = 0
    if os.path.exists(args.cursor):
        with open(args.cursor) as f:
            cursor = int(f.read())
    while True:
        got = mcx.call('wait_message', since=cursor, kind='message', timeout_s=60)
        if got['timed_out']:
            continue
        message = got['item']
        try:
            if not message['read']:  # read: answered before a restart
                # 3. Take it on: the sender sees the read report.
                mcx.call('mark_read', message_id=message['message_id'])
                if message.get('emergency') or message.get('imminent_peril'):  # absent when false
                    reply = 'This is an automated agent. Call a dispatcher for your emergency.'
                else:
                    reply = answer(message.get('text', ''))
                # 4. Answer in the sender's conversation; never to another recipient.
                mcx.call('reply_message', message_id=message['message_id'], text=reply)
                print(f'{message["from"]}: {message.get("text")!r} -> {reply!r}', flush=True)
        except RuntimeError as error:  # e.g. "rate_limited: ...": log it and go on
            print(f'message {message["seq"]}: {error}', flush=True)
        cursor = message['seq']
        with open(args.cursor, 'w') as f:
            f.write(str(cursor))


if __name__ == '__main__':
    main()

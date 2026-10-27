#!/usr/bin/env python3
#
# OpenMCX Licence Information : AGPL-3.0-or-later
#

"""A hello-world MCData agent on mcx-agent: it answers every one-to-one message with
"Hello <sender>, you said: <text>" in the same conversation, marks the message read (the
sender sees the read report), and accepts every file offered to it.

It reaches the daemon through the CLI (default) or the MCP server (--mcp), the two ways an
agent can use mcx-agent; the rest is the same. Standard library only.

    echo-bot.py [--mcp] [--agent mcx-agent] [--cursor FILE]

The bot keeps the sequence number of the last item it handled in the cursor file, so a restart
resumes where it stopped; a message already marked read is not answered again. An item whose
tool call fails is logged and skipped.
"""

import argparse
import json
import os
import subprocess
import sys
import time

MAX_ECHO = 200  # characters of the received text quoted back


class ToolError(Exception):
    pass


class CliAgent:
    """Calls a tool with mcx-agent <tool> --<name> <value>...; the result is JSON on stdout."""

    def __init__(self, program):
        self.program = program

    def call(self, tool, **args):
        command = [self.program, tool]
        for name, value in args.items():
            command += [f'--{name}', str(value).lower() if isinstance(value, bool) else str(value)]
        run = subprocess.run(command, capture_output=True, text=True)
        try:
            result = json.loads(run.stdout) if run.stdout.strip() else {}
        except json.JSONDecodeError:
            result = {}
        if run.returncode:
            error = result.get('error') or run.stderr.strip() or f'exit {run.returncode}'
            raise ToolError(f"{result.get('code', 'error')}: {error}")
        return result


class McpAgent:
    """Speaks MCP (JSON-RPC 2.0, one message per line) with mcx-agent mcp on its stdin and stdout."""

    def __init__(self, program):
        self.server = subprocess.Popen([program, 'mcp'], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
        self.next_id = 0
        info = self.request(
            'initialize', protocolVersion='2025-06-18', capabilities={}, clientInfo={'name': 'echo-bot', 'version': '1'}
        )
        self.notify('notifications/initialized')
        print(
            f"MCP server {info['serverInfo']['name']} {info['serverInfo']['version']}, "
            f"protocol {info['protocolVersion']}",
            flush=True,
        )

    def send(self, message):
        self.server.stdin.write(json.dumps(message) + '\n')
        self.server.stdin.flush()

    def notify(self, method):
        self.send({'jsonrpc': '2.0', 'method': method})

    def request(self, method, **params):
        self.next_id += 1
        self.send({'jsonrpc': '2.0', 'id': self.next_id, 'method': method, 'params': params})
        line = self.server.stdout.readline()
        if not line:
            raise ToolError('the MCP server ended')
        reply = json.loads(line)
        if 'error' in reply:
            raise ToolError(reply['error']['message'])
        return reply['result']

    def call(self, tool, **args):
        result = self.request('tools/call', name=tool, arguments=args)
        if result.get('isError'):
            raise ToolError(result['content'][0]['text'])  # "<code>: <message>"
        return result.get('structuredContent') or json.loads(result['content'][0]['text'])


def read_cursor(path):
    try:
        with open(path) as f:
            return int(f.read().strip() or 0)
    except FileNotFoundError:
        return 0


def write_cursor(path, seq):
    with open(path + '.tmp', 'w') as f:
        f.write(f'{seq}\n')
    os.replace(path + '.tmp', path)


def handle(agent, item):
    """Answer one received message or file offer."""
    sender = item['from']
    if item['kind'] == 'message':
        if item['read']:
            return  # handled before a restart
        agent.call('mark_read', message_id=item['message_id'])
        text = item['text'] if len(item['text']) <= MAX_ECHO else item['text'][:MAX_ECHO] + '...'
        answer = f'Hello {sender}, you said: {text}'
        try:
            sent = agent.call('reply_message', message_id=item['message_id'], text=answer)
        except ToolError as error:
            # The engine keeps only its last 50 messages to reply to; a new message reaches the sender too.
            print(f'reply failed ({error}); sending a new message', flush=True)
            sent = agent.call('send_message', to=sender, text=answer, report='none')
        print(f"answered {item['message_id']} from {sender}: {sent['message']['message_id']}", flush=True)
    elif item['kind'] == 'file':
        if item.get('file_state') not in ('offered', 'deferred'):
            return
        got = agent.call('respond_file', message_id=item['message_id'], action='accept')
        agent.call(
            'send_message',
            to=sender,
            report='none',
            text=f"Hello {sender}, I got your file {item['file_name']} ({got.get('bytes')} bytes)",
        )
        print(f"accepted file {item['file_name']} from {sender}: {got.get('path')}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--mcp', action='store_true', help='use the MCP server instead of the CLI')
    parser.add_argument('--agent', default='mcx-agent', help='the mcx-agent program')
    parser.add_argument('--cursor', default='echo-bot.cursor', help='where the last handled sequence number is kept')
    args = parser.parse_args()
    agent = McpAgent(args.agent) if args.mcp else CliAgent(args.agent)

    while True:
        try:
            status = agent.call('status')
            if status['can_send']:
                break
            print(f"waiting: {status.get('cannot_send_because', 'not ready')}", flush=True)
        except ToolError as error:
            print(f'waiting: {error}', flush=True)
        time.sleep(2)
    print(f"echo bot ready as {status['mcdata_id']}, alias {status.get('alias', {}).get('id', 'none')}", flush=True)

    cursor = read_cursor(args.cursor)
    while True:
        try:
            got = agent.call('wait_message', since=cursor, timeout_s=60)
        except ToolError as error:
            print(f'wait_message: {error}', flush=True)
            time.sleep(2)
            continue
        if got['timed_out']:
            continue
        item = got['item']
        try:
            handle(agent, item)
        except ToolError as error:
            print(f"item {item['seq']} ({item['message_id']}): {error}", flush=True)
        cursor = item['seq']
        write_cursor(args.cursor, cursor)


if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(0)

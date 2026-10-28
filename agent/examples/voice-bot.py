#!/usr/bin/env python3
#
# OpenMCX Licence Information : AGPL-3.0-or-later
#

"""A hello-world voice agent on mcx-agent: in every MCPTT call it is in (a private call to it, or a group call of
its groups), it waits for each talk burst, and answers it in the same call: a short tone, then the speech it heard,
played back. It stands where a real voice agent puts speech to text, a model and text to speech: respond() takes
the heard WAV and returns the WAV to say.

It reaches the daemon over MCP on HTTP from another container (--mcp-url, --token-file), MCP on stdio (--mcp) or
the CLI (default). Standard library only.

    voice-bot.py [--mcp | --mcp-url URL --token-file FILE] [--max-reply-s 10] [--outbox DIR (with the CLI)]

Talk bursts heard while it answers wait for the next turn; one heard while the floor is busy is answered once the
floor is free (speak waits for it). A burst older than --stale-s seconds when its turn comes is skipped.
"""

import argparse
import base64
import io
import math
import os
import re
import struct
import sys
import time
import wave
from datetime import UTC, datetime

from mcxagent import CliAgent, ToolError, connect, wait_ready

RATE = 16000


def received(text):
    """A time of the daemon (RFC 3339, nanoseconds) as a datetime."""
    text = re.sub(r'(\.\d{6})\d+', r'\1', text).replace('Z', '+00:00')
    return datetime.fromisoformat(text)


def tone(seconds=0.25, hz=880):
    """A short tone, 16 kHz mono 16-bit, as samples."""
    return [int(6000 * math.sin(2 * math.pi * hz * i / RATE)) for i in range(int(RATE * seconds))]


def respond(item, heard_wav, max_seconds):
    """The answer to a talk burst, as a WAV: a tone, then the speech heard (at most max_seconds). A real agent
    transcribes heard_wav, decides, and synthesises its answer here."""
    with wave.open(io.BytesIO(heard_wav)) as w:
        frames = w.readframes(min(w.getnframes(), int(max_seconds * w.getframerate())))
    samples = tone() + [0] * (RATE // 10) + list(struct.unpack(f'<{len(frames) // 2}h', frames))
    out = io.BytesIO()
    with wave.open(out, 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(struct.pack(f'<{len(samples)}h', *samples))
    return out.getvalue()


def speak(agent, wav, outbox, seq):
    """Say the WAV in the call. The CLI takes it from the outbox: inline, it would not fit on a command line."""
    if not isinstance(agent, CliAgent):
        return agent.call('speak', audio_base64=base64.b64encode(wav).decode(), timeout_s=30)
    name = f'voice-bot-{seq}.wav'
    with open(os.path.join(outbox, name), 'wb') as f:
        f.write(wav)
    try:
        return agent.call('speak', file=name, timeout_s=30)
    finally:
        os.remove(os.path.join(outbox, name))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--mcp', action='store_true', help='use the MCP server on stdio instead of the CLI')
    parser.add_argument('--mcp-url', help='use the MCP server over HTTP, e.g. http://mcx-agent:8765/mcp')
    parser.add_argument('--token-file', help='the bearer token for --mcp-url')
    parser.add_argument('--agent', default='mcx-agent', help='the mcx-agent program')
    parser.add_argument('--max-reply-s', type=float, default=10, help='the longest speech played back')
    parser.add_argument('--stale-s', type=float, default=30, help='skip a burst heard longer ago than this')
    parser.add_argument('--outbox', default='/var/lib/mcx-agent/outbox',
                        help="with the CLI: the daemon's outbox_dir, where the answer is written for speak")
    args = parser.parse_args()
    if args.mcp_url and not args.token_file:
        parser.error('--mcp-url needs --token-file')
    agent = connect(args.mcp_url, args.token_file, args.mcp, args.agent, 'voice-bot')
    status = wait_ready(agent, 'mcptt')
    print(f"voice bot ready as {status['services']['mcptt']['id']}", flush=True)

    cursor = agent.call('call_status')['last_speech_seq']  # only what is said from now on
    while True:
        try:
            got = agent.call('wait_speech', since=cursor, timeout_s=60)
        except ToolError as error:
            print(f'wait_speech: {error}', flush=True)
            time.sleep(2)
            continue
        if got['timed_out']:
            continue
        item = got['item']
        cursor = item['seq']
        where = item.get('group') or item.get('peer')
        print(f"heard {item['from']} in a {item['call_type']} call ({where}): {item['duration_ms']} ms", flush=True)
        age = (datetime.now(UTC) - received(item['received'])).total_seconds()
        if age > args.stale_s:
            print(f'skipped: heard {age:.0f} s ago', flush=True)
            continue
        answer = respond(item, base64.b64decode(item['audio']['base64']), args.max_reply_s)
        try:
            said = speak(agent, answer, args.outbox, item['seq'])
            print(f"spoke to {where}: {said['outcome']} ({said['duration_ms']} ms)", flush=True)
        except ToolError as error:
            print(f'speak: {error}', flush=True)  # e.g. no_call: the user hung up meanwhile


if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(0)

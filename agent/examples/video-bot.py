#!/usr/bin/env python3
#
# OpenMCX Licence Information : AGPL-3.0-or-later
#

"""A hello-world camera agent on mcx-agent, standing for a drone or a robot: it streams its pictures to the daemon
(POST /video, raw I420) and shows them in the MCVideo calls it is in: it makes no calls, but asks for the
permission to transmit in every call a user makes to it and every group call of its groups. What other users
transmit does not concern it.

The pictures are a generated test card (moving bars and a clock), or with --source anything ffmpeg reads (a camera
/dev/video0, an RTSP stream rtsp://drone/live, a file), converted by ffmpeg to the size and rate the daemon takes.
Standard library only (ffmpeg for --source).

    video-bot.py --mcp-url http://mcx-agent:8765/mcp --token-file token [--source URL] [--seconds N]
"""

import argparse
import subprocess
import sys
import threading
import time

from mcxagent import ToolError, connect, stream_pictures, wait_ready


def test_card(width, height, rate, seconds):
    """Generated I420 pictures at the frame rate: bars that move, and a block that counts the seconds."""
    luma_bars = [235, 210, 170, 145, 106, 81, 41, 16]
    chroma = [(128, 128), (16, 146), (166, 16), (54, 34), (202, 222), (90, 240), (240, 110), (128, 128)]
    # Two widths of bars, so that every shift is a slice.
    y_row = bytes(luma_bars[col * 8 // width % 8] for col in range(2 * width))
    u_row = bytes(chroma[col * 16 // width % 8][0] for col in range(width))
    v_row = bytes(chroma[col * 16 // width % 8][1] for col in range(width))
    started, index = time.monotonic(), 0
    while not seconds or index < seconds * rate:
        shift = index * 4 % width
        y = bytearray(y_row[shift : shift + width] * height)
        u = u_row[shift // 2 : shift // 2 + width // 2] * (height // 2)
        v = v_row[shift // 2 : shift // 2 + width // 2] * (height // 2)
        for s in range(index // rate % 10 + 1):  # a white block per elapsed second, top left
            for row in range(8, 16):
                at = row * width + 8 + s * 12
                y[at : at + 8] = b'\xeb' * 8
        yield bytes(y) + u + v
        index += 1
        time.sleep(max(0.0, started + index / rate - time.monotonic()))


def ffmpeg_pictures(source, width, height, rate, seconds):
    """The pictures of any ffmpeg source, scaled to the daemon's size and rate."""
    command = ['ffmpeg', '-v', 'error', '-re', '-i', source]
    if seconds:
        command += ['-t', str(seconds)]
    command += ['-vf', f'scale={width}:{height}', '-r', str(rate), '-pix_fmt', 'yuv420p', '-f', 'rawvideo', '-']
    size = width * height * 3 // 2
    with subprocess.Popen(command, stdout=subprocess.PIPE) as ffmpeg:
        while picture := ffmpeg.stdout.read(size):
            yield picture


def transmit_in_calls(agent, stop):
    """Ask for the permission to transmit whenever the agent is in an MCVideo call and not transmitting."""
    while not stop.is_set():
        try:
            status = agent.call('video_status')
            if status['state'] == 'active' and not status['transmitting']:
                got = agent.call('video_transmit')
                print(f"in a {status.get('type')} call: transmission {got['transmission']}", flush=True)
        except ToolError as error:
            print(f'video_transmit: {error}', flush=True)
        stop.wait(2)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--mcp-url', required=True, help='the MCP server over HTTP, e.g. http://mcx-agent:8765/mcp')
    parser.add_argument('--token-file', required=True, help='the bearer token')
    parser.add_argument('--source', help='an ffmpeg input (camera, RTSP URL, file) instead of the test card')
    parser.add_argument('--seconds', type=float, default=0, help='stop after this long (0: never)')
    args = parser.parse_args()
    agent = connect(args.mcp_url, args.token_file, name='video-bot')
    wait_ready(agent, 'mcvideo')
    feed = agent.call('video_status')['feed']
    width, height, rate = feed['width'], feed['height'], feed['frame_rate']
    print(f'video feed ready: {width}x{height} at {rate}/s', flush=True)

    with open(args.token_file) as f:
        token = f.readline().strip()
    url = args.mcp_url.rsplit('/mcp', 1)[0] + '/video'
    pictures = (
        ffmpeg_pictures(args.source, width, height, rate, args.seconds)
        if args.source
        else test_card(width, height, rate, args.seconds)
    )
    result = {}
    streaming = threading.Thread(target=lambda: result.update(stream_pictures(url, token, pictures)))
    streaming.start()
    stop = threading.Event()
    threading.Thread(target=transmit_in_calls, args=(agent, stop), daemon=True).start()
    streaming.join()
    stop.set()
    print(f'stream ended: {result}', flush=True)


if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(0)

#!/usr/bin/env python3
#
# OpenMCX Licence Information : AGPL-3.0-or-later
#

"""End-to-end check of the dispatcher on a running lab (see prepare.py).

Drives the dispatcher only through its web API, as the browser does, and checks the
effect on the lab's ordinary clients through their status files:
  DSP-01 registered and affiliated     DSP-05 private call to the dispatcher, answered
  DSP-02 group call, live talk         DSP-06 SDS both ways, one-to-one and group
  DSP-03 audio from a client heard     DSP-07 file distribution both ways
  DSP-04 private call from dispatcher  DSP-08 refuses what is not a dispatcher action
"""
import argparse
import base64
import json
import os
import socket
import struct
import subprocess
import sys
import threading
import time
import urllib.request
import uuid
import wave
from pathlib import Path

results = []


def check(name, ok, detail=""):
    results.append((name, bool(ok)))
    print(("PASS " if ok else "FAIL ") + name + (f": {detail}" if detail and not ok else ""), flush=True)
    return ok


class Lab:
    def __init__(self, directory: Path, project: str, url: str):
        self.dir, self.project, self.url = directory, project, url

    def api(self, method, path, body=None, headers=None, raw=None):
        data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
        h = {"X-Dispatcher": "1"}
        if body is not None:
            h["Content-Type"] = "application/json"
        h.update(headers or {})
        request = urllib.request.Request(self.url + path, data=data, method=method, headers=h)
        try:
            with urllib.request.urlopen(request, timeout=10) as r:
                return r.status, r.read()
        except urllib.error.HTTPError as e:
            return e.code, e.read()

    def state(self):
        status, raw = self.api("GET", "/api/state")
        return json.loads(raw) if status == 200 else {}

    def act(self, **body):
        status, raw = self.api("POST", "/api/command", body)
        if status != 202:
            print(f"  command {body} -> {status} {raw!r}")
        return status

    def client(self, name):
        path = self.dir / "artifacts" / name / "status.json"
        try:
            return json.loads(path.read_text())
        except (OSError, ValueError):
            return {}

    def tell(self, name, line):
        subprocess.run(["docker", "exec", f"{self.project}-{name}-1", "sh", "-c", f"printf '%s\\n' \"$0\" > /run/mcx/control", line],
                       check=True)


def wait(predicate, timeout=30.0, step=0.3):
    end = time.time() + timeout
    while time.time() < end:
        try:
            value = predicate()
        except (KeyError, TypeError, IndexError):
            value = None
        if value:
            return value
        time.sleep(step)
    return None


class Audio:
    """A minimal WebSocket client for /api/audio (binary PCM frames)."""

    def __init__(self, url):
        host, port = url.split("//")[1].split(":")
        self.sock = socket.create_connection((host, int(port)), timeout=5)
        key = base64.b64encode(os.urandom(16)).decode()
        self.sock.sendall((f"GET /api/audio HTTP/1.1\r\nHost: {host}:{port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                           f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\nOrigin: {url}\r\n\r\n").encode())
        reply = b""
        while b"\r\n\r\n" not in reply:
            reply += self.sock.recv(1024)
        self.upgraded = reply.startswith(b"HTTP/1.1 101")
        self.buffer = reply.split(b"\r\n\r\n", 1)[1]
        self.received = 0
        self.sock.settimeout(None)
        threading.Thread(target=self._read, daemon=True).start()

    def send(self, payload: bytes):
        mask = os.urandom(4)
        header = bytes([0x82]) + (bytes([0x80 | len(payload)]) if len(payload) < 126 else bytes([0x80 | 126]) + struct.pack(">H", len(payload)))
        self.sock.sendall(header + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(payload)))

    def _recv(self, n):
        while len(self.buffer) < n:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise EOFError
            self.buffer += chunk
        out, self.buffer = self.buffer[:n], self.buffer[n:]
        return out

    def _read(self):
        try:
            while True:
                first, second = self._recv(2)
                length = second & 0x7F
                if length == 126:
                    length = struct.unpack(">H", self._recv(2))[0]
                elif length == 127:
                    length = struct.unpack(">Q", self._recv(8))[0]
                payload = self._recv(length)
                if first & 0x0F == 2:
                    self.received += len(payload) // 2
        except (EOFError, OSError):
            pass


def tone(frames):
    import math
    for n in range(frames):
        yield b"".join(struct.pack("<h", int(8000 * math.sin(2 * math.pi * 440 * (n * 320 + i) / 16000))) for i in range(320))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("lab", type=Path)
    parser.add_argument("--project", required=True)
    parser.add_argument("--url", default="http://127.0.0.1:18080")
    args = parser.parse_args()
    lab = Lab(args.lab, args.project, args.url)

    s = wait(lambda: (st := lab.state()).get("registered") and st.get("affiliated") and st.get("mcdata_affiliated") and st, 90)
    check("DSP-01 registered and affiliated for MCPTT and MCData", s, json.dumps(lab.state())[:300])
    identity = (s or {}).get("identity", {})
    group, mcdata_group = identity.get("group"), identity.get("mcdata_group")
    a = lab.client("client-a")
    a_user, a_data = a.get("user_id"), json.loads((args.lab / "generated" / "client-a.json").read_text())["mcdata"]["user_id"]

    # DSP-02: the dispatcher starts a group call and talks live from the "browser".
    audio = Audio(args.url)
    check("DSP-02a audio socket upgraded", audio.upgraded)
    lab.act(action="call")
    ok = wait(lambda: lab.state().get("call_state") == "active", 20)
    check("DSP-02b group call active", ok, lab.state().get("call_state"))
    before = lab.client("client-a").get("received_packets", 0)
    lab.act(action="ptt")
    granted = wait(lambda: lab.state().get("floor_state") == "HasPermission", 10)
    for frame in tone(150):
        audio.send(frame)
        time.sleep(0.02)
    got = lab.client("client-a").get("received_packets", 0) - before
    check("DSP-02c floor granted and client-a received the dispatcher's voice", granted and got >= 100, f"granted={granted} packets={got}")
    lab.act(action="release")
    wait(lambda: lab.state().get("floor_state") != "HasPermission", 10)

    # DSP-03: client-a talks; the dispatcher's browser gets its audio.
    speech = args.lab / "artifacts" / "speech"
    speech.mkdir(parents=True, exist_ok=True)
    with wave.open(str(speech / "dispatch-tone.wav"), "wb") as w:
        w.setnchannels(1), w.setsampwidth(2), w.setframerate(16000)
        w.writeframes(b"".join(tone(150)))
    heard = audio.received
    time.sleep(1)
    lab.tell("client-a", "ptt /artifacts/speech/dispatch-tone.wav")
    time.sleep(4)
    lab.tell("client-a", "release")
    check("DSP-03 the browser received client-a's audio", audio.received - heard >= 16000, f"samples={audio.received - heard}")
    lab.act(action="hangup")
    wait(lambda: lab.state().get("call_state") == "idle", 15)
    wait(lambda: lab.client("client-a").get("call_state") == "idle", 15)
    time.sleep(1)

    # DSP-04: a private call from the dispatcher to client-b.
    b_user = lab.client("client-b").get("user_id")
    lab.act(action="private", target=b_user)
    ok = wait(lambda: lab.state().get("call_state") == "active" and lab.client("client-b").get("call_state") == "active", 20)
    check("DSP-04 private call from the dispatcher to client-b", ok, f"{lab.state().get('call_state')}/{lab.client('client-b').get('call_state')}")
    lab.act(action="hangup")
    wait(lambda: lab.state().get("call_state") == "idle" and lab.client("client-b").get("call_state") == "idle", 15)
    time.sleep(1)

    # DSP-05: client-a calls the dispatcher with manual answer; the dispatcher answers.
    lab.tell("client-a", f"private {s['identity']['user'] if s else ''} manual")
    ringing = wait(lambda: lab.state().get("call_state") == "ringing", 20)
    lab.act(action="answer")
    ok = wait(lambda: lab.state().get("call_state") == "active", 15)
    check("DSP-05 private call to the dispatcher rings and is answered", ringing and ok, str(lab.state().get("call_state")))
    lab.act(action="hangup")
    wait(lambda: lab.state().get("call_state") == "idle", 15)

    # DSP-06: SDS both ways.
    text = "dispatch " + uuid.uuid4().hex[:8]
    lab.act(action="sds", target=a_data, text=text)
    inbox_has = lambda status, t: any(any(p.get("text") == t for p in m.get("payloads", [])) for m in status.get("mcdata_inbox", []))
    ok1 = wait(lambda: inbox_has(lab.client("client-a"), text), 20)
    text2 = "group " + uuid.uuid4().hex[:8]
    lab.act(action="sds", target=mcdata_group, group=True, text=text2)
    ok2 = wait(lambda: inbox_has(lab.client("client-a"), text2), 20)
    text3 = "reply " + uuid.uuid4().hex[:8]
    lab.tell("client-a", f"sds {identity.get('mcdata_user')} {text3}")
    ok3 = wait(lambda: inbox_has(lab.state(), text3), 20)
    check("DSP-06 SDS one-to-one and group from the dispatcher, and to it", ok1 and ok2 and ok3, f"{ok1} {ok2} {ok3}")

    # DSP-07: file distribution both ways.
    name = f"report-{uuid.uuid4().hex[:6]}.txt"
    body = f"incident report {name}\n".encode() * 50
    boundary = uuid.uuid4().hex
    form = b"".join([
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"target\"\r\n\r\n{a_data}\r\n".encode(),
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{name}\"\r\nContent-Type: text/plain\r\n\r\n".encode(),
        body, f"\r\n--{boundary}--\r\n".encode()])
    status, raw = lab.api("POST", "/api/fd", raw=form, headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
    offered = wait(lambda: next((f for f in lab.client("client-a").get("mcdata_files", []) if f.get("name") == name), None), 30)
    if offered:
        lab.tell("client-a", f"fd-accept {offered['message_id']}")
    got = wait(lambda: next((f for f in lab.client("client-a").get("mcdata_files", [])
                             if f.get("name") == name and f.get("state") == "downloaded"), None), 30)
    check("DSP-07a file from the dispatcher downloaded by client-a", status == 202 and got and got.get("plain_bytes") == len(body),
          f"{status} {raw[:200]!r} {offered}")
    # client-a sends a file back (within its 300-byte one-to-one limit); the dispatcher accepts it and serves it to the browser.
    back = f"photo-{uuid.uuid4().hex[:6]}.txt"
    subprocess.run(["docker", "exec", f"{args.project}-client-a-1", "sh", "-c", f"printf 'field note\\n%.0s' $(seq 1 20) > /tmp/{back}"], check=True)
    lab.tell("client-a", f"fd-send {identity.get('mcdata_user')} /tmp/{back}")
    offer = wait(lambda: next((f for f in lab.state().get("mcdata_files", []) if f.get("name") == back), None), 30)
    if offer:
        lab.act(action="fd-accept", id=offer["message_id"])
    done = offer and wait(lambda: next((f for f in lab.state().get("mcdata_files", [])
                                        if f.get("name") == back and f.get("state") == "downloaded"), None), 30)
    status, content = lab.api("GET", f"/api/files/{offer['message_id']}") if done else (0, b"")
    check("DSP-07b file to the dispatcher accepted and saved from the browser", status == 200 and content == b"field note\n" * 20,
          f"{offer} {status} {len(content)}")

    # DSP-08: only dispatcher actions pass, and only from the page. A multi-line SDS goes to the engine escaped (one
    # command line), so its second line is text, not a command (DSP-08b: the engine is still registered).
    refused = [lab.act(action="quit"), lab.act(action="sds", target=a_data, text="x\x1bquit"),
               lab.api("POST", "/api/command", {"action": "call"}, headers={"X-Dispatcher": ""})[0],
               lab.act(action="private", target="a b")]
    check("DSP-08 unknown, control-character and cross-site commands refused", refused == [400, 400, 403, 400],
          str(refused))
    check("DSP-08 multi-line SDS sent escaped", lab.act(action="sds", target=a_data, text="x\nquit") == 202)
    alive = lab.state().get("registered")
    check("DSP-08b engine still registered after the refused commands", alive)

    failed = [n for n, ok in results if not ok]
    print(f"{len(results) - len(failed)}/{len(results)} passed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()

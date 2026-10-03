#!/usr/bin/env python3
"""Attachments in Messages between two phones: MCData file distribution over HTTP.

    tests/attachments.py LAB [--a client-a] [--b client-b] [--large] [--screenshots]

LAB is an androidlab.py phone lab made with --fd-max-bytes and two --phone-device phones,
both installed (install-phone). The files are generated here: a PNG image, a PDF document
and a playable MP4 video (encoded in a throwaway Alpine container with ffmpeg), plus, with
--large, a file of exactly the lab's file limit and one of a byte more. Each is pushed to
the sending phone and sent with the debug control endpoint's fd-send, which stages it as the
document picker does. The receiver's downloaded copy is hashed on the phone and compared with
the original. The report goes to lab/run/LAB/attachments/report.json, with screenshots of the
received files opened in the phone's viewers when --screenshots is given.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import struct
import subprocess
import sys
import time
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "lab"))
from androidlab import ADB, RUNS, meta  # noqa: E402
from scenarios import Failed, Lab  # noqa: E402

REMOTE = "/data/local/tmp/mcxfd"


def png(path: Path, width: int = 320, height: int = 200) -> None:
    """A gradient PNG, written without an imaging library."""
    rows = b"".join(b"\x00" + bytes(v for x in range(width) for v in (x * 255 // width, y * 255 // height, 160))
                    for y in range(height))

    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)) +
                     chunk(b"IDAT", zlib.compress(rows, 9)) + chunk(b"IEND", b""))


def pdf(path: Path, text: str) -> None:
    """A one-page PDF with a line of text and a correct cross-reference table."""
    stream = f"BT /F1 24 Tf 72 720 Td ({text}) Tj ET".encode()
    objects = [b"<< /Type /Catalog /Pages 2 0 R >>", b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
               b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
               b"<< /Length %d >>\nstream\n" % len(stream) + stream + b"\nendstream",
               b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"]
    out = bytearray(b"%PDF-1.4\n")
    offsets = []
    for i, body in enumerate(objects, 1):
        offsets.append(len(out))
        out += b"%d 0 obj\n" % i + body + b"\nendobj\n"
    xref = len(out)
    out += b"xref\n0 %d\n0000000000 65535 f \n" % (len(objects) + 1)
    out += b"".join(b"%010d 00000 n \n" % o for o in offsets)
    out += b"trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(objects) + 1, xref)
    path.write_bytes(bytes(out))


def mp4(path: Path, seconds: int = 4) -> None:
    """A playable H.264/AAC MP4 (test pattern and tone), encoded in a throwaway container."""
    script = (f"apk add --no-cache ffmpeg >/dev/null && ffmpeg -loglevel error -f lavfi -i testsrc=size=640x360:rate=25 "
              f"-f lavfi -i sine=frequency=440 -t {seconds} -c:v libx264 -pix_fmt yuv420p -c:a aac -movflags +faststart "
              f"-y /out/{path.name}")
    subprocess.run(["docker", "run", "--rm", "-v", f"{path.parent}:/out", "alpine:latest", "sh", "-c", script + f" && chown {os.getuid()}:{os.getgid()} /out/{path.name}"],
                   check=True)


def big(path: Path, size: int) -> None:
    """[size] pseudo-random bytes (incompressible, so nothing on the way can shrink them)."""
    with path.open("wb") as out:
        seed = hashlib.sha256(b"openmcx-fd").digest()
        left = size
        while left:
            block = hashlib.sha256(seed).digest() * 2048  # 64 KiB
            seed = block[:32]
            out.write(block[:min(left, len(block))])
            left -= min(left, len(block))


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


class Attachments(Lab):
    def __init__(self, name: str, a: str, b: str, screenshots: bool) -> None:
        super().__init__(name)
        self.a, self.b = a, b
        self.shots = screenshots
        self.out = RUNS / name / "attachments"
        self.out.mkdir(exist_ok=True)
        self.ids = {c: json.loads((RUNS / name / "provisioning" / c / "bundle.json").read_text())["mcdata"]["user_id"] for c in (a, b)}
        self.group = json.loads((RUNS / name / "provisioning" / a / "bundle.json").read_text())["mcdata"]["groups"][0]
        self.transfers: list[dict] = []

    def ready(self, client: str, timeout: float = 120) -> dict:
        return self.wait(f"{client} ready with MCData", lambda: (s := self.status(client))["phase"] == "ready"
                         and s["mcdata"]["authorised"] and s, timeout=timeout)

    def push(self, client: str, local: Path) -> str:
        device = self.devices[client]
        device.adb("shell", "mkdir", "-p", REMOTE)
        device.adb("shell", "chmod", "755", REMOTE)
        device.adb("push", str(local), f"{REMOTE}/{local.name}")
        device.adb("shell", "chmod", "644", f"{REMOTE}/{local.name}")
        return f"{REMOTE}/{local.name}"

    def record(self, client: str, file_id: str) -> dict | None:
        return next((r for r in self.cmd(client, "fd-files") if r["id"] == file_id), None)

    def transfer(self, sender: str, receiver: str, local: Path, kind: str, group: bool = False,
                 others: tuple[str, ...] = (), timeout: float = 900) -> dict:
        """Send [local] and have [receiver] download it; checks every step and returns the row for the report."""
        remote = self.push(sender, local)
        original = sha256(local)
        target = self.group if group else self.ids[receiver]
        started = time.monotonic()
        sent = self.cmd(sender, "fd-send-group" if group else "fd-send", target, remote, local.name, kind, timeout=timeout)
        upload_s = time.monotonic() - started
        label = f"{sender} → {'group' if group else receiver}: {local.name} ({local.stat().st_size} bytes)"
        self.check(f"{label}: sent", sent["state"] == "sent", error=sent.get("error"))
        if sent["state"] != "sent":
            raise Failed(f"{label}: {sent.get('error')}")
        self.check(f"{label}: staged hash is the original's", sent["sha256"] == original)
        offer = self.wait(f"{receiver} is offered {local.name}", lambda: self.record(receiver, sent["id"]), timeout=30)
        self.check(f"{label}: offered to {receiver} from {self.ids[sender]}", offer["peer"] == self.ids[sender]
                   and offer.get("group") == (self.group if group else None) and offer["name"] == local.name, offer=offer)
        started = time.monotonic()
        done = self.cmd(receiver, "fd-download", sent["id"], timeout=timeout)
        download_s = time.monotonic() - started
        self.check(f"{label}: downloaded", done["state"] == "completed", error=done.get("error"))
        received = self.cmd(receiver, "fd-hash", sent["id"]) if done["state"] == "completed" else None
        self.check(f"{label}: received file hash matches the original", received == original, received=received, original=original)
        self.check(f"{label}: openable on {receiver}", done.get("openable") is True)
        n = self.wait(f"{sender} learns {receiver} completed", lambda: (r := self.record(sender, sent["id"]))
                      and r["notifications"].get(self.ids[receiver]) == "completed" and r["notifications"], timeout=60)
        self.check(f"{label}: {sender} gets COMPLETED from {receiver}", n.get(self.ids[receiver]) == "completed", notifications=n)
        for other in others:
            own = self.record(other, sent["id"])
            self.check(f"{label}: {other} (the sender) is not offered its own file",
                       own is None or own["direction"] == "outgoing", record=own)
        row = {"from": sender, "to": "group" if group else receiver, "file": local.name, "type": kind,
               "bytes": local.stat().st_size, "sha256": original, "received_sha256": received, "id": sent["id"],
               "upload_s": round(upload_s, 2), "download_s": round(download_s, 2), "state": done["state"]}
        self.transfers.append(row)
        if self.shots and kind.split("/")[0] in ("image", "video", "application"):
            self.screenshot(receiver, sent["id"], local.stem)
        return row

    def screenshot(self, client: str, file_id: str, stem: str) -> None:
        device = self.devices[client]
        try:
            self.cmd(client, "fd-open", file_id)
            time.sleep(4)
            png_bytes = subprocess.run([str(ADB), "-s", device.serial, "exec-out", "screencap", "-p"],
                                       capture_output=True, check=True).stdout
            (self.out / f"{client}-{stem}.png").write_bytes(png_bytes)
        except Exception as e:  # evidence only; the checks above decide
            print(f"  screenshot of {stem} on {client} failed: {e}")
        finally:
            # Leave the viewer, or the "Open with" chooser a phone shows when it has no default app
            # for the type, until the client is in front again.
            for _ in range(4):
                focus = device.adb("shell", "dumpsys", "window", check=False)
                focus = next((l for l in focus.splitlines() if "mCurrentFocus" in l), "")
                if "org.openmcx.client.app.MainActivity" in focus:
                    break
                device.adb("shell", "input", "keyevent", "KEYCODE_BACK", check=False)
                time.sleep(1)
            device.adb("shell", "am", "start", "-n", "org.openmcx.client/.app.MainActivity", "--es", "tab", "messages", check=False)

    def run(self, large: bool) -> None:
        a, b = self.a, self.b
        for c in (a, b):
            self.ready(c)
        fixtures = self.out / "fixtures"
        fixtures.mkdir(exist_ok=True)
        image, document, video = fixtures / "gradient.png", fixtures / "report.pdf", fixtures / "clip.mp4"
        png(image); pdf(document, f"openMCX attachment test {time.strftime('%Y-%m-%d %H:%M')}")
        if not video.exists():
            mp4(video)

        self.scenario = "private"
        self.transfer(a, b, image, "image/png")
        self.transfer(b, a, document, "application/pdf")
        self.transfer(a, b, video, "video/mp4")
        self.transfer(b, a, video, "video/mp4")
        self.transfer(a, b, document, "application/pdf")
        self.transfer(b, a, image, "image/png")

        self.scenario = "group"
        self.transfer(a, b, image, "image/png", group=True, others=(a,))

        self.scenario = "reject"
        remote = self.push(b, document)
        sent = self.cmd(b, "fd-send", self.ids[a], remote, "declined.pdf", "application/pdf")
        self.wait("alice is offered declined.pdf", lambda: self.record(a, sent["id"]), timeout=30)
        rejected = self.cmd(a, "fd-reject", sent["id"])
        self.check("alice rejects: state rejected", rejected["state"] == "rejected")
        n = self.wait("bob learns alice rejected", lambda: (r := self.record(b, sent["id"]))
                      and r["notifications"].get(self.ids[a]) == "rejected" and r["notifications"], timeout=60)
        self.check("bob gets REJECTED from alice", n.get(self.ids[a]) == "rejected", notifications=n)
        again = self.cmd(a, "fd-download", sent["id"], ok=False)
        self.check("a rejected file cannot be downloaded", not again.get("ok"), reply=again)

        self.scenario = "text"
        stamp = time.strftime("%H%M%S")
        for sender, receiver in ((a, b), (b, a)):
            text = f"text still works {sender} {stamp}"
            self.cmd(sender, "sds", self.ids[receiver], text, "--disposition", "delivery")
            got = self.wait(f"{receiver} receives the SDS", lambda: next((m for m in self.status(receiver)["inbox"] if m["text"] == text), None))
            self.check(f"SDS {sender} → {receiver} still delivered", got["sender"] == self.ids[sender])

        if large:
            self.scenario = "large"
            limit = int(meta(self.name)["fd"]["service_max_bytes"])
            exact, over = fixtures / "limit.bin", fixtures / "over.bin"
            if not exact.exists() or exact.stat().st_size != limit:
                big(exact, limit)
            if not over.exists() or over.stat().st_size != limit + 1:
                big(over, limit + 1)
            row = self.transfer(a, b, exact, "application/octet-stream", timeout=1800)
            print(f"  {limit} bytes: upload {row['upload_s']} s, download {row['download_s']} s")
            remote = self.push(a, over)
            refused = self.cmd(a, "fd-send", self.ids[b], remote, over.name, "application/octet-stream", ok=False, timeout=600)
            self.check(f"{limit + 1} bytes is refused before upload", not refused.get("ok") and "limit" in (refused.get("error") or ""),
                       reply=refused)
            for c in (a, b):
                self.devices[c].adb("shell", "rm", "-f", f"{REMOTE}/{exact.name}", f"{REMOTE}/{over.name}", check=False)

    def report(self) -> Path:
        m = meta(self.name)
        doc = {"lab": self.name, "server_image": m.get("server_image"), "fd": m.get("fd"),
               "phones": {c: {"serial": self.devices[c].serial, "mcdata_id": self.ids[c]} for c in (self.a, self.b)},
               "at": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "transfers": self.transfers, "checks": self.results}
        path = self.out / "report.json"
        path.write_text(json.dumps(doc, indent=2) + "\n")
        return path


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("lab")
    parser.add_argument("--a", default="client-a", help="the first phone's role")
    parser.add_argument("--b", default="client-b", help="the second phone's role")
    parser.add_argument("--large", action="store_true", help="also send a file of exactly the limit, and refuse one byte more")
    parser.add_argument("--screenshots", action="store_true", help="open each received file in the phone's viewer and keep a screenshot")
    args = parser.parse_args()
    run = Attachments(args.lab, args.a, args.b, args.screenshots)
    try:
        run.run(args.large)
    except Failed as e:
        run.check(f"{run.scenario} completed", False, error=str(e))
    passed = sum(r["ok"] for r in run.results)
    print(f"\n{passed}/{len(run.results)} checks passed; report {run.report()}")
    sys.exit(0 if passed == len(run.results) else 1)


if __name__ == "__main__":
    main()

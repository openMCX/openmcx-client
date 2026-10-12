#!/usr/bin/env python3
"""MCVideo private video calls between two phones (TS 24.281 §10.2, TS 24.581).

    tests/video.py LAB [--a client-a] [--b client-b] [--seconds 6] [--screenshots]

LAB is an androidlab.py phone lab made with --mcvideo and two installed phones. The phones'
cameras are used, so the app must be in the foreground on both (the script brings it there).
Checks, through the debug control endpoint:

- a call with transmission control: each side in turn holds Transmit; the other is told who
  transmits, accepts reception, and decodes the video, every received picture identical to the
  one sent (fingerprints of the NAL units); the voice arrives; a request while the other side
  transmits is rejected; the call is released on both sides;
- a two-way call without transmission control: both cameras at once, pictures identical, and
  no audio (the server relays none: nobody holds the transmission);
- ringing (manual commencement): the called phone rings and shows the incoming call notification
  until the user answers (then video flows), declines (the caller is told: warning 110) or lets it
  ring out (after 20 s the caller is told it was not answered); with "ring first" on, an automatic
  call rings too;
- emergency (§6.2.8): an emergency group call that every member sees (the reference client may
  not cancel it: 403); the emergency user's transmission request pre-empts a normal transmitter;
  cancelling returns everyone to normal; imminent peril raised and ended; an emergency private call;
- emergency alerts (§11.2), with fixed coordinates: alice's alert reaches bob and the reference
  client with her location; the reference client may not alert (403); bob's own alert reaches
  alice; bob may not cancel it (403); alice, authorised, cancels bob's and then her own, and the
  alerts disappear everywhere;
- video push and pull (§12.2, §13.2): alice pushes her camera to bob and pulls bob's, the sender
  transmitting without asking; alice records a push on the server and bob plays it back, every
  picture identical to alice's; in a group call alice starts and stops bob's transmission remotely,
  and bob, not authorised, is refused;
- switching camera during a transmission: alice's transmission goes on while she switches from
  the back camera to the front one and back; each camera delivers frames once switched, the
  transmission is never interrupted (no new request), and every picture bob receives, across both
  switches, is identical to the one sent;
- ambient viewing (§15): alice views bob's camera (remote-init): bob's phone answers and transmits
  by itself and shows nothing (no call screen, no notification), every picture identical at alice;
  bob may not release it (no release capability), alice may; alice asks bob to view her camera
  (local-init) and bob ends it; bob, not authorised, is refused (403); a call to bob from the
  reference client ends alice's viewing, with the release reason call-request-for-viewed-to-client;
- a chat group's video call (§9.2.2): joining invites nobody; the phones (and the reference
  client) join one by one and list each other; video reaches everyone who joined; a member who
  leaves and rejoins while someone transmits decodes from the next key picture;
- on a protected lab (androidlab.py create --protected), the protection itself (TS 33.180): both phones
  keyed by the KMS as their MCVideo IDs, each with its CSK; a private call's media under a PCK the caller
  generates and the called phone authenticates (the same PCK-ID at both), transmission control as SRTCP
  (a capture at the MCVideo server shows no transmission control message in the clear), pictures
  identical; a tampered PCK refused with 488 and warning 136; a protected call without transmission
  control; each group's GMK (the same GMK-ID at both phones, from the GKTP subscription) and a group call
  under it, pictures identical. The other parts run protected too, with the reference client as a member;
- a prearranged group video call on the phones' group: every affiliated member is invited, and
  each transmission reaches every other member. When the lab has a reference client (the C++
  mcx-client, `androidlab.py create --reference-client client-c`), it is a third member: it
  decodes the phones' video with its own H.264 decoder, and the phones decode its video.

The report goes to lab/run/LAB/video/report.json, with screenshots of each receiver when asked.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "lab"))
from androidlab import ADB, PACKAGE, RUNS, compose_bin, meta  # noqa: E402
from scenarios import Failed, Lab  # noqa: E402

# Receiving: at least this share of the pictures sent must be decoded (Wi-Fi loses some), all identical.
MIN_PICTURES = 0.6
MIN_AUDIO = 0.8


class Video(Lab):
    def __init__(self, name: str, a: str, b: str, seconds: float, screenshots: bool) -> None:
        super().__init__(name)
        self.a, self.b, self.seconds, self.shots = a, b, seconds, screenshots
        self.out = RUNS / name / "video"
        self.out.mkdir(exist_ok=True)
        bundle = lambda c: json.loads((RUNS / name / "provisioning" / c / "bundle.json").read_text())
        self.mcptt = {c: bundle(c)["mcptt"]["user_id"] for c in (a, b)}
        self.mcvideo = {c: bundle(c)["mcvideo"]["user_id"] for c in (a, b)}
        self.rows: list[dict] = []
        m = meta(name)
        self.refs = m.get("reference_clients", [])
        self.compose = [*compose_bin(Path(m["openmcx"])), "-p", m["project"], "-f", str(RUNS / name / "compose.json")]
        self.protected_lab = bool(m.get("protected"))
        self.ref_ids = {r: json.loads((RUNS / name / "fixture" / "generated" / f"{r}.json").read_text())["mcvideo"]["user_id"] for r in self.refs}

    # The reference client: commands through its control FIFO, state from its status file.
    def ref_status(self, role: str) -> dict:
        return json.loads((RUNS / self.name / "fixture" / "artifacts" / role / "status.json").read_text())

    def ref_command(self, role: str, text: str) -> None:
        out = subprocess.run([*self.compose, "exec", "-T", role, "mcx-client", "--config", f"/config/{role}.json", "--command", text],
                             capture_output=True, text=True)
        if out.returncode:
            raise Failed(f"{role} {text}: {out.stderr.strip()[-300:]}")

    def front(self) -> None:
        for c in (self.a, self.b):
            self.devices[c].adb("shell", "am", "start", "-n", f"{PACKAGE}/.app.MainActivity", check=False)
        time.sleep(1.5)

    def video(self, client: str) -> dict:
        return self.cmd(client, "video-status")

    def active(self, client: str) -> dict | None:
        return self.video(client).get("active")

    def fingerprints(self, sender: str, receiver: str) -> tuple[int, int]:
        # The receiver first: while media still flows, every picture it has is then in the sender's list.
        got = self.cmd(receiver, "video-hashes").get("received", {})
        sent = self.cmd(sender, "video-hashes").get("sent", {})
        common = [k for k in got if k in sent]
        if got and not common:
            print(f"    fingerprints: no common key; {sender} sent {sorted(sent)[:2]}, {receiver} received {sorted(got)[:2]}")
        return sum(1 for k in common if sent[k] == got[k]), len(got)

    def screenshot(self, client: str, name: str) -> None:
        png = subprocess.run([str(ADB), "-s", self.devices[client].serial, "exec-out", "screencap", "-p"], capture_output=True).stdout
        (self.out / f"{name}.png").write_bytes(png)

    def transmit(self, talker: str, listener: str) -> None:
        label = f"{talker} → {listener}"
        before = self.active(listener)
        rx0, audio0 = before["pictures_received"], before["audio_received"]
        result: dict = {}
        worker = threading.Thread(target=lambda: result.update(self.cmd(talker, "video-transmit", str(self.seconds), timeout=self.seconds + 30)))
        worker.start()
        time.sleep(min(3.0, self.seconds / 2))
        if self.shots:
            self.screenshot(listener, f"{listener}-receiving-{talker}")
        mid = self.active(listener)
        self.check(f"{label}: {listener} is told {talker} transmits", mid["transmitter"] == self.mcvideo[talker], transmitter=mid["transmitter"])
        self.check(f"{label}: {listener} accepted reception", mid["receiving"] is True)
        # The listener asks to transmit meanwhile: rejected, the transmission is taken.
        refused = self.cmd(listener, "video-transmit", "1", timeout=20)
        self.check(f"{label}: {listener}'s request while {talker} transmits is rejected", refused.get("granted") is False, reply=refused)
        worker.join()
        time.sleep(1.5)
        self.check(f"{label}: granted", result.get("granted") is True, result=result)
        after = self.active(listener)
        received = after["pictures_received"] - rx0
        audio = after["audio_received"] - audio0
        same, total = self.fingerprints(talker, listener)
        sent = result.get("pictures", 0)
        self.check(f"{label}: {listener} decoded ≥{MIN_PICTURES:.0%} of {sent} pictures", sent > 0 and received >= MIN_PICTURES * sent,
                   received=received, sent=sent, lost=after["lost"], skipped=after["skipped_before_key"])
        self.check(f"{label}: every received picture is identical to the one sent", total > 0 and same == total, same=same, total=total)
        self.check(f"{label}: {listener} hears {talker}", audio >= MIN_AUDIO * self.seconds * 50, audio=audio)
        self.rows.append({"call": "transmission control", "from": talker, "to": listener, "pictures_sent": sent, "pictures_decoded": received,
                          "identical": f"{same}/{total}", "lost": after["lost"], "audio_packets": audio})

    def run(self) -> None:
        a, b = self.a, self.b
        self.scenario = "setup"
        self.front()  # starts the app (and its control endpoint) if it is not running
        time.sleep(3)
        # Start clean: end any call a previous run left behind.
        for c in (a, b):
            for _ in range(3):
                if not (self.video(c) or {}).get("active"):
                    break
                self.cmd(c, "video-hangup", ok=False)
                time.sleep(1)
        for r in self.refs:
            if self.ref_status(r).get("mcvideo_call_state") == "active":
                self.ref_command(r, "mcvideo-hangup")
        # A reference client can lose its MCVideo affiliation at the server while believing it has it (seen after a
        # previous run's chat call); it is then never invited. Say so at once rather than fail half-way.
        server_status = RUNS / self.name / "fixture" / "artifacts" / "mcvideo-server" / "status.json"
        if self.refs and server_status.exists():
            affiliated = json.loads(server_status.read_text()).get("affiliations", {})
            for r in self.refs:
                if not affiliated.get(self.ref_ids[r]):
                    raise Failed(f"{r} is not affiliated at the MCVideo server (stale lab state): recreate the lab (down, up)")
        for c in (a, b):
            s = self.wait(f"{c} ready", lambda c=c: (st := self.status(c))["phase"] == "ready" and st, timeout=120)
            self.check(f"{c}: MCVideo authorised", s.get("mcvideo", {}).get("authorised") is True, mcvideo=s.get("mcvideo"))
        self.front()

        self.scenario = "transmission-control"
        call = self.cmd(a, "video-call", self.mcptt[b], timeout=30)
        self.check("the video call with transmission control is established", call["state"] == "established" and call["transmission_control"])
        incoming = self.wait(f"{b} has the call", lambda: self.active(b), timeout=10)
        self.check(f"{b} sees {a} calling", incoming["peer"] == self.mcvideo[a] and not incoming["outgoing"], peer=incoming["peer"])
        time.sleep(1)
        self.transmit(a, b)
        self.transmit(b, a)
        self.cmd(a, "video-hangup")
        self.wait(f"{b}'s call released", lambda: self.active(b) is None, timeout=10)
        self.check("the call is released on both sides", self.active(a) is None and self.active(b) is None)

        self.scenario = "two-way"
        self.front()
        call = self.cmd(a, "video-call", self.mcptt[b], "notc", timeout=30)
        self.check("the two-way video call is established", call["state"] == "established" and not call["transmission_control"])
        time.sleep(self.seconds + 2)
        if self.shots:
            self.screenshot(a, f"{a}-two-way"); self.screenshot(b, f"{b}-two-way")
        live = {c: self.active(c) for c in (a, b)}
        # Compare the fingerprints once both streams have stopped: while they run, the two phones'
        # lists are read at different moments.
        self.cmd(b, "video-hangup")
        self.wait(f"{a}'s call released", lambda: self.active(a) is None, timeout=10)
        for me, other in ((a, b), (b, a)):
            act = live[me]
            same, total = self.fingerprints(other, me)
            self.check(f"two-way: {me} decodes {other}'s camera", act["pictures_received"] >= MIN_PICTURES * self.seconds * 10,
                       received=act["pictures_received"], sent=act["pictures_sent"])
            self.check(f"two-way: {me}'s received pictures are identical to those sent", total > 0 and same == total, same=same, total=total)
            self.check(f"two-way: no audio sent or relayed ({me})", act["audio_sent"] == 0 and act["audio_received"] == 0,
                       sent=act["audio_sent"], received=act["audio_received"])
            self.rows.append({"call": "two-way", "from": other, "to": me, "pictures_decoded": act["pictures_received"],
                              "identical": f"{same}/{total}", "lost": act["lost"]})

    def notified(self, client: str) -> bool:
        # The app's incoming video call notification (Notifications.VIDEO_CALL_ID, 2000 + the call's number); the
        # call-in-progress notification (5000 + the number) is also in the call category and stays for the call.
        dump = self.devices[client].adb("shell", "dumpsys", "notification", check=False)
        return any(re.search(r"pkg=org\.openmcx\.client .*\bid=2\d{3}\b", line) and "category=call" in line
                   for line in dump.splitlines())

    def ring(self, *extra: str, timeout: float = 60) -> tuple[threading.Thread, dict]:
        """Place a call from a to b in the background (it returns once answered, declined or timed out)."""
        out: dict = {}
        worker = threading.Thread(target=lambda: out.update(self.devices[self.a].control(
            "video-call", self.mcptt[self.b], *extra, timeout=timeout)))
        worker.start()
        return worker, out

    def ringing(self) -> None:
        a, b = self.a, self.b
        self.scenario = "ringing"
        self.front()
        worker, out = self.ring("manual")
        callee = self.wait(f"{b} rings", lambda: (c := self.active(b)) and c["state"] == "ringing" and c, timeout=10)
        self.check(f"{b} rings with an incoming call from {a}", not callee["outgoing"] and callee["peer"] == self.mcvideo[a], call=callee)
        self.check(f"{a} hears it ringing", (self.active(a) or {}).get("state") == "ringing")
        self.check(f"{b} shows the incoming video call notification", self.notified(b))
        if self.shots:
            self.screenshot(b, f"{b}-ringing")
        self.cmd(b, "video-answer")
        worker.join()
        self.check(f"{b} answers: the call is established", out.get("ok") and out["result"]["state"] == "established", reply=out)
        self.check(f"the notification goes once {b} answers", not self.notified(b))
        before = self.active(b)["pictures_received"]
        self.cmd(a, "video-transmit", "3", timeout=30)
        time.sleep(1.5)
        self.check(f"video flows after {b} answered", self.active(b)["pictures_received"] > before)
        self.cmd(a, "video-hangup")
        self.wait("released", lambda: self.active(b) is None, timeout=10)

        worker, out = self.ring("manual")
        self.wait(f"{b} rings", lambda: (c := self.active(b)) and c["state"] == "ringing", timeout=10)
        self.cmd(b, "video-decline")
        worker.join()
        self.check(f"{b} declines: {a} is told (480, warning 110)", not out.get("ok") and "110" in out.get("error", ""), reply=out)
        self.wait("released", lambda: self.active(a) is None and self.active(b) is None, timeout=10)

        started = time.monotonic()
        worker, out = self.ring("manual")
        worker.join()
        rang = time.monotonic() - started
        self.check(f"unanswered: {a} is told it was not answered after ringing ~20 s", not out.get("ok")
                   and "not answered" in out.get("error", "") and 17 <= rang <= 29, seconds=round(rang, 1), reply=out)
        self.wait("released", lambda: self.active(a) is None and self.active(b) is None, timeout=10)

        # "Ring me first": an automatic call rings on b too.
        self.cmd(b, "video-answer-mode", "manual")
        try:
            worker, out = self.ring()
            callee = self.wait(f"{b} rings", lambda: (c := self.active(b)) and c["state"] == "ringing" and c, timeout=10)
            self.check(f"with ring first on, {a}'s automatic call rings at {b}", callee["state"] == "ringing")
            self.cmd(b, "video-answer")
            worker.join()
            self.check("and connects once answered", out.get("ok") and out["result"]["state"] == "established", reply=out)
            self.cmd(a, "video-hangup")
            self.wait("released", lambda: self.active(b) is None, timeout=10)
        finally:
            self.cmd(b, "video-answer-mode", "automatic")

    def emergency(self) -> None:
        a, b = self.a, self.b
        self.scenario = "emergency"
        self.front()
        call = self.cmd(a, "video-group", "emergency", timeout=30)
        self.check(f"{a} places an emergency group video call", call["state"] == "established" and call["priority"] == "emergency", call=call)
        self.wait(f"{b} is in the call", lambda: self.active(b), timeout=10)
        self.check(f"{b} is in the emergency state", self.wait(f"{b} emergency", lambda: (self.active(b) or {}).get("priority") == "emergency", timeout=10))
        for r in self.refs:
            self.wait(f"{r} in the call", lambda r=r: self.ref_status(r).get("mcvideo_call_state") == "active", timeout=15)
            self.check(f"{r} (reference client) is in the emergency state",
                       self.wait(f"{r} emergency", lambda r=r: self.ref_status(r).get("mcvideo_priority") == "emergency", timeout=10))
            self.ref_command(r, "mcvideo-emergency off")
            result = self.wait(f"{r}'s cancellation answered", lambda r=r: (self.ref_status(r).get("mcvideo_priority_result") or {}).get("status"), timeout=10)
            self.check(f"{r} may not cancel the emergency (403), which stays", result == 403 and self.active(a)["priority"] == "emergency",
                       result=self.ref_status(r).get("mcvideo_priority_result"))
        # Pre-emption (TS 24.581 §4.1.1.2): b transmits; a, in the emergency state, asks and gets the transmission.
        bob: dict = {}
        worker = threading.Thread(target=lambda: bob.update(self.cmd(b, "video-transmit", "8", timeout=40)))
        worker.start()
        time.sleep(2.5)
        alice = self.cmd(a, "video-transmit", "3", timeout=30)
        worker.join()
        self.check(f"{a}'s emergency request pre-empts {b}'s transmission", alice.get("granted") is True
                   and any(e.startswith("revoked") for e in self.active(b)["events"]), alice=alice, events=self.active(b)["events"][-5:])
        self.cmd(a, "video-emergency", "off", timeout=30)
        self.check(f"{a} cancels: {b} returns to normal",
                   self.wait(f"{b} normal", lambda: (self.active(b) or {}).get("priority") == "normal", timeout=10))
        for r in self.refs:
            self.check(f"{r} returns to normal", self.wait(f"{r} normal", lambda r=r: self.ref_status(r).get("mcvideo_priority") == "normal", timeout=10))
        self.cmd(a, "video-imminent", "on", timeout=30)
        self.check(f"imminent peril raised: {b} follows", self.wait(f"{b} imminent", lambda: (self.active(b) or {}).get("priority") == "imminent", timeout=10))
        self.cmd(a, "video-imminent", "off", timeout=30)
        self.check(f"imminent peril ended: {b} follows", self.wait(f"{b} normal", lambda: (self.active(b) or {}).get("priority") == "normal", timeout=10))
        for c in (a, b):
            if self.active(c):
                self.cmd(c, "video-hangup", ok=False)
        for r in self.refs:
            if self.ref_status(r).get("mcvideo_call_state") == "active":
                self.ref_command(r, "mcvideo-hangup")
        self.wait("released", lambda: self.active(a) is None and self.active(b) is None, timeout=10)
        call = self.cmd(a, "video-call", self.mcptt[b], "emergency", timeout=30)
        self.check(f"{a} places an emergency private video call", call["state"] == "established" and call["priority"] == "emergency", call=call)
        self.check(f"{b} is in the emergency state in the private call",
                   self.wait(f"{b} emergency", lambda: (self.active(b) or {}).get("priority") == "emergency", timeout=10))
        self.cmd(a, "video-hangup")
        self.wait("released", lambda: self.active(a) is None and self.active(b) is None, timeout=10)

    def alerts(self) -> None:
        a, b = self.a, self.b
        self.scenario = "alerts"
        near = lambda x, y: abs(x - y) < 1e-4  # the 24-bit coding keeps about a metre
        self.cmd(a, "video-alert", "on", "52.52", "13.405", timeout=30)
        got = self.wait(f"{b} receives {a}'s alert", lambda: self.cmd(b, "video-alerts").get(self.mcvideo[a]), timeout=10)
        self.check(f"alert: {b} receives {a}'s alert with her location", near(got["latitude"], 52.52) and near(got["longitude"], 13.405), alert=got)
        for r in self.refs:
            seen = self.wait(f"{r} receives the alert", lambda r=r: (self.ref_status(r).get("mcvideo_alerts") or {}).get(self.mcvideo[a]), timeout=10)
            loc = seen.get("location", {})
            self.check(f"alert: {r} (reference) decodes the same location", loc.get("latitude") == int(52.52 / 90 * 2**23)
                       and loc.get("longitude") == int(13.405 / 360 * 2**24), alert=seen)
            self.ref_command(r, "mcvideo-alert on")
            result = self.wait(f"{r}'s alert answered", lambda r=r: (self.ref_status(r).get("mcvideo_priority_result") or {}).get("action") == "alert"
                               and self.ref_status(r)["mcvideo_priority_result"], timeout=10)
            self.check(f"alert: {r} may not raise an alert (403)", result.get("status") == 403 and self.ref_ids[r] not in self.cmd(a, "video-alerts"), result=result)
        self.cmd(b, "video-alert", "on", "48.8566", "2.3522", timeout=30)
        got = self.wait(f"{a} receives {b}'s alert", lambda: self.cmd(a, "video-alerts").get(self.mcvideo[b]), timeout=10)
        self.check(f"alert: {a} receives {b}'s alert", near(got["latitude"], 48.8566) and near(got["longitude"], 2.3522), alert=got)
        refused = self.cmd(b, "video-alert", "off", timeout=30, ok=False)
        self.check(f"alert: {b} may not cancel (403); the alert stays", not refused.get("ok") and "403" in refused.get("error", "")
                   and self.mcvideo[b] in self.cmd(a, "video-alerts"), reply=refused)
        self.cmd(a, "video-alert", "off", "for", self.mcvideo[b], timeout=30)
        self.check(f"alert: {a} cancels {b}'s alert: {b} learns his alert ended",
                   self.wait(f"{b} not alerting", lambda: not self.cmd(b, "video-status").get("alerting"), timeout=10))
        for r in self.refs:
            self.check(f"alert: {a} cancels {b}'s alert: gone at {r}",
                       self.wait(f"{r} drops {b}'s alert", lambda r=r: self.mcvideo[b] not in (self.ref_status(r).get("mcvideo_alerts") or {}), timeout=10))
        self.cmd(a, "video-alert", "off", timeout=30)
        self.check(f"alert: {a} cancels hers: gone at {b}",
                   self.wait(f"{b} drops the alert", lambda: not self.cmd(b, "video-alerts"), timeout=10))
        for r in self.refs:
            self.check(f"alert: and at {r}", self.wait(f"{r} drops it", lambda r=r: not self.ref_status(r).get("mcvideo_alerts"), timeout=10))

    def pushpull(self) -> None:
        a, b = self.a, self.b
        self.scenario = "pushpull"
        self.front()
        for mode, sender, receiver in (("push", a, b), ("pull", b, a)):
            call = self.cmd(a, f"video-{mode}", self.mcptt[b], timeout=30)
            self.check(f"{mode}: the call is established", call["state"] == "established", call=call)
            self.wait(f"{receiver} gets pictures", lambda: (self.active(receiver) or {}).get("pictures_received", 0) >= 30, timeout=20)
            other = self.active(b)
            self.check(f"{mode}: {b} sees a one-to-one video {mode}", other.get("session_type") == f"one-to-one video {mode}", session=other.get("session_type"))
            same, total = self.fingerprints(sender, receiver)
            self.check(f"{mode}: {sender} sends without asking; {receiver}'s pictures identical", total >= 30 and same == total,
                       same=same, total=total)
            self.rows.append({"call": f"video {mode}", "from": sender, "to": receiver, "pictures_decoded": total, "identical": f"{same}/{total}"})
            self.cmd(a, "video-hangup")
            self.wait("released", lambda: self.active(a) is None and self.active(b) is None, timeout=10)
        self.cmd(a, "video-push-server", timeout=30)
        url = self.wait("the server's recording URL", lambda: (self.active(a) or {}).get("push_url"), timeout=15)
        time.sleep(self.seconds)
        sent = self.cmd(a, "video-hashes").get("sent", {})
        self.cmd(a, "video-hangup")
        self.check("push to server: recorded, with a URL", bool(url) and len(sent) >= 30, url=url, pictures=len(sent))
        self.wait("released", lambda: self.active(a) is None, timeout=10)
        self.cmd(b, "video-pull-server", url, timeout=30)
        # Play until the recording is over: no new picture for two seconds (or the server ends the call).
        last, still, deadline = -1, 0, time.monotonic() + 3 * self.seconds + 10
        while time.monotonic() < deadline and still < 4:
            time.sleep(0.5)
            now = (self.active(b) or {}).get("pictures_received", last)
            still = still + 1 if now == last else 0
            last = now
        got = self.cmd(b, "video-hashes").get("received", {})
        common = [k for k in got if k in sent]
        same = sum(1 for k in common if sent[k] == got[k])
        self.check(f"pull from server: {b} plays the recording, pictures identical to {a}'s (same SSRC and timestamps)",
                   len(common) >= 0.9 * len(sent) and same == len(common), same=same, common=len(common), sent=len(sent))
        self.rows.append({"call": "pull from server", "from": a, "to": b, "pictures_decoded": len(got), "identical": f"{same}/{len(common)}"})
        self.cmd(b, "video-hangup")
        self.wait("released", lambda: self.active(b) is None, timeout=10)
        # Remote transmission in a group call.
        self.cmd(a, "video-group", timeout=30)
        self.wait(f"{b} in the call", lambda: self.active(b), timeout=10)
        time.sleep(1)
        self.cmd(a, "video-remote", self.mcvideo[b], "start")
        self.check(f"remote: {a} starts {b}'s transmission", self.wait(f"{b} transmits", lambda: (self.active(b) or {}).get("transmission") == "HAS_PERMISSION", timeout=10)
                   and self.wait("remote start done", lambda: "remote start done" in self.active(a)["events"], timeout=10))
        self.check(f"remote: {a} receives {b}'s video", self.wait("pictures", lambda: self.active(a)["pictures_received"] >= 15, timeout=15))
        self.cmd(a, "video-remote", self.mcvideo[b], "stop")
        self.check(f"remote: {a} stops it; {b} is revoked (cause 5)", self.wait(f"{b} revoked", lambda: "revoked cause 5" in self.active(b)["events"], timeout=10)
                   and self.wait("remote stop done", lambda: "remote stop done" in self.active(a)["events"], timeout=10))
        self.cmd(b, "video-remote", self.mcvideo[a], "start")
        self.check(f"remote: {b} may not start {a}'s transmission", self.wait("not done", lambda: "remote start not done" in self.active(b)["events"], timeout=10)
                   and self.active(a)["transmission"] != "HAS_PERMISSION")
        for c in (a, b):
            if self.active(c):
                self.cmd(c, "video-hangup", ok=False)
        for r in self.refs:
            if self.ref_status(r).get("mcvideo_call_state") == "active":
                self.ref_command(r, "mcvideo-hangup")
        self.wait("released", lambda: self.active(a) is None and self.active(b) is None, timeout=10)

    def camera(self) -> None:
        a, b = self.a, self.b
        self.scenario = "camera switch"
        self.front()
        self.cmd(a, "video-camera", "back")
        call = self.cmd(a, "video-call", self.mcptt[b], timeout=30)
        self.check("camera: the call is established", call["state"] == "established", call=call)
        self.wait(f"{b} in the call", lambda: self.active(b), timeout=10)
        rx0 = self.active(b)["pictures_received"]
        switches0 = self.video(a)["camera_switches"]
        result: dict = {}
        hold = 13.0  # both switches in one transmission, within the server's 15 s limit (max_talk_ms)
        worker = threading.Thread(target=lambda: result.update(self.cmd(a, "video-transmit", str(hold), timeout=hold + 30)))
        worker.start()
        self.wait(f"{a} transmits", lambda: (self.active(a) or {}).get("transmission") == "HAS_PERMISSION", timeout=10)
        time.sleep(1.5)
        for facing in ("front", "back"):
            if self.shots:
                self.screenshot(b, f"{b}-before-{facing}")
            sent0 = self.active(a)["pictures_sent"]
            self.cmd(a, "video-camera", facing)
            status = self.wait(f"the {facing} camera delivers", lambda: (lambda v: v if v.get("camera") == facing and (v.get("camera_frames") or 0) >= 15 else None)(self.video(a)), timeout=10)
            self.check(f"camera: switched to the {facing} camera during the transmission; it delivers frames",
                       bool(status), camera=status and status.get("camera"), frames=status and status.get("camera_frames"))
            time.sleep(1.5)
            act = self.active(a)
            self.check(f"camera: still transmitting after the switch to {facing}, pictures still sent",
                       act["transmission"] == "HAS_PERMISSION" and act["pictures_sent"] - sent0 >= 10,
                       transmission=act["transmission"], sent=act["pictures_sent"] - sent0)
            if self.shots:
                self.screenshot(b, f"{b}-after-{facing}")
        worker.join()
        time.sleep(1.5)
        requests = [e for e in self.active(a)["events"] if e.startswith("granted")]
        self.check("camera: one grant for the whole transmission (no new request)", result.get("granted") is True and len(requests) == 1,
                   grants=len(requests))
        self.check("camera: two switches counted", self.video(a)["camera_switches"] - switches0 == 2)
        received = self.active(b)["pictures_received"] - rx0
        same, total = self.fingerprints(a, b)
        sent = result.get("pictures", 0)
        self.check(f"camera: {b} decoded ≥{MIN_PICTURES:.0%} of {sent} pictures across both switches", sent > 0 and received >= MIN_PICTURES * sent,
                   received=received, sent=sent)
        self.check(f"camera: every picture {b} received is identical to the one sent", total > 0 and same == total, same=same, total=total)
        self.rows.append({"call": "camera switch", "from": a, "to": b, "pictures_sent": sent, "pictures_decoded": received, "identical": f"{same}/{total}"})
        self.cmd(a, "video-hangup")
        self.wait("released", lambda: self.active(a) is None and self.active(b) is None, timeout=10)

    def capture(self, seconds_hint: float):
        """A packet capture of MCVideo's media ports at the MCVideo server, while the caller's block runs."""
        scratch = self.out / "capture"
        scratch.mkdir(exist_ok=True)
        name = f"{meta(self.name)['project']}-cap"
        server = f"{meta(self.name)['project']}-mcvideo-server-1"
        subprocess.run(["docker", "rm", "-f", name], capture_output=True)
        subprocess.run(["docker", "run", "-d", "--rm", "--name", name, "--net", f"container:{server}", "--cap-add", "NET_RAW",
                        "--cap-add", "NET_ADMIN", "--user", "root", "-v", f"{scratch}:/cap", "--entrypoint", "tcpdump",
                        meta(self.name)["server_image"], "-i", "any", "-w", "/cap/call.pcap", "-U", "udp portrange 7000-7599"],
                       check=True, capture_output=True)
        time.sleep(1.5)
        return name, scratch

    def capture_summary(self, name: str, scratch: Path) -> dict:
        subprocess.run(["docker", "stop", name], capture_output=True)
        run = lambda cmd: subprocess.run(["docker", "run", "--rm", "--user", "root", "-v", f"{scratch}:/cap", "--entrypoint", "sh",
                                          meta(self.name)["server_image"], "-c", cmd], capture_output=True, text=True).stdout
        packets = int(run("tcpdump -r /cap/call.pcap -nn 2>/dev/null | wc -l").strip() or 0)
        clear = int(run("tcpdump -r /cap/call.pcap -nn -A 2>/dev/null | grep -c -E 'MCV[012]'").strip() or 0)
        return {"packets": packets, "clear_control": clear}

    def protection(self) -> None:
        a, b = self.a, self.b
        self.scenario = "protection"
        self.front()
        keys = {c: self.cmd(c, "video-keys") for c in (a, b)}
        self.check("protection: both phones keyed by the KMS as their MCVideo IDs, each with a CSK",
                   all(k["kms"] and k["csk_id"] and (k["csk_id"] >> 28) == 2 and k["identity"] == self.mcvideo[c] for c, k in keys.items()), keys=keys)
        # A private call with transmission control.
        name, scratch = self.capture(10)
        call = self.cmd(a, "video-call", self.mcptt[b], timeout=30)
        other = self.wait(f"{b} in the call", lambda: self.active(b), timeout=10)
        self.check("protection: the private call's media is protected under one PCK, generated by the caller",
                   call["protected"] and other["protected"] and call["pck_id"] and call["pck_id"] == other["pck_id"] and (call["pck_id"] >> 28) == 1,
                   caller=call["pck_id"], called=other["pck_id"])
        self.check("protection: transmission control under each phone's CSK (SRTCP)", call["control_protected"] and other["control_protected"])
        time.sleep(1)
        result = self.cmd(a, "video-transmit", "4", timeout=40)
        time.sleep(1.5)
        same, total = self.fingerprints(a, b)
        self.check(f"protection: granted over SRTCP; {b}'s pictures identical", result.get("granted") is True and total >= 20 and same == total,
                   same=same, total=total)
        self.rows.append({"call": "protected private (PCK)", "from": a, "to": b, "pictures_decoded": total, "identical": f"{same}/{total}"})
        self.cmd(a, "video-hangup")
        self.wait("released", lambda: self.active(a) is None and self.active(b) is None, timeout=10)
        cap = self.capture_summary(name, scratch)
        self.check("protection: captured at the MCVideo server, no transmission control message in the clear",
                   cap["packets"] > 100 and cap["clear_control"] == 0, **cap)
        # A tampered PCK.
        self.cmd(a, "video-tamper-pck")
        refused = self.cmd(a, "video-call", self.mcptt[b], ok=False, timeout=30)
        self.check("protection: a tampered PCK is refused (488, warning 136)", not refused.get("ok") and "488" in str(refused.get("error"))
                   and "136" in str(refused.get("error")), reply=refused)
        self.wait("released", lambda: self.active(a) is None and self.active(b) is None, timeout=10)
        # Without transmission control: both send at once, each under its own CS-ID.
        call = self.cmd(a, "video-call", self.mcptt[b], "notc", timeout=30)
        time.sleep(self.seconds)
        self.cmd(b, "video-hangup")
        self.wait("released", lambda: self.active(a) is None, timeout=10)
        for me, other in ((a, b), (b, a)):
            same, total = self.fingerprints(other, me)
            self.check(f"protection: two-way, {me}'s pictures of {other} identical", total >= 20 and same == total, same=same, total=total)
            self.rows.append({"call": "protected two-way (PCK)", "from": other, "to": me, "pictures_decoded": total, "identical": f"{same}/{total}"})
        # Group calls under each group's GMK.
        keys = {c: self.wait(f"{c}'s group keys", lambda c=c: (lambda k: k if k.get("group_keys") else None)(self.cmd(c, "video-keys")), timeout=20)
                for c in (a, b)}
        groups = keys[a]["group_keys"]
        self.check("protection: both phones hold each group's GMK, the same GMK-ID (purpose tag 0)",
                   groups and groups == keys[b]["group_keys"] and all((i >> 28) == 0 for i in groups.values()), a=groups, b=keys[b]["group_keys"])
        call = self.cmd(a, "video-group", timeout=30)
        other = self.wait(f"{b} in the group call", lambda: self.active(b), timeout=10)
        group_id = list(groups.values())[0] if groups else None
        self.check("protection: the group call's media under the group's GMK at both phones",
                   call["protected"] and other["protected"] and call["gmk_id"] in groups.values() and call["gmk_id"] == other["gmk_id"],
                   caller=call["gmk_id"], called=other["gmk_id"])
        time.sleep(1)
        result = self.cmd(a, "video-transmit", "4", timeout=40)
        time.sleep(1.5)
        same, total = self.fingerprints(a, b)
        self.check(f"protection: group call, {b}'s pictures of {a} identical", result.get("granted") is True and total >= 20 and same == total,
                   same=same, total=total, failures=self.active(b).get("protection_failures"))
        self.rows.append({"call": "protected group (GMK)", "from": a, "to": b, "pictures_decoded": total, "identical": f"{same}/{total}"})
        for c in (a, b):
            if self.active(c):
                self.cmd(c, "video-hangup", ok=False)  # the server may end the group call meanwhile
        for r in self.refs:
            if self.ref_status(r).get("mcvideo_call_state") == "active":
                self.ref_command(r, "mcvideo-hangup")
        self.wait("released", lambda: self.active(a) is None and self.active(b) is None, timeout=10)

    def screen_text(self, client: str) -> str:
        dev = self.devices[client]
        dev.adb("shell", "uiautomator", "dump", "/sdcard/window.xml", check=False)
        return dev.adb("shell", "cat", "/sdcard/window.xml", check=False)

    def ambient(self) -> None:
        a, b = self.a, self.b
        self.scenario = "ambient"
        self.front()
        # Remote-init: alice views bob's camera.
        call = self.cmd(a, "video-ambient", self.mcptt[b], "remote", timeout=30)
        self.check("ambient remote-init: established; alice is viewing and may release",
                   call["state"] == "established" and call["ambient_role"] == "viewing" and call["may_release"] is True, call=call)
        bob = self.wait(f"{b} in the ambient call", lambda: self.active(b), timeout=10)
        self.check(f"ambient remote-init: {b} answered by itself as the viewed-to user, without the release capability",
                   bob["session_type"] == "ambient-viewing" and bob["ambient_type"] == "remote-init" and bob["ambient_role"] == "viewed-to"
                   and bob["may_release"] is False, bob={k: bob.get(k) for k in ("session_type", "ambient_type", "ambient_role", "may_release")})
        self.check(f"ambient remote-init: {b} transmits without asking",
                   self.wait(f"{b} transmits", lambda: (self.active(b) or {}).get("transmission") == "HAS_PERMISSION", timeout=10))
        self.wait(f"{a} gets pictures", lambda: (self.active(a) or {}).get("pictures_received", 0) >= 30, timeout=20)
        same, total = self.fingerprints(b, a)
        self.check(f"ambient remote-init: {a}'s pictures identical to {b}'s", total >= 30 and same == total, same=same, total=total)
        self.rows.append({"call": "ambient remote-init", "from": b, "to": a, "pictures_decoded": total, "identical": f"{same}/{total}"})
        self.check(f"ambient remote-init: {a} sends nothing", self.active(a)["pictures_sent"] == 0)
        text = self.screen_text(b)
        if self.shots:
            self.screenshot(b, f"{b}-viewed-to")
        self.check(f"ambient remote-init: {b} shows no call screen and no call notification",
                   "ambient" not in text and "viewing your camera" not in text and not self.notified(b), notified=self.notified(b))
        refused = self.cmd(b, "video-hangup", ok=False)
        time.sleep(1)
        self.check(f"ambient remote-init: {b}'s release is refused and the call stays up",
                   not refused.get("ok") and (self.active(b) or {}).get("state") == "established", reply=refused)
        self.cmd(a, "video-hangup")
        self.check(f"ambient remote-init: {a} releases; both idle",
                   self.wait("released", lambda: self.active(a) is None and self.active(b) is None, timeout=10))
        # Local-init: alice asks bob to view her camera.
        call = self.cmd(a, "video-ambient", self.mcptt[b], "local", timeout=30)
        self.check("ambient local-init: established; alice is viewed", call["state"] == "established" and call["ambient_role"] == "viewed-to", call=call)
        bob = self.wait(f"{b} in the ambient call", lambda: self.active(b), timeout=10)
        self.check(f"ambient local-init: {b} is viewing and may release", bob["ambient_role"] == "viewing" and bob["may_release"] is True)
        self.check(f"ambient local-init: {a} transmits without asking",
                   self.wait(f"{a} transmits", lambda: (self.active(a) or {}).get("transmission") == "HAS_PERMISSION", timeout=10))
        self.wait(f"{b} gets pictures", lambda: (self.active(b) or {}).get("pictures_received", 0) >= 30, timeout=20)
        same, total = self.fingerprints(a, b)
        self.check(f"ambient local-init: {b}'s pictures identical to {a}'s", total >= 30 and same == total, same=same, total=total)
        self.rows.append({"call": "ambient local-init", "from": a, "to": b, "pictures_decoded": total, "identical": f"{same}/{total}"})
        self.check(f"ambient local-init: {b} shows it is viewing", "Viewing" in self.screen_text(b))
        self.cmd(b, "video-hangup")
        self.check(f"ambient local-init: {b} releases; both idle",
                   self.wait("released", lambda: self.active(a) is None and self.active(b) is None, timeout=10))
        # Not authorised.
        refused = self.cmd(b, "video-ambient", self.mcptt[a], "remote", ok=False, timeout=30)
        self.check(f"ambient: {b}, not authorised, is refused (403)", not refused.get("ok") and "403" in str(refused.get("error")), reply=refused)
        self.wait("released", lambda: self.active(a) is None and self.active(b) is None, timeout=10)
        # A call for the viewed-to user ends the ambient call (§15.4.3).
        for r in self.refs[:1]:
            self.cmd(a, "video-ambient", self.mcptt[b], "remote", timeout=30)
            self.wait(f"{b} transmits", lambda: (self.active(b) or {}).get("transmission") == "HAS_PERMISSION", timeout=10)
            self.ref_command(r, f"mcvideo-private {self.mcvideo[b]} auto")
            ended = self.wait(f"{a}'s ambient call ended", lambda: self.active(a) is None, timeout=15)
            last = self.video(a)["history"][-1]
            self.check(f"ambient: {r}'s call for {b} ends {a}'s viewing (call-request-for-viewed-to-client)",
                       bool(ended) and last.get("reason") == "call-request-for-viewed-to-client", reason=last.get("reason"))
            self.check(f"ambient: {r}'s call to {b} goes through",
                       self.wait(f"{b} in {r}'s call", lambda: (self.active(b) or {}).get("session_type") == "private"
                                 and self.active(b)["state"] == "established", timeout=20))
            self.ref_command(r, "mcvideo-hangup")
            self.wait("released", lambda: self.active(b) is None, timeout=10)

    def chat(self) -> None:
        a, b = self.a, self.b
        self.scenario = "chat"
        self.front()
        chat_group = json.loads((RUNS / self.name / "provisioning" / a / "bundle.json").read_text())["mcvideo"].get("chat_groups", [None])[0]
        if not chat_group:
            self.check("the phones are provisioned with a chat group", False)
            return
        joined = self.cmd(a, "video-chat", chat_group, timeout=30)
        self.check(f"{a} joins the chat group's video call", joined["state"] == "established" and joined["session_type"] == "chat", call=joined)
        time.sleep(3)
        self.check(f"nobody is invited: {b} has no call", self.active(b) is None)
        self.check(f"{b} joins the same chat call", self.cmd(b, "video-chat", chat_group, timeout=30)["state"] == "established")
        for r in self.refs:
            self.ref_command(r, f"mcvideo-chat {chat_group}")
        members = {self.mcvideo[a], self.mcvideo[b], *self.ref_ids.values()}
        for c in (a, b):
            listed = self.wait(f"{c} lists everyone who joined", lambda c=c: set((self.active(c) or {}).get("participants", {})) == members
                               and self.active(c)["participants"], timeout=10)
            self.check(f"chat: {c} lists the {len(members)} members who joined", set(listed) == members)
        ref_before = {r: self.ref_status(r).get("mcvideo_frames_received", 0) for r in self.refs}
        before = self.active(b)["pictures_received"]
        sent = self.cmd(a, "video-transmit", str(self.seconds), timeout=self.seconds + 30).get("pictures", 0)
        time.sleep(2)
        listener = self.active(b)
        got = listener["pictures_received"] - before
        same, total = self.fingerprints(a, b)
        self.check(f"chat: {a} → {b} decoded ≥{MIN_PICTURES:.0%} of {sent}, identical", got >= MIN_PICTURES * sent and same == total > 0,
                   received=got, same=same, total=total, undecoded=listener.get("undecoded"), skipped=listener.get("skipped_before_key"),
                   lost=listener.get("lost"))
        row = {"call": "chat", "from": a, "to": b, "pictures_sent": sent, "pictures_decoded": got, "identical": f"{same}/{total}"}
        for r in self.refs:
            decoded = self.ref_status(r).get("mcvideo_frames_received", 0) - ref_before[r]
            row[f"{r}_decoded"] = decoded
            if self.protected_lab:
                # The reference client subscribes to its configured group's GMK only, not the chat group's:
                # under protection it cannot decode the chat call (a limitation of that client, not the phones).
                row[f"{r}_note"] = "no chat group GMK in the reference client"
                continue
            self.check(f"chat: {a} → {r} decoded ≥{MIN_PICTURES:.0%} of {sent}", decoded >= MIN_PICTURES * sent, decoded=decoded)
        self.rows.append(row)
        # A late joiner: b leaves, a starts transmitting, b rejoins in the middle and decodes from the next key picture.
        self.cmd(b, "video-hangup")
        self.wait(f"{a} sees {b} leave", lambda: self.mcvideo[b] not in (self.active(a) or {}).get("participants", {}), timeout=10)
        self.check(f"chat: {b} left, the call goes on for {a}", self.active(a) is not None and self.active(a)["state"] == "established")
        result: dict = {}
        worker = threading.Thread(target=lambda: result.update(self.cmd(a, "video-transmit", "8", timeout=40)))
        worker.start()
        time.sleep(2.5)
        self.cmd(b, "video-chat", chat_group, timeout=30)
        worker.join()
        time.sleep(2)
        late = self.active(b)
        same, total = self.fingerprints(a, b)
        self.check(f"chat: {b} rejoins during {a}'s transmission and decodes it", late["pictures_received"] > 0 and same == total > 0,
                   received=late["pictures_received"], skipped=late["skipped_before_key"], same=same, total=total)
        self.rows.append({"call": "chat (late join)", "from": a, "to": b, "pictures_decoded": late["pictures_received"],
                          "skipped_before_key": late["skipped_before_key"], "identical": f"{same}/{total}"})
        for c in (a, b):
            if self.active(c):
                self.cmd(c, "video-hangup", ok=False)
        for r in self.refs:
            if self.ref_status(r).get("mcvideo_call_state") == "active":
                self.ref_command(r, "mcvideo-hangup")
        self.wait("everyone has left the chat", lambda: self.active(a) is None and self.active(b) is None, timeout=10)

    def group(self) -> None:
        a, b = self.a, self.b
        self.scenario = "group"
        self.front()
        call = self.cmd(a, "video-group", timeout=30)
        group = call.get("group")
        self.check("the group video call is established", call["state"] == "established" and call["session_type"] == "prearranged", call=call)
        joined = self.wait(f"{b} is invited", lambda: self.active(b), timeout=10)
        self.check(f"{b} is in the group call from {a}", joined["group"] == group and joined["peer"] == self.mcvideo[a], joined=joined)
        for r in self.refs:
            self.wait(f"{r} is in the call", lambda r=r: self.ref_status(r).get("mcvideo_call_state") == "active", timeout=15)
            self.check(f"{r} (reference client) is in the group call", True)
        # The participant list (conference event package, TS 24.281 §9.2.3): every member sees every member.
        members = {self.mcvideo[a], self.mcvideo[b], *self.ref_ids.values()}
        for c in (a, b):
            listed = self.wait(f"{c} lists every member", lambda c=c: set((self.active(c) or {}).get("participants", {})) == members
                               and self.active(c)["participants"], timeout=10)
            self.check(f"group: {c} lists all {len(members)} members as connected", set(listed) == members
                       and set(listed.values()) == {"connected"}, listed=listed)
        for r in self.refs:
            listed = self.wait(f"{r} lists every member", lambda r=r: set(self.ref_status(r).get("mcvideo_participants", {})) == members
                               and self.ref_status(r)["mcvideo_participants"], timeout=10)
            self.check(f"group: {r} lists the same members", set(listed) == members)
        if self.shots:
            self.screenshot(b, f"{b}-group-participants")
        time.sleep(1)
        # Each phone transmits: the other phone and every reference client decode it.
        for talker, listener in ((a, b), (b, a)):
            ref_before = {r: self.ref_status(r).get("mcvideo_frames_received", 0) for r in self.refs}
            before = self.active(listener)["pictures_received"]
            result = self.cmd(talker, "video-transmit", str(self.seconds), timeout=self.seconds + 30)
            time.sleep(2)
            sent = result.get("pictures", 0)
            got = self.active(listener)["pictures_received"] - before
            same, total = self.fingerprints(talker, listener)
            self.check(f"group: {talker} → {listener} decoded ≥{MIN_PICTURES:.0%} of {sent}", result.get("granted") and got >= MIN_PICTURES * sent,
                       received=got, sent=sent)
            self.check(f"group: {talker} → {listener} pictures identical", total > 0 and same == total, same=same, total=total)
            row = {"call": "group", "from": talker, "to": listener, "pictures_sent": sent, "pictures_decoded": got, "identical": f"{same}/{total}"}
            for r in self.refs:
                st = self.ref_status(r)
                decoded = st.get("mcvideo_frames_received", 0) - ref_before[r]
                self.check(f"group: {talker} → {r} (OpenH264) decoded ≥{MIN_PICTURES:.0%} of {sent}", decoded >= MIN_PICTURES * sent,
                           decoded=decoded, undecoded=st.get("mcvideo_undecoded"))
                row[f"{r}_decoded"] = decoded
            self.rows.append(row)
        # The reference client transmits its test pattern: both phones decode it.
        for r in self.refs:
            before = {c: self.active(c)["pictures_received"] for c in (a, b)}
            sent_before = self.ref_status(r).get("mcvideo_frames_sent", 0)
            self.ref_command(r, "mcvideo-transmit")
            time.sleep(self.seconds)
            self.ref_command(r, "mcvideo-release")
            time.sleep(2)
            sent = self.ref_status(r).get("mcvideo_frames_sent", 0) - sent_before
            row = {"call": "group", "from": r, "pictures_sent": sent}
            for c in (a, b):
                act = self.active(c)
                got = act["pictures_received"] - before[c]
                self.check(f"group: {r} → {c} is announced as the transmitter", f"taken by {self.ref_ids[r]}" in act["events"], events=act["events"][-4:])
                # Decoding starts at the reference client's next IDR (every 2 s): its first one precedes the reception.
                self.check(f"group: {c} decodes {r}'s video (MediaCodec)", got >= 0.3 * sent and act["undecoded"] == 0,
                           received=got, sent=sent, undecoded=act["undecoded"], skipped=act["skipped_before_key"])
                row[f"{c}_decoded"] = got
            self.rows.append(row)
        # b leaves; with a third member the call goes on, and a sees b go.
        if self.refs:
            self.cmd(b, "video-hangup")
            left = self.wait(f"{a} sees {b} leave", lambda: self.mcvideo[b] not in (self.active(a) or {}).get("participants", {self.mcvideo[b]: 1})
                             and self.active(a)["participants"], timeout=10)
            self.check(f"group: {b} leaves and {a}'s list drops {b}", set(left) == members - {self.mcvideo[b]}, listed=left)
        # Everyone leaves (the server may end the call for the rest once the caller has left).
        for c in (a, b):
            if self.active(c):
                self.cmd(c, "video-hangup", ok=False)
        for r in self.refs:
            if self.ref_status(r).get("mcvideo_call_state") == "active":
                self.ref_command(r, "mcvideo-hangup")
        self.wait("everyone has left", lambda: self.active(a) is None and self.active(b) is None, timeout=10)

    def report(self) -> Path:
        m = meta(self.name)
        doc = {"lab": self.name, "server_image": m.get("server_image"), "at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
               "phones": {c: {"serial": self.devices[c].serial, "mcvideo_id": self.mcvideo[c]} for c in (self.a, self.b)},
               "calls": self.rows, "checks": self.results}
        path = self.out / "report.json"
        path.write_text(json.dumps(doc, indent=2) + "\n")
        return path


    def aliases(self) -> None:
        """MCVideo functional aliases (TS 24.281 clause 20) with the lab's video-dispatcher (alice and bob, one activation,
        bob may take it over) and video-medic (bob and client-c): activation answered 200 with the outcome by NOTIFY, the
        take-over once offered, a private video call to the alias reaching its holder, and an alias presented on a call."""
        a, b = self.a, self.b
        self.scenario = "aliases"
        fa = lambda c: self.status(c)["mcvideo_functional_aliases"]
        alias = lambda c, name: next((x for x in fa(c)["available"] if x.startswith(f"sip:{name}.alias@")), None)
        dispatcher, medic = alias(a, "video-dispatcher"), alias(b, "video-medic")
        if not self.check("alice may use video-dispatcher, bob video-medic", bool(dispatcher and medic), a=fa(a), b=fa(b)):
            return
        self.cmd(a, "mcvideo-fa", "activate", dispatcher)
        self.wait(f"{a} holds video-dispatcher", lambda: dispatcher in fa(a)["active"])
        self.check("alice's activation: 200, the outcome by NOTIFY", fa(a)["last_status"] == 200, last=fa(a))
        self.cmd(b, "mcvideo-fa", "activate", dispatcher)
        self.wait(f"{b} is offered video-dispatcher", lambda: dispatcher in fa(b)["take_over_possible"])
        self.check("bob's activation beyond the limit: 200, then take-over-possible", dispatcher not in fa(b)["active"])
        self.cmd(b, "mcvideo-fa", "takeover", dispatcher)
        self.wait(f"{b} holds video-dispatcher", lambda: dispatcher in fa(b)["active"])
        self.wait(f"{a} is told it no longer holds it", lambda: dispatcher not in fa(a)["active"])
        self.check("bob takes video-dispatcher over", True)
        call = self.cmd(a, "video-call-fa", dispatcher, timeout=30)
        self.check("alice's video call to video-dispatcher is established", call["state"] == "established", call=call)
        incoming = self.wait(f"{b} has the call", lambda: self.active(b), timeout=10)
        self.check("the call to video-dispatcher reaches bob, who sees it was to the alias",
                   incoming["peer"] == self.mcvideo[a] and incoming.get("called_alias") == dispatcher, call=incoming)
        self.cmd(a, "video-hangup")
        self.wait(f"{b}'s call released", lambda: self.active(b) is None, timeout=10)
        self.cmd(b, "mcvideo-fa", "activate", medic)
        self.wait(f"{b} holds video-medic", lambda: medic in fa(b)["active"])
        self.cmd(b, "mcvideo-fa", "use", medic)
        self.cmd(b, "video-call", self.mcptt[a], timeout=30)
        other = self.wait(f"{a} has the call", lambda: self.active(a), timeout=10)
        self.check("alice sees bob's call from video-medic", other.get("calling_alias") == medic, call=other)
        self.cmd(b, "video-hangup")
        self.wait(f"{a}'s call released", lambda: self.active(a) is None, timeout=10)
        self.cmd(b, "mcvideo-fa", "use", "none")
        self.cmd(b, "mcvideo-fa", "deactivate", dispatcher, medic)
        self.wait("bob's aliases are deactivated", lambda: not ({dispatcher, medic} & set(fa(b)["active"])))
        self.check("bob deactivates his aliases", True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("lab")
    parser.add_argument("--a", default="client-a")
    parser.add_argument("--b", default="client-b")
    parser.add_argument("--seconds", type=float, default=6.0, help="how long each side transmits")
    parser.add_argument("--screenshots", action="store_true")
    parser.add_argument("--parts", help="comma-separated parts to run (default: all that apply to the lab)")
    args = parser.parse_args()
    run = Video(args.lab, args.a, args.b, args.seconds, args.screenshots)
    parts = args.parts.split(",") if args.parts else (
        ["setup", "ringing", "group", "emergency", "alerts", "pushpull", "ambient", "camera", "chat", "aliases"] + (["protection"] if run.protected_lab else []))
    try:
        for part in parts:
            getattr(run, "run" if part == "setup" else part)()
    except Failed as e:
        run.check(f"{run.scenario} completed", False, error=str(e))
        for c in (run.a, run.b):
            try:
                if run.active(c):
                    run.cmd(c, "video-hangup", ok=False)
            except Exception:
                pass
        for r in run.refs:
            try:
                run.ref_command(r, "mcvideo-hangup")
            except Exception:
                pass
    passed = sum(r["ok"] for r in run.results)
    print(f"\n{passed}/{len(run.results)} checks passed; report {run.report()}")
    sys.exit(0 if passed == len(run.results) else 1)


if __name__ == "__main__":
    main()

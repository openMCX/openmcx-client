#!/usr/bin/env python3
#
# OpenMCX Licence Information : AGPL-3.0-or-later
#

"""End-to-end scenarios between Android clients (docs/android/PLAN.md, "Scenarios").

    tests/scenarios.py a1 [S1 S2 ...]

Every client is the Android app on a redroid container of an androidlab.py lab. The
scenarios drive them through the debug control endpoint and check what each reports.
Speech is checked end to end: the talker's AMR-WB payload hashes must arrive, in order
and unchanged, at every listener.
"""
from __future__ import annotations

import json
import re
import subprocess
from collections import Counter
import sys
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "lab"))
from androidlab import ADB, FILES, Device, meta  # noqa: E402

A, B, C = "client-a", "client-b", "client-c"
FGS_PHONE_CALL, FGS_MICROPHONE, FGS_SPECIAL_USE = 0x4, 0x80, 0x40000000  # ServiceInfo.FOREGROUND_SERVICE_TYPE_*
MCPTT = {A: "sip:alice.mcptt@mcx.test", B: "sip:bob.mcptt@mcx.test", C: "sip:client-c.mcptt@mcx.test"}
MCDATA = {A: "sip:alice.mcdata@mcx.test", B: "sip:bob.mcdata@mcx.test", C: "sip:client-c.mcdata@mcx.test"}
GROUP = "sip:operations@mcx.test"
SPEECH = {A: f"{FILES}/speech/alpha.wav", B: f"{FILES}/speech/bravo.wav", C: f"{FILES}/speech/charlie.wav"}


class Failed(Exception):
    pass


class Lab:
    def __init__(self, name: str) -> None:
        self.name = name
        m = meta(name)
        clients = list(m["clients"])
        # Phone mode: each installed phone plays its client's part.
        clients += [role for role, phone in (m.get("phones") or {}).items() if phone.get("serial")]
        self.devices = {c: Device(name, c) for c in sorted(clients)}
        self.results: list[dict] = []
        self.scenario = ""
        self.skipped: list[str] = []

    def cmd(self, client: str, *args: str, ok: bool = True, timeout: float = 60):
        r = self.devices[client].control(*args, timeout=timeout)
        if not ok:
            return r  # the whole reply: {"ok": …, "result"|"error": …}
        if not r.get("ok"):
            raise Failed(f"{client} {' '.join(args)}: {r.get('error')}")
        return r.get("result")

    def status(self, client: str) -> dict:
        return self.cmd(client, "status")

    def wait(self, what: str, predicate, timeout: float = 15.0):
        deadline = time.monotonic() + timeout
        last = None
        while time.monotonic() < deadline:
            last = predicate()
            if last:
                return last
            time.sleep(0.3)
        raise Failed(f"timed out waiting for {what}")

    def check(self, name: str, condition: bool, **detail) -> bool:
        self.results.append({"scenario": self.scenario, "check": name, "ok": bool(condition), **detail})
        print(f"  [{'PASS' if condition else 'FAIL'}] {name}" + (f"  {json.dumps(detail)}" if detail and not condition else ""))
        return bool(condition)

    def call(self, client: str) -> dict | None:
        calls = self.status(client)["calls"]
        return calls[0] if calls else None

    def last_released(self, client: str) -> dict | None:
        history = self.status(client)["history"]
        return history[-1] if history else None

    def established(self, client: str) -> dict:
        return self.wait(f"{client} established", lambda: (c := self.call(client)) and c.get("state") == "established" and c)

    def idle_everywhere(self, clients) -> None:
        for c in clients:
            self.wait(f"{c} has no call", lambda c=c: self.call(c) is None, timeout=10)

    # Speech: talk and verify every listener got the talker's payloads unchanged.
    def talk_and_verify(self, talker: str, listeners: list[str], seconds: float = 3.0) -> None:
        before = {l: len(self.call(l)["bursts"]) for l in listeners}
        result = self.cmd(talker, "ptt", SPEECH[talker], str(seconds), timeout=seconds + 20)
        if not self.check(f"{talker} granted the floor", result.get("granted"), result=result):
            return
        sent = [b for b in self.call(talker)["bursts"] if b["direction"] == "sent"][-1]
        self.check(f"{talker} sent speech", sent["frames"] >= int(seconds * 50) - 10, frames=sent["frames"])
        for l in listeners:
            def received(l=l):
                c = self.call(l)
                bursts = [b for b in c["bursts"][before[l]:] if b["direction"] == "received" and b["ssrc"] == sent["ssrc"]]
                return bursts[-1] if bursts and bursts[-1]["frames"] >= sent["frames"] else None
            try:
                got = self.wait(f"{l} receives the burst", received, timeout=5)
            except Failed:
                got = next((b for b in self.call(l)["bursts"] if b["ssrc"] == sent["ssrc"]), None)
            same = got is not None and got["hashes"] == sent["hashes"]
            missing = [i for i, h in enumerate(sent["hashes"]) if got is None or h not in set(got["hashes"])]
            self.check(f"{l} received {talker}'s speech unchanged", same, sent=sent["frames"],
                       received=got and got["frames"], common=got and len(set(got["hashes"]) & set(sent["hashes"])),
                       missing=missing[:10])
        for c in [talker, *listeners]:
            self.wait(f"{c} floor idle", lambda c=c: (x := self.call(c)) and x.get("floor") == "NO_PERMISSION", timeout=5)

    def hangup(self, client: str) -> None:
        self.cmd(client, "hangup")

    # Scenarios.

    def s1_startup(self) -> None:
        for c in self.devices:
            s = self.wait(f"{c} ready", lambda c=c: (s := self.status(c))["phase"] == "ready" and s, timeout=60)
            self.check(f"{c} registered", s["registered"])
            for service in ("mcptt", "mcdata"):
                self.check(f"{c} {service} authorised", s[service]["authorised"])
                self.check(f"{c} {service} affiliated to {GROUP}", s[service]["affiliations"].get(GROUP) == "affiliated")

    def s2_private_automatic(self) -> None:
        call = self.cmd(A, "private", MCPTT[B], "auto")
        self.check("alice's private call is established", call["state"] == "established")
        bob = self.established(B)
        self.check("bob sees alice as the caller", bob["peer"] == MCPTT[A], peer=bob["peer"])
        self.check("bob's call is private", bob["type"] == "private")
        self.talk_and_verify(A, [B])
        self.talk_and_verify(B, [A])
        self.hangup(A)
        self.idle_everywhere([A, B])
        self.check("bob's call was released by the server", self.last_released(B)["state"] == "released")

    def s3_private_manual(self) -> None:
        self.cmd(B, "answer-mode", "manual")
        try:
            # Decline.
            result: dict = {}
            t = threading.Thread(target=lambda: result.update(r=self.cmd(A, "private", MCPTT[B], "manual", ok=False, timeout=40)))
            t.start()
            ringing = self.wait("bob rings", lambda: (c := self.call(B)) and c["state"] == "ringing" and c)
            self.check("bob's phone rings for alice", ringing["peer"] == MCPTT[A])
            self.cmd(B, "reject")
            t.join(30)
            r = result.get("r", {})
            self.check("alice's call is declined", not r.get("ok") and "480" in (r.get("error") or ""), error=r.get("error"))
            self.idle_everywhere([A, B])
            # Answer.
            t = threading.Thread(target=lambda: result.update(r=self.cmd(A, "private", MCPTT[B], "manual", ok=False, timeout=40)))
            t.start()
            self.wait("bob rings", lambda: (c := self.call(B)) and c["state"] == "ringing")
            time.sleep(1)
            self.cmd(B, "answer")
            t.join(30)
            r = result.get("r", {})
            self.check("alice's call is answered", r.get("ok") and r["result"]["state"] == "established", error=r.get("error"))
            self.established(B)
            self.talk_and_verify(A, [B])
            self.hangup(B)
            self.idle_everywhere([A, B])
        finally:
            self.cmd(B, "answer-mode", "automatic")
        # Forced commencement overrides bob's manual setting (§11.1.1.2.1.2 step 9).
        self.cmd(B, "answer-mode", "manual")
        try:
            r = self.cmd(A, "private", MCPTT[B], "force", ok=False, timeout=40)
            if r.get("ok"):
                self.check("forced call is answered automatically", self.established(B)["state"] == "established")
                self.hangup(A)
            else:
                # The a1 fixture does not grant alice force_auto_answer (contract: 403 warning 143).
                self.check("forced call refused by policy (403 143)", "403" in r.get("error", "") and "143" in r.get("error", ""),
                           error=r.get("error"))
            self.idle_everywhere([A, B])
        finally:
            self.cmd(B, "answer-mode", "automatic")

    def s4_group(self) -> None:
        call = self.cmd(A, "group", GROUP)
        self.check("alice's group call is established", call["state"] == "established")
        for c in (B, C):
            got = self.established(c)
            self.check(f"{c} joined the group call", got["type"] == "prearranged" and got["group"] == GROUP, call=got)
        self.talk_and_verify(A, [B, C])
        self.talk_and_verify(B, [A, C])
        self.talk_and_verify(C, [A, B])
        # Contention: bob holds the floor while client-c asks (queueing is off in a1: Deny cause 1).
        holder = threading.Thread(target=lambda: self.cmd(B, "ptt", SPEECH[B], "4", timeout=30))
        holder.start()
        self.wait("bob holds the floor", lambda: self.call(B).get("floor") == "HAS_PERMISSION", timeout=5)
        denied = self.cmd(C, "ptt", SPEECH[C], "2", timeout=30)
        self.check("client-c is denied while bob talks", not denied.get("granted"), result=denied)
        self.check("client-c learns it was denied", any("denied" in e for e in self.call(C)["floor_events"]))
        holder.join()
        self.hangup(A)
        self.idle_everywhere([A, B, C])

    def s5_adhoc(self) -> None:
        call = self.cmd(A, "adhoc", MCPTT[B], MCPTT[C])
        self.check("alice's ad hoc call is established", call["state"] == "established")
        group = call.get("group") or ""
        self.check("alice learns the ad hoc group identity", group.startswith("sip:adhoc-"), group=group)
        for c in (B, C):
            got = self.established(c)
            self.check(f"{c} joined the ad hoc call", got["type"] == "adhoc" and got.get("group") == group, call=got)
        self.talk_and_verify(A, [B, C], seconds=2)
        self.talk_and_verify(C, [A, B], seconds=2)
        self.hangup(A)
        self.idle_everywhere([A, B, C])

    def inbox_message(self, client: str, text: str) -> dict:
        return self.wait(f"{client} receives '{text}'",
                         lambda: next((m for m in self.status(client)["inbox"] if m["text"] == text), None), timeout=10)

    def notifications(self, client: str, message_id: str, count: int) -> dict:
        return self.wait(f"{client} gets {count} notification(s) for {message_id}",
                         lambda: (m := next((m for m in self.status(client)["outbox"] if m["message_id"] == message_id), None))
                         and len(m["notifications"]) >= count and m["notifications"], timeout=20)

    def s6_sds(self) -> None:
        stamp = time.strftime("%H%M%S")
        # One-to-one, delivery and read: bob reads within TDU1, so one DELIVERED AND READ comes back (§9.2.1.3).
        text = f"Hello Bob {stamp}"
        sent = self.cmd(A, "sds", MCDATA[B], text, "--disposition", "delivery-read")
        self.check("alice's SDS to bob is accepted (202)", sent["status"] == 202, sent=sent)
        got = self.inbox_message(B, text)
        self.check("bob sees alice as the sender", got["sender"] == MCDATA[A], sender=got["sender"])
        self.check("bob's copy is one-to-one", got.get("group") is None)
        self.check("bob's copy has alice's message ID", got["message_id"] == sent["message_id"])
        self.cmd(B, "display", got["message_id"])
        n = self.notifications(A, sent["message_id"], 1)
        self.check("alice learns bob delivered and read it", n.get(MCDATA[B]) == "DELIVERED_AND_READ", notifications=n)
        # One-to-one the other way, delivery only.
        text = f"Hello Alice {stamp}"
        sent = self.cmd(B, "sds", MCDATA[A], text, "--disposition", "delivery")
        got = self.inbox_message(A, text)
        self.check("alice receives bob's SDS", got["sender"] == MCDATA[B])
        n = self.notifications(B, sent["message_id"], 1)
        self.check("bob learns alice delivered it", n.get(MCDATA[A]) == "DELIVERED", notifications=n)
        # Group, delivery: every other affiliated member gets it; the sender gets no copy.
        text = f"Hello all {stamp}"
        sent = self.cmd(A, "sds-group", GROUP, text, "--disposition", "delivery")
        self.check("alice's group SDS is accepted (202)", sent["status"] == 202, sent=sent)
        for c in (B, C):
            got = self.inbox_message(c, text)
            self.check(f"{c} receives the group SDS from alice on {GROUP}", got["sender"] == MCDATA[A] and got["group"] == GROUP, got=got)
        time.sleep(1)
        self.check("alice gets no copy of her own group SDS", not any(m["text"] == text for m in self.status(A)["inbox"]))
        n = self.notifications(A, sent["message_id"], 2)
        self.check("alice learns bob and client-c delivered it",
                   n.get(MCDATA[B]) == "DELIVERED" and n.get(MCDATA[C]) == "DELIVERED", notifications=n)
        # Group, read only: a read notification after the display indication. (client-c may not
        # transmit on the group in the a1 fixture: 403 warning 201.)
        refused = self.cmd(C, "sds-group", GROUP, f"Not allowed {stamp}", ok=False)
        self.check("client-c's group SDS is refused by policy (403 201)", "403" in (refused.get("error") or "")
                   and "201" in refused.get("error", ""), error=refused.get("error"))
        text = f"Status report {stamp}"
        sent = self.cmd(B, "sds-group", GROUP, text, "--disposition", "read")
        got = self.inbox_message(A, text)
        self.inbox_message(C, text)
        self.cmd(A, "display", got["message_id"])
        n = self.notifications(B, sent["message_id"], 1)
        self.check("bob learns alice read it", n.get(MCDATA[A]) == "READ", notifications=n)
        time.sleep(1)
        n = self.notifications(B, sent["message_id"], 1)
        self.check("client-c has not read it, so sent nothing", MCDATA[C] not in n, notifications=n)

    # Lab-side controls (root adb shell on the redroid clients).

    def leave_clean(self) -> None:
        """Hang up what a scenario that stopped half-way left, for the next scenario."""
        for c in self.devices:
            try:
                if self.call(c):
                    self.cmd(c, "hangup", ok=False)
            except Exception:
                pass

    def kill_app(self, client: str, pid: int) -> None:
        """End the app's process as a crash would: the app kills itself (debug control die, SIGKILL), so that a phone
        needs no root; as root for an app built before that command."""
        if not self.cmd(client, "die", ok=False).get("ok"):
            self.root_shell(client, f"kill -9 {pid}")

    def root_shell(self, client: str, command: str) -> str:
        device = self.devices[client]
        if device.adb("shell", "id", "-u").strip() != "0":
            subprocess.run([str(ADB), "-s", device.serial, "root"], capture_output=True)
            time.sleep(2)
            device.connect()
        return device.adb("shell", command, check=False)

    def net(self, client: str, script: str) -> str:
        """Run [script] with nft and tc in the client's network namespace (lab/nettools)."""
        container = f"{meta(self.name)['project']}-{client}-1"
        out = subprocess.run(["docker", "run", "--rm", "--net", f"container:{container}", "--cap-add", "NET_ADMIN",
                              "mcx-nettools:1", "sh", "-c", script], capture_output=True, text=True)
        return out.stdout + out.stderr

    def ready(self, client: str, timeout: float = 90) -> dict:
        return self.wait(f"{client} ready", lambda: (s := self.status(client))["phase"] == "ready" and s, timeout=timeout)

    def group_call_reaches(self, caller: str, members: list[str], talker: str | None = None) -> None:
        self.cmd(caller, "group", GROUP)
        for m in members:
            self.check(f"{m} joins {caller}'s group call", bool(self.established(m)))
        talker = talker or caller
        self.talk_and_verify(talker, [c for c in [caller, *members] if c != talker], seconds=2)
        self.hangup(caller)
        self.idle_everywhere([caller, *members])

    def s7_refresh_and_recovery(self) -> None:
        # Refreshes on their timers take up to an hour; force each one now.
        before = self.status(A)
        for what in ("register", "subscription", "token"):
            self.cmd(A, "refresh", what, timeout=60)
            s = self.status(A)
            self.check(f"alice stays ready after a {what} refresh", s["phase"] == "ready")
            self.check(f"alice stays affiliated after a {what} refresh",
                       s["mcptt"]["affiliations"].get(GROUP) == "affiliated" and s["mcdata"]["affiliations"].get(GROUP) == "affiliated")
        self.check("the token was renewed", self.status(A)["token_expires_at"] > before["token_expires_at"])
        self.check("the flow was kept through the refreshes", self.status(A)["flow"] == before["flow"])
        self.group_call_reaches(A, [B, C])
        # Flow lost at the UE: a new flow, a new registration, and calls reach it again.
        old = self.status(B)["flow"]
        self.cmd(B, "drop-flow")
        s = self.wait("bob on a new flow", lambda: (s := self.status(B))["phase"] == "ready" and s["flow"] != old and s, timeout=60)
        self.check("bob re-registered on a new flow", s["flow"] != old, old=old, new=s["flow"])
        self.cmd(A, "private", MCPTT[B], "auto")
        self.check("alice's call reaches bob on his new flow", bool(self.established(B)))
        self.talk_and_verify(A, [B], seconds=2)
        self.hangup(A)
        self.idle_everywhere([A, B])
        # Coverage lost: all traffic to and from the SIP core dropped. The keepalive notices
        # (no pong in 10 s), the client retries until coverage returns, then comes back.
        self.net(C, 'nft add table inet mcxlab && nft add chain inet mcxlab out "{ type filter hook output priority 0; }" && '
                    'nft add chain inet mcxlab in "{ type filter hook input priority 0; }" && '
                    'nft add rule inet mcxlab out tcp dport 5061 drop && nft add rule inet mcxlab in tcp sport 5061 drop')
        lost = time.monotonic()
        try:
            self.wait("client-c notices the outage", lambda: self.status(C)["phase"] != "ready", timeout=60)
            self.check("client-c noticed the lost flow (keepalive)", True, seconds=round(time.monotonic() - lost, 1))
            time.sleep(10)
        finally:
            self.net(C, "nft delete table inet mcxlab")
        restored = time.monotonic()
        self.ready(C, timeout=90)
        self.check("client-c is ready again after the outage", True, seconds=round(time.monotonic() - restored, 1))
        self.group_call_reaches(A, [B, C], talker=C)
        # A long call: bob's leg is terminating, so bob refreshes it with UPDATE every 45 s (RFC 4028);
        # without that the server's session timer would end it at about 60 s. A talk burst every
        # 40 s keeps the server's 60 s inactivity timer from ending it.
        self.cmd(A, "private", MCPTT[B], "auto")
        self.established(B)
        started = time.monotonic()
        for at in (0, 40):
            time.sleep(max(0, at - (time.monotonic() - started)))
            self.talk_and_verify(A, [B], seconds=2)
        time.sleep(max(0, 80 - (time.monotonic() - started)))
        refreshes = [e for e in self.cmd(B, "events", "400") if "session refresh" in e]
        self.check("bob refreshed the session with UPDATE", any(e.endswith("refresh 200") for e in refreshes), refreshes=refreshes[-3:])
        self.check("the call is still up after 80 s", (self.call(A) or {}).get("state") == "established"
                   and (self.call(B) or {}).get("state") == "established")
        self.hangup(A)
        self.idle_everywhere([A, B])

    def s8_lifecycle(self) -> None:
        # Doze: with the foreground service and the flow's keepalive, calls still arrive.
        self.root_shell(B, "dumpsys deviceidle enable; dumpsys deviceidle force-idle")
        try:
            self.check("bob is in deep Doze", self.root_shell(B, "dumpsys deviceidle get deep").strip() == "IDLE")
            time.sleep(30)
            self.check("bob is still ready after 30 s in Doze", self.status(B)["phase"] == "ready")
            self.cmd(A, "private", MCPTT[B], "auto")
            self.check("alice's call reaches bob in Doze", bool(self.established(B)))
            self.talk_and_verify(A, [B], seconds=2)
            self.talk_and_verify(B, [A], seconds=2)
            self.hangup(A)
            self.idle_everywhere([A, B])
        finally:
            self.root_shell(B, "dumpsys deviceidle unforce")
        # Process death: Android restarts the sticky foreground service, which starts over.
        pid = self.status(C)["pid"]
        self.kill_app(C, pid)
        killed = time.monotonic()
        s = self.wait("client-c restarted", lambda: (s := self.status_or_none(C)) and s.get("pid") != pid and s["phase"] == "ready" and s,
                      timeout=120)
        self.check("client-c is ready again after being killed", s["pid"] != pid,
                   seconds=round(time.monotonic() - killed, 1))
        self.group_call_reaches(A, [B, C], talker=C)

    def status_or_none(self, client: str) -> dict | None:
        try:
            return self.status(client)
        except Exception:
            return None

    def s9_impaired(self) -> None:
        # 5% loss and 40±20 ms delay on alice's outgoing traffic (sch_netem on the host).
        out = self.net(A, "tc qdisc replace dev eth0 root netem loss 5% delay 40ms 20ms distribution normal")
        if "No such file" in out or "RTNETLINK" in out:
            self.check("netem available (host: sudo modprobe sch_netem)", False, output=out.strip())
            return
        try:
            self.cmd(A, "group", GROUP)
            for m in (B, C):
                self.established(m)
            before = {l: len(self.call(l)["bursts"]) for l in (B, C)}
            result = self.cmd(A, "ptt", SPEECH[A], "5", timeout=40)
            self.check("alice gets the floor despite loss (retransmission)", result.get("granted"), result=result)
            sent = [b for b in self.call(A)["bursts"] if b["direction"] == "sent"][-1]
            for l in (B, C):
                time.sleep(1)
                got = next((b for b in self.call(l)["bursts"][before[l]:] if b["ssrc"] == sent["ssrc"]), None)
                received = got["hashes"] if got else []
                # Delay jitter reorders packets; the jitter buffer puts them back by timestamp.
                position = {h: i for i, h in enumerate(sent["hashes"])}
                reordered = sum(1 for x, y in zip(received, received[1:]) if position.get(y, 0) < position.get(x, 0))
                unchanged = not (Counter(received) - Counter(sent["hashes"]))
                ratio = len(received) / max(1, len(sent["hashes"]))
                self.check(f"{l} receives most of the speech ({ratio:.0%})", 0.85 <= ratio < 1.0 or ratio == 1.0,
                           ratio=round(ratio, 3), concealed=got and got["concealed"])
                self.check(f"{l} receives every payload unchanged ({reordered} reordered in transit)", unchanged, reordered=reordered)
                self.check(f"{l} concealed the lost frames", got is not None and (got["concealed"] > 0 or ratio == 1.0),
                           concealed=got and got["concealed"], late=got and got["late"])
            self.hangup(A)
            self.idle_everywhere([A, B, C])
        finally:
            self.net(A, "tc qdisc del dev eth0 root")

    def s10_latency(self) -> None:
        # The redroid clients share the host clock, so their timestamps compare directly.
        self.cmd(A, "group", GROUP)
        for m in (B, C):
            self.established(m)
        access, transit, mouth_to_ear = [], [], []
        for _ in range(3):
            self.cmd(A, "ptt", SPEECH[A], "2", timeout=30)
            time.sleep(1)
            sent = [b for b in self.call(A)["bursts"] if b["direction"] == "sent"][-1]
            access.append(sent["granted_at"] - sent["requested_at"])
            for l in (B, C):
                got = next(b for b in self.call(l)["bursts"] if b["ssrc"] == sent["ssrc"])
                transit.append(got["started_at"] - sent["started_at"])
                # + 20 ms: the first frame is captured before it is sent.
                mouth_to_ear.append(got["first_playout_at"] - sent["started_at"] + 20)
        self.hangup(A)
        self.idle_everywhere([A, B, C])
        kpi = {"access_ms": access, "transit_ms": transit, "mouth_to_ear_ms": mouth_to_ear}
        print(f"  latency {json.dumps(kpi)}")
        self.check("MCPTT access time under 300 ms (TS 22.179 KPI 1)", max(access) < 300, **kpi)
        self.check("mouth-to-ear under 300 ms (TS 22.179 KPI 3), excluding the audio device", max(mouth_to_ear) < 300)

    def s11_product(self) -> None:
        # A hardware PTT key with no call: the selected group is called and the floor requested.
        self.cmd(A, "audio-next", SPEECH[A])
        self.devices[A].adb("shell", "am", "broadcast", "-a", "android.intent.action.PTT.down", "-p", "org.openmcx.client")
        call = self.wait("alice's PTT key starts a group call", lambda: (c := self.call(A)) and c.get("floor") == "HAS_PERMISSION" and c,
                         timeout=15)
        self.check("the PTT key called the selected group and got the floor", call["type"] == "prearranged" and call["group"] == GROUP)
        for m in (B, C):
            self.established(m)
        # Telecom knows about the call (self-managed), and the service declares phoneCall.
        for c in (A, B):
            telecom = self.devices[c].adb("shell", "dumpsys", "telecom", check=False)
            # Recorded, not asserted: redroid has no telephony service or InCallService, and its Telecom
            # leaves self-managed calls in CONNECTING at random. The telecom lifecycle is asserted by
            # the instrumented TelecomTest on an AVD.
            listed = self.telecom_call_listed(telecom)
            print(f"  [INFO] {c}'s call in telecom: {'ACTIVE' if listed else 'not active (redroid Telecom)'}")
            types = self.fgs_types(c)
            self.check(f"{c}'s service declares phoneCall and microphone during the call",
                       types & FGS_PHONE_CALL and types & FGS_MICROPHONE and types & self.special_use(c) == self.special_use(c),
                       types=hex(types))
        time.sleep(2)
        self.devices[A].adb("shell", "am", "broadcast", "-a", "android.intent.action.PTT.up", "-p", "org.openmcx.client")
        self.wait("alice releases with the key", lambda: self.call(A).get("floor") == "NO_PERMISSION", timeout=5)
        sent = [b for b in self.call(A)["bursts"] if b["direction"] == "sent"][-1]
        got = next((b for b in self.call(B)["bursts"] if b["ssrc"] == sent["ssrc"]), None)
        self.check("bob heard the key-triggered burst unchanged", got is not None and got["hashes"] == sent["hashes"],
                   sent=sent["frames"], received=got and got["frames"])
        self.hangup(A)
        self.idle_everywhere([A, B, C])
        types = self.fgs_types(A)
        self.check("alice's service is back to special use only after the call", types == self.special_use(A), types=hex(types))
        # A message notification, and history that outlives the process.
        text = f"Notify me {time.strftime('%H%M%S')}"
        self.cmd(A, "sds", MCDATA[B], text)
        self.inbox_message(B, text)
        notes = self.devices[B].adb("shell", "dumpsys", "notification", "--noredact", check=False)
        self.check("bob gets a notification for the message", text in notes)
        pid = self.status(B)["pid"]
        self.kill_app(B, pid)
        self.wait("bob restarted", lambda: (s := self.status_or_none(B)) and s.get("pid") != pid and s, timeout=120)
        history = self.cmd(B, "history", "50")
        self.check("bob's history keeps the message after a restart", any(h["summary"] == text for h in history))
        self.check("bob's history keeps the group call", any(h["kind"] == "call" and h.get("group") == GROUP for h in history))
        self.ready(B)

    @staticmethod
    def telecom_call_listed(dump: str) -> bool:
        calls = dump.split("mCalls:", 1)[1].split("mCallAudioManager", 1)[0] if "mCalls:" in dump else ""
        return any("org.openmcx.client" in l and "state=ACTIVE" in l and "self_mng" in l for l in calls.splitlines())

    def special_use(self, client: str) -> int:
        """The special use type, which the idle service holds: from Android 14 (API 34); before, Android drops it and
        the idle service has no type."""
        sdk = int(self.devices[client].adb("shell", "getprop", "ro.build.version.sdk").strip() or 0)
        return FGS_SPECIAL_USE if sdk >= 34 else 0

    def fgs_types(self, client: str) -> int:
        """The foreground service types of the app's notification-bearing service: as Android reports them, or, where
        dumpsys does not print them (Android 13), as the app reports them (status fgs_types)."""
        dump = self.devices[client].adb("shell", "dumpsys", "activity", "services", "org.openmcx.client", check=False)
        for line in dump.splitlines():
            m = re.search(r"isForeground=true foregroundId=1 types=(0x[0-9A-Fa-f]+)", line)
            if m:
                return int(m.group(1), 16)
        return self.status(client).get("fgs_types") or 0

    def s12_private_no_floor(self) -> None:
        """TS 24.379 §11.1.2: a private call without floor control; both parties speak at once, no floor message."""
        for c in (A, B):
            self.cmd(c, "audio-next", SPEECH[c])  # each side's microphone reads its own speech file
        call = self.cmd(A, "private", MCPTT[B], "auto", "no-floor", timeout=30)
        self.check("alice's call without floor control is established", call["state"] == "established" and call["floor_control"] is False,
                   call={k: call.get(k) for k in ("state", "floor_control")})
        bob = self.established(B)
        self.check("bob's call is private, without floor control", bob["type"] == "private" and bob["floor_control"] is False)
        time.sleep(7.5)  # the speech files last six seconds
        calls = {c: self.call(c) for c in (A, B)}
        sent = {c: next((b for b in calls[c]["bursts"] if b["direction"] == "sent"), None) for c in (A, B)}
        for c in (A, B):
            self.check(f"{c} spoke at once, without asking for the floor", sent[c] is not None and sent[c]["frames"] >= 250,
                       frames=sent[c] and sent[c]["frames"])
        if sent[A] and sent[B]:
            self.check("both spoke at the same time", abs(sent[A]["started_at"] - sent[B]["started_at"]) < 2000,
                       apart_ms=sent[A]["started_at"] - sent[B]["started_at"])
        for talker, listener in ((A, B), (B, A)):
            got = next((b for b in calls[listener]["bursts"] if b["direction"] == "received" and sent[talker] and b["ssrc"] == sent[talker]["ssrc"]), None)
            same = got is not None and got["hashes"] == sent[talker]["hashes"]
            self.check(f"{listener} received {talker}'s speech unchanged while speaking", same, sent=sent[talker] and sent[talker]["frames"],
                       received=got and got["frames"], common=got and len(set(got["hashes"]) & set(sent[talker]["hashes"])),
                       missing=[i for i, h in enumerate(sent[talker]["hashes"]) if got is None or h not in set(got["hashes"])][:20])
        for c in (A, B):
            self.check(f"{c} exchanged no floor message", not calls[c]["floor_events"], events=calls[c]["floor_events"])
        self.hangup(A)
        self.idle_everywhere([A, B])

    def location_of(self, client: str, service: str = "mcptt") -> dict:
        return next(s for s in self.cmd(client, "location") if s["service"] == service)

    @staticmethod
    def metres(a: dict, b: dict) -> float:
        import math
        p1, p2 = math.radians(a["lat"]), math.radians(b["lat"])
        h = math.sin((p2 - p1) / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(math.radians(b["lon"] - a["lon"]) / 2) ** 2
        return 6371008.8 * 2 * math.asin(math.sqrt(h))

    def s13_location(self) -> None:
        """TS 24.379 clause 13 (and its MCData and MCVideo counterparts) on a lab made with --location. No coordinate
        is recorded: only whether a report carried one, and distances between reports."""
        for c in (A, B):
            for service in ("mcptt", "mcdata", "mcvideo"):
                loc = self.wait(f"{c} {service} location configured", lambda c=c, s=service: (x := self.location_of(c, s))["configured"] and x, timeout=30)
                logon = self.wait(f"{c} {service} log-on report", lambda c=c, s=service: next(
                    (r for r in self.location_of(c, s)["sent"] if r.get("triggers")), None), timeout=30)
                self.check(f"{c} {service}: configured by the server, and reported at log-on with its position",
                           len(loc["triggers"]) >= 1 and logon.get("coordinate") is True and logon.get("timestamp") is not None,
                           triggers=len(loc["triggers"]), coordinate=logon.get("coordinate"))
                events = self.location_of(c, service)["events"]
                self.check(f"{c} {service}: the server accepted the reports", any(e.startswith("report") and e.endswith(": 200") for e in events))
        # alice may request others' locations; bob shares his with everyone.
        before = self.location_of(B)["sent"]
        previous = self.cmd(A, "located").get("mcptt", {}).get(MCPTT[B], {}).get("timestamp")
        time.sleep(1.1)  # report timestamps are in whole seconds
        asked = self.cmd(A, "location-request", "mcptt", MCPTT[B])
        self.check("alice's location request for bob is accepted", asked["result"] == "200", result=asked["result"])
        got = self.wait("alice receives bob's fresh location", lambda: (x := self.cmd(A, "located").get("mcptt", {}).get(MCPTT[B])) and
                        x.get("timestamp") != previous and x, timeout=15)
        answer = next((r for r in self.location_of(B)["sent"] if r not in before and r.get("id")), None)
        self.check("bob's phone answered the server's request at once, with its position", answer is not None and answer.get("coordinate") is True)
        if answer and got.get("lat") is not None:
            self.check("alice's copy of bob's position is bob's report", self.metres(got, answer) < 1, metres=round(self.metres(got, answer), 2))
        refused = self.cmd(B, "location-request", "mcptt", MCPTT[A])
        self.check("bob, without the right, is refused (403, warning 193)", refused["result"].startswith("403") and "193" in refused["result"],
                   result=refused["result"])
        # A group call fires GroupCallNonEmergency at both phones.
        counts = {c: len(self.location_of(c)["sent"]) for c in (A, B)}
        self.cmd(A, "group", GROUP, timeout=30)
        self.established(B)
        for c in (A, B):
            new = self.wait(f"{c} reports the group call", lambda c=c: [r for r in self.location_of(c)["sent"][counts[c]:] if r.get("triggers")] or None, timeout=15)
            self.check(f"{c}: a group call's start is reported (GroupCallNonEmergency)", bool(new), triggers=new[-1].get("triggers"))
        self.hangup(A)
        self.idle_everywhere([A, B])

    def alerts(self, client: str) -> dict:
        return self.cmd(client, "alerts")

    def alert_from(self, client: str, person: str) -> dict | None:
        return next((a for a in self.alerts(client)["received"] if a["person"] == person), None)

    def s14_emergency_alerts(self) -> None:
        """Emergency alerts on a lab made with --location --priority --mcvideo: the Call tab's single alert through MCPTT
        (TS 24.379 §12.1), MCData (TS 24.282 §16.2) and MCVideo (TS 24.281 §11.2), the ad hoc group emergency alert
        (§12.1A), and the alert an emergency alert area raises (§12.1.1.6). alice may cancel alerts, bob may not; bob's
        MCData alerts are for another group only (the fixture's alert_groups), so the server refuses them here."""
        every = ["mcdata", "mcptt", "mcvideo"]
        r = self.cmd(A, "alert", "on")
        self.check("alice's alert goes through MCPTT, MCData and video", r["result"].startswith("Emergency alert sent") and
                   sorted(r["state"]["own"]) == every, result=r["result"])
        got = self.wait("bob receives alice's alert on every service", lambda: (x := self.alert_from(B, "alice")) and len(x["services"]) == 3 and x)
        self.check("bob shows alice's alert once, on the group, with her position", got["group"] == GROUP and got["lat"] is not None and not got["adhoc"],
                   services=got["services"])
        refused = self.cmd(B, "alert-cancel-of", "alice")
        self.check("bob may not cancel alice's alert", "not allowed" in refused["result"] and "cancelled (" not in refused["result"] and
                   self.alert_from(B, "alice") is not None, result=refused["result"])
        r = self.cmd(A, "alert", "off")
        self.check("alice cancels her alert everywhere", r["result"].startswith("Emergency alert cancelled") and not r["state"]["own"], result=r["result"])
        self.wait("alice's alert leaves bob's phone", lambda: self.alert_from(B, "alice") is None)
        self.check("bob no longer shows alice's alert", True)
        # bob raises one; alice, authorised, cancels it for him.
        r = self.cmd(B, "alert", "on")
        self.check("bob's alert goes through MCPTT and video, and says MCData refused it", sorted(r["state"]["own"]) == ["mcptt", "mcvideo"] and
                   "MCData: not allowed" in r["result"], result=r["result"])
        self.wait("alice receives bob's alert", lambda: (x := self.alert_from(A, "bob")) and len(x["services"]) == 2 and x)
        r = self.cmd(A, "alert-cancel-of", "bob")
        self.check("alice cancels bob's alert on both services", r["result"].startswith("Bob's alert cancelled"), result=r["result"])
        self.wait("bob's own alert is down", lambda: not self.alerts(B)["own"])
        self.check("bob's phone shows his alert cancelled by alice", True)
        # One service only.
        r = self.cmd(A, "alert", "on", "mcdata")
        self.check("an MCData-only alert", list(r["state"]["own"]) == ["mcdata"], result=r["result"])
        got = self.wait("bob receives the MCData alert", lambda: self.alert_from(B, "alice"))
        self.check("bob shows it as MCData only", got["services"] == ["mcdata"], services=got["services"])
        self.cmd(A, "alert", "off")
        self.wait("the MCData alert leaves bob's phone", lambda: self.alert_from(B, "alice") is None)
        # Ad hoc group emergency alert to everyone affiliated to the group.
        r = self.cmd(A, "adhoc-alert", f"affiliated:{GROUP}")
        self.check("alice's ad hoc alert goes through MCPTT and video", r["result"].startswith("Ad hoc alert sent") and
                   sorted(r["state"]["adhoc"]) == ["mcptt", "mcvideo"], result=r["result"])
        got = self.wait("bob receives the ad hoc alert", lambda: (x := self.alert_from(B, "alice")) and len(x["services"]) == 2 and x)
        self.check("bob shows it as ad hoc, with its criteria", got["adhoc"] and got["criteria"] == f"affiliated:{GROUP}", criteria=got["criteria"])
        self.cmd(A, "adhoc-alert", "cancel")
        self.wait("the ad hoc alert leaves bob's phone", lambda: self.alert_from(B, "alice") is None)
        self.check("the ad hoc alert is withdrawn", True)
        # alice walks into the emergency alert area: the server says so, and her phone raises an alert (MCPTT and MCData).
        try:
            self.cmd(A, "location-position", "48.05", "2.05")
            notice = self.wait("alice's area notice", lambda: next((n for n in self.alerts(A)["notices"] if n["alert_area"] and n["entered"]), None), timeout=30)
            self.check("alice is told she entered the emergency alert area", notice is not None, notice=notice)
            own = self.wait("alice's area alert is up", lambda: (x := self.alerts(A)["own"]) and "mcptt" in x and "mcdata" in x and x, timeout=15)
            self.check("the area raised alice's alert on MCPTT and MCData", sorted(own) == ["mcdata", "mcptt"], own=own)
            got = self.wait("bob receives alice's area alert", lambda: (x := self.alert_from(B, "alice")) and len(x["services"]) == 2 and x)
            self.check("bob sees it with alice's position in the area", abs(got["lat"] - 48.05) < 0.001 and abs(got["lon"] - 2.05) < 0.001)
            self.cmd(A, "alert", "off")
            self.wait("alice's area alert leaves bob's phone", lambda: self.alert_from(B, "alice") is None)
        finally:
            self.cmd(A, "location-position", "clear", ok=False)
        left = self.wait("alice's notice of leaving the area", lambda: next((n for n in self.alerts(A)["notices"] if n["alert_area"] and not n["entered"]), None), timeout=30)
        self.check("back at her position, alice is told she left the area", left is not None)

    def s15_server_address(self) -> None:
        """The server address edited in Settings (a phone profile from lab/run/<lab>/phone): a copy of bob's profile with
        a stale address cannot reach the servers; setting the host's address in the app brings everything up with the
        profile's certificates unchanged; resetting goes back to the profile's address; the real profile is restored."""
        dev = self.devices[B]
        real = json.loads((Path(__file__).resolve().parents[1] / "lab" / "run" / self.name / "phone" / f"{B}.mcxprov").read_text())
        host = {v.rsplit(":", 1)[0] for v in real["bundle"]["endpoints"].values()}.pop()
        stale = "192.0.2.10"  # TEST-NET-1: never assigned, as a lost DHCP lease
        copy = json.loads(json.dumps(real))
        copy["bundle"]["endpoints"] = {k: f"{stale}:{v.rsplit(':', 1)[1]}" for k, v in real["bundle"]["endpoints"].items()}
        local = Path(__file__).resolve().parents[1] / "lab" / "run" / self.name / "stale.mcxprov"
        local.write_text(json.dumps(copy))
        for path, name in ((local, "stale.mcxprov"), (Path(__file__).resolve().parents[1] / "lab" / "run" / self.name / "phone" / f"{B}.mcxprov", "real.mcxprov")):
            dev.adb("push", str(path), f"/data/local/tmp/{name}")
            dev.adb("shell", "chmod", "644", f"/data/local/tmp/{name}")
        try:
            self.cmd(B, "import", "/data/local/tmp/stale.mcxprov")
            time.sleep(15)
            s = self.status(B)
            self.check("with the stale address bob cannot register", not s["registered"], phase=s["phase"])
            r = self.cmd(B, "server-address", host)
            self.check("the address is set in the app, the profile's kept", r["current"] == host and r["profile"] == stale and r["edited"], result=r)
            s = self.wait("bob ready at the edited address", lambda: (x := self.status(B))["phase"] == "ready" and x, timeout=90)
            self.check("bob registers, with the profile's certificates", s["registered"])
            for service in ("mcptt", "mcdata"):
                self.check(f"bob {service} authorised and affiliated", s[service]["authorised"] and s[service]["affiliations"].get(GROUP) == "affiliated")
            self.cmd(A, "group", GROUP, timeout=30)
            self.established(B)
            self.check("a group call from alice reaches bob", True)
            self.hangup(A)
            self.idle_everywhere([A, B])
            r = self.cmd(B, "server-address", "reset")
            self.check("reset goes back to the profile's address", r["current"] == stale and not r["edited"], result=r)
        finally:
            self.cmd(B, "import", "/data/local/tmp/real.mcxprov", ok=False)
            dev.adb("shell", "rm", "-f", "/data/local/tmp/stale.mcxprov", "/data/local/tmp/real.mcxprov", check=False)
            local.unlink(missing_ok=True)
        s = self.wait("bob ready with his real profile", lambda: (x := self.status(B))["phase"] == "ready" and x, timeout=90)
        r = self.cmd(B, "server-address", "reset")
        self.check("importing a profile clears the edited address", not r["edited"] and r["current"] == host, result=r)
        self.wait("bob ready again", lambda: (x := self.status(B))["phase"] == "ready" and x, timeout=90)

    def s16_emergency_calls(self) -> None:
        """MCPTT emergency and imminent peril calls (TS 24.379 §6.2.8) on a lab made with --priority: emergency and imminent
        peril group calls, raising and cancelling in the call by re-INVITE, the state followed at the other phone, an
        emergency private call, and the emergency location triggers meanwhile."""
        priority = lambda c: (self.call(c) or {}).get("priority")
        self.cmd(A, "group", GROUP, "emergency", timeout=30)
        self.established(B)
        self.check("alice's emergency group call is up as an emergency", priority(A) == "emergency", priority=priority(A))
        self.wait("bob's phone shows the emergency", lambda: priority(B) == "emergency")
        self.check("bob's phone shows the emergency", True)
        self.check("alice's MCPTT location reporting is in emergency", self.location_of(A)["emergency"] is True)
        self.talk_and_verify(A, [B])
        self.cmd(A, "priority", "emergency", "off")
        self.wait("bob's phone shows the emergency cancelled", lambda: priority(B) == "normal")
        self.check("alice cancels the emergency in the call; bob's phone follows", priority(A) == "normal")
        self.check("alice's location reporting is back to normal", self.location_of(A)["emergency"] is False)
        self.hangup(A)
        self.idle_everywhere([A, B])
        self.cmd(A, "group", GROUP, "imminent", timeout=30)
        self.established(B)
        self.wait("bob's phone shows imminent peril", lambda: priority(B) == "imminent")
        self.check("an imminent peril group call reaches bob as one", priority(A) == "imminent")
        self.cmd(B, "priority", "emergency", "on")
        self.wait("alice's phone shows bob's emergency", lambda: priority(A) == "emergency")
        self.check("bob raises an emergency in the call; alice's phone follows", priority(B) == "emergency")
        self.talk_and_verify(B, [A])
        self.cmd(B, "priority", "emergency", "off")
        self.wait("the emergency ends at alice's phone", lambda: priority(A) == "normal")
        self.check("bob cancels it", priority(B) == "normal")
        self.hangup(A)
        self.idle_everywhere([A, B])
        self.cmd(A, "private", MCPTT[B], "auto", "emergency", timeout=30)
        self.established(B)
        self.wait("bob's phone shows the emergency private call", lambda: priority(B) == "emergency")
        self.check("an emergency private call reaches bob as one", priority(A) == "emergency")
        self.talk_and_verify(A, [B])
        self.hangup(A)
        self.idle_everywhere([A, B])

    def s18_floor_queue(self) -> None:
        """Queued floor requests (TS 24.380 §6.2.4.9), as Unit Test Tutorial 2 runs them with the Linux client: a request
        queued while alice talks, its position asked, the queue left, a queued grant accepted, one let lapse (T132), and
        the hold-to-talk grant taken at once. Needs a lab made with --floor-queue."""
        if not meta(self.name).get("floor_queue"):
            print("  [SKIP] S18 needs a lab made with --floor-queue")
            self.skipped.append("S18")
            return
        call = self.cmd(A, "group", GROUP)
        self.check("alice's group call is established", call["state"] == "established")
        for c in (B, C):
            self.established(c)

        def alice_talks():
            holder = threading.Thread(target=lambda: self.cmd(A, "ptt", SPEECH[A], "6", timeout=30))
            holder.start()
            self.wait("alice holds the floor", lambda: self.call(A).get("floor") == "HAS_PERMISSION", timeout=5)
            return holder

        def queued(c):
            return (x := self.call(c)) and x.get("floor") == "QUEUED" and not x.get("pending_floor_confirmation") and x

        # 1. Queued, asked, left (§6.2.4.4.9, §6.2.4.9.9, §6.2.4.9.6), then queued again and accepted (§6.2.4.9.12).
        holder = alice_talks()
        self.cmd(B, "floor", "queue", SPEECH[B])
        got = self.wait("bob queued", lambda: queued(B), timeout=5)
        self.check("bob's request is queued at position 1, not denied", got.get("queue_position") == 1, call=got)
        before = sum("queued position" in e for e in got["floor_events"])
        self.cmd(B, "queue-status")
        again = self.wait("a fresh Floor Queue Position Info",
                          lambda: (x := self.call(B)) and sum("queued position" in e for e in x["floor_events"]) > before and x, timeout=5)
        self.check("bob asks for his position and is told 1", again.get("queue_position") == 1 and again.get("floor") == "QUEUED")
        self.cmd(B, "floor", "release")
        left = self.wait("bob left the queue", lambda: (x := self.call(B)) and x.get("floor") == "NO_PERMISSION" and x, timeout=5)
        self.check("bob leaves the queue; alice still talks", left.get("queue_position") is None
                   and self.call(A).get("floor") == "HAS_PERMISSION")
        self.cmd(B, "floor", "queue", SPEECH[B])
        self.wait("bob queued again", lambda: queued(B), timeout=5)
        holder.join()
        offered = self.wait("the floor offered to bob", lambda: (x := self.call(B)) and x.get("pending_floor_confirmation") and x, timeout=8)
        self.check("alice's release grants the floor to bob's queued request, awaiting his acceptance",
                   offered.get("floor") == "QUEUED", call=offered)
        self.cmd(B, "floor-confirm")
        self.check("bob accepts and talks", self.wait("bob talks", lambda: self.call(B).get("floor") == "HAS_PERMISSION", timeout=3))
        self.check("alice and client-c are told bob talks",
                   self.wait("taken by bob", lambda: all((self.call(c) or {}).get("talker") == MCPTT[B] for c in (A, C)), timeout=5))
        self.cmd(B, "floor", "release")
        for c in (A, B, C):
            self.wait(f"{c} floor idle", lambda c=c: (x := self.call(c)) and x.get("floor") == "NO_PERMISSION", timeout=5)

        # 2. A queued grant the user never accepts: T132 runs out, bob gives the floor back (§6.2.4.9.13).
        sent_before = sum(b["direction"] == "sent" for b in self.call(B)["bursts"])
        holder = alice_talks()
        self.cmd(B, "floor", "queue", SPEECH[B])
        self.wait("bob queued", lambda: queued(B), timeout=5)
        holder.join()
        self.wait("the floor offered to bob", lambda: (x := self.call(B)) and x.get("pending_floor_confirmation"), timeout=8)
        lapsed = self.wait("bob's grant lapses", lambda: (x := self.call(B)) and x.get("floor") == "NO_PERMISSION" and x, timeout=5)
        self.check("without acceptance the grant lapses after T132 and bob sends no speech",
                   any("lapsed" in e for e in lapsed["floor_events"])
                   and sum(b["direction"] == "sent" for b in lapsed["bursts"]) == sent_before, events=lapsed["floor_events"][-4:])
        for c in (A, C):
            self.wait(f"{c} floor idle", lambda c=c: (x := self.call(c)) and x.get("floor") == "NO_PERMISSION", timeout=8)

        # 3. Hold to talk: the user still holds PTT, so the queued grant is taken at once (§6.2.4.9.12).
        holder = alice_talks()
        self.cmd(B, "floor", SPEECH[B])
        self.wait("bob queued", lambda: (x := self.call(B)) and x.get("floor") == "QUEUED", timeout=5)
        holder.join()
        self.check("holding PTT, bob gets the floor as soon as alice releases",
                   self.wait("bob talks", lambda: self.call(B).get("floor") == "HAS_PERMISSION", timeout=8))
        self.cmd(B, "floor", "release")
        self.hangup(A)
        self.idle_everywhere([A, B, C])

    def s17_functional_aliases(self) -> None:
        """MCPTT functional aliases (TS 24.379 clause 9A) on a lab made with --functional-alias: "duty-officer" for alice
        and bob (one activation at a time, bob may take it over), "incident-commander" for alice. Activation answered 200 with the
        outcome by NOTIFY (9A.2.1.2, 9A.2.1.3), the take-over offered then done, a private call to the alias reaching
        whoever holds it (11.1.1.2.1.1 step 14c, 300 then the call), and an alias presented on alice's calls."""
        fa = lambda c: self.status(c)["functional_aliases"]
        alias = lambda c, name: next((x for x in fa(c)["available"] if x.startswith(f"sip:{name}.alias@")), None)
        duty_officer, incident_commander = alias(A, "duty-officer"), alias(A, "incident-commander")
        if not self.check("alice may use duty-officer and incident-commander", bool(duty_officer and incident_commander), available=fa(A)["available"]):
            return
        # Each PUBLISH lists every alias wanted active (9A.2.1.2 step 6 a; the server replaces the set, 9A.2.2.2.3
        # steps 12 to 14): a second activation keeps the first, a deactivation lists the alias that stays.
        self.cmd(A, "fa", "activate", duty_officer)
        self.wait("alice holds duty-officer", lambda: duty_officer in fa(A)["active"])
        self.cmd(A, "fa", "activate", incident_commander)
        self.wait("alice holds duty-officer and incident-commander", lambda: {duty_officer, incident_commander} <= set(fa(A)["active"]))
        self.cmd(A, "fa", "deactivate", duty_officer)
        self.wait("alice gives duty-officer up and keeps incident-commander",
                  lambda: duty_officer not in fa(A)["active"] and incident_commander in fa(A)["active"])
        self.cmd(A, "fa", "activate", duty_officer)
        self.wait("alice holds both again", lambda: {duty_officer, incident_commander} <= set(fa(A)["active"]))
        self.check("alice's activation is answered 200, the outcome by NOTIFY", fa(A)["last_status"] == 200, last=fa(A))
        self.cmd(B, "fa", "activate", duty_officer)
        self.wait("bob is offered duty-officer for take-over", lambda: duty_officer in fa(B)["take_over_possible"])
        self.check("bob's activation beyond the limit: 200, then take-over-possible", duty_officer not in fa(B)["active"], last=fa(B))
        self.cmd(B, "fa", "takeover", duty_officer)
        self.wait("bob holds duty-officer", lambda: duty_officer in fa(B)["active"])
        self.wait("alice is told she no longer holds duty-officer", lambda: duty_officer not in fa(A)["active"])
        self.check("bob takes duty-officer over; alice keeps incident-commander", incident_commander in fa(A)["active"])
        # A private call to the alias reaches whoever holds it: bob.
        third = next(c for c in self.devices if c not in (A, B))
        self.cmd(third, "private-fa", duty_officer, "auto", timeout=30)
        bob = self.established(B)
        self.check("a call to duty-officer reaches bob", bob["peer"] == MCPTT[third], peer=bob["peer"])
        self.check("bob sees the call was to duty-officer", bob.get("called_alias") == duty_officer, called=bob.get("called_alias"))
        self.talk_and_verify(third, [B])
        self.hangup(third)
        self.idle_everywhere([third, B])
        # Alice presents incident-commander on her calls.
        self.cmd(A, "fa", "use", incident_commander)
        self.cmd(A, "private", MCPTT[third], "auto", timeout=30)
        other = self.established(third)
        self.check(f"{third} sees alice's call from incident-commander", other.get("calling_alias") == incident_commander, calling=other.get("calling_alias"))
        self.hangup(A)
        self.idle_everywhere([A, third])
        self.cmd(A, "fa", "use", "none")
        self.cmd(A, "fa", "deactivate", incident_commander)
        self.cmd(B, "fa", "deactivate", duty_officer)
        self.wait("the aliases are deactivated", lambda: incident_commander not in fa(A)["active"] and duty_officer not in fa(B)["active"])
        self.check("alice and bob deactivate their aliases", True)

    SCENARIOS = {"S1": s1_startup, "S2": s2_private_automatic, "S3": s3_private_manual, "S4": s4_group, "S5": s5_adhoc, "S6": s6_sds,
                 "S7": s7_refresh_and_recovery, "S8": s8_lifecycle, "S9": s9_impaired, "S10": s10_latency,
                 "S11": s11_product, "S12": s12_private_no_floor, "S13": s13_location,
                 "S14": s14_emergency_alerts,
                 "S15": s15_server_address,
                 "S16": s16_emergency_calls,
                 "S17": s17_functional_aliases,
                 "S18": s18_floor_queue}


def main() -> None:
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    lab = Lab(sys.argv[1])
    wanted = sys.argv[2:] or list(Lab.SCENARIOS)
    for key in wanted:
        lab.scenario = key
        fn = Lab.SCENARIOS[key]
        print(f"{key} {fn.__name__}")
        try:
            fn(lab)
        except KeyError as e:
            if not (e.args and e.args[0] in (A, B, C) and e.args[0] not in lab.devices):
                raise
            # A phones-only lab has no third client: create the lab without --phones-only, so that redroid
            # client-c joins the phones (the C++ reference client does not take the debug control commands).
            print(f"  [SKIP] {key} needs {e.args[0]}, which this lab does not have (create it without --phones-only)")
            lab.skipped.append(key)
            lab.results = [r for r in lab.results if r["scenario"] != key]
            lab.leave_clean()
        except Failed as e:
            lab.check(f"{key} completed", False, error=str(e))
            lab.leave_clean()
    passed = sum(r["ok"] for r in lab.results)
    print(f"\n{passed}/{len(lab.results)} checks passed" + (f"; skipped {' '.join(lab.skipped)} (no client-c)" if lab.skipped else ""))
    out = Path(__file__).resolve().parents[1] / "lab" / "run" / lab.name / "android-scenarios.json"
    out.write_text(json.dumps(lab.results, indent=2) + "\n")
    sys.exit(0 if passed == len(lab.results) else 1)


if __name__ == "__main__":
    main()

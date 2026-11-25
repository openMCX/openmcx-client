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
from androidlab import ADB, FILES, PACKAGE, Device, meta  # noqa: E402

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
        self.meta = m
        self.protected = bool(m.get("protected"))
        self.results: list[dict] = []
        self.scenario = ""
        self.skipped: list[str] = []
        self.skipped_parts: list[str] = []  # parts of a scenario skipped for a missing client, the rest still run

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

    def group_call_over(self) -> None:
        """Every member of the group has left the group call, not only the two phones checked: when the initiator
        leaves, the MCPTT server BYEs the others but keeps the session until each has answered, and a new group call
        INVITE in that time joins the session being released, which then ends with its last answer (see
        TEST_REPORT.md, "S16"). The reference client cannot be asked, so it gets a grace period."""
        self.idle_everywhere(list(self.devices))
        if meta(self.name).get("reference_clients"):
            time.sleep(2)

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
        """Hang up what a scenario that stopped half-way left, and give up its functional aliases, for the next
        scenario (an alias held on the server outlives the run: a later activation of it is then refused)."""
        for c in self.devices:
            try:
                if self.call(c):
                    self.cmd(c, "hangup", ok=False)
            except Exception:
                pass
            try:
                if self.cmd(c, "sds-session", ok=False).get("result"):
                    self.cmd(c, "sds-session", "close", ok=False)
            except Exception:
                pass
            try:
                if self.cmd(c, "video-status", ok=False).get("result", {}).get("active"):
                    self.cmd(c, "video-hangup", ok=False)
            except Exception:
                pass
        self.release_aliases(list(self.devices))

    def release_aliases(self, clients) -> None:
        """Stop presenting and deactivate every functional alias these clients hold (S17 starts from none)."""
        for c in clients:
            try:
                held = self.status(c).get("functional_aliases", {}).get("active") or []
                if held:
                    self.cmd(c, "fa", "use", "none", ok=False)
                for alias in held:
                    self.cmd(c, "fa", "deactivate", alias, ok=False)
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
        self.group_call_over()
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
        self.group_call_over()
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
        # Start from no alias held: one left active on the server by an earlier run would make alice's activation of
        # duty-officer refused (one user at a time).
        self.release_aliases([A, B])
        self.wait("alice and bob hold no alias", lambda: not fa(A)["active"] and not fa(B)["active"])
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
        third = next((c for c in self.devices if c not in (A, B)), None)
        if third is None:
            # A two-phone lab: the calls need a third client to call the alias and to be called by alice.
            print(f"  [SKIP] {self.scenario}: the calls to and from an alias need a third client (create the lab without --phones-only)")
            self.skipped_parts.append(f"{self.scenario} calls")
        else:
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

    # The reg event package (openmcx-server docs/USAGE.md: the operator commands of the SIP core).
    def sip_core(self, *command: str) -> str:
        container = f"{meta(self.name)['project']}-sip-core-1"
        out = subprocess.run(["docker", "exec", container, *command], capture_output=True, text=True, timeout=60)
        return (out.stdout + out.stderr).strip()

    def events_after(self, client: str, marker: str) -> list[str] | None:
        """The client's events after the last one containing [marker]; None when there is none."""
        events = self.cmd(client, "events", "400")
        at = max((i for i, e in enumerate(events) if marker in e), default=None)
        return None if at is None else events[at + 1:]

    def s19_reg_event(self) -> None:
        # TS 24.229 §5.1.1.3: subscribed to its own reg event once registered.
        for c in (A, B):
            self.ready(c)
            ok = bool(self.wait(f"{c} reg event subscription", lambda c=c: self.status(c).get("reg_subscribed"), timeout=30))
            self.check(f"{c} holds its reg event subscription", ok)
        # §5.1.1.5A: alice's 3600 s registration shortened to 120 s is renewed at once (600 s before an expiry 120 s away).
        out = self.sip_core("/opt/kamailio/sbin/kamcmd", "-s", "unix:/tmp/kamailio_ctl", "reginfo.shorten", "location",
                            "alice@mcx.test", "120")
        self.check("the SIP core shortened alice's registration", "shortened" in out, out=out)
        try:
            after = self.wait("alice reregisters", lambda: (e := self.events_after(A, "registration shortened")) is not None
                              and any("registered for" in x for x in e) and e, timeout=30)
        except Failed:
            after = None
        self.check("alice reregistered after the shortening", after is not None,
                   events=[e for e in self.cmd(A, "events", "400") if "shortened" in e or "registered for" in e][-4:])
        self.check("alice stays ready after the shortening", self.status(A)["phase"] == "ready")
        # §5.1.1.7 "rejected" during a call: no call left, alice off (shown) until she logs on.
        self.cmd(A, "private", MCPTT[B], "auto")
        self.established(B)
        out = self.sip_core("sipcore-deregister", "alice@mcx.test", "rejected")
        try:
            s = self.wait("alice deregistered (rejected)", lambda: (s := self.status(A))["logged_off"]
                          and s.get("network_deregistered") == "rejected" and s, timeout=30)
        except Failed:
            s = self.status(A)
        self.check("alice is off after 'rejected', with the reason", s["logged_off"] and s.get("network_deregistered") == "rejected",
                   status={k: s.get(k) for k in ("phase", "logged_off", "network_deregistered", "registered")}, core=out)
        self.check("alice's services are no longer authorised", not s["mcptt"]["authorised"] and not s["mcdata"]["authorised"])
        self.idle_everywhere([A, B])
        self.check("no call left after 'rejected'", self.call(A) is None and self.call(B) is None)
        time.sleep(5)
        self.check("alice stays off until she logs on", self.status(A)["phase"] == "logged-off")
        self.cmd(A, "log-on")
        s = self.ready(A)
        self.wait("alice reg event subscription again", lambda: self.status(A).get("reg_subscribed"), timeout=30)
        self.check("alice is back after log-on", s["registered"] and self.status(A).get("network_deregistered") is None)
        # §5.1.1.7 "deactivated": bob's TLS session goes, then an initial registration on a new flow, services back.
        old = self.status(B)["flow"]
        out = self.sip_core("sipcore-deregister", "bob@mcx.test", "deactivated")
        try:
            s = self.wait("bob registered again on a new flow", lambda: (s := self.status(B))["phase"] == "ready"
                          and s["flow"] != old and s.get("reg_subscribed") and s, timeout=90)
        except Failed:
            s = self.status(B)
        self.check("bob registered again on a new flow after 'deactivated'", s["phase"] == "ready" and s["flow"] != old,
                   old=old, new=s["flow"], core=out)
        self.check("bob's services are authorised and affiliated again", s["mcptt"]["authorised"]
                   and s["mcptt"]["affiliations"].get(GROUP) == "affiliated")
        self.cmd(A, "private", MCPTT[B], "auto")
        self.check("alice's call reaches bob after his new registration", bool(self.established(B)))
        self.hangup(A)
        self.idle_everywhere([A, B])


    # Phases 0 and 1 of docs/PARITY_PLAN.md.

    def sip_trace(self, client: str) -> str:
        """The app's SIP trace (debug builds write every message to files/sip-trace.log)."""
        return self.devices[client].adb("shell", "run-as", PACKAGE, "cat", "files/sip-trace.log", check=False)

    def last_sent(self, client: str, start: str, containing: str = "") -> str | None:
        """The last message [client] sent whose start line begins with [start] (and that contains [containing]), from
        its SIP trace."""
        blocks = [b for b in self.sip_trace(client).split("----- ") if " send\n" in b.split("\n", 1)[0] + "\n"]
        found = [b for b in blocks if b.split("\n", 1)[1].startswith(start) and containing in b] if blocks else []
        return found[-1] if found else None

    def procedures(self, client: str) -> dict:
        return self.cmd(client, "procedures")

    def s20_implicit_floor(self) -> None:
        # TS 24.380 §14.2.5, §6.2.4.2.2: the initial INVITE requests the floor implicitly; the server accepts it and
        # grants the floor by Floor Granted, and the talk burst reaches the other members unchanged.
        for granted in (False, True):
            label = "with mc_granted" if granted else "without mc_granted"
            args = ["group", GROUP, "implicit", SPEECH[A]] + (["granted"] if granted else [])
            call = self.cmd(A, *args)
            self.check(f"alice's implicit floor request call is established ({label})", call["state"] == "established", call=call)
            self.check(f"the call's offer had mc_granted only when asked ({label})", call.get("granted_offered") == granted)
            invite = self.last_sent(A, "INVITE ") or ""
            fmtp = next((l for l in invite.splitlines() if l.startswith("a=fmtp:MCPTT")), "")
            self.check(f"the INVITE's offer has mc_implicit_request ({label})", "mc_implicit_request" in fmtp, fmtp=fmtp)
            self.check(f"mc_granted is in the offer only next to it ({label})", ("mc_granted" in fmtp) == granted, fmtp=fmtp)
            # The OpenMCX controlling function accepts the request and does not use mc_granted (TS 24.380 §14.3.4):
            # the floor comes by Floor Granted, once.
            self.check(f"the server accepted the implicit request ({label})",
                       call.get("implicit_floor_request") == "accepted", implicit=call.get("implicit_floor_request"))
            for c in (B, C):
                self.established(c)
            try:
                sent = self.wait("alice's implicit talk burst", lambda: next((b for b in (self.call(A) or {}).get("bursts", [])
                                 if b["direction"] == "sent" and b["frames"] > 100), None), timeout=15)
            except Failed:
                sent = None
            self.check(f"alice talks on the implicit grant ({label})", sent is not None, call=self.call(A))
            grants = [e for e in (self.call(A) or {}).get("floor_events", []) if e.startswith("granted")]
            self.check(f"one floor grant only ({label})", len(grants) == 1, events=(self.call(A) or {}).get("floor_events"))
            if sent:
                self.wait("alice's burst ends", lambda: (x := self.call(A)) and x.get("floor") == "NO_PERMISSION", timeout=20)
                sent = [b for b in self.call(A)["bursts"] if b["direction"] == "sent"][-1]
                # The burst starts with the call, while the other members' legs are still being set up: a listener may
                # miss its first frames (at most half a second); what it gets is the rest of the burst, unchanged.
                for l in (B, C):
                    got = next((b for b in self.call(l)["bursts"] if b["ssrc"] == sent["ssrc"]), None)
                    tail = got is not None and got["hashes"] == sent["hashes"][len(sent["hashes"]) - len(got["hashes"]):]
                    self.check(f"{l} received alice's implicit burst unchanged, but for its first frames ({label})",
                               tail and got["frames"] >= sent["frames"] - 25, sent=sent["frames"], received=got and got["frames"])
            # TS 24.380 §6.2.4.3.5: the Floor Release of a normal call carries bit A (0x8000) in its Floor Indicator.
            self.hangup(A)
            self.idle_everywhere([A, B, C])
        # The explicit request afterwards: no mc_implicit_request in the offer.
        self.cmd(A, "group", GROUP, "explicit")
        invite = self.last_sent(A, "INVITE ") or ""
        self.check("an explicit call's offer has no mc_implicit_request", "mc_implicit_request" not in invite)
        self.hangup(A)
        self.idle_everywhere([A, B, C])

    def s21_service_settings(self) -> None:
        if self.procedures(A)["settings_subscribed"]:  # left by an earlier run
            self.cmd(A, "settings-unsubscribe", ok=False)
        self.cmd(A, "answer-mode", "automatic")  # the provisioned mode, whatever an earlier run left
        # TS 24.379 §7.2.4: subscribe to this user's service settings; the NOTIFY gives this client's answer mode.
        r = self.cmd(A, "settings-subscribe")
        self.check("alice's settings subscription is accepted", r["status"] == 200, result=r)
        own = lambda p: next((v for v in p["service_settings"].values() if v.get("own")), None)
        try:
            p = self.wait("alice's own settings notified", lambda: (p := self.procedures(A)) and own(p) and p, timeout=15)
        except Failed:
            p = self.procedures(A)
        self.check("the notified settings name alice's client with its answer mode", own(p) is not None
                   and own(p).get("answer_mode") == "automatic", settings=p.get("service_settings"))
        # §7.2.3: the answer mode changed; the settings-only PUBLISH; the subscription notifies the new one.
        r = self.cmd(A, "answer-mode", "manual")
        self.check("alice's settings PUBLISH with the manual answer mode is accepted",
                   (r.get("settings_publication") or {}).get("status") == 200, result=r)
        publish = self.last_sent(A, "PUBLISH ", "Event: poc-settings") or ""
        self.check("the settings PUBLISH carries no access token, the client ID and SIP-If-Match",
                   "<mcptt-access-token" not in publish and "<mcptt-client-id " in publish and "SIP-If-Match:" in publish
                   and "<answer-mode>manual</answer-mode>" in publish, publish=publish[-1500:])
        try:
            p = self.wait("the manual answer mode notified", lambda: (p := self.procedures(A)) and (own(p) or {}).get("answer_mode") == "manual" and p, timeout=15)
            ok = True
        except Failed:
            p, ok = self.procedures(A), False
        self.check("the subscription notifies the new answer mode", ok, settings=p.get("service_settings"))
        # With the manual answer mode an automatic private call to alice rings (§11.1.1.2.1.2); she declines it.
        caller = threading.Thread(target=lambda: self.cmd(B, "private", MCPTT[A], "auto", ok=False, timeout=60))
        caller.start()
        try:
            ringing = self.wait("alice rings", lambda: (c := self.call(A)) and c["state"] == "ringing" and c, timeout=15)
        except Failed:
            ringing = None
        self.check("a private call to alice rings after the manual answer mode", ringing is not None, call=self.call(A))
        self.cmd(A, "reject", ok=False)
        caller.join()
        self.idle_everywhere([A, B])
        r = self.cmd(A, "answer-mode", "automatic")
        self.check("the automatic answer mode published again", (r.get("settings_publication") or {}).get("status") == 200, result=r)
        r = self.cmd(A, "settings-subscribe", "fetch")
        self.check("a settings fetch (Expires 0) is accepted", r["status"] == 200, result=r)
        r = self.cmd(A, "settings-resubscribe")
        self.check("the settings subscription is refreshed", r["status"] == 200, result=r)
        r = self.cmd(A, "settings-unsubscribe")
        self.check("the settings subscription ends", r["status"] == 200 and not self.procedures(A)["settings_subscribed"], result=r)
        # §7.2.3 with Expires 0: the settings removed, which logs alice off MCPTT (§7.2.1A NOTE 3); log-off and log-on
        # bring her back.
        r = self.cmd(B, "settings-publish", "remove")
        self.check("bob's settings removal is accepted", r["status"] == 200, result=r)
        self.check("bob is no longer authorised for MCPTT", not self.status(B)["mcptt"]["authorised"])
        self.cmd(B, "log-off")
        self.cmd(B, "log-on")
        s = self.ready(B)
        self.check("bob is authorised again after log-on", s["mcptt"]["authorised"] and s["mcptt"]["affiliations"].get(GROUP) == "affiliated")

    def s22_monitor(self) -> None:
        monitor = GROUP.replace("operations", "monitor")
        p = self.procedures(A)
        if monitor not in p.get("monitorable_groups", []):
            print(f"  [SKIP] S22 needs {monitor} in the user profile (create the lab with --monitor-group)")
            self.skipped_parts.append("S22 (no --monitor-group)")
            return
        # TS 24.379 §9.2.1.2: alice and bob monitor the second group; the PUBLISH names both groups.
        for c in (A, B):
            r = self.cmd(c, "monitor", monitor)
            self.check(f"{c} monitors {monitor}", monitor in r["monitored_groups"])
            s = self.wait(f"{c} affiliated to both", lambda c=c: (s := self.status(c)) and s["mcptt"]["affiliations"].get(monitor) == "affiliated"
                          and s["mcptt"]["affiliations"].get(GROUP) == "affiliated" and s, timeout=15)
            self.check(f"{c} is affiliated to {GROUP} and {monitor}", bool(s))
        self.check("client-c does not monitor it", monitor not in self.status(C)["mcptt"]["affiliations"])
        # A call on the monitored group reaches alice, not client-c.
        call = self.cmd(B, "group", monitor)
        self.check("bob's call on the monitored group is established", call["state"] == "established")
        got = self.established(A)
        self.check("alice joins the call on the monitored group", got["group"] == monitor, call=got)
        time.sleep(1)
        self.check("client-c, not monitoring, gets no call", self.call(C) is None)
        self.talk_and_verify(B, [A])
        self.hangup(B)
        self.idle_everywhere([A, B])
        for c in (A, B):
            r = self.cmd(c, "monitor", "none")
            ok = self.wait(f"{c} left {monitor}", lambda c=c: not (s := self.status(c))["mcptt"]["affiliations"].get(monitor)
                           or s["mcptt"]["affiliations"].get(monitor) in ("deaffiliating", "deaffiliated"), timeout=15)
            self.check(f"{c} no longer monitors {monitor}", bool(ok) and r["monitored_groups"] == [])
            self.check(f"{c} stays affiliated to {GROUP}", self.status(c)["mcptt"]["affiliations"].get(GROUP) == "affiliated")

    def s23_remote_affiliation(self) -> None:
        # TS 24.379 §9.2.1.3: the affiliation of another user. The OpenMCX participating function authorises no user for
        # another's affiliation status (§9.2.2.2.4: 403), so the subscription to bob is refused; this user's own is
        # accepted, and its NOTIFY lists alice's client.
        r = self.cmd(A, "affiliation-subscribe", MCPTT[B])
        self.check("the subscription to bob's affiliation is refused by the server (403)", r["status"] == 403, result=r)
        r = self.cmd(A, "affiliation-subscribe", MCPTT[A])
        self.check("alice's subscription to her own affiliation is accepted", r["status"] == 200, result=r)
        groups = lambda: [g for v in (self.procedures(A)["target_affiliations"].get(MCPTT[A]) or {}).get("clients", {}).values()
                          for g, st in v.items() if st == "affiliated"]
        self.wait("alice's own affiliation notified", lambda: GROUP in groups(), timeout=15)
        self.check("the notification lists alice's client affiliated to the group", GROUP in groups())
        # §9.2.1.2 in mandatory mode, targeted at alice's own client: de-affiliate, then affiliate again.
        r = self.cmd(A, "affiliate-remote", MCPTT[A], GROUP, "deaffiliate", "mandatory")
        self.check("the mandatory-mode de-affiliation is accepted", r["status"] == 200, result=r)
        try:
            self.wait("alice de-affiliated", lambda: GROUP not in groups(), timeout=15)
            ok = True
        except Failed:
            ok = False
        self.check("the server de-affiliated alice's client", ok, target=self.procedures(A)["target_affiliations"])
        publish = self.last_sent(A, "PUBLISH ") or ""
        self.check("the de-affiliation PUBLISH has Expires 0 and no PIDF (§9.2.1.2 step 4)",
                   "Expires: 0" in publish and "application/pidf+xml" not in publish)
        r = self.cmd(A, "affiliate-remote", MCPTT[A], GROUP, "affiliate", "mandatory")
        self.check("the mandatory-mode affiliation is accepted", r["status"] == 200, result=r)
        self.wait("alice affiliated again", lambda: GROUP in groups(), timeout=15)
        publish = self.last_sent(A, "PUBLISH ") or ""
        self.check("the affiliation PUBLISH names the targeted client, no status attribute, a p-id",
                   "urn:uuid:" in publish and "<mcpttPI10:p-id>" in publish and 'status="' not in publish.split("application/pidf+xml", 1)[-1])
        r = self.cmd(A, "affiliation-unsubscribe", MCPTT[A])
        self.check("alice's subscription to her own affiliation ends", r["status"] == 200, result=r)
        # The server notifies one subscription per user: the client's own takes it back with its refresh.
        self.cmd(A, "refresh", "subscription")
        # §9.2.1.4 negotiated mode: the MESSAGE with the affiliation command. The OpenMCX servers do not pass it on to
        # bob (no clause 9.2.2 procedure of theirs handles it), so its final answer is what is checked.
        r = self.cmd(A, "affiliate-remote", MCPTT[B], GROUP, "deaffiliate", "negotiated")
        self.check("the negotiated-mode MESSAGE gets a final answer", r["status"] >= 200, result=r)
        message = self.last_sent(A, "MESSAGE ") or ""
        self.check("the MESSAGE carries the affiliation command naming bob",
                   "application/vnd.3gpp.mcptt-affiliation-command+xml" in message and "<de-affiliate><group>" in message
                   and f"<mcpttURI>{MCPTT[B]}</mcpttURI>" in message, message=message[-1200:])
        self.check("bob got no request (the server does not relay it)", self.procedures(B)["affiliation_command"] is None)
        s = self.status(A)
        self.check("alice is still affiliated to the group", s["mcptt"]["affiliations"].get(GROUP) == "affiliated")

    def s24_group_dynamic_data(self) -> None:
        # TS 24.379 §9.2.1.6: the subscription with the per-group filter; the NOTIFY's dynamic data.
        r = self.cmd(A, "gdd-subscribe", GROUP)
        self.check("alice's group dynamic data subscription is accepted", r["status"] == 200, result=r)
        gdd = lambda: self.procedures(A)["group_dynamic_data"].get(GROUP) or {}
        try:
            self.wait("dynamic data notified", lambda: gdd().get("notifications", 0) >= 1, timeout=15)
        except Failed:
            pass
        g = gdd()
        self.check("the dynamic data is notified", g.get("notifications", 0) >= 1, gdd=g)
        members = {m["id"] for m in g.get("members", [])}
        self.check("its affiliated members include alice and bob", {MCPTT[A], MCPTT[B]} <= members, members=sorted(members))
        before = g.get("notifications", 0)
        self.cmd(B, "group", GROUP)
        self.established(A)
        try:
            self.wait("call ongoing notified", lambda: gdd().get("call_ongoing") is True, timeout=15)
        except Failed:
            pass
        self.check("a call on the group is notified as ongoing", gdd().get("call_ongoing") is True,
                   gdd=gdd(), notifications_before=before)
        self.hangup(B)
        self.idle_everywhere([A, B, C])
        r = self.cmd(A, "gdd-resubscribe", GROUP)
        self.check("the dynamic data subscription is refreshed", r["status"] == 200, result=r)
        r = self.cmd(A, "gdd-unsubscribe", GROUP)
        self.check("the dynamic data subscription ends", r["status"] == 200 and not gdd().get("subscribed"), result=r)
        r = self.cmd(A, "gdd-subscribe", GROUP, "fetch", "call")
        self.check("a dynamic data fetch with an item filter is accepted", r["status"] == 200, result=r)

    # Phase 2: MCPTT call types, floor priority and pre-emption, regroups and temporary groups (S25 to S31).

    CHAT = "sip:operations-chat@mcx.test"
    NORTH, SOUTH = "sip:operations-north@mcx.test", "sip:operations-south@mcx.test"
    PRECONF = "sip:operations-preconf@mcx.test"

    def needs(self, flag: str, scenario: str) -> bool:
        """The lab was made with the androidlab.py option this scenario needs; else it is skipped."""
        if self.meta.get(flag):
            return True
        print(f"  [SKIP] {scenario} needs a lab made with --{flag.replace('_', '-')}")
        self.skipped_parts.append(f"{scenario} (no --{flag.replace('_', '-')})")
        return False

    def ringing(self, client: str) -> dict:
        return self.wait(f"{client} rings", lambda: (c := self.call(client)) and c.get("state") == "ringing" and c, timeout=15)

    def s25_first_to_answer(self) -> None:
        # TS 24.379 §11.1.1.2.1.1 steps 10 a) and 15, §11.1.1.2.1.2 step 8: both invitees ring (the server sets
        # Priv-Answer-Mode Manual), the first to answer takes the call, the other is cancelled. TS 37.579-2 6.2.21 had
        # the app answer the first-to-answer INVITE with 400.
        if not self.needs("priority", "S25"):
            return
        for winner, loser in ((B, C), (C, B)):
            result: dict = {}
            t = threading.Thread(target=lambda: result.update(r=self.cmd(A, "first-to-answer", MCPTT[B], MCPTT[C], ok=False, timeout=40)))
            t.start()
            rings = {c: self.ringing(c) for c in (B, C)}
            for c, call in rings.items():
                self.check(f"{c} rings for the first-to-answer call (not 400)", call["first_to_answer"] and call["peer"] == MCPTT[A], call=call)
            invite = self.last_sent(A, "INVITE ") or ""
            self.check("alice's INVITE has session type first-to-answer and both invitees, no Answer-Mode",
                       "<session-type>first-to-answer</session-type>" in invite and MCPTT[B] in invite and MCPTT[C] in invite
                       and "\nAnswer-Mode:" not in invite and "forwarding-sequence-number" not in invite)
            self.cmd(winner, "answer")
            t.join()
            r = result.get("r") or {}
            self.check(f"alice's call is established with {winner}", r.get("ok") and r["result"]["peer"] == MCPTT[winner], result=r)
            self.established(winner)
            self.wait(f"{loser} is no longer ringing", lambda: self.call(loser) is None, timeout=10)
            released = self.last_released(loser) or {}
            self.check(f"{loser}'s invitation ends (cancelled or not selected)", released.get("reason") in
                       ("cancelled", "not answered", "not selected for call", "released by the server"), released=released)
            self.talk_and_verify(A, [winner], seconds=2)
            self.talk_and_verify(winner, [A], seconds=2)
            self.hangup(A)
            self.idle_everywhere([A, B, C])
        # §11.1.1.2.1.1 step 2 and the server's 156: bob has no first-to-answer right.
        r = self.cmd(B, "first-to-answer", MCPTT[A], MCPTT[C], ok=False, timeout=30)
        self.check("bob's first-to-answer call is refused (no right)", not r.get("ok"), result=r)
        self.idle_everywhere([A, B, C])

    def s26_chat(self) -> None:
        # TS 24.379 §10.1.2.2.1.1: alice and bob join the chat group call (session type chat); the server invites nobody,
        # so client-c is not in it; bob leaves (BYE, §10.1.2.2.3.1) and joins again.
        if not self.needs("chat", "S26"):
            return
        call = self.cmd(A, "chat", self.CHAT)
        self.check("alice joins the chat group call", call["state"] == "established" and call["type"] == "chat", call=call)
        invite = self.last_sent(A, "INVITE ") or ""
        self.check("the INVITE has session type chat and the group", "<session-type>chat</session-type>" in invite and self.CHAT in invite)
        time.sleep(2)
        self.check("client-c is not invited to the chat call", self.call(C) is None)
        bob = self.cmd(B, "chat", self.CHAT)
        self.check("bob joins the same chat call", bob["state"] == "established" and bob["group"] == self.CHAT, call=bob)
        carol = self.cmd(C, "chat", self.CHAT)
        self.check("client-c joins too", carol["state"] == "established", call=carol)
        self.talk_and_verify(A, [B, C], seconds=2)
        # The server ends a chat call when one participant remains (§6.3.8.1 item 2): three are in it, so bob may leave.
        self.hangup(B)
        self.wait("bob left", lambda: self.call(B) is None, timeout=10)
        time.sleep(1)
        self.check("alice and client-c are still in the chat call after bob left",
                   (self.call(A) or {}).get("state") == "established" and (self.call(C) or {}).get("state") == "established")
        again = self.cmd(B, "rejoin", "chat")
        self.check("bob rejoins the chat call", again["state"] == "established" and again["type"] == "chat", call=again)
        self.talk_and_verify(B, [A, C], seconds=2)
        self.hangup(B)
        self.hangup(C)
        self.wait("the chat call ends with one participant left", lambda: self.call(A) is None, timeout=10)
        self.idle_everywhere([A, B, C])
        # The group list shows it, joined rather than called: it is a chat group of the user profile.
        doc = self.cmd(A, "group-document", self.CHAT)
        self.check("the chat group's document says chat", doc and doc["type"] == "chat", doc=doc)

    def s27_broadcast(self) -> None:
        # TS 24.379 §6.2.8.2: <broadcast-ind> in the INVITE; every member receives it as a broadcast call; a receiver's
        # floor request is denied (receive only, cause 5); the call ends with the initiator's transmission.
        if not self.needs("broadcast", "S27"):
            return
        call = self.cmd(A, "broadcast", GROUP)
        self.check("alice's broadcast call is established", call["state"] == "established" and call["broadcast"], call=call)
        invite = self.last_sent(A, "INVITE ") or ""
        self.check("the INVITE has <broadcast-ind> true", "<broadcast-ind>true</broadcast-ind>" in invite)
        for c in (B, C):
            got = self.established(c)
            self.check(f"{c} receives a broadcast group call", got["broadcast"] and got["group"] == GROUP, call=got)
        denied = self.cmd(B, "ptt", SPEECH[B], "1", timeout=30)
        self.check("bob, a receiver, is denied the floor", not denied.get("granted"), result=denied)
        self.check("bob is told the floor was denied (receive only)", any("denied cause 5" in e for e in self.call(B)["floor_events"]),
                   events=self.call(B)["floor_events"])
        before = {l: len(self.call(l)["bursts"]) for l in (B, C)}
        r = self.cmd(A, "ptt", SPEECH[A], "2", timeout=30)
        self.check("alice is granted the floor", r.get("granted"), result=r)
        # The server ends a broadcast call when the initiator's transmission ends.
        self.idle_everywhere([A, B, C])
        for l in (B, C):
            history = self.last_released(l) or {}
            got = [b for b in history.get("bursts", [])[before[l]:] if b["direction"] == "received"]
            self.check(f"{l} received alice's broadcast", any(b["frames"] >= 80 for b in got), bursts=[b["frames"] for b in got])
        self.check("the call ended with the transmission", (self.last_released(A) or {}).get("state") == "released")

    def s28_adhoc_criteria(self) -> None:
        # TS 24.379 §17.2.2.1.1 step 12: an ad hoc call by criteria (the users affiliated to the group); §17.2.6.1.1:
        # its participant list modified (client-c removed, then added again) and its criteria changed; §17.2.4.1.1 and
        # §17.2.5.1.1: bob leaves and rejoins by the session identity.
        call = self.cmd(A, "adhoc", "criteria", f"affiliated:{GROUP}")
        self.check("alice's ad hoc call by criteria is established", call["state"] == "established", call=call)
        invite = self.last_sent(A, "INVITE ") or ""
        self.check("the INVITE has the criteria and no resource list",
                   f"<call-participants-criterias>affiliated:{GROUP}</call-participants-criterias>" in invite
                   and "resource-lists+xml" not in invite)
        group = call.get("group") or ""
        for c in (B, C):
            got = self.established(c)
            self.check(f"{c} is called by the criteria", got["type"] == "adhoc" and got["group"] == group, call=got)
        self.talk_and_verify(A, [B, C], seconds=2)
        r = self.cmd(A, "adhoc-modify", "remove", MCPTT[C], ok=False)
        self.check("the removal of client-c is accepted", r.get("ok"), result=r)
        reinvite = self.last_sent(A, "INVITE ") or ""
        self.check("the re-INVITE names client-c with method BYE", f"{MCPTT[C]};method=BYE" in reinvite)
        try:
            self.wait("client-c leaves", lambda: self.call(C) is None, timeout=10)
        except Failed:
            pass
        self.check("client-c is removed from the call", self.call(C) is None)
        r = self.cmd(A, "adhoc-modify", "add", MCPTT[C], ok=False)
        self.check("adding client-c again is accepted", r.get("ok"), result=r)
        try:
            self.established(C)
        except Failed:
            pass
        self.check("client-c is in the call again", (self.call(C) or {}).get("state") == "established")
        r = self.cmd(A, "adhoc-modify", "criteria", f"member:{GROUP}", ok=False)
        self.check("the criteria change gets a final answer", r.get("ok") or "error" in r, result=r)
        reinvite = self.last_sent(A, "INVITE ") or ""
        self.check("the re-INVITE carries the new criteria and no list",
                   f"member:{GROUP}" in reinvite and "resource-lists+xml" not in reinvite.split("\r\n\r\n", 1)[-1])
        self.cmd(B, "adhoc-leave")
        self.wait("bob left", lambda: self.call(B) is None, timeout=10)
        r = self.cmd(B, "rejoin", "adhoc", ok=False, timeout=30)
        self.check("bob rejoins the ad hoc call by its session identity", r.get("ok") and r["result"]["group"] == group, result=r)
        if r.get("ok"):
            self.talk_and_verify(B, [A], seconds=2)
        self.cmd(A, "adhoc-release")
        self.idle_everywhere([A, B, C])

    def s29_floor_priority(self) -> None:
        # TS 24.380 §14.3.3: the floor priority offered (bob 90, alice 10, from the provisioning) and negotiated; §4.1.1.4
        # and the server's policy (bob may pre-empt at 75 or more): bob's request revokes alice, who releases
        # (§6.2.4.5.4) and sees bob take the floor.
        if not self.needs("priority", "S29"):
            return
        call = self.cmd(B, "group", GROUP)
        offer = self.last_sent(B, "INVITE ") or ""
        self.check("bob's offer has mc_priority 90", "mc_priority=90" in offer)
        self.check("bob's negotiated floor priority is 90", call.get("floor_priority") == 90, call=call)
        self.established(A)
        holder = threading.Thread(target=lambda: self.cmd(A, "ptt", SPEECH[A], "6", timeout=40))
        holder.start()
        self.wait("alice holds the floor", lambda: self.call(A).get("floor") == "HAS_PERMISSION", timeout=8)
        time.sleep(1)
        r = self.cmd(B, "ptt", SPEECH[B], "2", timeout=30)
        self.check("bob pre-empts alice and is granted the floor", r.get("granted"), result=r)
        holder.join()
        events = self.call(A)["floor_events"]
        self.check("alice's permission is revoked", any(e.startswith("revoked") for e in events), events=events)
        self.check("alice then sees bob take the floor", any(f"taken by {MCPTT[B]}" in e for e in events), events=events)
        self.hangup(B)
        self.idle_everywhere([A, B, C])
        # A lower priority request does not pre-empt: client-c (10) is denied while alice (10) talks.
        self.cmd(A, "group", GROUP)
        self.established(C)
        holder = threading.Thread(target=lambda: self.cmd(A, "ptt", SPEECH[A], "4", timeout=30))
        holder.start()
        self.wait("alice holds the floor", lambda: self.call(A).get("floor") == "HAS_PERMISSION", timeout=8)
        denied = self.cmd(C, "ptt", SPEECH[C], "1", timeout=30)
        self.check("client-c, of no higher priority, does not pre-empt", not denied.get("granted"), result=denied)
        holder.join()
        self.check("alice was not revoked", not any(e.startswith("revoked") for e in self.call(A)["floor_events"]))
        self.hangup(A)
        self.idle_everywhere([A, B, C])

    def regroups(self, client: str) -> dict:
        return self.cmd(client, "regroups")

    def s30_regroup(self) -> None:
        # TS 24.379 §16.2.1.1: alice regroups north (alice, bob) and south (client-c) with the preconfigured group;
        # §16.2.1.3: every member is told; a call on the regroup names the caller's constituent group (§10.1.1.2.1.1
        # step 14 d)) and reaches both groups; north is regrouped (148); §16.2.1.2 the removal frees it.
        if not self.needs("regroup", "S30"):
            return
        for c in self.devices:
            self.wait(f"{c} ready", lambda c=c: self.status(c)["phase"] == "ready", timeout=90)
        rg = "sip:operations-regroup-a1@mcx.test"
        r = self.cmd(B, "regroup", "create", rg, self.PRECONF, f"{self.NORTH},{self.SOUTH}")
        self.check("bob may not regroup (403, warning 160)", r["status"] == 403 and "160" in (r["warning"] or ""), result=r)
        r = self.cmd(A, "regroup", "create", rg, self.PRECONF, f"{self.NORTH},{self.SOUTH}")
        self.check("alice's regroup is created", r["status"] == 200, result=r)
        message = self.last_sent(A, "MESSAGE ") or ""
        self.check("the request carries the regroup document", "application/vnd.3gpp.mcptt-regroup+xml" in message
                   and "<regroup-action>create</regroup-action>" in message and self.SOUTH in message)
        for c in (A, B, C):
            try:
                self.wait(f"{c} told of the regroup", lambda c=c: rg in self.regroups(c)["regroups"], timeout=15)
            except Failed:
                pass
            got = self.regroups(c)["regroups"].get(rg) or {}
            self.check(f"{c} is told of the regroup with both groups", set(got.get("groups", [])) == {self.NORTH, self.SOUTH}, got=got)
        refused = self.cmd(A, "group", self.NORTH, ok=False)
        self.check("a call on north, now regrouped, is refused (148)", not refused.get("ok") and "148" in refused.get("error", ""), result=refused)
        self.idle_everywhere([A, B, C])
        call = self.cmd(A, "group", rg)
        self.check("alice's call on the regroup is established", call["state"] == "established", call=call)
        invite = self.last_sent(A, "INVITE ") or ""
        self.check("the INVITE names alice's constituent group (associated-group-id)",
                   f"<associated-group-id type=\"Normal\"><mcpttURI>{self.NORTH}</mcpttURI>" in invite)
        for c in (B, C):
            got = self.established(c)
            self.check(f"{c} is in the call on the regroup", got["group"] == rg, call=got)
        self.talk_and_verify(A, [B, C], seconds=2)
        self.talk_and_verify(C, [A, B], seconds=2)
        self.hangup(A)
        self.idle_everywhere([A, B, C])
        r = self.cmd(A, "regroup", "remove", rg)
        self.check("alice removes the regroup", r["status"] == 200, result=r)
        for c in (A, B, C):
            try:
                self.wait(f"{c} told of the removal", lambda c=c: rg not in self.regroups(c)["regroups"], timeout=15)
            except Failed:
                pass
            self.check(f"{c} forgets the regroup", rg not in self.regroups(c)["regroups"])
        call = self.cmd(A, "group", self.NORTH)
        self.check("north carries a call again", call["state"] == "established", call=call)
        self.established(B)
        self.hangup(A)
        self.idle_everywhere([A, B, C])

    def s31_temporary_group(self) -> None:
        # TS 24.481 §6.3.14.2: alice forms a temporary group of north and south at the GMS; the servers notify the
        # members; a group call on it reaches both groups; §6.3.15.2 the tear down.
        if not self.needs("regroup", "S31"):
            return
        for c in self.devices:
            self.wait(f"{c} ready", lambda c=c: self.status(c)["phase"] == "ready", timeout=90)
        tg = "sip:operations-temp-a1@mcx.test"
        doc = "temp-a1"
        r = self.cmd(B, "temporary-create", doc, tg, f"{self.NORTH},{self.SOUTH}")
        self.check("bob may not form a temporary group (403)", r["status"] == 403, result=r)
        r = self.cmd(A, "temporary-create", doc, tg, f"{self.NORTH},{self.SOUTH}")
        self.check("alice's temporary group is formed (200)", r["status"] == 200, result=r)
        for c in (A, B, C):
            try:
                self.wait(f"{c} told of the temporary group", lambda c=c: tg in self.regroups(c)["regroups"], timeout=20)
            except Failed:
                pass
            got = self.regroups(c)["regroups"].get(tg) or {}
            self.check(f"{c} is told of the temporary group", got.get("temporary") is True, got=got)
        d = self.cmd(A, "group-document", tg)
        self.check("the temporary group's document lists both constituents",
                   d and set(d["temporary_constituents"]) == {self.NORTH, self.SOUTH}, doc=d)
        call = self.cmd(A, "group", tg)
        self.check("alice's call on the temporary group is established", call["state"] == "established", call=call)
        for c in (B, C):
            got = self.established(c)
            self.check(f"{c} is in the call on the temporary group", got["group"] == tg, call=got)
        self.talk_and_verify(A, [B, C], seconds=2)
        self.hangup(A)
        self.idle_everywhere([A, B, C])
        r = self.cmd(A, "temporary-delete", doc)
        self.check("alice tears the temporary group down (200)", r["status"] == 200, result=r)
        for c in (A, B, C):
            try:
                self.wait(f"{c} told of the tear down", lambda c=c: tg not in self.regroups(c)["regroups"], timeout=20)
            except Failed:
                pass
            self.check(f"{c} forgets the temporary group", tg not in self.regroups(c)["regroups"])

    # MCVideo (TS 24.281, TS 24.581) on redroid: no camera there, so these check the signalling, transmission and
    # reception control, not pictures (tests/video.py checks the pictures on phones).

    def video(self, client: str) -> dict | None:
        return self.cmd(client, "video-status").get("active")

    def video_events(self, client: str) -> list:
        return (self.video(client) or {}).get("events") or []

    def video_idle(self, clients) -> None:
        for c in clients:
            self.wait(f"{c} has no video call", lambda c=c: self.video(c) is None, timeout=15)

    def video_hangup(self, client: str) -> None:
        if self.video(client):
            self.cmd(client, "video-hangup", ok=False)

    def v1_transmission_control(self) -> None:
        """TS 24.581 §6.2.4 and §6.2.5 with §11: the transmission ends by Transmission end request and its response
        (§6.2.4.5.3, §6.2.4.6.4), never Transmission Release; no counter reaches its limit; a receiver stops receiving
        (§6.2.5.5.3, T104); the transmission and reception priorities offered from the MCVideo user profile, negotiated
        and requested (§14.2.3, §14.3.3, §6.2.4.3.2; a lab made with --mcvideo-priority client-a=7:4); an implicit request
        with the group call setup (TS 24.281 §6.4) granted without a Transmission Request. On a lab made with --protected
        --xml-protection --integrity-protection, the MCVideo signalling is XML protected (TS 33.180 §9.3) with nothing refused."""
        m = meta(self.name)
        keys = {c: self.cmd(c, "keys")["xml_protection"] for c in (A, B)}
        xml = all((k["services"].get("mcvideo") or {}).get("confidentiality") and (k["services"].get("mcvideo") or {}).get("integrity")
                  for k in keys.values())
        if xml:
            self.check("MCVideo XML confidentiality and integrity required by the service configuration, both with an MCVideo CSK",
                       all(k["services"]["mcvideo"]["csk_id"] for k in keys.values()))
        call = self.cmd(A, "video-call", "sip:bob.mcvideo@mcx.test", timeout=30)
        self.check("a private video call with transmission control is established", call["state"] == "established", call=call)
        self.wait("bob's call is up", lambda: (v := self.video(B)) and v["state"] == "established")
        r = self.cmd(A, "video-transmit", "2", timeout=30)
        self.check("alice is granted the transmission", r.get("granted") is True, result=r)
        self.wait("alice's transmission ends with the end response", lambda: "transmission ended" in self.video_events(A))
        ev = self.video_events(B)
        self.check("bob is told alice transmits and receives it", "taken by sip:alice.mcvideo@mcx.test" in ev and "receiving" in ev, events=ev)
        a = self.video(A)
        self.check("alice's end request is answered (T101 stopped, no timeout), back to no permission",
                   a["transmission"] == "NO_PERMISSION" and not a.get("timeouts"), call=a)
        # Reception end while alice transmits.
        done = {}
        t = threading.Thread(target=lambda: done.update(r=self.cmd(A, "video-transmit", "4", timeout=40)))
        t.start()
        self.wait("bob receives alice's second transmission", lambda: (v := self.video(B)) and v["reception"] == "HAS_PERMISSION" or
                  (v and v["receiving"]))
        r = self.cmd(B, "video-reception-end")
        self.check("bob sends a Media reception end request", r["sent"] is True)
        self.wait("bob's reception ends with the response (T104 stopped)", lambda: (v := self.video(B)) and v["reception"] == "TERMINATED")
        self.check("bob no longer receives, without a timeout", not self.video(B).get("timeouts"))
        t.join()
        self.cmd(A, "video-hangup")
        self.video_idle([A, B])
        # Implicit transmission request with the group call setup.
        call = self.cmd(A, "video-group", "implicit", timeout=30)
        got = self.wait("alice is granted without pressing Transmit", lambda: (v := self.video(A)) and v["transmission"] == "HAS_PERMISSION" and v)
        self.check("an implicit request in the group call's offer is granted (T100 of the implicit request stopped)",
                   not got.get("timeouts") and "granted" in " ".join(got["events"]), events=got["events"])
        if m.get("mcvideo_priority"):
            self.check("alice offered her profile priority 7 and the server capped it: the request carries the negotiated 4",
                       got.get("negotiated_priority") == 4 and got.get("request_priority") == 4,
                       negotiated=got.get("negotiated_priority"), request=got.get("request_priority"))
        self.cmd(A, "video-release")
        self.wait("the implicit transmission ends with the end response", lambda: "transmission ended" in self.video_events(A))
        self.check("the implicitly granted transmission ends", True)
        self.cmd(A, "video-hangup")
        self.video_idle([A, B])
        if xml:
            after = {c: self.cmd(c, "keys")["xml_protection"] for c in (A, B)}
            self.check("the calls' MCVideo signalling was encrypted, signed, verified and decrypted, nothing refused",
                       all(after[c]["encrypted"] > keys[c]["encrypted"] and after[c]["decrypted"] > keys[c]["decrypted"] and
                           after[c]["verified"] > keys[c]["verified"] and after[c]["refused"] == 0 and after[c]["not_sent"] == 0 for c in (A, B)),
                       before=keys, after=after)

    def v2_adhoc(self) -> None:
        """TS 24.281 §22: an ad hoc group video call to listed users (§22.2.2.1.1 step 11), the server naming the ad hoc
        group; bob leaves (§22.2.4.1.1) and re-joins at the session identity (§22.2.5.1.1); alice releases it for
        everyone (§22.2.3.1.1, Reason "User requested release"). Then emergency and imminent peril ad hoc group calls
        (§6.2.8.1.19, §6.2.8.1.23, <adhoc-emergency-ind>, <imminentperil-ind>) that bob receives as such. client-c, when the lab has the reference client, is the third participant."""
        # A third participant (client-c: the reference client or the redroid one), so that the call outlives bob's
        # leaving (TS 24.281 §6.3.8.1 item 2: a group call with one participant left is released).
        third = bool(meta(self.name).get("reference_clients")) or C in self.devices
        others = ["sip:bob.mcvideo@mcx.test"] + (["sip:client-c.mcvideo@mcx.test"] if third else [])
        android = [c for c in (A, B, C) if c in self.devices]
        call = self.cmd(A, "video-adhoc", *others, timeout=30)
        group = call["group"] or ""
        self.check("alice's ad hoc group video call is up, and the server names its ad hoc group",
                   call["state"] == "established" and call["session_type"] == "adhoc" and group.startswith("sip:"), call=call)
        b = self.wait("bob is in the ad hoc call", lambda: (v := self.video(B)) and v["state"] == "established" and v)
        self.check("bob's call is the same ad hoc group", b["session_type"] == "adhoc" and b["group"] == group, group=b["group"])
        self.wait("alice lists every participant", lambda: len((self.video(A) or {}).get("participants") or {}) == len(others) + 1)
        r = self.cmd(A, "video-transmit", "2", timeout=30)
        self.check("alice transmits in the ad hoc call (transmission control in the clear)", r.get("granted") is True, result=r)
        self.cmd(B, "video-hangup")
        rejoin = self.cmd(B, "video-rejoinable")
        self.check("bob leaves; the ad hoc session can be re-joined", rejoin["type"] == "adhoc" and rejoin["group"] == group, rejoin=rejoin)
        self.wait("alice sees bob leave", lambda: (self.video(A) or {}).get("participants", {}).get("sip:bob.mcvideo@mcx.test") != "connected")
        again = self.cmd(B, "video-rejoin", timeout=30)
        self.check("bob re-joins the ad hoc session at its identity", again["state"] == "established" and again["group"] == group, call=again)
        self.wait("alice sees bob back", lambda: (self.video(A) or {}).get("participants", {}).get("sip:bob.mcvideo@mcx.test") == "connected")
        self.cmd(A, "video-adhoc-release")
        self.video_idle([A])
        released = self.cmd(A, "video-status")["history"][-1]
        self.check("alice releases the ad hoc call (BYE with Reason \"User requested release\")",
                   released["reason"] == "released for everyone", reason=released["reason"])
        # TS 24.281 clause 6.3.8.1 item 3 limits the release on the initiator's leaving to pre-arranged calls: the OpenMCX
        # MCVideo server lets an ad hoc call go on without its initiator, whatever the Reason.
        time.sleep(2)
        self.check("the MCVideo server keeps the ad hoc call for the others (its policy, clause 6.3.8.1 item 3)",
                   (self.video(B) or {}).get("state") == "established")
        self.video_hangup(B)
        self.video_idle(android)
        if not meta(self.name).get("priority"):
            return
        for level in ("emergency", "imminent"):
            call = self.cmd(A, "video-adhoc", *others, level, timeout=30)
            self.check(f"an {level} ad hoc group video call is up as one", call["state"] == "established" and call["priority"] == level, call=call)
            self.wait(f"bob's ad hoc call shows {level}", lambda level=level: (v := self.video(B)) and v["priority"] == level)
            self.check(f"bob receives the {level} ad hoc group call as such", True)
            # The OpenMCX server's ad hoc re-INVITE changes the participants only (adhoc_modify), not the priority:
            # the emergency ends with the call (TS 24.281 clause 6.2.8.1.25).
            self.cmd(A, "video-adhoc-release")
            self.video_idle([A])
            self.video_hangup(B)
            self.video_idle(android)

    def v3_priority_extras(self) -> None:
        """On a lab made with --priority: an authorised user cancels another user's emergency alert with the emergency
        in a call (TS 24.281 §6.2.8.1.14 step 3: <alert-ind> "false", <originated-by>); a group's imminent peril state
        cancelled without a call (§9.2.5.2.1, MESSAGE with <imminentperil-ind> "false")."""
        if not meta(self.name).get("priority"):
            print("  [SKIP] needs a lab made with --priority"); return
        self.cmd(B, "video-alert", "on", "48.85", "2.35")
        self.wait("alice receives bob's video alert", lambda: "sip:bob.mcvideo@mcx.test" in self.cmd(A, "video-alerts"))
        self.cmd(A, "video-group", "emergency", timeout=30)
        self.wait("bob's call shows the emergency", lambda: (v := self.video(B)) and v["priority"] == "emergency")
        self.cmd(A, "video-emergency", "off", "alert=sip:bob.mcvideo@mcx.test")
        self.check("alice cancels the emergency and bob's alert with it", self.video(A)["priority"] == "normal" and
                   "sip:bob.mcvideo@mcx.test" not in self.cmd(A, "video-alerts"))
        self.wait("bob's own alert is cancelled", lambda: not self.cmd(B, "video-status").get("alerting"))
        self.check("bob is told his alert was cancelled", True)
        self.cmd(A, "video-hangup")
        self.video_idle([A, B])
        self.cmd(A, "video-group", "imminent", timeout=30)
        self.wait("bob's call shows imminent peril", lambda: (v := self.video(B)) and v["priority"] == "imminent")
        self.cmd(A, "video-hangup")
        self.video_idle([A, B])
        r = self.cmd(A, "video-imminent-cancel", ok=False)
        self.check("alice cancels the group's imminent peril without a call (MVIG 1 on the 2xx)",
                   r.get("ok") and r["result"]["state"] == "MVIG 1: no-imminent-peril", reply=r)

    def v4_settings_and_gdd(self) -> None:
        """TS 24.281 §7.2.3: the MCVideo settings-only PUBLISH, then its modification (SIP-If-Match); §8.2.1.6: the group
        dynamic data subscription of the group: its members and their clients, the call ongoing during a group call (a
        re-subscription brings the full state), and the de-subscription."""
        self.check("alice publishes her MCVideo service settings", self.cmd(A, "video-settings-publish")["status"] == 200)
        self.check("and publishes them again, as a modification", self.cmd(A, "video-settings-publish")["status"] == 200)
        self.check("alice subscribes to the group's dynamic data", self.cmd(A, "video-gdd-subscribe", GROUP)["status"] == 200)
        gdd = lambda: self.cmd(A, "video-gdd", GROUP).get(GROUP)
        got = self.wait("the group's dynamic data", lambda: (d := gdd()) and d)
        ids = {m["id"] for m in got["members"]}
        self.check("it lists alice and bob with their clients", {"sip:alice.mcvideo@mcx.test", "sip:bob.mcvideo@mcx.test"} <= ids and
                   all(m["clients"] for m in got["members"]), members=got["members"])
        self.cmd(B, "video-group", timeout=30)
        self.wait("alice's call is up", lambda: (v := self.video(A)) and v["state"] == "established")
        self.cmd(A, "video-gdd-resubscribe", GROUP)
        self.wait("the group's call is ongoing", lambda: (d := gdd()) and d.get("call_ongoing") is True, timeout=10)
        self.check("during bob's group call the dynamic data says a call is ongoing", True)
        self.cmd(B, "video-hangup")
        self.video_idle([A, B])
        self.check("alice ends her subscription", self.cmd(A, "video-gdd-unsubscribe", GROUP)["status"] == 200)

    def v5_kms(self) -> None:
        """On a --protected lab: a private video call's PCK keyed to the KMS of the called user from the MCVideo user
        profile's <PrivateCallKMSURI> (TS 24.281 §6.2.8.3.9 1) a)); a PCK naming another receiver KMS is answered 488
        with a KMS Redirect Response, and the call is keyed again to the receiver KMS it lists (TS 33.180 §5.2.8.2.5)."""
        if not self.protected:
            print("  [SKIP] needs a lab made with --protected"); return
        call = self.cmd(A, "video-call", "sip:bob.mcvideo@mcx.test", timeout=30)
        self.check("the PCK is keyed to bob's KMS from alice's user profile", call["state"] == "established" and call.get("protected") and
                   call.get("kms_source") == "user-profile" and call.get("kms_uri"), call={k: call.get(k) for k in ("state", "kms_uri", "kms_source")})
        self.cmd(A, "video-hangup")
        self.video_idle([A, B])
        self.cmd(A, "video-pck-kms", "https://kms-elsewhere.mcx.test")
        call = self.cmd(A, "video-call", "sip:bob.mcvideo@mcx.test", timeout=30)
        krr = call.get("krr") or {}
        self.check("a PCK for another KMS gets a KMS Redirect Response naming bob's KMS", krr.get("receiver_kms") == "https://kms-elsewhere.mcx.test"
                   and krr.get("receiver_list") == [call.get("kms_uri")], krr=krr)
        self.check("the call is keyed again to that KMS and set up", call["state"] == "established" and call.get("kms_source") == "kms-redirect")
        self.wait("bob's protected call is up", lambda: (v := self.video(B)) and v["state"] == "established" and v.get("pck_id") == call.get("pck_id"))
        self.check("bob holds the PCK of the redirected call", True)
        self.cmd(A, "video-hangup")
        self.video_idle([A, B])

    def v6_affiliation(self) -> None:
        """TS 24.281 §8.2.1.2: de-affiliation from one MCVideo group (video-deaffiliate <group>; the last group: Expires 0
        and no PIDF, step 4) and affiliation again; §8.2.1.3: this user's own MCVideo affiliation subscribed to; §8.2.1.4:
        a negotiated-mode request (MESSAGE with the MCVideo affiliation command) to bob, who accepts it for the group
        named (§8.2.1.5, video-affiliation-accept <group>) when the server passes it on."""
        alice, bob = "sip:alice.mcvideo@mcx.test", "sip:bob.mcvideo@mcx.test"
        video_groups = lambda c: self.status(c)["mcvideo"]["affiliations"]
        self.check("alice is affiliated to the MCVideo group", video_groups(A).get(GROUP) == "affiliated", groups=video_groups(A))
        r = self.cmd(A, "video-deaffiliate", GROUP, timeout=30)
        self.check("alice de-affiliates from that one group", r["affiliations"].get(GROUP) != "affiliated" and GROUP not in r["wanted_groups"], result=r)
        publish = self.last_sent(A, "PUBLISH sip:mcvideo", "Event: presence") or ""
        self.check("the PUBLISH has Expires 0 and names no group (§8.2.1.2 step 4)", "Expires: 0" in publish and f'group="{GROUP}"' not in publish)
        r = self.cmd(A, "video-deaffiliate", GROUP, ok=False)
        self.check("de-affiliating again is refused: not affiliated", not r.get("ok") and "not affiliated" in (r.get("error") or ""), reply=r)
        r = self.cmd(A, "video-affiliate", GROUP, timeout=30)
        self.check("alice affiliates to the group again", r["affiliations"].get(GROUP) == "affiliated", result=r)
        publish = self.last_sent(A, "PUBLISH sip:mcvideo", "Event: presence") or ""
        # On a protected lab the trace holds the bodies as sent, encrypted: their content is checked unprotected only.
        self.check("the PUBLISH carries the MCVideo PIDF" + ("" if self.protected else " naming the group"),
                   "Expires: 4294967295" in publish and "application/pidf+xml" in publish and
                   (self.protected or (GROUP in publish and "urn:3gpp:ns:mcvideoPresInfo" in publish)))
        r = self.cmd(A, "video-affiliation-subscribe", alice)
        self.check("alice's subscription to her own MCVideo affiliation is accepted", r["status"] == 200, result=r)
        groups = lambda: [g for v in (self.cmd(A, "video-procedures")["target_affiliations"].get(alice) or {}).get("clients", {}).values()
                          for g, st in v.items() if st == "affiliated"]
        try:
            self.wait("alice's own MCVideo affiliation notified", lambda: GROUP in groups(), timeout=15)
            ok = True
        except Failed:
            ok = False
        self.check("the notification lists alice's client affiliated to the group", ok, target=self.cmd(A, "video-procedures")["target_affiliations"])
        self.cmd(A, "video-affiliation-unsubscribe", alice)
        # The server notifies one affiliation subscription per user (see S23): the client's own takes it back.
        self.cmd(A, "refresh", "video-subscription")
        r = self.cmd(A, "video-affiliate-remote", bob, GROUP, "deaffiliate", "negotiated")
        self.check("the negotiated-mode MESSAGE gets a final answer", r["status"] >= 200, result=r)
        message = self.last_sent(A, "MESSAGE sip:mcvideo") or ""
        self.check("the MESSAGE carries the MCVideo affiliation command" + ("" if self.protected else " naming bob"),
                   "application/vnd.3gpp.mcvideo-affiliation-command+xml" in message and
                   (self.protected or ("<de-affiliate><group>" in message and bob in message)), message=message[-1200:])
        time.sleep(2)
        pending = self.cmd(B, "video-procedures")["affiliation_command"]
        if pending is None:
            self.check("bob got no request (the server does not relay it)", True)
        else:
            r = self.cmd(B, "video-affiliation-accept", GROUP, timeout=30)
            self.check("bob accepts it for the group and de-affiliates", r["last_affiliation_answer"] == "accepted" and
                       r["affiliations"].get(GROUP) != "affiliated", result=r)
            self.cmd(B, "video-affiliate", GROUP, timeout=30)
        r = self.cmd(B, "video-affiliation-accept", ok=False)
        self.check("with no request pending, video-affiliation-accept is refused", not r.get("ok"), reply=r)
        self.check("alice and bob are affiliated to the MCVideo group at the end",
                   video_groups(A).get(GROUP) == "affiliated" and video_groups(B).get(GROUP) == "affiliated")

    # Phase 6: location rules, group geographic areas and SEAL location reporting, on a lab made with
    # --location --functional-alias --location-rules (androidlab.location_rules: the areas below).
    HOME = ("48.0", "2.0")              # where every client starts (the fixture's positions)
    ALIAS_AREA = ("48.03", "1.97")      # RULES_ALIAS_AREA: incident-commander activated inside
    RULE_AREA = ("48.03", "2.03")       # RULES_AFFILIATION_AREA: alice's group left inside, affiliated on leaving
    OUTSIDE = ("48.2", "2.0")           # outside the group's mandatory area RULES_MANDATORY_AREA
    GROUP_AREA = ("48.02", "2.02")      # the server's group geographic area (6.3.2.4 notices)
    HAZARD = ("48.05", "2.05")          # the server's emergency alert area

    def rules(self, client: str = A) -> dict:
        return self.cmd(client, "location-rules")

    def move(self, client: str, where) -> None:
        self.cmd(client, "location-position", *where)

    def rules_home(self) -> dict:
        """alice at home with her rules read (the first position only places her: no area is crossed)."""
        r = self.wait("alice's location rules read", lambda: (x := self.rules(A)) and x["mcptt"]["loaded"] and x["mcdata"]["loaded"] and x, timeout=30)
        self.move(A, self.HOME)
        time.sleep(2)
        return r

    def affiliated(self, client: str, service: str) -> bool:
        return self.status(client)[service]["affiliations"].get(GROUP) == "affiliated"

    def s40_location_aliases(self) -> None:
        """TS 24.379 9A.2.1.4 and TS 24.282 22.2.1.4: entering the area of incident-commander's
        <LocationCriteriaForActivation> activates it on MCPTT and MCData (9A.2.1.2 / 22.2.1.2), leaving the area of its
        <LocationCriteriaForDeactivation> deactivates it; meanwhile the profile's lock refuses the user's deactivation."""
        r = self.rules_home()
        ic = next((a for a in r["mcptt"]["functional_aliases"] if a.startswith("sip:incident-commander.")), None)
        icd = next((a for a in r["mcdata"]["functional_aliases"] if a.startswith("sip:incident-commander.")), None)
        if not self.check("alice's MCPTT and MCData profiles give incident-commander location criteria", ic is not None and icd is not None, rules=r):
            return
        self.release_aliases([A])
        try:
            self.move(A, self.ALIAS_AREA)
            got = self.wait("incident-commander active on MCPTT and MCData", lambda: (x := self.rules(A)) and ic in x["mcptt"]["active_aliases"] and icd in x["mcdata"]["active_aliases"] and x, timeout=20)
            self.check("entering the area activates incident-commander on MCPTT and MCData", True)
            self.check("the app says it was activated by location", ic in got["mcptt"]["aliases_by_location"] and icd in got["mcdata"]["aliases_by_location"])
            refused = self.cmd(A, "fa", "deactivate", ic, ok=False)
            self.check("the user's deactivation is refused while the location criterion holds it (9A.2.1.2)", not refused["ok"] and "not allowed" in refused["error"], reply=refused)
            self.move(A, self.HOME)
            self.wait("incident-commander inactive", lambda: (x := self.rules(A)) and ic not in x["mcptt"]["active_aliases"] and icd not in x["mcdata"]["active_aliases"], timeout=20)
            self.check("leaving the area deactivates it on both services", True)
        finally:
            self.move(A, self.HOME)

    def s41_rules_affiliation(self) -> None:
        """TS 24.379 9.2.1.7.2 and TS 24.282 8.2.6.2: alice's <RulesForDeaffiliation> (entering the rule area) leaves her
        group on MCPTT and MCData (9.2.1.2 / 8.2.2), her <RulesForAffiliation> (leaving it) affiliates again; while that
        rule holds the group, the profile's <manual-deaffiliation-not-allowed-if-affiliation-rules-are-met> refuses
        her de-affiliation."""
        r = self.rules_home()
        self.check("alice's profiles carry the affiliation rules", r["mcptt"]["affiliation_rules"] == 2 and r["mcdata"]["affiliation_rules"] == 2
                   and GROUP in r["mcptt"]["locked"], rules=r)
        try:
            self.move(A, self.RULE_AREA)
            self.wait("alice leaves the group on both services", lambda: not self.affiliated(A, "mcptt") and not self.affiliated(A, "mcdata"), timeout=30)
            self.check("entering the area de-affiliates alice from the group (MCPTT and MCData)", True)
            # Not affiliated, so a group call of bob's does not reach her.
            self.cmd(B, "group", GROUP, timeout=30)
            self.established(B)
            time.sleep(3)
            self.check("bob's group call does not reach alice meanwhile", self.call(A) is None)
            self.hangup(B)
            self.idle_everywhere([A, B])
            self.move(A, self.HOME)
            self.wait("alice affiliated again on both services", lambda: self.affiliated(A, "mcptt") and self.affiliated(A, "mcdata"), timeout=30)
            got = self.rules(A)
            self.check("leaving the area affiliates her again, by the rule", GROUP in got["mcptt"]["rule_affiliated"], rules=got["mcptt"])
            refused = self.cmd(A, "deaffiliate", GROUP, ok=False)
            self.check("her de-affiliation is refused while the rule holds the group (9.2.1.2)", not refused["ok"] and "not allowed" in refused["error"], reply=refused)
            self.check("and she stays affiliated", self.affiliated(A, "mcptt"))
        finally:
            self.move(A, self.HOME)
            self.wait("alice affiliated", lambda: self.affiliated(A, "mcptt") and self.affiliated(A, "mcdata"), timeout=30)

    def s42_group_mandatory_area(self) -> None:
        """TS 24.379 9.2.1.7.3: outside the group's <mandatory-geographic-area> alice de-affiliates; there an affiliation
        is refused; back inside she affiliates again."""
        r = self.rules_home()
        self.check("the app read the group's geographic area", GROUP in r["mcptt"]["group_areas"], rules=r["mcptt"])
        try:
            self.move(A, self.OUTSIDE)
            self.wait("alice leaves the group", lambda: not self.affiliated(A, "mcptt"), timeout=30)
            got = self.rules(A)
            self.check("outside the mandatory area alice de-affiliates", GROUP in got["mcptt"]["area_left"], rules=got["mcptt"])
            refused = self.cmd(A, "affiliate", GROUP, ok=False)
            self.check("an affiliation outside the area is refused", not refused["ok"] and "outside" in refused["error"], reply=refused)
            self.move(A, self.HOME)
            self.wait("alice affiliated again", lambda: self.affiliated(A, "mcptt"), timeout=30)
            self.check("back inside she affiliates again", GROUP not in self.rules(A)["mcptt"]["area_left"])
        finally:
            self.move(A, self.HOME)
            self.wait("alice affiliated", lambda: self.affiliated(A, "mcptt"), timeout=30)

    def s43_group_geographic_area(self) -> None:
        """TS 24.379 12.1.1.4 and TS 24.282 16.2.1.4: the server's notice of leaving the group geographic area
        (<group-geo-area-ind> false, <associated-group-id>) de-affiliates alice from that group, on MCPTT and MCData;
        the notice of entering it affiliates her again."""
        self.rules_home()
        since = lambda t: [n for n in self.alerts(A)["notices"] if not n["alert_area"] and n["at"] >= t]
        try:
            t0 = time.time() * 1000
            self.move(A, self.GROUP_AREA)
            got = self.wait("the notices of entering the group area", lambda: (x := since(t0)) and {n["service"] for n in x if n["entered"]} >= {"mcptt", "mcdata"} and x, timeout=30)
            self.check("alice is told she entered the group's area, on MCPTT and MCData, naming the group",
                       all(n["group"] for n in got), notices=got)
            self.check("she stays affiliated", self.affiliated(A, "mcptt") and self.affiliated(A, "mcdata"))
            t1 = time.time() * 1000
            self.move(A, self.HOME)
            got = self.wait("the notices of leaving it", lambda: (x := since(t1)) and {n["service"] for n in x if not n["entered"]} >= {"mcptt", "mcdata"} and x, timeout=30)
            self.check("the notices of leaving name the group", all(n["group"] == GROUP for n in got if not n["entered"]), notices=got)
            self.wait("alice de-affiliated on both services", lambda: not self.affiliated(A, "mcptt") and not self.affiliated(A, "mcdata"), timeout=30)
            self.check("leaving the area de-affiliates her from the group (MCPTT and MCData)", True)
            self.move(A, self.GROUP_AREA)
            self.wait("alice affiliated again on both services", lambda: self.affiliated(A, "mcptt") and self.affiliated(A, "mcdata"), timeout=30)
            self.check("entering it again affiliates her again", True)
        finally:
            # Back home the notice of leaving de-affiliates her again: she affiliates by hand (9.2.1.2, 8.2.2).
            self.move(A, self.HOME)
            time.sleep(6)
            for service in ("mcptt", "mcdata"):
                if not self.affiliated(A, service):
                    self.cmd(A, "affiliate", GROUP, service, ok=False)
            self.wait("alice affiliated", lambda: self.affiliated(A, "mcptt") and self.affiliated(A, "mcdata"), timeout=30)

    def s44_emergency_alert_area(self) -> None:
        """TS 24.379 12.1.1.6 (TS 37.579-2 6.6.4): on the server's notice of entering an emergency alert area the phone
        raises the alert itself, with no user request; the notice of leaving does not cancel it."""
        self.rules_home()
        t0 = time.time() * 1000
        try:
            self.move(A, self.HAZARD)
            notice = self.wait("alice's notice", lambda: next((n for n in self.alerts(A)["notices"] if n["at"] >= t0 and n["alert_area"] and n["entered"] and n["service"] == "mcptt"), None), timeout=30)
            self.check("the MCPTT notice of entering the emergency alert area", notice is not None)
            own = self.wait("the automatic alert", lambda: (x := self.alerts(A)["own"]) and "mcptt" in x and x, timeout=15)
            self.check("alice's alert is raised automatically on MCPTT, on the selected group (12.1.1.6 1)a)ii))", own["mcptt"] == [GROUP], own=own)
            got = self.wait("bob receives it", lambda: self.alert_from(B, "alice"))
            self.check("bob receives the automatic alert with alice's position", got is not None and abs(got["lat"] - 48.05) < 0.001)
            t1 = time.time() * 1000
            self.move(A, self.HOME)
            self.wait("the notice of leaving", lambda: next((n for n in self.alerts(A)["notices"] if n["at"] >= t1 and n["alert_area"] and not n["entered"]), None), timeout=30)
            self.check("leaving the area leaves the alert up (12.1.1.6 NOTE)", "mcptt" in self.alerts(A)["own"])
        finally:
            self.cmd(A, "alert", "off", ok=False)
            self.move(A, self.HOME)

    def s45_seal_location(self) -> None:
        """TS 24.545 6.2.2.2.1, 6.2.2.2.2 and 6.2.20.2: the configuration from the location management server, periodic
        reports of the position, positions stored while offline and sent back in a history location result report."""
        self.rules_home()
        r = self.cmd(A, "seal", "start")
        self.check("the SEAL configuration is read: a periodic trigger and a report URI", r["started"] and r["periodic"] > 0 and r["report_uri"], seal=r)
        try:
            got = self.wait("two periodic reports accepted", lambda: (x := self.cmd(A, "seal", "status"))["reports_sent"] >= 2 and x, timeout=30)
            self.check("the periodic reports are accepted (200)", got["last_status"] == 200, seal=got)
            self.cmd(A, "seal", "offline")
            got = self.wait("positions stored offline", lambda: (x := self.cmd(A, "seal", "status"))["stored"] >= 2 and x, timeout=30)
            self.check("offline, the positions are stored", got["stored"] >= 2, seal=got)
            r = self.cmd(A, "seal", "online")
            self.check("back online, they go in a history location result report, accepted", r["history_sent"] >= 2 and r["stored"] == 0 and r["last_status"] == 200, seal=r)
        finally:
            self.cmd(A, "seal", "stop", ok=False)

    # The MCData media plane (MSRP; docs/CONFORMANCE.md "MCData media plane"). The phone is the active endpoint: it
    # connects to the MCData server's stream. The a1-style fixture's MCData policy (prepare-prearranged-test.py --mcdata):
    # alice may send one-to-one up to 300 bytes, bob without a limit; bob receives from alice only; the group takes 400
    # byte SDS and 2000 byte files, client-c may not transmit on it; bob may release any user's communication.

    def media(self, client: str, kind: str | None = None, outgoing: bool | None = None) -> list[dict]:
        return [c for c in self.status(client)["media"] if (kind is None or c["kind"] == kind)
                and (outgoing is None or c["outgoing"] == outgoing)]

    def last_media(self, client: str, kind: str, outgoing: bool, what: str, predicate=lambda c: True, timeout: float = 15) -> dict:
        return self.wait(what, lambda: next((c for c in reversed(self.media(client, kind, outgoing)) if predicate(c)), None), timeout=timeout)

    def notification_sent(self, client: str, message_id: str, kind: str) -> None:
        """The client sent its notification by SIP MESSAGE; the server's answer (403 216 for a media-plane SDS) is noted."""
        line = self.wait(f"{client} sends {kind} for {message_id}", lambda: next((e for e in self.cmd(client, "events", "200")
                         if f"sent {kind} for {message_id}" in e), None), timeout=20)
        answer = line.rsplit(": ", 1)[-1]
        self.check(f"{client} sends the {kind} notification by MESSAGE (the server answers {answer})", answer in ("200", "403"), event=line)

    def s60_long_sds(self) -> None:
        stamp = time.strftime("%H%M%S")
        # TS 24.282 §9.2.1.1: above the signalling-plane limit (1000 octets) the SDS goes over the media plane (§9.2.3).
        text = f"{stamp} " + "media plane " * 250
        sent = self.cmd(B, "sds", MCDATA[A], text, "--disposition", "delivery", timeout=60)
        out = self.status(B)["outbox"]
        self.check("bob's long SDS went over the media plane", any(m["message_id"] == sent["message_id"] and m["plane"] == "media" for m in out))
        sds = self.last_media(B, "sds", True, "bob's media communication")
        self.check("bob's standalone SDS was delivered and released", sds["state"] == "delivered", communication=sds["state"])
        got = self.inbox_message(A, text)
        self.check("alice receives it whole over the media plane", got["plane"] == "media" and got["length"] == len(text), plane=got["plane"], length=got["length"])
        self.check("alice sees bob as the sender", got["sender"] == MCDATA[B])
        self.check("alice's copy has bob's message ID", got["message_id"] == sent["message_id"])
        # §9.2.1.3: the DELIVERED notification goes by SIP MESSAGE (the standalone session is over). The MCData server
        # correlates notifications with the SDS it relayed on the signalling plane only (dispatched_, TS 24.282 §12.2.3
        # step 5), so it refuses this one with 403 warning 216: a server gap, checked here as the client's part.
        self.notification_sent(A, sent["message_id"], "DELIVERED")
        a = self.last_media(A, "sds", False, "alice's terminating leg released", lambda c: c["state"] == "released")
        self.check("alice's terminating leg is released by the server", a["state"] == "released")

    def s61_media_group_sds(self) -> None:
        stamp = time.strftime("%H%M%S")
        # TS 24.282 §9.2.3: a group standalone SDS over the media plane (sds-media forces it; the group allows 400 bytes).
        text = f"Group media {stamp}"
        sent = self.cmd(A, "sds-media", f"group={GROUP}", text, "--disposition", "delivery")
        self.check("alice's group SDS over the media plane is delivered", sent["communication"]["state"] == "delivered", sent=sent)
        for c in (B, C):
            got = self.inbox_message(c, text)
            self.check(f"{c} receives it on {GROUP} over the media plane", got["plane"] == "media" and got["group"] == GROUP
                       and got["sender"] == MCDATA[A], got=got)
        for c in (B, C):
            self.notification_sent(c, sent["message_id"], "DELIVERED")
        # Above the group's 400 byte limit, the server discards it (TS 24.582 §6.3.1.3 step 2, 217): nobody receives it.
        big = f"Too big {stamp} " + "x" * 600
        self.cmd(A, "sds-media", f"group={GROUP}", big, ok=False)  # the server may end the leg before its 200
        time.sleep(2)
        self.check("an SDS above the group's limit is not delivered", not any(m["text"] == big for m in self.status(B)["inbox"]))

    def s62_one_to_one_session(self) -> None:
        stamp = time.strftime("%H%M%S")
        # TS 24.282 §9.2.4: alice opens a one-to-one SDS session with bob; both send in it.
        opened = self.cmd(A, "sds-session", MCDATA[B])
        self.check("alice's SDS session is open", opened["state"] == "open", session=opened)
        bob = self.last_media(B, "session", False, "bob's session open", lambda c: c["state"] == "open")
        self.check("bob's session is from alice", bob["peer"] == MCDATA[A])
        text = f"In session {stamp}"
        sent = self.cmd(A, "sds-session", "send", "delivery", text)
        got = self.inbox_message(B, text)
        self.check("bob receives it in the session", got["plane"] == "session" and got["sender"] == MCDATA[A], got=got)
        # TS 24.582 §6.1.2.5.1: bob's DELIVERED goes in the session.
        n = self.notifications(A, sent["message_id"], 1)
        self.check("alice learns bob delivered it (in the session)", n.get(MCDATA[B]) == "DELIVERED", notifications=n)
        self.check("bob sent it in the session, not by MESSAGE", any("in the SDS session" in e for e in self.cmd(B, "events", "80")))
        reply = f"Reply in session {stamp}"
        self.cmd(B, "sds-session", "send", reply)
        got = self.inbox_message(A, reply)
        self.check("alice receives bob's reply in the session", got["plane"] == "session" and got["sender"] == MCDATA[B], got=got)
        self.cmd(A, "sds-session", "close")
        self.last_media(B, "session", False, "bob's session released", lambda c: c["state"] == "released")
        self.check("closing it releases bob's leg too", True)

    def s63_group_session(self) -> None:
        stamp = time.strftime("%H%M%S")
        # TS 24.282 §9.2.4: a group SDS session; the server relays every message to the other legs (TS 24.582 §6.3.2.3).
        opened = self.cmd(A, "sds-session", f"group={GROUP}")
        self.check("alice's group SDS session is open", opened["state"] == "open", session=opened)
        for c in (B, C):
            leg = self.last_media(c, "session", False, f"{c}'s session open", lambda x: x["state"] == "open")
            self.check(f"{c} joins the session on {GROUP}", leg["group"] == GROUP and leg["peer"] == MCDATA[A], leg=leg)
        text = f"Group session {stamp}"
        self.cmd(A, "sds-session", "send", text)
        for c in (B, C):
            got = self.inbox_message(c, text)
            self.check(f"{c} receives alice's message in the session", got["plane"] == "session" and got["group"] == GROUP, got=got)
        reply = f"Bob to the group {stamp}"
        self.cmd(B, "sds-session", "send", reply)
        for c in (A, C):
            got = self.inbox_message(c, reply)
            self.check(f"{c} receives bob's message, bob named by the Sender IE", got["sender"] == MCDATA[B] and got["plane"] == "session", got=got)
        self.cmd(A, "sds-session", "close")
        for c in (B, C):
            self.last_media(c, "session", False, f"{c}'s session released", lambda x: x["state"] == "released", timeout=20)
        self.check("the session ends for everyone when alice, the originator, leaves", True)

    def s64_media_fd(self) -> None:
        # TS 24.282 §10.2.5: files over the media plane. Bob may send one-to-one without a limit (the service: 4000 bytes).
        local = Path("/tmp") / f"p4-media-{time.strftime('%H%M%S')}.bin"
        local.write_bytes(bytes((i * 31) % 256 for i in range(3500)))
        remote = f"/data/local/tmp/{local.name}"
        for c in (A, B):
            self.devices[c].adb("push", str(local), remote)
            self.devices[c].adb("shell", "chmod", "644", remote)
        import hashlib
        sha = hashlib.sha256(local.read_bytes()).hexdigest()
        sent = self.cmd(B, "fd-media", MCDATA[A], remote, "name=report.bin", "mandatory", "completed", timeout=60)
        self.check("bob's file over the media plane is sent", sent["state"] == "delivered", sent=sent)
        got = self.last_media(A, "fd", False, "alice receives the file", lambda c: c["state"] in ("received", "corrupt", "failed"), timeout=30)
        self.check("alice receives the mandatory file whole", got["state"] == "received" and got["sha256"] == sha and got["size"] == 3500, got=got)
        self.check("alice's copy is stored", bool(got["path"]))
        # Non-mandatory: the user decides (§10.2.5.2.4 7)); alice declines, then defers.
        self.cmd(A, "fd-media-policy", "ask")
        try:
            for decision, state in (("decline", "declined"), ("defer", "deferred")):
                # The server holds bob's INVITE until alice decides (TS 24.282 §10.2.5.3): bob's command waits.
                result: dict = {}
                sender = threading.Thread(target=lambda: result.update(self.cmd(B, "fd-media", MCDATA[A], remote, f"name={decision}.bin",
                                                                                ok=False, timeout=90)))
                sender.start()
                offer = self.last_media(A, "fd", False, f"alice is offered {decision}.bin", lambda c: c["state"] == "offered" and c["file"] == f"{decision}.bin")
                self.check(f"alice is asked about {decision}.bin", offer["size"] == 3500)
                self.cmd(A, "fd-media-answer", decision)
                done = self.last_media(A, "fd", False, f"{decision}.bin {state}", lambda c: c["file"] == f"{decision}.bin" and c["state"] == state)
                self.check(f"alice's {decision} is applied", done["state"] == state)
                sender.join(100)
                out = self.last_media(B, "fd", True, f"bob's {decision}.bin ends", lambda c: c["file"] == f"{decision}.bin" and c["state"] != "inviting")
                # The server takes alice's 480 (warning 110 or 231) and ends bob's INVITE with 487, the warning not passed on.
                self.check(f"bob's file is refused after alice's {decision}", out["state"] == "refused" and (out.get("status") or 0) >= 400, out=out)
        finally:
            self.cmd(A, "fd-media-policy", "accept", ok=False)
        # A group file, mandatory (the group takes 2000 bytes).
        small = local.with_name(local.stem + "-group.bin")
        small.write_bytes(b"g" * 1500)
        remote_small = f"/data/local/tmp/{small.name}"
        self.devices[A].adb("push", str(small), remote_small)
        self.devices[A].adb("shell", "chmod", "644", remote_small)
        sent = self.cmd(A, "fd-media", f"group={GROUP}", remote_small, "name=group.bin", "mandatory", timeout=60)
        self.check("alice's group file over the media plane is sent", sent["state"] == "delivered", sent=sent)
        for c in (B, C):
            got = self.last_media(c, "fd", False, f"{c} receives the group file", lambda x: x["file"] == "group.bin" and x["state"] in ("received", "corrupt"), timeout=30)
            self.check(f"{c} receives the group file on {GROUP}", got["state"] == "received" and got["group"] == GROUP and got["size"] == 1500, got=got)
        # FD over HTTP, released by its originator (TS 24.282 §6.2.2.4): the FD HTTP TERMINATION and the server's answer (§6.3.6.1).
        http = self.cmd(B, "fd-send", MCDATA[A], remote, "http.bin", "application/octet-stream", timeout=120)
        self.check("bob's file over HTTP is sent", http["state"] == "sent", http=http)
        self.cmd(B, "fd-terminate", http["id"])
        done = self.wait("bob's release answered", lambda: next((r for r in self.cmd(B, "fd-files") if r["id"] == http["id"]
                         and r.get("release") in ("terminated", "termination failed")), None), timeout=20)
        self.check("the server terminates bob's distribution", done["release"] == "terminated", record=done)

    def s65_media_priority(self) -> None:
        # TS 24.282 §6.2.8.1.1: an emergency group SDS session; §6.2.8.1.3: its cancel by re-INVITE. Needs --priority.
        opened = self.cmd(A, "sds-session", f"group={GROUP}", "emergency")
        self.check("alice's emergency group session is open", opened["state"] == "open" and opened["emergency"], session=opened)
        for c in (B, C):
            leg = self.last_media(c, "session", False, f"{c}'s session open", lambda x: x["state"] == "open")
            self.check(f"{c} learns the group's emergency", leg["emergency"], leg=leg)
        cancelled = self.cmd(A, "sds-session-priority", "emergency", "off")
        self.check("alice cancels the group's emergency", not cancelled["emergency"], session=cancelled)
        for c in (B, C):
            leg = self.last_media(c, "session", False, f"{c} learns the cancel", lambda x: x["state"] == "open" and not x["emergency"])
            self.check(f"{c} learns the emergency is cancelled", not leg["emergency"])
        # §6.2.8.1.9: the imminent peril of the group in the same session.
        r = self.cmd(A, "sds-session-priority", "imminent", "on")
        self.check("alice raises the group's imminent peril", r["imminent_peril"], session=r)
        self.cmd(A, "sds-session", "close")
        # TS 24.282 §6.2.8.3.2: an emergency one-to-one session with bob.
        opened = self.cmd(A, "sds-session", MCDATA[B], "emergency")
        self.check("alice's emergency one-to-one session is open", opened["emergency"], session=opened)
        leg = self.last_media(B, "session", False, "bob's emergency session", lambda x: x["state"] == "open")
        self.check("bob's session is an emergency one", leg["emergency"], leg=leg)
        self.cmd(A, "sds-session", "close")
        refused = self.cmd(A, "sds-session", MCDATA[B], "imminent", ok=False)
        self.check("imminent peril is refused for a one-to-one session", not refused.get("ok"), refused=refused)

    def s66_media_release(self) -> None:
        # TS 24.282 §13.2.6: bob (release_any_user) announces the release of alice's group session, asking for the
        # remaining data; alice answers (§13.2.4.2.1 step 2c) and asks for an extension, bob grants it; then bob releases it.
        self.cmd(A, "sds-session", f"group={GROUP}")
        self.last_media(B, "session", False, "bob's session open", lambda x: x["state"] == "open")
        self.last_media(C, "session", False, "client-c's session open", lambda x: x["state"] == "open")
        r = self.cmd(B, "mcdata-release", "intent", "query", "shift change")
        self.check("bob's intent to release is accepted", r["status"] == 200, result=r)
        a = self.last_media(A, "session", True, "alice is told of the release", lambda x: (x.get("release") or "").startswith("release pending"))
        self.check("alice learns the release is pending, with bob's reason", "shift change" in a["release"], release=a["release"])
        b = self.last_media(B, "session", False, "bob gets alice's remaining data", lambda x: (x.get("release") or "").startswith("remaining data"))
        self.check("bob learns alice's remaining data", b["release"] == "remaining data: 0", release=b["release"])
        r = self.cmd(A, "mcdata-release", "extend")
        self.check("alice's extension request is accepted", r["status"] == 200, result=r)
        self.last_media(B, "session", False, "bob is asked for an extension", lambda x: x.get("release") == "extension requested")
        self.cmd(B, "mcdata-release", "accept")
        a = self.last_media(A, "session", True, "alice's extension granted", lambda x: x.get("release") == "extended")
        self.check("alice's session is extended", a["state"] == "open")
        # Client-c may not release another user's communication: 403.
        refused = self.cmd(C, "mcdata-release", "now", ok=False)
        self.check("client-c's release is refused (403)", "403" in json.dumps(refused) or (refused.get("result") or {}).get("status") == 403, refused=refused)
        r = self.cmd(B, "mcdata-release", "now", "end of incident")
        self.check("bob's release is accepted", r["status"] == 200, result=r)
        for c in (A, C):
            self.last_media(c, "session", c == A, f"{c}'s leg released", lambda x: x["state"] == "released", timeout=20)
        self.check("every leg is released", True)

    def s67_media_reference_client(self) -> None:
        """Interoperation with the Linux client (--reference-client client-d), which listens where Android connects: it
        receives the Android client's media-plane SDS, session messages and files (it may not transmit in the fixture)."""
        refs = meta(self.name).get("reference_clients") or []
        if "client-d" not in refs:
            print("  [SKIP] S67 needs --reference-client client-d"); self.skipped.append("S67"); return
        from androidlab import RUNS, compose_bin
        status_file = RUNS / self.name / "fixture" / "artifacts" / "client-d" / "status.json"
        def received(text):
            return self.wait(f"client-d receives '{text}'", lambda: next((m for m in json.loads(status_file.read_text()).get("mcdata_media_received") or []
                             if any(p.get("text") == text for p in m.get("payloads", []))), None), timeout=15)
        stamp = time.strftime("%H%M%S")
        text = f"To the Linux client {stamp}"
        self.cmd(A, "sds-media", f"group={GROUP}", text)
        got = received(text)
        self.check("client-d (Linux) receives alice's media-plane group SDS", got["from"] == MCDATA[A] and got["group"] == GROUP, got=got)
        self.cmd(A, "sds-session", f"group={GROUP}")
        time.sleep(2)
        text = f"Session to the Linux client {stamp}"
        self.cmd(A, "sds-session", "send", text)
        got = received(text)
        self.check("client-d (Linux) receives alice's message in the group session", got["from"] == MCDATA[A], got=got)
        self.cmd(A, "sds-session", "close")
        local = Path("/tmp") / f"p4-ref-{stamp}.bin"
        local.write_bytes(b"r" * 1200)
        remote = f"/data/local/tmp/{local.name}"
        self.devices[A].adb("push", str(local), remote)
        self.devices[A].adb("shell", "chmod", "644", remote)
        self.cmd(A, "fd-media", f"group={GROUP}", remote, f"name=ref-{stamp}.bin", "mandatory", timeout=60)
        f = self.wait("client-d receives the file", lambda: next((x for x in json.loads(status_file.read_text()).get("mcdata_media_files") or []
                      if x.get("name") == f"ref-{stamp}.bin" and x.get("state") not in (None, "offered")), None), timeout=30)
        self.check("client-d (Linux) receives alice's group file whole", f.get("state") == "received" and f.get("hash_verified") is True, file=f)

    SCENARIOS = {"S1": s1_startup, "S2": s2_private_automatic, "S3": s3_private_manual, "S4": s4_group, "S5": s5_adhoc, "S6": s6_sds,
                 "S7": s7_refresh_and_recovery, "S8": s8_lifecycle, "S9": s9_impaired, "S10": s10_latency,
                 "S11": s11_product, "S12": s12_private_no_floor, "S13": s13_location,
                 "S14": s14_emergency_alerts,
                 "S15": s15_server_address,
                 "S16": s16_emergency_calls,
                 "S17": s17_functional_aliases,
                 "S18": s18_floor_queue,
                 "S19": s19_reg_event,
                 "S20": s20_implicit_floor, "S21": s21_service_settings, "S22": s22_monitor,
                 "S23": s23_remote_affiliation, "S24": s24_group_dynamic_data,
                 "V1": v1_transmission_control, "V2": v2_adhoc, "V3": v3_priority_extras, "V4": v4_settings_and_gdd, "V5": v5_kms,
                 "V6": v6_affiliation,
                 "S40": s40_location_aliases, "S41": s41_rules_affiliation, "S42": s42_group_mandatory_area,
                 "S43": s43_group_geographic_area, "S44": s44_emergency_alert_area, "S45": s45_seal_location,
                 "S25": s25_first_to_answer, "S26": s26_chat, "S27": s27_broadcast, "S28": s28_adhoc_criteria,
                 "S29": s29_floor_priority, "S30": s30_regroup, "S31": s31_temporary_group,
                 "S60": s60_long_sds, "S61": s61_media_group_sds, "S62": s62_one_to_one_session, "S63": s63_group_session,
                 "S64": s64_media_fd, "S65": s65_media_priority, "S66": s66_media_release, "S67": s67_media_reference_client}


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
                lab.check(f"{key} completed", False, error=f"KeyError: {e}")
                lab.leave_clean()
                continue
            # A phones-only lab has no third client: create the lab without --phones-only, so that redroid
            # client-c joins the phones (the C++ reference client does not take the debug control commands).
            print(f"  [SKIP] {key} needs {e.args[0]}, which this lab does not have (create it without --phones-only)")
            lab.skipped.append(key)
            lab.results = [r for r in lab.results if r["scenario"] != key]
            lab.leave_clean()
        except Failed as e:
            lab.check(f"{key} completed", False, error=str(e))
            lab.leave_clean()
        except Exception as e:
            # A scenario that crashes fails, and what it left is cleaned up; the run goes on to the next scenario.
            lab.check(f"{key} completed", False, error=f"{type(e).__name__}: {e}")
            lab.leave_clean()
    passed = sum(r["ok"] for r in lab.results)
    print(f"\n{passed}/{len(lab.results)} checks passed" + (f"; skipped {' '.join(lab.skipped)} (no client-c)" if lab.skipped else "")
          + (f"; partly skipped: {', '.join(lab.skipped_parts)}" if lab.skipped_parts else ""))
    out = Path(__file__).resolve().parents[1] / "lab" / "run" / lab.name / "android-scenarios.json"
    out.write_text(json.dumps(lab.results, indent=2) + "\n")
    sys.exit(0 if passed == len(lab.results) else 1)


if __name__ == "__main__":
    main()

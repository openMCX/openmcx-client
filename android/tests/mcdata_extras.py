#
# OpenMCX Licence Information : AGPL-3.0-or-later
#

"""Scenarios S80 onwards: the MCData procedures of parity phase 4b (IP connectivity, enhanced status, the message
store, protected MSRP bodies, ad hoc and functional alias media), run by tests/scenarios.py as the others:

    tests/scenarios.py LAB S80 S81 ...

Each scenario is a function of the scenarios.py Lab; it only uses the lab's cmd, status, wait and check.
"""
from __future__ import annotations

import time

A, B, C = "client-a", "client-b", "client-c"
MCDATA = {A: "sip:alice.mcdata@mcx.test", B: "sip:bob.mcdata@mcx.test", C: "sip:client-c.mcdata@mcx.test"}
GROUP = "sip:operations@mcx.test"
# The fixture's IP connectivity addresses (openmcx-server tools/provision.py, mcdata.ipconn.address).
IPCONN = {A: "10.77.0.1", B: "10.77.0.2", C: "10.77.0.3"}


def ipcon(lab, client: str) -> dict:
    return lab.cmd(client, "ipcon-status")


def s80_ip_connectivity(lab) -> None:
    """TS 24.282 clause 20.2.1 and 20.2.2, TS 24.582 clause 13: alice opens an IP connectivity session with bob; her
    ICMP echo requests cross the GRE-in-UDP tunnel the MCData server relays, bob's IP endpoint answers them, and his
    reach her; alice's BYE ends both legs."""
    for c in (A, B):
        if ipcon(lab, c)["state"] == "open":
            lab.cmd(c, "ipcon", "close", ok=False)
    s = lab.cmd(A, "ipcon", MCDATA[B], timeout=40)
    lab.check("alice's IP connectivity session is open", s["state"] == "open", session=s)
    b = lab.wait("bob's leg is open", lambda: (x := ipcon(lab, B))["state"] == "open" and x, timeout=15)
    lab.check("bob is told alice calls (clause 20.2.2 step 3)", b["from"] == MCDATA[A], session=b)
    lab.check("both tunnel ends are the MCData server's relay, not the other phone",
              s["peer"] != b["local"] and b["peer"] != s["local"], alice=s["peer"], bob=b["peer"])
    lab.cmd(A, "ipcon-ping", IPCONN[B], "3", "120")
    got = lab.wait("alice's three echo replies", lambda: len((x := ipcon(lab, A))["pings"]) >= 3 and x, timeout=15)
    lab.check("bob's IP endpoint answers alice's three pings through the tunnel",
              all(p["from"] == IPCONN[B] and p["bytes"] == 120 for p in got["pings"]), pings=got["pings"])
    lab.check("bob answered three echo requests", ipcon(lab, B)["echoes_answered"] >= 3)
    lab.cmd(B, "ipcon-ping", IPCONN[A], "2")
    back = lab.wait("bob's two echo replies", lambda: len((x := ipcon(lab, B))["pings"]) >= 2 and x, timeout=15)
    lab.check("alice answers bob's pings", all(p["from"] == IPCONN[A] for p in back["pings"]))
    lab.cmd(A, "ipcon", "close")
    end = lab.wait("bob's leg released", lambda: (x := ipcon(lab, B))["state"] == "closed" and x, timeout=15)
    lab.check("alice's BYE ends bob's leg", end["state"] == "closed")
    # TS 24.282 clause 20.3.2 (server): bob takes one-to-one communications from alice only, so client-c is refused.
    if C in lab.devices:
        r = lab.cmd(C, "ipcon", MCDATA[B], ok=False, timeout=40)
        lab.check("client-c's session with bob is refused 403 (bob receives from alice only)",
                  not r.get("ok") and "403" in r.get("error", ""), reply=r)


def s81_enhanced_status(lab) -> None:
    """TS 24.282 clause 14.2.1: the group's operational values from its document; alice sends "On scene" (id 1) as a
    group SDS of type ENHANCED STATUS, bob and client-c render it; an id the group does not know is discarded; a group
    without <mcdata-allow-enhanced-status> refuses the request before anything is sent."""
    values = lab.cmd(A, "sds-status-values", GROUP)
    lab.check("the group document allows enhanced status and has its operational values",
              values["allowed"] and {"1": "On scene", "2": "En route"}.items() <= {v["id"]: v["value"] for v in values["values"]}.items(),
              values=values)
    before = {c: len(lab.cmd(c, "sds-status-received")["received"]) for c in (B, C) if c in lab.devices}
    sent = lab.cmd(A, "sds-status", "delivery", GROUP, "1")
    lab.check("alice's enhanced status is accepted by the server", sent["status"] in (200, 202), sent=sent)
    for c in before:
        got = lab.wait(f"{c} renders it", lambda c=c: (r := lab.cmd(c, "sds-status-received")["received"][before[c]:]) and r[-1], timeout=15)
        lab.check(f"{c} renders alice's 'On scene' (clause 14.2.1.2 step 3)", got["value"] == "On scene" and got["from"] == MCDATA[A]
                  and got["group"] == GROUP, got=got)
    discarded = lab.cmd(B, "sds-status-received")["discarded"]
    inbox = len(lab.status(B)["inbox"])
    forced = lab.cmd(A, "sds-status", GROUP, "9", "force")
    lab.wait("bob discards id 9", lambda: lab.cmd(B, "sds-status-received")["discarded"] > discarded, timeout=15)
    lab.check("bob discards a value the group does not have, and does not deliver it",
              len(lab.status(B)["inbox"]) == inbox and all(m["message_id"] != forced["message_id"] for m in lab.status(B)["inbox"]))
    r = lab.cmd(A, "sds-status", "sip:operations-noemergency@mcx.test", "1", ok=False)
    lab.check("a group without <mcdata-allow-enhanced-status> refuses it locally (clause 14.2.1.1)", not r.get("ok")
              and "not allowed" in r.get("error", ""), reply=r)


def stored(lab, client: str, text: str):
    return next((o for o in lab.cmd(client, "msgstore", "status")["objects"] if o["text"] == text), None)


def s82_message_store(lab) -> None:
    """TS 24.282 clause 21.3.2.2: alice enables the storage of her private and group communications (client-c, without
    <allow-store-comms-in-msgstore>, is refused 403 warning 234); clause 21.2.19.1, 21.2.12A.1 and 21.2.22.1: her
    notification channel tells her of bob's SDS stored in her box; clause 21.2.1.1, 21.2.3.1, 21.2.4.1 and 21.2.17.1:
    she reads its attributes and payload, marks it seen and deletes it; then storage is disabled again."""
    stamp = time.strftime("%H%M%S")
    r = lab.cmd(A, "msgstore-ctrl", "enable", "all-private", "all-group")
    lab.check("alice's storage control is accepted (200)", r["status"] == 200, reply=r)
    if C in lab.devices:
        r = lab.cmd(C, "msgstore-ctrl", "enable", "all-private")
        lab.check("client-c may not store (403 warning 234)", r["status"] == 403 and "234" in (r.get("warning") or ""), reply=r)
    lab.cmd(A, "msgstore", "sync")
    if not lab.cmd(A, "msgstore", "status")["subscribed"]:
        lab.cmd(A, "msgstore", "subscribe")
    st = lab.cmd(A, "msgstore", "status")
    lab.check("alice's notification channel and subscription exist", st["subscribed"] and st["channel"] and st["subscription"], status=st)
    text = f"store me {stamp}"
    lab.cmd(B, "sds", MCDATA[A], text)
    o = lab.wait("the notification of the stored SDS", lambda: (x := stored(lab, A, text)) and x.get("from") and x, timeout=30)
    lab.check("alice is notified of bob's SDS stored in her box", o["notified"], obj=o)
    lab.check("the stored object says from bob, direction In", o["from"] == MCDATA[B] and o["direction"] == "In", obj=o)
    p = lab.cmd(A, "msgstore", "payload", o["id"])
    lab.check("its payload is retrieved with the text", p["status"] == 200 and p.get("text") == text, payload={k: p.get(k) for k in ("status", "bytes", "text")})
    lab.cmd(A, "msgstore", "flag", o["id"], "\\Seen")
    seen = lab.wait("the \\Seen flag", lambda: (x := stored(lab, A, text)) and "\\Seen" in x["flags"] and x, timeout=15)
    lab.check("alice marks it seen", "\\Seen" in seen["flags"])
    found = lab.cmd(A, "msgstore", "search", f"From={MCDATA[B]}")
    lab.check("a search by sender finds it", o["id"] in found, found=found)
    lab.cmd(A, "msgstore", "delete", o["id"])
    lab.cmd(A, "msgstore", "sync")
    lab.check("deleted, it is gone after a synchronisation", stored(lab, A, text) is None)
    lab.cmd(A, "msgstore", "unsubscribe")
    r = lab.cmd(A, "msgstore-ctrl", "disable", "all-private", "all-group")
    lab.check("alice disables storage again (200)", r["status"] == 200, reply=r)


SCENARIOS = {"S80": s80_ip_connectivity, "S81": s81_enhanced_status, "S82": s82_message_store}

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


def sealing(lab, client: str) -> tuple[int, int]:
    p = lab.cmd(client, "keys")["xml_protection"]
    return p["signalling_sealed"], p["signalling_opened"]


def s83_protected_msrp(lab) -> None:
    """TS 24.282 clause 6.6.3.2 with TS 33.180 clause 9.5.3 (a lab made with --protected): the MCData signalling bodies of
    the MSRP SENDs between each phone and the MCData server are sealed under the CSK, and opened: an SDS session's
    messages and its in-session notification, and a standalone SDS over the media plane, reach the other phone."""
    if not lab.protected:
        print("  [SKIP] S83 needs a protected lab (androidlab.py create --protected)"); lab.skipped.append("S83"); return
    stamp = time.strftime("%H%M%S")
    a0, b0 = sealing(lab, A), sealing(lab, B)
    lab.check("both phones protect their MCData signalling", lab.cmd(A, "keys")["xml_protection"]["signalling_protection"]
              and lab.cmd(B, "keys")["xml_protection"]["signalling_protection"])
    s = lab.cmd(A, "sds-session", MCDATA[B], timeout=40)
    lab.check("alice's SDS session with bob is open", s["state"] == "open", session=s)
    lab.wait("bob's leg open", lambda: any(c["kind"] == "session" and c["state"] == "open" for c in lab.status(B)["media"]), timeout=15)
    text = f"sealed {stamp}"
    sent = lab.cmd(A, "sds-session", "send", "delivery", text)
    got = lab.wait("bob receives it", lambda: next((m for m in lab.status(B)["inbox"] if m["text"] == text), None), timeout=15)
    lab.check("bob receives alice's message from the session (opened under his CSK)", got["plane"] == "session")
    note = lab.wait("alice's DELIVERED in the session", lambda: (m := next((m for m in lab.status(A)["outbox"]
                    if m["message_id"] == sent["message_id"]), None)) and m["notifications"] and m, timeout=15)
    lab.check("bob's in-session DELIVERED reaches alice (sealed by bob, opened by alice)", "DELIVERED" in note["notifications"].values(), note=note)
    lab.cmd(A, "sds-session", "close")
    long_text = f"{stamp} " + "sealed media " * 120
    lab.cmd(B, "sds", MCDATA[A], long_text, timeout=60)
    far = lab.wait("alice receives the long SDS", lambda: next((m for m in lab.status(A)["inbox"] if m["text"] == long_text), None), timeout=20)
    lab.check("bob's standalone SDS over the media plane reaches alice", far["plane"] == "media")
    a1, b1 = sealing(lab, A), sealing(lab, B)
    lab.check("alice sealed her session SEND and opened bob's notice and SDS", a1[0] > a0[0] and a1[1] >= a0[1] + 2, before=a0, after=a1)
    lab.check("bob opened alice's SEND and sealed his notice and SDS", b1[1] > b0[1] and b1[0] >= b0[0] + 2, before=b0, after=b1)


PATROL = "sip:patrol-car-12.fa@mcx.test"  # the MCData alias client-c (and client-d) may activate (--functional-alias)


def inbox(lab, client: str, text: str):
    return lab.wait(f"{client} receives '{text[:30]}'", lambda: next((m for m in lab.status(client)["inbox"] if m["text"] == text), None), timeout=20)


def s84_adhoc_and_alias_media(lab) -> None:
    """TS 24.282 clause 9.2.2.2.1 step 3A and clause 24.2.2.1.1: ad hoc group SDS on the signalling plane and over the
    media plane (standalone, session) and an ad hoc group FD over the media plane, the participants listed, the ad hoc
    group the server creates learned from its 2xx; clause 22 with 9.2.2.2.1 step 3A b ii: a functional alias among the
    participants reaches the user who activated it, told the alias called. A one-to-one media-plane communication to an
    alias goes to the server with <call-to-functional-alias-ind>; its 300 names the user to invite (clause 9.2.3.4.4)."""
    if C not in lab.devices:
        print("  [SKIP] S84 needs client-c"); lab.skipped.append("S84"); return
    stamp = time.strftime("%H%M%S")
    who = f"{MCDATA[B]},{MCDATA[C]}"
    text = f"adhoc signalling {stamp}"
    sent = lab.cmd(A, "sds-adhoc", who, text)
    lab.check("alice's ad hoc group SDS is accepted, its ad hoc group named by the server", sent["status"] in (200, 202)
              and sent["group"].startswith("sip:adhoc-"), sent=sent)
    for c in (B, C):
        m = inbox(lab, c, text)
        lab.check(f"{c} receives it on the ad hoc group", m["group"] == sent["group"] and m["sender"] == MCDATA[A], got=m)
    text = f"adhoc media {stamp} " + "x" * 1100
    lab.cmd(A, "sds-media", f"adhoc={who}", text, timeout=60)
    out = lab.wait("alice's ad hoc media SDS", lambda: next((x for x in reversed(lab.status(A)["media"]) if x["kind"] == "sds" and x["adhoc"]), None))
    lab.check("alice's ad hoc standalone SDS over the media plane is delivered, the ad hoc group learned",
              out["state"] == "delivered" and (out["group"] or "").startswith("sip:adhoc-"), communication=out)
    for c in (B, C):
        m = inbox(lab, c, text)
        lab.check(f"{c} receives it over the media plane", m["plane"] == "media" and m["group"] == out["group"], got={k: m[k] for k in ("plane", "group")})
    s = lab.cmd(A, "sds-session", f"adhoc={who}", timeout=40)
    lab.check("alice's ad hoc SDS session is open", s["state"] == "open" and s["adhoc"], session=s)
    for c in (B, C):
        lab.wait(f"{c} joins", lambda c=c: any(x["kind"] == "session" and x["state"] == "open" and x["adhoc"] for x in lab.status(c)["media"]), timeout=15)
    text = f"adhoc session {stamp}"
    lab.cmd(A, "sds-session", "send", text)
    for c in (B, C):
        lab.check(f"{c} receives alice's session message", inbox(lab, c, text)["plane"] == "session")
    lab.cmd(A, "sds-session", "close", "all")
    # The ad hoc group FD over the media plane.
    import tempfile, pathlib
    local = pathlib.Path(tempfile.gettempdir()) / f"p4b-adhoc-{stamp}.bin"
    local.write_bytes(b"a" * 900)
    remote = f"/data/local/tmp/{local.name}"
    lab.devices[A].adb("push", str(local), remote)
    lab.devices[A].adb("shell", "chmod", "644", remote)
    f = lab.cmd(A, "fd-media", f"adhoc={who}", remote, "name=adhoc.bin", "mandatory", timeout=60)
    lab.check("alice's ad hoc group file is sent", f["state"] == "delivered", file=f)
    for c in (B, C):
        got = lab.wait(f"{c} receives the file", lambda c=c: next((x for x in lab.status(c)["media"] if x["kind"] == "fd" and x["file"] == "adhoc.bin"
                       and x["state"] in ("received", "corrupt")), None), timeout=30)
        lab.check(f"{c} receives the ad hoc group file whole", got["state"] == "received" and got["size"] == 900 and got["adhoc"], got=got)
    # A functional alias among the participants (client-c activates patrol-car-12).
    lab.cmd(C, "mcdata-fa", "activate", PATROL, ok=False)
    lab.wait("client-c's alias active", lambda: PATROL in lab.status(C)["mcdata_functional_aliases"]["active"], timeout=20)
    text = f"to the patrol car {stamp} " + "y" * 1100
    lab.cmd(A, "sds-media", f"adhoc={PATROL}", text, timeout=60)
    m = inbox(lab, C, text)
    lab.check("the alias in an ad hoc list reaches client-c, told the alias called", m["called_alias"] == PATROL and m["plane"] == "media", got=m)
    # TS 24.282 §9.2.3.4.4 step 6a1: the server redirects a one-to-one media-plane SDS to an alias with 300, naming
    # the user who activated it; alice's app invites client-c, the alias called (fixed on openmcx-server branch
    # android-server-faults: it was refused 403 warning 229, the alias taken for a user).
    text = f"one-to-one to the alias {stamp} " + "z" * 1100
    r = lab.cmd(A, "sds-media", f"fa={PATROL}", text, ok=False, timeout=60)
    one = lab.wait("alice's alias communication ends", lambda: next((x for x in reversed(lab.status(A)["media"]) if x["kind"] == "sds" and not x["adhoc"]), None))
    lab.check("a one-to-one media-plane SDS to an alias is redirected (300) to client-c and delivered",
              one["state"] == "delivered" and one.get("peer") == MCDATA[C] and one.get("called_alias") == PATROL,
              reply=r, communication={k: one.get(k) for k in ("state", "status", "warning", "peer", "called_alias")})
    m = inbox(lab, C, text)
    # After the 300 the server passes alice's <called-functional-alias-URI> on in client-c's INVITE (openmcx-server
    # branch android-server-faults).
    lab.check("client-c receives it over the media plane, from alice, told the alias called",
              m["plane"] == "media" and m["sender"] == MCDATA[A] and m.get("called_alias") == PATROL,
              got={k: m.get(k) for k in ("plane", "sender", "called_alias")})
    lab.cmd(C, "mcdata-fa", "deactivate", PATROL, ok=False)


SCENARIOS = {"S80": s80_ip_connectivity, "S81": s81_enhanced_status, "S82": s82_message_store, "S83": s83_protected_msrp,
             "S84": s84_adhoc_and_alias_media}

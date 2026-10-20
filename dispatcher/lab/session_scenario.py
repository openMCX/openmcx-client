#!/usr/bin/env python3
#
# OpenMCX Licence Information : AGPL-3.0-or-later
#

"""The dispatcher's SDS sessions (TS 24.282 clause 9.2.4) and enhanced status (TS 24.282 clause 14.2), on a running lab
prepared with --mcdata (see prepare.py, which lets the dispatcher start ad hoc group communications):

  DSP-49 a one-to-one SDS session, both ways, closed    DSP-51 an ad hoc group SDS session, closed for all
  DSP-50 a group SDS session                             DSP-52 enhanced status, both ways

Drives the dispatcher through its web API, as the page does, and checks the other clients' status files.

  python3 lab/session_scenario.py <lab> --project <project> [--url http://127.0.0.1:18080]
"""
import argparse
import json
import sys
import uuid
from pathlib import Path

from scenario import Lab, check, results, wait


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("lab", type=Path)
    parser.add_argument("--project", required=True)
    parser.add_argument("--url", default="http://127.0.0.1:18080")
    args = parser.parse_args()
    lab = Lab(args.lab, args.project, args.url)
    wait(lambda: lab.state().get("registered") and lab.state().get("mcdata_authorized"), 90)
    a, b = (json.loads((args.lab / "generated" / f"{role}.json").read_text())["mcdata"]["user_id"]
            for role in ("client-a", "client-b"))
    group = json.loads((args.lab / "generated" / "site.json").read_text())["groups"][0]["id"]

    def received(role, text):
        status = lab.state() if role == "dispatcher" else lab.client(role)
        return any(text in [p.get("text") for p in m.get("payloads", [])] for m in status.get("mcdata_media_received") or [])

    def opened():
        return lab.state().get("mcdata_media_session") and (lab.state().get("mcdata_media_result") or {}).get("state") == "open"

    def closed(*roles):
        return wait(lambda: not lab.state().get("mcdata_media_session") and
                    all(not lab.client(r).get("mcdata_media_session") for r in roles), 20)

    word = uuid.uuid4().hex[:8]

    # DSP-49: a one-to-one SDS session with client-a (TS 24.282 clause 9.2.4.2.3); each side sends into it, and the
    # dispatcher closes it (clause 13.2.2.2).
    lab.act(action="sds-session", op="open", target=a)
    ok = wait(lambda: opened() and lab.client("client-a").get("mcdata_media_session"), 20)
    lab.act(action="sds-session", op="send", text=f"{word} to a")
    ok = ok and wait(lambda: received("client-a", f"{word} to a"), 15)
    lab.tell("client-a", f"sds-session send {word} to dispatcher")
    ok = ok and wait(lambda: received("dispatcher", f"{word} to dispatcher"), 15)
    lab.act(action="sds-session", op="close")
    ok = ok and closed("client-a")
    check("DSP-49 a one-to-one SDS session with client-a: messages both ways, then closed", ok,
          str(lab.state().get("mcdata_media_result")))

    # DSP-50: a group SDS session on the lab's MCData group; client-b gets the message.
    lab.act(action="sds-session", op="open", target=group, group=True)
    ok = wait(opened, 20)
    lab.act(action="sds-session", op="send", text=f"{word} to the group")
    ok = ok and wait(lambda: received("client-b", f"{word} to the group"), 15)
    lab.act(action="sds-session", op="close")
    ok = ok and closed()
    check("DSP-50 a group SDS session: client-b gets the message", ok, str(lab.state().get("mcdata_media_result")))

    # DSP-51: an ad hoc group SDS session with client-a and client-b (TS 24.282 clause 24.2.2.1.1), closed for
    # every participant (clause 13.2.2.2.4.3 step 3A).
    lab.act(action="sds-session", op="open", adhoc=True, targets=[a, b])
    ok = wait(lambda: opened() and lab.state().get("mcdata_media_result", {}).get("group"), 20)
    lab.act(action="sds-session", op="send", text=f"{word} ad hoc")
    ok = ok and wait(lambda: received("client-a", f"{word} ad hoc") and received("client-b", f"{word} ad hoc"), 15)
    lab.act(action="sds-session", op="close", all=True)
    ok = ok and closed("client-a", "client-b")
    check("DSP-51 an ad hoc group SDS session with client-a and client-b, closed for all", ok,
          str(lab.state().get("mcdata_media_result")))

    # DSP-52: the group's operational values read (TS 24.282 clause 14.2.1.1); the dispatcher sends one, client-a
    # sees it, and client-b's status reaches the dispatcher.
    lab.act(action="status-values", group=group)
    values = {}
    if wait(lambda: (lab.state().get("mcdata_enhanced_status_values") or {}).get(group), 15):
        values = {v["id"]: v["value"] for v in lab.state()["mcdata_enhanced_status_values"][group]["values"]}
    first, second = sorted(values)[:2] if len(values) >= 2 else ("", "")

    def status(role, value, sender):
        entries = (lab.state() if role == "dispatcher" else lab.client(role)).get("mcdata_enhanced_status") or []
        return any(e.get("value") == value and e.get("from") == sender for e in entries)

    me = lab.state().get("identity", {}).get("mcdata_user")
    ok = bool(first)
    if ok:
        lab.act(action="status", group=group, value=second)
        lab.tell("client-b", f"sds-status {group} {first}")
        ok = wait(lambda: status("client-a", values[second], me) and status("dispatcher", values[first], b), 20)
    check("DSP-52 enhanced status: the group's values read, one sent, client-b's received", ok,
          f"values={values} a={lab.client('client-a').get('mcdata_enhanced_status')}")

    failed = [n for n, ok in results if not ok]
    print(f"{len(results) - len(failed)}/{len(results)} passed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()

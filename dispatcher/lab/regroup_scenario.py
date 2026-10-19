#!/usr/bin/env python3
#
# OpenMCX Licence Information : AGPL-3.0-or-later
#

"""The dispatcher's regroups (TS 24.379 clause 16) and temporary groups (TS 24.481 clause 6.3.14), on a running lab
prepared with --regroup (see prepare.py, which gives the dispatcher, client-c, both rights). The lab's constituent
groups: north (client-a, client-b) and south (client-c, client-d); its preconfigured group gives a regroup its
configuration.

  DSP-31 a group regroup created, and its members told    DSP-34 a user regroup, called and removed
  DSP-32 a group call on the regroup reaches both groups  DSP-35 a temporary group formed, called and deleted
  DSP-33 the regroup removed

Drives the dispatcher through its web API, as the page does, and checks the other clients' status files.

  python3 lab/regroup_scenario.py <lab> --project <project> [--url http://127.0.0.1:18080]
"""
import argparse
import json
import sys
from pathlib import Path

from scenario import Lab, check, results, wait


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("lab", type=Path)
    parser.add_argument("--project", required=True)
    parser.add_argument("--url", default="http://127.0.0.1:18080")
    args = parser.parse_args()
    lab = Lab(args.lab, args.project, args.url)
    wait(lambda: lab.state().get("registered") and lab.state().get("affiliated"), 90)
    a, b = (lab.client(role).get("user_id") for role in ("client-a", "client-b"))
    site = json.loads((args.lab / "generated" / "site.json").read_text())
    groups = {g["id"] for g in site["groups"]}
    preconf = next(g["id"] for g in site["groups"] if g.get("preconfigured_use_only") and g.get("type") != "chat")
    north = next(g for g in groups if g.endswith("-north@" + g.split("@")[1]))
    south = next(g for g in groups if g.endswith("-south@" + g.split("@")[1]))
    prefix, domain = north.rsplit("-north@", 1)
    tgi, user_tgi, temp = (f"{prefix}-{name}@{domain}" for name in ("regroup-7", "regroup-8", "temp-7"))
    own = lab.state().get("identity", {}).get("group")

    def regroups(role):
        status = lab.state() if role == "dispatcher" else lab.client(role)
        return status.get("regroups") or {}

    def result(status_code):
        return (lab.state().get("regroup_result") or {}).get("status") == status_code

    def group_call(target, *roles):
        """The dispatcher selects the group and calls; every role joins."""
        lab.act(action="group", id=target)
        wait(lambda: lab.state().get("identity", {}).get("group") == target and lab.state().get("affiliated"), 20)
        lab.act(action="call")
        ok = wait(lambda: lab.state().get("call_state") == "active" and
                  all(lab.client(r).get("call_state") == "active" for r in roles), 25)
        lab.act(action="hangup")
        wait(lambda: lab.state().get("call_state", "idle") == "idle" and
             all(lab.client(r).get("call_state", "idle") == "idle" for r in roles), 20)
        lab.act(action="group", id=own)
        wait(lambda: lab.state().get("identity", {}).get("group") == own, 20)
        return ok

    # DSP-31: a group regroup of north and south using the preconfigured group (TS 24.379 clause 16.2.1.1); the
    # affiliated members of both are told (clause 16.2.1.3).
    lab.act(action="regroup", op="create", tgi=tgi, preconfigured=preconf, members=[north, south])
    ok = wait(lambda: result(200) and all(tgi in regroups(r) for r in ("dispatcher", "client-a", "client-d")), 20)
    check("DSP-31 the dispatcher regroups north and south; members of both are told", ok,
          f"{lab.state().get('regroup_result')} a={list(regroups('client-a'))}")

    # DSP-32: a group call on the regroup reaches a member of each constituent group.
    check("DSP-32 a group call on the regroup reaches client-a (north) and client-d (south)",
          group_call(tgi, "client-a", "client-d"), str(lab.state().get("last_call_failure")))

    # DSP-33: the regroup removed (clause 16.2.1.2); the members are told (clause 16.2.1.4).
    lab.act(action="regroup", op="remove", tgi=tgi)
    ok = wait(lambda: result(200) and all(tgi not in regroups(r) for r in ("dispatcher", "client-a", "client-d")), 20)
    check("DSP-33 the regroup removed; its members are told", ok, str(lab.state().get("regroup_result")))

    # DSP-34: a user regroup of the dispatcher, client-a and client-b (clause 16.3.1.1; its members are the users
    # listed), called, then removed.
    me = lab.state().get("identity", {}).get("user")
    lab.act(action="regroup", op="users", tgi=user_tgi, preconfigured=preconf, members=[me, a, b])
    ok = wait(lambda: result(200) and all(user_tgi in regroups(r) for r in ("dispatcher", "client-a", "client-b")), 20)
    check("DSP-34a the dispatcher regroups itself, client-a and client-b; all are told", ok,
          str(lab.state().get("regroup_result")))
    check("DSP-34b a group call on the user regroup reaches client-a and client-b",
          group_call(user_tgi, "client-a", "client-b"), str(lab.state().get("last_call_failure")))
    lab.act(action="regroup", op="remove", tgi=user_tgi)
    check("DSP-34c the user regroup removed", wait(lambda: result(200) and user_tgi not in regroups("client-a"), 20),
          str(lab.state().get("regroup_result")))

    # DSP-35: a temporary group of north and south formed at the group management server (TS 24.481 clause
    # 6.3.14.2), called, then torn down (clause 6.3.15.2).
    lab.act(action="temporary", op="create", tgi=temp, groups=[north, south])
    ok = wait(lambda: (lab.state().get("xcap_last") or {}).get("status") == 200 and
              all(temp in regroups(r) for r in ("dispatcher", "client-a", "client-d")), 25)
    check("DSP-35a the dispatcher forms a temporary group of north and south; members of both are told", ok,
          str(lab.state().get("xcap_last"))[:300])
    check("DSP-35b a group call on the temporary group reaches client-a and client-d",
          group_call(temp, "client-a", "client-d"), str(lab.state().get("last_call_failure")))
    lab.act(action="temporary", op="delete", tgi=temp)
    ok = wait(lambda: (lab.state().get("xcap_last") or {}).get("method") == "DELETE" and
              (lab.state().get("xcap_last") or {}).get("status") == 200 and temp not in regroups("client-a"), 25)
    check("DSP-35c the temporary group torn down; its members are told", ok, str(lab.state().get("xcap_last"))[:300])

    failed = [n for n, ok in results if not ok]
    print(f"{len(results) - len(failed)}/{len(results)} passed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
